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
   - **LUMA AND CHROMA (Cb/Cr), ChromaArrayType 1 (4:2:0) only** (chroma
     encode addition, ADR-2607122000 Migration step 8 follow-up). Real
     chroma DC (2x2 Hadamard, `nC==-1` special CAVLC table) and chroma AC
     (regular neighbor-derived 4x4 blocks, QPc-scaled) residual is encoded
     from the actual `:cb`/`:cr` source planes — decoded Cb/Cr planes are a
     real (lossy, quantization-bounded) reconstruction of the source's
     chroma content, not a flat DC-only placeholder. `:cb`/`:cr` are
     OPTIONAL in `encode-idr-luma-frame`'s input map — when omitted, they
     default to flat 128-valued planes (preserving this fn's original
     luma-only call shape/behavior for existing callers/tests: DC
     prediction against a locally-constant 128 source always gives zero
     residual, so `CodedBlockPatternChroma` degenerates to 0 exactly as the
     original luma-only encoder always wrote it).
   - **Chroma intra prediction mode decision: DC/Horizontal/Vertical only**
     (Table 8-5 numbering, matching `h264.intra-pred/predict-chroma-8x8`) —
     no Plane (mode 3). This mirrors the luma mode decision's own SAD-only,
     no-RDO simplification (see below) and is a DELIBERATE scope cut,
     unlike `h264.decode`'s chroma path (which DOES implement Plane,
     because a real encoder — libx264 — was observed choosing it even for
     near-flat content, see `h264.intra-pred`'s docstring): this simplified
     reference ENCODER never emits `intra_chroma_pred_mode`=3, so nothing
     here exercises decode's Plane path. `intra_chroma_pred_mode` is a
     SINGLE syntax element shared by both Cb and Cr (§7.3.5.1) — mode
     decision jointly minimizes COMBINED Cb+Cr SAD, not per-component.
   - **Simplified mode decision**: for each macroblock, DC/Vertical/
     Horizontal Intra_16x16 prediction (whichever are available given
     neighbor availability) are each tried and the one minimizing SAD
     against the source is chosen. No Plane, no RDO (rate-distortion
     optimization) — a real encoder's mode decision is far more
     sophisticated; this is explicitly the \"簡易モード決定\" the task scope
     allows.
   - **Constant QP across the whole frame** (`mb_qp_delta` is always 0 —
     the slice's single QP, from the PPS's `pic_init_qp`, applies to every
     macroblock uniformly). No per-MB rate control. QPc (`h264.quant/chroma-qp`,
     from PPS `chroma_qp_index_offset`) is therefore ALSO constant across
     the whole frame (derived once from the constant QPy, since
     `mb_qp_delta`=0 means QPy never actually varies per-MB despite the
     bitstream nominally allowing it).
   - **`CodedBlockPatternLuma`**: 0 (DC-only) if every block's AC-quantized
     levels are all zero, else 15 (full AC for all 16 4x4 blocks) — mirrors
     the two real values `h264.decode`'s `i16x16-mb-info` recognizes.
   - **`CodedBlockPatternChroma`**: 0 (no chroma residual at all) if BOTH
     components' quantized DC levels are all zero, 1 (DC only) if some DC
     level is nonzero but no AC level is, else 2 (DC+AC) — mirrors
     `h264.decode/i16x16-mb-info`'s three real values (`(mod (quot m 4) 3)`).

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
            [h264.interp :as interp]
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

;; --- P-slice inter residual quantization (Wave 6 encode increment,
;;     ADR-2607122000 Migration step 7's encode-side counterpart). An inter
;;     (P_L0_16x16) 4x4 luma block is a FULL 16-coefficient "regular" block
;;     (`h264.decode/decode-regular-block!`) — unlike Intra16x16, there is
;;     NO separate macroblock-level DC/Hadamard block; raster position 0
;;     dequantizes via the SAME per-position `ac-qmul` formula as every
;;     other position (already true of `level-pixel-matrix` above — it was
;;     built by probing `ac-qmul`/`inverse-4x4` at EVERY raster position
;;     0..15 uniformly, so no new probing is needed, just a solver that
;;     DOESN'T exclude column 0 the way `ac-solver` deliberately does for
;;     the Intra16x16 AC path). ---

(def ^:private regular-solver-cache (atom {}))

(defn- regular-solver
  "Memoized per-QP: like `ac-solver`, but over the FULL 16x16
   `level-pixel-matrix` (no DC column excluded) — the exact least-squares
   solve for all 16 raster-position integer levels of an inter (or I_NxN)
   regular 4x4 residual block."
  [qp]
  (or (@regular-solver-cache qp)
      (let [M (level-pixel-matrix qp)
            MT (mat-transpose M)
            MTM (mat-mul MT M)
            inv (mat-inverse MTM)
            result [MT inv]]
        (swap! regular-solver-cache assoc qp result)
        result)))

(defn- solve-regular-levels
  "Solve all 16 raster-position integer CAVLC levels for one 4x4 inter
   block's target pixel-domain residual (`target-flat`, 16 values, raster
   idx=row*4+col), via `regular-solver`. Returns a 16-element vector indexed
   by RASTER position directly (unlike `solve-ac-levels`, which is shifted
   by 1 since it excludes the DC position)."
  [qp target-flat]
  (let [[MT inv] (regular-solver qp)
        rhs (mat-vec-mul MT target-flat)
        sol (mat-vec-mul inv rhs)]
    (mapv round-nearest sol)))

(defn- regular-dequant-raster
  "Given 16 raster-position integer levels (from `solve-regular-levels`) and
   `qp`, returns the 16-element DEQUANTIZED raster array — matches
   `h264.decode/decode-regular-block!`'s own dequant formula exactly (EVERY
   position, including 0, through `ac-qmul` — no DC overlay, unlike
   `ac-dequant-raster`)."
  [qp levels]
  (vec (for [pos (range 16)]
         (let [level (nth levels pos)]
           (if (zero? level)
             0
             (let [row (quot pos 4) col (mod pos 4)]
               (bit-shift-right (+ (* level (quant/ac-qmul qp row col)) 32) 6)))))))

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

(defn- chroma-block-residual
  "Extract the 4x4 pixel-domain residual (raster idx=row*4+col within the
   4x4 block) for chroma 4x4 sub-block index `b` (`h264.decode/chroma-blk->col-row`
   spatial convention — plain 2x2 raster, NOT the same block-index
   convention as luma's `blk->col-row`, see that def's docstring) from an
   8x8 `residual` grid (one Cb or Cr component)."
  [residual b]
  (let [[col row] (decode/chroma-blk->col-row b)]
    (vec (for [ry (range 4)] (vec (for [rx (range 4)] (get-in residual [(+ (* row 4) ry) (+ (* col 4) rx)])))))))

