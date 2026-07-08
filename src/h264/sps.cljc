(ns h264.sps
  "H.264 Sequence Parameter Set (SPS, NAL type 7) parsing — profile/level and
   picture dimensions (H.264 §7.3.2.1.1 seq_parameter_set_data, §7.4.2.1.1
   frame-cropping → width/height). Reads the *unescaped* RBSP (see
   h264.rbsp) via Exp-Golomb (see h264.expgolomb). High-profile scaling
   lists are skipped correctly (bits consumed, values discarded) so the
   reader stays aligned for the fields that follow, but their content isn't
   surfaced. Validated against a real libx264-encoded (baseline profile)
   NAL — see test/h264/sps_test.clj.

   New implementation as part of the kotoba-lang reverse-domain
   media/graphics standards-substrate split (com-junkawasaki/root)."
  (:require [h264.expgolomb :as eg]))

(def ^:private high-profile-family
  #{100 110 122 244 44 83 86 118 128 138 139 134 135})

(defn- skip-scaling-list! [r size]
  (loop [j 0 last-scale 8 next-scale 8]
    (when (< j size)
      (if (zero? next-scale)
        (recur (inc j) last-scale next-scale)
        (let [delta (eg/se! r)
              ns2   (mod (+ last-scale delta 256) 256)]
          (recur (inc j) (if (zero? ns2) last-scale ns2) ns2))))))

(defn parse
  "Parse an SPS RBSP (already emulation-unescaped, header byte INCLUDED at
   index 0 — see h264.rbsp/unescape) → {:profile-idc :level-idc :width
   :height :frame-mbs-only? :chroma-format-idc}."
  [rbsp]
  (let [r (eg/reader rbsp)
        _ (eg/bits! r 8)                                   ; NAL header byte (already validated by caller)
        profile-idc (eg/bits! r 8)
        _constraint (eg/bits! r 8)                          ; constraint_set0..5_flag + reserved_zero_2bits
        level-idc   (eg/bits! r 8)
        _sps-id     (eg/ue! r)
        chroma-format-idc
        (if (contains? high-profile-family profile-idc)
          (let [cfi (eg/ue! r)]
            (when (= cfi 3) (eg/flag! r))                   ; separate_colour_plane_flag
            (eg/ue! r)                                       ; bit_depth_luma_minus8
            (eg/ue! r)                                       ; bit_depth_chroma_minus8
            (eg/flag! r)                                     ; qpprime_y_zero_transform_bypass_flag
            (when (= 1 (eg/flag! r))                         ; seq_scaling_matrix_present_flag
              (let [n (if (= cfi 3) 12 8)]
                (dotimes [i n]
                  (when (= 1 (eg/flag! r))                    ; seq_scaling_list_present_flag[i]
                    (skip-scaling-list! r (if (< i 6) 16 64))))))
            cfi)
          1)                                                  ; default 4:2:0 when not signaled
        _log2-max-frame-num (eg/ue! r)
        poc-type (eg/ue! r)
        _ (case poc-type
            0 (eg/ue! r)                                      ; log2_max_pic_order_cnt_lsb_minus4
            1 (do (eg/flag! r) (eg/se! r) (eg/se! r)
                  (let [n (eg/ue! r)] (dotimes [_ n] (eg/se! r))))
            nil)
        _max-num-ref-frames (eg/ue! r)
        _gaps-allowed (eg/flag! r)
        pic-width-in-mbs-minus1 (eg/ue! r)
        pic-height-in-map-units-minus1 (eg/ue! r)
        frame-mbs-only? (= 1 (eg/flag! r))
        _mb-adaptive (when-not frame-mbs-only? (eg/flag! r))
        _direct-8x8-inference (eg/flag! r)
        crop? (= 1 (eg/flag! r))
        [crop-l crop-r crop-t crop-b] (if crop?
                                         [(eg/ue! r) (eg/ue! r) (eg/ue! r) (eg/ue! r)]
                                         [0 0 0 0])
        sub-width-c  (if (= chroma-format-idc 3) 1 2)
        sub-height-c (if (= chroma-format-idc 1) 2 (if (= chroma-format-idc 3) 1 1))
        crop-unit-x  sub-width-c
        crop-unit-y  (* sub-height-c (if frame-mbs-only? 1 2))
        width  (- (* (inc pic-width-in-mbs-minus1) 16) (* crop-unit-x (+ crop-l crop-r)))
        height (- (* (if frame-mbs-only? 1 2) (inc pic-height-in-map-units-minus1) 16)
                  (* crop-unit-y (+ crop-t crop-b)))]
    {:profile-idc profile-idc
     :level-idc   level-idc
     :chroma-format-idc chroma-format-idc
     :frame-mbs-only? frame-mbs-only?
     :width  width
     :height height}))
