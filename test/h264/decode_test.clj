(ns h264.decode-test
  "Golden-vector tests for `h264.decode` (ADR-2607122000 Phase 1 / \"R0.5\").

   Both fixtures are REAL libx264 (Constrained Baseline profile, CAVLC)
   Annex B elementary streams, generated as follows (also see README):

   `flat16-dc-only.h264` — a single 16x16 flat-gray macroblock:
     ffmpeg -f lavfi -i color=c=0x808080:s=16x16 -frames:v 1 -update 1 test16.png
     ffmpeg -i test16.png -c:v libx264 -profile:v baseline \\
       -x264opts keyint=1:qp=26 -frames:v 1 -pix_fmt yuv420p flat16-dc-only.h264
   This encodes as a single Intra_16x16 macroblock, DC prediction mode,
   CodedBlockPatternLuma=0 (no AC residual — only the luma DC/Hadamard
   coefficient is coded), exercising: SPS/PPS/slice-header parsing,
   mb_type→Intra_16x16 mapping, CAVLC decode of the luma DC block (nC=0,
   no neighbor MBs), the DC Hadamard transform + dequant, and DC-mode
   16x16 prediction with unavailable neighbors (defaults to 128).

   `gradient16-ac.h264` — a single 16x16 macroblock with a horizontal
   luma gradient (100..140), forced to Intra_16x16 (`--partitions none`)
   at a QP where libx264 codes real AC residual for all four luma 8x8
   groups (CodedBlockPatternLuma=15):
     ffmpeg -i softgrad16.png -pix_fmt yuv420p soft16.y4m
     x264 --input-res 16x16 --fps 25 -o gradient16-ac.h264 --qp 27 \\
       --keyint 1 --partitions none --profile baseline soft16.y4m
   This additionally exercises: real CAVLC coeff_token/level/total_zeros/
   run_before decode with nonzero levels and nonzero total_zeros/run_before,
   within-macroblock CAVLC neighbor (nC) derivation across the 16 luma 4x4
   sub-blocks, per-position AC dequantization, and the full 4x4 inverse
   transform (`h264.transform/inverse-4x4`) combined with the DC term.

   Both fixtures' reference output (`*.ref.yuv`) was produced by decoding
   the SAME `.h264` file with a real ffmpeg:
     ffmpeg -i <fixture>.h264 -pix_fmt yuv420p <fixture>.ref.yuv
   — i.e. the comparison is against ffmpeg's OWN reconstructed pixels (not
   the pre-encode source image, since lossy encoding changes pixel values).
   Bit-exact — no tolerance/epsilon is used anywhere in these assertions —
   for luma AND chroma (Cb/Cr, bytes 256..320 / 320..384 of the yuv420p
   file for these 16x16 fixtures; both single-MB streams happen to carry
   chroma DC-only residual, `CodedBlockPatternChroma` 0 or 1 — real chroma
   AC residual (`CodedBlockPatternChroma` == 2) and multi-macroblock
   pictures are validated separately below,
   `chroma-multimb32-golden-vector`).

   `chroma-multimb32.h264` (32x32, 2x2 macroblocks) — see that fixture's
   own docstring in `chroma-multimb32-golden-vector` below for how it was
   generated and what it exercises (real chroma AC + multi-macroblock
   cross-MB neighbor derivation + multiple distinct luma/chroma prediction
   modes actually selected by a real encoder)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [h264.decode :as decode]))

(defn- rd [p] (mapv #(bit-and (int %) 0xff)
                    (with-open [in (io/input-stream (io/resource p))] (.readAllBytes in))))

(deftest flat16-dc-only-golden-vector
  (let [bytes (rd "h264/fixtures/flat16-dc-only.h264")
        result (decode/decode-idr-frame bytes)
        ref (rd "h264/fixtures/flat16-dc-only.ref.yuv")
        luma-ref (vec (take 256 ref))
        cb-ref (vec (subvec (vec ref) 256 320))
        cr-ref (vec (subvec (vec ref) 320 384))]
    (testing "dimensions from SPS"
      (is (= 16 (:width result)))
      (is (= 16 (:height result))))
    (testing "reconstructed luma plane is bit-exact vs. real ffmpeg decode"
      (is (= luma-ref (:luma result))))
    (testing "reconstructed Cb/Cr planes are bit-exact vs. real ffmpeg decode"
      (is (= cb-ref (:cb result)))
      (is (= cr-ref (:cr result))))
    (testing "the flat source produces a uniform reconstructed value (126, not the pre-encode 128 — DC correction applied)"
      (is (= 1 (count (distinct (:luma result))))))))

