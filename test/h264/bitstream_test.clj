(ns h264.bitstream-test
  (:require [clojure.test :refer [deftest is testing]]
            [h264.bitstream :as bs]))

(deftest split-annexb-synthetic
  (testing "3-byte and 4-byte start codes, back-to-back NALs"
    (let [b (vec (concat [0 0 0 1] [0x67 0xAA 0xBB]      ; 4-byte start, SPS-ish header
                         [0 0 1]   [0x68 0xCC]            ; 3-byte start, PPS-ish header
                         [0 0 0 1] [0x65 0xDD 0xEE 0xFF]))] ; 4-byte start, IDR slice-ish
      (let [units (bs/nal-units b)]
        (is (= 3 (count units)))
        (is (= [:sps :pps :slice-idr] (mapv :kind units)))
        (is (= [3 2 4] (mapv #(count (:bytes %)) units)))))))

(deftest nal-header-fields
  (let [h (bs/nal-header [0x67] {:start 0 :end 1})]
    (is (= 0 (:forbidden-zero-bit h)))
    (is (= 3 (:nal-ref-idc h)))
    (is (= 7 (:nal-unit-type h)))))