(defn- choose-chroma-pred-mode
  "Simplified Intra_Chroma mode decision (same style/scope as
   `choose-pred-mode`'s luma decision): try every mode legal given neighbor
   availability (DC always legal; Vertical needs a top neighbor; Horizontal
   needs a left neighbor — `h264.intra-pred/predict-chroma-8x8`'s Table 8-5
   numbering: 0=DC/1=Horizontal/2=Vertical, a DIFFERENT permutation than
   luma's own mode numbers) and pick whichever minimizes COMBINED Cb+Cr SAD
   against the source 8x8 blocks — `intra_chroma_pred_mode` is a SINGLE
   syntax element shared by both components (§7.3.5.1), so mode decision
   must jointly optimize both, not independently per-component. No Plane
   mode (mode 3, out of scope — see namespace docstring), no RDO."
  [src-cb-8x8 src-cr-8x8 top-available? left-available? cb-top-row cb-left-col cr-top-row cr-left-col]
  (let [candidates (cond-> [0] top-available? (conj 2) left-available? (conj 1))
        pred (fn [mode top-row left-col]
               (intra-pred/predict-chroma-8x8 mode {:top-available? top-available? :left-available? left-available?
                                                      :top-row top-row :left-col left-col}))
        cost (fn [mode]
               (+ (sad src-cb-8x8 (pred mode cb-top-row cb-left-col))
                  (sad src-cr-8x8 (pred mode cr-top-row cr-left-col))))]
    (apply min-key cost candidates)))

(defn- chroma-component-plan
  "Compute the per-component (Cb or Cr) chroma DC/AC quantization plan for
   one macroblock, given its 8x8 pixel-domain `residual` (src - chosen-mode
   prediction, ALREADY computed by the caller — mode decision is joint
   across both components, see `choose-chroma-pred-mode`) and this frame's
   constant `qpc`. Returns `{:dc-raster :dc-quad :ac-levels-per-block}` —
   `dc-raster` (4 raw CAVLC-ready integer levels, raster order, NO zigzag —
   see `h264.transform/forward-chroma-dc-hadamard`), `dc-quad` (the same 4
   values DEQUANTIZED back through the real `chroma-dc-hadamard`, i.e. the
   transform-domain DC coefficient to overlay at position 0 of each
   quadrant's own 4x4 coefficient array — mirrors `encode-macroblock!`'s
   luma `dc-per-block`), `ac-levels-per-block` (4-element vector, each a
   15-element AC level vector from `solve-ac-levels`, mirroring the luma AC
   solve but with `qpc` instead of `qp` — the underlying `ac-qmul`+
   `inverse-4x4` pipeline is IDENTICAL for luma and chroma, only the QP
   input differs, so the same exact-least-squares solver applies unchanged)."
  [residual qpc]
  (let [raw-dc (mapv (fn [b] (* 4 (reduce + (apply concat (chroma-block-residual residual b))))) (range 4))
        qmul-dc (quant/dc-qmul qpc)
        dc-raster (transform/forward-chroma-dc-hadamard raw-dc qmul-dc)
        dc-quad (transform/chroma-dc-hadamard dc-raster qmul-dc)
        ac-levels-per-block
        (mapv (fn [b]
                (let [dc-contrib (transform/inverse-4x4 (assoc (vec (repeat 16 0)) 0 (nth dc-quad b)))
                      resid-b (chroma-block-residual residual b)
                      target (vec (for [ry (range 4)]
                                    (vec (for [rx (range 4)]
                                           (- (get-in resid-b [ry rx]) (get-in dc-contrib [ry rx]))))))]
                  (solve-ac-levels qpc (vec (apply concat target)))))
              (range 4))]
    {:dc-raster dc-raster :dc-quad dc-quad :ac-levels-per-block ac-levels-per-block}))

(defn- encode-chroma-ac-blocks!
  "Write the 4 chroma AC 4x4 blocks for ONE component to writer `w` (mirrors
   `h264.decode/decode-chroma-ac-blocks!`'s READ side exactly, including its
   documented bitstream-order precondition — callers must have already
   written BOTH components' DC blocks before calling this for either
   component, see `encode-macroblock!`). `ac-levels-per-block`/`dc-quad` are
   this component's own `chroma-component-plan` output; `left-c`/`top-c` are
   the neighbor MB's SAME-component chroma state (nil at picture edges).

   Returns `{:block-coeffs (4-elem vector of 16-elem row-major coefficient
   arrays) :ac-nnz (4-elem vec, this component's per-block total_coeff)}` —
   same shape `decode-chroma-ac-blocks!` returns, for use as this MB's own
   `:cb`/`:cr` neighbor state and for chroma reconstruction below."
  [w qpc cbp-chroma dc-quad ac-levels-per-block left-c top-c]
  (let [ac-nnz (atom (vec (repeat 4 0)))
        block-coeffs
        (if (< cbp-chroma 2)
          (mapv (fn [b] (assoc (vec (repeat 16 0)) 0 (nth dc-quad b)))
                (range 4))
          (mapv
           (fn [b]
             (let [[col row] (decode/chroma-blk->col-row b)
                   nA (if (pos? col)
                        (nth @ac-nnz (decode/chroma-col-row->blk [(dec col) row]))
                        (when left-c (nth (:ac-nnz left-c) (decode/chroma-col-row->blk [1 row]))))
                   nB (if (pos? row)
                        (nth @ac-nnz (decode/chroma-col-row->blk [col (dec row)]))
                        (when top-c (nth (:ac-nnz top-c) (decode/chroma-col-row->blk [col 1]))))
                   nc (neighbor-nc nA nB)
                   ac-levels (nth ac-levels-per-block b)
                   ac-scanned (scan/scan ac-zigzag (into [0] ac-levels))
                   total-coeff (cavlc/encode-residual-block! w nc 15 ac-scanned)]
               (swap! ac-nnz assoc b total-coeff)
               (assoc (ac-dequant-raster qpc ac-levels) 0 (nth dc-quad b))))
           (range 4)))]
    {:block-coeffs block-coeffs :ac-nnz @ac-nnz}))

