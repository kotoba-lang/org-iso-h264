(ns h264.cabac
  "H.264 CABAC (Context-Adaptive Binary Arithmetic Coding) entropy decode
   (ITU-T H.264 / ISO/IEC 14496-10 §9.3). Scope: **I-slice / Intra_16x16
   ONLY** (main/high-profile CABAC support, ADR-2607122000 follow-on to the
   CAVLC-only \"R0.5\" decoder — see `h264.decode`'s namespace docstring for
   the combined scope statement). CABAC + P-slice/inter prediction, CABAC +
   I_NxN/I_PCM/8x8-transform, and CABAC ENCODE are all explicitly out of
   scope for this increment — this namespace only ever decodes.

   ## Why these tables are transcribed from Cisco's OpenH264, not FFmpeg

   FFmpeg's own `libavcodec/cabac.c`/`h264_cabac.c` implement a heavily
   byte-buffered/table-driven variant of the arithmetic decoding engine
   (a combined-state `mlps_state` trick, `norm_shift` lookup tables driven
   by 16-bit buffered reads) that is faster but NOT a literal transcription
   of the spec's own per-bit pseudocode (§9.3.3.2) — reverse-engineering
   FFmpeg's bit-packed encoding of `transIdxLPS`/`transIdxMPS` risked a
   transcription error that would be very hard to detect (a subtly wrong
   *state machine* still runs and produces plausible-looking-but-wrong
   output, unlike a wrong bit COUNT which usually desyncs loudly). OpenH264
   (Cisco)'s decoder (`codec/common/src/common_tables.cpp`,
   `codec/decoder/core/src/{cabac_decoder,parse_mb_syn_cabac}.cpp`,
   https://github.com/cisco/openh264) implements the SAME literal per-bit
   engine this namespace does (separate `pStateIdx`/`valMPS`, a straight
   `rangeTabLPS[64][4]` + `transIdxLPS[64]`/`transIdxMPS[64]` table, one
   `RenormD`-equivalent bit at a time) — its context-index constants
   (`NEW_CTX_OFFSET_*`) and per-syntax-element binarization/context-
   derivation functions were used directly as the porting reference for
   this namespace, and were cross-checked against FFmpeg's OWN
   `ff_h264_cabac_tables` (`libavcodec/cabac.c`) by decoding FFmpeg's
   packed/duplicated-state encoding back out and confirming the two
   sources agree on `rangeTabLPS`/`transIdxLPS`/`transIdxMPS` bit-for-bit
   (both give pStateIdx=0 → {128,176,208,240}/transIdxLPS=0/transIdxMPS=1,
   pStateIdx=63 → {2,2,2,2}/transIdxLPS=transIdxMPS=63, matching the well-
   known published H.264 Table 9-44/9-45 values). `range-tab-lps`/
   `trans-idx-lps`/`trans-idx-mps`/`context-init-i` below were extracted
   PROGRAMMATICALLY (a small parser over the fetched OpenH264 C source,
   not hand-transcribed) to eliminate manual-transcription risk for the
   464-row context-init table in particular.

   ## Context-init scope: I-slice column only

   `context-init-i` is ONLY the `cabac_init_idc`-independent I-slice column
   of OpenH264's combined `g_kiCabacGlobalContextIdx[460][4][2]` table (its
   own comment: \"this table is from Table9-12 to Table 9-24\", i.e. ITU-T
   H.264 Tables 9-12..9-24's (m,n) context-init parameters, §9.3.1.1). The
   other 3 columns (`cabac_init_idc` 0/1/2, used only for P/B/SP slices) are
   NOT extracted — out of scope, since this namespace never decodes a P/B
   slice. Many entries in the I-slice column are `nil` (OpenH264's `CTX_NA`
   sentinel) because they're P/B-only contexts (`mb_skip_flag`, P/B
   `mb_type`/`sub_mb_type`, `mvd`, `ref_idx` — see the `NEW_CTX_OFFSET_*`
   constants below); none of the context indices this namespace actually
   uses (mb_type-I, intra_chroma_pred_mode, mb_qp_delta, coded_block_flag,
   significant_coeff_flag, last_significant_coeff_flag,
   coeff_abs_level_minus1's greater1/abs-remainder) fall in the `nil`
   region, so this is never actually indexed.

   ## Syntax elements NOT implemented (throws or simply never called)

   `coded_block_pattern` (context offset 73, `NEW_CTX_OFFSET_CBP`) is NOT
   implemented: unlike I_NxN/inter mb_types (out of scope, see `h264.decode`),
   an Intra_16x16 macroblock's CodedBlockPattern is fully INFERRED from its
   `mb_type` value (Table 7-11 — `h264.decode/i16x16-mb-info`), the same as
   the existing CAVLC path — no separate `coded_block_pattern` syntax
   element is read for Intra_16x16 macroblocks regardless of entropy mode
   (§7.3.5.1's own `if (CodedBlockPatternLuma>0 || ... || MbPartPredMode==
   Intra_16x16)` gate for `mb_qp_delta`/`residual()` presence — note the
   explicit `MbPartPredMode==Intra_16x16` disjunct — confirms `mb_qp_delta`
   is likewise unconditional for Intra_16x16, matching the existing CAVLC
   code's own unconditional `(eg/se! r)` read)."
  (:require [h264.expgolomb :as eg]))

;; --- Arithmetic decoding engine tables (§9.3.3.2, Tables 9-44/9-45) ---
;; Programmatically extracted from Cisco OpenH264's
;; codec/common/src/common_tables.cpp (`g_kuiCabacRangeLps`,
;; `g_kuiStateTransTable`) — see namespace docstring for cross-check
;; methodology against FFmpeg's independently-encoded `ff_h264_cabac_tables`.

(def range-tab-lps
  "Table 9-44: rangeTabLPS[pStateIdx][qCodIRangeIdx] (64 rows x 4 cols)."
  [[128 176 208 240] [128 167 197 227] [128 158 187 216] [123 150 178 205]
   [116 142 169 195] [111 135 160 185] [105 128 152 175] [100 122 144 166]
   [95 116 137 158] [90 110 130 150] [85 104 123 142] [81 99 117 135]
   [77 94 111 128] [73 89 105 122] [69 85 100 116] [66 80 95 110]
   [62 76 90 104] [59 72 86 99] [56 69 81 94] [53 65 77 89]
   [51 62 73 85] [48 59 69 80] [46 56 66 76] [43 53 63 72]
   [41 50 59 69] [39 48 56 65] [37 45 54 62] [35 43 51 59]
   [33 41 48 56] [32 39 46 53] [30 37 43 50] [29 35 41 48]
   [27 33 39 45] [26 31 37 43] [24 30 35 41] [23 28 33 39]
   [22 27 32 37] [21 26 30 35] [20 24 29 33] [19 23 27 31]
   [18 22 26 30] [17 21 25 28] [16 20 23 27] [15 19 22 25]
   [14 18 21 24] [14 17 20 23] [13 16 19 22] [12 15 18 21]
   [12 14 17 20] [11 14 16 19] [11 13 15 18] [10 12 15 17]
   [10 12 14 16] [9 11 13 15] [9 11 12 14] [8 10 12 14]
   [8 9 11 13] [7 9 11 12] [7 9 10 12] [7 8 10 11]
   [6 8 9 11] [6 7 9 10] [6 7 8 9] [2 2 2 2]])

(def trans-idx-lps
  "Table 9-45, transIdxLPS(pStateIdx) — 64 entries."
  [0 0 1 2 2 4 4 5 6 7 8 9 9 11 11 12 13 13 15 15 16 16 18 18 19 19 21 21 22
   22 23 24 24 25 26 26 27 27 28 29 29 30 30 30 31 32 32 33 33 33 34 34 35
   35 35 36 36 36 37 37 37 38 38 63])

(def trans-idx-mps
  "Table 9-45, transIdxMPS(pStateIdx) — 64 entries."
  [1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24 25 26 27
   28 29 30 31 32 33 34 35 36 37 38 39 40 41 42 43 44 45 46 47 48 49 50 51
   52 53 54 55 56 57 58 59 60 61 62 62 63])

(def context-init-i
  "I-slice column of Tables 9-12..9-24 ((m,n) context-init parameters,
   §9.3.1.1), 460 entries indexed by absolute ctxIdx; `nil` = P/B-only
   context (never indexed by this namespace's I-slice-only scope)."
  [[20 -15] [2 54] [3 74] [20 -15] [2 54] [3 74] [-28 127] [-23 104] [-6 53] [-1 54] [7 51] nil nil nil nil nil nil nil nil nil nil nil nil nil nil nil nil nil nil nil nil nil nil nil nil nil nil nil nil nil nil nil nil nil nil nil nil nil nil nil nil nil nil nil nil nil nil nil nil nil [0 41] [0 63] [0 63] [0 63] [-9 83] [4 86] [0 97] [-7 72] [13 41] [3 62] [0 11] [1 55] [0 69] [-17 127] [-13 102] [0 82] [-7 74] [-21 107] [-27 127] [-31 127] [-24 127] [-18 95] [-27 127] [-21 114] [-30 127] [-17 123] [-12 115] [-16 122] [-11 115] [-12 63] [-2 68] [-15 84] [-13 104] [-3 70] [-8 93] [-10 90] [-30 127] [-1 74] [-6 97] [-7 91] [-20 127] [-4 56] [-5 82] [-7 76] [-22 125] [-7 93] [-11 87] [-3 77] [-5 71] [-4 63] [-4 68] [-12 84] [-7 62] [-7 65] [8 61] [5 56] [-2 66] [1 64] [0 61] [-2 78] [1 50] [7 52] [10 35] [0 44] [11 38] [1 45] [0 46] [5 44] [31 17] [1 51] [7 50] [28 19] [16 33] [14 62] [-13 108] [-15 100] [-13 101] [-13 91] [-12 94] [-10 88] [-16 84] [-10 86] [-7 83] [-13 87] [-19 94] [1 70] [0 72] [-5 74] [18 59] [-8 102] [-15 100] [0 95] [-4 75] [2 72] [-11 75] [-3 71] [15 46] [-13 69] [0 62] [0 65] [21 37] [-15 72] [9 57] [16 54] [0 62] [12 72] [24 0] [15 9] [8 25] [13 18] [15 9] [13 19] [10 37] [12 18] [6 29] [20 33] [15 30] [4 45] [1 58] [0 62] [7 61] [12 38] [11 45] [15 39] [11 42] [13 44] [16 45] [12 41] [10 49] [30 34] [18 42] [10 55] [17 51] [17 46] [0 89] [26 -19] [22 -17] [26 -17] [30 -25] [28 -20] [33 -23] [37 -27] [33 -23] [40 -28] [38 -17] [33 -11] [40 -15] [41 -6] [38 1] [41 17] [30 -6] [27 3] [26 22] [37 -16] [35 -4] [38 -8] [38 -3] [37 3] [38 5] [42 0] [35 16] [39 22] [14 48] [27 37] [21 60] [12 68] [2 97] [-3 71] [-6 42] [-5 50] [-3 54] [-2 62] [0 58] [1 63] [-2 72] [-1 74] [-9 91] [-5 67] [-5 27] [-3 39] [-2 44] [0 46] [-16 64] [-8 68] [-10 78] [-6 77] [-10 86] [-12 92] [-15 55] [-10 60] [-6 62] [-4 65] [-12 73] [-8 76] [-7 80] [-9 88] [-17 110] [-11 97] [-20 84] [-11 79] [-6 73] [-4 74] [-13 86] [-13 96] [-11 97] [-19 117] [-8 78] [-5 33] [-4 48] [-2 53] [-3 62] [-13 71] [-10 79] [-12 86] [-13 90] [-14 97] nil [-6 93] [-6 84] [-8 79] [0 66] [-1 71] [0 62] [-2 60] [-2 59] [-5 75] [-3 62] [-4 58] [-9 66] [-1 79] [0 71] [3 68] [10 44] [-7 62] [15 36] [14 40] [16 27] [12 29] [1 44] [20 36] [18 32] [5 42] [1 48] [10 62] [17 46] [9 64] [-12 104] [-11 97] [-16 96] [-7 88] [-8 85] [-7 85] [-9 85] [-13 88] [4 66] [-3 77] [-3 76] [-6 76] [10 58] [-1 76] [-1 83] [-7 99] [-14 95] [2 95] [0 76] [-5 74] [0 70] [-11 75] [1 68] [0 65] [-14 73] [3 62] [4 62] [-1 68] [-13 75] [11 55] [5 64] [12 70] [15 6] [6 19] [7 16] [12 14] [18 13] [13 11] [13 15] [15 16] [12 23] [13 23] [15 20] [14 26] [14 44] [17 40] [17 47] [24 17] [21 21] [25 22] [31 27] [22 29] [19 35] [14 50] [10 57] [7 63] [-2 77] [-4 82] [-3 94] [9 69] [-12 109] [36 -35] [36 -34] [32 -26] [37 -30] [44 -32] [34 -18] [34 -15] [40 -15] [33 -7] [35 -5] [33 0] [38 2] [33 13] [23 35] [13 58] [29 -3] [26 0] [22 30] [31 -7] [35 -15] [34 -3] [34 3] [36 -1] [34 5] [32 11] [35 5] [34 12] [39 11] [30 29] [34 26] [29 39] [19 66] [31 21] [31 31] [25 50] [-17 120] [-20 112] [-18 114] [-11 85] [-15 92] [-14 89] [-26 71] [-15 81] [-14 80] [0 68] [-14 70] [-24 56] [-23 68] [-24 50] [-11 74] [23 -13] [26 -13] [40 -15] [49 -14] [44 3] [45 6] [44 34] [33 54] [19 82] [-3 75] [-1 23] [1 34] [1 43] [0 54] [-2 55] [0 61] [1 64] [0 68] [-9 92] [-14 106] [-13 97] [-15 90] [-12 90] [-18 88] [-10 73] [-9 79] [-14 86] [-10 73] [-10 70] [-10 69] [-5 66] [-9 64] [-5 58] [2 59] [21 -10] [24 -11] [28 -8] [28 -1] [29 3] [29 9] [35 20] [29 36] [14 67]])

;; --- Absolute ctxIdx offsets (§Table 9-11) for the syntax elements this
;;     namespace's I-slice/Intra_16x16-only scope actually needs. Named
;;     identically to OpenH264's own `NEW_CTX_OFFSET_*` #defines
;;     (`codec/decoder/core/inc/decoder_context.h`) for direct traceability
;;     back to the porting reference. ---

(def ^:const ctx-mb-type-i 3)   ;; mb_type, I/SI slice (ctxIdxOffset 3, 8 ctx: prefix 0..2 + suffix 3..7)
(def ^:const ctx-delta-qp 60)   ;; mb_qp_delta (ctxIdxOffset 60, 4 ctx: 0/1 first bin + 2/3 continuation)
(def ^:const ctx-cipr 64)       ;; intra_chroma_pred_mode (ctxIdxOffset 64, 4 ctx: 0..2 first bin + 3 continuation)
(def ^:const ctx-cbf 85)        ;; coded_block_flag (ctxIdxOffset 85)
(def ^:const ctx-map 105)       ;; significant_coeff_flag (ctxIdxOffset 105)
(def ^:const ctx-last 166)      ;; last_significant_coeff_flag (ctxIdxOffset 166)
(def ^:const ctx-one 227)       ;; coeff_abs_level_minus1 "greater1" (ctxIdxOffset 227)
(def ^:const ctx-abs 232)       ;; coeff_abs_level_minus1 remainder (ctxIdxOffset 232)

;; --- Block-category parameters (§9.3.3.1.3/Table 9-42's blockCat, scoped
;;     to the 4 categories this namespace needs — LUMA_DC_AC_8/regular
;;     LUMA_DC_AC (inter/I_NxN) are out of scope). Values transcribed from
;;     OpenH264's `g_kMaxPos`/`g_kMaxC2`/`g_kBlockCat2CtxOffset{CBF,Map,Last,One,Abs}`
;;     (`codec/decoder/core/src/parse_mb_syn_cabac.cpp`). Chroma DC/AC use
;;     the SAME offsets for both Cb and Cr (only the coded_block_flag
;;     NEIGHBOR lookup differs per-component, via the caller-supplied nA/nB
;;     — see `h264.decode`)."
(defn- cat-params [cat]
  (case cat
    :luma-dc   {:max-num-coeff 16 :max-pos 15 :max-c2 4 :cbf-off 0  :map-off 0  :one-off 0}
    :luma-ac   {:max-num-coeff 15 :max-pos 14 :max-c2 4 :cbf-off 4  :map-off 15 :one-off 10}
    :chroma-dc {:max-num-coeff 4  :max-pos 3  :max-c2 3 :cbf-off 12 :map-off 44 :one-off 30}
    :chroma-ac {:max-num-coeff 15 :max-pos 14 :max-c2 4 :cbf-off 16 :map-off 47 :one-off 39}))

;; --- §9.3.1.1.1 context-model initialization ---

(defn init-context
  "One context model's initial {pStateIdx, valMPS} from its (m,n) pair and
   SliceQPY (§9.3.1.1.1 — contexts are initialized ONCE per slice from the
   slice's OWN QP, not re-initialized as `mb_qp_delta` drifts QP per-MB)."
  [[m n] qp]
  (let [pre-ctx-state (max 1 (min 126 (+ (bit-shift-right (* m qp) 4) n)))]
    (if (<= pre-ctx-state 63)
      {:state (atom (- 63 pre-ctx-state)) :mps (atom 0)}
      {:state (atom (- pre-ctx-state 64)) :mps (atom 1)})))

(defn init-contexts
  "Full 460-entry context-model vector for `qp` (I-slice init values only —
   see `context-init-i`). Entries whose (m,n) is `nil` (P/B-only, never
   indexed by this namespace) stay `nil`."
  [qp]
  (mapv (fn [mn] (when mn (init-context mn qp))) context-init-i))

;; --- §9.3.3.2 arithmetic decoding engine (literal per-bit port, NOT
;;     FFmpeg's byte-buffered variant — see namespace docstring) ---

(defn init-engine!
  "§9.3.1.2 initialization of the arithmetic decoding engine: codIRange=510,
   codIOffset = the next 9 bits (read directly off the SAME `h264.expgolomb`
   reader `r` that decoded the slice header — the caller MUST have already
   byte-aligned `r` via `byte-align!`, since slice_data()'s
   `cabac_alignment_one_bit` guarantees codIOffset's first bit is byte-
   aligned in the actual NAL bytes, per spec)."
  [r]
  {:r r :range (atom 510) :offset (atom (eg/bits! r 9))})

(defn- renorm-d!
  [eng]
  (let [r (:r eng) rg (:range eng) off (:offset eng)]
    (while (< @rg 256)
      (swap! rg bit-shift-left 1)
      (swap! off (fn [o] (bit-or (bit-shift-left o 1) (eg/bit! r)))))))

(defn decode-decision!
  "§9.3.3.2.1 DecodeDecision: one context-coded bin against context model
   `ctx` (a {:state (atom pStateIdx) :mps (atom valMPS)} — see
   `init-context`/`init-contexts`), mutating `ctx`'s state in place."
  [eng ctx]
  (let [rg (:range eng) off (:offset eng)
        state @(:state ctx) mps @(:mps ctx)
        q (bit-and (bit-shift-right @rg 6) 3)
        rlps (get-in range-tab-lps [state q])
        new-range (- @rg rlps)
        lps? (>= @off new-range)
        bin (if lps? (- 1 mps) mps)]
    (if lps?
      (do (reset! off (- @off new-range))
          (reset! rg rlps)
          (when (zero? state) (reset! (:mps ctx) (- 1 mps)))
          (reset! (:state ctx) (nth trans-idx-lps state)))
      (do (reset! rg new-range)
          (reset! (:state ctx) (nth trans-idx-mps state))))
    (renorm-d! eng)
    bin))

(defn decode-bypass!
  "§9.3.3.2.3 DecodeBypass: one equiprobable (context-free) bin."
  [eng]
  (let [r (:r eng) rg (:range eng) off (:offset eng)]
    (reset! off (bit-or (bit-shift-left @off 1) (eg/bit! r)))
    (if (>= @off @rg)
      (do (reset! off (- @off @rg)) 1)
      0)))

(defn decode-terminate!
  "§9.3.3.2.4 DecodeTerminate: used ONLY for `end_of_slice_flag` in this
   namespace's I-slice scope (I_PCM detection also uses it, but that mb_type
   throws immediately in `h264.decode` before any further reads)."
  [eng]
  (let [rg (:range eng) off (:offset eng)]
    (swap! rg - 2)
    (if (>= @off @rg)
      1
      (do (renorm-d! eng) 0))))

;; --- §7.3.4 slice_data()'s CABAC-only byte-alignment ---

(defn byte-align!
  "`while (!byte_aligned()) cabac_alignment_one_bit` — consumes 0..7 bits
   up to the next byte boundary. Per spec these MUST all be `1`; validated
   (throws) rather than silently accepting a misaligned/corrupt bitstream."
  [r]
  (while (not (eg/byte-aligned? r))
    (let [b (eg/bit! r)]
      (when (zero? b)
        (throw (ex-info "h264.cabac: cabac_alignment_one_bit was 0 (misaligned or corrupt bitstream)" {}))))))

;; --- §9.3.2.5 / Table 9-36 mb_type binarization, I-slice ---

(defn read-mb-type-i!
  "I-slice `mb_type` (§9.3.2.5, Table 9-36's I-slice binarization + Table
   9-39's ctxIdxInc): prefix bin (ctxIdxInc = neighbor-available count, 0..2)
   distinguishes I_NxN(0) from everything else; if not I_NxN, a
   `decode_terminate` distinguishes I_PCM(25) from Intra_16x16 (1..24); the
   Intra_16x16 suffix (5 more context-coded bins) directly encodes the
   `mb_type` value 1..24 per Table 7-11's own arithmetic (cbp_luma!=0,
   cbp_chroma 0/1/2, pred_mode 0..3) — this repo's scope never decodes
   I_NxN/I_PCM (both throw downstream in `h264.decode/i16x16-mb-info`, same
   as the CAVLC path), so `left-avail?`/`top-avail?` are simply \"neighbor
   MB exists\" (any neighbor this decoder has SUCCESSFULLY decoded is, by
   construction, never I_NxN — the full spec condition also excludes
   I_NxN/I_8x8 neighbors, which is automatically satisfied here)."
  [eng ctxs left-avail? top-avail?]
  (let [ctx-inc (+ (if left-avail? 1 0) (if top-avail? 1 0))
        bin0 (decode-decision! eng (nth ctxs (+ ctx-mb-type-i ctx-inc)))]
    (if (zero? bin0)
      0
      (if (= 1 (decode-terminate! eng))
        25
        (let [b1 (decode-decision! eng (nth ctxs (+ ctx-mb-type-i 3)))
              mb-type (+ 1 (* 12 b1))
              b2 (decode-decision! eng (nth ctxs (+ ctx-mb-type-i 4)))
              mb-type (if (pos? b2)
                        (let [b3 (decode-decision! eng (nth ctxs (+ ctx-mb-type-i 5)))]
                          (+ mb-type 4 (* 4 b3)))
                        mb-type)
              b4 (decode-decision! eng (nth ctxs (+ ctx-mb-type-i 6)))
              mb-type (+ mb-type (* 2 b4))
              b5 (decode-decision! eng (nth ctxs (+ ctx-mb-type-i 7)))]
          (+ mb-type b5))))))

;; --- §9.3.2.2/Table 9-34 intra_chroma_pred_mode binarization (TU, cMax=3) ---

(defn read-intra-chroma-pred-mode!
  "`intra_chroma_pred_mode` (Table 9-34 TU cMax=3 binarization, Table 9-39's
   ctxIdxInc: bin0 = neighbor-nonzero-mode count 0..2, bins 1+ share a single
   fixed context). `left-nonzero?`/`top-nonzero?` = neighbor MB available AND
   its OWN `intra_chroma_pred_mode` != 0 (DC) — see `h264.decode`."
  [eng ctxs left-nonzero? top-nonzero?]
  (let [ctx-inc (+ (if left-nonzero? 1 0) (if top-nonzero? 1 0))
        bin0 (decode-decision! eng (nth ctxs (+ ctx-cipr ctx-inc)))]
    (if (zero? bin0)
      0
      (let [b1 (decode-decision! eng (nth ctxs (+ ctx-cipr 3)))]
        (if (zero? b1)
          1
          (let [b2 (decode-decision! eng (nth ctxs (+ ctx-cipr 3)))]
            (if (zero? b2) 2 3)))))))

;; --- §9.3.2.7 mb_qp_delta binarization (unary, mapped like se(v)) ---

(defn read-mb-qp-delta!
  "`mb_qp_delta`: bin0 ctxIdxInc = (previous mb's mb_qp_delta != 0); if 0,
   delta=0. Else a further unary code (bin1 on its own context, bin2+ on a
   THIRD shared context) gives codeNum k (1-indexed unary length), mapped to
   the signed delta exactly like `se(v)`'s own codeNum mapping
   (`(k+1)>>1`, negated when k is even)."
  [eng ctxs last-delta-nonzero?]
  (let [bin0 (decode-decision! eng (nth ctxs (+ ctx-delta-qp (if last-delta-nonzero? 1 0))))]
    (if (zero? bin0)
      0
      (let [k (loop [k 1 pos 1]
                (let [ctx-idx (+ ctx-delta-qp (if (= pos 1) 2 3))
                      b (decode-decision! eng (nth ctxs ctx-idx))]
                  (if (zero? b) k (recur (inc k) (inc pos)))))
            delta (quot (inc k) 2)]
        (if (odd? k) delta (- delta))))))

;; --- §9.3.2.3 coeff_abs_level_minus1's EGk bypass suffix + UEG "greater
;;     than 2" continuation, both ported literally from OpenH264's
;;     `DecodeExpBypassCabac`/`DecodeUEGLevelCabac` (see namespace
;;     docstring) rather than re-derived, since an off-by-one here silently
;;     produces a plausible-but-wrong coefficient magnitude. ---

(defn- decode-exp-golomb-bypass!
  "Order-`k0` Exp-Golomb, bypass-coded (§9.3.2.3's EGk, ported from
   `DecodeExpBypassCabac`): a unary prefix (each `1` bit doubles the implicit
   range and increments `k0`) terminated by a `0`, then `k0` more bypass
   bits read directly (MSB-first) as the suffix value.

   BUG FIX (multi-macroblock CABAC root-cause, see README \"Pixel decode:
   CABAC\" and ADR-2607122000 addendum): the suffix accumulation MUST use a
   SEPARATE accumulator from the prefix sum, the two ADDED together at the
   end — exactly OpenH264's own `DecodeExpBypassCabac` (`iSymTmp` for the
   prefix, a SEPARATE `iSymTmp2` for the suffix, `uiSymVal = iSymTmp +
   iSymTmp2`). The previous version here reused the prefix's `sym` as the
   suffix loop's OWN starting accumulator and OR'd suffix bits into it —
   which is a SILENT NO-OP for every suffix value, because a `count`-bit
   unary prefix always accumulates to exactly `2^count - 1` (ALL `count`
   low bits already 1), and the suffix then OR's `count` MORE bits into
   those SAME low bit positions, which can only ever leave them unchanged.
   This made `coeff_abs_level_minus1` silently truncate to the truncated-
   unary prefix's own value (`decode-ueg-level!`'s `code`) plus the wrong,
   suffix-blind EG0 term, for EVERY coefficient whose magnitude was large
   enough to saturate the cMax=13 truncated-unary bank in
   `decode-ueg-level!` (i.e. `coeff_abs_level_minus1 >= 14`) — invisible on
   `flat16-dc-only-cabac.h264`/`gradient16-ac-cabac.h264` (both single-MB,
   ≤1 small significant coefficient, never reaching this code path at all)
   but reliably wrong on real multi-macroblock/multi-coefficient content
   the moment any one block's decoded magnitude got large enough to need
   this suffix. Root-caused by an independent from-scratch Python
   re-implementation (arithmetic engine + context tables cross-checked
   programmatically against Cisco OpenH264's own `common_tables.cpp`, zero
   diffs across all 460 context-init entries and every ctxIdxOffset/block-
   category constant) that reproduced the IDENTICAL wrong bin sequence and
   coefficient values as this namespace on a real multi-macroblock Main-
   profile CABAC fixture — i.e. the entropy decode CONTROL FLOW (which
   bins get read, in what order, against which contexts) was already
   correct, only this one arithmetic combination step was wrong — then
   confirmed by hand-tracing the actual bin sequence for the fixture's
   affected DC block and finding the corrected value (`code + (prefix +
   suffix) + 1`) exactly matches an independently-CAVLC-encoded reference
   of near-identical content (`+29`/`+89` instead of the previous `+22`/
   `+78` at two of the block's four significant positions)."
  [eng k0]
  (loop [count k0 sym 0]
    (let [bit (decode-bypass! eng)]
      (if (= bit 1)
        (let [count' (inc count)]
          (when (= count' 16)
            (throw (ex-info "h264.cabac: Exp-Golomb bypass unary prefix exceeded 16 (corrupt bitstream)" {})))
          (recur count' (+ sym (bit-shift-left 1 count))))
        (loop [i count acc 0]
          (if (zero? i)
            (+ sym acc)
            (let [i' (dec i) b (decode-bypass! eng)]
              (recur i' (if (= b 1) (bit-or acc (bit-shift-left 1 i')) acc)))))))))

(defn- decode-ueg-level!
  "The \"is this coefficient's absolute value greater than 2\" continuation
   (ported from `DecodeUEGLevelCabac`): a truncated-unary prefix (cMax=13,
   ALL bins on the SAME shared context `ctx`) then, if the prefix saturates
   at 13 without a natural `0` terminator, an EG0 bypass suffix
   (`decode-exp-golomb-bypass!` with k0=0). Returns the amount to ADD to the
   already-known base level of 2 (the caller has already decoded
   significance=1 and greater1=1, i.e. level>=2, before calling this)."
  [eng ctx]
  (if (zero? (decode-decision! eng ctx))
    0
    (loop [code 0 count 1]
      (let [bit (decode-decision! eng ctx)
            code (inc code)
            count (inc count)]
        (if (and (not (zero? bit)) (not= count 13))
          (recur code count)
          (if (not (zero? bit))
            (+ code (decode-exp-golomb-bypass! eng 0) 1)
            code))))))

;; --- §7.3.5.3.3/9.3.3.1.1.9 coded_block_flag + §9.3.3.1.2/9.3.2.3
;;     significant_coeff_flag/last_significant_coeff_flag/
;;     coeff_abs_level_minus1, combined into one per-block residual decode
;;     matching `h264.cavlc/residual-block!`'s OWN return shape (SCAN-order
;;     `:coeffs` + `:total-coeff`) so `h264.decode` can reuse the exact same
;;     entropy-independent unscan/dequant/transform pipeline for both. ---

(defn- read-significant-map!
  "§9.3.3.1.2/Table 9-39: `significant_coeff_flag`/`last_significant_coeff_flag`
   for one block, scan positions 0..(max-pos-1) each read a
   significant_coeff_flag (context = position index, no neighbor dependence)
   then, if significant, a last_significant_coeff_flag (same position-
   indexed context in the LAST table) — if that fires, all REMAINING
   positions are 0 and decode stops early. If the loop runs through position
   (max-pos-1) without an early stop, position max-pos (the final scan
   position) is IMPLICITLY significant (no bit read for it — §9.3.3.1.2's
   own note that the last coefficient position never gets its own
   significant_coeff_flag). Returns a `(inc max-pos)`-length (== max-num-
   coeff) vector of 0/1."
  [eng ctxs map-base last-base max-pos]
  (loop [i 0 sig (vec (repeat (inc max-pos) 0))]
    (if (>= i max-pos)
      (assoc sig max-pos 1)
      (let [s (decode-decision! eng (nth ctxs (+ map-base i)))]
        (if (zero? s)
          (recur (inc i) sig)
          (let [last? (decode-decision! eng (nth ctxs (+ last-base i)))]
            (if (zero? last?)
              (recur (inc i) (assoc sig i 1))
              (assoc sig i 1))))))))

(defn- read-coeff-levels!
  "§9.3.2.3/9.3.3.1.3 `coeff_abs_level_minus1` (+ its bypass sign bit),
   walking the already-decoded significance vector `sig` in REVERSE scan
   order (spec-mandated — c1/c2 adaptive-context state is threaded through
   this SAME reverse traversal, reset to c1=1/c2=0 once per block). Zero
   (non-significant) positions consume NO bits at all. Returns {:coeffs
   :total-coeff}, `:coeffs` in SCAN order (position 0..max-pos, signed
   dequantization-ready levels — same shape `h264.cavlc/residual-block!`'s
   `:coeffs` has)."
  [eng ctxs one-base abs-base max-c2 sig max-pos]
  (loop [i max-pos c1 1 c2 0 levels (vec (repeat (count sig) 0)) total 0]
    (if (< i 0)
      {:coeffs levels :total-coeff total}
      (if (zero? (nth sig i))
        (recur (dec i) c1 c2 levels total)
        (let [gt1 (decode-decision! eng (nth ctxs (+ one-base c1)))
              lvl (inc gt1)
              [lvl c1' c2']
              (if (= lvl 2)
                (let [extra (decode-ueg-level! eng (nth ctxs (+ abs-base c2)))]
                  [(+ lvl extra) 0 (min max-c2 (inc c2))])
                [lvl (if (pos? c1) (min 4 (inc c1)) c1) c2])
              sign (decode-bypass! eng)
              lvl (if (= sign 1) (- lvl) lvl)]
          (recur (dec i) c1' c2' (assoc levels i lvl) (inc total)))))))

(defn residual-block!
  "Decode one CABAC residual block (`cat` in `#{:luma-dc :luma-ac
   :chroma-dc :chroma-ac}` — see `cat-params`): `coded_block_flag` (always
   explicitly read in this repo's scope — no ChromaArrayType==3/8x8-transform
   `coded_block_flag`-inference shortcut applies, see namespace docstring)
   gates whether ANY further bits are read at all; if set,
   `significant_coeff_flag`/`last_significant_coeff_flag` map
   (`read-significant-map!`) then `coeff_abs_level_minus1`+sign
   (`read-coeff-levels!`). `cbf-nA`/`cbf-nB` are the already-derived (by
   `h264.decode`, per §9.3.3.1.1.9 — unavailable-neighbor default is
   \"current MB is intra\" which is ALWAYS true in this namespace's scope,
   see that fn's own docstring) `coded_block_flag` neighbor booleans.
   Returns {:coeffs (SCAN-order vector, length = category's max-num-coeff)
   :total-coeff int} — the SAME shape `h264.cavlc/residual-block!` returns,
   so `h264.decode` can dequantize/unscan/transform identically regardless
   of entropy method."
  [eng ctxs cat cbf-nA cbf-nB]
  (let [{:keys [max-num-coeff max-pos max-c2 cbf-off map-off one-off]} (cat-params cat)
        cbf-ctx-inc (+ (if cbf-nA 1 0) (* 2 (if cbf-nB 1 0)))
        cbf (decode-decision! eng (nth ctxs (+ ctx-cbf cbf-off cbf-ctx-inc)))]
    (if (zero? cbf)
      {:coeffs (vec (repeat max-num-coeff 0)) :total-coeff 0}
      (let [sig (read-significant-map! eng ctxs (+ ctx-map map-off) (+ ctx-last map-off) max-pos)]
        (read-coeff-levels! eng ctxs (+ ctx-one one-off) (+ ctx-abs one-off) max-c2 sig max-pos)))))
