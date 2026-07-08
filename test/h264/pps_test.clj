(ns h264.pps-test
  "Validated against the same real libx264-encoded (baseline profile) Annex
   B elementary stream as sps_test.clj. entropy-coding-mode is the key
   cross-check: baseline profile forbids CABAC by spec, so a correct parse
   must read :cavlc here — an independent correctness signal beyond just
   'the parser didn't throw'."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [h264.bitstream :as bs]
            [h264.rbsp :as rbsp]
            [h264.pps :as pps]))

(defn- rd [p] (mapv #(bit-and (int %) 0xff)
                    (with-open [in (io/input-stream (io/resource p))] (.readAllBytes in))))

(deftest pps-from-real-encoder
  (let [b     (rd "h264/fixtures/sample.h264")
        units (bs/nal-units b)
        pps-u (first (filter #(= :pps (:kind %)) units))]
    (testing "PPS NAL found"
      (is (some? pps-u)))
    (let [parsed (pps/parse (rbsp/unescape (:bytes pps-u)))]
      (testing "baseline profile forbids CABAC (cross-checks sps_test's profile-idc=66 finding)"
        (is (= :cavlc (:entropy-coding-mode parsed))))
      (testing "ids link back to the stream's single SPS"
        (is (= 0 (:pic-parameter-set-id parsed)))
        (is (= 0 (:seq-parameter-set-id parsed))))
      (testing "other fields are self-consistent, real-encoder values"
        (is (pos? (:num-ref-idx-l0-default-active parsed)))
        (is (pos? (:num-ref-idx-l1-default-active parsed)))
        (is (false? (:weighted-pred? parsed)))
        (is (true? (:deblocking-filter-control-present? parsed)))))))
