(ns h264.encode
  "H.264 baseline-profile Intra_16x16 (LUMA ONLY — see \"Scope\" below)
   reference ENCODER: quantization → forward transform → CAVLC → simple
   mode decision → NAL assembly (ADR-2607122000 `com-junkawasaki/root`
   Migration step 8, the encode-side counterpart of `h264.decode`'s Phase 1
   / \"R0.5\" decoder). Pure cljc, NON-REALTIME, correctness-first reference
   implementation — same tier as `h264.decode`, not a claim of realtime
   encode performance.

   ## Scope (read before assuming this encodes arbitrary images)

   - **Single IDR I-slice covering the whole picture, `mb_type` fixed to
     Intra_16x16** — matches `h264.decode`'s own decode scope exactly (this
     encoder's output is designed to be decoded by THAT decoder, and by any
     spec-conformant real decoder).
   - **LUMA ONLY.** `intra_chroma_pred_mode` is always written 0 (DC) and
     `CodedBlockPatternChroma` is always 0 (via the `mb_type` formula — no
     chroma DC/AC residual is ever coded). This is an explicit, stated
     scope cut (see task/ADR: \"luma+chroma両方対応が理想だが、まずluma onlyで
     動くパイプラインを完成させ\") — decoded Cb/Cr planes will be flat
     DC-predicted output, NOT a reconstruction of the source image's actual
     chroma content. A real decoder (ffmpeg included) will still decode the
     stream correctly (chroma bits are syntactically well-formed, just
     empty), so this doesn't affect stream validity — only chroma pixel
     fidelity, which isn't attempted here.
   - **Simplified mode decision**: for each macroblock, DC/Vertical/
     Horizontal Intra_16x16 prediction (whichever are available given
     neighbor availability) are each tried and the one minimizing SAD
     against the source is chosen. No Plane, no RDO (rate-distortion
     optimization) — a real encoder's mode decision is far more
     sophisticated; this is explicitly the \"簡易モード決定\" the task scope
     allows.
   - **Constant QP across the whole frame** (`mb_qp_delta` is always 0 —
     the slice's single QP, from the PPS's `pic_init_qp`, applies to every
     macroblock uniformly). No per-MB rate control.
   - **`CodedBlockPatternLuma`**: 0 (DC-only) if every block's AC-quantized
     levels are all zero, else 15 (full AC for all 16 4x4 blocks) — mirrors
     the two real values `h264.decode`'s `i16x16-mb-info` recognizes.

   ## Why the AC quantizer here is NOT a memorized textbook MF table

   H.264's encoder-side forward quantization is explicitly NON-NORMATIVE
   (ITU-T H.264 / ISO/IEC 14496-10 — only the DECODER's dequant + inverse
   transform is spec-normative). Real encoders (JM reference software,
   x264) use their own independently-chosen forward-quant (\"MF\") tables.
   Rather than trust a memorized MF/forward-transform pairing — which was
   empirically found (see ADR-2607122000 session notes' probe scripts) to
   leave MEASURABLY MORE cross-coefficient leakage (~20%) than this
   pipeline's own inherent non-orthogonality (~2%, a real, expected
   property of H.264's integer transform, not a bug) — this namespace
   quantizes AC coefficients by solving the EXACT least-squares inverse of
   THIS REPO'S OWN already bit-exact-vs-real-ffmpeg-tested
   `h264.quant/ac-qmul` + `h264.transform/inverse-4x4` pipeline, directly
   from pixel-domain residuals. `level-pixel-matrix` below builds the exact
   16x16 \"integer level → reconstructed pixel\" matrix by probing those
   real functions (impulse level=64, chosen so the dequant step's own
   internal rounding is exact); `ac-solver` inverts the AC (non-DC)
   15-column submatrix via exact Gauss-Jordan elimination
   (`mat-inverse`, using Clojure's native ratio arithmetic on JVM —
   degrades gracefully to double-precision floats on ClojureScript, still
   correct to reasonable precision for this small, diagonally-dominant
   matrix). The luma-DC (Hadamard) path is handled analogously by
   `h264.transform/forward-luma-dc-hadamard`, the exact derived inverse of
   the tested `luma-dc-hadamard` — see that fn's docstring.

   This encoder's neighbor (CAVLC nC / intra-prediction) state is tracked
   from ITS OWN reconstructed pixels (predict + dequantized-residual +
   inverse transform, using the SAME formulas `h264.decode` uses) — NOT the
   original source pixels — matching what any real decoder will
   reconstruct, so subsequent macroblocks' predictions and CAVLC neighbor
   derivation stay in sync with what actually gets decoded.

   New implementation as part of the kotoba-lang reverse-domain
   media/graphics standards-substrate split (com-junkawasaki/root),
   ADR-2607122000 Migration step 8."
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
            [h264.decode :as decode]
            [codec-primitives.scan :as scan]))

(def zigzag scan/zigzag-4x4)
(def ac-zigzag (subvec zigzag 1))

;; --- exact linear algebra (Gauss-Jordan) used to derive the AC quantizer
;;     directly from this repo's OWN tested dequant+inverse-transform
;;     pipeline -- see namespace docstring. ---

(defn- mat-transpose [A]
  (vec (for [j (range (count (first A)))] (vec (for [i (range (count A))] (get-in A [i j]))))))

(defn- mat-mul [A B]
  (vec (for [i (range (count A))]
         (vec (for [j (range (count (first B)))]
                (reduce + (for [k (range (count B))] (* (get-in A [i k]) (get-in B [k j])))))))))

(defn- mat-vec-mul [A v]
  (vec (for [row A] (reduce + (map * row v)))))

(defn- mat-inverse
  "Exact Gauss-Jordan inverse of a square, invertible matrix `A` (entries
   may be integers or ratios)."
  [A]
  (let [n (count A)
        aug (vec (for [i (range n)]
                   (vec (concat (mapv (fn [x] (/ x 1)) (nth A i))
                                (mapv (fn [j] (if (= i j) 1 0)) (range n))))))]
    (loop [m aug col 0]
      (if (= col n)
        (vec (for [row m] (vec (subvec row n (* 2 n)))))
        (let [piv-row (some (fn [r] (when (not (zero? (get-in m [r col]))) r)) (range col n))
              _ (when-not piv-row
                  (throw (ex-info "h264.encode/mat-inverse: singular matrix" {:col col})))
              m1 (if (not= piv-row col) (assoc m col (nth m piv-row) piv-row (nth m col)) m)
              pivot (get-in m1 [col col])
              m2 (assoc m1 col (mapv #(/ % pivot) (nth m1 col)))
              m3 (reduce (fn [acc r]
                           (if (= r col)
                             acc
                             (let [factor (get-in acc [r col])]
                               (if (zero? factor)
                                 acc
                                 (assoc acc r (mapv - (nth acc r) (mapv #(* factor %) (nth acc col))))))))
                         m2 (range n))]
          (recur m3 (inc col)))))))

(defn- level-pixel-matrix
  "16x16 exact matrix M(qp): `M[out][in]` = the REAL, already-tested
   `ac-qmul`+`inverse-4x4` pipeline's pixel-domain response (raster output
   position `out`) to a UNIT integer level at raster coefficient position
   `in`. Probed directly against those functions (impulse `level=64`, which
   makes the dequant step's own internal `+32`/`>>6` rounding EXACT —
   `(64*m+32)>>6 = m` for any integer `m` — then divided by 64 to normalize
   to a per-unit-level response). NOT a memorized/textbook table — see
   namespace docstring."
  [qp]
  (let [baseline (vec (apply concat (transform/inverse-4x4 (vec (repeat 16 0)))))]
    (vec (for [out (range 16)]
           (vec (for [in (range 16)]
                  (let [row (quot in 4) col (mod in 4)
                        m (quant/ac-qmul qp row col)
                        dequant-vec (assoc (vec (repeat 16 0)) in m)
                        recon (vec (apply concat (transform/inverse-4x4 dequant-vec)))]
                    (/ (- (nth recon out) (nth baseline out)) 64))))))))

(def ^:private ac-solver-cache (atom {}))

(defn- ac-solver
  "Memoized per-QP: returns `[MacT MacTMac-inv]`, the precomputed pieces of
   the exact least-squares solve `ac-levels = round((MacT·Mac)^-1 · MacT ·
   target)` for the 15 AC (non-DC, raster positions 1..15) integer levels
   that best reconstruct a target 4x4 pixel-domain residual through the
   REAL `ac-qmul`+`inverse-4x4` pipeline. Position 0 (DC) is excluded from
   `Mac` — that always goes through the Intra16x16 luma-DC Hadamard path
   instead (see `encode-macroblock!`)."
  [qp]
  (or (@ac-solver-cache qp)
      (let [M (level-pixel-matrix qp)
            Mac (vec (for [row M] (vec (rest row))))
            MacT (mat-transpose Mac)
            MacTMac (mat-mul MacT Mac)
            inv (mat-inverse MacTMac)
            result [MacT inv]]
        (swap! ac-solver-cache assoc qp result)
        result)))

(defn- round-nearest
  "Round a real number (integer, Clojure ratio, or float/double) to the
   nearest integer, ties away from negative infinity. Portable — no
   `Math/` interop (this is a `.cljc` file; on ClojureScript `/` yields a
   double rather than an exact ratio, and this fn handles both uniformly
   via `mod`, which has floor semantics on both platforms)."
  [x]
  (let [y (+ (double x) 0.5)
        f (- y (mod y 1))]
    #?(:clj (long f) :cljs f)))

(defn- solve-ac-levels
  "Solve the 15 AC (raster positions 1..15) integer CAVLC levels for one
   4x4 block's target pixel-domain residual (`target-flat`, 16 values,
   raster idx=row*4+col), via the exact least-squares inverse
   (`ac-solver`) of this repo's own tested dequant+inverse-transform
   pipeline. Returns a 15-element vector indexed by (raster-position - 1)."
  [qp target-flat]
  (let [[MacT MacTMac-inv] (ac-solver qp)
        rhs (mat-vec-mul MacT target-flat)
        sol (mat-vec-mul MacTMac-inv rhs)]
    (mapv round-nearest sol)))

(defn- ac-dequant-raster
  "Given 15 AC integer levels (indexed by raster-position-1, i.e. position
   0 excluded) and `qp`, returns the 16-element DEQUANTIZED raster array
   (position 0 = 0 — the caller overlays the real DC value), matching
   `h264.decode/decode-ac-block!`'s own dequant formula exactly."
  [qp ac-levels]
  (vec (for [pos (range 16)]
         (if (zero? pos)
           0
           (let [level (nth ac-levels (dec pos))
                 row (quot pos 4) col (mod pos 4)]
             (if (zero? level) 0 (bit-shift-right (+ (* level (quant/ac-qmul qp row col)) 32) 6)))))))

(defn- neighbor-nc
  "Same neighbor-nC averaging `h264.decode/neighbor-nc` uses (duplicated
   here rather than accessed via private var — a tiny, stable 4-line
   function, see ADR-2607122000 session notes on fork/agent isolation
   preferring small deliberate duplication over reaching into another
   namespace's private internals)."
  [nA nB]
  (cond (and (nil? nA) (nil? nB)) 0
        (nil? nA) nB
        (nil? nB) nA
        :else (quot (+ nA nB 1) 2)))

(defn- clip8 [v] (max 0 (min 255 v)))

(defn- sad
  "Sum of absolute differences between two same-shape row-vector grids."
  [a b]
  (reduce + (map (fn [ra rb] (reduce + (map (fn [x y] (let [d (- x y)] (if (neg? d) (- d) d))) ra rb))) a b)))

(defn- choose-pred-mode
  "Simplified Intra_16x16 mode decision (ADR-2607122000 Migration step 8:
   \"モード決定は簡略化してよい\"): try every mode legal given neighbor
   availability (DC is always legal; Vertical needs a top neighbor;
   Horizontal needs a left neighbor — matching `h264.intra-pred/predict-16x16`'s
   own preconditions) and pick whichever minimizes SAD against the source
   16x16 block. No Plane mode (out of scope, matching `h264.decode`), no
   RDO."
  [src-16x16 top-available? left-available? top-row left-col]
  (let [candidates (cond-> [2] top-available? (conj 0) left-available? (conj 1))
        cost (fn [mode]
               (sad src-16x16 (intra-pred/predict-16x16 mode {:top-available? top-available?
                                                                :left-available? left-available?
                                                                :top-row top-row :left-col left-col})))]
    (apply min-key cost candidates)))

(defn- block-residual
  "Extract the 4x4 pixel-domain residual (raster idx=row*4+col within the
   4x4 block) for luma 4x4 block index `b` (`h264.decode/blk->col-row`
   spatial convention) from a 16x16 `residual` grid."
  [residual b]
  (let [[col row] (decode/blk->col-row b)]
    (vec (for [ry (range 4)] (vec (for [rx (range 4)] (get-in residual [(+ (* row 4) ry) (+ (* col 4) rx)])))))))

(defn- encode-macroblock!
  "Encode one Intra_16x16 luma macroblock (16x16 `src` pixel grid) to
   writer `w` at constant `qp`, given `left-mb`/`top-mb` neighbor state (or
   nil at picture edges — same shape this fn RETURNS, see below). Writes
   `mb_type`/`intra_chroma_pred_mode`(=0)/`mb_qp_delta`(=0)/luma-DC
   CAVLC/luma-AC CAVLC (if `CodedBlockPatternLuma`=15) to `w`. No chroma
   bits are written (luma-only scope, `CodedBlockPatternChroma` is always 0
   via the `mb_type` value itself).

   Returns `{:recon :pred-mode :dc-nnz :ac-nnz :top-row :left-col}` — this
   MB's OWN reconstructed pixels (predict + dequantized-residual + inverse
   transform, the SAME formulas `h264.decode` uses) and CAVLC neighbor
   state, for use as `left-mb`/`top-mb` by subsequent macroblocks."
  [w src qp left-mb top-mb]
  (let [top-available? (some? top-mb)
        left-available? (some? left-mb)
        top-row (:top-row top-mb)
        left-col (:left-col left-mb)
        pred-mode (choose-pred-mode src top-available? left-available? top-row left-col)
        pred (intra-pred/predict-16x16 pred-mode {:top-available? top-available? :left-available? left-available?
                                                    :top-row top-row :left-col left-col})
        residual (mapv (fn [sr pr] (mapv - sr pr)) src pred)
        ;; forward-luma-dc-hadamard's target-dc-per-block[b] must be in the
        ;; SAME domain `dc-per-block[b]` occupies as inverse-4x4's position-0
        ;; input, i.e. the value X such that inverse-4x4([X,0,...,0]) gives a
        ;; uniform pixel correction of avg_b (this block's own mean residual)
        ;; -- per the tested `inverse-4x4-dc-only-is-uniform` relationship
        ;; `uniform-pixel = (X+32)>>6`, X ≈ 64*avg_b = 64*(sum_b/16) = 4*sum_b.
        ;; Plain `sum_b` (the "forward-4x4 DC = sum of samples" convention
        ;; used for AC-position dequant, which is a DIFFERENT domain scaled
        ;; by ac-qmul before inverse-4x4) is 4x too small here and would
        ;; silently under-correct every macroblock's DC by a factor of 4.
        raw-dc (mapv (fn [b] (* 4 (reduce + (apply concat (block-residual residual b))))) (range 16))
        qmul-dc (quant/dc-qmul qp)
        dc-raster (transform/forward-luma-dc-hadamard raw-dc qmul-dc)
        dc-per-block (transform/luma-dc-hadamard dc-raster qmul-dc)
        ac-levels-per-block
        (mapv (fn [b]
                (let [dc-contrib (transform/inverse-4x4 (assoc (vec (repeat 16 0)) 0 (nth dc-per-block b)))
                      resid-b (block-residual residual b)
                      target (vec (for [ry (range 4)]
                                    (vec (for [rx (range 4)]
                                           (- (get-in resid-b [ry rx]) (get-in dc-contrib [ry rx]))))))]
                  (solve-ac-levels qp (vec (apply concat target)))))
              (range 16))
        any-ac-nonzero? (boolean (some (fn [lv] (some (complement zero?) lv)) ac-levels-per-block))
        cbp-luma (if any-ac-nonzero? 15 0)
        mb-type (inc (+ pred-mode (if (= cbp-luma 15) 12 0)))
        ;; §9.2.1 nC derivation for the Intra16x16 luma DC block: it uses
        ;; the SAME neighbor cells as luma AC block 0 (left neighbor's
        ;; block [3,0], top neighbor's block [0,3]) via `:ac-nnz` — NOT a
        ;; separate `:dc-nnz` channel. This MUST mirror
        ;; `h264.decode/decode-macroblock!`'s own (fixed) `dc-nc`
        ;; derivation exactly, or this encoder's CAVLC bits desync on
        ;; decode the moment a neighbor MB has real AC content (see that
        ;; fn's docstring for the full spec/ffmpeg-source rationale).
        dc-nc (neighbor-nc (when left-mb (nth (:ac-nnz left-mb) (decode/col-row->blk [3 0])))
                            (when top-mb (nth (:ac-nnz top-mb) (decode/col-row->blk [0 3]))))
        dc-scanned (scan/scan zigzag dc-raster)]
    (eg/write-ue! w mb-type)
    (eg/write-ue! w 0)   ; intra_chroma_pred_mode = 0 (DC) — luma-only scope
    (eg/write-se! w 0)   ; mb_qp_delta = 0 — constant QP across the frame
    (let [dc-total-coeff (cavlc/encode-residual-block! w dc-nc 16 dc-scanned)
          ac-nnz (atom (vec (repeat 16 0)))
          block-coeffs
          (if (zero? cbp-luma)
            (mapv (fn [b] (assoc (vec (repeat 16 0)) 0 (nth dc-per-block b))) (range 16))
            (mapv
             (fn [b]
               (let [[col row] (decode/blk->col-row b)
                     nA (if (pos? col)
                          (nth @ac-nnz (decode/col-row->blk [(dec col) row]))
                          (when left-mb (nth (:ac-nnz left-mb) (decode/col-row->blk [3 row]))))
                     nB (if (pos? row)
                          (nth @ac-nnz (decode/col-row->blk [col (dec row)]))
                          (when top-mb (nth (:ac-nnz top-mb) (decode/col-row->blk [col 3]))))
                     nc (neighbor-nc nA nB)
                     ac-levels (nth ac-levels-per-block b)
                     ac-scanned (scan/scan ac-zigzag (into [0] ac-levels))
                     total-coeff (cavlc/encode-residual-block! w nc 15 ac-scanned)]
                 (swap! ac-nnz assoc b total-coeff)
                 (assoc (ac-dequant-raster qp ac-levels) 0 (nth dc-per-block b))))
             (range 16)))
          recon (vec (repeat 16 (vec (repeat 16 0))))
          recon (reduce
                 (fn [recon b]
                   (let [[col row] (decode/blk->col-row b)
                         pred4x4 (vec (for [ry (range 4)] (vec (for [rx (range 4)] (get-in pred [(+ (* row 4) ry) (+ (* col 4) rx)])))))
                         resid4x4 (transform/inverse-4x4 (nth block-coeffs b))]
                     (reduce
                      (fn [recon ry]
                        (reduce
                         (fn [recon rx]
                           (assoc-in recon [(+ (* row 4) ry) (+ (* col 4) rx)]
                                     (clip8 (+ (get-in pred4x4 [ry rx]) (get-in resid4x4 [ry rx])))))
                         recon (range 4)))
                      recon (range 4))))
                 recon (range 16))]
      {:recon recon
       :pred-mode pred-mode
       :dc-nnz dc-total-coeff
       :ac-nnz @ac-nnz
       :top-row (nth recon 15)
       :left-col (mapv #(nth % 15) recon)})))

(defn- plane->grid
  "Flat row-major byte vector (width*height) → vector of `height` row
   vectors (each `width` long)."
  [plane width height]
  (vec (for [y (range height)] (subvec plane (* y width) (+ (* y width) width)))))

(defn encode-idr-luma-frame
  "Encode a single IDR I-slice, Intra_16x16-only, LUMA-ONLY (see namespace
   docstring) H.264 Annex B elementary stream from `{:width :height :qp
   :luma}` — `width`/`height` must be exact multiples of 16 (no
   frame-cropping, mirrors `h264.sps/encode`'s own scope), `luma` a flat
   row-major byte vector (`width*height`, values 0..255), `qp` the
   (constant-across-the-frame) QP.

   Returns `{:bytes (Annex B byte vector) :mb-states (per-MB encode state,
   raster order — pred-mode/dc-nnz/ac-nnz/recon, exposed for tests)}`."
  [{:keys [width height qp luma]}]
  (when (pos? (mod width 16))
    (throw (ex-info "h264.encode: width must be a multiple of 16" {:width width})))
  (when (pos? (mod height 16))
    (throw (ex-info "h264.encode: height must be a multiple of 16" {:height height})))
  (let [mb-width (quot width 16)
        mb-height (quot height 16)
        num-mb (* mb-width mb-height)
        luma-grid (plane->grid luma width height)
        sps-rbsp (sps/encode {:profile-idc 66 :level-idc 30 :seq-parameter-set-id 0
                               :width width :height height})
        pps-rbsp (pps/encode {:pic-init-qp qp})
        sps-map (sps/parse (rbsp/unescape sps-rbsp))
        pps-map (pps/parse (rbsp/unescape pps-rbsp))
        w (eg/writer)
        _ (eg/write-bits! w 8 (bit-or (bit-shift-left 3 5) 5)) ; NAL header: nal_ref_idc=3, nal_unit_type=5 (IDR)
        _ (slice/encode-header! w sps-map pps-map {:frame-num 0 :idr-pic-id 0 :slice-qp qp})
        mb-states
        (loop [addr 0 states []]
          (if (= addr num-mb)
            states
            (let [mb-x (mod addr mb-width)
                  mb-y (quot addr mb-width)
                  src (vec (for [ry (range 16)] (subvec (nth luma-grid (+ (* mb-y 16) ry)) (* mb-x 16) (+ (* mb-x 16) 16))))
                  left-mb (when (pos? mb-x) (nth states (dec addr)))
                  top-mb (when (pos? mb-y) (nth states (- addr mb-width)))
                  state (encode-macroblock! w src qp left-mb top-mb)]
              (recur (inc addr) (conj states state)))))
        _ (eg/rbsp-trailing-bits! w)
        slice-bytes (eg/bytes! w)
        stream (bs/write-annexb-stream [(bs/write-nal-unit sps-rbsp)
                                         (bs/write-nal-unit pps-rbsp)
                                         (bs/write-nal-unit slice-bytes)])]
    {:bytes stream :mb-states mb-states}))
