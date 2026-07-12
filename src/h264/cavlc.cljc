(ns h264.cavlc
  "H.264 baseline-profile CAVLC residual entropy decode (ITU-T H.264 /
   ISO/IEC 14496-10 §9.2 \"CAVLC parsing process for transform coefficient
   levels\"). Scope: luma blocks only (the Intra16x16 DC block, maxNumCoeff
   16, and regular/AC 4x4 blocks, maxNumCoeff 15 or 16) — chroma DC/AC
   VLC tables (§9.2.1's `nC == -1`/`-2` special cases) are NOT implemented
   (this repo's decode scope is luma-only, see `h264.decode`).

   All VLC tables (`coeff-token-len`/`coeff-token-bits` for the 4 nC
   classes, `total-zeros-len`/`total-zeros-bits`, `run-len`/`run-bits`) are
   transcribed byte-for-byte from FFmpeg's reference decoder
   (`libavcodec/h264_cavlc.c`, https://github.com/FFmpeg/FFmpeg — these
   are themselves direct transcriptions of ITU-T H.264 Tables 9-5, 9-7,
   9-10). The decode algorithm (`residual-block!`) follows spec §9.2.1's
   pseudocode (coeff_token → trailing-ones sign bits → level_prefix/suffix
   → total_zeros → run_before → position reconstruction), cross-checked
   against `h264_cavlc.c`'s `decode_residual`.

   New implementation as part of the kotoba-lang reverse-domain
   media/graphics standards-substrate split (com-junkawasaki/root)."
  (:require [h264.expgolomb :as eg]))

;; --- coeff_token VLC tables, Table 9-5 (4 nC classes: 0<=nC<2, 2<=nC<4,
;;     4<=nC<8, nC>=8). Row-major [TotalCoeff 0..16][TrailingOnes 0..3]. ---

(def coeff-token-len
  [[1 0 0 0
    6 2 0 0    8 6 3 0    9 8 7 5    10 9 8 6
    11 10 9 7  13 11 10 8 13 13 11 9 13 13 13 10
    14 14 13 11 14 14 14 13 15 15 14 14 15 15 15 14
    16 15 15 15 16 16 16 15 16 16 16 16 16 16 16 16]
   [2 0 0 0
    6 2 0 0    6 5 3 0    7 6 6 4    8 6 6 4
    8 7 7 5    9 8 8 6    11 9 9 6   11 11 11 7
    12 11 11 9 12 12 12 11 12 12 12 11 13 13 13 12
    13 13 13 13 13 14 13 13 14 14 14 13 14 14 14 14]
   [4 0 0 0
    6 4 0 0    6 5 4 0    6 5 5 4    7 5 5 4
    7 5 5 4    7 6 6 4    7 6 6 4    8 7 7 5
    8 8 7 6    9 8 8 7    9 9 8 8    9 9 9 8
    10 9 9 9   10 10 10 10 10 10 10 10 10 10 10 10]
   [6 0 0 0
    6 6 0 0    6 6 6 0    6 6 6 6    6 6 6 6
    6 6 6 6    6 6 6 6    6 6 6 6    6 6 6 6
    6 6 6 6    6 6 6 6    6 6 6 6    6 6 6 6
    6 6 6 6    6 6 6 6    6 6 6 6    6 6 6 6]])

(def coeff-token-bits
  [[1 0 0 0
    5 1 0 0    7 4 1 0    7 6 5 3    7 6 5 3
    7 6 5 4    15 6 5 4   11 14 5 4  8 10 13 4
    15 14 9 4  11 10 13 12 15 14 9 12 11 10 13 8
    15 1 9 12  11 14 13 8 7 10 9 12 4 6 5 8]
   [3 0 0 0
    11 2 0 0   7 7 3 0    7 10 9 5   7 6 5 4
    4 6 5 6    7 6 5 8    15 6 5 4   11 14 13 4
    15 10 9 4  11 14 13 12 8 10 9 8  15 14 13 12
    11 10 9 12 7 11 6 8   9 8 10 1   7 6 5 4]
   [15 0 0 0
    15 14 0 0  11 15 13 0 8 12 14 12 15 10 11 11
    11 8 9 10  9 14 13 9  8 10 9 8   15 14 13 13
    11 14 10 12 15 10 13 12 11 14 9 12 8 10 13 8
    13 7 9 12  9 12 11 10 5 8 7 6    1 4 3 2]
   [3 0 0 0
    0 1 0 0    4 5 6 0    8 9 10 11  12 13 14 15
    16 17 18 19 20 21 22 23 24 25 26 27 28 29 30 31
    32 33 34 35 36 37 38 39 40 41 42 43 44 45 46 47
    48 49 50 51 52 53 54 55 56 57 58 59 60 61 62 63]])

(defn- nc-class
  "nC → coeff_token VLC table row index (0: nC<2, 1: 2<=nC<4, 2: 4<=nC<8,
   3: nC>=8)."
  [nc]
  (cond (< nc 2) 0 (< nc 4) 1 (< nc 8) 2 :else 3))

