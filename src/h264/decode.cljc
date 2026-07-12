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
  "H.264 4x4 luma block index (0..15, CAVLC/residual decode order) → [col
   row] (both 0..3) within the macroblock's 4x4-block grid. This exact
   mapping — including which pairs of blocks are grouped together — was
   determined empirically by decoding a real x264-encoded gradient image
   and checking the reconstructed picture against ffmpeg's own decode
   (see `test/h264/decode_test.clj`'s AC-coded golden vector and the task
   session notes/ADR); it is NOT independently re-derivable from casual
   reading of the spec's figures alone, since it must match this
   implementation's OWN `h264.transform/luma-dc-hadamard` index arithmetic
   (itself ported 1:1 from FFmpeg's `x_offset`/`stride` trick) — changing
   one without the other will silently break block placement."
  {0 [0 0], 1 [0 1], 2 [1 0], 3 [1 1]
   4 [0 2], 5 [0 3], 6 [1 2], 7 [1 3]
   8 [2 0], 9 [2 1], 10 [3 0], 11 [3 1]
   12 [2 2], 13 [2 3], 14 [3 2], 15 [3 3]})

(def col-row->blk
  (into {} (map (fn [[k v]] [v k]) blk->col-row)))

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

(defn- decode-chroma-plane!
  "Decode one 8x8 chroma component (Cb or Cr) of an Intra_16x16
   macroblock: optional DC block (`cbp-chroma` >= 1) + optional 4 AC
   blocks (`cbp-chroma` == 2) + Intra_Chroma prediction (`pred-mode`, per
   `h264.intra-pred/predict-chroma-8x8`'s mode numbering) + reconstruction.
   `left-c`/`top-c` are the neighbor MB's SAME-component chroma state (nil
   at picture edges) — {:ac-nnz (4-elem vec) :top-row (8-elem vec)
   :left-col (8-elem vec)}.

   The 4 chroma 4x4 sub-blocks use the SAME block-index → (col,row)
   convention as luma's `blk->col-row` (block 0/1/2/3 restricted to
   col,row in #{0,1} — i.e. block 0=[0,0] 1=[0,1] 2=[1,0] 3=[1,1], the
   same 2x2 pattern used within any one of luma's four 8x8 groups), NOT
   raster order — but `h264.transform/chroma-dc-hadamard`'s `dc-quad`
   output IS raster order (idx = row*2+col, ported directly from ffmpeg's
   stride-addressed 2x2 dequant). These two orderings must be reconciled
   explicitly (`(+ (* row 2) col)`) when overlaying a block's DC value —
   getting this wrong silently produces a right-shaped, wrong-valued
   picture (transposed top-right/bottom-left quadrants), the same failure
   mode called out for luma's `blk->col-row` in this namespace's docstring.

   `corner` is the diagonal top-left MB's bottom-right pixel of this SAME
   component (only used/required for Plane mode, `pred-mode` 3 — see
   `h264.intra-pred/predict-chroma-8x8`).

   Returns {:recon (8x8 row-vector grid) :ac-nnz (4-elem vec) :top-row
   (8-elem vec, this MB's bottom row) :left-col (8-elem vec, this MB's
   right col)}."
  [r qpc cbp-chroma pred-mode left-c top-c corner]
  (let [dc-quad (if (pos? cbp-chroma)
                  (:dc-quad (decode-chroma-dc! r qpc))
                  [0 0 0 0])
        ac-nnz (atom (vec (repeat 4 0)))
        block-coeffs
        (if (< cbp-chroma 2)
          (mapv (fn [b] (let [[col row] (blk->col-row b)]
                          (assoc (vec (repeat 16 0)) 0 (nth dc-quad (+ (* row 2) col)))))
                (range 4))
          (mapv
           (fn [b]
             (let [[col row] (blk->col-row b)
                   nA (if (pos? col)
                        (nth @ac-nnz (col-row->blk [(dec col) row]))
                        (when left-c (nth (:ac-nnz left-c) (col-row->blk [1 row]))))
                   nB (if (pos? row)
                        (nth @ac-nnz (col-row->blk [col (dec row)]))
                        (when top-c (nth (:ac-nnz top-c) (col-row->blk [col 1]))))
                   nc (neighbor-nc nA nB)
                   {:keys [ac-raster total-coeff]} (decode-ac-block! r nc qpc)]
               (swap! ac-nnz assoc b total-coeff)
               (assoc ac-raster 0 (nth dc-quad (+ (* row 2) col)))))
           (range 4)))
        top-row (:top-row top-c)
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
                 (let [[col row] (blk->col-row b)
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
     :ac-nnz @ac-nnz
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
        qp' (+ qp mb-qp-delta)
        qpc (quant/chroma-qp qp' chroma-qp-index-offset)
        dc-nc (neighbor-nc (:dc-nnz left-mb) (:dc-nnz top-mb))
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
        cb-corner (get-in topleft-mb [:cb :recon 7 7])
        cr-corner (get-in topleft-mb [:cr :recon 7 7])
        cb (decode-chroma-plane! r qpc cbp-chroma intra-chroma-pred-mode (:cb left-mb) (:cb top-mb) cb-corner)
        cr (decode-chroma-plane! r qpc cbp-chroma intra-chroma-pred-mode (:cr left-mb) (:cr top-mb) cr-corner)]
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
