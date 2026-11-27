// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.fuzz

import com.weavechain.curve25519.{
  CompressedEdwardsY,
  Constants,
  EdwardsPoint,
  Scalar,
  Curve25519Gen as G,
  VendoredOps as V
}
import com.weavechain.curve25519.VendoredOps.Limbs
import java.nio.file.{Files, Path}
import org.bouncycastle.math.ec.rfc8032.Ed25519
import scala.jdk.OptionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.witness.{
  Ed25519Verifiers,
  VartimeEd25519Probe,
  VerificationResult,
  Ed25519Model as M
}

/** Differential targets of the vendored curve arithmetic (`com.weavechain.curve25519`) and of the
  * Ed25519 verifiers, with oracles that are no dependency of the node: exact integer arithmetic
  * (`BigInt` over `java.math.BigInteger`), the independent model of RFC 8032 (`Ed25519Model`), the
  * JDK's Ed25519 and Bouncy Castle's. The fuzzer's bytes are operands, not an encoding to decode:
  * every input reads as one operation and its operands, so a mutation changes a limb, a scalar, a
  * point or a signature, and the shrunk input of a failure is still one.
  */
object CurveTargets:
  private def hit(name: String): Unit =
    Targets.accepted
      .computeIfAbsent(name, _ => new java.util.concurrent.atomic.LongAdder)
      .increment()

  private def hex(bytes: Array[Byte]): String = Bytes.fromArray(bytes).hex
  private def hex(value: BigInt): String = value.toString(16)

  /** `n` bytes of `in` from `from`, zero past its end: every input reads as operands. */
  private def window(in: Array[Byte], from: Int, n: Int): Array[Byte] =
    Array.tabulate(n)(i => if from + i < in.length then in(from + i) else 0.toByte)

  private def unsigned(in: Array[Byte], at: Int): Int = if at < in.length then in(at) & 0xff else 0

  /** The checks of one operation, whose name prefixes what a violation says. */
  private final class Check(operation: String):
    def apply(ok: Boolean, what: => String): Unit =
      if !ok then throw new Violation(s"$operation: $what")
    def same(got: BigInt, expected: BigInt, what: => String): Unit =
      apply(got == expected, s"$what is ${hex(got)}, expected ${hex(expected)}")
    def within(t: Limbs, bounds: Int => Int, what: => String): Unit =
      t.indices.foreach { i =>
        apply(
          math.abs(t(i).toLong) <= bounds(i),
          s"$what: limb $i is ${t(i)}, bound ${bounds(i)} (limbs ${t.mkString(",")})"
        )
      }

  // --- operands -----------------------------------------------------------------------------------

  /** The operands an operation reads, each of a fixed size at a fixed offset. */
  private enum Operand(val size: Int):
    /** A mode and 40 bytes: ten limbs within the bounds the operation takes. */
    case Field extends Operand(41)

    /** A mode and 32 bytes: the encoding `fromByteArray` reads. */
    case FieldBytes extends Operand(33)

    /** A mode and 32 bytes: an integer below 2^256 (or 2^255, as the operation takes). */
    case Scalar32 extends Operand(33)

    /** A mode and 64 bytes: an integer below 2^512. */
    case Wide extends Operand(65)

    /** A mode, a point encoding and a torsion index. */
    case Point extends Operand(34)

  private val moveSteps = Vector(-2, -1, 0, 0, 1, 2)

  /** The largest k for which the bounds write k p, as `Curve25519Gen.nearMultiplesOfP` takes it. */
  private def reach(bounds: Int => Int): Int =
    ((0 until 10).map(i => BigInt(bounds(i)) << G.fieldLimbOffsets(i)).sum / G.p).toInt

  /** Limbs of `v` within `bounds`, the carries `moves` moved between neighbours
    * (`Curve25519Gen.limbsFor`); without them when they leave a bound, and the decoding of v modulo
    * p when v has no limbs within the bounds at all.
    */
  private def written(v: BigInt, bounds: Int => Int, moves: Seq[Int]): Limbs =
    G.limbsFor(v, bounds, moves)
      .orElse(G.limbsFor(v, bounds, Nil))
      .getOrElse(V.fromByteArray(G.le(v.mod(G.p), 32)))

  /** Ten limbs within `bounds`: drawn in their range, at the corners of the range, k p + delta or
    * an edge of the field (0, 1, p and its neighbours, the limb boundaries) written in large limbs,
    * or 32 bytes decoded as the arithmetic decodes them. The modes are those of the generators of
    * `FieldElementSuite`.
    */
  private def field(in: Array[Byte], from: Int, bounds: Int => Int): Limbs =
    val payload = window(in, from + 1, 40)
    def at(i: Int): Int = payload(i) & 0xff
    def moves: Seq[Int] = (2 until 11).map(i => moveSteps(at(i) % moveSteps.size))
    unsigned(in, from) % 5 match
      case 0 =>
        Array.tabulate(10) { i =>
          val drawn = (at(4 * i) | at(4 * i + 1) << 8 | at(4 * i + 2) << 16 | at(4 * i + 3) << 24)
          (Math.floorMod(drawn.toLong, 2L * bounds(i) + 1) - bounds(i)).toInt
        }
      case 1 =>
        Array.tabulate(10) { i =>
          val b = bounds(i)
          Vector(b, -b, b - 1, 1 - b, 0, 1, -1, b / 2)(at(i) & 7)
        }
      case 2 =>
        val largest = reach(bounds)
        val k = Math.floorMod(payload(0).toInt, 2 * largest + 1) - largest
        written(BigInt(k) * G.p + payload(1).toInt, bounds, moves)
      case 3 =>
        written(G.fieldEdges(at(0) % G.fieldEdges.size) + (at(1) % 7 - 3), bounds, moves)
      case _ => V.fromByteArray(payload.take(32))

  private val below255: BigInt = (BigInt(1) << 255) - 1

  /** 32 bytes for `fromByteArray`: as given, an edge of the field with either bit 255, or one of
    * the 64 values below 2^255, the 19 non-canonical encodings among them.
    */
  private def fieldBytes(in: Array[Byte], from: Int): Array[Byte] =
    val payload = window(in, from + 1, 32)
    def at(i: Int): Int = payload(i) & 0xff
    def signed(v: BigInt): Array[Byte] = G.le(if (at(2) & 1) == 1 then v.setBit(255) else v, 32)
    unsigned(in, from) % 3 match
      case 0 => payload
      case 1 =>
        signed((G.fieldEdges(at(0) % G.fieldEdges.size) + (at(1) % 7 - 3)).max(0).min(below255))
      case _ => signed(below255 - (at(0) & 63))

  /** An integer below 2^bits: as given, an edge of the scalars (0, L and its multiples and
    * neighbours, 2^252 to 2^256, the limb boundaries), runs of 0x00 and 0xff, or j L + delta.
    */
  private def scalar(in: Array[Byte], from: Int, bits: Int): BigInt =
    val payload = window(in, from + 1, 32)
    def at(i: Int): Int = payload(i) & 0xff
    val v = unsigned(in, from) % 4 match
      case 0 => G.fromLe(payload)
      case 1 => G.scalarEdges(at(0) % G.scalarEdges.size) + (at(1) % 7 - 3)
      case 2 => G.fromLe(runs(payload))
      case _ => BigInt(at(0) & 15) * G.L + payload(1).toInt
    v.mod(BigInt(1) << bits)

  /** An integer below 2^512, in the modes of [[scalar]], with q L + delta for q below 2^256. */
  private def wide(in: Array[Byte], from: Int): BigInt =
    val payload = window(in, from + 1, 64)
    def at(i: Int): Int = payload(i) & 0xff
    val v = unsigned(in, from) % 4 match
      case 0 => G.fromLe(payload)
      case 1 => G.wideEdges(at(0) % G.wideEdges.size) + (at(1) % 7 - 3)
      case 2 => G.fromLe(runs(payload))
      case _ => G.fromLe(payload.take(32)) * G.L + payload(32).toInt
    v.mod(BigInt(1) << 512)

  /** Bytes mostly 0x00 or 0xff, so that carries run the length of them. */
  private def runs(payload: Array[Byte]): Array[Byte] =
    payload.map(b =>
      (b & 3) match
        case 0 => 0.toByte
        case 1 => (-1).toByte
        case _ => b
    )

  /** A point: the bytes the implementations read it from, the model's point of them, and whether it
    * is of prime order where its construction says so.
    */
  private final case class PointOperand(
      encoding: Array[Byte],
      model: M.Point,
      primeOrder: Option[Boolean]
  ):
    def vendored(check: Check): EdwardsPoint =
      val decoded = new CompressedEdwardsY(encoding.clone()).decompress().toScala
      check(decoded.isDefined, s"decompress refuses ${hex(encoding)}, a point's encoding")
      decoded.get

  private def canonical(m: M.Point, primeOrder: Boolean): PointOperand =
    PointOperand(m.encode, m, Some(primeOrder))

  /** The 32 bytes at the operand as every decoder reads them (the vendored `decompress`, the
    * decoding of `VartimeEd25519` and the model's, y modulo p and x = 0 whatever the sign), then a
    * point of them: the point as written, [8]P (of the prime-order subgroup), a point of small
    * order, or [8]P plus one. None when the bytes are no point's (all three decoders refusing
    * them).
    */
  private def point(in: Array[Byte], from: Int, check: Check): Option[PointOperand] =
    val raw = window(in, from + 1, 32)
    val model = M.decode(raw, M.Reading.Lenient)
    val vendored = new CompressedEdwardsY(raw.clone()).decompress().toScala
    val expected = model.map(m => hex(m.encode))
    check(
      vendored.map(p => hex(p.compress().toByteArray)) == expected,
      s"decompress of ${hex(raw)} is ${vendored.map(p => hex(p.compress().toByteArray))}, " +
        s"expected $expected"
    )
    check(
      VartimeEd25519Probe.decoded(raw).map(hex) == expected,
      s"VartimeEd25519's decoding of ${hex(raw)} is ${VartimeEd25519Probe.decoded(raw).map(hex)}, " +
        s"expected $expected"
    )
    val i = unsigned(in, from + 33) & 7
    unsigned(in, from) % 4 match
      case 0 => model.map(PointOperand(raw, _, None))
      case 1 => model.map(m => canonical(m * 8, true))
      case 2 => Some(canonical(M.torsion(i), i == 0))
      case _ => model.map(m => canonical(m * 8 + M.torsion(i), i == 0))

  // --- field operations: FieldElementSuite's comparisons ----------------------------------------

  private def value(t: Limbs): BigInt = G.limbValue(t).mod(G.p)

  private def fieldDecode(in: Array[Byte], at: Vector[Int], check: Check): Boolean =
    val bytes = fieldBytes(in, at(0))
    val expected = (G.fromLe(bytes) & below255).mod(G.p)
    val t = V.fromByteArray(bytes)
    check.same(value(t), expected, s"fromByteArray(${hex(bytes)})")
    check.within(t, G.reducedBound, s"fromByteArray(${hex(bytes)})")
    check.same(G.fromLe(V.toByteArray(t)), expected, s"toByteArray(fromByteArray(${hex(bytes)}))")
    true

  private def fieldEncode(in: Array[Byte], at: Vector[Int], check: Check): Boolean =
    val t = field(in, at(0), G.documentedEncodableBound)
    val v = value(t)
    val bytes = V.toByteArray(t)
    val clue = s"limbs ${t.mkString(",")}"
    check.same(G.fromLe(bytes), v, s"toByteArray, $clue")
    check((bytes(31) & 0x80) == 0, s"bit 255 of the encoding, $clue")
    check(V.isZero(t) == (if v == 0 then 1 else 0), s"isZero, $clue")
    check(V.isNegative(t) == (if v.testBit(0) then 1 else 0), s"isNegative, $clue")
    check(V.ctEquals(t, V.fromByteArray(G.le(v, 32))) == 1, s"ctEquals the canonical, $clue")
    true

  private def fieldAddSubtract(in: Array[Byte], at: Vector[Int], check: Check): Boolean =
    val (f, g) = (field(in, at(0), G.reducedBound), field(in, at(1), G.reducedBound))
    val (x, y) = (value(f), value(g))
    val clue = s"limbs ${f.mkString(",")} and ${g.mkString(",")}"
    val (sum, difference, negation) = (V.add(f, g), V.subtract(f, g), V.negate(f))
    check.same(value(sum), (x + y).mod(G.p), s"add, $clue")
    check.same(value(difference), (x - y).mod(G.p), s"subtract, $clue")
    check.same(value(negation), (-x).mod(G.p), s"negate, $clue")
    check.within(sum, G.sumBound, s"add, $clue")
    check.within(difference, G.sumBound, s"subtract, $clue")
    check.within(negation, G.reducedBound, s"negate, $clue")
    true

  private def fieldMultiply(in: Array[Byte], at: Vector[Int], check: Check): Boolean =
    val (f, g) = (field(in, at(0), G.looseBound), field(in, at(1), G.looseBound))
    val (x, y) = (value(f), value(g))
    val clue = s"limbs ${f.mkString(",")} and ${g.mkString(",")}"
    val products = Vector(
      "multiply" -> (V.multiply(f, g), x * y),
      "square" -> (V.square(f), x * x),
      "squareAndDouble" -> (V.squareAndDouble(f), 2 * x * x)
    )
    products.foreach { case (name, (got, exact)) =>
      check.same(value(got), exact.mod(G.p), s"$name, $clue")
      check.within(got, G.productBound, s"$name, $clue")
    }
    true

  private def fieldInvert(in: Array[Byte], at: Vector[Int], check: Check): Boolean =
    val f = field(in, at(0), G.looseBound)
    val x = value(f)
    val clue = s"limbs ${f.mkString(",")}"
    val inverse = V.invert(f)
    check.same(value(inverse), x.modPow(G.p - 2, G.p), s"invert, $clue")
    check.within(inverse, G.productBound, s"invert, $clue")
    check.same(value(V.powP58(f)), x.modPow((G.p - 5) / 8, G.p), s"powP58, $clue")
    true

  private def fieldSquareRoot(in: Array[Byte], at: Vector[Int], check: Check): Boolean =
    val (u, v) = (field(in, at(0), G.sumBound), field(in, at(1), G.sumBound))
    val (x, y) = (value(u), value(v))
    val (square, root) = G.sqrtRatio(x, y)
    val (wasSquare, result) = V.sqrtRatioM1(u, v)
    val clue = s"u = ${hex(x)}, v = ${hex(y)}"
    check(wasSquare == square, s"sqrtRatioM1's wasSquare is $wasSquare, expected $square, $clue")
    check.same(value(result), root, s"sqrtRatioM1's root, $clue")
    true

  // --- scalar operations: ScalarSuite's comparisons ---------------------------------------------

  private def scalarValue(s: Scalar): BigInt = G.fromLe(s.toByteArray)

  private def scalarReduce(in: Array[Byte], at: Vector[Int], check: Check): Boolean =
    val v = scalar(in, at(0), 256)
    val bytes = G.le(v, 32)
    val clue = hex(v)
    check.same(scalarValue(Scalar.fromBytesModOrder(bytes.clone())), v.mod(G.L), s"reduce $clue")
    val canonical = Scalar.fromCanonicalBytes(bytes.clone()).toScala.map(scalarValue)
    check(
      canonical == Option.when(v < G.L)(v),
      s"fromCanonicalBytes of $clue is ${canonical.map(hex)}, expected it exactly below L"
    )
    val low = v & below255
    val bits = Scalar.fromBits(bytes.clone())
    check.same(scalarValue(bits), low, s"fromBits $clue")
    check.same(scalarValue(bits.reduce()), low.mod(G.L), s"reduce of fromBits $clue")
    true

  private def scalarReduceWide(in: Array[Byte], at: Vector[Int], check: Check): Boolean =
    val v = wide(in, at(0))
    val bytes = G.le(v, 64)
    check.same(
      scalarValue(Scalar.fromBytesModOrderWide(bytes.clone())),
      v.mod(G.L),
      s"reduce wide ${hex(v)}"
    )
    val into = Array.fill[Byte](32)(0x5a)
    Scalar.reduceWide(bytes.clone(), into)
    check.same(G.fromLe(into), v.mod(G.L), s"reduceWide into an array, ${hex(v)}")
    true

  private def scalarRing(in: Array[Byte], at: Vector[Int], check: Check): Boolean =
    val (a, b, c) = (scalar(in, at(0), 255), scalar(in, at(1), 255), scalar(in, at(2), 255))
    val (x, y, z) =
      (Scalar.fromBits(G.le(a, 32)), Scalar.fromBits(G.le(b, 32)), Scalar.fromBits(G.le(c, 32)))
    val clue = s"a = ${hex(a)}, b = ${hex(b)}, c = ${hex(c)}"
    check.same(scalarValue(x.add(y)), (a + b).mod(G.L), s"add, $clue")
    check.same(scalarValue(x.subtract(y)), (a - b).mod(G.L), s"subtract, $clue")
    check.same(scalarValue(x.multiply(y)), (a * b).mod(G.L), s"multiply, $clue")
    check.same(scalarValue(x.multiplyAndAdd(y, z)), (a * b + c).mod(G.L), s"multiplyAndAdd, $clue")
    check.same(scalarValue(x.square()), (a * a).mod(G.L), s"square, $clue")
    val into = Array.fill[Byte](32)(0x5a)
    Scalar.multiplyAndAdd(G.le(a, 32), G.le(b, 32), G.le(c, 32), into)
    check.same(G.fromLe(into), (a * b + c).mod(G.L), s"multiplyAndAdd into an array, $clue")
    true

  private def scalarDigits(in: Array[Byte], at: Vector[Int], check: Check): Boolean =
    val v = scalar(in, at(0), 255)
    val digits = new Array[Byte](64)
    Scalar.toRadix16(G.le(v, 32), digits)
    digits.indices.foreach { i =>
      val upper = if i == 63 then 8 else 7
      check(
        digits(i) >= -8 && digits(i) <= upper,
        s"radix-16 digit $i is ${digits(i)} for ${hex(v)}"
      )
    }
    check.same(
      digits.indices.map(i => BigInt(digits(i).toInt) << (4 * i)).sum,
      v,
      "radix-16 digits"
    )
    val naf = V.nonAdjacentForm(G.le(v, 32)).map(_.toInt)
    naf.indices.foreach { i =>
      val d = naf(i)
      check(d == 0 || (math.abs(d) <= 15 && d % 2 != 0), s"NAF digit $i is $d for ${hex(v)}")
    }
    val nonzero = naf.indices.filter(naf(_) != 0)
    nonzero.zip(nonzero.drop(1)).foreach { (i, j) =>
      check(j - i >= 5, s"NAF digits $i and $j are less than 5 apart for ${hex(v)}")
    }
    check.same(naf.indices.map(i => BigInt(naf(i)) << i).sum, v, "NAF digits")
    true

  // --- point operations: EdwardsPointSuite's comparisons ----------------------------------------

  private def encoding(e: EdwardsPoint): String = hex(e.compress().toByteArray)

  private val zero32 = new Array[Byte](32)

  private def groupLaw(in: Array[Byte], at: Vector[Int], check: Check): Boolean =
    point(in, at(0), check).zip(point(in, at(1), check)).exists { (p, q) =>
      val (a, b) = (p.vendored(check), q.vendored(check))
      val (m, n) = (p.model, q.model)
      val clue = s"P ${hex(p.encoding)}, Q ${hex(q.encoding)}"
      Vector(
        "add" -> (a.add(b), m + n),
        "subtract" -> (a.subtract(b), m - n),
        "negate" -> (a.negate(), -m),
        "dbl" -> (a.dbl(), m + m),
        "multiplyByCofactor" -> (a.multiplyByCofactor(), m * 8)
      ).foreach { case (name, (got, model)) =>
        check(
          encoding(got) == hex(model.encode),
          s"$name is ${encoding(got)}, expected ${hex(model.encode)}, $clue"
        )
      }
      check(a.isSmallOrder() == m.isSmallOrder, s"isSmallOrder, $clue")
      check(a.isIdentity() == m.isIdentity, s"isIdentity, $clue")
      check(a.equals(b) == m.same(n), s"equals, $clue")
      true
    }

  /** The scalar of an operand below 2^255, with its bytes and radix-16 digits. */
  private final case class ScalarOperand(value: BigInt):
    val bytes: Array[Byte] = G.le(value, 32)
    def scalar: Scalar = Scalar.fromBits(bytes.clone())
    def digits: Array[Byte] =
      val e = new Array[Byte](64)
      Scalar.toRadix16(bytes, e)
      e

  /** [a]P by the constant-time multiplication, from radix-16 digits, by the variable-time double
    * multiplication with b = 0, and by the double multiplication of `VartimeEd25519`: the same
    * point.
    */
  private def variableBase(in: Array[Byte], at: Vector[Int], check: Check): Boolean =
    point(in, at(0), check).exists { p =>
      val a = ScalarOperand(scalar(in, at(1), 255))
      val point = p.vendored(check)
      val expected = encoding(point.multiply(a.scalar))
      val clue = s"[${hex(a.value)}]P, P ${hex(p.encoding)}"
      Vector(
        "multiplyRadix16" -> Some(encoding(point.multiplyRadix16(a.digits))),
        "vartimeDoubleScalarMultiplyBasepoint" -> Some(
          encoding(EdwardsPoint.vartimeDoubleScalarMultiplyBasepoint(a.scalar, point, Scalar.ZERO))
        ),
        "VartimeEd25519" -> VartimeEd25519Probe
          .doubleScalarMultiplyBasepoint(a.bytes, p.encoding, zero32)
          .map(hex)
      ).foreach { (name, got) =>
        check(got.contains(expected), s"$name gives $got, multiply $expected, $clue")
      }
      true
    }

  /** [a]P by the constant-time multiplication against the model's. */
  private def variableBaseModel(in: Array[Byte], at: Vector[Int], check: Check): Boolean =
    point(in, at(0), check).exists { p =>
      val a = ScalarOperand(scalar(in, at(1), 255))
      val got = encoding(p.vendored(check).multiply(a.scalar))
      val expected = hex((p.model * a.value).encode)
      check(
        got == expected,
        s"multiply gives $got, the model $expected, [${hex(a.value)}]P, P ${hex(p.encoding)}"
      )
      true
    }

  private val identityEncoding = M.identity.encode

  /** [b]B by the basepoint table, from its radix-16 digits, by the constant-time multiplication of
    * the basepoint, and by both double multiplications with a = 0.
    */
  private def fixedBase(in: Array[Byte], at: Vector[Int], check: Check): Boolean =
    val b = ScalarOperand(scalar(in, at(0), 255))
    val expected = encoding(Constants.ED25519_BASEPOINT_TABLE.multiply(b.scalar))
    val clue = s"[${hex(b.value)}]B"
    Vector(
      "the basepoint table's multiplyRadix16" -> Some(
        encoding(Constants.ED25519_BASEPOINT_TABLE.multiplyRadix16(b.digits))
      ),
      "multiply" -> Some(encoding(Constants.ED25519_BASEPOINT.multiply(b.scalar))),
      "vartimeDoubleScalarMultiplyBasepoint" -> Some(
        encoding(
          EdwardsPoint.vartimeDoubleScalarMultiplyBasepoint(
            Scalar.ZERO,
            EdwardsPoint.IDENTITY,
            b.scalar
          )
        )
      ),
      "VartimeEd25519" -> VartimeEd25519Probe
        .doubleScalarMultiplyBasepoint(zero32, identityEncoding, b.bytes)
        .map(hex)
    ).foreach { (name, got) =>
      check(got.contains(expected), s"$name gives $got, the basepoint table $expected, $clue")
    }
    true

  /** [b]B by the basepoint table against the model's. */
  private def fixedBaseModel(in: Array[Byte], at: Vector[Int], check: Check): Boolean =
    val b = ScalarOperand(scalar(in, at(0), 255))
    val got = encoding(Constants.ED25519_BASEPOINT_TABLE.multiply(b.scalar))
    val expected = hex((M.basepoint * b.value).encode)
    check(
      got == expected,
      s"the basepoint table gives $got, the model $expected, [${hex(b.value)}]B"
    )
    true

  /** [a]P + [b]B by the two double multiplications against the sum of the constant-time
    * multiplication and the basepoint table.
    */
  private def doubleBase(in: Array[Byte], at: Vector[Int], check: Check): Boolean =
    point(in, at(0), check).exists { p =>
      val (a, b) = (ScalarOperand(scalar(in, at(1), 255)), ScalarOperand(scalar(in, at(2), 255)))
      val point = p.vendored(check)
      val expected =
        encoding(point.multiply(a.scalar).add(Constants.ED25519_BASEPOINT_TABLE.multiply(b.scalar)))
      val clue = s"[${hex(a.value)}]P + [${hex(b.value)}]B, P ${hex(p.encoding)}"
      Vector(
        "vartimeDoubleScalarMultiplyBasepoint" -> Some(
          encoding(EdwardsPoint.vartimeDoubleScalarMultiplyBasepoint(a.scalar, point, b.scalar))
        ),
        "VartimeEd25519" -> VartimeEd25519Probe
          .doubleScalarMultiplyBasepoint(a.bytes, p.encoding, b.bytes)
          .map(hex)
      ).foreach { (name, got) =>
        check(
          got.contains(expected),
          s"$name gives $got, the constant-time multiplications $expected, $clue"
        )
      }
      true
    }

  /** `isTorsionFree` against the point's construction, or the model's [L]P for a point as written.
    */
  private def order(in: Array[Byte], at: Vector[Int], check: Check): Boolean =
    point(in, at(0), check).exists { p =>
      val expected = p.primeOrder.getOrElse(p.model.inPrimeSubgroup)
      check(
        p.vendored(check).isTorsionFree() == expected,
        s"isTorsionFree of ${hex(p.encoding)} is not $expected"
      )
      true
    }

  /** One operation: its operands, in order from byte 1, its comparisons, and its share of the
    * inputs. Whether its comparisons ran (its points decoded) is what the target counts.
    */
  private final case class Operation(
      name: String,
      operands: Vector[Operand],
      run: (Array[Byte], Vector[Int], Check) => Boolean,
      weight: Int
  ):
    val offsets: Vector[Int] = operands.scanLeft(1)(_ + _.size).init

  import Operand.*

  // A point operation costs ten to a hundred times a field or scalar operation, and one against
  // the model (an exact multiplication over `BigInt`) ten times a point operation. The weights give
  // the point operations a sixth of the inputs, the model's multiplications one in 24.
  private val operations: Vector[Operation] = Vector(
    Operation("field decode", Vector(FieldBytes), fieldDecode, 4),
    Operation("field encode", Vector(Field), fieldEncode, 4),
    Operation("field add, subtract and negate", Vector(Field, Field), fieldAddSubtract, 4),
    Operation("field multiply and square", Vector(Field, Field), fieldMultiply, 4),
    Operation("field invert and powP58", Vector(Field), fieldInvert, 4),
    Operation("field sqrtRatioM1", Vector(Field, Field), fieldSquareRoot, 4),
    Operation("scalar reduce and canonical decode", Vector(Scalar32), scalarReduce, 4),
    Operation("scalar wide reduce", Vector(Wide), scalarReduceWide, 4),
    Operation("scalar ring operations", Vector(Scalar32, Scalar32, Scalar32), scalarRing, 4),
    Operation("scalar digits", Vector(Scalar32), scalarDigits, 4),
    Operation("point group law", Vector(Point, Point), groupLaw, 2),
    Operation("variable-base multiplication", Vector(Point, Scalar32), variableBase, 1),
    Operation(
      "variable-base multiplication, the model",
      Vector(Point, Scalar32),
      variableBaseModel,
      1
    ),
    Operation("fixed-base multiplication", Vector(Scalar32), fixedBase, 1),
    Operation("fixed-base multiplication, the model", Vector(Scalar32), fixedBaseModel, 1),
    Operation("double-base multiplication", Vector(Point, Scalar32, Scalar32), doubleBase, 1),
    Operation("prime-order test", Vector(Point), order, 1)
  )

  /** Byte 0 of an input modulo this table's size chooses its operation. */
  private val slots: Vector[Operation] = operations.flatMap(o => Vector.fill(o.weight)(o))

  /** Operand bytes the seeds are made of: each mode, with its payload zero, all ones, alternating
    * bits and counting up.
    */
  private def operandSeeds(operand: Operand): Vector[Array[Byte]] =
    val patterns: Vector[Int => Int] = Vector(_ => 0, _ => 0xff, _ => 0x55, i => i)
    val modes = operand match
      case Field      => 5
      case FieldBytes => 3
      case _          => 4
    for
      mode <- (0 until modes).toVector
      pattern <- patterns
    yield (mode +: (1 until operand.size).map(pattern)).map(_.toByte).toArray

  /** For each slot, its operation's operands' seeds in turn after the byte that chooses it, so that
    * mutated seeds, like noise, reach each operation in proportion to its weight.
    */
  private val arithmeticSeedList: Vector[Array[Byte]] =
    slots.zipWithIndex.flatMap { (operation, slot) =>
      val choices = operation.operands.map(operandSeeds)
      (0 until choices.map(_.size).max).toVector.map { i =>
        slot.toByte +: operation.operands.indices.toArray.flatMap(j =>
          choices(j)((i + j + slot) % choices(j).size)
        )
      }
    }

  private val arithmeticName = "curve25519-arithmetic"

  private def runArithmetic(bytes: Array[Byte]): Unit =
    val operation = slots(unsigned(bytes, 0) % slots.size)
    if operation.run(bytes, operation.offsets, new Check(operation.name)) then hit(arithmeticName)

  // The first call of each operation loads the oracles' and the vendored classes and builds their
  // tables, more than the allocation bound of one input allows and not what it is for. The corpus is
  // read before any input is probed, so it runs each operation once on its first seed, outside the
  // probe; the outcome is the probed runs' to report.
  private lazy val arithmeticWarmed: Vector[Array[Byte]] =
    operations
      .flatMap(o => arithmeticSeedList.find(s => slots(unsigned(s, 0) % slots.size) == o))
      .foreach(seed => scala.util.Try(runArithmetic(seed)))
    arithmeticSeedList

  /** The vendored field, scalar and point arithmetic against exact integer arithmetic and the model
    * of RFC 8032. Byte 0 chooses the operation; its operands follow.
    */
  private val arithmetic: Target = Target(
    arithmeticName,
    () => arithmeticWarmed,
    in => Probe.run(arithmeticName, in)(runArithmetic(in.bytes))
  )

  // --- Ed25519 verification ---------------------------------------------------------------------

  /** A key, a signature and a message, and whether their construction makes A and R points of the
    * prime-order subgroup and A not the identity: for such a triple Bouncy Castle refuses neither,
    * and its cofactored equation is the exact one.
    */
  private final case class Triple(
      key: Array[Byte],
      signature: Array[Byte],
      message: Array[Byte],
      primeOrder: Boolean
  ):
    override def toString: String =
      s"key ${hex(key)} signature ${hex(signature)} message ${hex(message)}"

  private def publicKey(seed: Array[Byte]): Array[Byte] =
    val key = new Array[Byte](32)
    Ed25519.generatePublicKey(seed, 0, key, 0)
    key

  private def signed(seed: Array[Byte], message: Array[Byte]): Triple =
    val signature = new Array[Byte](64)
    Ed25519.sign(seed, 0, message, 0, message.length, signature, 0)
    Triple(publicKey(seed), signature, message, true)

  private def flipped(bytes: Array[Byte], bit: Int): Array[Byte] =
    val out = bytes.clone()
    out(bit / 8) = (out(bit / 8) ^ (1 << (bit % 8))).toByte
    out

  /** y = p + delta for delta 0 to 18, each sign, and x = 0 with the sign bit set: the encodings
    * that only a reading of y modulo p, or a sign ignored, decodes.
    */
  private val aliases: Vector[Array[Byte]] =
    ((0 to 18).toVector.flatMap(delta => Vector(G.p + delta, (G.p + delta).setBit(255))) ++
      Vector(M.identity, M.torsion(4)).map(_.affineY.setBit(255))).map(G.le(_, 32))

  private def le32(v: BigInt): Array[Byte] = G.le(v.mod(G.L), 32)

  /** s + j L, j lowered to the largest that keeps the sum below 2^256 (15 L < 2^256 < 16 L). */
  private def plusMultipleOfL(s: BigInt, j: Int): Array[Byte] =
    G.le(s + BigInt(j).min(((BigInt(1) << 256) - 1 - s) / G.L) * G.L, 32)

  /** A signature valid for the key its seed derives (Bouncy Castle's signer), then changed as the
    * mutation byte says: unchanged, a bit flipped in A, R, S or the message, S + j L, R a point of
    * small order, or A an encoding only a lenient decoding reads.
    */
  private def mutatedSignature(in: Array[Byte]): Triple =
    val valid = signed(window(in, 1, 32), in.drop(36))
    val position = unsigned(in, 34) | unsigned(in, 35) << 8
    val (r, s) = (valid.signature.take(32), valid.signature.drop(32))
    unsigned(in, 33) % 8 match
      case 0 => valid
      case 1 => valid.copy(key = flipped(valid.key, position % 256), primeOrder = false)
      case 2 => valid.copy(signature = flipped(valid.signature, position % 256), primeOrder = false)
      case 3 => valid.copy(signature = flipped(valid.signature, 256 + position % 256))
      case 4 =>
        if valid.message.isEmpty then valid
        else valid.copy(message = flipped(valid.message, position % (8 * valid.message.length)))
      case 5 => valid.copy(signature = r ++ plusMultipleOfL(G.fromLe(s), 1 + position % 15))
      case 6 =>
        valid.copy(
          signature = M.torsion(position & 7).encode ++ s,
          primeOrder = (position & 7) == 0
        )
      case _ => valid.copy(key = aliases(position % aliases.size), primeOrder = false)

  /** The families of `Ed25519CrossCheckSuite`, built with the model from two signing seeds (A =
    * [a]B and R = [r]B by Bouncy Castle's key derivation): A, R or both of mixed order, A or R of
    * small order, A or R an alias, and S + j L. The signature is S = r + k a for the message as
    * given, so whether a family's torsion part satisfies the equation is the message's choice.
    */
  private def family(in: Array[Byte]): Triple =
    val (seedA, seedR) = (window(in, 2, 32), window(in, 34, 32))
    val (a, r) = (M.secretScalar(seedA), M.secretScalar(seedR))
    val (aB, rB) = (publicKey(seedA), publicKey(seedR))
    val index = unsigned(in, 66)
    val message = in.drop(67)
    def mixed(encoding: Array[Byte], i: Int): Array[Byte] =
      (M.decode(encoding, M.Reading.Strict).get + M.torsion(i & 7)).encode
    def response(key: Array[Byte], rEncoding: Array[Byte], nonce: BigInt, scale: BigInt) =
      le32(nonce + M.challenge(rEncoding, key, message) * scale)
    unsigned(in, 1) % 8 match
      case 0 =>
        val key = mixed(aB, index)
        Triple(key, rB ++ response(key, rB, r, a), message, (index & 7) == 0)
      case 1 =>
        val rEncoding = mixed(rB, index)
        Triple(aB, rEncoding ++ response(aB, rEncoding, r, a), message, (index & 7) == 0)
      case 2 =>
        val (key, rEncoding) = (mixed(aB, index), mixed(rB, index >> 3))
        Triple(key, rEncoding ++ response(key, rEncoding, r, a), message, (index & 0x3f) == 0)
      case 3 => Triple(M.torsion(index & 7).encode, rB ++ le32(r), message, false)
      case 4 =>
        val rEncoding = M.torsion(index & 7).encode
        Triple(aB, rEncoding ++ response(aB, rEncoding, 0, a), message, (index & 7) == 0)
      case 5 => Triple(aliases(index % aliases.size), rB ++ le32(r), message, false)
      case 6 =>
        val rEncoding = aliases(index % aliases.size)
        Triple(aB, rEncoding ++ response(aB, rEncoding, 0, a), message, false)
      case _ =>
        Triple(
          aB,
          rB ++ plusMultipleOfL(G.fromLe(response(aB, rB, r, a)), 1 + index % 15),
          message,
          true
        )

  /** The triple of an input: byte 0 chooses key, signature and message as given (32, 64 and the
    * rest), a valid signature mutated, or a family of the cross-check suite.
    */
  private def triple(in: Array[Byte]): Triple =
    unsigned(in, 0) % 3 match
      case 0 => Triple(window(in, 1, 32), window(in, 33, 64), in.drop(97), false)
      case 1 => mutatedSignature(in)
      case _ => family(in)

  import Ed25519Verifiers.Jdk
  import VerificationResult.*

  /** The y of each point of small order: a y determines a point up to the sign of x, and both have
    * the same order, so an encoding read modulo p is of a point of small order exactly when its y
    * is one of these.
    */
  private val smallOrderY: Set[BigInt] = M.torsion.map(_.affineY).toSet

  /** Whether a canonical encoding (y below p) is of a point of small order. */
  private def smallOrder(encoding: Array[Byte]): Boolean =
    M.y(encoding) < G.p && smallOrderY.contains(M.y(encoding))

  /** [a]P + [b]Q in the model, its group law over one double-and-add of the bits of both. */
  private def combination(a: BigInt, p: M.Point, b: BigInt, q: M.Point): M.Point =
    val both = p + q
    (math.max(a.bitLength, b.bitLength) - 1 to 0 by -1).foldLeft(M.identity) { (acc, i) =>
      val doubled = acc + acc
      (a.testBit(i), b.testBit(i)) match
        case (true, true)   => doubled + both
        case (true, false)  => doubled + p
        case (false, true)  => doubled + q
        case (false, false) => doubled
    }

  /** The JDK's verdict where Bouncy Castle accepts, so that A and R decode and S is below L:
    * whether [S]B = R + [k]A. A refusal there is a disagreement of the JDK's with Bouncy Castle.
    */
  private def equation(key: Array[Byte], signature: Array[Byte], message: Array[Byte]): Boolean =
    Ed25519Verifiers.jdk(key, signature, message) match
      case Jdk.Verified => true
      case Jdk.Rejected => false
      case refused =>
        throw new Violation(
          s"the JDK gives $refused for key ${hex(key)} signature ${hex(signature)} " +
            s"message ${hex(message)}, which Bouncy Castle accepts"
        )

  /** Each verifier's verdict on the triple against the others' and the model's, by the rules
    * `Ed25519Verifiers` states and cites, so that only a disagreement outside them is reported.
    *
    * `StrictEd25519` against Bouncy Castle and the JDK. Bouncy Castle refuses an encoding with y >=
    * p, an R or A with no x or with x = 0 and the sign bit set, an A of small order and S >= L, and
    * otherwise decides by the cofactored equation; each of those refusals is one of Strict's, and a
    * failed cofactored equation fails the exact one, so where Bouncy Castle rejects Strict must
    * reject. Where it accepts, the exact equation [S]B = R + [k]A splits the cases: Strict must
    * accept where it holds and R is not of small order, and reject otherwise (an R of small order,
    * or a torsion part that only the cofactor removes). The equation is the JDK's verdict (it has
    * no small-order test), or Bouncy Castle's own where the triple's construction puts A and R in
    * the prime-order subgroup.
    *
    * `ByronEd25519` against the same: it refuses S >= 2^253 and an A that no reading of y modulo p
    * decodes, and otherwise decides by the exact equation with S reduced modulo L, R never decoded.
    * It accepts what Strict accepts. Where Strict refuses for a reason Byron does not have (S >= L,
    * A not canonical, A or R of small order), the equation decides: the verdict on R || (S mod L)
    * of the JDK (or of Bouncy Castle, as above) for an A they decode, and the model's for an A only
    * a reading modulo p decodes. For any other triple Strict's refusal is Byron's.
    *
    * Whether `StrictEd25519` accepted.
    */
  private def verdicts(t: Triple, check: Check): Boolean =
    val r = t.signature.take(32)
    val s = G.fromLe(t.signature.drop(32))
    val strict = Ed25519Verifiers.scarab(t.key, t.signature, t.message)
    check(strict.isRight, s"StrictEd25519 gives $strict, $t")
    val strictAccepts = strict == Right(SignatureVerified)
    val bc = Ed25519Verifiers.bc(t.key, t.signature, t.message)
    if !bc then check(!strictAccepts, s"StrictEd25519 accepts where Bouncy Castle rejects, $t")
    else
      val holds = t.primeOrder || equation(t.key, t.signature, t.message)
      if strictAccepts then
        check(holds, s"StrictEd25519 accepts where the equation fails, $t")
        check(!smallOrder(r), s"StrictEd25519 accepts an R of small order, $t")
      else
        check(
          !holds || smallOrder(r),
          s"StrictEd25519 rejects where the equation holds and R is not of small order, $t"
        )
    val byron = Ed25519Verifiers.byron(t.key, t.signature, t.message)
    check(byron.isRight, s"ByronEd25519 gives $byron, $t")
    val byronOnly =
      s >= G.L || M.y(t.key) >= G.p || smallOrderY.contains(M.y(t.key).mod(G.p)) || smallOrder(r)
    val byronAccepts =
      if (t.signature(63) & 0xe0) != 0 then false
      else if strictAccepts then true
      else if !byronOnly then false
      else
        M.decode(t.key, M.Reading.Lenient) match
          case None => false
          case Some(_) if t.primeOrder =>
            Ed25519Verifiers.bc(t.key, r ++ le32(s), t.message)
          case Some(_) if M.decode(t.key, M.Reading.Strict).isDefined =>
            // the JDK refuses an R that is not a point's canonical encoding, which Byron's byte
            // comparison never matches
            Ed25519Verifiers.jdk(t.key, r ++ le32(s), t.message) == Jdk.Verified
          case Some(a) =>
            val k = M.challenge(r, t.key, t.message)
            combination(s.mod(G.L), M.basepoint, k, -a).encode.sameElements(r)
    check(
      byron == Right(if byronAccepts then SignatureVerified else SignatureRejected),
      s"ByronEd25519 gives $byron where its rule gives " +
        s"${if byronAccepts then "acceptance" else "rejection"}, $t"
    )
    strictAccepts

  private def hexBytes(text: String): Array[Byte] =
    Bytes.fromHex(text).fold(e => throw new IllegalStateException(e.toString), _.toArray)

  /** Seeds: every twentieth row of the witness corpus (`fixtures/witness/vectors.tsv`) and the
    * Wycheproof cases (`fixtures/wycheproof-ed25519`) with a key of 32 bytes and a signature of 64,
    * as given; valid signatures with each mutation; and each family with each torsion index.
    */
  private val verificationSeeds: () => Vector[Array[Byte]] = () =>
    val raw = (key: Array[Byte], signature: Array[Byte], message: Array[Byte]) =>
      Option.when(key.length == 32 && signature.length == 64)(
        0.toByte +: (key ++ signature ++ message)
      )
    val witness = Files
      .readAllLines(Path.of("fixtures/witness/vectors.tsv"))
      .toArray(Array.empty[String])
      .toVector
      .zipWithIndex
      .collect { case (line, i) if i % 20 == 0 => line.split("\t", -1) }
      .flatMap(f => raw(hexBytes(f(1)), hexBytes(f(2)), hexBytes(f.lift(3).getOrElse(""))))
    val wycheproof = io.circe.parser
      .parse(Files.readString(Path.of("fixtures/wycheproof-ed25519/ed25519_test.json")))
      .fold(throw _, identity)
      .hcursor
      .downField("testGroups")
      .values
      .toVector
      .flatten
      .flatMap { group =>
        val c = group.hcursor
        val key = c.downField("publicKey").get[String]("pk").fold(throw _, hexBytes)
        c.downField("tests").values.toVector.flatten.flatMap { test =>
          val t = test.hcursor
          raw(
            key,
            t.get[String]("sig").fold(throw _, hexBytes),
            t.get[String]("msg").fold(throw _, hexBytes)
          )
        }
      }
    val seeds = Vector.tabulate(4)(i => Array.tabulate[Byte](32)(j => (i * 37 + j * 11 + 1).toByte))
    val messages = Vector(Array.emptyByteArray, Array[Byte](0x72), Array.fill[Byte](33)(0x5a))
    val mutations = for
      kind <- (0 until 8).toVector
      i <- seeds.indices
    yield Array[Byte](1) ++ seeds(i) ++ Array(kind, i * 77, i).map(_.toByte) ++
      messages(i % messages.size)
    val families = for
      kind <- (0 until 8).toVector
      index <- (0 until 8).toVector
    yield Array[Byte](2, kind.toByte) ++ seeds(index % 4) ++ seeds((index + 1) % 4) ++
      Array((index * 9).toByte) ++ messages(index % messages.size)
    witness ++ wycheproof ++ mutations ++ families

  private val verificationName = "ed25519-verification"

  private def runVerification(bytes: Array[Byte]): Unit =
    if verdicts(triple(bytes), new Check("Ed25519 verification")) then hit(verificationName)

  // As the arithmetic's: one seed of each construction run outside the probe, when the corpus is
  // read, loads the verifiers' and the oracles' classes.
  private lazy val verificationWarmed: Vector[Array[Byte]] =
    val seeds = verificationSeeds()
    (0 until 3)
      .flatMap(mode => seeds.find(s => unsigned(s, 0) % 3 == mode))
      .foreach(seed => scala.util.Try(runVerification(seed)))
    seeds

  /** `StrictEd25519` and `ByronEd25519` against the JDK's and Bouncy Castle's Ed25519 and the
    * model, on triples as given, valid ones mutated, and the cross-check suite's families.
    */
  private val verification: Target = Target(
    verificationName,
    () => verificationWarmed,
    in => Probe.run(verificationName, in)(runVerification(in.bytes))
  )

  val all: Vector[Target] = Vector(arithmetic, verification)
