(ns h264.interp
  "H.264 sub-pixel motion-compensated interpolation:
   - Luma quarter-sample interpolation (ITU-T H.264 / ISO/IEC 14496-10
     §8.4.2.2.1 \"Luma sample interpolation process\"): a 6-tap FIR filter
     `(1,-5,20,20,-5,1)` for half-sample positions, then simple rounded
     averaging for quarter-sample positions.
   - Chroma sample interpolation (§8.4.2.2.2 \"Chroma sample interpolation
     process\", ChromaArrayType 1 / 4:2:0 only, matching this repo's whole
     chroma scope elsewhere): bilinear interpolation at 1/8-chroma-sample
     precision, since a chroma motion vector derived from a luma one
     (§8.4.1.4, ChromaArrayType 1: `mvCLX == mvLX`, see `mc-chroma-block`'s
     docstring for why no additional scaling is needed) lands on an
     eighth-chroma-sample grid.

   Pure functions over a flat row-major picture-boundary-extended plane —
   no bitstream/CAVLC dependency, independently callable/testable from
   `h264.decode`'s macroblock loop (which is the only caller).

   **Arithmetic cross-checked against FFmpeg's actual reference-decoder
   source** (`libavcodec/h264qpel_template.c` for luma six-tap + averaging,
   `libavcodec/h264chroma_template.c` for chroma bilinear —
   https://github.com/FFmpeg/FFmpeg, matching this repo's existing
   discipline in `h264.quant`/`h264.transform` of cross-checking dequant/
   transform arithmetic against the same reference source), NOT
   reconstructed from memory of the spec prose alone:

   - The 6-tap filter sum for a half-sample position (e.g. `b`, horizontal,
     between full-sample `G` and `H`) is `(G+H)*20 - (F+I)*5 + (E+J)` where
     E,F,G,H,I,J are the 6 consecutive full-samples centered on the
     G/H pair (2 samples further out on the F side, 3 further out on the I
     side) — rounded via `Clip1( (sum+16) >> 5 )`.
   - The center half-sample `j` (both directions half-sample) is NOT simply
     a 2-D convolution rounded once — per FFmpeg's own two-pass
     `h264_qpel_hv_lowpass` (horizontal 6-tap pass producing UNROUNDED,
     UNCLIPPED 16-bit-range intermediate sums at 6 rows, then a vertical
     6-tap pass over those same intermediate sums, rounded ONCE at the end
     via `Clip1( (sum+512) >> 10 )`), this ns computes `j` as a direct
     algebraic reassociation of that exact same two-pass integer sum
     (`center-j` below) — mathematically identical (finite integer
     addition/multiplication is associative/commutative; there is no
     intermediate rounding to reorder around), just without needing an
     explicit intermediate buffer since `sample` gives O(1) random access
     into the whole reference plane already. Getting this order wrong
     (e.g. clipping/rounding the horizontal pass before the vertical pass)
     would silently produce a plausible-looking but WRONG value — this is
     exactly the kind of \"intermediate bit-width/clipping-timing\" mistake
     the calling task's own instructions flagged as the well-known pitfall
     here, hence cross-checking against FFmpeg's source rather than
     reimplementing from a recalled formula.
   - Quarter-sample positions (`a,c,d,e,f,g,i,k,n,p,q,r`, §8.4.2.2.1 Figure
     8-4's naming) are ALL a plain rounded average of exactly two of
     {the integer sample, a half-sample} — `avg1` — never a 3- or 4-way
     average, never independently filtered.
   - Chroma bilinear interpolation (§8.4.2.2.2): weights `A=(8-x)(8-y)
     B=x(8-y) C=(8-x)y D=xy` (x,y = eighth-chroma-sample fractional parts,
     0..7) over the 2x2 integer-chroma-sample neighborhood, rounded via
     `(A*p00+B*p01+C*p10+D*p11+32) >> 6` — provably always in range 0..255
     given inputs in that range (weights sum to 64), matching why FFmpeg's
     own `op_put` for chroma has no explicit clip, though this ns still
     applies `clip8` defensively (spec's own `Clip1C` notation does, even
     though it's a mathematical no-op here).
   - Picture-boundary extension: reference samples at coordinates outside
     the decoded picture are substituted by the nearest picture-boundary
     sample (§8.4.2.2.1's boundary-sample derivation process) — implemented
     by `sample`'s coordinate clamping, needed whenever a motion vector (or
     the 6-tap filter's own +/-2/+3 reach) points outside the picture."
  )

(defn- clip8 [v] (max 0 (min 255 v)))

(defn- sample
  "Read one full-pel sample from flat row-major `plane` (dimensions `w`x`h`)
   at (x,y), clamping out-of-range coordinates to the nearest picture-
   boundary sample (see namespace docstring)."
  [plane w h x y]
  (let [cx (max 0 (min (dec w) x))
        cy (max 0 (min (dec h) y))]
    (nth plane (+ (* cy w) cx))))

(defn- six-tap-h
  "Unrounded, unclipped horizontal 6-tap FIR sum (§8.4.2.2.1) centered
   between (x,y) and (x+1,y): (G+H)*20 - (F+I)*5 + (E+J)."
  [plane w h x y]
  (+ (* 20 (+ (sample plane w h x y) (sample plane w h (inc x) y)))
     (* -5 (+ (sample plane w h (dec x) y) (sample plane w h (+ x 2) y)))
     (sample plane w h (- x 2) y)
     (sample plane w h (+ x 3) y)))

(defn- six-tap-v
  "Same 6-tap FIR sum, vertical direction, centered between (x,y) and
   (x,y+1)."
  [plane w h x y]
  (+ (* 20 (+ (sample plane w h x y) (sample plane w h x (inc y))))
     (* -5 (+ (sample plane w h x (dec y)) (sample plane w h x (+ y 2))))
     (sample plane w h x (- y 2))
     (sample plane w h x (+ y 3))))

(defn- half-h
  "Half-sample horizontal position ('b'/'s' in Figure 8-4, depending on
   which row `y` is): `Clip1( (six-tap-h + 16) >> 5 )`."
  [plane w h x y]
  (clip8 (bit-shift-right (+ (six-tap-h plane w h x y) 16) 5)))

(defn- half-v
  "Half-sample vertical position ('h'/'m' in Figure 8-4, depending on which
   column `x` is): `Clip1( (six-tap-v + 16) >> 5 )`."
  [plane w h x y]
  (clip8 (bit-shift-right (+ (six-tap-v plane w h x y) 16) 5)))

(defn- center-j
  "Center half-sample position ('j' in Figure 8-4): the vertical 6-tap
   filter applied to the UNROUNDED horizontal 6-tap sums at the 6 rows
   y-2..y+3 (see namespace docstring for why this is exactly FFmpeg's
   two-pass `hv_lowpass`, not an approximation), rounded once via
   `Clip1( (sum + 512) >> 10 )`."
  [plane w h x y]
  (let [hs (fn [dy] (six-tap-h plane w h x (+ y dy)))]
    (clip8 (bit-shift-right
            (+ (* 20 (+ (hs 0) (hs 1)))
               (* -5 (+ (hs -1) (hs 2)))
               (hs -2) (hs 3)
               512)
            10))))

(defn- avg1
  "Rounded average of two already-clipped (0..255) sample values —
   `(a+b+1)>>1` — used for every quarter-sample position (§8.4.2.2.1: each
   quarter-sample is a plain 2-way average of an integer- or half-sample
   neighbor pair, never independently filtered)."
  [a b]
  (bit-shift-right (+ a b 1) 1))

(defn quarter-pel-luma
  "One interpolated luma sample (§8.4.2.2.1 Figure 8-4) at full-pel base
   position (x,y) with quarter-sample offset (fx,fy), each 0..3 (0 = the
   integer sample itself). Covers all 16 (fx,fy) combinations — see
   namespace docstring for the FFmpeg cross-check this table is built from."
  [plane w h x y fx fy]
  (cond
    (and (zero? fx) (zero? fy)) (sample plane w h x y)
    (and (= fx 2) (zero? fy)) (half-h plane w h x y)
    (and (zero? fx) (= fy 2)) (half-v plane w h x y)
    (and (= fx 2) (= fy 2)) (center-j plane w h x y)
    (and (= fx 1) (zero? fy)) (avg1 (sample plane w h x y) (half-h plane w h x y))
    (and (= fx 3) (zero? fy)) (avg1 (sample plane w h (inc x) y) (half-h plane w h x y))
    (and (zero? fx) (= fy 1)) (avg1 (sample plane w h x y) (half-v plane w h x y))
    (and (zero? fx) (= fy 3)) (avg1 (sample plane w h x (inc y)) (half-v plane w h x y))
    (and (= fx 1) (= fy 2)) (avg1 (half-v plane w h x y) (center-j plane w h x y))
    (and (= fx 3) (= fy 2)) (avg1 (center-j plane w h x y) (half-v plane w h (inc x) y))
    (and (= fx 2) (= fy 1)) (avg1 (half-h plane w h x y) (center-j plane w h x y))
    (and (= fx 2) (= fy 3)) (avg1 (center-j plane w h x y) (half-h plane w h x (inc y)))
    (and (= fx 1) (= fy 1)) (avg1 (half-h plane w h x y) (half-v plane w h x y))
    (and (= fx 3) (= fy 1)) (avg1 (half-h plane w h x y) (half-v plane w h (inc x) y))
    (and (= fx 1) (= fy 3)) (avg1 (half-v plane w h x y) (half-h plane w h x (inc y)))
    (and (= fx 3) (= fy 3)) (avg1 (half-v plane w h (inc x) y) (half-h plane w h x (inc y)))
    :else (throw (ex-info "h264.interp: invalid luma quarter-pel fraction (must be 0..3)" {:fx fx :fy fy}))))

(defn eighth-pel-chroma
  "One interpolated chroma sample (§8.4.2.2.2) — bilinear, NOT 6-tap — at
   full-pel base position (x,y) with eighth-sample offset (fx,fy), each
   0..7 (0 = the integer sample itself)."
  [plane w h x y fx fy]
  (let [a (* (- 8 fx) (- 8 fy))
        b (* fx (- 8 fy))
        c (* (- 8 fx) fy)
        d (* fx fy)
        p00 (sample plane w h x y)
        p01 (sample plane w h (inc x) y)
        p10 (sample plane w h x (inc y))
        p11 (sample plane w h (inc x) (inc y))]
    (clip8 (bit-shift-right (+ (* a p00) (* b p01) (* c p10) (* d p11) 32) 6))))

(defn mc-luma-block
  "Motion-compensated `size`x`size` luma block from reference `plane`
   (dimensions `w`x`h`) at zero-motion top-left picture position (x0,y0)
   with motion vector `[mvx mvy]` (quarter-luma-sample units, §8.4.1).
   Returns a `size`x`size` row-vector grid (row-major, `[row][col]`).

   Full-pel/fractional split follows the spec's `>>`/`&` convention exactly
   (arithmetic right shift = floor division, matching two's-complement
   semantics both C and the JVM already use — `mvx & 3` is always 0..3
   even for negative `mvx`, no separate negative-number handling needed)."
  [plane w h x0 y0 [mvx mvy] size]
  (let [ix (bit-shift-right mvx 2) fx (bit-and mvx 3)
        iy (bit-shift-right mvy 2) fy (bit-and mvy 3)
        bx (+ x0 ix) by (+ y0 iy)]
    (vec (for [ry (range size)]
           (vec (for [rx (range size)]
                  (quarter-pel-luma plane w h (+ bx rx) (+ by ry) fx fy)))))))

(defn mc-chroma-block
  "Motion-compensated `size`x`size` chroma block from reference `plane`
   (chroma-plane dimensions `w`x`h`) at zero-motion top-left CHROMA-plane
   position (x0,y0) with the LUMA motion vector `[mvx mvy]` (quarter-luma-
   sample units). Returns a `size`x`size` row-vector grid.

   For ChromaArrayType 1 (4:2:0, this repo's only supported chroma format),
   §8.4.1.4 derives the chroma motion vector as `mvCLX == mvLX` — no
   scaling — because chroma sample spacing is exactly 2x luma sample
   spacing, which exactly cancels the 2x difference between luma's
   quarter-sample motion-vector unit and chroma's eighth-sample unit (a
   displacement of `mvx` quarter-LUMA-samples = `mvx/4` luma samples =
   `mvx/8` chroma samples = `mvx` eighth-CHROMA-samples — the same integer).
   So `mvx`/`mvy` here are the SAME values passed to `mc-luma-block`,
   reinterpreted as eighth-chroma-sample units directly (`>> 3`/`& 7`
   instead of luma's `>> 2`/`& 3`)."
  [plane w h x0 y0 [mvx mvy] size]
  (let [ix (bit-shift-right mvx 3) fx (bit-and mvx 7)
        iy (bit-shift-right mvy 3) fy (bit-and mvy 7)
        bx (+ x0 ix) by (+ y0 iy)]
    (vec (for [ry (range size)]
           (vec (for [rx (range size)]
                  (eighth-pel-chroma plane w h (+ bx rx) (+ by ry) fx fy)))))))