(defn- reconstruct-chroma-plane
  "Pure (no bit writes) Intra_Chroma prediction + residual reconstruction
   for ONE 8x8 chroma component, given its `block-coeffs`
   (`encode-chroma-ac-blocks!`'s output) — mirrors
   `h264.decode/reconstruct-chroma-plane` exactly (duplicated here rather
   than reaching into that namespace's private var, same small-deliberate-
   duplication convention `neighbor-nc` above already uses). `pred-mode` is
   the jointly-chosen Intra_Chroma mode (Table 8-5 numbering, no Plane —
   see `choose-chroma-pred-mode`).

   Returns `{:recon (8x8 row-vector grid) :top-row (8-elem vec) :left-col
   (8-elem vec)}`."
  [block-coeffs pred-mode top-available? left-available? top-row left-col]
  (let [pred-8x8 (intra-pred/predict-chroma-8x8 pred-mode {:top-available? top-available?
                                                            :left-available? left-available?
                                                            :top-row top-row :left-col left-col})
        recon (vec (repeat 8 (vec (repeat 8 0))))
        recon (reduce
               (fn [recon b]
                 (let [[col row] (decode/chroma-blk->col-row b)
                       pred4x4 (vec (for [ry (range 4)] (vec (for [rx (range 4)] (get-in pred-8x8 [(+ (* row 4) ry) (+ (* col 4) rx)])))))
                       residual (transform/inverse-4x4 (nth block-coeffs b))]
                   (reduce
                    (fn [recon ry]
                      (reduce
                       (fn [recon rx]
                         (assoc-in recon [(+ (* row 4) ry) (+ (* col 4) rx)]
                                   (clip8 (+ (get-in pred4x4 [ry rx]) (get-in residual [ry rx])))))
                       recon (range 4)))
                    recon (range 4))))
               recon (range 4))]
    {:recon recon
     :top-row (nth recon 7)
     :left-col (mapv #(nth % 7) recon)}))

(defn- encode-macroblock!
  "Encode one Intra_16x16 macroblock (16x16 luma `src` grid, 8x8 `src-cb`/
   `src-cr` chroma grids) to writer `w` at constant `qp`/`qpc`, given
   `left-mb`/`top-mb` neighbor state (or nil at picture edges — same shape
   this fn RETURNS, see below). Writes `mb_type`/`intra_chroma_pred_mode`/
   `mb_qp_delta`(=0)/luma-DC CAVLC/luma-AC CAVLC (if `CodedBlockPatternLuma`
   =15)/chroma-DC CAVLC (Cb then Cr, if `CodedBlockPatternChroma`>=1)/
   chroma-AC CAVLC (Cb x4 then Cr x4, if `CodedBlockPatternChroma`=2) to
   `w` — this exact ordering (`§7.3.5.3.3`/`h264.decode/decode-chroma-ac-blocks!`'s
   documented bitstream order) MUST match decode's read order or the stream
   silently desyncs on the first macroblock with real chroma AC.

   Returns `{:recon :pred-mode :dc-nnz :ac-nnz :top-row :left-col :intra-chroma-pred-mode
   :cb :cr}` — this MB's OWN reconstructed pixels (predict +
   dequantized-residual + inverse transform, the SAME formulas
   `h264.decode` uses) and CAVLC neighbor state (luma AND per-component
   chroma), for use as `left-mb`/`top-mb` by subsequent macroblocks."
  [w src src-cb src-cr qp qpc left-mb top-mb]
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
        ;; --- chroma: joint mode decision (single intra_chroma_pred_mode
        ;;     shared by Cb+Cr), then per-component DC/AC quantization plan
        ;;     against that SAME chosen prediction (mirrors luma's own
        ;;     predict -> residual -> DC -> AC pipeline exactly, just at 8x8/
        ;;     4-quadrant granularity instead of 16x16/16-block). ---
        cb-top-mb (:cb top-mb) cb-left-mb (:cb left-mb)
        cr-top-mb (:cr top-mb) cr-left-mb (:cr left-mb)
        cb-top-row (:top-row cb-top-mb) cb-left-col (:left-col cb-left-mb)
        cr-top-row (:top-row cr-top-mb) cr-left-col (:left-col cr-left-mb)
        intra-chroma-pred-mode (choose-chroma-pred-mode src-cb src-cr top-available? left-available?
                                                         cb-top-row cb-left-col cr-top-row cr-left-col)
        chroma-pred-opts {:top-available? top-available? :left-available? left-available?}
        cb-pred (intra-pred/predict-chroma-8x8 intra-chroma-pred-mode (assoc chroma-pred-opts :top-row cb-top-row :left-col cb-left-col))
        cr-pred (intra-pred/predict-chroma-8x8 intra-chroma-pred-mode (assoc chroma-pred-opts :top-row cr-top-row :left-col cr-left-col))
        cb-residual (mapv (fn [sr pr] (mapv - sr pr)) src-cb cb-pred)
        cr-residual (mapv (fn [sr pr] (mapv - sr pr)) src-cr cr-pred)
        {cb-dc-raster :dc-raster cb-dc-quad :dc-quad cb-ac-levels-per-block :ac-levels-per-block} (chroma-component-plan cb-residual qpc)
        {cr-dc-raster :dc-raster cr-dc-quad :dc-quad cr-ac-levels-per-block :ac-levels-per-block} (chroma-component-plan cr-residual qpc)
        any-chroma-dc-nonzero? (boolean (or (some (complement zero?) cb-dc-raster) (some (complement zero?) cr-dc-raster)))
        any-chroma-ac-nonzero? (boolean (or (some (fn [lv] (some (complement zero?) lv)) cb-ac-levels-per-block)
                                             (some (fn [lv] (some (complement zero?) lv)) cr-ac-levels-per-block)))
        cbp-chroma (cond any-chroma-ac-nonzero? 2 any-chroma-dc-nonzero? 1 :else 0)
        mb-type (inc (+ pred-mode (* 4 cbp-chroma) (if (= cbp-luma 15) 12 0)))
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
    (eg/write-ue! w intra-chroma-pred-mode)
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
                 recon (range 16))
          ;; Bitstream order (§7.3.5.3.3 / `h264.decode/decode-macroblock!`):
          ;; Cb DC, Cr DC, Cb AC x4, Cr AC x4 — NOT Cb(DC+AC) then Cr(DC+AC).
          _ (when (pos? cbp-chroma)
              (cavlc/encode-residual-block! w :chroma-dc 4 cb-dc-raster)
              (cavlc/encode-residual-block! w :chroma-dc 4 cr-dc-raster))
          {cb-block-coeffs :block-coeffs cb-ac-nnz :ac-nnz}
          (encode-chroma-ac-blocks! w qpc cbp-chroma cb-dc-quad cb-ac-levels-per-block cb-left-mb cb-top-mb)
          {cr-block-coeffs :block-coeffs cr-ac-nnz :ac-nnz}
          (encode-chroma-ac-blocks! w qpc cbp-chroma cr-dc-quad cr-ac-levels-per-block cr-left-mb cr-top-mb)
          cb-recon (reconstruct-chroma-plane cb-block-coeffs intra-chroma-pred-mode top-available? left-available? cb-top-row cb-left-col)
          cr-recon (reconstruct-chroma-plane cr-block-coeffs intra-chroma-pred-mode top-available? left-available? cr-top-row cr-left-col)
          cb (assoc cb-recon :ac-nnz cb-ac-nnz)
          cr (assoc cr-recon :ac-nnz cr-ac-nnz)]
      {:recon recon
       :pred-mode pred-mode
       :intra-chroma-pred-mode intra-chroma-pred-mode
       :dc-nnz dc-total-coeff
       :ac-nnz @ac-nnz
       :top-row (nth recon 15)
       :left-col (mapv #(nth % 15) recon)
       :cb cb
       :cr cr})))

