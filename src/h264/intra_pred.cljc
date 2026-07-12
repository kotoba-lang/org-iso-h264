(ns h264.intra-pred
  "H.264 Intra_16x16 luma prediction (§8.3.3) AND Intra_Chroma prediction
   (§8.3.4, ChromaArrayType 1 / 4:2:0 8x8 chroma blocks) modes.

   Luma (`predict-16x16`): modes 0 (Vertical), 1 (Horizontal), 2 (DC) —
   mode 3 (Plane) is NOT implemented (out of scope, see README/task scope
   notes). Intra_4x4 and Intra_8x8 (per-4x4/8x8-block adaptive prediction,
   mb_type I_NxN) are ALSO out of scope — this repo's decoder throws on
   those mb_types, see `h264.decode`.

   Chroma (`predict-chroma-8x8`): modes 0 (DC), 1 (Horizontal), 2
   (Vertical), 3 (Plane) — ALL FOUR §8.3.4 chroma modes are implemented
   (unlike luma, where Plane is out of scope) because real-world encoders
   select chroma Plane even for near-flat chroma content whenever both
   neighbors are available (observed empirically with real libx264 output
   while building this repo's chroma golden vectors — a real encoder chose
   Plane for constant Cb=Cr=128 content at a moderate QP, apparently as an
   RD tie-break, not because the content had any actual gradient) — so
   leaving it unimplemented would make chroma decode fail on ordinary
   multi-macroblock real streams, not just contrived ones. NOTE the chroma
   mode NUMBERING (Table 8-5: 0=DC/1=Horizontal/2=Vertical/3=Plane) is a
   DIFFERENT permutation than the luma Intra_16x16 mode numbering above
   (0=Vertical/1=Horizontal/2=DC) — this is a real, easy-to-miss spec
   quirk, not a typo; `h264.decode` reads the two mode syntax elements
   (`Intra16x16PredMode`/`intra_chroma_pred_mode`) into this SAME
   `predict-*` mode-number convention split deliberately to avoid silently
   swapping them.

   New implementation as part of the kotoba-lang reverse-domain
   media/graphics standards-substrate split (com-junkawasaki/root)."
  )

(defn- clip8 [v] (max 0 (min 255 v)))

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

(defn- chroma-dc-quadrants
  "The 4 scalar DC values for the top-left/top-right/bottom-left/bottom-
   right 4x4 quadrants of an 8x8 Intra_Chroma_DC-predicted block (§8.3.4.1).
   Ported 1:1 (including which raw SUM feeds the bottom-right quadrant —
   it's `(sum-top-right + sum-bottom-left)`, NOT an average of the two
   already-rounded quadrant DC values) from ffmpeg's `pred8x8_dc_c`/
   `pred8x8_left_dc_c`/`pred8x8_top_dc_c` (`libavcodec/h264pred_template.c`)."
  [top-available? left-available? top-row left-col]
  (cond
    (and top-available? left-available?)
    (let [sum-t04 (reduce + (subvec top-row 0 4))
          sum-t48 (reduce + (subvec top-row 4 8))
          sum-l04 (reduce + (subvec left-col 0 4))
          sum-l48 (reduce + (subvec left-col 4 8))]
      [(quot (+ sum-t04 sum-l04 4) 8)   ; top-left: both neighbors, 8 samples
       (quot (+ sum-t48 2) 4)           ; top-right: top only, 4 samples
       (quot (+ sum-l48 2) 4)           ; bottom-left: left only, 4 samples
       (quot (+ sum-t48 sum-l48 4) 8)]) ; bottom-right: top-right + bottom-left RAW sums, 8 samples
    top-available?
    (let [dc-l (quot (+ (reduce + (subvec top-row 0 4)) 2) 4)
          dc-r (quot (+ (reduce + (subvec top-row 4 8)) 2) 4)]
      [dc-l dc-r dc-l dc-r])
    left-available?
    (let [dc-top (quot (+ (reduce + (subvec left-col 0 4)) 2) 4)
          dc-bot (quot (+ (reduce + (subvec left-col 4 8)) 2) 4)]
      [dc-top dc-top dc-bot dc-bot])
    :else
    [128 128 128 128]))

