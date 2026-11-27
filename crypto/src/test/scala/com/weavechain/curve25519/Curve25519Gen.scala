// SPDX-License-Identifier: Apache-2.0
package com.weavechain.curve25519

import org.scalacheck.Gen
import xyz.kd5ujc.scarab.crypto.witness.Ed25519Model as M

/** Generators for the suites of the vendored arithmetic, weighted toward the values where limb and
  * reduction code goes wrong: 0, 1, the moduli and their neighbours, the boundaries of the limbs,
  * and runs of ones and zeros. Each generator also draws uniformly, so the rest of the range is
  * reached too. The suites use `forAllNoShrink`: a shrunk value would leave the range a generator
  * keeps to (a negative integer, a shorter limb array), and the failure reported would not be the
  * one found.
  */
object Curve25519Gen:
  val p: BigInt = M.p
  val L: BigInt = M.L
  val d: BigInt = M.d
  val sqrtM1: BigInt = M.sqrtM1

  /** The bit offsets of the ten limbs of a `FieldElement` (radix 2^25.5). */
  val fieldLimbOffsets: Vector[Int] = Vector(0, 26, 51, 77, 102, 128, 153, 179, 204, 230)

  def limbValue(t: Array[Int]): BigInt =
    t.indices.map(i => BigInt(t(i)) << fieldLimbOffsets(i)).sum

  /** A bound on limb `i` given as a factor in hundredths of 2^25 (even limbs) or 2^24 (odd), the
    * form of the bounds in the documentation of `FieldElement`: `bound(110, 25)` is 1.1 * 2^25.
    */
  def bound(hundredths: Long, shiftEven: Int)(i: Int): Int =
    ((hundredths << (if i % 2 == 0 then shiftEven else shiftEven - 1)) / 100).toInt

  /** `add`, `subtract` and `negate` take limbs bounded by 1.1 * 2^25, 1.1 * 2^24, ... */
  val reducedBound: Int => Int = bound(110, 25)

  /** `multiply` and `square` take limbs bounded by 1.65 * 2^26, 1.65 * 2^25, ... */
  val looseBound: Int => Int = bound(165, 26)

  /** What `multiply` and `square` give: limbs bounded by 1.01 * 2^25, 1.01 * 2^24, ... */
  val productBound: Int => Int = bound(101, 25)

  /** What `add` and `subtract` give: limbs bounded by 1.1 * 2^26, 1.1 * 2^25, ... */
  val sumBound: Int => Int = bound(110, 26)

  /** What `toByteArray` reduces, as its documentation states: any limbs below 2^27 in absolute
    * value (`FieldElement.java:124-125`); also what `isZero`, `isNegative` and `ctEquals` read
    * through it.
    */
  val documentedEncodableBound: Int => Int = _ => (1 << 27) - 1

  private def limb(max: Int): Gen[Int] =
    Gen.frequency(
      2 -> Gen.oneOf(0, 1, -1, max, -max, max - 1, -(max - 1), max / 2, -(max / 2)),
      1 -> Gen
        .oneOf((1 << 24) - 1, 1 << 24, (1 << 25) - 1, -(1 << 24), -(1 << 25) + 1)
        .map(v => v.max(-max).min(max)),
      4 -> Gen.choose(-max, max)
    )

  /** Ten limbs, each within its bound: drawn limb by limb, at the corners of the bounds, or of a
    * value near a multiple of p.
    */
  def limbs(bounds: Int => Int): Gen[Array[Int]] =
    Gen.frequency(
      3 -> Gen.sequence[Vector[Int], Int]((0 until 10).map(i => limb(bounds(i)))).map(_.toArray),
      1 -> corners(bounds),
      1 -> nearMultiplesOfP(bounds)
    )

  /** Ten limbs, each at or next to its bound, either sign, or zero. */
  def corners(bounds: Int => Int): Gen[Array[Int]] =
    Gen
      .sequence[Vector[Int], Int]((0 until 10).map { i =>
        val max = bounds(i)
        Gen.frequency(4 -> Gen.oneOf(max, -max), 1 -> Gen.oneOf(max - 1, -(max - 1), 0, 1, -1))
      })
      .map(_.toArray)

  /** The limbs of every sign pattern with each limb at its bound: 2^10 of them. */
  def signPatterns(bounds: Int => Int): Vector[Array[Int]] =
    (0 until 1024).toVector.map(m =>
      Array.tabulate(10)(i => if (m >> i & 1) == 1 then -bounds(i) else bounds(i))
    )

  private val fieldLimbWidths = Vector(26, 25, 26, 25, 26, 25, 26, 25, 26, 25)

  /** Limbs within `bounds` of the value `v`, if it has such: its balanced radix-2^25.5 digits, then
    * the carries `moves` (a small integer per limb) moved between neighbouring limbs wherever both
    * stay within their bounds, which keeps the value and makes the limbs large.
    */
  def limbsFor(v: BigInt, bounds: Int => Int, moves: Seq[Int]): Option[Array[Int]] =
    val (digits, top) = (0 until 9).foldLeft((Vector.empty[BigInt], v)) { case ((ds, rest), i) =>
      val half = BigInt(1) << (fieldLimbWidths(i) - 1)
      val digit = (rest + half).mod(BigInt(1) << fieldLimbWidths(i)) - half
      (ds :+ digit, (rest - digit) >> fieldLimbWidths(i))
    }
    val t = (digits :+ top).toArray
    moves.take(9).zipWithIndex.foreach { (c, i) =>
      val lower = t(i) + c * (BigInt(1) << fieldLimbWidths(i))
      val upper = t(i + 1) - c
      if lower.abs <= bounds(i) && upper.abs <= bounds(i + 1) then
        t(i) = lower
        t(i + 1) = upper
    }
    Option.when(t.indices.forall(i => t(i).abs <= bounds(i)))(t.map(_.toInt))

  /** Limbs within `bounds` whose value is k p + delta, |delta| at most 3, for every k the bounds
    * reach, positive and negative: where a reduction's estimate of the quotient goes wrong first.
    */
  def nearMultiplesOfP(bounds: Int => Int): Gen[Array[Int]] =
    val largest = (0 until 10).map(i => BigInt(bounds(i)) << fieldLimbOffsets(i)).sum / p
    Gen
      .zip(
        Gen.choose(-largest.toInt, largest.toInt),
        Gen.choose(-3, 3),
        Gen.listOfN(9, Gen.oneOf(-2, -1, 0, 0, 1, 2))
      )
      .map((k, delta, moves) => limbsFor(k * p + delta, bounds, moves))
      .suchThat(_.isDefined)
      .map(_.get)

  def uniform(bits: Int): Gen[BigInt] =
    Gen.listOfN((bits + 7) / 8, Gen.choose(0, 255)).map { bytes =>
      BigInt(1, bytes.map(_.toByte).toArray) & ((BigInt(1) << bits) - 1)
    }

  /** Values of `bits` bits whose bytes are mostly 0x00 or 0xff: carries run the length of them. */
  def runs(bits: Int): Gen[BigInt] =
    Gen
      .listOfN(
        (bits + 7) / 8,
        Gen.frequency(3 -> Gen.const(0xff), 2 -> Gen.const(0), 1 -> Gen.choose(0, 255))
      )
      .map(bytes => BigInt(1, bytes.map(_.toByte).toArray) & ((BigInt(1) << bits) - 1))

  /** Below 2^bits: the edges given, their neighbours, uniform values and runs. */
  def weighted(bits: Int, edges: Vector[BigInt]): Gen[BigInt] =
    val top = BigInt(1) << bits
    val inRange = edges.filter(e => e >= 0 && e < top).distinct
    Gen.frequency(
      3 -> Gen.oneOf(inRange),
      2 -> Gen
        .zip(Gen.oneOf(inRange), Gen.choose(-3, 3))
        .map((e, delta) => e + delta)
        .filter(v => v >= 0 && v < top),
      4 -> uniform(bits),
      1 -> runs(bits)
    )

  private def powersAround(offsets: Iterable[Int]): Vector[BigInt] =
    offsets.toVector.flatMap(k =>
      Vector(BigInt(1) << k, (BigInt(1) << k) - 1, (BigInt(1) << k) + 1)
    )

  /** Edges of the field: 0, 1, p and its neighbours, 2^255 - 1, (p - 1)/2, sqrt(-1), d, and the
    * powers of two around each limb boundary.
    */
  val fieldEdges: Vector[BigInt] =
    Vector[BigInt](
      0,
      1,
      2,
      19,
      38,
      p - 2,
      p - 1,
      p,
      p + 1,
      p + 18,
      (p - 1) / 2,
      (p + 1) / 2,
      M.sqrtM1,
      M.d
    ) ++
      powersAround(fieldLimbOffsets.drop(1) ++ Vector(25, 255, 254)) ++
      (0 until 10).map(i =>
        (BigInt(1) << fieldLimbOffsets(i)) * ((BigInt(1) << (if i % 2 == 0 then 26 else 25)) - 1)
      )

  /** The 256 bits `FieldElement.fromByteArray` reads (it ignores bit 255). */
  val fieldInput: Gen[BigInt] =
    Gen
      .zip(weighted(255, fieldEdges), Gen.oneOf(false, true))
      .map((v, high) => if high then v.setBit(255) else v)

  /** Edges of the scalars: 0, 1, L and its multiples and neighbours, 2^252 to 2^256, and the powers
    * of two around the 21-bit limbs of `Scalar` and the 29-bit limbs of `UnpackedScalar`.
    */
  val scalarEdges: Vector[BigInt] =
    Vector[BigInt](
      0,
      1,
      2,
      L - 2,
      L - 1,
      L,
      L + 1,
      2 * L - 1,
      2 * L,
      8 * L,
      15 * L,
      15 * L + (L - 1)
    ) ++
      powersAround(Vector(252, 253, 254, 255, 256)) ++
      powersAround((1 to 12).map(_ * 21)) ++ powersAround((1 to 8).map(_ * 29))

  val wideEdges: Vector[BigInt] =
    scalarEdges ++ Vector(
      L * L,
      L * L - 1,
      L * L + 1,
      (BigInt(1) << 512) - 1,
      ((BigInt(1) << 512) - 1) / L * L
    ) ++
      powersAround(Vector(256, 384, 448, 504, 511, 512)) ++ powersAround((13 to 24).map(_ * 21))

  val scalar256: Gen[BigInt] = weighted(256, scalarEdges)
  val scalar255: Gen[BigInt] = weighted(255, scalarEdges)
  val canonicalScalar: Gen[BigInt] = weighted(253, scalarEdges).map(_.mod(L))
  val wide: Gen[BigInt] = weighted(512, wideEdges)

  def le(value: BigInt, size: Int): Array[Byte] = M.le(value, size)
  def fromLe(bytes: Array[Byte]): BigInt = M.fromLe(bytes)

  /** The square root modulo p that `sqrtRatioM1` calls non-negative (its low bit clear), if any. */
  def nonNegativeRoot(w: BigInt): Option[BigInt] =
    val c = w.modPow((p + 3) / 8, p)
    val root =
      if (c * c - w).mod(p) == 0 then Some(c)
      else if (c * c + w).mod(p) == 0 then Some((c * sqrtM1).mod(p))
      else None
    root.map(r => if r.testBit(0) then p - r else r)

  /** What `sqrtRatioM1` gives for u and v of the values `x` and `y` modulo p: 1 when u/v is a
    * square and 0 when it is not (0/v and 0/0 count as squares, u/0 for u not 0 does not), and the
    * non-negative root of u/v, or of i u/v when u/v is not a square (0 when v is 0). The oracle of
    * `FieldElementSuite` and of the `curve25519-arithmetic` fuzz target.
    */
  def sqrtRatio(x: BigInt, y: BigInt): (Int, BigInt) =
    if x.mod(p) == 0 then (1, BigInt(0))
    else if y.mod(p) == 0 then (0, BigInt(0))
    else
      val w = (x * y.modPow(p - 2, p)).mod(p)
      nonNegativeRoot(w) match
        case Some(r) => (1, r)
        case None    => (0, nonNegativeRoot((w * sqrtM1).mod(p)).get)

  /** What breaks the invariants of the extended coordinates (X : Y : Z : T) of the point (X/Z,
    * Y/Z): Z is not zero, X Y = Z T, and -X^2 + Y^2 = Z^2 + d T^2, the curve equation. None when
    * they hold. A point with the right encoding and a wrong T compresses correctly and breaks the
    * next operation that reads T. `EdwardsPointSuite` checks every point an operation returns with
    * it, and so does the `curve25519-arithmetic` fuzz target.
    */
  def extendedFailure(e: EdwardsPoint): Option[String] =
    def value(f: FieldElement): BigInt = limbValue(VendoredOps.limbs(f)).mod(p)
    val (x, y, z, t) = (value(e.X), value(e.Y), value(e.Z), value(e.T))
    if z == 0 then Some("Z is zero")
    else if (x * y - z * t).mod(p) != 0 then Some("X Y is not Z T")
    else if (-x * x + y * y - z * z - d * t * t).mod(p) != 0 then Some("not on the curve")
    else None