(defn- plane->grid
  "Flat row-major byte vector (width*height) → vector of `height` row
   vectors (each `width` long)."
  [plane width height]
  (vec (for [y (range height)] (subvec plane (* y width) (+ (* y width) width)))))

(defn encode-idr-luma-frame
  "Encode a single IDR I-slice, Intra_16x16-only H.264 Annex B elementary
   stream from `{:width :height :qp :luma :cb :cr}` — `width`/`height` must
   be exact multiples of 16 (no frame-cropping, mirrors `h264.sps/encode`'s
   own scope), `luma` a flat row-major byte vector (`width*height`, values
   0..255), `qp` the (constant-across-the-frame) QP. `cb`/`cr` are OPTIONAL
   flat row-major byte vectors, ChromaArrayType 1 (4:2:0) — each
   `(width/2)*(height/2)`, values 0..255 — encoding REAL chroma residual
   (see namespace docstring); when omitted they default to flat 128-valued
   planes, which (since DC prediction against a locally-constant 128 source
   always gives zero residual) preserves this fn's original luma-only call
   shape/behavior for existing callers (no chroma bits beyond a DC-only
   `CodedBlockPatternChroma`=0 declaration, same as before this addition).

   Returns `{:bytes (Annex B byte vector) :mb-states (per-MB encode state,
   raster order — pred-mode/intra-chroma-pred-mode/dc-nnz/ac-nnz/recon/cb/cr,
   exposed for tests)}`."
  [{:keys [width height qp luma cb cr]}]
  (when (pos? (mod width 16))
    (throw (ex-info "h264.encode: width must be a multiple of 16" {:width width})))
  (when (pos? (mod height 16))
    (throw (ex-info "h264.encode: height must be a multiple of 16" {:height height})))
  (let [mb-width (quot width 16)
        mb-height (quot height 16)
        num-mb (* mb-width mb-height)
        cw (quot width 2) ch (quot height 2)
        cb (or cb (vec (repeat (* cw ch) 128)))
        cr (or cr (vec (repeat (* cw ch) 128)))
        luma-grid (plane->grid luma width height)
        cb-grid (plane->grid cb cw ch)
        cr-grid (plane->grid cr cw ch)
        sps-rbsp (sps/encode {:profile-idc 66 :level-idc 30 :seq-parameter-set-id 0
                               :width width :height height})
        pps-rbsp (pps/encode {:pic-init-qp qp})
        sps-map (sps/parse (rbsp/unescape sps-rbsp))
        pps-map (pps/parse (rbsp/unescape pps-rbsp))
        ;; QPc is constant across the whole frame here — `mb_qp_delta` is
        ;; always 0 (see namespace docstring), so QPy never actually varies
        ;; per-MB despite the bitstream nominally allowing it, and QPc is a
        ;; pure function of QPy + the PPS's (also constant) chroma_qp_index_offset.
        qpc (quant/chroma-qp qp (:chroma-qp-index-offset pps-map))
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
                  src-cb (vec (for [ry (range 8)] (subvec (nth cb-grid (+ (* mb-y 8) ry)) (* mb-x 8) (+ (* mb-x 8) 8))))
                  src-cr (vec (for [ry (range 8)] (subvec (nth cr-grid (+ (* mb-y 8) ry)) (* mb-x 8) (+ (* mb-x 8) 8))))
                  left-mb (when (pos? mb-x) (nth states (dec addr)))
                  top-mb (when (pos? mb-y) (nth states (- addr mb-width)))
                  state (encode-macroblock! w src src-cb src-cr qp qpc left-mb top-mb)]
              (recur (inc addr) (conj states state)))))
        _ (eg/rbsp-trailing-bits! w)
        slice-bytes (eg/bytes! w)
        stream (bs/write-annexb-stream [(bs/write-nal-unit sps-rbsp)
                                         (bs/write-nal-unit pps-rbsp)
                                         (bs/write-nal-unit slice-bytes)])]
    {:bytes stream :mb-states mb-states}))

;; --- P-slice (inter) encode (Wave 6 encode increment, ADR-2607122000
;;     Migration step 7's encode-side counterpart of `h264.decode/decode-gop`'s
;;     P_Skip/P_L0_16x16 decode support). Scope, deliberately narrow (mirrors
;;     `h264.decode`'s own P-slice decode scope exactly, so this encoder's
;;     output is designed to be decoded by THAT decoder and by any real
;;     spec-conformant decoder):
;;
;;     - Single reference frame: every P-frame's motion estimation/
;;       compensation references the IMMEDIATELY PRECEDING frame in the GOP
;;       (the OWN reconstructed pixels of that frame, not the original
;;       source — matching what a real decoder will actually have available,
;;       same "encoder reconstructs from its own dequantized output" design
;;       already used for intra neighbor state above).
;;     - Two mb_types: P_Skip (mv = the P_Skip predictor, §8.4.1.1, NO
;;       residual) and P_L0_16x16 (one 16x16 partition, one motion vector,
;;       real CAVLC-coded luma+chroma residual) — no sub-partitioned motion
;;       (P_L0_L0_16x8/P_L0_L0_8x16/P_8x8/P_8x8ref0), matching
;;       `h264.decode/decode-macroblock-p!`'s own throw-on-unsupported scope.
;;       No intra-in-P macroblocks on the ENCODE side (mode decision here
;;       never emits one, even though decode supports them) — this
;;       encoder's motion estimation always finds SOME candidate MV, so
;;       there's no RD reason built into this simplified mode decision to
;;       fall back to intra.
;;     - Motion estimation: integer-pel full search over a small
;;       `search-range` (default ±8 pixels, luma-SAD-minimizing, checked
;;       against `h264.interp/mc-luma-block` so the SAME sub-pel-capable
;;       interpolation the decoder uses is what the search itself is scored
;;       against — no separate "integer-only" fast-path approximation), THEN
;;       a small quarter-pel LOCAL refinement (`me-subpel-refine`, ±3
;;       quarter-samples around the best integer position) — REAL sub-pel
;;       motion vectors are supported end-to-end (this repo's decode side
;;       already handles any (fx,fy), see `h264.interp`), not just a
;;       documented gap.
;;     - P_Skip decision: chosen whenever the P_Skip-predictor MV
;;       (`p-skip-mv`, the SAME §8.4.1.1 predictor `h264.decode` uses)
;;       already gives EXACT (zero-SAD) luma prediction — i.e. whenever no
;;       residual would be needed anyway, so spending bits on an explicit
;;       P_L0_16x16 mb_type + mvd + coded_block_pattern=0 would be strictly
;;       wasteful. This is intentionally a much simpler rule than a real
;;       encoder's full RD mode decision (which would also skip for small-
;;       but-nonzero residual, trading a little quality for bits) — see
;;       `encode-p-slice-mbs!`'s docstring.
;;     - Constant QP across the whole frame, same as `encode-idr-luma-frame`.

