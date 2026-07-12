# kotoba-lang/org-iso-h264

Zero-dep portable `.cljc` H.264 (ITU-T H.264 / ISO/IEC 14496-10 AVC)
bitstream **framing** — Annex B NAL unit splitting/writing, NAL header
parsing, and SPS/PPS (Sequence/Picture Parameter Set) decode **and
encode**. Named
`org-iso-h264` (ISO/IEC numbering, consistent with `org-iso-aac`/
`org-iso-isobmff`/`org-iso-jpeg`/`org-iso-pdf`/`org-iso-opentype` in the
same batch — H.264 is jointly published by ITU-T and ISO/IEC, see
`org-iso-jpeg`'s README for the same joint-body naming rationale).

**New implementation, not an extraction** — `kotoba-lang/utsushi`'s
`utsushi.bitstream/split-annexb` was an unimplemented `(throw (ex-info
"TODO..."))` stub (discovered while decomposing `utsushi` into
per-format-spec repos; see `com-junkawasaki/root` ADR precedent
2607072500). This repo fills that gap for real, matching the
"entropy-coded pixels stay opaque, only framing/metadata is decoded" design
boundary the sibling `kasane`/`utsushi` repos already establish for
JPEG/AVIF/etc: **actual CABAC/CAVLC slice decode is out of scope** — that's
a capability-gated native concern per `kotoba-lang/utsushi`'s own design
(ADR-2606272200 §3).

## Namespaces

| ns | role |
|---|---|
| `h264.bitstream` | decode: Annex B start-code scan → NAL unit `[start,end)` ranges + 1-byte NAL header (`nal_ref_idc`/`nal_unit_type`). encode: `write-nal-unit` (escape + start code) / `write-annexb-stream` (concatenate) |
| `h264.rbsp` | decode: emulation-prevention byte (`0x000003`→`0x0000`) removal (`unescape`), required before any bit-level SPS/PPS parsing. encode: `escape`, the exact inverse |
| `h264.expgolomb` | decode: MSB-first bit reader + Exp-Golomb `ue(v)`/`se(v)` decode (H.264 §9.1). encode: matching bit `writer` + `write-ue!`/`write-se!`/`write-bits!`/`write-flag!`/`rbsp-trailing-bits!`/`bytes!` |
| `h264.sps` | decode: SPS (NAL type 7) parse: profile/level + picture width/height (handles high-profile chroma/scaling-list fields correctly so the bit position stays aligned, though scaling-list *values* aren't surfaced). encode: `encode`, non-high-profile only, no frame-cropping (width/height must be multiples of 16) — see Encoding below |
| `h264.pps` | decode: PPS (NAL type 8) parse: entropy coding mode (CAVLC/CABAC), reference index defaults, QP/deblocking/intra-pred flags. Covers the common case (`num_slice_groups_minus1 == 0` — FMO is essentially absent from real-world encoders); throws rather than silently mis-parsing if FMO is present. High-Profile-only trailing fields (`transform_8x8_mode_flag` etc., gated by `more_rbsp_data()`) aren't parsed — this reader doesn't track exact bit position precisely enough to detect that condition. encode: `encode`, covers the same field set as `parse` |

## Validation

`h264.sps-test`/`h264.pps-test` validate against a **real libx264-encoded
baseline-profile Annex B stream** (generated via `ffmpeg -f lavfi -i
testsrc=size=64x48 ... -profile:v baseline -bsf:v h264_mp4toannexb`) — the
SPS-derived width/height match the real 64×48 encode source exactly, and
the PPS's `entropy-coding-mode` decodes to `:cavlc`, cross-checking the
SPS's own `profile-idc=66` (baseline) finding — baseline profile forbids
CABAC by spec, so a wrong PPS parse would very likely produce `:cabac`
instead (an independent correctness signal beyond "the parser didn't
throw").

## Usage

```clojure
(require '[h264.bitstream :as bs] '[h264.rbsp :as rbsp]
         '[h264.sps :as sps] '[h264.pps :as pps])

(def units (bs/nal-units annexb-bytes))   ; => [{:start :end :bytes :nal-unit-type :kind ...} ...]
(def sps-u (first (filter #(= :sps (:kind %)) units)))
(def pps-u (first (filter #(= :pps (:kind %)) units)))
(sps/parse (rbsp/unescape (:bytes sps-u)))
;; => {:profile-idc :level-idc :chroma-format-idc :frame-mbs-only? :width :height}
(pps/parse (rbsp/unescape (:bytes pps-u)))
;; => {:pic-parameter-set-id :seq-parameter-set-id :entropy-coding-mode
;;     :num-ref-idx-l0-default-active :num-ref-idx-l1-default-active
;;     :weighted-pred? :weighted-bipred-idc :pic-init-qp :pic-init-qs
;;     :chroma-qp-index-offset :deblocking-filter-control-present?
;;     :constrained-intra-pred? :redundant-pic-cnt-present?}
```

## Encoding (Wave 2, kotoba-lang/root ADR-2607121400)

Adds the encode-side counterpart of the framing/SPS/PPS decode above —
`utsushi.codec`'s decode path already delegates real SPS parsing here
(ADR-2606272200 §H.264 wiring); this is the matching encode capability for
that same delegation boundary, not a change to `utsushi` itself.

**What's implemented and tested:**
- Exp-Golomb `ue(v)`/`se(v)` bit writer, RBSP trailing-bits padding
- Emulation-prevention byte insertion (`h264.rbsp/escape`)
- SPS encode: baseline/extended/main-profile-shaped (non-high-profile),
  progressive-only (`frame_mbs_only_flag=1`), no frame-cropping (width and
  height must both be multiples of 16 — real 1080p needs padding to 1088
  the same way real encoders do; cropping to exactly 1080 isn't
  implemented)
- PPS encode: full field set `parse` reads (CAVLC/CABAC entropy mode
  selectable, though only CAVLC is spec-legal for baseline profile — that
  constraint lives in the *SPS* profile-idc, not enforced by
  `pps/encode` itself)
- NAL unit wrapping (`bitstream/write-nal-unit`/`write-annexb-stream`):
  start code + escaping, round-trips through `nal-units`/`split-annexb`

**What's explicitly NOT implemented** (be aware before assuming this
produces a decodable video frame):
- **Slice header and macroblock/pixel data are not implemented at all** —
  no CAVLC/CABAC entropy coding, no DCT/quantization, no intra/inter
  prediction. This repo (both decode and encode) stops at the
  container/parameter-set framing layer by design (see the "framing only"
  scope note above) — actual pixel encoding is a capability-gated native
  concern, same as decode.
- High-profile SPS encode (chroma_format_idc signaling, scaling lists) —
  `sps/encode` throws if given a high-profile `profile-idc`.
- Frame cropping on encode (arbitrary width/height) — only MB-aligned
  (multiple-of-16) dimensions.

All encode-side correctness claims are backed by round-trip tests against
this repo's own `parse` functions (`sps_test.clj`/`pps_test.clj`/
`bitstream_test.clj`/`expgolomb_test.clj`/`rbsp_test.clj`) — there is no
independent reference decoder (e.g. ffprobe) cross-check for the encode
path the way `sps-from-real-encoder` has for decode, since the output
here is a synthetic parameter-set NAL, not a decodable frame.

## Test

```sh
clojure -M:test
```
