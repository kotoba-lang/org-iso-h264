(ns h264.slice-test
  "Round-trip test for `h264.slice/encode-header!` (ADR-2607122000
   Migration step 8) against the real, already-tested `parse-header!` —
   the same discipline `h264.cavlc-test` uses for CAVLC encode."
  (:require [clojure.test :refer [deftest is testing]]
            [h264.slice :as slice]
            [h264.expgolomb :as eg]))

(def sps {:log2-max-frame-num-minus4 0 :pic-order-cnt-type 0 :log2-max-pic-order-cnt-lsb-minus4 0})
(def pps {:pic-init-qp 26 :deblocking-filter-control-present? true})

(deftest encode-header-roundtrips
  (testing "encode-header! then parse-header! (with a 1-byte dummy NAL header prefix, matching parse-header!'s convention) reproduces the same slice-qp/frame-num"
    (let [w (eg/writer)
          _ (eg/write-bits! w 8 0x65) ; dummy NAL header byte (nal_ref_idc=3,type=5)
          _ (slice/encode-header! w sps pps {:frame-num 0 :idr-pic-id 0 :slice-qp 28})
          _ (eg/rbsp-trailing-bits! w)
          bytes (eg/bytes! w)
          r (eg/reader bytes)
          _ (eg/bits! r 8) ; consume the NAL header byte
          header (slice/parse-header! r sps pps 5 3)]
      (is (= 0 (:first-mb-in-slice header)))
      (is (= 7 (:slice-type header)))
      (is (= 0 (:pic-parameter-set-id header)))
      (is (= 0 (:frame-num header)))
      (is (= 0 (:idr-pic-id header)))
      (is (= 28 (:slice-qp header))))))

(deftest encode-header-nonzero-frame-num-and-qp
  (testing "non-default frame-num/qp values round-trip"
    (let [w (eg/writer)
          _ (eg/write-bits! w 8 0x65)
          _ (slice/encode-header! w sps pps {:frame-num 5 :idr-pic-id 2 :slice-qp 40})
          _ (eg/rbsp-trailing-bits! w)
          bytes (eg/bytes! w)
          r (eg/reader bytes)
          _ (eg/bits! r 8)
          header (slice/parse-header! r sps pps 5 3)]
      (is (= 5 (:frame-num header)))
      (is (= 2 (:idr-pic-id header)))
      (is (= 40 (:slice-qp header))))))

(deftest encode-header-no-deblocking-field-when-absent
  (testing "when the PPS says deblocking_filter_control_present_flag=false, no extra bits are written/expected"
    (let [pps-no-dbf (assoc pps :deblocking-filter-control-present? false)
          w (eg/writer)
          _ (eg/write-bits! w 8 0x65)
          _ (slice/encode-header! w sps pps-no-dbf {:frame-num 0 :idr-pic-id 0 :slice-qp 26})
          _ (eg/rbsp-trailing-bits! w)
          bytes (eg/bytes! w)
          r (eg/reader bytes)
          _ (eg/bits! r 8)
          header (slice/parse-header! r sps pps-no-dbf 5 3)]
      (is (= 26 (:slice-qp header))))))