(deftest gradient16-ac-golden-vector
  (let [bytes (rd "h264/fixtures/gradient16-ac.h264")
        result (decode/decode-idr-frame bytes)
        ref (rd "h264/fixtures/gradient16-ac.ref.yuv")
        luma-ref (vec (take 256 ref))
        cb-ref (vec (subvec (vec ref) 256 320))
        cr-ref (vec (subvec (vec ref) 320 384))]
    (testing "dimensions from SPS"
      (is (= 16 (:width result)))
      (is (= 16 (:height result))))
    (testing "reconstructed luma plane is bit-exact vs. real ffmpeg decode (exercises real AC residual + full 4x4 IDCT)"
      (is (= luma-ref (:luma result))))
    (testing "reconstructed Cb/Cr planes are bit-exact vs. real ffmpeg decode"
      (is (= cb-ref (:cb result)))
      (is (= cr-ref (:cr result))))
    (testing "the gradient is non-uniform (sanity: AC residual actually changed pixels, this isn't accidentally the DC-only path)"
      (is (> (count (distinct (:luma result))) 1)))))

(deftest chroma-multimb32-golden-vector
  (let [bytes (rd "h264/fixtures/chroma-multimb32.h264")]
    (testing "chroma-multimb32.h264 — 32x32 (2x2 macroblocks), REAL libx264
     (Constrained Baseline, CAVLC) Annex B stream, generated:
       ffmpeg -f lavfi -i \"color=size=32x32:c=black\" \\
         -vf \"geq=lum=128:cb='128+40*sin(X/2)*cos(Y/3)':cr='128+40*cos(X/3)*sin(Y/2)'\" \\
         -frames:v 1 -update 1 chromanoise32.png
       ffmpeg -i chromanoise32.png -pix_fmt yuv420p chromanoise32.y4m
       x264 --input-res 32x32 --fps 25 -o chroma-multimb32.h264 --qp 20 \\
         --keyint 1 --partitions none --profile baseline chromanoise32.y4m
     Luma is flat (uniform mid-gray) — deliberately, so libx264 never has
     an RD-cost reason to spend bits on luma residual/small-block intra,
     keeping all 4 macroblocks Intra_16x16 (real encoder log:
     `mb I  I16..4: 100.0%  0.0%  0.0%`) — while Cb/Cr carry a genuine 2-D
     oscillating pattern (`sin`/`cos` of position) so CHROMA gets real,
     non-trivial per-macroblock AC residual (`coded y,uvDC,uvAC intra:
     0.0% 100.0% 100.0%` — zero luma residual, full chroma DC AND AC).
     This is this repo's ONLY multi-macroblock chroma golden vector, and
     validates real cross-macroblock CAVLC neighbor (nC) derivation for
     chroma AC blocks (four separate macroblocks' worth, not just the
     within-macroblock case the single-MB fixtures above exercise) —
     catching a genuine bug during development where chroma AC decoded
     bit-exact for an isolated macroblock but desynced the bit reader on
     the SECOND macroblock (see `h264.decode/chroma-blk->col-row`'s
     docstring: chroma 4x4 sub-blocks use RASTER order, not luma's Z-order
     — an easy, plausible-looking wrong assumption)."
      (let [result (decode/decode-idr-frame bytes)
            ref (rd "h264/fixtures/chroma-multimb32.ref.yuv")
            luma-ref (vec (take 1024 ref))
            cb-ref (vec (subvec (vec ref) 1024 1280))
            cr-ref (vec (subvec (vec ref) 1280 1536))]
        (testing "dimensions from SPS"
          (is (= 32 (:width result)))
          (is (= 32 (:height result))))
        (testing "reconstructed luma plane is bit-exact vs. real ffmpeg decode"
          (is (= luma-ref (:luma result))))
        (testing "reconstructed Cb/Cr planes are bit-exact vs. real ffmpeg decode (real cross-macroblock chroma AC neighbor derivation)"
          (is (= cb-ref (:cb result)))
          (is (= cr-ref (:cr result))))
        (testing "a real encoder actually selected more than just DC prediction across these 4 macroblocks — not a contrived/forced mode"
          (is (= [2 2 0 0] (:mb-pred-modes result))
              "luma: DC (mb0/mb1, no top neighbor) then Vertical (mb2/mb3, real top-neighbor-derived prediction)")
          (is (= [0 1 2 0] (:mb-intra-chroma-pred-modes result))
              "chroma: DC, Horizontal, Vertical, DC — all three implemented Intra_Chroma modes actually chosen by x264"))))))

(deftest unsupported-mb-type-throws
  (testing "mb_type 0 (I_NxN / Intra_4x4) is out of scope and throws rather than mis-decoding"
    (is (thrown? clojure.lang.ExceptionInfo (#'decode/i16x16-mb-info 0))))
  (testing "mb_type 25 (I_PCM) is out of scope and throws"
    (is (thrown? clojure.lang.ExceptionInfo (#'decode/i16x16-mb-info 25)))))
