# kotoba-lang/org-iso-h264

Zero-dep portable `.cljc` H.264 (ITU-T H.264 / ISO/IEC 14496-10 AVC)
bitstream **framing** — Annex B NAL unit splitting, NAL header parsing, and
SPS (Sequence Parameter Set) profile/level/dimensions decode. Named
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
| `h264.bitstream` | Annex B start-code scan → NAL unit `[start,end)` ranges + 1-byte NAL header (`nal_ref_idc`/`nal_unit_type`) |
| `h264.rbsp` | Emulation-prevention byte (`0x000003`→`0x0000`) removal — required before any bit-level SPS/PPS parsing |
| `h264.expgolomb` | MSB-first bit reader + Exp-Golomb `ue(v)`/`se(v)` decode (H.264 §9.1) |
| `h264.sps` | SPS (NAL type 7) parse: profile/level + picture width/height (handles high-profile chroma/scaling-list fields correctly so the bit position stays aligned, though scaling-list *values* aren't surfaced) |

## Validation

`h264.sps-test` validates against a **real libx264-encoded baseline-profile
Annex B stream** (generated via `ffmpeg -f lavfi -i testsrc=size=64x48 ...
-profile:v baseline -bsf:v h264_mp4toannexb`) — the SPS-derived width/height
are asserted to match the real 64×48 encode source exactly.

## Usage

```clojure
(require '[h264.bitstream :as bs] '[h264.rbsp :as rbsp] '[h264.sps :as sps])

(def units (bs/nal-units annexb-bytes))   ; => [{:start :end :bytes :nal-unit-type :kind ...} ...]
(def sps-u (first (filter #(= :sps (:kind %)) units)))
(sps/parse (rbsp/unescape (:bytes sps-u)))
;; => {:profile-idc :level-idc :chroma-format-idc :frame-mbs-only? :width :height}
```

## Test

```sh
clojure -M:test
```