;; --- P-slice inter residual is a "regular" 4x4 block for EVERY luma 4x4
;;     block (no separate DC/Hadamard split) but chroma residual is
;;     UNCHANGED from the intra path (`h264.decode`'s own docstring: "chroma
;;     DC(2x2 Hadamard)+AC residual structure doesn't depend on the luma
;;     macroblock type at all") — so `chroma-component-plan`/
;;     `encode-chroma-ac-blocks!` above are reused VERBATIM here, just fed a
;;     motion-compensated (not intra) prediction. ---

(defn- overlay-4x4-blocks
  "Pure (no bit writes) residual reconstruction: `pred-grid` (row-vector
   grid, `grid-size`x`grid-size`) + inverse-transformed `block-coeffs`
   (`num-blocks` 4x4 arrays, indexed per `col-row-fn`'s convention) → full
   reconstructed grid. Shared by luma (16 blocks, `decode/blk->col-row`) and
   chroma (4 blocks, `decode/chroma-blk->col-row`) inter reconstruction —
   mirrors `h264.decode/add-residual-16x16`/`add-residual-8x8` (private
   there too, hence this small deliberate duplication, same convention
   `neighbor-nc` above already uses)."
  [pred-grid block-coeffs num-blocks grid-size col-row-fn]
  (reduce
   (fn [recon b]
     (let [[col row] (col-row-fn b)
           pred4x4 (vec (for [ry (range 4)] (vec (for [rx (range 4)] (get-in pred-grid [(+ (* row 4) ry) (+ (* col 4) rx)])))))
           residual (transform/inverse-4x4 (nth block-coeffs b))]
       (reduce
        (fn [recon ry]
          (reduce
           (fn [recon rx]
             (assoc-in recon [(+ (* row 4) ry) (+ (* col 4) rx)]
                       (clip8 (+ (get-in pred4x4 [ry rx]) (get-in residual [ry rx])))))
           recon (range 4)))
        recon (range 4))))
   (vec (repeat grid-size (vec (repeat grid-size 0))))
   (range num-blocks)))

(defn- mc-predict
  "Motion-compensated prediction for the macroblock at (`mb-x`,`mb-y`) given
   the ALREADY-DERIVED final motion vector `mv` — mirrors
   `h264.decode/mc-predict` exactly (private there too, same small-
   deliberate-duplication convention). `ref-frame` is the PREVIOUS frame's
   OWN reconstructed pixels ({:width :height :luma :cb :cr})."
  [ref-frame mb-x mb-y mv]
  (let [w (:width ref-frame) h (:height ref-frame) cw (quot w 2) ch (quot h 2)
        lx (* mb-x 16) ly (* mb-y 16)
        cx (* mb-x 8) cy (* mb-y 8)]
    {:luma-pred (interp/mc-luma-block (:luma ref-frame) w h lx ly mv 16)
     :cb-pred (interp/mc-chroma-block (:cb ref-frame) cw ch cx cy mv 8)
     :cr-pred (interp/mc-chroma-block (:cr ref-frame) cw ch cx cy mv 8)}))

;; --- motion vector prediction / P_Skip predictor (§8.4.1.1/§8.4.1.3) —
;;     mirrors `h264.decode/mv-predict-16x16`/`p-skip-mv`/`neighbor-mv-ref`
;;     exactly (private there too). The ENCODER must derive mvd from the
;;     SAME predictor the decoder will reconstruct, or the decoded final mv
;;     will silently differ from the one this encoder's own motion search
;;     actually chose. ---

(defn- neighbor-mv-ref
  [mb]
  (if (and mb (:inter? mb)) [(:mv mb) 0] [[0 0] -1]))

(defn- median3 [a b c] (- (+ a b c) (min a b c) (max a b c)))

(defn- mv-predict-16x16
  [left-mb top-mb topleft-mb topright-mb cur-ref-idx]
  (let [c-mb (or topright-mb topleft-mb)
        [mvA refA] (neighbor-mv-ref left-mb)
        [mvB0 refB0] (neighbor-mv-ref top-mb)
        [mvC0 refC0] (neighbor-mv-ref c-mb)
        both-unavail? (and (nil? top-mb) (nil? c-mb))
        [mvB refB mvC refC] (if (and both-unavail? left-mb)
                               [mvA refA mvA refA]
                               [mvB0 refB0 mvC0 refC0])
        cands [[mvA refA] [mvB refB] [mvC refC]]
        exact-matches (filter #(= cur-ref-idx (second %)) cands)]
    (if (= 1 (count exact-matches))
      (first (first exact-matches))
      [(median3 (nth mvA 0) (nth mvB 0) (nth mvC 0))
       (median3 (nth mvA 1) (nth mvB 1) (nth mvC 1))])))

(defn- p-skip-mv
  [left-mb top-mb topleft-mb topright-mb]
  (let [[mvA refA] (neighbor-mv-ref left-mb)
        [mvB refB] (neighbor-mv-ref top-mb)]
    (if (or (nil? left-mb) (nil? top-mb)
            (and (= refA 0) (= mvA [0 0]))
            (and (= refB 0) (= mvB [0 0])))
      [0 0]
      (mv-predict-16x16 left-mb top-mb topleft-mb topright-mb 0))))

;; --- motion estimation: integer-pel full search + a small quarter-pel
;;     local refinement. NOT a claim of realtime-encoder-grade motion
;;     search (real encoders use hierarchical/diamond search, RDO lambda,
;;     multiple candidate predictors, etc.) — this is the "簡易" (simplified)
;;     search this task's own scope allows, mirroring `choose-pred-mode`'s
;;     existing "try a small candidate set, pick min-SAD" style for intra
;;     mode decision. ---

(defn- me-full-search
  "Integer-pel full search: minimize luma SAD between `src` (16x16 grid) and
   `ref-frame`'s luma plane at the macroblock's zero-motion position offset
   by (dx,dy) pixels, for dx,dy in `[-search-range,search-range]`. Returns
   the best MV in QUARTER-luma-sample units (always a multiple of 4 in both
   components — sub-pel refinement is `me-subpel-refine`'s job)."
  [src ref-frame mb-x mb-y search-range]
  (let [w (:width ref-frame) h (:height ref-frame) ref-luma (:luma ref-frame)
        x0 (* mb-x 16) y0 (* mb-y 16)
        candidates (for [dy (range (- search-range) (inc search-range))
                          dx (range (- search-range) (inc search-range))]
                     [dx dy])
        [bx by] (apply min-key
                       (fn [[dx dy]] (sad src (interp/mc-luma-block ref-luma w h x0 y0 [(* dx 4) (* dy 4)] 16)))
                       candidates)]
    [(* bx 4) (* by 4)]))

(defn- me-subpel-refine
  "Quarter-pel LOCAL refinement around `best-mv` (already a multiple of 4,
   from `me-full-search`): try every quarter-sample offset in ±3 (covering
   the surrounding integer positions too, redundantly but cheaply — block
   sizes here are small) and keep whichever minimizes luma SAD. Real,
   non-multiple-of-4 (half/quarter-pel) motion vectors are a normal outcome
   of this step, motion-compensated via the SAME `h264.interp` sub-pel path
   `h264.decode/mc-predict` uses for decode."
  [src ref-frame mb-x mb-y best-mv]
  (let [w (:width ref-frame) h (:height ref-frame) ref-luma (:luma ref-frame)
        x0 (* mb-x 16) y0 (* mb-y 16)
        [base-mvx base-mvy] best-mv
        candidates (for [dmy (range -3 4) dmx (range -3 4)]
                     [(+ base-mvx dmx) (+ base-mvy dmy)])]
    (apply min-key
           (fn [[mvx mvy]] (sad src (interp/mc-luma-block ref-luma w h x0 y0 [mvx mvy] 16)))
           candidates)))

(def ^:private inter-cbp->golomb
  "Reverse lookup of `h264.decode/golomb-to-inter-cbp` — given an actual
   CodedBlockPattern value (`cbp-chroma*16 + cbp-luma`), the ue(v) codeNum
   to write. Exact inverse of the fixed §9.1.2 Table 9-4 Inter-column
   permutation (`golomb-to-inter-cbp` is cross-checked there to be a full
   permutation of 0..47, so this reverse map is total over that domain)."
  (zipmap decode/golomb-to-inter-cbp (range 48)))

(defn- mb-block
  "Extract the `size`x`size` sub-grid for macroblock (`mb-x`,`mb-y`) from a
   full-picture row-vector `grid` (`plane->grid`'s output) — same slicing
   `encode-idr-luma-frame`'s own macroblock loop does inline, factored out
   here since the P-slice loop needs it for luma AND both chroma planes."
  [grid mb-x mb-y size]
  (vec (for [ry (range size)] (subvec (nth grid (+ (* mb-y size) ry)) (* mb-x size) (+ (* mb-x size) size)))))

(defn- encode-p-skip-macroblock!
  "Materialize one P_Skip macroblock (NO bits written here at all — the
   `mb_skip_run` bookkeeping is the caller's job, see
   `encode-p-slice-mbs!`): pure motion-compensated copy via `mc-predict`,
   zero residual — mirrors `h264.decode/decode-p-skip-macroblock!`'s return
   shape exactly, so it can serve as `left-mb`/`top-mb` neighbor state for
   subsequent macroblocks."
  [qp mb-x mb-y ref-frame mv]
  (let [{:keys [luma-pred cb-pred cr-pred]} (mc-predict ref-frame mb-x mb-y mv)]
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

(defn- encode-inter-16x16-macroblock!
  "Encode one P_L0_16x16 macroblock to writer `w`: `mvd_l0` (2 se(v), the
   ALREADY-CHOSEN final `mv` minus the median predictor `mvp` —
   `mv-predict-16x16`, NOT the P_Skip predictor), `coded_block_pattern`
   (me(v) via `inter-cbp->golomb`, ALWAYS present for this mb_type, matching
   `h264.decode/decode-inter-16x16-macroblock!`'s unconditional read),
   `mb_qp_delta` (only if any residual — se(v) 0, constant QP), then luma
   residual (16 FULL 4x4 `solve-regular-levels`/`regular-dequant-raster`
   blocks — NOT the Intra16x16 DC/AC split, gated per-8x8-quadrant by
   `cbp-luma`'s 4 bits, mirroring `h264.decode/decode-regular-block!`'s
   scope) and chroma residual (`chroma-component-plan`/
   `encode-chroma-ac-blocks!`, UNCHANGED from the intra path — see namespace
   docstring). `left-mb`/`top-mb` carry CAVLC neighbor (`:ac-nnz`/chroma)
   state, same shape `encode-macroblock!`'s intra path and
   `encode-p-skip-macroblock!` both produce.

   Returns `{:recon :inter? true :mv :ac-nnz :top-row :left-col :cb :cr}` —
   same shape `encode-p-skip-macroblock!` returns, for use as neighbor
   state by subsequent macroblocks."
  [w src src-cb src-cr qp qpc mb-x mb-y left-mb top-mb ref-frame mv mvp]
  (let [mvd-x (- (first mv) (first mvp))
        mvd-y (- (second mv) (second mvp))
        {:keys [luma-pred cb-pred cr-pred]} (mc-predict ref-frame mb-x mb-y mv)
        residual (mapv (fn [sr pr] (mapv - sr pr)) src luma-pred)
        levels-per-block
        (mapv (fn [b]
                (let [resid-b (block-residual residual b)]
                  (solve-regular-levels qp (vec (apply concat resid-b)))))
              (range 16))
        quadrant-nonzero?
        (mapv (fn [q] (boolean (some (fn [b] (some (complement zero?) (nth levels-per-block b)))
                                     (range (* q 4) (+ (* q 4) 4)))))
              (range 4))
        cbp-luma (reduce (fn [acc q] (if (nth quadrant-nonzero? q) (bit-or acc (bit-shift-left 1 q)) acc)) 0 (range 4))
        cb-residual (mapv (fn [sr pr] (mapv - sr pr)) src-cb cb-pred)
        cr-residual (mapv (fn [sr pr] (mapv - sr pr)) src-cr cr-pred)
        {cb-dc-raster :dc-raster cb-dc-quad :dc-quad cb-ac-levels-per-block :ac-levels-per-block} (chroma-component-plan cb-residual qpc)
        {cr-dc-raster :dc-raster cr-dc-quad :dc-quad cr-ac-levels-per-block :ac-levels-per-block} (chroma-component-plan cr-residual qpc)
        any-chroma-dc-nonzero? (boolean (or (some (complement zero?) cb-dc-raster) (some (complement zero?) cr-dc-raster)))
        any-chroma-ac-nonzero? (boolean (or (some (fn [lv] (some (complement zero?) lv)) cb-ac-levels-per-block)
                                             (some (fn [lv] (some (complement zero?) lv)) cr-ac-levels-per-block)))
        cbp-chroma (cond any-chroma-ac-nonzero? 2 any-chroma-dc-nonzero? 1 :else 0)
        golomb-code (get inter-cbp->golomb (+ (* cbp-chroma 16) cbp-luma))]
    (eg/write-ue! w 0)                                     ; p_mb_type = 0 (P_L0_16x16)
    (eg/write-se! w mvd-x)
    (eg/write-se! w mvd-y)
    (eg/write-ue! w golomb-code)                           ; coded_block_pattern (always present)
    (when (or (pos? cbp-luma) (pos? cbp-chroma))
      (eg/write-se! w 0))                                  ; mb_qp_delta = 0 — constant QP
    (let [ac-nnz (atom (vec (repeat 16 0)))
          block-coeffs
          (mapv
           (fn [b]
             (let [quadrant (quot b 4)]
               (if-not (bit-test cbp-luma quadrant)
                 (vec (repeat 16 0))
                 (let [[col row] (decode/blk->col-row b)
                       nA (if (pos? col)
                            (nth @ac-nnz (decode/col-row->blk [(dec col) row]))
                            (when left-mb (nth (:ac-nnz left-mb) (decode/col-row->blk [3 row]))))
                       nB (if (pos? row)
                            (nth @ac-nnz (decode/col-row->blk [col (dec row)]))
                            (when top-mb (nth (:ac-nnz top-mb) (decode/col-row->blk [col 3]))))
                       nc (neighbor-nc nA nB)
                       levels (nth levels-per-block b)
                       scanned (scan/scan zigzag levels)
                       total-coeff (cavlc/encode-residual-block! w nc 16 scanned)]
                   (swap! ac-nnz assoc b total-coeff)
                   (regular-dequant-raster qp levels)))))
           (range 16))
          recon (overlay-4x4-blocks luma-pred block-coeffs 16 16 decode/blk->col-row)
          ;; Bitstream order (§7.3.5.3.3, UNCHANGED from intra): Cb DC, Cr
          ;; DC, Cb AC x4, Cr AC x4.
          _ (when (pos? cbp-chroma)
              (cavlc/encode-residual-block! w :chroma-dc 4 cb-dc-raster)
              (cavlc/encode-residual-block! w :chroma-dc 4 cr-dc-raster))
          {cb-block-coeffs :block-coeffs cb-ac-nnz :ac-nnz}
          (encode-chroma-ac-blocks! w qpc cbp-chroma cb-dc-quad cb-ac-levels-per-block (:cb left-mb) (:cb top-mb))
          {cr-block-coeffs :block-coeffs cr-ac-nnz :ac-nnz}
          (encode-chroma-ac-blocks! w qpc cbp-chroma cr-dc-quad cr-ac-levels-per-block (:cr left-mb) (:cr top-mb))
          cb-recon (overlay-4x4-blocks cb-pred cb-block-coeffs 4 8 decode/chroma-blk->col-row)
          cr-recon (overlay-4x4-blocks cr-pred cr-block-coeffs 4 8 decode/chroma-blk->col-row)]
      {:recon recon
       :qp qp
       :inter? true
       :mv mv
       :ac-nnz @ac-nnz
       :top-row (nth recon 15)
       :left-col (mapv #(nth % 15) recon)
       :cb {:recon cb-recon :ac-nnz cb-ac-nnz :top-row (nth cb-recon 7) :left-col (mapv #(nth % 7) cb-recon)}
       :cr {:recon cr-recon :ac-nnz cr-ac-nnz :top-row (nth cr-recon 7) :left-col (mapv #(nth % 7) cr-recon)}})))

(defn- encode-p-slice-mbs!
  "Encode all macroblocks of a single P-slice covering the whole picture to
   writer `w` (the encode-side counterpart of
   `h264.decode/decode-p-slice-mbs!`'s `mb_skip_run` → `macroblock_layer()`
   loop structure). For each macroblock address in raster order:

   1. Derive this MB's motion vector predictor `mvp` (`mv-predict-16x16`)
      and P_Skip predictor `skip-mv` (`p-skip-mv`) from already-encoded
      neighbor states.
   2. Score `skip-mv`: motion-compensate via `mc-predict` and compute luma
      SAD against `src`. If EXACTLY zero (no residual would help at all),
      choose P_Skip — see namespace docstring for why this is a much
      simpler rule than a real encoder's RD mode decision.
   3. Otherwise, run `me-full-search` + `me-subpel-refine` to find a real
      (possibly sub-pel) motion vector, and encode a P_L0_16x16 macroblock
      with real residual against THAT vector.

   `mb_skip_run` is buffered across consecutive P_Skip macroblocks and
   flushed (written) right before the next coded `macroblock_layer()` — or,
   if the picture ends on a run of skips, after the loop, with no trailing
   `macroblock_layer()` call (matches `decode-p-slice-mbs!`'s own
   `do {...} while` structure exactly).

   Returns the per-MB state vector (raster order, same shape
   `encode-p-skip-macroblock!`/`encode-inter-16x16-macroblock!` both
   return)."
  [w qp qpc mb-width mb-height ref-frame luma-grid cb-grid cr-grid search-range]
  (let [num-mb (* mb-width mb-height)]
    (loop [addr 0 states [] skip-run 0]
      (if (= addr num-mb)
        (do (eg/write-ue! w skip-run) states)
        (let [mb-x (mod addr mb-width) mb-y (quot addr mb-width)
              left-mb (when (pos? mb-x) (nth states (dec addr)))
              top-mb (when (pos? mb-y) (nth states (- addr mb-width)))
              topleft-mb (when (and (pos? mb-x) (pos? mb-y)) (nth states (- addr mb-width 1)))
              topright-mb (when (and (pos? mb-y) (< (inc mb-x) mb-width)) (nth states (+ (- addr mb-width) 1)))
              src (mb-block luma-grid mb-x mb-y 16)
              src-cb (mb-block cb-grid mb-x mb-y 8)
              src-cr (mb-block cr-grid mb-x mb-y 8)
              mvp (mv-predict-16x16 left-mb top-mb topleft-mb topright-mb 0)
              skip-mv (p-skip-mv left-mb top-mb topleft-mb topright-mb)
              skip-pred (:luma-pred (mc-predict ref-frame mb-x mb-y skip-mv))
              skip-sad (sad src skip-pred)]
          (if (zero? skip-sad)
            (let [state (encode-p-skip-macroblock! qp mb-x mb-y ref-frame skip-mv)]
              (recur (inc addr) (conj states state) (inc skip-run)))
            (let [best-int-mv (me-full-search src ref-frame mb-x mb-y search-range)
                  best-mv (me-subpel-refine src ref-frame mb-x mb-y best-int-mv)
                  _ (eg/write-ue! w skip-run)
                  state (encode-inter-16x16-macroblock! w src src-cb src-cr qp qpc mb-x mb-y left-mb top-mb ref-frame best-mv mvp)]
              (recur (inc addr) (conj states state) 0))))))))

(defn- mb-states->plane
  "Assemble a full-picture flat row-major plane from per-MB `mb-states`,
   given `blk-size` (16 luma, 8 chroma) and a `recon-fn` selecting which
   grid out of each MB's state to use — mirrors
   `h264.decode/decode-picture`'s own `assemble` helper (private there,
   inlined via `let`, not reachable as a fn — this is a fresh small
   implementation of the same idea, not a duplication of decode's private
   code)."
  [mb-states mb-width plane-w plane-h blk-size recon-fn]
  (reduce
   (fn [plane addr]
     (let [mb-x (mod addr mb-width) mb-y (quot addr mb-width)
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
   (range (count mb-states))))

(defn encode-gop
  "Encode a whole GOP (one IDR I-frame — via THIS namespace's existing
   `encode-idr-luma-frame` Intra_16x16 encoder — followed by zero or more
   P-frames, P_Skip/P_L0_16x16 inter prediction) as a single concatenated
   Annex B elementary stream — the encode-side counterpart of
   `h264.decode/decode-gop`. See namespace docstring's \"P-slice (inter)
   encode\" section for exact scope (single reference frame = the
   immediately-preceding frame's OWN reconstruction, integer + small
   quarter-pel-refinement motion search, P_Skip whenever the skip predictor
   already gives zero luma SAD, constant QP).

   `{:width :height :qp :frames :search-range}` — `frames` a vector of
   `{:luma :cb :cr}` maps (same per-frame shape `encode-idr-luma-frame`
   accepts; `:cb`/`:cr` optional, defaulting to flat 128), FIRST entry
   encoded as the IDR frame, the rest as P-frames in order. `search-range`
   (default 8) is `me-full-search`'s integer-pel search radius in pixels.

   Returns `{:bytes (single Annex B byte vector, all frames' NALs
   concatenated) :frames (vector, one entry per frame, each
   `{:mb-states ...}` — IDR's mb-states shape from `encode-idr-luma-frame`,
   P-frames' from `encode-p-slice-mbs!` — PLUS `:inter?`/`:mv` per-MB for
   P-frames)}`."
  [{:keys [width height qp frames search-range] :or {search-range 8}}]
  (when (empty? frames)
    (throw (ex-info "h264.encode/encode-gop: at least 1 frame (the IDR) is required" {})))
  (when (pos? (mod width 16))
    (throw (ex-info "h264.encode: width must be a multiple of 16" {:width width})))
  (when (pos? (mod height 16))
    (throw (ex-info "h264.encode: height must be a multiple of 16" {:height height})))
  (let [mb-width (quot width 16) mb-height (quot height 16)
        cw (quot width 2) ch (quot height 2)
        {idr-bytes :bytes idr-mb-states :mb-states}
        (encode-idr-luma-frame (merge {:width width :height height :qp qp} (first frames)))
        sps-rbsp (sps/encode {:profile-idc 66 :level-idc 30 :seq-parameter-set-id 0 :width width :height height})
        pps-rbsp (pps/encode {:pic-init-qp qp})
        sps-map (sps/parse (rbsp/unescape sps-rbsp))
        pps-map (pps/parse (rbsp/unescape pps-rbsp))
        qpc (quant/chroma-qp qp (:chroma-qp-index-offset pps-map))
        idr-ref {:width width :height height
                 :luma (mb-states->plane idr-mb-states mb-width width height 16 :recon)
                 :cb (mb-states->plane idr-mb-states mb-width cw ch 8 #(:recon (:cb %)))
                 :cr (mb-states->plane idr-mb-states mb-width cw ch 8 #(:recon (:cr %)))}]
    (loop [remaining (rest frames)
           ref-frame idr-ref
           frame-num 1
           nal-byte-seqs [idr-bytes]
           frame-results [{:mb-states idr-mb-states}]]
      (if (empty? remaining)
        {:bytes (vec (apply concat nal-byte-seqs)) :frames frame-results}
        (let [{:keys [luma cb cr]} (first remaining)
              cb (or cb (vec (repeat (* cw ch) 128)))
              cr (or cr (vec (repeat (* cw ch) 128)))
              luma-grid (plane->grid luma width height)
              cb-grid (plane->grid cb cw ch)
              cr-grid (plane->grid cr cw ch)
              w (eg/writer)
              _ (eg/write-bits! w 8 (bit-or (bit-shift-left 2 5) 1)) ; NAL header: nal_ref_idc=2, nal_unit_type=1 (non-IDR slice)
              _ (slice/encode-p-header! w sps-map pps-map {:frame-num frame-num :slice-qp qp :nal-ref-idc 2})
              mb-states (encode-p-slice-mbs! w qp qpc mb-width mb-height ref-frame luma-grid cb-grid cr-grid search-range)
              _ (eg/rbsp-trailing-bits! w)
              slice-bytes (eg/bytes! w)
              nal (bs/write-nal-unit slice-bytes)
              new-ref {:width width :height height
                       :luma (mb-states->plane mb-states mb-width width height 16 :recon)
                       :cb (mb-states->plane mb-states mb-width cw ch 8 #(:recon (:cb %)))
                       :cr (mb-states->plane mb-states mb-width cw ch 8 #(:recon (:cr %)))}]
          (recur (rest remaining) new-ref (inc frame-num) (conj nal-byte-seqs nal) (conj frame-results {:mb-states mb-states})))))))
