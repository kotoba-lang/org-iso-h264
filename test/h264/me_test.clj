(ns h264.me-test
  "Equivalence tests for motion estimation's fused, early-abandoning SAD
   (`h264.encode/sad-mc` + `best-mv`) against the straightforward
   `apply min-key` over `sad` on a materialised `interp/mc-luma-block` that it
   replaced (com-junkawasaki/root ADR-2800002800, lever 3 — measured 1.71x on a
   P frame).

   The whole point of the optimisation is that it must choose the SAME motion
   vector, so what has to be pinned is not the SAD arithmetic (unchanged) but
   the two places the shortcut could silently disagree:

   - **Ties.** `apply min-key` keeps the LAST minimum. The fused version has to
     replace its best on `<=`, and must not abandon a candidate whose running
     sum has merely REACHED the best (only one that exceeds it) — otherwise a
     tie is resolved differently and a different, equally-cheap vector is
     encoded. `flat-source-every-candidate-ties` makes every candidate tie, so
     this is not left to chance on random data.
   - **Abandonment.** A candidate abandoned early returns a meaningless value
     that must never be mistaken for a minimum.

   The reference implementation below is deliberately written out in full rather
   than reusing anything from `h264.encode`, so it cannot drift along with the
   code it is checking."
  (:require [clojure.test :refer [deftest is testing]]
            [h264.encode :as encode]
            [h264.interp :as interp]))

(def ^:private me-full-search #'h264.encode/me-full-search)
(def ^:private me-subpel-refine #'h264.encode/me-subpel-refine)

(defn- reference-sad
  "Plain SAD over two grids — no cutoff, no early exit."
  [a b]
  (reduce + 0 (map (fn [ra rb]
                     (reduce + 0 (map (fn [x y] (Math/abs (- (long x) (long y)))) ra rb)))
                   a b)))

(defn- reference-full-search
  "`me-full-search` as it was before the fused SAD: materialise every candidate
   block and hand the costs to `apply min-key`."
  [src ref-frame mb-x mb-y search-range]
  (let [w (:width ref-frame) h (:height ref-frame) ref-luma (:luma ref-frame)
        x0 (* mb-x 16) y0 (* mb-y 16)
        candidates (for [dy (range (- search-range) (inc search-range))
                         dx (range (- search-range) (inc search-range))]
                     [dx dy])
        [bx by] (apply min-key
                       (fn [[dx dy]]
                         (reference-sad src (interp/mc-luma-block ref-luma w h x0 y0
                                                                  [(* dx 4) (* dy 4)] 16)))
                       candidates)]
    [(* bx 4) (* by 4)]))

(defn- reference-subpel-refine
  [src ref-frame mb-x mb-y integer-mv]
  (let [w (:width ref-frame) h (:height ref-frame) ref-luma (:luma ref-frame)
        x0 (* mb-x 16) y0 (* mb-y 16)
        [bmx bmy] integer-mv]
    (apply min-key
           (fn [[mvx mvy]]
             (reference-sad src (interp/mc-luma-block ref-luma w h x0 y0 [mvx mvy] 16)))
           (for [dmy (range -3 4) dmx (range -3 4)] [(+ bmx dmx) (+ bmy dmy)]))))

(defn- plane [w h f] (vec (for [y (range h) x (range w)] (f x y))))
(defn- grid-16 [plane w x0 y0]
  (vec (for [ry (range 16)]
         (let [row (* (+ y0 ry) w)]
           (vec (for [rx (range 16)] (nth plane (+ row x0 rx))))))))

(def ^:private w 96)
(def ^:private h 96)

(defn- ref-frame-of [plane]
  {:width w :height h :luma plane})

(deftest matches-min-key-on-textured-content
  (testing "the fused search picks the same vector as apply min-key over materialised blocks"
    (let [rnd (java.util.Random. 20260730)
          ref-plane (plane w h (fn [_ _] (.nextInt rnd 256)))
          src-plane (plane w h (fn [_ _] (.nextInt rnd 256)))
          rf (ref-frame-of ref-plane)]
      (doseq [mb-y (range 2 4) mb-x (range 2 4)]
        (let [src (grid-16 src-plane w (* mb-x 16) (* mb-y 16))
              got (me-full-search src rf mb-x mb-y 3)
              want (reference-full-search src rf mb-x mb-y 3)]
          (is (= want got) (str "mb " mb-x "," mb-y)))))))

(deftest matches-min-key-on-real-motion
  (testing "same, on content that actually moves (so the minimum is a genuine, non-zero vector)"
    (let [base (fn [x y] (bit-and (+ (* 3 x) (* 7 y) (* 11 (quot x 8))) 0xff))
          ref-plane (plane w h base)
          ;; shifted by (2,-1), so the true minimum is at that displacement
          src-plane (plane w h (fn [x y] (base (- x 2) (+ y 1))))
          rf (ref-frame-of ref-plane)]
      (doseq [mb-y (range 2 4) mb-x (range 2 4)]
        (let [src (grid-16 src-plane w (* mb-x 16) (* mb-y 16))
              got (me-full-search src rf mb-x mb-y 4)]
          (is (= (reference-full-search src rf mb-x mb-y 4) got))
          (testing "and the sub-pel refinement agrees too"
            (is (= (reference-subpel-refine src rf mb-x mb-y got)
                   (me-subpel-refine src rf mb-x mb-y got)))))))))

(deftest flat-source-every-candidate-ties
  (testing "on a uniform plane EVERY candidate has the same SAD, so the choice is decided entirely by the tie rule — apply min-key keeps the LAST, and so must the fused search"
    (let [flat (plane w h (fn [_ _] 128))
          rf (ref-frame-of flat)
          src (grid-16 flat w 32 32)]
      (doseq [sr [1 2 3]]
        (let [want (reference-full-search src rf 2 2 sr)
              got (me-full-search src rf 2 2 sr)]
          (is (= want got) (str "search-range " sr))
          (testing "and that answer is the LAST candidate, not the first — i.e. the tie rule is real, not coincidence"
            (is (= [(* sr 4) (* sr 4)] want))))))))

(deftest partial-ties-still-agree
  (testing "content with only a few distinct SAD values (many ties, but not all) — the case where an off-by-one in the cutoff comparison would show up"
    (let [coarse (fn [x y] (* 64 (mod (+ (quot x 4) (quot y 4)) 4)))
          ref-plane (plane w h coarse)
          src-plane (plane w h (fn [x y] (coarse (- x 1) y)))
          rf (ref-frame-of ref-plane)]
      (doseq [mb-y (range 2 4) mb-x (range 2 4) sr [2 4]]
        (let [src (grid-16 src-plane w (* mb-x 16) (* mb-y 16))]
          (is (= (reference-full-search src rf mb-x mb-y sr)
                 (me-full-search src rf mb-x mb-y sr))
              (str "mb " mb-x "," mb-y " sr " sr)))))))
