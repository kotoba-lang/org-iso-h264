(ns h264.decode-cabac-test
  "Golden-vector tests for CABAC (main/high-profile entropy coding) decode
   (`h264.cabac`, wired into `h264.decode` — ADR-2607122000 CABAC increment).

   **Scope validated here: I-slice / Intra_16x16, SINGLE MACROBLOCK ONLY**
   (see `h264.cabac`'s namespace docstring for the full scope statement —
   CABAC + P-slice/inter, CABAC + I_NxN/I_PCM, and CABAC encode are all out
   of scope). Both fixtures are REAL libx264 (Main profile, CABAC — verified
   via `entropy_coding_mode_flag=1` in the actual PPS bits, not just the
   SPS `profile_idc`) Annex B elementary streams, generated exactly like
   this repo's own CAVLC counterparts (`flat16-dc-only.h264`/
   `gradient16-ac.h264` in `decode_test.clj`) but with `-profile:v main`
   (ffmpeg) / `--profile main` (x264) instead of baseline:

   `flat16-dc-only-cabac.h264`:
     ffmpeg -f lavfi -i color=c=0x808080:s=16x16 -frames:v 1 -update 1 test16.png
     ffmpeg -i test16.png -c:v libx264 -profile:v main \\
       -x264opts keyint=1:qp=26 -frames:v 1 -pix_fmt yuv420p flat16-dc-only-cabac.h264
   A single flat 16x16 macroblock, DC prediction, `CodedBlockPatternLuma=0`
   (no AC residual — only the luma DC/Hadamard coefficient, itself zero for
   flat content, is coded). Exercises: the CABAC arithmetic decoding engine
   init/byte-alignment (`h264.cabac/byte-align!`/`init-engine!`), context
   model init from SliceQPY, `mb_type` (I-slice) binarization/context
   (unavailable-neighbor case), `intra_chroma_pred_mode`, `mb_qp_delta`, and
   the `coded_block_flag`=0 (empty-block) fast path for the DC block AND all
   16 AC blocks (since `CodedBlockPatternLuma=0` means the AC blocks aren't
   read at all, matching the CAVLC path's own `cbp-luma` gating) plus both
   chroma DC blocks.

   `gradient16-ac-cabac.h264`:
     ffmpeg -f lavfi -i \"color=size=16x16:c=black\" \\
       -vf \"geq=lum='100+3*X':cb=128:cr=128\" -frames:v 1 -update 1 softgrad16b.png
     ffmpeg -i softgrad16b.png -pix_fmt yuv420p soft16b.y4m
     x264 --input-res 16x16 --fps 25 -o gradient16-ac-cabac.h264 --qp 33 \\
       --keyint 1 --partitions none --no-deblock --profile main soft16b.y4m
   A single 16x16 macroblock with a real horizontal luma gradient, forced to
   Intra_16x16 (`--partitions none`) at a QP where libx264 codes real AC
   residual (`CodedBlockPatternLuma!=0`, real ffmpeg log:
   `coded y,uvDC,uvAC intra: 100.0% 0.0% 0.0%`). Exercises: real
   `coded_block_flag`=1 decode, `significant_coeff_flag`/
   `last_significant_coeff_flag` map decode, and `coeff_abs_level_minus1`
   (+bypass sign bit) for a genuinely nonzero AC coefficient — i.e. the full
   CABAC residual decode path (`h264.cabac/residual-block!`), not just the
   empty-block fast path the flat fixture exercises.

   `--no-deblock` (`disable_deblocking_filter_idc=1`, spec-mandated,
   properly signaled in the slice header) is REQUIRED for this fixture: an
   earlier attempt without it decoded successfully but was off by ±1 at
   every internal 4x4 transform-block boundary — a real libx264 in-loop
   deblocking-filter effect this repo's decoder (CABAC OR CAVLC alike) does
   not implement, same limitation the existing CAVLC
   `horizontal-multimb64.h264` fixture documents (see `decode_test.clj`).
   This was root-caused during this increment's own development by manually
   verifying the pre-deblock reconstruction (from this decoder's own decoded
   coefficients, fed back through the already-tested `h264.transform`
   pipeline) matched ffmpeg's OWN output only once `--no-deblock` was added
   — i.e. confirmed to be a deblocking difference, not a CABAC decode bug.

   **What this does NOT yet validate (known gap, see `h264.cabac`'s
   namespace docstring and the ADR/PR description for this increment):
   multi-macroblock CABAC pictures, and any 4x4 residual block with MORE
   THAN ONE significant coefficient, are not yet confirmed bit-exact** — a
   real multi-macroblock fixture (`horizontal-multimb64`-style, 64x64/16
   macroblocks) decodes with plausible-looking mb_type/coded_block_pattern/
   prediction-mode decisions (independently cross-checked to MATCH an
   independently-CAVLC-encoded version of the exact same source image) and
   correct overall bit-stream framing (`end_of_slice_flag` fires exactly at
   the picture's last macroblock, and an ALL-ZERO-residual multi-macroblock
   chroma fixture's `intra_chroma_pred_mode` decisions also match a CAVLC
   reference bit-for-bit), but the RECONSTRUCTED PIXELS show small (±1..3,
   occasionally more) discrepancies specifically in 4x4 blocks whose
   `coded_block_flag`=1 residual has 3 or more significant coefficients —
   both the single-coefficient case (this file's own
   `gradient16-ac-cabac-golden-vector`) and the arithmetic-decoding-engine/
   binarization control flow for multi-coefficient blocks (verified via
   scripted-bit unit tests of `h264.cabac/read-coeff-levels!`/
   `decode-ueg-level!` in isolation, matching hand-derived expected
   sequences exactly) check out individually, so the remaining discrepancy
   is narrowly scoped but NOT YET root-caused. See the ADR/PR writeup for
   this increment for the full investigation trail (tables/engine/
   binarization cross-checked against two independent sources; dequant/
   transform sharing with the already-tested CAVLC path ruled out via a
   larger-magnitude CAVLC fixture that already passes) and next steps
   (obtain a verbose per-bin CABAC reference trace, e.g. via the JM
   reference software or a custom-instrumented ffmpeg build, to directly
   diff against this namespace's own bin sequence on a real multi-
   coefficient block)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [h264.decode :as decode]))

