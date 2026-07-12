(ns h264.transform
  "H.264 baseline-profile integer 4x4 inverse transform (ITU-T H.264 /
   ISO/IEC 14496-10 §8.5.10 \"Transformation process for residual 4x4
   blocks\") + the Intra16x16 luma DC Hadamard transform (§8.5.10, luma DC
   transform coefficients). Implements `codec-primitives.transform/BlockTransform`
   for the regular 4x4 residual block. Decode-only: `forward` throws (this
   repo is a decoder, not an encoder, for the pixel-codec layer).

   The exact butterfly arithmetic (including the `+32`/`>>6` rounding
   constants) is ported from FFmpeg's reference decoder
   (`libavcodec/h264idct_template.c` `ff_h264_idct_add` /
   `ff_h264_luma_dc_dequant_idct`, https://github.com/FFmpeg/FFmpeg) rather
   than re-derived from the spec prose, specifically so this implementation
   is bit-exact against real x264-encoded streams decoded by a real ffmpeg
   (the golden-vector fixtures in `test/h264/decode_test.clj` are decoded
   by ffmpeg itself, so matching its exact integer arithmetic — not just
   the abstract DCT math — is what makes bit-exactness possible).

   IMPORTANT internal convention: `inverse-4x4`'s coefficient input is
   accepted in normal row-major order (index = row*4 + col, matching how
   `h264.cavlc`'s zigzag-unscanned coefficients are naturally laid out) —
   but FFmpeg's `ff_h264_idct_add` C code indexes its `block[]` array in a
   column-major sense internally. This was NOT obvious from reading the C
   source alone; it was discovered empirically (see ADR/README) by decoding
   a real x264 gradient-image golden vector and observing the reconstructed
   picture was transposed until the coefficient matrix was transposed
   before running the butterfly. `inverse-4x4` transposes internally so
   callers can pass/receive plain row-major 4x4 grids throughout.

   New implementation as part of the kotoba-lang reverse-domain
   media/graphics standards-substrate split (com-junkawasaki/root)."
  (:require [codec-primitives.transform :as cp-transform]))

(defn- transpose16
  "Transpose a 16-element row-major (idx = row*4+col) coefficient vector."
  [v]
  (mapv (fn [idx] (let [row (quot idx 4) col (mod idx 4)]
                    (nth v (+ (* col 4) row))))
        (range 16)))

(defn inverse-4x4
  "Inverse 4x4 transform of a already-dequantized 16-element row-major
   (idx = row*4+col) coefficient vector, per H.264 §8.5.10 (two-stage
   integer butterfly + `+32` rounding bias on the DC term + final `>>6`).
   Returns a 4x4 residual grid (vector of 4 row vectors) — NOT yet added to
   a predictor and NOT yet clipped (that's the caller's job, see
   `h264.decode`)."
  [coeffs]
  (let [block (-> (transpose16 coeffs)
                   (update 0 + 32))
        b (fn [i] (nth block i))
        ;; stage 1: for each i in 0..3, transform block[i], block[i+4],
        ;; block[i+8], block[i+12] (a "column" in this internal layout)
        tmp (vec (repeat 16 0))
        tmp (reduce
             (fn [acc i]
               (let [b0 (b i) b1 (b (+ i 4)) b2 (b (+ i 8)) b3 (b (+ i 12))
                     z0 (+ b0 b2)
                     z1 (- b0 b2)
                     z2 (- (bit-shift-right b1 1) b3)
                     z3 (+ b1 (bit-shift-right b3 1))]
                 (-> acc
                     (assoc i (+ z0 z3))
                     (assoc (+ i 4) (+ z1 z2))
                     (assoc (+ i 8) (- z1 z2))
                     (assoc (+ i 12) (- z0 z3)))))
             tmp (range 4))
        t (fn [i] (nth tmp i))
        out (vec (repeat 16 0))
        out (reduce
             (fn [acc i]
               (let [t0 (t (+ (* 4 i) 0)) t1 (t (+ (* 4 i) 1))
                     t2 (t (+ (* 4 i) 2)) t3 (t (+ (* 4 i) 3))
                     z0 (+ t0 t2)
                     z1 (- t0 t2)
                     z2 (- (bit-shift-right t1 1) t3)
                     z3 (+ t1 (bit-shift-right t3 1))]
                 (-> acc
                     (assoc i (bit-shift-right (+ z0 z3) 6))
                     (assoc (+ i 4) (bit-shift-right (+ z1 z2) 6))
                     (assoc (+ i 8) (bit-shift-right (- z1 z2) 6))
                     (assoc (+ i 12) (bit-shift-right (- z0 z3) 6)))))
             out (range 4))]
    (vec (for [row (range 4)] (vec (for [col (range 4)] (nth out (+ (* row 4) col))))))))

(def luma-dc-x-offset
  "Column-group base offsets into the flattened 16-blocks*16-samples buffer
   used by `luma-dc-hadamard`, ported from
   `ff_h264_luma_dc_dequant_idct`'s `x_offset` table."
  [0 32 128 160])

(defn luma-dc-hadamard
  "Inverse Hadamard transform + dequant of the Intra16x16 luma DC block
   (H.264 §8.5.10). `dc-raster` is the 16 RAW (NOT yet dequantized — the
   DC block's CAVLC decode in `h264.cavlc` does not apply `qmul`, matching
   ffmpeg's split) coefficients, row-major (idx=row*4+col) after zigzag
   unscan. `qmul` is `h264.quant/dc-qmul`.

   Returns a 16-element vector indexed by the SAME luma 4x4 block index
   (0..15) used throughout this namespace/`h264.decode`
   (`h264.decode/blk->col-row`) — i.e. `(nth result blk-idx)` is the
   transform-domain DC coefficient to place at position 0 of that block's
   own 4x4 coefficient array before calling `inverse-4x4` on it. Ported
   1:1 from `ff_h264_luma_dc_dequant_idct` (including its exact `x_offset`
   index arithmetic) since that arithmetic IS the block-index mapping —
   re-deriving it independently risks a subtly different (but
   self-consistent-looking) mapping that silently mismatches ffmpeg's."
  [dc-raster qmul]
  (let [in (fn [i] (nth dc-raster i))
        tmp (vec (repeat 16 0))
        tmp (reduce
             (fn [acc i]
               (let [z0 (+ (in (+ (* 4 i) 0)) (in (+ (* 4 i) 1)))
                     z1 (- (in (+ (* 4 i) 0)) (in (+ (* 4 i) 1)))
                     z2 (- (in (+ (* 4 i) 2)) (in (+ (* 4 i) 3)))
                     z3 (+ (in (+ (* 4 i) 2)) (in (+ (* 4 i) 3)))]
                 (-> acc
                     (assoc (+ (* 4 i) 0) (+ z0 z3))
                     (assoc (+ (* 4 i) 1) (- z0 z3))
                     (assoc (+ (* 4 i) 2) (- z1 z2))
                     (assoc (+ (* 4 i) 3) (+ z1 z2)))))
             tmp (range 4))
        t (fn [i] (nth tmp i))
        stride 16
        out (vec (repeat 256 0))
        out (reduce
             (fn [acc i]
               (let [offset (nth luma-dc-x-offset i)
                     z0 (+ (t (+ (* 4 0) i)) (t (+ (* 4 2) i)))
                     z1 (- (t (+ (* 4 0) i)) (t (+ (* 4 2) i)))
                     z2 (- (t (+ (* 4 1) i)) (t (+ (* 4 3) i)))
                     z3 (+ (t (+ (* 4 1) i)) (t (+ (* 4 3) i)))
                     sc (fn [v] (bit-shift-right (+ (* v qmul) 128) 8))]
                 (-> acc
                     (assoc (+ (* stride 0) offset) (sc (+ z0 z3)))
                     (assoc (+ (* stride 1) offset) (sc (+ z1 z2)))
                     (assoc (+ (* stride 4) offset) (sc (- z1 z2)))
                     (assoc (+ (* stride 5) offset) (sc (- z0 z3))))))
             out (range 4))]
    (mapv #(nth out (* % 16)) (range 16))))

(defrecord H264BlockTransform []
  cp-transform/BlockTransform
  (forward [_ _block]
    (throw (ex-info "h264.transform: forward (encode) not implemented — decode-only scope" {})))
  (inverse [_ coeffs] (inverse-4x4 coeffs)))

(def block-transform
  "Shared `codec-primitives.transform/BlockTransform` instance."
  (->H264BlockTransform))
