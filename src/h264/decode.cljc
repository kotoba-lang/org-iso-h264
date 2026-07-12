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
        recon (vec (repeat 8 (vec (repeat 8 0))))
        recon (reduce
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
               recon (range 4))]
    {:recon recon
     :top-row (nth recon 7)
     :left-col (mapv #(nth % 7) recon)}))

(defn- decode-macroblock!
  "Decode one Intra_16x16 macroblock (luma + chroma) from reader `r`. `qp`
   is this MB's QPy (already resolved by the caller from slice_qp +
   running mb_qp_delta). `chroma-qp-index-offset` is PPS
   `chroma_qp_index_offset` (see `h264.quant/chroma-qp`; applied to BOTH
   Cb and Cr, see that fn's docstring for why). `left-mb`/`top-mb` are the
   neighbor MB states (or nil if unavailable — picture edge), carrying
   BOTH luma (`:dc-nnz`/`:ac-nnz`/`:top-row`/`:left-col`) and per-component
   chroma (`:cb`/`:cr`, each {:ac-nnz :top-row :left-col}) neighbor state.
   `topleft-mb` is the diagonal top-left neighbor MB state (or nil), used
   ONLY for chroma Plane mode's corner sample (raster-scan single-slice
   decode guarantees it's already reconstructed whenever both `left-mb`
   and `top-mb` are available, see `h264.intra-pred/chroma-plane-grid`).
   Returns {:recon (16x16 luma row-vector grid) :cb :cr (8x8 chroma
   row-vector grids) :dc-nnz :ac-nnz (luma) :cb-ac-nnz :cr-ac-nnz ...}."
  [r qp chroma-qp-index-offset left-mb top-mb topleft-mb]
  (let [mb-type (eg/ue! r)
        {:keys [pred-mode cbp-luma cbp-chroma]} (i16x16-mb-info mb-type)
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
        recon (vec (repeat 16 (vec (repeat 16 0))))
        recon (reduce
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
               recon (range 16))
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
     :top-row (nth recon 15)
     :left-col (mapv #(nth % 15) recon)
     :cb cb
     :cr cr}))

(defn decode-idr-frame
  "Decode a single-IDR-I-slice Annex B H.264 elementary stream `annexb-bytes`
   (a byte vector — see `h264.bitstream/nal-units`). Returns {:width
   :height :luma (flat row-major byte vector, width*height, 0..255) :cb
   :cr (flat row-major byte vectors, (width/2)*(height/2), 0..255 —
   ChromaArrayType 1 / 4:2:0 only, see namespace docstring)}.

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
        pps-map (pps/parse (rbsp/unescape (:bytes pps-u)))
        _ (when (pos? (mod (:width sps-map) 16))
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
        header (slice/parse-header! r sps-map pps-map (:nal-unit-type slice-u))
        _ (when-not (contains? #{2 4 7 9} (:slice-type header))
            (throw (ex-info "h264.decode: only I-slice (slice_type 2/7, or SI 4/9) supported"
                             {:slice-type (:slice-type header)})))
        _ (when-not (zero? (:first-mb-in-slice header))
            (throw (ex-info "h264.decode: only a single slice covering the whole picture (first_mb_in_slice=0) is supported"
                             {:first-mb-in-slice (:first-mb-in-slice header)})))
        num-mb (* mb-width mb-height)
        chroma-qp-index-offset (:chroma-qp-index-offset pps-map)
        mb-states
        (loop [addr 0 qp (:slice-qp header) states []]
          (if (= addr num-mb)
            states
            (let [mb-x (mod addr mb-width)
                  mb-y (quot addr mb-width)
                  left-mb (when (pos? mb-x) (nth states (dec addr)))
                  top-mb (when (pos? mb-y) (nth states (- addr mb-width)))
                  topleft-mb (when (and (pos? mb-x) (pos? mb-y)) (nth states (- addr mb-width 1)))
                  state (decode-macroblock! r qp chroma-qp-index-offset left-mb top-mb topleft-mb)]
              (recur (inc addr) (:qp state) (conj states state)))))
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
     ;; per-MB Intra16x16 luma pred mode (0=Vertical/1=Horizontal/2=DC) and
     ;; Intra_Chroma pred mode (0=DC/1=Horizontal/2=Vertical, Table 8-5's
     ;; OWN numbering — see `h264.intra-pred`), raster order, one entry per
     ;; macroblock — exposed so callers/tests can assert WHICH prediction
     ;; mode a real encoder actually chose (see test/h264/decode_test.clj's
     ;; multi-macroblock golden vector, which asserts this is non-DC for at
     ;; least one MB rather than just trusting the reconstructed pixels).
     :mb-pred-modes (mapv :pred-mode mb-states)
     :mb-intra-chroma-pred-modes (mapv :intra-chroma-pred-mode mb-states)}))