(defn- decode-coeff-token!
  "Reads coeff_token via linear VLC search over `coeff-token-len`/`-bits`
   for the given nC. Returns [total-coeff trailing-ones]."
  [r nc]
  (let [class (nc-class nc)
        lens (nth coeff-token-len class)
        bits (nth coeff-token-bits class)
        start-byte @(:bytepos r) start-bit @(:bitpos r)
        max-len (apply max lens)]
    (loop [len 1]
      (when (> len max-len)
        (throw (ex-info "h264.cavlc: no coeff_token VLC match" {:nc nc})))
      (reset! (:bytepos r) start-byte) (reset! (:bitpos r) start-bit)
      (let [code (eg/bits! r len)
            hit (loop [tc 0]
                  (if (> tc 16)
                    nil
                    (let [to-match
                          (loop [to 0]
                            (if (> to 3)
                              nil
                              (let [idx (+ (* tc 4) to)]
                                (if (and (not (and (zero? tc) (pos? to)))
                                         (= (nth lens idx) len)
                                         (= (nth bits idx) code))
                                  to
                                  (recur (inc to))))))]
                      (if to-match [tc to-match] (recur (inc tc))))))]
        (if hit
          (do (reset! (:bytepos r) start-byte) (reset! (:bitpos r) start-bit)
              (eg/bits! r len)
              hit)
          (recur (inc len)))))))

;; --- total_zeros VLC, Table 9-7 (maxNumCoeff 16 — also used for the
;;     Intra16x16 luma DC block and luma AC (maxNumCoeff 15) blocks per
;;     spec; row index = TotalCoeff-1, 1..15). ---

(def total-zeros-len
  [[1 3 3 4 4 5 5 6 6 7 7 8 8 9 9 9]
   [3 3 3 3 3 4 4 4 4 5 5 6 6 6 6]
   [4 3 3 3 4 4 3 3 4 5 5 6 5 6]
   [5 3 4 4 3 3 3 4 3 4 5 5 5]
   [4 4 4 3 3 3 3 3 4 5 4 5]
   [6 5 3 3 3 3 3 3 4 3 6]
   [6 5 3 3 3 2 3 4 3 6]
   [6 4 5 3 2 2 3 3 6]
   [6 6 4 2 2 3 2 5]
   [5 5 3 2 2 2 4]
   [4 4 3 3 1 3]
   [4 4 2 1 3]
   [3 3 1 2]
   [2 2 1]
   [1 1]])

(def total-zeros-bits
  [[1 3 2 3 2 3 2 3 2 3 2 3 2 3 2 1]
   [7 6 5 4 3 5 4 3 2 3 2 3 2 1 0]
   [5 7 6 5 4 3 4 3 2 3 2 1 1 0]
   [3 7 5 4 6 5 4 3 3 2 2 1 0]
   [5 4 3 7 6 5 4 3 2 1 1 0]
   [1 1 7 6 5 4 3 2 1 1 0]
   [1 1 5 4 3 3 2 1 1 0]
   [1 1 1 3 3 2 2 1 0]
   [1 0 1 3 2 1 1 1]
   [1 0 1 3 2 1 1]
   [0 1 1 2 1 3]
   [0 1 1 1 1]
   [0 1 1 1]
   [0 1 1]
   [0 1]])

;; --- run_before VLC, Table 9-10 (row index = min(zerosLeft,7)-1). ---

(def run-len
  [[1 1]
   [1 2 2]
   [2 2 2 2]
   [2 2 2 3 3]
   [2 2 3 3 3 3]
   [2 3 3 3 3 3 3]
   [3 3 3 3 3 3 3 4 5 6 7 8 9 10 11]])

(def run-bits
  [[1 0]
   [1 1 0]
   [3 2 1 0]
   [3 2 1 1 0]
   [3 2 3 2 1 0]
   [3 0 1 3 2 5 4]
   [7 6 5 4 3 2 1 1 1 1 1 1 1 1 1]])