(defn- assemble-chroma-dc-grid
  [[dc-tl dc-tr dc-bl dc-br]]
  (vec (for [row (range 8)]
         (vec (for [col (range 8)]
                (cond (and (< row 4) (< col 4)) dc-tl
                      (< row 4) dc-tr
                      (< col 4) dc-bl
                      :else dc-br))))))

(defn- chroma-plane-grid
  "8x8 Intra_Chroma_Plane prediction (§8.3.4.4). `top-row`/`left-col` are
   8-element pixel vectors (this block's own top/left neighbor samples,
   indices 0..7); `corner` is the single diagonal top-left sample p[-1,-1]
   (the top-left MB's bottom-right chroma pixel — always available
   whenever BOTH `top-row`/`left-col` are, in single-slice raster-scan
   decode order, since that MB was necessarily already reconstructed; see
   `h264.decode/decode-macroblock!`'s `topleft-mb` derivation). Ported 1:1
   (including the exact H/V weighted-difference formula and the `a`
   integer-plane-origin term) from ffmpeg's `pred8x8_plane_c`
   (`libavcodec/h264pred_template.c`)."
  [top-row left-col corner]
  (let [top #(nth top-row %) left #(nth left-col %)
        h-raw (+ (- (top 4) (top 2))
                  (* 2 (- (top 5) (top 1)))
                  (* 3 (- (top 6) (top 0)))
                  (* 4 (- (top 7) corner)))
        v-raw (+ (- (left 4) (left 2))
                  (* 2 (- (left 5) (left 1)))
                  (* 3 (- (left 6) (left 0)))
                  (* 4 (- (left 7) corner)))
        H (bit-shift-right (+ (* 17 h-raw) 16) 5)
        V (bit-shift-right (+ (* 17 v-raw) 16) 5)
        a (- (* 16 (+ (left 7) (top 7) 1)) (* 3 (+ V H)))]
    (vec (for [row (range 8)]
           (let [a-row (+ a (* row V))]
             (vec (for [col (range 8)]
                    (clip8 (bit-shift-right (+ a-row (* col H)) 5)))))))))

(defn predict-chroma-8x8
  "Predict an 8x8 Intra_Chroma block (one component, Cb or Cr) per §8.3.4.
   `mode` is 0 (DC), 1 (Horizontal), 2 (Vertical), or 3 (Plane) — NOTE this
   numbering is Table 8-5's chroma numbering, a DIFFERENT permutation than
   `predict-16x16`'s luma mode numbers (see namespace docstring).
   `top-row`/`left-col` are 8-element pixel vectors from the already-
   reconstructed neighbor macroblock's SAME chroma component (ignored/may
   be nil when the corresponding availability flag is false); `corner` (Plane
   only) is the diagonal top-left MB's bottom-right sample of this SAME
   component."
  [mode {:keys [top-available? left-available? top-row left-col corner]}]
  (case mode
    2 (if top-available?
        (vec (repeat 8 top-row))
        (throw (ex-info "h264.intra-pred: chroma Vertical mode requires an available top neighbor" {})))
    1 (if left-available?
        (mapv #(vec (repeat 8 %)) left-col)
        (throw (ex-info "h264.intra-pred: chroma Horizontal mode requires an available left neighbor" {})))
    0 (assemble-chroma-dc-grid (chroma-dc-quadrants top-available? left-available? top-row left-col))
    3 (if (and top-available? left-available?)
        (chroma-plane-grid top-row left-col corner)
        (throw (ex-info "h264.intra-pred: chroma Plane mode requires both top and left neighbors" {})))
    (throw (ex-info "h264.intra-pred: unsupported Intra_Chroma pred mode (only 0/1/2/3 implemented)"
                     {:mode mode}))))
