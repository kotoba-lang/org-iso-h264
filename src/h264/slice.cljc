(ns h264.slice
  "H.264 slice header parsing (ITU-T H.264 / ISO/IEC 14496-10 §7.3.3
   slice_header). Scope: exactly what's needed to decode a single IDR
   I-slice (this repo's decode scope, see `h264.decode`) — first_mb_in_slice,
   slice_type, pic_parameter_set_id, frame_num, idr_pic_id, POC fields
   (only for pic_order_cnt_type 0, the common case; type 1/2 pictures throw
   — type 2 in particular derives POC directly from frame_num and needs no
   extra bits here, so it's actually already fully handled by NOT reading
   anything extra, but is called out explicitly since it's what this repo's
   own golden-vector fixtures use), dec_ref_pic_marking's two IDR flags,
   slice_qp_delta, and the deblocking_filter_control fields (read-and-
   discarded — SPS/PPS-controlled deblocking is out of scope for this
   decoder's R0.5 reference-decode phase; see README).

   `parse-header!` takes an already-positioned `h264.expgolomb` reader
   (created by the caller over the slice RBSP, NAL header byte included at
   index 0 per this repo's convention — see `h264.sps`/`h264.pps`) and
   ADVANCES it past the slice header, so the SAME reader can continue
   being used by `h264.decode` for macroblock_layer() parsing immediately
   afterward — unlike `h264.sps/parse`/`h264.pps/parse`, which each own a
   private reader, this can't be a black box because the caller needs the
   reader's post-header bit position.

   New implementation as part of the kotoba-lang reverse-domain
   media/graphics standards-substrate split (com-junkawasaki/root)."
  (:require [h264.expgolomb :as eg]))

(defn parse-header!
  "Advance `r` (an `h264.expgolomb` reader already past the 1-byte NAL
   header) past the slice header, given the SPS map (`h264.sps/parse`
   result) and PPS map (`h264.pps/parse` result) it references. `nal-type`
   is the containing NAL's `nal-unit-type` (5 = IDR slice — this repo only
   decodes IDR I-slices, see `h264.decode`).

   Returns {:first-mb-in-slice :slice-type :pic-parameter-set-id
   :frame-num :idr-pic-id :slice-qp-delta}."
  [r {:keys [log2-max-frame-num-minus4 pic-order-cnt-type
             log2-max-pic-order-cnt-lsb-minus4]}
   {:keys [pic-init-qp deblocking-filter-control-present?]}
   nal-type]
  (let [idr? (= nal-type 5)
        first-mb-in-slice (eg/ue! r)
        slice-type (eg/ue! r)
        pic-parameter-set-id (eg/ue! r)
        frame-num (eg/bits! r (+ log2-max-frame-num-minus4 4))
        idr-pic-id (when idr? (eg/ue! r))
        _ (case pic-order-cnt-type
            0 (do (eg/bits! r (+ log2-max-pic-order-cnt-lsb-minus4 4))
                  nil)
            2 nil ; POC derived directly from frame_num — no extra bits
            (throw (ex-info "h264.slice: only pic_order_cnt_type 0/2 supported"
                             {:pic-order-cnt-type pic-order-cnt-type})))
        _ (when idr?
            (eg/flag! r)   ; no_output_of_prior_pics_flag
            (eg/flag! r))  ; long_term_reference_flag
        slice-qp-delta (eg/se! r)
        _ (when deblocking-filter-control-present?
            (let [idc (eg/ue! r)]
              (when (not= idc 1)
                (eg/se! r) (eg/se! r))))]
    {:first-mb-in-slice first-mb-in-slice
     :slice-type slice-type
     :pic-parameter-set-id pic-parameter-set-id
     :frame-num frame-num
     :idr-pic-id idr-pic-id
     :slice-qp-delta slice-qp-delta
     :slice-qp (+ pic-init-qp slice-qp-delta)}))
