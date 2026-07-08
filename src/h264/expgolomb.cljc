(ns h264.expgolomb
  "MSB-first bit reader + Exp-Golomb ue(v)/se(v) decode (H.264 §9.1), the
   entropy code SPS/PPS/slice-header fields use. Pure cljc.

   New implementation as part of the kotoba-lang reverse-domain
   media/graphics standards-substrate split (com-junkawasaki/root)."
  )

(defn reader [data]
  {:data (vec data) :len (count data) :bytepos (atom 0) :bitpos (atom 0)})

(defn bit! [r]
  (let [bp @(:bytepos r) bi @(:bitpos r)]
    (when (>= bp (:len r)) (throw (ex-info "expgolomb: bit EOF" {})))
    (let [byte (nth (:data r) bp)
          v    (bit-and (bit-shift-right byte (- 7 bi)) 1)]
      (if (= bi 7) (do (reset! (:bitpos r) 0) (swap! (:bytepos r) inc))
          (reset! (:bitpos r) (inc bi)))
      v)))

(defn bits! [r n]
  (loop [i 0 acc 0] (if (= i n) acc (recur (inc i) (bit-or (bit-shift-left acc 1) (bit! r))))))

(defn ue!
  "Unsigned Exp-Golomb: count leading zero bits, then read that many more
   bits as the low-order part. 2^leadingZeroBits - 1 + readBits."
  [r]
  (let [lz (loop [n 0] (if (zero? (bit! r)) (recur (inc n)) n))]
    (if (zero? lz) 0 (+ (dec (bit-shift-left 1 lz)) (bits! r lz)))))

(defn se!
  "Signed Exp-Golomb, mapped from ue(v) per H.264 §9.1.1: codeNum k → value
   (-1)^(k+1) * ceil(k/2)."
  [r]
  (let [k (ue! r)
        m (bit-shift-right (inc k) 1)]
    (if (odd? k) m (- m))))

(defn flag! [r] (bit! r))