(defn- decode-flatlist-vlc!
  [r lens bits]
  (let [start-byte @(:bytepos r) start-bit @(:bitpos r)
        max-len (apply max lens)]
    (loop [len 1]
      (when (> len max-len)
        (throw (ex-info "h264.cavlc: no VLC match" {})))
      (reset! (:bytepos r) start-byte) (reset! (:bitpos r) start-bit)
      (let [code (eg/bits! r len)
            idx (loop [i 0]
                  (cond (>= i (count lens)) nil
                        (and (= (nth lens i) len) (= (nth bits i) code)) i
                        :else (recur (inc i))))]
        (if idx
          (do (reset! (:bytepos r) start-byte) (reset! (:bitpos r) start-bit)
              (eg/bits! r len)
              idx)
          (recur (inc len)))))))

(defn residual-block!
  "Decode one CAVLC residual_block (spec §9.2.1/9.2.2/9.2.3) from reader
   `r`. `nc` selects the coeff_token VLC class (already resolved by the
   caller from neighbor total_coeff per §9.2.1 clause 9.2.1). `max-num-coeff`
   is 16 for the Intra16x16 luma DC block and regular 4x4 luma blocks, 15
   for Intra16x16 luma AC blocks.

   Returns {:coeffs (vector of `max-num-coeff` ints, SCAN-ORDER — the
   caller unscans via `codec-primitives.scan`) :total-coeff int}."
  [r nc max-num-coeff]
  (let [[total-coeff trailing-ones] (decode-coeff-token! r nc)]
    (if (zero? total-coeff)
      {:coeffs (vec (repeat max-num-coeff 0)) :total-coeff 0}
      (let [;; --- levels ---
            levels
            (loop [i 0 suffix-length (if (and (> total-coeff 10) (< trailing-ones 3)) 1 0)
                   acc []]
              (if (= i total-coeff)
                acc
                (if (< i trailing-ones)
                  (let [sign (eg/bit! r)]
                    (recur (inc i) suffix-length (conj acc (if (zero? sign) 1 -1))))
                  (let [level-prefix (loop [lz 0] (if (zero? (eg/bit! r)) (recur (inc lz)) lz))
                        level-suffix-size (cond (and (= level-prefix 14) (zero? suffix-length)) 4
                                                 (>= level-prefix 15) (- level-prefix 3)
                                                 :else suffix-length)
                        level-suffix (if (pos? level-suffix-size) (eg/bits! r level-suffix-size) 0)
                        level-code (+ (bit-shift-left (min 15 level-prefix) suffix-length) level-suffix)
                        level-code (if (and (>= level-prefix 15) (zero? suffix-length)) (+ level-code 15) level-code)
                        level-code (if (>= level-prefix 16) (+ level-code (- (bit-shift-left 1 (- level-prefix 3)) 4096)) level-code)
                        level-code (if (and (= i trailing-ones) (< trailing-ones 3)) (+ level-code 2) level-code)
                        v (if (even? level-code) (quot (+ level-code 2) 2) (quot (- (- level-code) 1) 2))
                        suffix-length' (if (zero? suffix-length) 1 suffix-length)
                        abs-v (if (neg? v) (- v) v)
                        suffix-length'' (if (and (> abs-v (bit-shift-left 3 (dec suffix-length'))) (< suffix-length' 6))
                                          (inc suffix-length') suffix-length')]
                    (recur (inc i) suffix-length'' (conj acc v))))))
            zeros-left (if (= total-coeff max-num-coeff)
                         0
                         (decode-flatlist-vlc! r (nth total-zeros-len (dec total-coeff))
                                               (nth total-zeros-bits (dec total-coeff))))
            run-vals
            (loop [i 0 zl zeros-left acc []]
              (if (= i (dec total-coeff))
                (conj acc zl)
                (if (pos? zl)
                  (let [zl-idx (min (dec zl) 6)
                        rb (decode-flatlist-vlc! r (nth run-len zl-idx) (nth run-bits zl-idx))]
                    (recur (inc i) (- zl rb) (conj acc rb)))
                  (recur (inc i) zl (conj acc 0)))))
            coeffs (vec (repeat max-num-coeff 0))
            coeffs (loop [i (dec total-coeff) coeff-num -1 acc coeffs]
                     (if (neg? i)
                       acc
                       (let [coeff-num' (+ coeff-num (nth run-vals i) 1)]
                         (recur (dec i) coeff-num' (assoc acc coeff-num' (nth levels i))))))]
        {:coeffs coeffs :total-coeff total-coeff}))))
