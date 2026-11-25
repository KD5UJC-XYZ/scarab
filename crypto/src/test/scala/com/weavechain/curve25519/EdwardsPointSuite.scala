// SPDX-License-Identifier: Apache-2.0
package com.weavechain.curve25519

import org.scalacheck.Gen
import org.scalacheck.Prop.forAllNoShrink
import scala.util.Try
import xyz.kd5ujc.scarab.crypto.witness.Ed25519Model as M

import Curve25519Gen.*

/** The vendored `EdwardsPoint`, `CompressedEdwardsY` and `EdwardsBasepointTable` against the
  * independent model of RFC 8032 (`Ed25519Model`): decoding and encoding, the group law, the three
  * scalar multiplications (constant-time variable base, the basepoint table, and the variable-time
  * double multiplication the verifiers use), the cofactor and the order tests. Points are drawn as
  * [a]B plus each of the eight torsion points, so mixed-order and small-order points are as common
  * as points of the prime-order subgroup; every point an operation returns is also checked against
  * the invariants of extended coordinates.
  */
class EdwardsPointSuite extends munit.ScalaCheckSuite:
  override def scalaCheckTestParameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(200)

  private def value(fe: FieldElement): BigInt = fromLe(fe.toByteArray)

  private def decompressed(encoding: Array[Byte]): Option[EdwardsPoint] =
    // an invalid encoding is an InvalidEncodingException; any other exception fails the test
    Try(new CompressedEdwardsY(encoding.clone()).decompress()).toEither match
      case Right(point)                      => Some(point)
      case Left(_: InvalidEncodingException) => None
      case Left(other)                       => throw other

  private def point(m: M.Point): EdwardsPoint = decompressed(m.encode).get

  private def encoding(e: EdwardsPoint): Vector[Byte] = e.compress().toByteArray.toVector
  private def encoding(m: M.Point): Vector[Byte] = m.encode.toVector

  /** X Y = Z T and -X^2 + Y^2 = Z^2 + d T^2, the point being (X/Z, Y/Z) on the curve. */
  private def extended(e: EdwardsPoint, what: String): Unit =
    val (x, y, z, t) = (value(e.X), value(e.Y), value(e.Z), value(e.T))
    assert(z != 0, s"$what: Z is zero")
    assertEquals((x * y - z * t).mod(p), BigInt(0), s"$what: X Y = Z T")
    assertEquals((-x * x + y * y - z * z - M.d * t * t).mod(p), BigInt(0), s"$what: curve equation")

  private val modelPoint: Gen[M.Point] =
    Gen.frequency(
      4 -> Gen.zip(canonicalScalar, Gen.choose(0, 7)).map((a, i) => M.basepoint * a + M.torsion(i)),
      3 -> canonicalScalar.map(M.basepoint * _),
      1 -> Gen.oneOf(M.torsion),
      1 -> Gen.const(M.basepoint)
    )

  /** Encodings at the edges of the y range, both signs, and of points of every order. */
  private val edgeEncoding: Gen[Array[Byte]] =
    val ys = (BigInt(0) until 24).toVector ++ (BigInt(-24) until 24).map(p + _) ++
      Vector((BigInt(1) << 255) - 1, (BigInt(1) << 254), (p - 1) / 2) ++ M.torsion.map(_.affineY)
    val raw =
      Gen.zip(Gen.oneOf(ys.filter(y => y >= 0 && y < (BigInt(1) << 255))), Gen.oneOf(0, 1)).map {
        (y, s) =>
          le(if s == 1 then y.setBit(255) else y, 32)
      }
    Gen.frequency(
      3 -> raw,
      2 -> modelPoint.map(_.encode),
      2 -> uniform(256).map(le(_, 32))
    )

  test("the basepoint and the identity are those of RFC 8032") {
    assertEquals(encoding(Constants.ED25519_BASEPOINT), encoding(M.basepoint), "basepoint")
    assertEquals(encoding(EdwardsPoint.IDENTITY), encoding(M.identity), "identity")
    extended(Constants.ED25519_BASEPOINT, "basepoint")
    assert(Constants.ED25519_BASEPOINT.isTorsionFree(), "the basepoint has prime order")
  }

  property(
    "decompress reads y modulo p with the sign as written, and compress writes the canonical encoding"
  ) {
    // Lenient as libsodium's ge25519_frombytes is: a non-canonical y and x = 0 with the sign bit set
    // decode. StrictEd25519 refuses both itself (canonicalY and the small-order test).
    forAllNoShrink(edgeEncoding) { e =>
      val expected = M.decode(e, M.Reading.Lenient)
      val got = decompressed(e)
      assertEquals(got.isDefined, expected.isDefined, s"decodes ${fromLe(e).toString(16)}")
      got.zip(expected).foreach { (g, m) =>
        assertEquals(encoding(g), encoding(m), s"point of ${fromLe(e).toString(16)}")
        extended(g, s"decompress ${fromLe(e).toString(16)}")
        if M.decode(e, M.Reading.Strict).isDefined then
          assertEquals(encoding(g), e.toVector, "a canonical encoding is compressed back unchanged")
      }
    }
  }

  property("add, subtract, negate and dbl are the group law") {
    forAllNoShrink(modelPoint, modelPoint) { (m, n) =>
      val (a, b) = (point(m), point(n))
      val results = Vector(
        "add" -> (a.add(b), m + n),
        "subtract" -> (a.subtract(b), m - n),
        "negate" -> (a.negate(), -m),
        "dbl" -> (a.dbl(), m + m)
      )
      results.foreach { case (name, (got, model)) =>
        assertEquals(encoding(got), encoding(model), name)
        extended(got, name)
      }
      assertEquals(encoding(a.add(b)), encoding(b.add(a)), "commutative")
      assertEquals(encoding(a.add(EdwardsPoint.IDENTITY)), encoding(a), "identity")
      assertEquals(encoding(a.add(a.negate())), encoding(EdwardsPoint.IDENTITY), "inverse")
      assertEquals(encoding(a.dbl()), encoding(a.add(a)), "dbl is add to itself")
    }
  }

  property("addition is associative") {
    forAllNoShrink(modelPoint, modelPoint, modelPoint) { (l, m, n) =>
      val (a, b, c) = (point(l), point(m), point(n))
      assertEquals(encoding(a.add(b).add(c)), encoding(a.add(b.add(c))))
    }
  }

  property("multiply is [s]P for every s below 2^255, on points of every order") {
    forAllNoShrink(modelPoint, scalar255) { (m, s) =>
      val got = point(m).multiply(Scalar.fromBits(le(s, 32)))
      assertEquals(encoding(got), encoding(m * s), s"[${s.toString(16)}]P")
      extended(got, "multiply")
    }
  }

  property("the basepoint table gives [s]B for every s below 2^255") {
    forAllNoShrink(scalar255) { s =>
      val got = Constants.ED25519_BASEPOINT_TABLE.multiply(Scalar.fromBits(le(s, 32)))
      assertEquals(encoding(got), encoding(M.basepoint * s), s"[${s.toString(16)}]B")
      extended(got, "basepoint table")
    }
  }

  property("vartimeDoubleScalarMultiplyBasepoint is [a]A + [b]B, on points of every order") {
    forAllNoShrink(modelPoint, scalar255, scalar255) { (m, a, b) =>
      val got = EdwardsPoint.vartimeDoubleScalarMultiplyBasepoint(
        Scalar.fromBits(le(a, 32)),
        point(m),
        Scalar.fromBits(le(b, 32))
      )
      assertEquals(
        encoding(got),
        encoding(m * a + M.basepoint * b),
        s"[${a.toString(16)}]A + [${b.toString(16)}]B"
      )
      extended(got, "double multiplication")
    }
  }

  property("multiplyByCofactor, isSmallOrder, isTorsionFree and isIdentity are the model's") {
    forAllNoShrink(modelPoint) { m =>
      val a = point(m)
      assertEquals(encoding(a.multiplyByCofactor()), encoding(m * 8), "multiplyByCofactor")
      assertEquals(a.isSmallOrder(), m.isSmallOrder, "isSmallOrder")
      assertEquals(a.isTorsionFree(), m.inPrimeSubgroup, "isTorsionFree")
      assertEquals(a.isIdentity(), m.isIdentity, "isIdentity")
    }
  }

  property("equality, hashing and ctSelect are by the point, not its coordinates") {
    forAllNoShrink(modelPoint, modelPoint) { (m, n) =>
      val (a, b) = (point(m), point(n))
      // the same point with Z = 2: (2X : 2Y : 2 : 2T)
      val two = FieldElement.ONE.add(FieldElement.ONE)
      val scaled = new EdwardsPoint(a.X.multiply(two), a.Y.multiply(two), two, a.T.multiply(two))
      assertEquals(a.equals(scaled), true, "equals across representations")
      assertEquals(a.hashCode(), scaled.hashCode(), "hashCode across representations")
      assertEquals(a.equals(b), m.same(n), "equals")
      assertEquals(a.ctEquals(b), if m.same(n) then 1 else 0, "ctEquals")
      assertEquals(encoding(a.ctSelect(b, 0)), encoding(m), "ctSelect 0")
      assertEquals(encoding(a.ctSelect(b, 1)), encoding(n), "ctSelect 1")
    }
  }

  test("the eight torsion points have the orders 1, 8, 4, 8, 2, 8, 4, 8") {
    assertEquals(M.torsion.map(_.smallOrder), Vector(1, 8, 4, 8, 2, 8, 4, 8))
    M.torsion.zipWithIndex.foreach { (m, i) =>
      val a = point(m)
      assert(a.isSmallOrder(), s"torsion point $i is of small order")
      assertEquals(
        a.isTorsionFree(),
        i == 0,
        s"torsion point $i is torsion-free only if the identity"
      )
    }
  }
