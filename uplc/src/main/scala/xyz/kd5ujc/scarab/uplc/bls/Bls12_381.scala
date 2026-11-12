// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.bls

import cats.syntax.either.*
import java.math.BigInteger
import org.bouncycastle.crypto.bls.{BLS12_381G1, BLS12_381G2HashToCurve, BLS12_381Serialization}
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.hash2curve.HashToField
import org.bouncycastle.crypto.hash2curve.impl.{
  BLS12_381G1CurveProcessor,
  BLS12_381G1MapToCurve,
  XmdMessageExpansion
}
import org.bouncycastle.math.ec.ECCurve
import supranational.blst.{P1, P1_Affine, P2, P2_Affine, PT}
import xyz.kd5ujc.scarab.codec.Bytes

/** A group of BLS12-381 with a builtin type. */
enum Group:
  case G1, G2

/** Why blst refuses to uncompress a point: the `BLSTError` cases of cardano-crypto-class that
  * `blsUncompress` returns (`PlutusCore/Crypto/BLS12_381/G1.hs:148-150`, `G2.hs:129-131`, plutus
  * 1.70.0.0 `507cab8c`). The builtin fails with their `show` as its log line
  * (`eitherToBuiltinResult`, `Default/Builtins.hs:270-271`). cardano-base is not vendored here: the
  * constructor names and which check gives which case are blst's `POINTonE1_Uncompress_Z` and
  * `POINTonE2_Uncompress_Z` followed by the subgroup check, as recorded, not read at the pin.
  */
enum BlstError:
  case BadEncoding, PointNotOnCurve, PointNotInGroup

  def show: String = this match
    case BadEncoding     => "BLST_BAD_ENCODING"
    case PointNotOnCurve => "BLST_POINT_NOT_ON_CURVE"
    case PointNotInGroup => "BLST_POINT_NOT_IN_GROUP"

/** A builtin that takes a bounded scalar, named as its failure names it. */
enum ScalarOperation:
  case ScalarMul(group: Group)
  case MultiScalarMul(group: Group)

  def name: String = this match
    case ScalarMul(g)      => s"$g.scalarMul"
    case MultiScalarMul(g) => s"$g.multiScalarMul"

/** The failures of the BLS12-381 builtins that the reference has too, each with its log line. */
enum BlsFailure:
  /** `uncompress` refuses the bytes (`G1.hs:140-150`, `G2.hs:121-131`). */
  case Uncompress(group: Group, error: BlstError)

  /** A domain separation tag of more than 255 bytes (`G1.hs:177-181`, `G2.hs:135-139`): `show` of
    * `HashToCurveDstTooBig` (`PlutusCore/Crypto/BLS12_381/Error.hs:31-32`).
    */
  case DstTooBig(group: Group, length: Int)

  /** A scalar outside `[-2^4095, 2^4095 - 1]` (`Bounds.hs:10-24`): `scalarMul` of variants D and E
    * (`G1.hs:124-127`, `G2.hs:107-110`) and both `multiScalarMul` (`G1.hs:211-214`,
    * `G2.hs:168-171`).
    */
  case ScalarOutOfBounds(operation: ScalarOperation)

  def message: String = this match
    case Uncompress(_, error)         => error.show
    case DstTooBig(_, _)              => "HashToCurveDstTooBig"
    case ScalarOutOfBounds(operation) => s"Scalar exceeds 512-byte bound for ${operation.name}"

/** A failure the reference cannot have: a defect of this implementation. */
enum BlsDefect:
  /** jblst or Bouncy Castle threw, or hashed to a point outside the group. */
  case Thrown(cause: Throwable)
  case NotInGroup(group: Group)

  /** A placeholder from [[MlResult.fromRaw]] reached the pairing code. */
  case DetachedMlResult

/** The BLS12-381 operations of the builtins, over jblst (blst v0.3.17, ADR 0025) for the group,
  * pairing and decompression arithmetic and Bouncy Castle for hashing to the groups. The reference
  * calls blst through cardano-crypto-class (`PlutusCore/Crypto/BLS12_381/G1.hs`, `G2.hs`,
  * `Pairing.hs`).
  *
  * jblst's points are mutable native handles whose `add`, `neg`, `mult` and `mul` change the
  * receiver. Every operation here makes its own handles from the bytes of its arguments (or
  * duplicates a [[MlResult]]'s) and lets none escape, so the functions are pure. An exception from
  * either library is a [[BlsDefect]].
  */
