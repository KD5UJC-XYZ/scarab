// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.codec

import cats.kernel.laws.discipline.OrderTests
import munit.DisciplineSuite
import org.scalacheck.{Arbitrary, Cogen, Gen}
import org.scalacheck.Prop.*

class RationalSuite extends DisciplineSuite:
  given Arbitrary[Rational] = Arbitrary(
    for
      n <- Gen.choose(-1000000L, 1000000L)
      d <- Gen.choose(1L, 100000L)
    yield Rational(n, d)
  )
  given Cogen[Rational] = Cogen[(BigInt, BigInt)].contramap(r => (r.numerator, r.denominator))

  checkAll("Rational", OrderTests[Rational].order)

  property("floor rounds towards negative infinity, like Haskell") {
    forAll { (r: Rational) =>
      val f = r.floor
      assert(Rational(f) <= r && r < Rational(f + 1))
    }
  }

  property("field operations are exact") {
    forAll { (a: Rational, b: Rational) =>
      assertEquals(a + b - b, a)
      if !b.isZero then assertEquals(a * b / b, a)
    }
  }

  test("decimal conversion is exact") {
    assertEquals(Rational.fromDecimal(BigDecimal("0.05")), Rational(1, 20))
    assertEquals(Rational.fromDecimal(BigDecimal("7.21e-05")), Rational(721, 10000000))
    assertEquals(Rational.fromDecimal(BigDecimal("1E+2")), Rational(100))
  }
