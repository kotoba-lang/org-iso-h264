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
baseline pixel decoder (`h264.decode` et al., see "Pixel decode" below),
chroma (4:2:0) decode, the encode-side counterpart (`h264.encode`, Migration
step 8, initially luma-only), real chroma (Cb/Cr) encode (see "Pixel
encode" below), **P-slice inter prediction decode** (`decode-gop`,
Migration step 7: P_Skip + P_L0_16x16, single reference frame — see "Pixel
decode: P-slice (inter)" below), and — most recently — **real sub-pel/
non-zero motion compensation** for that same P_Skip/P_L0_16x16 path (luma
quarter-sample + chroma eighth-sample interpolation, `h264.interp` — see
"Sub-pel motion compensation" below). The original framing-only boundary
still holds for **CABAC**, multi-reference/B-slice inter prediction,
sub-partitioned motion (`P_L0_L0_16x8`/`P_L0_L0_8x16`/`P_8x8`/`P_8x8ref0`),
and inter-side ENCODE — those remain out of scope (see below for exactly
what is/isn't covered).

## Namespaces

| ns | role |
|---|---|
| `h264.bitstream` | decode: Annex B start-code scan → NAL unit `[start,end)` ranges + 1-byte NAL header (`nal_ref_idc`/`nal_unit_type`). encode: `write-nal-unit` (escape + start code) / `write-annexb-stream` (concatenate) |
| `h264.rbsp` | decode: emulation-prevention byte (`0x000003`→`0x0000`) removal (`unescape`), required before any bit-level SPS/PPS parsing. encode: `escape`, the exact inverse |
| `h264.expgolomb` | decode: MSB-first bit reader + Exp-Golomb `ue(v)`/`se(v)` decode (H.264 §9.1). encode: matching bit `writer` + `write-ue!`/`write-se!`/`write-bits!`/`write-flag!`/`rbsp-trailing-bits!`/`bytes!` |
| `h264.sps` | decode: SPS (NAL type 7) parse: profile/level + picture width/height (handles high-profile chroma/scaling-list fields correctly so the bit position stays aligned, though scaling-list *values* aren't surfaced). encode: `encode`, non-high-profile only, no frame-cropping (width/height must be multiples of 16) — see Encoding below |
| `h264.pps` | decode: PPS (NAL type 8) parse: entropy coding mode (CAVLC/CABAC), reference index defaults, QP/deblocking/intra-pred flags. Covers the common case (`num_slice_groups_minus1 == 0` — FMO is essentially absent from real-world encoders); throws rather than silently mis-parsing if FMO is present. High-Profile-only trailing fields (`transform_8x8_mode_flag` etc., gated by `more_rbsp_data()`) aren't parsed — this reader doesn't track exact bit position precisely enough to detect that condition. encode: `encode`, covers the same field set as `parse` |
| `h264.slice` | decode: slice header parse (`first_mb_in_slice`/`slice_type`/`pic_parameter_set_id`/`frame_num`/`idr_pic_id`/POC (type 0 or 2 only)/IDR dec_ref_pic_marking flags/`slice_qp_delta`/deblocking-control fields, read-and-discarded). `parse-header!` advances the SAME reader `h264.decode` continues using for macroblock data (unlike `sps`/`pps`'s private-reader `parse`). ALSO: P-slice fields (`num_ref_idx_active_override_flag`/`num_ref_idx_l0_active_minus1` — must resolve to exactly 1 active reference — `ref_pic_list_modification_flag_l0`/weighted-prediction/non-IDR `dec_ref_pic_marking` — all throw if set to anything beyond this repo's single-reference-frame, no-reordering, no-weighting scope), see "Pixel decode: P-slice (inter)" below |
| `h264.quant` | dequantization: the `normAdjust4x4` V-table (§8.5.9) + per-position `ac-qmul`/single-scalar `dc-qmul`. Implements `codec-primitives.quant/QuantScale`. Baseline scope only — no custom scaling lists (flat weight 16 everywhere) |
| `h264.transform` | decode: the integer 4x4 inverse transform (`inverse-4x4`, §8.5.10) + the Intra16x16 luma DC Hadamard transform (`luma-dc-hadamard`) + the chroma-DC 2x2 Hadamard transform (`chroma-dc-hadamard`). Arithmetic ported 1:1 from FFmpeg's reference decoder for bit-exactness, including an internal coefficient-array transpose whose necessity was discovered empirically (see "Pixel decode" below). encode: `forward-4x4` (textbook forward transform, API symmetry/DC-extraction only) + `forward-luma-dc-hadamard` (exact derived inverse of `luma-dc-hadamard`) + `forward-chroma-dc-hadamard` (exact derived inverse of `chroma-dc-hadamard`, same probe-and-invert methodology) — see "Pixel encode" below |
| `h264.cavlc` | decode: CAVLC residual entropy decode (§9.2): `coeff_token`/`total_zeros`/`run_before` VLC tables (luma AND the ChromaArrayType 1 chroma-DC `nC==-1` special case) + `residual-block!` (coeff_token → trailing-ones signs → level_prefix/suffix → total_zeros → run_before → position reconstruction). encode: `encode-residual-block!`, reusing the same tables as reverse lookups (already generic over `:chroma-dc` — no chroma-specific CAVLC encode code was needed) |
| `h264.encode` | encode-side orchestration: quantization (exact least-squares solve, NOT a memorized MF table, reused unchanged for chroma via QPc) → CAVLC → simplified SAD-based mode decision (luma Intra_16x16 AND chroma Intra_Chroma, jointly for Cb+Cr) → macroblock loop → NAL assembly. LUMA AND CHROMA (Cb/Cr, 4:2:0). See "Pixel encode" below |
| `h264.intra-pred` | Intra_16x16 luma prediction (§8.3.3): DC/Vertical/Horizontal (modes 0/1/2) only — Plane (mode 3) throws. Intra_Chroma prediction (§8.3.4, 4:2:0 8x8 blocks, `predict-chroma-8x8`): ALL FOUR modes (DC/Horizontal/Vertical/Plane) on decode — see "Chroma decode" below for why Plane is implemented here but not for luma; encode's mode decision only ever selects DC/Horizontal/Vertical (see "Pixel encode") |
| `h264.quant` | (also) `chroma-qp`: QPc derivation from QPy + PPS `chroma_qp_index_offset` (§8.5.8 Table 8-15) — reused unchanged by `h264.encode`'s chroma path |
| `h264.decode` | orchestration: NAL → SPS/PPS/slice header → macroblock loop → (Intra_16x16/Intra_Chroma prediction + CAVLC residual + dequant + inverse transform) → reconstructed luma AND chroma (Cb/Cr) planes. `decode-idr-frame` (single IDR picture, unchanged public API) AND `decode-gop` (a whole IDR+P sequence, new). See "Pixel decode" and "Pixel decode: P-slice (inter)" below for exact scope |
| `h264.interp` | decode: luma quarter-sample (§8.4.2.2.1, 6-tap FIR + averaging) and chroma eighth-sample (§8.4.2.2.2, bilinear) sub-pel motion-compensated interpolation over a picture-boundary-extended plane. Pure functions, no bitstream dependency — `h264.decode/mc-predict` is the only caller. See "Sub-pel motion compensation" below |

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

## Pixel decode: P-slice (inter) (Wave 6, ADR-2607122000 Migration step 7's first increment)

`h264.decode/decode-gop` decodes a whole GOP (one IDR I-frame followed by
zero or more P-frames) in bitstream order — the first time this ecosystem
decodes inter-predicted pixels, not just intra. `decode-idr-frame` (single
IDR picture) is UNCHANGED — it still only ever looks at the first IDR
slice NAL and ignores anything else, so existing callers are unaffected.

**What's implemented:**
- **Two mb_types**: `P_Skip` (§7.3.5, the whole-picture-covering
  `mb_skip_run` bookkeeping in `slice_data()` — no `mb_type`/residual bits
  at all for a skipped macroblock) and `P_L0_16x16` (`mb_type` 0 in a
  P-slice: one 16x16 partition, one motion vector, `ref_idx_l0` implicit 0
  since this repo requires `num_ref_idx_l0_active == 1`). `P_L0_L0_16x8`/
  `P_L0_L0_8x16`/`P_8x8`/`P_8x8ref0` (`mb_type` 1..4, sub-partitioned
  motion) throw explicitly, matching this repo's existing
  throw-on-unsupported-mb_type discipline for `I_NxN`/`I_PCM`. Intra
  macroblocks WITHIN a P-slice (`mb_type` >= 5, `intra_mb_type = mb_type -
  5` per Table 7-13) are also decoded — real encoders do choose intra
  macroblocks inside P-slices, and this repo's existing Intra_16x16 decode
  path (`decode-intra-macroblock-body!`) is reused unchanged, since a
  P-slice's intra macroblock is pixel-identical to an I-slice's.
- **Single reference frame**: a P-slice's motion compensation always
  references the IMMEDIATELY PRECEDING decoded picture (no multi-frame
  DPB/reference-picture-list machinery). `h264.slice/parse-header!` throws
  unless `num_ref_idx_l0_active` resolves to exactly 1, unless
  `ref_pic_list_modification_flag_l0` is 0, and unless PPS
  `weighted_pred?` is false — reference reordering and weighted prediction
  are out of scope.
- **Real median motion-vector prediction** (§8.4.1.3, `mv-predict-16x16`)
  and the P_Skip-specific predictor special case (§8.4.1.1, `p-skip-mv`) —
  implemented per spec (median of left/top/top-right-or-top-left
  neighbors, with the correct "only one ref-idx matches" and "B/C both
  unavailable → copy A" special cases), NOT hardcoded to always return
  `[0 0]` — see `h264.decode/mv-predict-16x16`'s docstring.
- **Real sub-pel motion compensation** (`mc-predict`, delegating to the new
  `h264.interp` namespace — see "Sub-pel motion compensation" below):
  luma quarter-sample interpolation (§8.4.2.2.1, 6-tap FIR
  `(1,-5,20,20,-5,1)` for half-sample positions + rounded averaging for
  quarter-sample positions) and chroma eighth-sample bilinear interpolation
  (§8.4.2.2.2), for ANY motion vector — integer, half-pel, or quarter-pel,
  in any direction. MV=(0,0) is simply the trivial degenerate case (a
  direct sample read), not a separate code path. This replaces the
  earlier MV=(0,0)-only increment's explicit throw-on-nonzero-mv guards in
  `decode-p-skip-macroblock!`/`decode-inter-16x16-macroblock!` — real
  libx264 was found (during the earlier increment's own development) to
  reliably choose non-zero motion vectors for almost any real motion
  content, so this was the natural next increment, not a hypothetical
  concern.
- **Inter luma residual**: unlike Intra_16x16 (which has a separate
  macroblock-level DC/Hadamard block plus 15-coefficient AC blocks), an
  inter (or `I_NxN`) macroblock's 16 luma 4x4 blocks are each a FULL
  16-coefficient regular block (`decode-regular-block!`, all 16 positions
  — including [0,0] — dequantized via the same per-position `ac-qmul`
  formula AC blocks already use). `CodedBlockPatternLuma` gates each of
  the 4 8x8 quadrants independently (1 bit each, `bit-test`) rather than
  being inferred all-or-nothing from `mb_type` the way Intra16x16's is.
- **`coded_block_pattern` (§9.1.2 Table 9-4 ME(v) mapping, Inter column
  only)**: `golomb-to-inter-cbp`, transcribed from FFmpeg's
  `ff_h264_golomb_to_inter_cbp` (`libavcodec/h264data.c`) and verified to
  be a full permutation of 0..47. The Intra_4x4/Intra_8x8 CBP-mapping
  column is NOT implemented, since this repo's decoder never reaches that
  case (`I_NxN` throws before any CBP read would happen).
- **Chroma residual is UNCHANGED** — `decode-chroma-dc!`/
  `decode-chroma-ac-blocks!` are reused as-is for inter macroblocks; the
  chroma DC(2x2 Hadamard)+AC residual structure doesn't depend on the luma
  macroblock type at all.

**What's explicitly NOT implemented** (out of scope, not silently wrong):
CABAC (unchanged from the I-slice-only scope), B-slices, multiple
reference frames / a real DPB, reference-list reordering, weighted
prediction, adaptive (MMCO) reference-picture marking, and
`P_L0_L0_16x8`/`P_L0_L0_8x16`/`P_8x8`/`P_8x8ref0` (sub-partitioned
motion — still limited to ONE 16x16 partition/one motion vector per
macroblock, per the calling task's own scope decision; sub-pel/non-zero
motion compensation IS now implemented for that one partition, see below).
The encode side has NOT been extended for P-slices at all — `h264.encode`
remains IDR-only.

**Validation.** `test/h264/decode_p_slice_test.clj` covers 3 golden
vectors (the ORIGINAL MV=(0,0)-only increment, still passing unchanged
now that MV=(0,0) is just the trivial case of the general sub-pel path),
all bit-exact (no tolerance) against real `ffmpeg 8.1.1`:
`p-skip-flat16.h264` (REAL libx264 output — 2 identical flat-gray frames,
100% P_Skip), and `p-16x16-mb0-realac.h264`/
`p-skip-then-16x16-multimb.h264` (hand-authored using this repo's OWN
already-bit-exact-tested encode-side primitives — `h264.encode` for the
IDR reference frame, `h264.expgolomb`/`h264.cavlc` for a hand-built
spec-valid P-slice NAL — because real encoders essentially never choose
`P_L0_16x16` with a genuinely zero final motion vector AND nonzero
residual for content that also keeps the reference frame's own intra
coding within this repo's Intra_16x16-only scope, see that test file's
own docstring for the full explanation and exactly why this is still a
genuine independent-decoder cross-check, not a self-consistency
tautology: real ffmpeg decodes these exact hand-authored bytes without
having seen or trusted anything about how they were constructed). See
"Sub-pel motion compensation" below for the (real + hand-authored)
non-zero/sub-pel golden vectors.

## Sub-pel motion compensation (`h264.interp`, Migration step 7's sub-pel increment)

`h264.interp` implements real luma quarter-sample (§8.4.2.2.1) and chroma
eighth-sample (§8.4.2.2.2) interpolation, wired into `h264.decode/mc-predict`
so P-slice motion compensation now handles ANY motion vector — this is the
direct successor to the MV=(0,0)-only increment above, and the reason
`decode-p-skip-macroblock!`/`decode-inter-16x16-macroblock!` no longer
throw on a non-zero derived motion vector.

**What's implemented:**
- **Luma quarter-sample interpolation** (`quarter-pel-luma`): the 6-tap FIR
  filter `(1,-5,20,20,-5,1)` for the 3 half-sample positions (`b`
  horizontal, `h` vertical, `j` center), then plain rounded 2-way averaging
  (`(a+b+1)>>1`) for all 12 quarter-sample positions — covers all 16
  `(fx,fy)` fractional combinations (§8.4.2.2.1 Figure 8-4's full naming).
  The center position `j` is the two-pass case that's easy to get subtly
  wrong (see below): it is the vertical 6-tap filter applied to the
  UNROUNDED, UNCLIPPED horizontal 6-tap sums at 6 rows, rounded ONCE at the
  end via `(sum+512)>>10` — NOT independent horizontal-then-vertical
  rounding/clipping.
- **Chroma eighth-sample bilinear interpolation** (`eighth-pel-chroma`):
  weights `A=(8-x)(8-y) B=x(8-y) C=(8-x)y D=xy` over the 2x2 integer-chroma
  neighborhood, `(A·p00+B·p01+C·p10+D·p11+32)>>6` — for ChromaArrayType 1
  (4:2:0), the chroma motion vector in eighth-CHROMA-sample units is
  numerically IDENTICAL to the luma motion vector in quarter-LUMA-sample
  units (chroma sample spacing is exactly 2x luma's, which exactly cancels
  the 2x unit-scale difference — see `h264.interp/mc-chroma-block`'s
  docstring), so no additional per-component MV scaling/derivation step is
  needed beyond reinterpreting the SAME integer mv components.
- **Picture-boundary extension**: reference samples outside the decoded
  picture (needed both for the 6-tap filter's own ±2/+3 reach near picture
  edges and for large motion vectors) are clamped to the nearest boundary
  sample, per §8.4.2.2.1.
- **Cross-checked against FFmpeg's actual reference-decoder source**
  (`libavcodec/h264qpel_template.c`/`h264chroma_template.c`,
  https://github.com/FFmpeg/FFmpeg), matching this repo's existing
  discipline in `h264.quant`/`h264.transform` — NOT reconstructed from
  memory of the spec prose alone. This mattered in practice: the classic
  pitfall here is rounding/clipping the horizontal and vertical passes of
  the center position `j` independently instead of carrying the
  unrounded, unclipped intermediate sums through both passes and rounding
  once — `h264.interp/center-j`'s docstring spells out exactly why the
  two are NOT equivalent (they are, in fact, mathematically identical as
  long as no intermediate rounding is introduced — the bug is introducing
  rounding where the reference implementation doesn't).

**Validation.** `test/h264/decode_p_subpel_test.clj` covers 3 golden
vectors, all bit-exact (no tolerance) against real `ffmpeg 8.1.1`, plus
`test/h264/interp_test.clj` unit-tests the interpolation arithmetic
directly (hand-computed 6-tap/bilinear values, flat-plane invariance under
every one of the 16 luma / 64 chroma fractional combinations, and
picture-boundary clamping) independent of the bitstream machinery:
- **`p-subpel-vertical64.h264`/`p-subpel-horizontal64.h264`** — REAL
  libx264 (Constrained Baseline, CAVLC) 2-frame (IDR + P) streams: a
  64x64 sinusoid varying smoothly along ONE axis only (so the IDR
  reference frame stays within Intra_16x16 DC/Vertical/Horizontal, no
  Intra_4x4/luma-Plane — see `horizontal-multimb64.h264` above for the
  same forcing trick), whose phase shifts by a fractional (1.25px) amount
  per frame — genuine temporal sub-pel motion, not merely re-evaluated
  per-frame content. Real libx264 motion estimation (default `--subme`,
  NOT `--subme 0`/`--preset ultrafast`, which would force full-pel-only
  search) independently finds real quarter-pel motion vectors: `[0 -5]`/
  `[0 -7]`/`[0 -9]` (vertical fixture) and `[-5 0]`/`[-7 0]` (horizontal
  fixture) — every value intentionally NOT a multiple of 4, i.e. genuinely
  fractional. Both fixtures mix real P_Skip (with a non-zero PREDICTED
  motion vector — the first real-encoder evidence this repo's `p-skip-mv`
  predictor path is correct for a non-zero case, not just the MV=(0,0)
  case `p-skip-flat16.h264` already covered) and real P_L0_16x16
  macroblocks.
- **`p-subpel-diagonal32.h264`** — HAND-AUTHORED (this repo's OWN
  `h264.encode` for a REAL, non-flat 2-D gradient IDR reference frame, plus
  a hand-built P-slice NAL via `h264.expgolomb`'s writer — same methodology
  as `p-16x16-mb0-realac.h264` above): real libx264, even with the
  single-axis forcing trick, was not observed to select a genuinely 2-D
  (both fx AND fy fractional) motion vector while also keeping every
  P-frame macroblock free of luma Plane-mode intra coding (a real 2-axis
  test image pushes some P-frame macroblocks to Plane, which this decoder
  doesn't implement for luma). This fixture's 2 macroblocks carry motion
  vectors `[6 10]` (fx=2,fy=2 — the CENTER `j` position, the single
  hardest case) and `[13 7]` (fx=1,fy=3 — an "average of two half-samples"
  position), both with zero residual (isolating pure motion compensation).
  Independent check: real ffmpeg decodes these exact hand-authored bytes
  bit-exact, with no `corrupted macroblock`/`error while decoding`
  messages.

Together these 3 fixtures (plus the 3 from the original MV=(0,0)
increment) exercise every distinct arithmetic branch of
`quarter-pel-luma`'s 16-way fractional dispatch at least once: plain
integer position, the 3 half-sample positions (`b`/`h`/`j`), "average of
integer + half-sample" (from the real single-axis fixtures), and "average
of two half-samples" (from the hand-authored diagonal fixture) — not
exhaustively all 16 `(fx,fy)` combinations individually, but every
DISTINCT code path in the implementation.

## Pixel encode (Wave 5, ADR-2607122000 Migration step 8 + chroma-encode follow-up)

`h264.encode/encode-idr-luma-frame` encodes raw luma AND chroma (Cb/Cr)
planes to a real H.264 Annex B elementary stream: quantization → forward
transform → CAVLC → simplified mode decision, the encode-side counterpart
of `h264.decode`'s Phase 1/"R0.5" decoder. Same non-realtime,
correctness-first reference tier. Initially shipped LUMA ONLY (Migration
step 8); chroma (Cb/Cr) DC+AC encode was added as a follow-up (see below).

**Scope, deliberately narrow (be aware before assuming this encodes
arbitrary images):**
- Single IDR I-slice, `mb_type` fixed to Intra_16x16 (matches `h264.decode`'s
  own decode scope exactly).
- **LUMA AND CHROMA (Cb/Cr), ChromaArrayType 1 (4:2:0) only.** Real chroma
  DC (2x2 Hadamard, `nC==-1` special CAVLC table) and chroma AC (regular
  neighbor-derived 4x4 blocks, QPc-scaled) residual is encoded from the
  actual `:cb`/`:cr` source planes passed to `encode-idr-luma-frame` —
  decoded Cb/Cr planes are a real (lossy, quantization-bounded)
  reconstruction of the source's chroma content. `:cb`/`:cr` are OPTIONAL:
  when omitted, they default to flat 128-valued planes, which (since DC
  prediction against a locally-constant 128 source always gives zero
  residual) preserves the ORIGINAL luma-only encoder's exact call
  shape/behavior for existing callers (`CodedBlockPatternChroma`
  degenerates to 0, no chroma bits beyond that declaration — same as
  before this addition).
- **Chroma intra prediction mode decision: DC/Horizontal/Vertical only**
  (Table 8-5 numbering) — no Plane (mode 3), even though `h264.decode`'s
  chroma path DOES implement Plane (a real encoder, libx264, was observed
  selecting it even for near-flat content — see `h264.intra-pred`'s
  docstring). This simplified reference encoder never emits
  `intra_chroma_pred_mode`=3. `intra_chroma_pred_mode` is a SINGLE syntax
  element shared by both Cb and Cr (§7.3.5.1) — mode decision jointly
  minimizes COMBINED Cb+Cr SAD, not per-component.
- **Simplified mode decision**: for each macroblock, DC/Vertical/Horizontal
  Intra_16x16 prediction (whichever are legal given neighbor availability)
  are each tried and the SAD-minimizing one is chosen. No Plane mode, no
  RDO (rate-distortion optimization).
- **Constant QP across the whole frame** (`mb_qp_delta` always 0). QPc
  (`h264.quant/chroma-qp`, from PPS `chroma_qp_index_offset`) is therefore
  ALSO constant across the whole frame, computed once per frame from the
  constant QPy.
- `CodedBlockPatternLuma` is 0 (DC-only) if every block's AC-quantized
  levels are all zero, else 15 (full AC for all 16 4x4 blocks).
- `CodedBlockPatternChroma` is 0 (no chroma residual at all) if BOTH
  components' quantized DC levels are all zero, 1 (DC only) if some DC
  level is nonzero but no AC level is, else 2 (DC+AC) — mirrors
  `h264.decode/i16x16-mb-info`'s three real values.

**Quantizer design — NOT a memorized textbook MF table.** H.264's
encoder-side forward quantization is explicitly non-normative (only the
decoder's dequant + inverse transform is spec-normative). Rather than trust
a memorized "MF table" (empirically found, via probe scripts during this
task's development, to leave measurably MORE cross-coefficient leakage
—~20%— than this pipeline's own inherent ~2% non-orthogonality), the AC
quantizer (`h264.encode/solve-ac-levels`) solves the EXACT least-squares
inverse of this repo's own already bit-exact-vs-real-ffmpeg-tested
`h264.quant/ac-qmul` + `h264.transform/inverse-4x4` pipeline, via exact
Gauss-Jordan elimination over the probed 16x16 "level→pixel" matrix — this
pipeline is IDENTICAL for luma and chroma AC (only the QP input differs,
QPy vs QPc), so `solve-ac-levels` is reused UNCHANGED for chroma, no
separate chroma AC solver needed. The Intra16x16 luma-DC (Hadamard) path is
handled analogously — `h264.transform/forward-luma-dc-hadamard` is the
exact derived linear inverse of the tested `luma-dc-hadamard`
(`H·Hᵀ=16·I`, verified in `transform_test.clj`), not a hand-derived
formula. The chroma-DC (2x2 Hadamard) path mirrors this EXACTLY:
`h264.transform/forward-chroma-dc-hadamard` is the exact derived linear
inverse of the tested `chroma-dc-hadamard`, derived from a 4x4 Sylvester
Hadamard matrix `H4` (empirically probed against the real, tested
`chroma-dc-hadamard` via unit impulses, NOT hand-derived) satisfying
`H4·H4ᵀ=4·I` (also verified in `transform_test.clj`). `h264.transform/forward-4x4`
(a textbook forward-transform butterfly) exists for API symmetry with
`inverse-4x4` and for DC-term extraction (`forward-4x4(block)[0][0]` = sum
of samples, a property true for any correct forward transform's DC term),
but is NOT used for AC quantization — see its own docstring.

CAVLC encode (`h264.cavlc/encode-residual-block!`) reuses the existing
decode-side VLC tables as reverse lookups, plus a mechanically-derived
inverse of the level_prefix/level_suffix/suffix_length state machine
(`h264.cavlc/encode-level!`) — this same fn was ALREADY generic over the
`:chroma-dc` nC selector from its original (luma-only-caller) Migration
step 8 implementation, so no new CAVLC encode code was needed for chroma;
`h264.encode` just calls it with `:chroma-dc`/chroma AC's regular
neighbor-derived `nc` where the luma-only caller never did. Slice-header
encode is `h264.slice/encode-header!`, symmetric with `parse-header!`.

**Validation.** `test/h264/encode_test.clj` covers:
- Round-trip through this repo's own decoder (flat/gradient, single- and
  multi-macroblock, various QP; luma AND chroma) — bit-exact for flat
  content, small bounded error for real AC content.
- Luma-only fixtures (Migration step 8): `encode-mb0-realac.h264` (single
  macroblock, real nonzero AC residual), `encode-flat32-multimb.h264`
  (32x32, 2x2 macroblocks, flat — multi-macroblock intra-prediction
  chaining without AC residual), and `encode-multimb-realac.h264` (32x32,
  2x2 macroblocks, a real 2-D oscillating luma pattern giving real AC
  residual in every macroblock, real cross-macroblock CAVLC neighbor
  derivation for both AC blocks and the Intra16x16 luma-DC block, and
  Horizontal prediction actually selected for 2 of the 4 macroblocks).
- Chroma fixtures (chroma-encode follow-up), all generated by `h264.encode`
  and independently verified (2026-07, ffmpeg 8.1.1) to be decoded
  **bit-exact by real ffmpeg**, with no `corrupted macroblock`/`error while
  decoding` messages: `encode-mb0-chroma-realac.h264` (single macroblock,
  real DISTINCT per-component Cb/Cr gradients plus real luma AC — Cb varies
  by column, Cr by row, cross-checking Cb/Cr aren't accidentally swapped),
  `encode-multimb-chroma-realac.h264` (32x32, 2x2 macroblocks, flat luma,
  real 2-D oscillating `sin`/`cos` Cb/Cr pattern giving real chroma DC+AC
  residual in every macroblock, real cross-macroblock chroma CAVLC neighbor
  derivation, and 3 distinct Intra_Chroma modes actually selected —
  `[0 1 2 0]`, DC/Horizontal/Vertical/DC — the strongest chroma-encode
  interoperability evidence here), and `encode-multimb-chroma-dconly.h264`
  (32x32, flat-but-nonzero Cb=150/Cr=180 chroma, no AC anywhere —
  exercises `CodedBlockPatternChroma`=1, reproducing the exact uniform
  source value in every pixel).

**Development-time note, kept for traceability.** While building the
ORIGINAL luma-only encoder against the west pin current at the time, two
apparent decode-side gaps surfaced (a macroblock whose neighbor had real AC
residual failed to decode correctly; a single macroblock whose own AC
pushed the neighbor-derived nC to 2 or higher also failed). Both turned out
to be manifestations of the SAME two bugs `h264.decode`/`h264.transform`
had JUST been fixed for upstream in this repo's own history (commit
`14e72ea`, "resolve luma Horizontal multi-macroblock CAVLC desync":
`blk->col-row` was a transposed/wrong block-index mapping, and
`luma-dc-hadamard` was missing an internal transpose — see those two
defs' own docstrings). After syncing to the fixed `h264.decode` and fixing
this encoder's OWN `dc-nc` derivation (`h264.encode/encode-macroblock!`
had the SAME `:dc-nnz`-vs-`:ac-nnz` bug the upstream fix corrected on the
decode side), every previously-failing case — including the original
complex multi-macroblock/real-AC/Horizontal-prediction test image —
decodes bit-exact via real ffmpeg. No known decode-side gap remains open
as of the luma-only encoder's test suite, and the chroma-encode follow-up
above needed no further decode-side fixes (the existing chroma decode path,
already bit-exact-vs-ffmpeg-tested from the earlier chroma-decode work, was
reused unchanged as the mathematical basis for deriving the chroma
encode-side quantizer).

## Test

```sh
clojure -M:test
```
