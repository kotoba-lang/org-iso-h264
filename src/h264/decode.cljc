(ns h264.decode
  "H.264 baseline-profile reference pixel decoder — NAL → SPS/PPS → IDR
   I-slice → macroblock loop → (Intra_16x16 prediction + CAVLC residual +
   dequant + inverse transform) → reconstructed luma plane. Pure cljc,
   NON-REALTIME reference implementation (ADR-2607122000 \"R0.5\": a
   correctness-first golden model, not a claim of realtime decode
   performance — see `com-junkawasaki/root` ADR-2607122000 and the R0
   opaque / R1 capability-gated-native-host-word phases it doesn't
   replace).

   ## Scope (read before assuming this decodes arbitrary H.264)

   - **Single IDR I-slice per picture, one slice per picture** — no
     multi-slice, no P/B slices, no multiple reference frames, no
     multi-frame GOP structure.
   - **Baseline profile, CAVLC only** (no CABAC).
   - **mb_type: Intra_16x16 ONLY** (`mb_type` 1..24). `mb_type` 0
     (`I_NxN`/Intra_4x4/Intra_8x8, requiring per-4x4-block adaptive
     prediction-mode signaling) and 25 (`I_PCM`) THROW rather than being
     silently mis-decoded.
   - **Intra_16x16 luma prediction modes: DC/Vertical/Horizontal (0/1/2)
     only** — mode 3 (Plane) throws.
   - **Chroma (Cb/Cr): ChromaArrayType 1 (4:2:0) only, DC/Horizontal/
     Vertical Intra_Chroma prediction (modes 0/1/2 per Table 8-5's OWN
     numbering — a different permutation than luma's, see
     `h264.intra-pred`) — mode 3 (Plane) throws.** Chroma DC (nC==-1
     special CAVLC table) and chroma AC (regular neighbor-derived 4x4
     blocks, reusing the luma AC path with QPc instead of QPy) are both
     implemented — this is new as of the chroma-decode follow-up to Phase 1
     (see \"Pixel decode\" below); any other `chroma_format_idc` throws.
   - **No deblocking filter** — `h264.slice` reads and discards the
     deblocking-control fields; the reconstructed picture is the raw
     (pre-deblock) reconstruction. This only matters at block boundaries
     with real pixel discontinuities; it does not affect this repo's own
     flat/gradient golden vectors (see `test/h264/decode_test.clj`).
   - **No POC/reference-picture-buffer bookkeeping beyond what's needed to
     parse a single slice header.**

   Given these constraints, `decode-idr-frame` returns decoded luma AND
   chroma planes ({:luma :cb :cr}, each a flat byte vector, row-major,
   values 0..255 — luma is width*height, Cb/Cr are each (width/2)*(height/2)
   per 4:2:0 subsampling) for a picture whose width/height (from SPS) are
   exact multiples of 16 (no frame cropping — matches `h264.sps/encode`'s
   own encode-side limitation elsewhere in this repo).

   New implementation as part of the kotoba-lang reverse-domain
   media/graphics standards-substrate split (com-junkawasaki/root),
   ADR-2607122000 Phase 1 (\"R0.5\") + a chroma-decode / multi-macroblock
   V/H-prediction follow-up."
  (:require [h264.bitstream :as bs]
            [h264.rbsp :as rbsp]
            [h264.sps :as sps]
            [h264.pps :as pps]
            [h264.slice :as slice]
            [h264.expgolomb :as eg]
            [h264.cavlc :as cavlc]
            [h264.quant :as quant]
            [h264.transform :as transform]
            [h264.intra-pred :as intra-pred]
            [h264.interp :as interp]
            [codec-primitives.scan :as scan]))

(def zigzag scan/zigzag-4x4)

(def blk->col-row
  "H.264 4x4 luma block index (0..15, CAVLC/residual decode order — the
   spec's `luma4x4BlkIdx`, Figure 6-10) → [col row] (both 0..3) within the
   macroblock's 4x4-block grid.

   The four 4x4 blocks 0..15 group into FOUR 8x8 quadrants in raster order
   (TL=0-3, TR=4-7, BL=8-11, BR=12-15 — GLOBAL quadrant positions [0,0]
   [2,0] [0,2] [2,2] respectively), and WITHIN each quadrant the four 4x4
   sub-blocks are ALSO in plain raster order (TL,TR,BL,BR — col varies
   fastest, matching `h264.decode/chroma-blk->col-row`'s 2x2 raster
   convention, NOT a column-major/Z-order variant).

   PREVIOUSLY (through the chroma-decode/multi-MB-V/H-prediction
   development session) this table used an INCORRECT column-major variant
   (`{0 [0 0], 1 [0 1], 2 [1 0], ...}` — row and col swapped relative to
   the mapping above, i.e. a transpose of the correct table) that was
   believed \"empirically validated\" by the single-macroblock
   `gradient16-ac.h264` fixture and the multi-macroblock
   `chroma-multimb32.h264` fixture. Both fixtures' LUMA content is
   direction-degenerate in a way that can't distinguish a transposed block
   mapping from the correct one (a single-MB horizontal gradient has no
   cross-MB neighbor to expose it; the multimb32 fixture's luma is flat).
   The bug was only exposed by a genuinely cross-macroblock, row-varying
   (not column-varying) multi-MB luma test — where libx264 selected
   Intra_16x16 Horizontal prediction — via a real desync/wrong-pixel
   investigation (see README \"Pixel decode\" and ADR history around the
   Horizontal-prediction multi-macroblock golden vector). The correct
   mapping was independently re-derived (and cross-checked three ways) from
   FFmpeg's OWN source: (1) `scan8[]`'s neighbor-addressing formula
   (`libavcodec/h264dec.h`/callers), (2) the `ref_index`/`scan8[0|4|8|12]`
   8x8-partition assignment in `ff_h264_slice_context_init`
   (`libavcodec/h264dec.c`), and (3) the explicit `dc_mapping[16]` table in
   `hl_decode_mb_idct_luma` (`libavcodec/h264_mb.c`, the
   `transform_bypass`/lossless path, which spells out DC-raster-position →
   block-index numerically) — all three agree exactly on the mapping below.
   `h264.transform/luma-dc-hadamard`'s own arithmetic (`x_offset`/`stride`,
   kept as a byte-for-byte port of `ff_h264_luma_dc_dequant_idct`) already
   produces its 16-element output correctly indexed by this SAME true
   block numbering (verified: `out[16*k]` for k=0..15 lines up exactly with
   `stride*R+offset` for the (loop-i, R) combination that
   `ff_h264_luma_dc_dequant_idct`'s caller — `sl->mb + p*256` — treats as
   block-major storage). It ALSO needs its OWN input transposed first
   (see `h264.transform/luma-dc-hadamard`'s docstring) — the two bugs
   compound: without the input transpose, the DC-Hadamard-derived values
   vary along the wrong screen axis (column instead of row for row-varying
   content) regardless of which `blk->col-row` is used; fixing only one of
   the two still fails."
  {0 [0 0], 1 [1 0], 2 [0 1], 3 [1 1]
   4 [2 0], 5 [3 0], 6 [2 1], 7 [3 1]
   8 [0 2], 9 [1 2], 10 [0 3], 11 [1 3]
   12 [2 2], 13 [3 2], 14 [2 3], 15 [3 3]})

(def col-row->blk
  (into {} (map (fn [[k v]] [v k]) blk->col-row)))

(def chroma-blk->col-row
  "ChromaArrayType 1 (4:2:0) 4x4 chroma sub-block index (0..3, CAVLC/
   residual decode order) → [col row] (both 0..1) within the 8x8 chroma
   block's 2x2 grid. Plain RASTER order (0=[0,0] TL, 1=[1,0] TR, 2=[0,1]
   BL, 3=[1,1] BR) — NOT the same as luma's `blk->col-row` first-4-entry
   pattern (which is a column-major/Z-order convention: 0=[0,0] 1=[0,1]
   2=[1,0] 3=[1,1]). This was assumed to be the SAME building block as
   luma's (reasonable-looking given ffmpeg shares the `16 + 16*chroma_idx
   + i4x4` index arithmetic with luma's own i4x4 sub-index) but that
   assumption was WRONG and was caught empirically: a real multi-macroblock
   x264-encoded color-gradient chroma golden vector decoded to a
   right-shaped, WRONG-valued picture (AC coefficients swapped between the
   TR and BL quadrants — i.e. transposed) until this raster convention was
   substituted. Do not re-derive this from luma's convention by analogy."
  {0 [0 0], 1 [1 0], 2 [0 1], 3 [1 1]})

(def chroma-col-row->blk
  (into {} (map (fn [[k v]] [v k]) chroma-blk->col-row)))

(defn- i16x16-mb-info
  "mb_type (1..24, I_16x16_*) → {:pred-mode :cbp-luma :cbp-chroma} per
   Table 7-11. Throws for mb_type 0 (I_NxN) / 25 (I_PCM) / anything else
   (out of scope, see namespace docstring)."
  [mb-type]
  (when (or (zero? mb-type) (= mb-type 25) (> mb-type 25))
    (throw (ex-info "h264.decode: only Intra_16x16 mb_type (1..24) is supported"
                     {:mb-type mb-type
                      :reason (cond (zero? mb-type) "I_NxN (Intra_4x4/8x8) not implemented"
                                    (= mb-type 25) "I_PCM not implemented"
                                    :else "not a valid I-slice mb_type")})))
  (let [m (dec mb-type)]
    {:pred-mode (mod m 4)
     :cbp-chroma (mod (quot m 4) 3)
     :cbp-luma (if (>= m 12) 15 0)}))

(defn- neighbor-nc
  [nA nB]
  (cond (and (nil? nA) (nil? nB)) 0
        (nil? nA) nB
        (nil? nB) nA
        :else (quot (+ nA nB 1) 2)))

(defn- unscan-raster
  "Place `scanned` (vector, scan-order, length = (count scan-order)) into
   a 16-element row-major (idx=row*4+col) vector using `scan-order` — a
   permutation vector of raster indices — starting at raster position 0."
  [scan-order scanned]
  (reduce (fn [acc [pos v]] (assoc acc pos v))
          (vec (repeat 16 0))
          (map vector scan-order scanned)))

(defn- decode-luma-dc!
  [r nc qp]
  (let [{:keys [coeffs total-coeff]} (cavlc/residual-block! r nc 16)
        raster (unscan-raster zigzag coeffs)
        dc-per-block (transform/luma-dc-hadamard raster (quant/dc-qmul qp))]
    {:dc-per-block dc-per-block :total-coeff total-coeff}))

(defn- decode-ac-block!
  "Decode one regular AC 4x4 block (scan positions 1..15, maxNumCoeff 15).
   Shared by Intra16x16 luma AC blocks AND chroma AC blocks (§8.5.9's
   per-position dequant formula is identical for both — only the `qp`
   value passed in differs: QPy for luma, QPc — `h264.quant/chroma-qp` —
   for chroma). Returns {:ac-raster (16-elem vector, position 0 always 0 —
   the caller overlays the DC value) :total-coeff int} with dequantized
   levels, ready to overlay onto a block's coefficient array at positions
   1..15 (position 0 is the DC value, handled separately)."
  [r nc qp]
  (let [{:keys [coeffs total-coeff]} (cavlc/residual-block! r nc 15)
        ac-zigzag (subvec zigzag 1)
        positions (unscan-raster ac-zigzag coeffs)]
    {:ac-raster (mapv (fn [pos]
                         (let [level (nth positions pos)]
                           ;; matches ffmpeg's `((int)(level*qmul+32))>>6` —
                           ;; MUST be an arithmetic (floor) shift, not
                           ;; truncating division, since level can be
                           ;; negative and the two differ whenever the
                           ;; numerator isn't an exact multiple of 64.
                           (if (zero? level) 0 (bit-shift-right (+ (* level (quant/ac-qmul qp (quot pos 4) (mod pos 4))) 32) 6))))
                       (range 16))
     :total-coeff total-coeff}))

(defn- decode-regular-block!
  "Decode one FULL 4x4 residual block (ALL 16 scan positions, maxNumCoeff
   16 — including position [0,0], dequantized via the SAME per-position
   `h264.quant/ac-qmul` formula as every other position). This is the
   residual shape used by mb_types that have NO separate macroblock-level
   luma-DC/Hadamard block — i.e. P-slice inter macroblocks (P_L0_16x16 etc.)
   and I_NxN — as opposed to `decode-ac-block!`, which is Intra16x16-
   luma-ONLY (maxNumCoeff 15, position 0 supplied externally from the
   Hadamard-transformed macroblock-level DC). Returns {:raster (16-elem
   row-major vector, dequantized) :total-coeff int}."
  [r nc qp]
  (let [{:keys [coeffs total-coeff]} (cavlc/residual-block! r nc 16)
        positions (unscan-raster zigzag coeffs)]
    {:raster (mapv (fn [pos]
                      (let [level (nth positions pos)]
                        (if (zero? level) 0 (bit-shift-right (+ (* level (quant/ac-qmul qp (quot pos 4) (mod pos 4))) 32) 6))))
                    (range 16))
     :total-coeff total-coeff}))

;; --- coded_block_pattern (§9.1.2 Table 9-4, ME(v) mapping) — the codeNum
;;     read via a plain ue(v) is mapped through this fixed table to the
;;     actual CodedBlockPattern value (CodedBlockPatternChroma*16 +
;;     CodedBlockPatternLuma). Only the INTER column is needed — this
;;     repo's decoder never reaches the Intra_4x4/Intra_8x8 CBP-mapping
;;     case (mb_type 0/I_NxN throws, see `i16x16-mb-info`; Intra16x16
;;     mb_types infer their CBP from mb_type directly, no ME(v) read at
;;     all). Transcribed from FFmpeg's `ff_h264_golomb_to_inter_cbp`
;;     (`libavcodec/h264data.c`, https://github.com/FFmpeg/FFmpeg) — a
;;     direct transcription of ITU-T H.264 Table 9-4's Inter column for
;;     ChromaArrayType 1/2 — cross-checked to be a full permutation of
;;     0..47 (`(= (sort golomb-to-inter-cbp) (range 48))`)."
(def golomb-to-inter-cbp
  [0 16 1 2 4 8 32 3 5 10 12 15 47 7 11 13
   14 6 9 31 35 37 42 44 33 34 36 40 39 43 45 46
   17 18 20 24 19 21 26 28 23 27 29 30 22 25 38 41])

(defn- read-coded-block-pattern-inter!
  "Read `coded_block_pattern` (§7.3.5.1, `me(v)`) for an INTER macroblock:
   a plain ue(v) codeNum, mapped via `golomb-to-inter-cbp`. Returns
   {:cbp-luma (0..15, one bit per 8x8 luma quadrant — NOT inferred from
   mb_type the way Intra16x16's is, see `i16x16-mb-info`) :cbp-chroma
   (0..2)}."
  [r]
  (let [cbp (nth golomb-to-inter-cbp (eg/ue! r))]
    {:cbp-luma (mod cbp 16) :cbp-chroma (quot cbp 16)}))

(defn- decode-chroma-dc!
  "Decode one ChromaArrayType 1 (4:2:0) chroma DC block (nC==-1, maxNumCoeff
   4). Returns {:dc-quad (4-elem vector, RASTER order idx=row*2+col — see
   `h264.transform/chroma-dc-hadamard`) :total-coeff int}."
  [r qpc]
  (let [{:keys [coeffs total-coeff]} (cavlc/residual-block! r :chroma-dc 4)
        dc-quad (transform/chroma-dc-hadamard coeffs (quant/dc-qmul qpc))]
    {:dc-quad dc-quad :total-coeff total-coeff}))

(defn- clip8 [v] (max 0 (min 255 v)))

(defn- decode-chroma-ac-blocks!
  "Read the 4 chroma AC 4x4 blocks (`cbp-chroma` == 2) for ONE component
   from reader `r`, given that component's already-decoded `dc-quad`
   (RASTER order, see `decode-chroma-dc!`/`h264.transform/chroma-dc-hadamard`).
   If `cbp-chroma` < 2, no bits are read and `block-coeffs` is just the DC
   values overlaid onto otherwise-zero 4x4 arrays.

   IMPORTANT bitstream-order note (§7.3.5.3.3 `residual_block_cavlc`/
   ffmpeg's `ff_h264_decode_mb_cavlc`): the actual NAL bit order for an
   Intra_16x16 macroblock's chroma residual is [Cb DC, Cr DC, Cb AC x4, Cr
   AC x4] — ALL DC blocks (both components) before ANY AC blocks, NOT
   [Cb DC, Cb AC x4, Cr DC, Cr AC x4] (i.e. NOT fully decoding one
   component before starting the other). This fn intentionally does ONLY
   the AC read for a single component — callers (`h264.decode/decode-macroblock!`)
   MUST call `decode-chroma-dc!` for BOTH components first, THEN call this
   fn for both components, to match that order. Getting this wrong
   silently desyncs the bit reader partway through the FIRST macroblock
   that has real chroma AC residual (`cbp-chroma` == 2) — caught via a real
   multi-macroblock color-gradient golden vector during development (DC-
   only fixtures don't exercise this order at all).

   The 4 chroma 4x4 sub-blocks use `chroma-blk->col-row` (plain raster
   order — NOT the same block-index convention as luma's `blk->col-row`,
   see that def's docstring for the empirical mismatch this caused).
   `dc-quad` is ALSO raster order (idx = row*2+col), so with
   `chroma-blk->col-row` the two indices coincide (`b == row*2+col`) —
   overlaying a block's DC value is a direct `(nth dc-quad b)`, no
   remapping needed.

   Returns {:block-coeffs (4-elem vector of 16-elem row-major coefficient
   arrays) :ac-nnz (4-elem vec, this component's per-block total_coeff —
   feeds neighbor `nC` derivation for MBs to the right/below)}."
  [r qpc cbp-chroma dc-quad left-c top-c]
  (let [ac-nnz (atom (vec (repeat 4 0)))
        block-coeffs
        (if (< cbp-chroma 2)
          (mapv (fn [b] (assoc (vec (repeat 16 0)) 0 (nth dc-quad b)))
                (range 4))
          (mapv
           (fn [b]
             (let [[col row] (chroma-blk->col-row b)
                   nA (if (pos? col)
                        (nth @ac-nnz (chroma-col-row->blk [(dec col) row]))
                        (when left-c (nth (:ac-nnz left-c) (chroma-col-row->blk [1 row]))))
                   nB (if (pos? row)
                        (nth @ac-nnz (chroma-col-row->blk [col (dec row)]))
                        (when top-c (nth (:ac-nnz top-c) (chroma-col-row->blk [col 1]))))
                   nc (neighbor-nc nA nB)
                   {:keys [ac-raster total-coeff]} (decode-ac-block! r nc qpc)]
               (swap! ac-nnz assoc b total-coeff)
               (assoc ac-raster 0 (nth dc-quad b))))
           (range 4)))]
    {:block-coeffs block-coeffs :ac-nnz @ac-nnz}))

(defn- add-residual-16x16
  "Pure (no bit reads) residual reconstruction for a 16x16 luma macroblock:
   overlay each of the 16 `block-coeffs` 4x4 blocks' inverse-transformed
   residual onto the corresponding 4x4 region of `pred-16x16` (a 16x16
   row-vector grid — from Intra_16x16 prediction for intra macroblocks, OR
   from motion-compensated reference-frame pixels for inter macroblocks —
   this fn doesn't care which, it's pure pixel-domain reconstruction).
   Shared by `decode-intra-macroblock-body!` and the P-slice inter
   macroblock path (`decode-inter-16x16-macroblock!`)."
  [pred-16x16 block-coeffs]
  (reduce
   (fn [recon b]
     (let [[col row] (blk->col-row b)
           pred4x4 (vec (for [ry (range 4)] (vec (for [rx (range 4)] (get-in pred-16x16 [(+ (* row 4) ry) (+ (* col 4) rx)])))))
           residual (transform/inverse-4x4 (nth block-coeffs b))]
       (reduce
        (fn [recon ry]
          (reduce
           (fn [recon rx]
             (let [v (clip8 (+ (get-in pred4x4 [ry rx]) (get-in residual [ry rx])))]
               (assoc-in recon [(+ (* row 4) ry) (+ (* col 4) rx)] v)))
           recon (range 4)))
        recon (range 4))))
   (vec (repeat 16 (vec (repeat 16 0))))
   (range 16)))

(defn- add-residual-8x8
  "Pure (no bit reads) residual reconstruction for an 8x8 chroma component:
   the SAME overlay as `add-residual-16x16`, just 4 blocks over an 8x8 grid
   (chroma's `chroma-blk->col-row` raster convention) instead of 16 over a
   16x16 grid. Shared by `reconstruct-chroma-plane` (intra) and the
   P-slice inter macroblock path."
  [pred-8x8 block-coeffs]
  (reduce
   (fn [recon b]
     (let [[col row] (chroma-blk->col-row b)
           pred4x4 (vec (for [ry (range 4)] (vec (for [rx (range 4)] (get-in pred-8x8 [(+ (* row 4) ry) (+ (* col 4) rx)])))))
           residual (transform/inverse-4x4 (nth block-coeffs b))]
       (reduce
        (fn [recon ry]
          (reduce
           (fn [recon rx]
             (let [v (clip8 (+ (get-in pred4x4 [ry rx]) (get-in residual [ry rx])))]
               (assoc-in recon [(+ (* row 4) ry) (+ (* col 4) rx)] v)))
           recon (range 4)))
        recon (range 4))))
   (vec (repeat 8 (vec (repeat 8 0))))
   (range 4)))

(defn- reconstruct-chroma-plane
  "Pure (no bit reads) Intra_Chroma prediction + residual reconstruction
   for ONE 8x8 chroma component, given its already-decoded `block-coeffs`
   (from `decode-chroma-ac-blocks!`). `pred-mode` is per
   `h264.intra-pred/predict-chroma-8x8`'s mode numbering. `left-c`/`top-c`
   are the neighbor MB's SAME-component chroma state (nil at picture
   edges) — {:top-row (8-elem vec) :left-col (8-elem vec)}; `corner` is
   the diagonal top-left MB's bottom-right pixel of this SAME component
   (only used/required for Plane mode, `pred-mode` 3).

   Returns {:recon (8x8 row-vector grid) :top-row (8-elem vec, this MB's
   bottom row) :left-col (8-elem vec, this MB's right col)}."
  [block-coeffs pred-mode left-c top-c corner]
  (let [top-row (:top-row top-c)
        left-col (:left-col left-c)
        pred-8x8 (intra-pred/predict-chroma-8x8 pred-mode
                                                 {:top-available? (some? top-c)
                                                  :left-available? (some? left-c)
                                                  :top-row top-row
                                                  :left-col left-col
                                                  :corner corner})
        recon (add-residual-8x8 pred-8x8 block-coeffs)]
    {:recon recon
     :top-row (nth recon 7)
     :left-col (mapv #(nth % 7) recon)}))

(defn- decode-intra-macroblock-body!
  "Decode one Intra_16x16 macroblock (luma + chroma) from reader `r`, given
   an ALREADY-READ `mb-type` (this fn does NOT itself read the mb_type
   ue(v) — callers differ in how mb_type is obtained/numbered: an I-slice
   reads it directly (`decode-macroblock!` below), a P-slice reads a
   P-mb_type first and derives the intra mb_type as `p-mb-type - 5`
   per Table 7-13, see `decode-macroblock-p!` in the P-slice section
   below). `qp` is this MB's QPy (already resolved by the caller from
   slice_qp + running mb_qp_delta). `chroma-qp-index-offset` is PPS
   `chroma_qp_index_offset` (see `h264.quant/chroma-qp`; applied to BOTH
   Cb and Cr, see that fn's docstring for why). `left-mb`/`top-mb` are the
   neighbor MB states (or nil if unavailable — picture edge), carrying
   BOTH luma (`:dc-nnz`/`:ac-nnz`/`:top-row`/`:left-col`) and per-component
   chroma (`:cb`/`:cr`, each {:ac-nnz :top-row :left-col}) neighbor state —
   this works identically whether a neighbor MB was itself intra or inter,
   see `add-residual-16x16`/`add-residual-8x8`'s shared-reconstruction
   design note. `topleft-mb` is the diagonal top-left neighbor MB state
   (or nil), used ONLY for chroma Plane mode's corner sample (raster-scan
   single-slice decode guarantees it's already reconstructed whenever both
   `left-mb` and `top-mb` are available, see
   `h264.intra-pred/chroma-plane-grid`). Returns {:recon (16x16 luma
   row-vector grid) :cb :cr (8x8 chroma row-vector grids) :dc-nnz :ac-nnz
   (luma) :inter? false :mv nil ...}."
  [r mb-type qp chroma-qp-index-offset left-mb top-mb topleft-mb]
  (let [{:keys [pred-mode cbp-luma cbp-chroma]} (i16x16-mb-info mb-type)
        intra-chroma-pred-mode (eg/ue! r)
        mb-qp-delta (eg/se! r)
        ;; §7.4.5: QPy = ((QPy,PREV + mb_qp_delta + 52 + 2*QpBdOffsetY) %
        ;; (52 + QpBdOffsetY)) - QpBdOffsetY — modulo-52 WRAP, not plain
        ;; addition (QpBdOffsetY=0 for this repo's 8-bit-only scope). A
        ;; large negative mb_qp_delta (legal per spec, range -26..+25 for
        ;; 8-bit) can otherwise push qp' negative or >51, which doesn't
        ;; desync the bitstream (QP doesn't gate any CAVLC read) but DOES
        ;; silently produce a wrong dequant scale.
        qp' (mod (+ qp mb-qp-delta 52) 52)
        qpc (quant/chroma-qp qp' chroma-qp-index-offset)
        ;; §9.2.1 nC derivation for the Intra16x16 luma DC block: per
        ;; ffmpeg's `decode_residual`/`pred_non_zero_count` (and the spec's
        ;; §6.4.11.4 neighbouring-4x4-luma-block process), the DC block is
        ;; positioned at 4x4 luma block index 0 for NEIGHBOR-DERIVATION
        ;; purposes — its nC uses the SAME neighbor cells as luma AC block 0
        ;; (left neighbor MB's block [3,0], top neighbor MB's block [0,3]),
        ;; i.e. `:ac-nnz`, NOT a separate DC-total-coeff channel. This is
        ;; NOT obvious from a naive reading (there being both a per-MB
        ;; :dc-nnz already in this state map invites reusing it directly),
        ;; but ffmpeg explicitly stores the DC block's own total_coeff into
        ;; a dedicated cache slot that NO neighbor-derivation read ever
        ;; consults — and when cbp_luma is 0 (DC-only, no AC), ffmpeg
        ;; explicitly zero-fills the AC nnz cache for the WHOLE macroblock
        ;; (`decode_luma_residual`'s `else` branch), so a neighbor MB's
        ;; contribution to nC is 0 in that case regardless of how many
        ;; nonzero DC coefficients that neighbor actually had. Using
        ;; `:dc-nnz` here (the DC block's own total-coeff) instead of
        ;; `:ac-nnz` at block [3,0]/[0,3] silently selects the WRONG
        ;; coeff_token VLC nC-class and desyncs the CAVLC bit reader on the
        ;; very next macroblock that has any real neighbor-derived DC nC —
        ;; caught via a real multi-macroblock x264 stream where MB0 had
        ;; cbp_luma=15 (dc-total-coeff=4, ac-nnz[3,0]=0) and MB1 read the
        ;; DC block with the wrong nc (4, i.e. VLC class 2) instead of the
        ;; correct nc (0, class 0).
        dc-nc (neighbor-nc (when left-mb (nth (:ac-nnz left-mb) (col-row->blk [3 0])))
                            (when top-mb (nth (:ac-nnz top-mb) (col-row->blk [0 3]))))
        dc-decoded (decode-luma-dc! r dc-nc qp')
        dc-per-block (:dc-per-block dc-decoded)
        dc-total-coeff (:total-coeff dc-decoded)
        ac-nnz (atom (vec (repeat 16 0)))
        block-coeffs
        (if (zero? cbp-luma)
          (mapv (fn [b] (assoc (vec (repeat 16 0)) 0 (nth dc-per-block b))) (range 16))
          (mapv
           (fn [b]
             (let [[col row] (blk->col-row b)
                   nA (if (pos? col)
                        (nth @ac-nnz (col-row->blk [(dec col) row]))
                        (when left-mb (nth (:ac-nnz left-mb) (col-row->blk [3 row]))))
                   nB (if (pos? row)
                        (nth @ac-nnz (col-row->blk [col (dec row)]))
                        (when top-mb (nth (:ac-nnz top-mb) (col-row->blk [col 3]))))
                   nc (neighbor-nc nA nB)
                   {:keys [ac-raster total-coeff]} (decode-ac-block! r nc qp')]
               (swap! ac-nnz assoc b total-coeff)
               (assoc ac-raster 0 (nth dc-per-block b))))
           (range 16)))
        top-row (:top-row top-mb)
        left-col (:left-col left-mb)
        pred-16x16 (intra-pred/predict-16x16 pred-mode
                                              {:top-available? (some? top-mb)
                                               :left-available? (some? left-mb)
                                               :top-row top-row
                                               :left-col left-col})
        recon (add-residual-16x16 pred-16x16 block-coeffs)
        ;; Bitstream order (§7.3.5.3.3 / ffmpeg `ff_h264_decode_mb_cavlc`):
        ;; Cb DC, Cr DC, Cb AC x4, Cr AC x4 — NOT Cb(DC+AC) then Cr(DC+AC).
        ;; See `decode-chroma-ac-blocks!`'s docstring for why this matters.
        cb-dc-quad (if (pos? cbp-chroma) (:dc-quad (decode-chroma-dc! r qpc)) [0 0 0 0])
        cr-dc-quad (if (pos? cbp-chroma) (:dc-quad (decode-chroma-dc! r qpc)) [0 0 0 0])
        {cb-block-coeffs :block-coeffs cb-ac-nnz :ac-nnz}
        (decode-chroma-ac-blocks! r qpc cbp-chroma cb-dc-quad (:cb left-mb) (:cb top-mb))
        {cr-block-coeffs :block-coeffs cr-ac-nnz :ac-nnz}
        (decode-chroma-ac-blocks! r qpc cbp-chroma cr-dc-quad (:cr left-mb) (:cr top-mb))
        cb-corner (get-in topleft-mb [:cb :recon 7 7])
        cr-corner (get-in topleft-mb [:cr :recon 7 7])
        cb-recon (reconstruct-chroma-plane cb-block-coeffs intra-chroma-pred-mode (:cb left-mb) (:cb top-mb) cb-corner)
        cr-recon (reconstruct-chroma-plane cr-block-coeffs intra-chroma-pred-mode (:cr left-mb) (:cr top-mb) cr-corner)
        cb (assoc cb-recon :ac-nnz cb-ac-nnz)
        cr (assoc cr-recon :ac-nnz cr-ac-nnz)]
    {:recon recon
     :qp qp'
     :pred-mode pred-mode
     :intra-chroma-pred-mode intra-chroma-pred-mode
     :dc-nnz dc-total-coeff
     :ac-nnz @ac-nnz
     :inter? false
     :mv nil
     :top-row (nth recon 15)
     :left-col (mapv #(nth % 15) recon)
     :cb cb
     :cr cr}))

(defn- decode-macroblock!
  "I-slice entry point: read `mb_type` directly (I-slice numbering, 1..24
   for Intra16x16 — see `i16x16-mb-info`), then delegate to
   `decode-intra-macroblock-body!`. Kept as a separate fn (rather than
   inlining the `eg/ue! r` read into every caller) purely so the I-slice
   macroblock loop's call shape stays unchanged from before the P-slice
   addition."
  [r qp chroma-qp-index-offset left-mb top-mb topleft-mb]
  (let [mb-type (eg/ue! r)]
    (decode-intra-macroblock-body! r mb-type qp chroma-qp-index-offset left-mb top-mb topleft-mb)))

;; --- P-slice inter prediction (ADR-2607122000 Migration step 7:
;;     P_Skip + P_L0_16x16, with real sub-pel/non-zero motion compensation
;;     via h264.interp (§8.4.2.2.1/8.4.2.2.2) — see namespace docstring's
;;     "Pixel decode: P-slice (inter)" section below for full scope). ---

(defn- neighbor-mv-ref
  "[mv ref-idx] for a neighbor macroblock state `mb` (nil = unavailable),
   per §8.4.1.3's convention: an unavailable OR intra-coded neighbor
   contributes mv=[0 0] and ref-idx=-1 (never equal to a real ref-idx,
   which is always 0 in this repo's single-reference-frame scope) — intra
   macroblocks are NOT motion-compensated candidates, spec-mandated."
  [mb]
  (if (and mb (:inter? mb)) [(:mv mb) 0] [[0 0] -1]))

(defn- median3 [a b c] (- (+ a b c) (min a b c) (max a b c)))

(defn- mv-predict-16x16
  "Median luma motion vector prediction (§8.4.1.3) for a 16x16 partition,
   given this repo's single-reference-frame simplification (`cur-ref-idx`
   is always 0). `left-mb`/`top-mb` are the A/B neighbor MB states (nil if
   unavailable); `topleft-mb`/`topright-mb` are the D/C neighbors — C
   (top-right) is used when available, else D (top-left) is substituted
   per spec's `mbAddrC` unavailability rule. Returns [mvx mvy] (quarter-
   luma-sample units)."
  [left-mb top-mb topleft-mb topright-mb cur-ref-idx]
  (let [c-mb (or topright-mb topleft-mb)
        [mvA refA] (neighbor-mv-ref left-mb)
        [mvB0 refB0] (neighbor-mv-ref top-mb)
        [mvC0 refC0] (neighbor-mv-ref c-mb)
        ;; §8.4.1.3: when B and C are BOTH unavailable and A is available,
        ;; B and C are each set equal to A (not left as unavailable/zero).
        both-unavail? (and (nil? top-mb) (nil? c-mb))
        [mvB refB mvC refC] (if (and both-unavail? left-mb)
                               [mvA refA mvA refA]
                               [mvB0 refB0 mvC0 refC0])
        cands [[mvA refA] [mvB refB] [mvC refC]]
        exact-matches (filter #(= cur-ref-idx (second %)) cands)]
    (if (= 1 (count exact-matches))
      ;; exactly one neighbor's ref-idx matches the current one: use ITS mv
      ;; directly (not the median) — §8.4.1.3.1.
      (first (first exact-matches))
      [(median3 (nth mvA 0) (nth mvB 0) (nth mvC 0))
       (median3 (nth mvA 1) (nth mvB 1) (nth mvC 1))])))

(defn- p-skip-mv
  "Predicted (and, for P_Skip, FINAL — no mvd is ever coded for P_Skip)
   motion vector for a P_Skip macroblock, per §8.4.1.1: [0 0] if the left
   or top neighbor is unavailable, or if either has ref-idx 0 and mv
   [0 0] already; otherwise the general median predictor (§8.4.1.3)
   applies."
  [left-mb top-mb topleft-mb topright-mb]
  (let [[mvA refA] (neighbor-mv-ref left-mb)
        [mvB refB] (neighbor-mv-ref top-mb)]
    (if (or (nil? left-mb) (nil? top-mb)
            (and (= refA 0) (= mvA [0 0]))
            (and (= refB 0) (= mvB [0 0])))
      [0 0]
      (mv-predict-16x16 left-mb top-mb topleft-mb topright-mb 0))))

(defn- mc-predict
  "Motion-compensated prediction for the macroblock at (`mb-x`,`mb-y`) given
   the ALREADY-DERIVED final motion vector `mv` (quarter-luma-sample units —
   predictor + mvd for P_L0_16x16, or the P_Skip predictor alone; see
   `mv-predict-16x16`/`p-skip-mv`). Delegates to `h264.interp`'s luma
   quarter-sample (§8.4.2.2.1) / chroma eighth-sample (§8.4.2.2.2)
   interpolation — this covers MV=(0,0) as the trivial degenerate case
   (`h264.interp/quarter-pel-luma`'s `fx=fy=0` branch is a direct sample
   read), so no separate integer-only-MV fast path is needed. `ref-frame` is
   this repo's OWN previously-decoded-picture return shape ({:width :height
   :luma :cb :cr}). Returns {:luma-pred :cb-pred :cr-pred} (row-vector
   grids)."
  [ref-frame mb-x mb-y mv]
  (let [w (:width ref-frame) h (:height ref-frame) cw (quot w 2) ch (quot h 2)
        lx (* mb-x 16) ly (* mb-y 16)
        cx (* mb-x 8) cy (* mb-y 8)]
    {:luma-pred (interp/mc-luma-block (:luma ref-frame) w h lx ly mv 16)
     :cb-pred (interp/mc-chroma-block (:cb ref-frame) cw ch cx cy mv 8)
     :cr-pred (interp/mc-chroma-block (:cr ref-frame) cw ch cx cy mv 8)}))

(defn- decode-p-skip-macroblock!
  "Materialize one P_Skip macroblock (no bits read at all beyond the
   `mb_skip_run` the caller already consumed — see `decode-p-slice-mbs!`):
   derive its motion vector (`p-skip-mv`), motion-compensate via
   `mc-predict` (sub-pel-capable — see that fn's docstring, real non-(0,0)
   motion vectors are supported) with NO residual (a defining property of
   P_Skip). `qp` is UNCHANGED from the previous macroblock (P_Skip never
   reads `mb_qp_delta`)."
  [qp mb-x mb-y left-mb top-mb topleft-mb topright-mb ref-frame]
  (let [mv (p-skip-mv left-mb top-mb topleft-mb topright-mb)
        {:keys [luma-pred cb-pred cr-pred]} (mc-predict ref-frame mb-x mb-y mv)]
    {:recon luma-pred
     :qp qp
     :inter? true
     :mv mv
     :ac-nnz (vec (repeat 16 0))
     :top-row (nth luma-pred 15)
     :left-col (mapv #(nth % 15) luma-pred)
     :cb {:recon cb-pred :ac-nnz (vec (repeat 4 0))
          :top-row (nth cb-pred 7) :left-col (mapv #(nth % 7) cb-pred)}
     :cr {:recon cr-pred :ac-nnz (vec (repeat 4 0))
          :top-row (nth cr-pred 7) :left-col (mapv #(nth % 7) cr-pred)}}))

(defn- decode-inter-16x16-macroblock!
  "Decode one P_L0_16x16 macroblock (§7.3.5.1's `mb_pred`/`macroblock_layer`
   for `mb_type` 0 in a P-slice): mvd_l0 (2 se(v)) → final mv = predictor
   (`mv-predict-16x16`) + mvd (real, possibly non-zero and/or sub-pel — see
   `mc-predict`); `coded_block_pattern` (me(v),
   `read-coded-block-pattern-inter!`); if any residual is signaled,
   `mb_qp_delta`; then luma residual — 16 FULL 4x4 blocks
   (`decode-regular-block!`, maxNumCoeff 16, NOT the Intra16x16 DC/AC split
   — gated per-8x8-quadrant by `cbp-luma`'s 4 bits, see
   `golomb-to-inter-cbp`) — and chroma residual (reusing
   `decode-chroma-dc!`/`decode-chroma-ac-blocks!` UNCHANGED — chroma's
   DC/AC residual structure doesn't depend on the luma macroblock type,
   see README). Reference-frame ref_idx is implicitly 0 (this repo
   requires num_ref_idx_l0_active == 1, see `h264.slice`), so no ref_idx
   is read (matches spec's `if (num_ref_idx_l0_active_minus1 > 0) ...`
   gate)."
  [r qp chroma-qp-index-offset mb-x mb-y left-mb top-mb topleft-mb topright-mb ref-frame]
  (let [mvp (mv-predict-16x16 left-mb top-mb topleft-mb topright-mb 0)
        mvd-x (eg/se! r)
        mvd-y (eg/se! r)
        mv [(+ (first mvp) mvd-x) (+ (second mvp) mvd-y)]
        {:keys [cbp-luma cbp-chroma]} (read-coded-block-pattern-inter! r)
        mb-qp-delta (if (or (pos? cbp-luma) (pos? cbp-chroma)) (eg/se! r) 0)
        qp' (mod (+ qp mb-qp-delta 52) 52)
        qpc (quant/chroma-qp qp' chroma-qp-index-offset)
        {:keys [luma-pred cb-pred cr-pred]} (mc-predict ref-frame mb-x mb-y mv)
        ac-nnz (atom (vec (repeat 16 0)))
        block-coeffs
        (mapv
         (fn [b]
           (let [[col row] (blk->col-row b)
                 quadrant (quot b 4)]
             (if-not (bit-test cbp-luma quadrant)
               (vec (repeat 16 0))
               (let [nA (if (pos? col)
                          (nth @ac-nnz (col-row->blk [(dec col) row]))
                          (when left-mb (nth (:ac-nnz left-mb) (col-row->blk [3 row]))))
                     nB (if (pos? row)
                          (nth @ac-nnz (col-row->blk [col (dec row)]))
                          (when top-mb (nth (:ac-nnz top-mb) (col-row->blk [col 3]))))
                     nc (neighbor-nc nA nB)
                     {:keys [raster total-coeff]} (decode-regular-block! r nc qp')]
                 (swap! ac-nnz assoc b total-coeff)
                 raster))))
         (range 16))
        recon (add-residual-16x16 luma-pred block-coeffs)
        cb-dc-quad (if (pos? cbp-chroma) (:dc-quad (decode-chroma-dc! r qpc)) [0 0 0 0])
        cr-dc-quad (if (pos? cbp-chroma) (:dc-quad (decode-chroma-dc! r qpc)) [0 0 0 0])
        {cb-block-coeffs :block-coeffs cb-ac-nnz :ac-nnz}
        (decode-chroma-ac-blocks! r qpc cbp-chroma cb-dc-quad (:cb left-mb) (:cb top-mb))
        {cr-block-coeffs :block-coeffs cr-ac-nnz :ac-nnz}
        (decode-chroma-ac-blocks! r qpc cbp-chroma cr-dc-quad (:cr left-mb) (:cr top-mb))
        cb-recon (add-residual-8x8 cb-pred cb-block-coeffs)
        cr-recon (add-residual-8x8 cr-pred cr-block-coeffs)]
    {:recon recon
     :qp qp'
     :inter? true
     :mv mv
     :ac-nnz @ac-nnz
     :top-row (nth recon 15)
     :left-col (mapv #(nth % 15) recon)
     :cb {:recon cb-recon :ac-nnz cb-ac-nnz :top-row (nth cb-recon 7) :left-col (mapv #(nth % 7) cb-recon)}
     :cr {:recon cr-recon :ac-nnz cr-ac-nnz :top-row (nth cr-recon 7) :left-col (mapv #(nth % 7) cr-recon)}}))

(defn- decode-macroblock-p!
  "Decode one macroblock from a P-slice (`macroblock_layer()` after the
   caller has already handled `mb_skip_run` — see `decode-p-slice-mbs!`).
   Reads `mb_type` (P-slice numbering, Table 7-13): 0 = P_L0_16x16
   (`decode-inter-16x16-macroblock!`); 1/2/3/4 = P_L0_L0_16x8/P_L0_L0_8x16/
   P_8x8/P_8x8ref0 (sub-partitioned motion — OUT OF SCOPE, throws
   explicitly rather than mis-decoding, matching this repo's existing
   throw-on-unsupported-mb_type discipline, see `i16x16-mb-info`); >=5 =
   intra macroblock, `intra_mb_type = p_mb_type - 5` (Table 7-13's own
   offset), delegated to `decode-intra-macroblock-body!` (same intra
   decode path an I-slice would use — a P-slice's intra-coded macroblocks
   are pixel-identical to an I-slice's, see that fn's docstring)."
  [r qp chroma-qp-index-offset mb-x mb-y left-mb top-mb topleft-mb topright-mb ref-frame]
  (let [p-mb-type (eg/ue! r)]
    (cond
      (zero? p-mb-type)
      (decode-inter-16x16-macroblock! r qp chroma-qp-index-offset mb-x mb-y left-mb top-mb topleft-mb topright-mb ref-frame)

      (contains? #{1 2 3 4} p-mb-type)
      (throw (ex-info "h264.decode: only P_L0_16x16 (mb_type 0) is supported for P-slice inter macroblocks — P_L0_L0_16x8/P_L0_L0_8x16/P_8x8/P_8x8ref0 (sub-partitioned motion) are out of scope"
                       {:p-mb-type p-mb-type}))

      :else
      (decode-intra-macroblock-body! r (- p-mb-type 5) qp chroma-qp-index-offset left-mb top-mb topleft-mb))))

(defn- decode-p-slice-mbs!
  "Decode all macroblocks of a single P-slice covering the whole picture
   (§7.3.4 `slice_data()`'s CAVLC/`!entropy_coding_mode_flag` branch,
   specialized to this repo's single-slice-per-picture scope — see
   namespace docstring). Because the slice is known in advance to cover
   EXACTLY `mb-width * mb-height` macroblock addresses (no FMO, no
   multi-slice), this loop doesn't need general `more_rbsp_data()` bit-
   position tracking to know when to stop reading `mb_skip_run` vs.
   decoding a `macroblock_layer()`: after consuming one `mb_skip_run`
   worth of P_Skip macroblocks, either the picture is exactly full (done,
   no trailing macroblock_layer — this happens when the LAST run of
   macroblocks in the picture are all skipped) or it isn't (there MUST be
   exactly one more coded macroblock_layer() at the next address, per
   spec's `do { mb_skip_run; ...; if (moreDataFlag) macroblock_layer(); }
   while (moreDataFlag)` structure)."
  [r slice-qp chroma-qp-index-offset mb-width mb-height ref-frame]
  (let [num-mb (* mb-width mb-height)]
    (loop [addr 0 qp slice-qp states []]
      (if (= addr num-mb)
        states
        (let [mb-skip-run (eg/ue! r)
              {:keys [addr states]}
              (loop [i 0 addr addr states states]
                (if (= i mb-skip-run)
                  {:addr addr :states states}
                  (let [mb-x (mod addr mb-width) mb-y (quot addr mb-width)
                        left-mb (when (pos? mb-x) (nth states (dec addr)))
                        top-mb (when (pos? mb-y) (nth states (- addr mb-width)))
                        topleft-mb (when (and (pos? mb-x) (pos? mb-y)) (nth states (- addr mb-width 1)))
                        topright-mb (when (and (pos? mb-y) (< (inc mb-x) mb-width)) (nth states (+ (- addr mb-width) 1)))
                        state (decode-p-skip-macroblock! qp mb-x mb-y left-mb top-mb topleft-mb topright-mb ref-frame)]
                    (recur (inc i) (inc addr) (conj states state)))))]
          (if (= addr num-mb)
            states
            (let [mb-x (mod addr mb-width) mb-y (quot addr mb-width)
                  left-mb (when (pos? mb-x) (nth states (dec addr)))
                  top-mb (when (pos? mb-y) (nth states (- addr mb-width)))
                  topleft-mb (when (and (pos? mb-x) (pos? mb-y)) (nth states (- addr mb-width 1)))
                  topright-mb (when (and (pos? mb-y) (< (inc mb-x) mb-width)) (nth states (+ (- addr mb-width) 1)))
                  state (decode-macroblock-p! r qp chroma-qp-index-offset mb-x mb-y left-mb top-mb topleft-mb topright-mb ref-frame)]
              (recur (inc addr) (:qp state) (conj states state)))))))))

(defn- decode-picture
  "Shared per-picture decode body — parses ONE slice header + macroblock
   loop + luma/Cb/Cr plane assembly — for BOTH I-slice pictures
   (`decode-idr-frame`) and P-slice pictures (`decode-gop`), added as part
   of the P-slice/inter-prediction increment (ADR-2607122000 Migration
   step 7). `ref-frame` is the IMMEDIATELY PRECEDING decoded picture (this
   fn's OWN return shape, {:width :height :luma :cb :cr, ...} — reused
   directly as the single reference frame for a P-slice's motion
   compensation); REQUIRED (non-nil) for P-slices, ignored for I-slices."
  [sps-map pps-map slice-u ref-frame]
  (let [_ (when (pos? (mod (:width sps-map) 16))
            (throw (ex-info "h264.decode: width must be a multiple of 16 (no frame-cropping support)" {:width (:width sps-map)})))
        _ (when (pos? (mod (:height sps-map) 16))
            (throw (ex-info "h264.decode: height must be a multiple of 16 (no frame-cropping support)" {:height (:height sps-map)})))
        _ (when (not= 1 (:chroma-format-idc sps-map))
            (throw (ex-info "h264.decode: only chroma_format_idc=1 (4:2:0) chroma decode is supported"
                             {:chroma-format-idc (:chroma-format-idc sps-map)})))
        mb-width (quot (:width sps-map) 16)
        mb-height (quot (:height sps-map) 16)
        slice-rbsp (rbsp/unescape (:bytes slice-u))
        r (eg/reader slice-rbsp)
        _ (eg/bits! r 8) ; NAL header
        header (slice/parse-header! r sps-map pps-map (:nal-unit-type slice-u) (:nal-ref-idc slice-u))
        slice-class (:slice-type-class header)
        _ (when-not (contains? #{:i :p} slice-class)
            (throw (ex-info "h264.decode: only I-slice or P-slice supported (B/SP/SI are out of scope)"
                             {:slice-type (:slice-type header) :slice-type-class slice-class})))
        _ (when (and (= slice-class :p) (nil? ref-frame))
            (throw (ex-info "h264.decode: a P-slice requires a previously-decoded reference frame" {})))
        _ (when-not (zero? (:first-mb-in-slice header))
            (throw (ex-info "h264.decode: only a single slice covering the whole picture (first_mb_in_slice=0) is supported"
                             {:first-mb-in-slice (:first-mb-in-slice header)})))
        num-mb (* mb-width mb-height)
        chroma-qp-index-offset (:chroma-qp-index-offset pps-map)
        mb-states
        (case slice-class
          :i (loop [addr 0 qp (:slice-qp header) states []]
               (if (= addr num-mb)
                 states
                 (let [mb-x (mod addr mb-width)
                       mb-y (quot addr mb-width)
                       left-mb (when (pos? mb-x) (nth states (dec addr)))
                       top-mb (when (pos? mb-y) (nth states (- addr mb-width)))
                       topleft-mb (when (and (pos? mb-x) (pos? mb-y)) (nth states (- addr mb-width 1)))
                       state (decode-macroblock! r qp chroma-qp-index-offset left-mb top-mb topleft-mb)]
                   (recur (inc addr) (:qp state) (conj states state)))))
          :p (decode-p-slice-mbs! r (:slice-qp header) chroma-qp-index-offset mb-width mb-height ref-frame))
        w (:width sps-map) h (:height sps-map)
        cw (quot w 2) ch (quot h 2)
        assemble (fn [blk-size plane-w plane-h recon-fn]
                   (reduce
                    (fn [plane addr]
                      (let [mb-x (mod addr mb-width)
                            mb-y (quot addr mb-width)
                            recon (recon-fn (nth mb-states addr))]
                        (reduce
                         (fn [plane ry]
                           (reduce
                            (fn [plane rx]
                              (assoc plane (+ (* (+ (* mb-y blk-size) ry) plane-w) (* mb-x blk-size) rx)
                                     (get-in recon [ry rx])))
                            plane (range blk-size)))
                         plane (range blk-size))))
                    (vec (repeat (* plane-w plane-h) 0))
                    (range num-mb)))
        luma (assemble 16 w h #(:recon %))
        cb (assemble 8 cw ch #(:recon (:cb %)))
        cr (assemble 8 cw ch #(:recon (:cr %)))]
    {:width w :height h :luma luma :cb cb :cr cr
     :slice-type-class slice-class
     ;; per-MB Intra16x16 luma pred mode (0=Vertical/1=Horizontal/2=DC) and
     ;; Intra_Chroma pred mode (0=DC/1=Horizontal/2=Vertical, Table 8-5's
     ;; OWN numbering — see `h264.intra-pred`), raster order, one entry per
     ;; macroblock — exposed so callers/tests can assert WHICH prediction
     ;; mode a real encoder actually chose (see test/h264/decode_test.clj's
     ;; multi-macroblock golden vector, which asserts this is non-DC for at
     ;; least one MB rather than just trusting the reconstructed pixels).
     ;; nil for inter (P_Skip/P_L0_16x16) macroblocks — see :mb-inter?/:mb-mvs
     ;; below for those.
     :mb-pred-modes (mapv :pred-mode mb-states)
     :mb-intra-chroma-pred-modes (mapv :intra-chroma-pred-mode mb-states)
     ;; per-MB inter/intra flag + final motion vector (nil for intra MBs) —
     ;; new as of the P-slice addition; always all-false/all-nil for an
     ;; I-slice picture.
     :mb-inter? (mapv #(boolean (:inter? %)) mb-states)
     :mb-mvs (mapv :mv mb-states)}))

(defn decode-idr-frame
  "Decode a single-IDR-I-slice Annex B H.264 elementary stream `annexb-bytes`
   (a byte vector — see `h264.bitstream/nal-units`). Returns {:width
   :height :luma (flat row-major byte vector, width*height, 0..255) :cb
   :cr (flat row-major byte vectors, (width/2)*(height/2), 0..255 —
   ChromaArrayType 1 / 4:2:0 only, see namespace docstring)}.

   Only the FIRST IDR slice NAL in `annexb-bytes` is decoded — any
   subsequent (e.g. P-slice) NALs are ignored, preserving this fn's
   original single-IDR-frame behavior unchanged even when called on a
   multi-picture stream (use `decode-gop` to decode a whole GOP, IDR +
   P-frames, in sequence).

   See namespace docstring for the (deliberately narrow) supported scope."
  [annexb-bytes]
  (let [units (bs/nal-units annexb-bytes)
        sps-u (first (filter #(= :sps (:kind %)) units))
        pps-u (first (filter #(= :pps (:kind %)) units))
        slice-u (first (filter #(= :slice-idr (:kind %)) units))
        _ (when-not sps-u (throw (ex-info "h264.decode: no SPS NAL found" {})))
        _ (when-not pps-u (throw (ex-info "h264.decode: no PPS NAL found" {})))
        _ (when-not slice-u (throw (ex-info "h264.decode: no IDR slice NAL found" {})))
        sps-map (sps/parse (rbsp/unescape (:bytes sps-u)))
        pps-map (pps/parse (rbsp/unescape (:bytes pps-u)))]
    (decode-picture sps-map pps-map slice-u nil)))

(defn decode-gop
  "Decode ALL pictures in `annexb-bytes` (an Annex B elementary stream,
   e.g. a whole GOP: one IDR I-frame followed by zero or more P-frames) in
   bitstream order — ADR-2607122000 Migration step 7 (P_Skip + P_L0_16x16
   inter prediction, REAL sub-pel/non-zero motion compensation via
   `h264.interp`, single reference frame — see namespace docstring's
   \"Pixel decode: P-slice (inter)\" section). Each P-frame's inter
   prediction references the IMMEDIATELY PRECEDING decoded picture as its
   single reference frame (`h264.slice` requires `num_ref_idx_l0_active ==
   1`, throwing otherwise — a real multi-frame reference-picture buffer/DPB
   is out of scope).

   A single SPS/PPS pair (the first of each found in the whole stream) is
   assumed to apply to every picture — matches real encoders, which
   typically emit SPS/PPS once before the IDR frame and don't repeat them
   per P-frame (this repo doesn't handle mid-stream SPS/PPS changes).

   Returns a vector of frame maps, one per decoded picture in bitstream
   order, each the SAME shape `decode-idr-frame` returns (see
   `decode-picture`)."
  [annexb-bytes]
  (let [units (bs/nal-units annexb-bytes)
        sps-u (first (filter #(= :sps (:kind %)) units))
        pps-u (first (filter #(= :pps (:kind %)) units))
        slice-us (filterv #(contains? #{:slice-idr :slice-non-idr} (:kind %)) units)
        _ (when-not sps-u (throw (ex-info "h264.decode: no SPS NAL found" {})))
        _ (when-not pps-u (throw (ex-info "h264.decode: no PPS NAL found" {})))
        _ (when (empty? slice-us) (throw (ex-info "h264.decode: no slice NAL found" {})))
        sps-map (sps/parse (rbsp/unescape (:bytes sps-u)))
        pps-map (pps/parse (rbsp/unescape (:bytes pps-u)))]
    (loop [remaining slice-us ref-frame nil frames []]
      (if (empty? remaining)
        frames
        (let [frame (decode-picture sps-map pps-map (first remaining) ref-frame)]
          (recur (rest remaining) frame (conj frames frame)))))))
