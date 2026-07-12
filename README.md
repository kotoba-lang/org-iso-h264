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
2607072500). This repo originally filled that gap only for framing/
metadata (NAL splitting, SPS/PPS), matching the "entropy-coded pixels stay
opaque" design boundary `kasane`/`utsushi` establish for JPEG/AVIF/etc
(ADR-2606272200 §3) — actual pixel decode was explicitly out of scope.

**That framing-only boundary has since been amended for a narrow "R0.5"
pixel-decode scope** (ADR-2607122000, `com-junkawasaki/root`): this repo
now also implements a real, non-realtime, CAVLC + intra-only H.264
baseline pixel decoder (`h264.decode` et al., see "Pixel decode" below).
The original framing-only boundary still holds for **CABAC**, inter
prediction, and chroma — those remain out of scope (see below for exactly
what is/isn't covered).

## Namespaces

| ns | role |
|---|---|
| `h264.bitstream` | decode: Annex B start-code scan → NAL unit `[start,end)` ranges + 1-byte NAL header (`nal_ref_idc`/`nal_unit_type`). encode: `write-nal-unit` (escape + start code) / `write-annexb-stream` (concatenate) |
| `h264.rbsp` | decode: emulation-prevention byte (`0x000003`→`0x0000`) removal (`unescape`), required before any bit-level SPS/PPS parsing. encode: `escape`, the exact inverse |
| `h264.expgolomb` | decode: MSB-first bit reader + Exp-Golomb `ue(v)`/`se(v)` decode (H.264 §9.1). encode: matching bit `writer` + `write-ue!`/`write-se!`/`write-bits!`/`write-flag!`/`rbsp-trailing-bits!`/`bytes!` |
| `h264.sps` | decode: SPS (NAL type 7) parse: profile/level + picture width/height (handles high-profile chroma/scaling-list fields correctly so the bit position stays aligned, though scaling-list *values* aren't surfaced). encode: `encode`, non-high-profile only, no frame-cropping (width/height must be multiples of 16) — see Encoding below |
| `h264.pps` | decode: PPS (NAL type 8) parse: entropy coding mode (CAVLC/CABAC), reference index defaults, QP/deblocking/intra-pred flags. Covers the common case (`num_slice_groups_minus1 == 0` — FMO is essentially absent from real-world encoders); throws rather than silently mis-parsing if FMO is present. High-Profile-only trailing fields (`transform_8x8_mode_flag` etc., gated by `more_rbsp_data()`) aren't parsed — this reader doesn't track exact bit position precisely enough to detect that condition. encode: `encode`, covers the same field set as `parse` |
| `h264.slice` | decode: slice header parse (`first_mb_in_slice`/`slice_type`/`pic_parameter_set_id`/`frame_num`/`idr_pic_id`/POC (type 0 or 2 only)/IDR dec_ref_pic_marking flags/`slice_qp_delta`/deblocking-control fields, read-and-discarded). `parse-header!` advances the SAME reader `h264.decode` continues using for macroblock data (unlike `sps`/`pps`'s private-reader `parse`) |
| `h264.quant` | dequantization: the `normAdjust4x4` V-table (§8.5.9) + per-position `ac-qmul`/single-scalar `dc-qmul`. Implements `codec-primitives.quant/QuantScale`. Baseline scope only — no custom scaling lists (flat weight 16 everywhere) |
| `h264.transform` | the integer 4x4 inverse transform (`inverse-4x4`, §8.5.10) + the Intra16x16 luma DC Hadamard transform (`luma-dc-hadamard`). Implements `codec-primitives.transform/BlockTransform` (`forward`/encode throws — decode-only). Arithmetic ported 1:1 from FFmpeg's reference decoder for bit-exactness, including an internal coefficient-array transpose whose necessity was discovered empirically (see "Pixel decode" below) |
| `h264.cavlc` | CAVLC residual entropy decode (§9.2): `coeff_token`/`total_zeros`/`run_before` VLC tables (luma AND the ChromaArrayType 1 chroma-DC `nC==-1` special case) + `residual-block!` (coeff_token → trailing-ones signs → level_prefix/suffix → total_zeros → run_before → position reconstruction) |
| `h264.intra-pred` | Intra_16x16 luma prediction (§8.3.3): DC/Vertical/Horizontal (modes 0/1/2) only — Plane (mode 3) throws. Intra_Chroma prediction (§8.3.4, 4:2:0 8x8 blocks, `predict-chroma-8x8`): ALL FOUR modes (DC/Horizontal/Vertical/Plane) — see "Chroma decode" below for why Plane is implemented here but not for luma |
| `h264.quant` | (also) `chroma-qp`: QPc derivation from QPy + PPS `chroma_qp_index_offset` (§8.5.8 Table 8-15) |
| `h264.transform` | (also) `chroma-dc-hadamard`: the 2x2 chroma-DC Hadamard transform (§8.5.8/§8.5.11) |
| `h264.decode` | orchestration: NAL → SPS/PPS/slice header → macroblock loop → (Intra_16x16/Intra_Chroma prediction + CAVLC residual + dequant + inverse transform) → reconstructed luma AND chroma (Cb/Cr) planes. See "Pixel decode" below for exact scope |

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

## Pixel decode (Wave 3, ADR-2607122000 Phase 1 / "R0.5")

`h264.decode/decode-idr-frame` decodes a real H.264 Annex B elementary
stream to a raw luma pixel plane — the first time this ecosystem can
actually reconstruct pixels from a real encoder's output rather than only
parse framing/metadata. This is explicitly a **non-realtime, correctness-
first reference decoder** (the "R0.5" tier of ADR-2607122000 — it is a
golden model for a future capability-gated *native* realtime decoder
(R1), not a claim that pure cljc decodes video at framerate).

**What's implemented:**
- Baseline profile, CAVLC (no CABAC)
- A single IDR I-slice covering the whole picture (no multi-slice, no
  multiple pictures/GOP, no P/B slices, no reference-picture buffering)
- `mb_type` **Intra_16x16 only** (1..24) — `I_NxN`/Intra_4x4/Intra_8x8
  (`mb_type` 0) and `I_PCM` (`mb_type` 25) throw a clear error rather than
  being silently mis-decoded
- Both `CodedBlockPatternLuma` values I16x16 macroblocks can have: 0 (DC-
  only, no AC residual) and 15 (full AC residual for all four luma 8x8
  groups) — both real CAVLC paths are exercised
- Intra_16x16 luma prediction modes 0 (Vertical), 1 (Horizontal), 2 (DC) —
  mode 3 (Plane) throws
- Multi-macroblock pictures, with real cross-macroblock CAVLC neighbor
  (`nC`) derivation and cross-macroblock Vertical/Horizontal prediction
  using actual reconstructed neighbor pixels (the code path is generic,
  not hardcoded to one macroblock) — see the multi-macroblock golden
  vector below for the extent this is actually validated
- **Chroma (Cb/Cr), ChromaArrayType 1 (4:2:0) only** (Wave 4 addition,
  chroma-decode follow-up to Phase 1): chroma DC (the `nC==-1` special
  CAVLC table + 2x2 Hadamard transform) and chroma AC (regular
  neighbor-derived 4x4 blocks, reusing the luma AC CAVLC/dequant/IDCT
  path with QPc — `h264.quant/chroma-qp` — instead of QPy), for both
  `CodedBlockPatternChroma` values that carry residual (1 = DC only, 2 =
  DC+AC) as well as 0 (prediction only, no residual). All FOUR
  Intra_Chroma prediction modes (DC/Horizontal/Vertical/Plane) are
  implemented — unlike luma, where Plane is out of scope, chroma Plane
  IS implemented because a real x264 encoder was observed selecting it
  even for near-flat chroma content whenever both neighbors are
  available (an RD tie-break, not a genuine gradient) — leaving it
  unimplemented would make chroma decode fail on ordinary real streams,
  not just contrived ones. `decode-idr-frame` returns `:cb`/`:cr` planes
  (each `(width/2)*(height/2)`, flat row-major) alongside `:luma`. Any
  `chroma_format_idc` other than 1 throws.

**What's explicitly NOT implemented** (out of scope, not silently wrong):
CABAC, P/B slices and all inter prediction/motion compensation, multiple
reference frames, ChromaArrayType 0/2/3 (monochrome/4:2:2/4:4:4), the
deblocking loop filter, `I_NxN`/`I_PCM` macroblock types, Plane *luma*
intra prediction (Plane *chroma* IS implemented, see above), frame
cropping (picture width/height must be exact multiples of 16), and
multi-picture streams (only the first IDR slice is decoded).

**Golden-vector validation is real but narrow — read this before trusting
a number beyond it.** `test/h264/decode_test.clj` validates against real
`ffmpeg 8.1.1`/`x264 core 165` — encoded Annex B streams, compared
bit-exact (no tolerance) against the SAME file decoded by a real `ffmpeg`
(not the pre-encode source image, since lossy encoding changes pixel
values) — **luma AND chroma (Cb/Cr) both**, for every fixture below:

1. **`flat16-dc-only.h264`** — a single flat 16x16 macroblock,
   `CodedBlockPatternLuma=0`, DC prediction mode. Exercises: SPS/PPS/
   slice-header parsing, `mb_type`→Intra_16x16 mapping, the luma DC
   Hadamard transform + dequant, DC prediction with unavailable
   neighbors (defaults to 128 per spec). Chroma here is DC-only
   (`CodedBlockPatternChroma` 0 or 1) — no chroma AC.
2. **`gradient16-ac.h264`** — a single 16x16 macroblock with a real
   horizontal luma gradient, forced (`x264 --partitions none`) to
   Intra_16x16 at a QP where `CodedBlockPatternLuma=15`. Exercises: real
   CAVLC `coeff_token`/level/`total_zeros`/`run_before` decode with
   nonzero coefficients, within-macroblock CAVLC neighbor (`nC`)
   derivation across the 16 luma 4x4 sub-blocks, per-position AC
   dequantization, and the full 4x4 inverse transform combined with the
   DC term.
3. **`chroma-multimb32.h264`** — 32x32 (2x2 macroblocks), flat
   (uniform-gray) luma but a real 2-D oscillating (`sin`/`cos`) Cb/Cr
   pattern, so libx264 never has an RD reason to pick anything but
   Intra_16x16 for luma (`mb I  I16..4: 100.0%  0.0%  0.0%`) while
   Cb/Cr get genuine per-macroblock DC **and AC** residual across all 4
   macroblocks (`coded y,uvDC,uvAC intra: 0.0% 100.0% 100.0%`). This is
   the multi-macroblock validation: real cross-macroblock CAVLC neighbor
   (`nC`) derivation for chroma AC (proven across 4 separate macroblocks,
   not just within one), and 3 of the 4 Intra_Chroma modes actually
   selected by a real encoder (DC/Horizontal/Vertical — `:mb-intra-chroma-pred-modes`
   is `[0 1 2 0]`) plus real cross-macroblock luma Vertical prediction
   (`:mb-pred-modes` is `[2 2 0 0]` — DC for the top row with no top
   neighbor, Vertical for the bottom row using actual reconstructed
   neighbor pixels).
4. **`horizontal-multimb64.h264`** — 64x64 (4x4=16 macroblocks), luma
   varying smoothly by ROW only (`sin` of Y, flat chroma), so libx264 has
   a genuine RD reason to pick Intra_16x16 LUMA HORIZONTAL prediction
   (mode 1) for most macroblocks once a left neighbor is available
   (`:mb-pred-modes` is `[2 1 1 1 0 1 1 1 0 1 1 1 0 1 1 1]` — 12 of 16
   Horizontal). This is the first bit-exact-validated real-encoder example
   of luma Horizontal in a multi-macroblock picture — see
   `test/h264/decode_test.clj`'s `horizontal-multimb64-golden-vector` for
   the fixture's generation recipe and exactly what it exercises (both
   `CodedBlockPatternLuma` 0 and 15 paths, real cross-macroblock nC
   derivation for the Intra16x16 luma DC block specifically). This was a
   real, previously-shipped, tracked bug (see git history / ADR for the
   root-cause writeup): two independent bugs compounded — (a) the luma DC
   block's cross-MB `nC` derivation read the wrong neighbor state, and (b)
   `h264.decode/blk->col-row` used a wrong block-index-to-position mapping
   AND `h264.transform/luma-dc-hadamard` was missing an input transpose —
   both had to be fixed together (fixing either alone still fails, see
   `blk->col-row`'s docstring for why). Root-caused and fixed by
   cross-referencing FFmpeg's own `libavcodec/h264dec.c`/`h264_mb.c`
   source rather than by guessing.

Fixtures 1/2 are **single-macroblock pictures** (mode 0/1 Intra_16x16
luma prediction is implemented but not exercised by either — a real
encoder never selects Vertical/Horizontal for an unavailable neighbor).
Fixture 3 validates real chroma cross-macroblock decode plus luma
DC/Vertical. Fixture 4 validates real cross-macroblock luma Horizontal.
Likewise the deblocking filter is not implemented at all; fixtures 1-3
happen not to need it because they reconstruct to (locally) constant
regions with zero gradient at block boundaries, where deblocking is a
mathematical no-op — fixture 4 has a genuine cross-block-boundary
gradient, so it's encoded with `--no-deblock` (`disable_deblocking_filter_idc=1`
in the slice header, a properly signaled, spec-mandatory flag that any
compliant decoder — including the real ffmpeg used for the reference
output — MUST honor by skipping deblocking, not an encoder-side-only
nicety). Deblocking itself has NOT been shown to be safe to skip in
general beyond these fixtures.

**Nonobvious implementation details, called out because a plausible-
looking alternative silently produces wrong (but not obviously wrong —
i.e. right-shaped, wrong-valued) output:**
- `h264.transform/inverse-4x4`'s coefficient input needs an internal
  transpose relative to the "obvious" row-major reading of FFmpeg's
  `ff_h264_idct_add` C source — this was found empirically (decoding
  `gradient16-ac.h264` first produced a *transposed* picture) rather than
  by static code reading, and is called out in the namespace docstring so
  a future port doesn't "simplify it away."
- `>>6`/`>>8` rounding in `h264.decode`/`h264.transform` is implemented
  with `bit-shift-right` (an arithmetic/floor shift), not `quot` — for
  the negative coefficient values CAVLC can produce, `quot` (truncating
  toward zero) gives a different result than C's `>>` whenever the value
  isn't an exact multiple of 64. This was caught during implementation,
  not by the golden-vector tests (both fixtures' specific coefficient
  values happen not to expose most instances of this — see
  `h264.transform-test/inverse-4x4-negative-dc-rounds-toward-negative-infinity`
  for an isolated regression test of just this).
- **Chroma sub-block bitstream/placement order is plain RASTER order
  (0=top-left, 1=top-right, 2=bottom-left, 3=bottom-right)** —
  `h264.decode/chroma-blk->col-row`. Luma's own 4x4-sub-block order
  (`h264.decode/blk->col-row`) uses the SAME plain-raster convention
  (per-8x8-quadrant TL/TR/BL/BR, quadrants themselves in TL/TR/BL/BR
  order) — see that def's docstring for the derivation (cross-checked
  three ways against FFmpeg's `libavcodec/h264dec.c`/`h264_mb.c` source).
  An EARLIER version of `blk->col-row` used a column-major/Z-order
  variant instead (a transpose of the correct table) that went
  undetected for a full development session because every fixture up to
  that point had luma content that's symmetric under transpose — see the
  "luma Horizontal desync" bullet below for how this was actually caught
  and fixed. Do not assume luma and chroma need DIFFERENT conventions by
  analogy with ffmpeg's shared `16 + 16*chroma_idx + i4x4` index
  arithmetic — that arithmetic is shared, but the ACTUAL raster-vs-Z-order
  choice must be independently verified for each (chroma correctly uses
  raster; luma, after this fix, also correctly uses raster).
- **Two independent bugs compounded to cause a real, previously-shipped,
  multi-macroblock CAVLC desync/wrong-pixel bug whenever libx264 selected
  Intra_16x16 LUMA HORIZONTAL prediction across macroblocks** (see
  `test/h264/decode_test.clj`'s `horizontal-multimb64-golden-vector`, the
  fixture that finally exercises this path): (1) `h264.decode/decode-macroblock!`'s
  Intra16x16 luma DC block computed its cross-MB CAVLC `nC` from the
  neighbor's OWN `:dc-nnz` (that neighbor macroblock's DC-block
  total-coeff) instead of the neighbor's `:ac-nnz` at luma 4x4 block
  position `[3,0]`/`[0,3]` — per ffmpeg's `pred_non_zero_count`, the DC
  block's `nC` prediction reads the SAME neighbor cache cell as luma AC
  block 0, which is explicitly ZERO-FILLED whenever that neighbor had
  `CodedBlockPatternLuma=0` (DC-only), REGARDLESS of how many nonzero DC
  coefficients that neighbor actually had. Using `:dc-nnz` instead
  silently picks the wrong coeff_token VLC nC-class and desyncs the bit
  reader on the very next macroblock with a real neighbor-derived DC
  `nC`. (2) `h264.decode/blk->col-row` (see bullet above) AND
  `h264.transform/luma-dc-hadamard` (missing the same kind of input
  transpose `inverse-4x4` already has, see below) were BOTH wrong in a way
  that happened to cancel out for every existing single-MB or flat-luma
  fixture — fixing only one of the two, in isolation, produces a THIRD
  wrong permutation, not a partial improvement (verified empirically
  while root-causing this). Both root causes were found by cross-checking
  FFmpeg's actual `libavcodec/h264dec.c`/`h264_mb.c`/`h264_cavlc.c` source
  (`pred_non_zero_count`, `scan8[]`'s neighbor-addressing formula, the
  `ref_index`/`scan8[0|4|8|12]` 8x8-partition assignment, and the
  `dc_mapping[16]` table), not by static guessing — a real,
  content-specific test (luma varying smoothly along ONE screen axis,
  spanning multiple macroblocks with a real left/top neighbor) was
  necessary to distinguish correct from incorrect in the first place.
- **Chroma DC/AC residual bitstream order is Cb-DC, Cr-DC, Cb-AC (×4),
  Cr-AC (×4)** — NOT Cb(DC then AC) followed by Cr(DC then AC). Decoding
  one component fully before starting the other silently desyncs the bit
  reader on the first macroblock that has real chroma AC
  (`CodedBlockPatternChroma == 2`) — DC-only fixtures don't exercise this
  order at all, so this only surfaces with real multi-component AC
  content. See `h264.decode/decode-chroma-ac-blocks!`'s docstring.

## Test

```sh
clojure -M:test
```
