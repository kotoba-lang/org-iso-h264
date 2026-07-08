(ns h264.bitstream
  "H.264 (ITU-T H.264 / ISO/IEC 14496-10 AVC) Annex B byte-stream framing.
   Splits the byte stream into NAL units at start codes and reads the
   1-byte NAL header. Pure cljc, zero dependencies. This is framing only —
   entropy-coded slice data (CABAC/CAVLC) is not decoded; that stays a
   capability-gated native concern per kotoba-lang/utsushi's design.

   New implementation (not an extraction — utsushi.bitstream's split-annexb
   was an unimplemented TODO stub) as part of the kotoba-lang reverse-domain
   media/graphics standards-substrate split (com-junkawasaki/root)."
  )

(defn- start-code-len
  "Length of the start code beginning exactly at index `i` (3 or 4), or nil
   if none begins there."
  [b n i]
  (cond
    (and (<= (+ i 4) n) (= 0 (nth b i)) (= 0 (nth b (+ i 1)))
         (= 0 (nth b (+ i 2))) (= 1 (nth b (+ i 3)))) 4
    (and (<= (+ i 3) n) (= 0 (nth b i)) (= 0 (nth b (+ i 1)))
         (= 1 (nth b (+ i 2)))) 3
    :else nil))

(defn split-annexb
  "Split an H.264/H.265 Annex B byte stream (vector of unsigned bytes) into
   NAL unit [start end) byte ranges, start codes (0x000001 / 0x00000001)
   excluded. Returns a vector of {:start :end}."
  [b]
  (let [n (count b)
        ;; positions where a start code BEGINS (not where NAL content begins)
        sc-starts (loop [i 0 acc []]
                    (if (>= i n)
                      acc
                      (if-let [sc (start-code-len b n i)]
                        (recur (+ i sc) (conj acc i))
                        (recur (inc i) acc))))
        content-starts (mapv #(+ % (start-code-len b n %)) sc-starts)
        content-ends   (vec (concat (rest sc-starts) [n]))]
    (vec (map (fn [s e] {:start s :end e}) content-starts content-ends))))

(defn nal-header
  "Parse an H.264 NAL unit's 1-byte header (the byte at `nal-range`'s :start).
   {:forbidden-zero-bit :nal-ref-idc :nal-unit-type}."
  [b nal-range]
  (let [h (nth b (:start nal-range))]
    {:forbidden-zero-bit (bit-and (bit-shift-right h 7) 1)
     :nal-ref-idc        (bit-and (bit-shift-right h 5) 3)
     :nal-unit-type       (bit-and h 0x1f)}))

(def nal-unit-types
  "H.264 Table 7-1 nal_unit_type semantics (the subset this repo cares
   about; unlisted values are still valid NAL types, just unnamed here)."
  {0 :unspecified 1 :slice-non-idr 5 :slice-idr 6 :sei 7 :sps 8 :pps
   9 :access-unit-delimiter 10 :end-of-sequence 11 :end-of-stream 12 :filler-data
   13 :sps-extension 14 :prefix-nal 15 :subset-sps 19 :slice-aux 20 :slice-extension})

(defn nal-units
  "Split `b` into NAL units and attach the parsed header (+ :kind, the
   nal-unit-types lookup) and a raw byte subvec of the NAL (including the
   header byte). Convenience wrapper over split-annexb + nal-header."
  [b]
  (mapv (fn [r] (let [h (nal-header b r)]
                  (assoc h :start (:start r) :end (:end r)
                         :kind (nal-unit-types (:nal-unit-type h) :unspecified)
                         :bytes (subvec b (:start r) (:end r)))))
        (split-annexb b)))
