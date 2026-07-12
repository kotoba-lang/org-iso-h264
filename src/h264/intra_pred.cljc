(ns h264.intra-pred
  "H.264 Intra_16x16 prediction modes (ITU-T H.264 / ISO/IEC 14496-10
   §8.3.3 \"Intra_16x16 prediction process for luma samples\"). Scope:
   modes 0 (Vertical), 1 (Horizontal), 2 (DC) — mode 3 (Plane) is NOT
   implemented (out of scope, see README/task scope notes). Intra_4x4 and
   Intra_8x8 (per-4x4/8x8-block adaptive prediction, mb_type I_NxN) are
   ALSO out of scope — this repo's decoder throws on those mb_types, see
   `h264.decode`.

   New implementation as part of the kotoba-lang reverse-domain
   media/graphics standards-substrate split (com-junkawasaki/root)."
  )

(defn predict-16x16
  "Predict a 16x16 luma macroblock per §8.3.3. `mode` is 0 (Vertical), 1
   (Horizontal), or 2 (DC). `top-row`/`left-col` are 16-element pixel
   vectors from the already-reconstructed neighbor macroblocks (ignored /
   may be nil when the corresponding availability flag is false).

   Per spec, Vertical requires `top-available?` and Horizontal requires
   `left-available?` (a conformant encoder never selects them otherwise);
   this throws rather than silently guessing if that's violated. DC mode
   is defined for all four availability combinations (§8.3.3.1): both
   available → rounded average of both; only one available → that one's
   average; neither → 128 (the flat default used when this MB has no
   reconstructed neighbors at all, e.g. the top-left MB of a frame)."
  [mode {:keys [top-available? left-available? top-row left-col]}]
  (case mode
    0 (if top-available?
        (vec (repeat 16 top-row))
        (throw (ex-info "h264.intra-pred: Vertical mode requires an available top neighbor" {})))
    1 (if left-available?
        (mapv #(vec (repeat 16 %)) left-col)
        (throw (ex-info "h264.intra-pred: Horizontal mode requires an available left neighbor" {})))
    2 (let [dc (cond
                 (and top-available? left-available?)
                 (quot (+ (reduce + top-row) (reduce + left-col) 16) 32)
                 top-available? (quot (+ (reduce + top-row) 8) 16)
                 left-available? (quot (+ (reduce + left-col) 8) 16)
                 :else 128)]
        (vec (repeat 16 (vec (repeat 16 dc)))))
    (throw (ex-info "h264.intra-pred: unsupported Intra_16x16 pred mode (only 0/1/2 implemented)"
                     {:mode mode}))))