object Bls12_381:
  type Result[A] = Either[BlsFailure | BlsDefect, A]

  /** The field modulus `p` and the group order `r` of BLS12-381. */
  val FieldModulus: BigInteger = BLS12_381G1.Q
  val GroupOrder: BigInteger = BLS12_381G1.ORDER

  /** The scalar bounds of `Bounds.hs:10-24`: 64 words of 64 bits, signed. */
  val ScalarMax: BigInt = (BigInt(1) << 4095) - 1
  val ScalarMin: BigInt = -(BigInt(1) << 4095)

  /** `msmScalarOutOfBounds` (`Bounds.hs:22-24`). */
  def scalarOutOfBounds(k: BigInt): Boolean = k < ScalarMin || k > ScalarMax

  /** The largest domain separation tag `hashToGroup` takes (`G1.hs:179`). */
  val MaxDstLength: Int = 255

  private def guarded[A](body: => A): Either[BlsDefect, A] =
    Either.catchNonFatal(body).leftMap(BlsDefect.Thrown(_))

  /** blst multiplies by the scalar itself (`blsMult`); on a point of the group of order `r` that is
    * multiplying by the scalar modulo `r`, which jblst takes only as a non-negative integer.
    */
  private def reduced(k: BigInt): BigInteger = k.bigInteger.mod(GroupOrder)

  private val Two: BigInteger = BigInteger.valueOf(2)
  private val Four: BigInteger = BigInteger.valueOf(4)

  /** Whether `a`, an element of the field, is a square: Euler's criterion. */
  private def isSquare(a: BigInteger): Boolean =
    a.signum == 0 || a.modPow(FieldModulus.subtract(BigInteger.ONE).shiftRight(1), FieldModulus) ==
      BigInteger.ONE

  /** The coordinate `x` a compressed encoding gives, from the leading byte's three flags (`0x80`
    * compressed, `0x40` infinity, `0x20` sign of `y`): none for the point at infinity, which must
    * be `0xc0` and zeros only. The checks and their order are blst's `POINTonE1_Uncompress_Z` and
    * `POINTonE2_Uncompress_Z` with the length check of `blsUncompress` first.
    */
  private def coordinates(
      bytes: Bytes,
      group: Group,
      size: Int
  ): Either[BlsFailure, Option[Vector[BigInteger]]] =
    val bad = BlsFailure.Uncompress(group, BlstError.BadEncoding)
    bytes.headOption.map(_ & 0xff) match
      case Some(b0) if bytes.size == size && (b0 & 0x80) != 0 =>
        if (b0 & 0x40) != 0 then
          Either.cond((b0 & 0x3f) == 0 && bytes.drop(1).value.forall(_ == 0), None, bad)
        else
          // The flags are the top three bits of the first field element; x.c1 comes first in G2.
          val parts = (0 until size / 48).toVector.map(i =>
            new BigInteger(1, bytes.slice(48 * i, 48 * (i + 1)).toArray)
          )
          val xs = parts.zipWithIndex.map((x, i) => if i == 0 then x.mod(Two.shiftLeft(380)) else x)
          Either.cond(xs.forall(_.compareTo(FieldModulus) < 0), Some(xs), bad)
      case _ => Left(bad)

  object G1:
    val CompressedSize: Int = 48

    /** The compressed point at infinity, `0xc0` and 47 zeros (`G1.hs:190-191`). */
    val Zero: G1Element =
      G1Element.fromCompressed(Bytes.wrap(0xc0.toByte +: Array.fill(47)(0.toByte)))

    private def point(e: G1Element): P1 = new P1(e.compressed.toArray)
    private def element(p: P1): G1Element = G1Element.fromCompressed(Bytes.wrap(p.compress()))

    /** `uncompress` (`G1.hs:140-150`): the length, the flags, `x < p` (bad encoding); `x^3 + 4` a
      * square (not on the curve); `x = 0`, the points `(0, ±2)` of order 3, and the subgroup check
      * (not in the group).
      */
    def uncompress(bytes: Bytes): Result[G1Element] =
      coordinates(bytes, Group.G1, CompressedSize).flatMap {
        case None => Right(Zero)
        case Some(xs) =>
          val x = xs.headOption.getOrElse(BigInteger.ZERO)
          val rhs = x.pow(3).add(Four).mod(FieldModulus)
          if !isSquare(rhs) then Left(BlsFailure.Uncompress(Group.G1, BlstError.PointNotOnCurve))
          else if x.signum == 0 then
            Left(BlsFailure.Uncompress(Group.G1, BlstError.PointNotInGroup))
          else
            guarded {
              val affine = new P1_Affine(bytes.toArray)
              Option.when(affine.in_group())(
                G1Element.fromCompressed(Bytes.wrap(affine.compress()))
              )
            }.flatMap(_.toRight(BlsFailure.Uncompress(Group.G1, BlstError.PointNotInGroup)))
      }

    def add(a: G1Element, b: G1Element): Either[BlsDefect, G1Element] =
      guarded(element(point(a).add(point(b))))

    def neg(a: G1Element): Either[BlsDefect, G1Element] = guarded(element(point(a).neg()))

    /** `scalarMul` (`G1.hs:120-122`), or `scalarMulE` (124-127) when `bounded`. */
    def scalarMul(k: BigInt, a: G1Element, bounded: Boolean): Result[G1Element] =
      if bounded && scalarOutOfBounds(k) then
        Left(BlsFailure.ScalarOutOfBounds(ScalarOperation.ScalarMul(Group.G1)))
      else multiply(reduced(k), a)

    private def multiply(k: BigInteger, a: G1Element): Either[BlsDefect, G1Element] =
      if k.signum == 0 then Right(Zero) else guarded(element(point(a).mult(k)))

    /** `multiScalarMul` (`G1.hs:209-214`): every scalar in bounds, the sum over the pairs `zip`
      * makes. blst's `blsMSM` sums by Pippenger's method; the sum is the same point.
      */
    def multiScalarMul(ks: Vector[BigInt], ps: Vector[G1Element]): Result[G1Element] =
      if ks.exists(scalarOutOfBounds) then
        Left(BlsFailure.ScalarOutOfBounds(ScalarOperation.MultiScalarMul(Group.G1)))
      else
        guarded(
          element(
            ks.zip(ps).foldLeft(new P1()) { case (sum, (k, p)) =>
              val s = reduced(k)
              if s.signum == 0 then sum else sum.add(point(p).mult(s))
            }
          )
        )

    /** `hashToGroup` (`G1.hs:175-181`): blst's `hash_to_g1` (RFC 9380 suite
      * `BLS12381G1_XMD:SHA-256_SSWU_RO_`) with `dst` as the domain separation tag. jblst takes the
      * tag as a Java string, which cannot carry every byte string, so this is Bouncy Castle's
      * implementation of the same suite; its result is checked to be in the group.
      */
    def hashToGroup(message: Bytes, dst: Bytes): Result[G1Element] =
      if dst.size > MaxDstLength then Left(BlsFailure.DstTooBig(Group.G1, dst.size))
      else
        guarded {
          val curve: ECCurve = BLS12_381G1.createCurve()
          val field = new HashToField(
            dst.toArray,
            curve,
            new XmdMessageExpansion(new SHA256Digest(), 128),
            64
          )
          val u = field.process(message.toArray, 2)
          val map = new BLS12_381G1MapToCurve(curve)
          val processor = new BLS12_381G1CurveProcessor()
          val q = processor.clearCofactor(processor.add(map.process(u(0)(0)), map.process(u(1)(0))))
          new P1_Affine(BLS12_381Serialization.compressG1(q))
        }.flatMap(affine =>
          guarded(Option.when(affine.in_group())(Bytes.wrap(affine.compress())))
            .flatMap(_.toRight(BlsDefect.NotInGroup(Group.G1)))
        ).map(G1Element.fromCompressed)

  object G2:
    val CompressedSize: Int = 96

    /** The compressed point at infinity, `0xc0` and 95 zeros. */
    val Zero: G2Element =
      G2Element.fromCompressed(Bytes.wrap(0xc0.toByte +: Array.fill(95)(0.toByte)))

    private def point(e: G2Element): P2 = new P2(e.compressed.toArray)
    private def element(p: P2): G2Element = G2Element.fromCompressed(Bytes.wrap(p.compress()))

    /** `x^3 + 4(1 + i)` is a square in `Fp2 = Fp[i]/(i^2 + 1)` exactly when its norm `a0^2 + a1^2`
      * is a square in `Fp`.
      */
    private def onCurve(c1: BigInteger, c0: BigInteger): Boolean =
      val p = FieldModulus
      // (c0 + c1 i)^2 and then times (c0 + c1 i)
      val s0 = c0.multiply(c0).subtract(c1.multiply(c1)).mod(p)
      val s1 = c0.multiply(c1).shiftLeft(1).mod(p)
      val t0 = s0.multiply(c0).subtract(s1.multiply(c1)).add(Four).mod(p)
      val t1 = s0.multiply(c1).add(s1.multiply(c0)).add(Four).mod(p)
      isSquare(t0.multiply(t0).add(t1.multiply(t1)).mod(p))

    /** `uncompress` (`G2.hs:121-131`), with the checks of [[G1.uncompress]] over `Fp2`. */
    def uncompress(bytes: Bytes): Result[G2Element] =
      coordinates(bytes, Group.G2, CompressedSize).flatMap {
        case None => Right(Zero)
        case Some(xs) =>
          val c1 = xs.headOption.getOrElse(BigInteger.ZERO)
          val c0 = xs.lift(1).getOrElse(BigInteger.ZERO)
          if !onCurve(c1, c0) then Left(BlsFailure.Uncompress(Group.G2, BlstError.PointNotOnCurve))
          else
            guarded {
              val affine = new P2_Affine(bytes.toArray)
              Option.when(affine.in_group())(
                G2Element.fromCompressed(Bytes.wrap(affine.compress()))
              )
            }.flatMap(_.toRight(BlsFailure.Uncompress(Group.G2, BlstError.PointNotInGroup)))
      }

    def add(a: G2Element, b: G2Element): Either[BlsDefect, G2Element] =
      guarded(element(point(a).add(point(b))))

    def neg(a: G2Element): Either[BlsDefect, G2Element] = guarded(element(point(a).neg()))

    /** `scalarMul` (`G2.hs:103-105`), or `scalarMulE` (107-110) when `bounded`. */
    def scalarMul(k: BigInt, a: G2Element, bounded: Boolean): Result[G2Element] =
      if bounded && scalarOutOfBounds(k) then
        Left(BlsFailure.ScalarOutOfBounds(ScalarOperation.ScalarMul(Group.G2)))
      else multiply(reduced(k), a)

    private def multiply(k: BigInteger, a: G2Element): Either[BlsDefect, G2Element] =
      if k.signum == 0 then Right(Zero) else guarded(element(point(a).mult(k)))

    /** `multiScalarMul` (`G2.hs:168-171`), as [[G1.multiScalarMul]]. */
    def multiScalarMul(ks: Vector[BigInt], ps: Vector[G2Element]): Result[G2Element] =
      if ks.exists(scalarOutOfBounds) then
        Left(BlsFailure.ScalarOutOfBounds(ScalarOperation.MultiScalarMul(Group.G2)))
      else
        guarded(
          element(
            ks.zip(ps).foldLeft(new P2()) { case (sum, (k, p)) =>
              val s = reduced(k)
              if s.signum == 0 then sum else sum.add(point(p).mult(s))
            }
          )
        )

    /** `hashToGroup` (`G2.hs:135-139`): blst's `hash_to_g2` (suite
      * `BLS12381G2_XMD:SHA-256_SSWU_RO_`) through Bouncy Castle, as [[G1.hashToGroup]].
      */
    def hashToGroup(message: Bytes, dst: Bytes): Result[G2Element] =
      if dst.size > MaxDstLength then Left(BlsFailure.DstTooBig(Group.G2, dst.size))
      else
        guarded {
          val q = new BLS12_381G2HashToCurve(dst.toArray).hashToCurve(message.toArray)
          new P2_Affine(BLS12_381Serialization.compressG2(q))
        }.flatMap(affine =>
          guarded(Option.when(affine.in_group())(Bytes.wrap(affine.compress())))
            .flatMap(_.toRight(BlsDefect.NotInGroup(Group.G2)))
        ).map(G2Element.fromCompressed)

  object Pairing:
    private def target(m: MlResult): Either[BlsDefect, PT] =
      m.target.toRight(BlsDefect.DetachedMlResult)

    /** `millerLoop` (`Pairing.hs:68-69`): blst's `blst_miller_loop`. */
    def millerLoop(a: G1Element, b: G2Element): Either[BlsDefect, MlResult] =
      guarded(
        MlResult.ofTarget(
          new PT(new P1_Affine(a.compressed.toArray), new P2_Affine(b.compressed.toArray))
        )
      )

    /** `mulMlResult` (`Pairing.hs:71-72`): blst's `blst_fp12_mul`. */
    def mulMlResult(a: MlResult, b: MlResult): Either[BlsDefect, MlResult] =
      for
        x <- target(a)
        y <- target(b)
        product <- guarded(MlResult.ofTarget(x.dup().mul(y)))
      yield product

    /** `finalVerify` (`Pairing.hs:74-75`): blst's `blst_fp12_finalverify`. */
    def finalVerify(a: MlResult, b: MlResult): Either[BlsDefect, Boolean] =
      for
        x <- target(a)
        y <- target(b)
        verdict <- guarded(PT.finalverify(x, y))
      yield verdict
