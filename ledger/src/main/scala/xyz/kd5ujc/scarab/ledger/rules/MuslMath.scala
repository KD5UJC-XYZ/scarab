// SPDX-License-Identifier: Apache-2.0 AND MIT
package xyz.kd5ujc.scarab.ledger.rules

import java.lang.Double.{doubleToRawLongBits as bitsOf, longBitsToDouble as fromBits}
import java.lang.Integer.compareUnsigned as compareUnsigned32
import java.lang.Long.compareUnsigned as compareUnsigned64

/** The double-precision `log`, `pow` and `exp` of the reference's libm, bit for bit.
  *
  * The cardano-node 11.1.3 and 11.2.1 release binaries are static executables linked against musl,
  * and GHC 9.6.7 compiles `log`, `exp` and `(**)` on `Double` to calls of musl's `log`, `exp` and
  * `pow`. The pool-ranking likelihoods (`PoolRank.hs:126-215`, cardano-ledger f649f975) are those
  * calls rounded to `Float`, and the JVM's `Math` differs from musl in the last bit often enough to
  * change a stored `Float` (#1003). This is a transcription of musl 1.2.5's `src/math/log.c`,
  * `pow.c` and `exp.c` (Arm's optimized-routines: Copyright (c) 2018, Arm Limited, MIT licence in
  * `ledger/licenses/LICENSE-musl`), the paths musl builds for x86-64 and the release binary
  * contains:
  *   - without fast FMA (`__FP_FAST_FMA` undefined): the `#else` branches of `log.c:89-95`,
  *     `pow.c:60-69`, `pow.c:83-93` and `pow.c:331-341`;
  *   - without `TOINT_INTRINSICS` (`src/internal/libm.h:74-76`) and with `EXP_USE_TOINT_NARROW` 0
  *     (`exp_data.h:13`): the `#else` branches of `exp.c:101-114` and `pow.c:196-209`;
  *   - with `WANT_ROUNDING` 1 and `WANT_SNAN` 0 (`libm.h:63-65`).
  *
  * Every operation is one IEEE-754 binary64 operation in round-to-nearest, in the order and
  * grouping of the C source; the JVM, like the release binary's SSE2 code, neither contracts nor
  * reassociates. The integer arithmetic is musl's unsigned arithmetic on two's-complement `Long`
  * and `Int`: comparisons through `compareUnsigned`, logical shifts `>>>`, and `% N` as
  * `& (N - 1)`. Floating-point exceptions, which musl raises through `fp_barrier` and
  * `fp_force_eval`, are not modelled: they never change a result.
  *
  * The results equal the release binary's own functions bit for bit on every finite and infinite
  * input (`MuslMathSuite`, `fixtures/reference/release-libm`). A NaN result is a NaN, but its
  * payload is not guaranteed: musl's `(x - x) / (x - x)` yields the processor's default NaN and the
  * JVM does not promise one NaN encoding. No caller sees a NaN: the likelihoods take `log` of `x`
  * and `1 - t * x` with `x` in (0, 1) and `t` in [0, 1], and `pow` of `1 - f` in [0, 1) by a stake
  * share in [0, 1].
  */
