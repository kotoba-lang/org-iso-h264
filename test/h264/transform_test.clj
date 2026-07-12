(ns h264.transform-test
  (:require [clojure.test :refer [deftest is testing]]
            [h264.transform :as transform]
            [codec-primitives.transform :as cp-transform]))

(deftest inverse-4x4-all-zero
  (testing "an all-zero coefficient block has zero residual (rounding bias/64 truncates to 0)"
    (is (= [[0 0 0 0] [0 0 0 0] [0 0 0 0] [0 0 0 0]]
           (transform/inverse-4x4 (vec (repeat 16 0)))))))

(deftest inverse-4x4-dc-only-is-uniform
  (testing "a pure-DC coefficient (position 0 only) produces a UNIFORM residual across all 16 pixels — this is what makes h264.decode able to reuse inverse-4x4 for cbp_luma=0 macroblocks without a separate DC-only fast path"
    (let [dc 128
          coeffs (assoc (vec (repeat 16 0)) 0 dc)
          result (transform/inverse-4x4 coeffs)
          flat (apply concat result)]
      (is (= 1 (count (distinct flat))))
      (is (= (quot (+ dc 32) 64) (first flat))))))

(deftest inverse-4x4-negative-dc-rounds-toward-negative-infinity
  (testing "matches C's arithmetic >>6 (floor), not truncating division, for negative values"
    (let [coeffs (assoc (vec (repeat 16 0)) 0 -100)
          result (transform/inverse-4x4 coeffs)
          v (ffirst result)]
      ;; (-100+32)>>6 = -68>>6 = -2 (arithmetic/floor shift), NOT (quot -68 64) = -1
      (is (= -2 v)))))

(deftest block-transform-protocol-inverse
  (testing "BlockTransform protocol's inverse delegates to inverse-4x4"
    (is (= (transform/inverse-4x4 (vec (repeat 16 0)))
           (cp-transform/inverse transform/block-transform (vec (repeat 16 0)))))))

(deftest block-transform-forward-not-implemented
  (testing "forward (encode) throws — this repo is decode-only for the pixel-codec layer"
    (is (thrown? clojure.lang.ExceptionInfo
                 (cp-transform/forward transform/block-transform [[0 0 0 0] [0 0 0 0] [0 0 0 0] [0 0 0 0]])))))

(deftest luma-dc-hadamard-single-impulse-spreads-uniformly
  (testing "luma-dc-hadamard is a real (non-identity) transform: a DC-domain impulse at (0,0) spreads to the same magnitude across all 16 blocks (forward Hadamard of a constant spatial block collapses to a single DC-domain impulse, so the inverse must do the reverse: spread it back out uniformly)"
    (let [raster (assoc (vec (repeat 16 0)) 0 64)
          out (transform/luma-dc-hadamard raster 256)]
      (is (= 16 (count out)))
      (is (= 1 (count (distinct out)))))))