(defn- rd [p] (mapv #(bit-and (int %) 0xff)
                    (with-open [in (io/input-stream (io/resource p))] (.readAllBytes in))))

(deftest flat16-dc-only-cabac-golden-vector
  (let [bytes (rd "h264/fixtures/flat16-dc-only-cabac.h264")
        result (decode/decode-idr-frame bytes)
        ref (rd "h264/fixtures/flat16-dc-only-cabac.ref.yuv")
        luma-ref (vec (take 256 ref))
        cb-ref (vec (subvec (vec ref) 256 320))
        cr-ref (vec (subvec (vec ref) 320 384))]
    (testing "dimensions from SPS"
      (is (= 16 (:width result)))
      (is (= 16 (:height result))))
    (testing "reconstructed luma plane is bit-exact vs. real ffmpeg decode (real Main-profile CABAC stream)"
      (is (= luma-ref (:luma result))))
    (testing "reconstructed Cb/Cr planes are bit-exact vs. real ffmpeg decode"
      (is (= cb-ref (:cb result)))
      (is (= cr-ref (:cr result))))
    (testing "the flat source produces a uniform reconstructed value (DC correction applied, same as the CAVLC counterpart)"
      (is (= 1 (count (distinct (:luma result))))))))

(deftest gradient16-ac-cabac-golden-vector
  (let [bytes (rd "h264/fixtures/gradient16-ac-cabac.h264")
        result (decode/decode-idr-frame bytes)
        ref (rd "h264/fixtures/gradient16-ac-cabac.ref.yuv")
        luma-ref (vec (take 256 ref))
        cb-ref (vec (subvec (vec ref) 256 320))
        cr-ref (vec (subvec (vec ref) 320 384))]
    (testing "dimensions from SPS"
      (is (= 16 (:width result)))
      (is (= 16 (:height result))))
    (testing "reconstructed luma plane is bit-exact vs. real ffmpeg decode (real CABAC significant_coeff_flag/coeff_abs_level_minus1 decode)"
      (is (= luma-ref (:luma result))))
    (testing "reconstructed Cb/Cr planes are bit-exact vs. real ffmpeg decode"
      (is (= cb-ref (:cb result)))
      (is (= cr-ref (:cr result))))
    (testing "the gradient is non-uniform (sanity: AC residual actually changed pixels, this isn't accidentally the DC-only path)"
      (is (> (count (distinct (:luma result))) 1)))))