object MuslMath:

  // `asuint64(1.0)` and `asuint64(INFINITY)`.
  private val OneBits = 0x3ff0000000000000L
  private val InfBits = 0x7ff0000000000000L

  /** `0x1p27`, `0x1p52`, `0x1p1009` and `0x1p-1022`. */
  private val Two27 = fromBits(0x41a0000000000000L)
  private val Two52 = fromBits(0x4330000000000000L)
  private val Two1009 = fromBits(0x7f00000000000000L)
  private val TwoMinus1022 = fromBits(0x0010000000000000L)

  private def double(bits: Long): Double = fromBits(bits)

  /** `__math_xflow` (`__math_xflow.c:3-6`): `y` times itself, carrying the sign. */
  private def xflow(sign: Long, y: Double): Double = (if sign != 0 then -y else y) * y

  /** `__math_oflow` (`__math_oflow.c:3-6`): an overflow to infinity. */
  private def oflow(sign: Long): Double = xflow(sign, fromBits(0x7000000000000000L))

  /** `__math_uflow` (`__math_uflow.c:3-6`): an underflow to zero. */
  private def uflow(sign: Long): Double = xflow(sign, fromBits(0x1000000000000000L))

  /** `__math_invalid` (`__math_invalid.c:3-6`). */
  private def invalid(x: Double): Double = (x - x) / (x - x)

  /** `__math_divzero` (`__math_divzero.c:3-6`). */
  private def divzero(sign: Long): Double = (if sign != 0 then -1.0 else 1.0) / 0.0

  // ---- log (`src/math/log.c`)

  private object LogConst:
    val Ln2hi: Double = double(MuslData.Log.ln2hi)
    val Ln2lo: Double = double(MuslData.Log.ln2lo)
    val A: IArray[Double] = MuslData.Log.poly.map(double)
    val B: IArray[Double] = MuslData.Log.poly1.map(double)
    // `OFF` (`log.c:20`), and `LO` and `HI` (`log.c:37-38`): `asuint64(1.0 - 0x1p-4)` and
    // `asuint64(1.0 + 0x1.09p-4)`.
    val Off = 0x3fe6000000000000L
    val Lo = 0x3fee000000000000L
    val Hi = 0x3ff1090000000000L

  /** musl's `log` (`log.c:28-112`). */
  def log(x: Double): Double =
    import LogConst.*
    val ix = bitsOf(x)
    val top = (ix >>> 48).toInt
    if compareUnsigned64(ix - Lo, Hi - Lo) < 0 then logNearOne(x, ix)
    else if compareUnsigned32(top - 0x0010, 0x7ff0 - 0x0010) >= 0 then
      // `x < 0x1p-1022 or inf or nan` (`log.c:63-74`).
      if ix * 2 == 0 then divzero(1)
      else if ix == InfBits then x
      else if (top & 0x8000) != 0 || (top & 0x7ff0) == 0x7ff0 then invalid(x)
      // A subnormal `x`, normalised.
      else logReduced(bitsOf(x * Two52) - (52L << 52))
    else logReduced(ix)

  /** `log.c:39-62`: `x` within `1 - 0x1p-4` and `1 + 0x1.09p-4`. */
  private def logNearOne(x: Double, ix: Long): Double =
    import LogConst.*
    if ix == OneBits then 0.0
    else
      val r = x - 1.0
      val r2 = r * r
      val r3 = r * r2
      val y = r3 *
        (B(1) + r * B(2) + r2 * B(3) +
          r3 * (B(4) + r * B(5) + r2 * B(6) +
            r3 * (B(7) + r * B(8) + r2 * B(9) + r3 * B(10))))
      val w = r * Two27
      val rhi = r + w - w
      val rlo = r - rhi
      val w2 = rhi * rhi * B(0)
      val hi = r + w2
      val lo = r - hi + w2
      val lo2 = lo + B(0) * rlo * (rhi + r)
      val y2 = y + lo2
      y2 + hi

  /** `log.c:76-111`: `ix` of a positive normal `x` away from 1. */
  private def logReduced(ix: Long): Double =
    import LogConst.*
    val tmp = ix - Off
    val i = ((tmp >>> (52 - 7)) & 127).toInt
    val k = (tmp >> 52).toInt
    val iz = ix - (tmp & (0xfffL << 52))
    val invc = double(MuslData.Log.tab(2 * i))
    val logc = double(MuslData.Log.tab(2 * i + 1))
    val z = double(iz)
    // The non-FMA branch (`log.c:92-95`).
    val r = (z - double(MuslData.Log.tab2(2 * i)) - double(MuslData.Log.tab2(2 * i + 1))) * invc
    val kd = k.toDouble
    val w = kd * Ln2hi + logc
    val hi = w + r
    val lo = w - hi + r + kd * Ln2lo
    val r2 = r * r
    lo + r2 * A(0) + r * r2 * (A(1) + r * A(2) + r2 * (A(3) + r * A(4))) + hi

  // ---- exp (`src/math/exp.c`) and the `exp_inline` of `src/math/pow.c`

  private object ExpConst:
    val InvLn2N: Double = double(MuslData.Exp.invln2N)
    val Shift: Double = double(MuslData.Exp.shift)
    val NegLn2hiN: Double = double(MuslData.Exp.negln2hiN)
    val NegLn2loN: Double = double(MuslData.Exp.negln2loN)
    // `C2` to `C5`: `__exp_data.poly[5 - EXP_POLY_ORDER]` and on (`exp.c:19-22`).
    val C2: Double = double(MuslData.Exp.poly(0))
    val C3: Double = double(MuslData.Exp.poly(1))
    val C4: Double = double(MuslData.Exp.poly(2))
    val C5: Double = double(MuslData.Exp.poly(3))
    // `top12(0x1p-54)`, `top12(512.0)`, `top12(1024.0)` and `top12(INFINITY)`.
    val TopTiny = 0x3c9
    val Top512 = 0x408
    val Top1024 = 0x409
    val TopInf = 0x7ff

  /** `x = ln2/N*k + r` (`exp.c:98-115`, `pow.c:193-210`): `r` and the bits `ki` of `kd + Shift`. */
  private final case class Reduced(r: Double, ki: Long)

  private def reduce(x: Double): Reduced =
    import ExpConst.*
    val z = InvLn2N * x
    // The `#else` branch of `exp.c:109-113`: rounded through the shift.
    val kd0 = z + Shift
    val ki = bitsOf(kd0)
    val kd = kd0 - Shift
    Reduced(x + kd * NegLn2hiN + kd * NegLn2loN, ki)

  /** `exp(r) * 2^(k/N)` as `scale + scale * tmp` (`exp.c:116-127`, `pow.c:213-224`): `tmp` and
    * `sbits`, the bits of `scale`, with `signBias` added to its exponent.
    */
  private final case class Scaled(tmp: Double, sbits: Long)

  private def scaled(r: Double, ki: Long, signBias: Long): Scaled =
    import ExpConst.*
    val idx = 2 * (ki & 127).toInt
    val top = (ki + signBias) << (52 - 7)
    val tail = double(MuslData.Exp.tab(idx))
    val sbits = MuslData.Exp.tab(idx + 1) + top
    val r2 = r * r
    Scaled(tail + r + r2 * (C2 + r * C3) + r2 * r2 * (C4 + r * C5), sbits)

  /** musl's `exp` (`exp.c:72-134`). */
  def exp(x: Double): Double =
    import ExpConst.*
    val abstop = (bitsOf(x) >>> 52).toInt & 0x7ff
    if compareUnsigned32(abstop - TopTiny, Top512 - TopTiny) >= 0 then
      if compareUnsigned32(abstop - TopTiny, 0x80000000) >= 0 then 1.0 + x
      else if abstop >= Top1024 then
        if bitsOf(x) == 0xfff0000000000000L then 0.0
        else if abstop >= TopInf then 1.0 + x
        else if (bitsOf(x) >>> 63) != 0 then uflow(0)
        else oflow(0)
      else
        val reduced = reduce(x)
        val s = scaled(reduced.r, reduced.ki, 0)
        expSpecial(s.tmp, s.sbits, reduced.ki)
    else
      val reduced = reduce(x)
      val s = scaled(reduced.r, reduced.ki, 0)
      val scale = double(s.sbits)
      scale + scale * s.tmp

  /** `specialcase` of `exp.c:31-64`. */
  private def expSpecial(tmp: Double, sbits: Long, ki: Long): Double =
    if (ki & 0x80000000L) == 0 then
      val scale = double(sbits - (1009L << 52))
      Two1009 * (scale + scale * tmp)
    else
      val sb = sbits + (1022L << 52)
      val scale = double(sb)
      val y = scale + scale * tmp
      if y < 1.0 then
        val lo = scale - y + scale * tmp
        val hi = 1.0 + y
        val lo2 = 1.0 - hi + y + lo
        val y2 = (hi + lo2) - 1.0
        // `WANT_ROUNDING`: no `-0.0` (`exp.c:56-58`).
        TwoMinus1022 * (if y2 == 0.0 then 0.0 else y2)
      else TwoMinus1022 * y

  // ---- pow (`src/math/pow.c`)

  private object PowConst:
    val Ln2hi: Double = double(MuslData.PowLog.ln2hi)
    val Ln2lo: Double = double(MuslData.PowLog.ln2lo)
    val A: IArray[Double] = MuslData.PowLog.poly.map(double)
    // `OFF` (`pow.c:25`) and `SIGN_BIAS` (`pow.c:162`).
    val Off = 0x3fe6955500000000L
    val SignBias = 0x800L << 7

  /** `log_inline` of `pow.c:36-101`: `log(x)` as `hi`, rounded, and `lo`, its tail. */
  private final case class LogParts(hi: Double, lo: Double)

  private def logInline(ix: Long): LogParts =
    import PowConst.*
    val tmp = ix - Off
    val i = ((tmp >>> (52 - 7)) & 127).toInt
    val k = (tmp >> 52).toInt
    val iz = ix - (tmp & (0xfffL << 52))
    val z = double(iz)
    val kd = k.toDouble
    val invc = double(MuslData.PowLog.tab(3 * i))
    val logc = double(MuslData.PowLog.tab(3 * i + 1))
    val logctail = double(MuslData.PowLog.tab(3 * i + 2))
    // The non-FMA branch (`pow.c:62-68`): `z` split so that `rhi`, `rlo` and `rhi * rhi` are exact.
    val zhi = double((iz + (1L << 31)) & (-1L << 32))
    val zlo = z - zhi
    val rhi = zhi * invc - 1.0
    val rlo = zlo * invc
    val r = rhi + rlo
    val t1 = kd * Ln2hi + logc
    val t2 = t1 + r
    val lo1 = kd * Ln2lo + logctail
    val lo2 = t1 - t2 + r
    val ar = A(0) * r
    val ar2 = r * ar
    val ar3 = r * ar2
    // The non-FMA branch (`pow.c:87-92`).
    val arhi = A(0) * rhi
    val arhi2 = rhi * arhi
    val hi = t2 + arhi2
    val lo3 = rlo * (ar + arhi)
    val lo4 = t2 - hi + arhi2
    val p = ar3 * (A(1) + r * A(2) + ar2 * (A(3) + r * A(4) + ar2 * (A(5) + r * A(6))))
    val lo = lo1 + lo2 + lo3 + lo4 + p
    val y = hi + lo
    LogParts(y, hi - y + lo)

  /** `exp_inline` of `pow.c:166-231`: `sign * exp(x + xtail)`. */
  private def expInline(x: Double, xtail: Double, signBias: Long): Double =
    import ExpConst.*
    val abstop = (bitsOf(x) >>> 52).toInt & 0x7ff
    if compareUnsigned32(abstop - TopTiny, Top512 - TopTiny) >= 0 then
      if compareUnsigned32(abstop - TopTiny, 0x80000000) >= 0 then
        val one = 1.0 + x
        if signBias != 0 then -one else one
      else if abstop >= Top1024 then
        if (bitsOf(x) >>> 63) != 0 then uflow(signBias) else oflow(signBias)
      else
        val reduced = reduce(x)
        // The code assumes `2^-200 < |xtail| < 2^-8/N` (`pow.c:211-212`).
        val s = scaled(reduced.r + xtail, reduced.ki, signBias)
        powSpecial(s.tmp, s.sbits, reduced.ki)
    else
      val reduced = reduce(x)
      val s = scaled(reduced.r + xtail, reduced.ki, signBias)
      val scale = double(s.sbits)
      scale + scale * s.tmp

  /** `specialcase` of `pow.c:124-160`, which keeps the sign `sbits` carries. */
  private def powSpecial(tmp: Double, sbits: Long, ki: Long): Double =
    if (ki & 0x80000000L) == 0 then
      val scale = double(sbits - (1009L << 52))
      Two1009 * (scale + scale * tmp)
    else
      val sb = sbits + (1022L << 52)
      val scale = double(sb)
      val y = scale + scale * tmp
      if math.abs(y) < 1.0 then
        val one = if y < 0.0 then -1.0 else 1.0
        val lo = scale - y + scale * tmp
        val hi = one + y
        val lo2 = one - hi + y + lo
        val y2 = (hi + lo2) - one
        // The sign of a zero result (`pow.c:152-154`).
        TwoMinus1022 * (if y2 == 0.0 then double(sb & 0x8000000000000000L) else y2)
      else TwoMinus1022 * y

  /** `checkint` (`pow.c:235-247`): 0 if `iy` is not an integer, 1 if odd, 2 if even. */
  private def checkint(iy: Long): Int =
    val e = ((iy >>> 52) & 0x7ff).toInt
    if e < 0x3ff then 0
    else if e > 0x3ff + 52 then 2
    else if (iy & ((1L << (0x3ff + 52 - e)) - 1)) != 0 then 0
    else if (iy & (1L << (0x3ff + 52 - e))) != 0 then 1
    else 2

  /** `zeroinfnan` (`pow.c:250-253`). */
  private def zeroinfnan(i: Long): Boolean = compareUnsigned64(2 * i - 1, 2 * InfBits - 1) >= 0

  /** musl's `pow` (`pow.c:255-343`). */
  def pow(x: Double, y: Double): Double =
    val ix = bitsOf(x)
    val iy = bitsOf(y)
    val topx = (ix >>> 52).toInt
    val topy = (iy >>> 52).toInt
    if compareUnsigned32(topx - 0x001, 0x7ff - 0x001) >= 0 ||
      compareUnsigned32((topy & 0x7ff) - 0x3be, 0x43e - 0x3be) >= 0
    then powSpecialInputs(x, y, ix, iy, topx, topy)
    else powCore(ix, iy, y, 0)

  /** `pow.c:269-325`: `x` subnormal, zero, infinite, NaN or negative, or `|y|` below `0x1p-65` or
    * from `0x1p63`, or NaN.
    */
  private def powSpecialInputs(
      x: Double,
      y: Double,
      ix: Long,
      iy: Long,
      topx: Int,
      topy: Int
  ): Double =
    if zeroinfnan(iy) then
      if 2 * iy == 0 then 1.0
      else if ix == OneBits then 1.0
      else if compareUnsigned64(2 * ix, 2 * InfBits) > 0 || compareUnsigned64(
          2 * iy,
          2 * InfBits
        ) > 0
      then x + y
      else if 2 * ix == 2 * OneBits then 1.0
      else if (compareUnsigned64(2 * ix, 2 * OneBits) < 0) == ((iy >>> 63) == 0) then 0.0
      else y * y
    else if zeroinfnan(ix) then
      val x2 = x * x
      val signed = if (ix >>> 63) != 0 && checkint(iy) == 1 then -x2 else x2
      if (iy >>> 63) != 0 then 1 / signed else signed
    else
      // Here `x` and `y` are non-zero and finite.
      val negative = (ix >>> 63) != 0
      val yint = if negative then checkint(iy) else 2
      if negative && yint == 0 then invalid(x)
      else
        val signBias = if negative && yint == 1 then PowConst.SignBias else 0L
        val ixa = if negative then ix & 0x7fffffffffffffffL else ix
        val topxa = if negative then topx & 0x7ff else topx
        if compareUnsigned32((topy & 0x7ff) - 0x3be, 0x43e - 0x3be) >= 0 then
          if ixa == OneBits then 1.0
          else if (topy & 0x7ff) < 0x3be then
            // `WANT_ROUNDING`: `|y| < 0x1p-65`, `x^y ~= 1 + y * log(x)`.
            if compareUnsigned64(ixa, OneBits) > 0 then 1.0 + y else 1.0 - y
          else if (compareUnsigned64(ixa, OneBits) > 0) == (topy < 0x800) then oflow(0)
          else uflow(0)
        else
          // A subnormal `x`, normalised so that its exponent becomes negative.
          val normal =
            if topxa == 0 then ((bitsOf(x * Two52) & 0x7fffffffffffffffL) - (52L << 52)) else ixa
          powCore(normal, iy, y, signBias)

  /** `pow.c:328-342`: `exp(y * log(x))`, with the non-FMA split of the product (`pow.c:334-340`).
    */
  private def powCore(ix: Long, iy: Long, y: Double, signBias: Long): Double =
    val l = logInline(ix)
    val yhi = double(iy & (-1L << 27))
    val ylo = y - yhi
    val lhi = double(bitsOf(l.hi) & (-1L << 27))
    val llo = l.hi - lhi + l.lo
    val ehi = yhi * lhi
    val elo = ylo * lhi + y * llo
    expInline(ehi, elo, signBias)
