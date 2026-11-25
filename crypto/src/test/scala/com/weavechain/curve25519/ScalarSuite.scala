// SPDX-License-Identifier: Apache-2.0
package com.weavechain.curve25519

import org.scalacheck.Prop.forAllNoShrink
import scala.util.Try

import Curve25519Gen.*

/** The vendored `Scalar` against exact arithmetic modulo L = 2^252 +
  * 27742317777372353535851937790883648493 on `BigInt`: reduction of 256- and 512-bit inputs, the
  * canonical check, the ring operations, and the radix-16 and NAF digits the point multiplications
  * read. Inputs are weighted toward 0, L and its multiples, 2^252 to 2^256 and the boundaries of
  * the 21-bit limbs of `Scalar` and the 29-bit limbs of `UnpackedScalar`.
  */
class ScalarSuite extends munit.ScalaCheckSuite:
  override def scalaCheckTestParameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(1000)

  private def value(s: Scalar): BigInt = fromLe(s.toByteArray)

  private def canonical(v: BigInt): Scalar = Scalar.fromCanonicalBytes(le(v, 32))

  /** Any value below 2^255, the domain of `Scalar`'s representation, reduced or not. */
  private def unreduced(v: BigInt): Scalar = Scalar.fromBits(le(v, 32))

  property("fromBytesModOrder reduces any 256-bit input modulo L") {
    forAllNoShrink(scalar256) { v =>
      assertEquals(value(Scalar.fromBytesModOrder(le(v, 32))), v.mod(L), v.toString(16))
    }
  }

  property("fromBytesModOrderWide reduces any 512-bit input modulo L") {
    forAllNoShrink(wide) { v =>
      assertEquals(value(Scalar.fromBytesModOrderWide(le(v, 64))), v.mod(L), v.toString(16))
    }
  }

  property("fromCanonicalBytes accepts exactly the values below L") {
    forAllNoShrink(scalar256) { v =>
      val parsed = Try(Scalar.fromCanonicalBytes(le(v, 32))).toEither
      if v < L then assertEquals(parsed.map(value), Right(v), v.toString(16))
      else
        parsed match
          case Left(_: IllegalArgumentException) => ()
          case other => fail(s"${v.toString(16)} is at least L and was read as $other")
    }
  }

  property("fromBits keeps the low 255 bits, and reduce and isCanonical read them modulo L") {
    forAllNoShrink(scalar256) { v =>
      val low = v & ((BigInt(1) << 255) - 1)
      val s = Scalar.fromBits(le(v, 32))
      assertEquals(value(s), low, "fromBits")
      assertEquals(value(s.reduce()), low.mod(L), "reduce")
      assertEquals(s.isCanonical(), low < L, "isCanonical")
    }
  }

  property("add and subtract of canonical scalars are canonical, modulo L") {
    forAllNoShrink(canonicalScalar, canonicalScalar) { (a, b) =>
      assertEquals(value(canonical(a).add(canonical(b))), (a + b).mod(L), "add")
      assertEquals(value(canonical(a).subtract(canonical(b))), (a - b).mod(L), "subtract")
    }
  }

  property("multiply, square and multiplyAndAdd of canonical scalars are exact modulo L") {
    forAllNoShrink(canonicalScalar, canonicalScalar, canonicalScalar) { (a, b, c) =>
      val (x, y, z) = (canonical(a), canonical(b), canonical(c))
      assertEquals(value(x.multiply(y)), (a * b).mod(L), "multiply")
      assertEquals(value(x.square()), (a * a).mod(L), "square")
      assertEquals(value(x.multiplyAndAdd(y, z)), (a * b + c).mod(L), "multiplyAndAdd")
    }
  }

  property("multiplyAndAdd reduces unreduced operands below 2^255 too") {
    // The VRF prover's response c x + k (Draft03Prover) takes c from fromBits.
    forAllNoShrink(scalar255, scalar255, scalar255) { (a, b, c) =>
      assertEquals(
        value(unreduced(a).multiplyAndAdd(unreduced(b), unreduced(c))),
        (a * b + c).mod(L),
        s"${a.toString(16)} * ${b.toString(16)} + ${c.toString(16)}"
      )
    }
  }

  property("square, invert and divide reduce unreduced operands below 2^255 too") {
    forAllNoShrink(scalar255, scalar255) { (a, b) =>
      assertEquals(value(unreduced(a).square()), (a * a).mod(L), s"square of ${a.toString(16)}")
      assertEquals(value(unreduced(a).invert()), a.modPow(L - 2, L), s"invert of ${a.toString(16)}")
      assertEquals(
        value(unreduced(b).divide(unreduced(a))),
        (b * a.modPow(L - 2, L)).mod(L),
        s"${b.toString(16)} / ${a.toString(16)}"
      )
    }
  }

  test("the reductions and digits at the largest inputs they take") {
    val ones32 = (BigInt(1) << 256) - 1
    val ones64 = (BigInt(1) << 512) - 1
    val top = (BigInt(1) << 255) - 1
    assertEquals(
      value(Scalar.fromBytesModOrder(le(ones32, 32))),
      ones32.mod(L),
      "fromBytesModOrder"
    )
    assertEquals(
      value(Scalar.fromBytesModOrderWide(le(ones64, 64))),
      ones64.mod(L),
      "fromBytesModOrderWide"
    )
    assertEquals(value(unreduced(top).reduce()), top.mod(L), "reduce")
    assertEquals(
      value(unreduced(top).multiplyAndAdd(unreduced(top), unreduced(top))),
      (top * top + top).mod(L),
      "multiplyAndAdd"
    )
    assertEquals(value(unreduced(top).square()), (top * top).mod(L), "square")
    val radix = unreduced(top).toRadix16()
    assertEquals(radix.indices.map(i => BigInt(radix(i).toInt) << (4 * i)).sum, top, "toRadix16")
    val naf = unreduced(top).nonAdjacentForm()
    assertEquals(naf.indices.map(i => BigInt(naf(i).toInt) << i).sum, top, "nonAdjacentForm")
  }

  // Scalar.add and subtract document a + b and a - b modulo L for any two scalars, the result
  // canonical. UnpackedScalar.add and subtract correct by L once (their preconditions), so the
  // operands are reduced first: unreduced, the result could leave [0, 2^255) and the Scalar
  // constructor refused it. No caller in Scarab calls them.
  property(
    "add and subtract of any two scalars below 2^255 are a + b and a - b modulo L, as documented"
  ) {
    forAllNoShrink(scalar255, scalar255) { (a, b) =>
      val clue = s"${a.toString(16)} and ${b.toString(16)}"
      assertEquals(value(unreduced(a).add(unreduced(b))), (a + b).mod(L), s"add of $clue")
      assertEquals(value(unreduced(a).subtract(unreduced(b))), (a - b).mod(L), s"subtract of $clue")
    }
  }

  test("add and subtract at the edge: (2^255 - 1) + (L + 1), and 0 - (L + 1)") {
    val top = (BigInt(1) << 255) - 1
    assertEquals(
      value(unreduced(top).add(unreduced(L + 1))),
      (top + L + 1).mod(L),
      "(2^255 - 1) + (L + 1)"
    )
    assertEquals(
      value(Scalar.ZERO.subtract(unreduced(L + 1))),
      (-L - 1).mod(L),
      "0 - (L + 1)"
    )
  }

  property("invert and divide are the inverse modulo L") {
    forAllNoShrink(canonicalScalar.filter(_ != 0), canonicalScalar) { (a, b) =>
      assertEquals(value(canonical(a).invert()), a.modPow(L - 2, L), "invert")
      assertEquals(value(canonical(a).multiply(canonical(a).invert())), BigInt(1), "a * a^-1")
      assertEquals(
        value(canonical(b).divide(canonical(a))),
        (b * a.modPow(L - 2, L)).mod(L),
        "divide"
      )
    }
  }

  property("toRadix16 writes digits in [-8, 8) that sum to the scalar") {
    forAllNoShrink(scalar255) { v =>
      val digits = unreduced(v).toRadix16()
      assertEquals(digits.length, 64)
      digits.indices.foreach { i =>
        val upper = if i == 63 then 8 else 7
        assert(
          digits(i) >= -8 && digits(i) <= upper,
          s"digit $i is ${digits(i)} for ${v.toString(16)}"
        )
      }
      val sum = digits.indices.map(i => BigInt(digits(i).toInt) << (4 * i)).sum
      assertEquals(sum, v, v.toString(16))
    }
  }

  // vartimeDoubleScalarMultiplyBasepoint reads these digits with tables of the eight odd multiples
  // 1P to 15P (ProjectiveNielsPoint and AffineNielsPoint NafLookupTable), so the form is width 5: odd
  // digits of absolute value at most 15, each nonzero digit followed by at least four zeros.
  property("nonAdjacentForm writes a width-5 NAF of the scalar") {
    forAllNoShrink(scalar255) { v =>
      val naf = unreduced(v).nonAdjacentForm().map(_.toInt)
      assertEquals(naf.length, 256)
      naf.indices.foreach { i =>
        val d = naf(i)
        assert(d == 0 || (math.abs(d) <= 15 && d % 2 != 0), s"digit $i is $d for ${v.toString(16)}")
      }
      val nonzero = naf.indices.filter(naf(_) != 0)
      nonzero.zip(nonzero.drop(1)).foreach { (i, j) =>
        assert(j - i >= 5, s"nonzero digits $i and $j are less than 5 apart for ${v.toString(16)}")
      }
      val sum = naf.indices.map(i => BigInt(naf(i)) << i).sum
      assertEquals(sum, v, v.toString(16))
    }
  }

  /** The scalar `v` (below L) made five ways: read as canonical, reduced from v + L (256 bits) and
    * from v + 2^256 L (512 bits), reduced from its unreduced form v + L, and as v + 0.
    */
  private def representations(v: BigInt): Vector[(String, Scalar)] =
    Vector(
      "fromCanonicalBytes" -> canonical(v),
      "fromBytesModOrder(v + L)" -> Scalar.fromBytesModOrder(le(v + L, 32)),
      "fromBytesModOrderWide(v + 2^256 L)" -> Scalar.fromBytesModOrderWide(le(v + (L << 256), 64)),
      "fromBits(v + L).reduce()" -> unreduced(v + L).reduce(),
      "add(ZERO)" -> canonical(v).add(Scalar.ZERO)
    )

  property(
    "equal scalars made different ways are equal and hash alike; unequal ones are not equal"
  ) {
    forAllNoShrink(canonicalScalar, canonicalScalar) { (a, b) =>
      val first = canonical(a)
      representations(a).foreach { (how, other) =>
        assert(first ne other, s"$how makes another object")
        assertEquals(other.equals(first), true, s"equals, $how of ${a.toString(16)}")
        assertEquals(other.ctEquals(first), 1, s"ctEquals, $how of ${a.toString(16)}")
        assertEquals(other.hashCode(), first.hashCode(), s"hashCode, $how of ${a.toString(16)}")
      }
      assertEquals(canonical(a).equals(canonical(b)), a == b, "equals of two values")
      assertEquals(
        canonical(a).ctEquals(canonical(b)),
        if a == b then 1 else 0,
        "ctEquals of two values"
      )
    }
  }

  // A constant hashCode keeps the contract of equals and hashCode; this checks that the hash also
  // tells scalars apart, as Arrays.hashCode of the encoding does.
  test("hashCode tells distinct scalars apart") {
    val values = Iterator.continually(canonicalScalar.sample).flatten.distinct.take(1000).toVector
    val hashes = values.map(v => canonical(v).hashCode()).distinct
    assert(hashes.size >= 990, s"${hashes.size} distinct hashes of 1000 distinct scalars")
  }

  test("the scalar constants are those of edwards25519") {
    assertEquals(value(Constants.BASEPOINT_ORDER), L, "L")
    assertEquals(BigInt(Constants.ModMinus2), L - 2, "L - 2")
    assertEquals(value(Scalar.ZERO), BigInt(0), "ZERO")
    assertEquals(value(Scalar.ONE), BigInt(1), "ONE")
  }
