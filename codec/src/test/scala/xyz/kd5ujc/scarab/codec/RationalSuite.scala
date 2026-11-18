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

  /** What `within` must give: `apply`'s reduction (`BigInteger.gcd`), when it fits in `bits`. */
  private def expected(n: BigInt, d: BigInt, bits: Int): Option[Rational] =
    Some(Rational(n, d)).filter(r =>
      r.numerator.abs.bitLength <= bits && r.denominator.bitLength <= bits
    )

  /** A number of at most `bits` bits, its bits drawn at random, of either sign. */
  private def signed(bits: Int): Gen[BigInt] =
    for
      length <- Gen.choose(0, bits)
      seed <- Gen.long
      negative <- Gen.prob(0.5)
      magnitude = BigInt(length, new scala.util.Random(seed))
    yield if negative then -magnitude else magnitude

  /** A positive number of exactly `bits` bits, its other bits drawn from `seed`. */
  private def exactly(bits: Int, seed: Long): BigInt =
    BigInt(bits - 1, new scala.util.Random(seed)).setBit(bits - 1)

  private val twoTo64 = BigInt(1) << 64

  /** Components of every length to a little past 64 bits, and those next to 2^64 more often. */
  private val component: Gen[BigInt] = Gen.frequency(
    4 -> signed(70),
    2 -> Gen.choose(-3L, 3L).map(twoTo64 + _),
    1 -> Gen.choose(-3L, 3L).map(-twoTo64 + _),
    1 -> signed(4)
  )

  private val factor: Gen[BigInt] =
    Gen.frequency(3 -> signed(256), 2 -> signed(4096), 1 -> signed(32768)).map(_.abs + 1)

  /** Euclid's divisions on `x` and `y`, counted without any bound. */
  @scala.annotation.tailrec
  private def divisions(x: Int, y: Int, made: Int = 0): Int =
    if y == 0 then made else divisions(y, x % y, made + 1)

  private val fibonacci: LazyList[BigInt] =
    BigInt(0) #:: BigInt(1) #:: fibonacci.zip(fibonacci.tail).map(_ + _)

  property("within is apply's reduced value when it fits in 64 bits, and nothing otherwise") {
    forAll(signed(4000), signed(4000).suchThat(_ != 0)) { (n, d) =>
      assertEquals(Rational.within(n, d, 64), expected(n, d, 64))
      val run = Rational.euclid(n.abs, d.abs, 64)
      assert(run.divisions <= 92, s"${run.divisions} divisions")
    }
  }

  property("within removes a common factor of any length, with apply's signs") {
    forAll(factor, component, component.suchThat(_ != 0)) { (g, a, b) =>
      assertEquals(
        Rational.within(g * a, g * b, 64),
        expected(a, b, 64),
        s"g of ${g.bitLength} bits"
      )
    }
  }

  property("Euclid's run divides at most 92 times, and finishes when the reduced value fits") {
    forAll(factor, component.map(_.abs), component.map(_.abs).suchThat(_ != 0)) { (g, a, b) =>
      val run = Rational.euclid(g * a, g * b, 64)
      assert(run.divisions <= 92, s"${run.divisions} divisions")
      run.gcd.foreach(found => assertEquals(found, (g * a).gcd(g * b)))
      if expected(a, b, 64).isDefined then assert(run.gcd.isDefined, s"gave up on $a / $b")
    }
  }

  test("divisions is the most Euclid's algorithm makes on numbers below 2^bits") {
    assertEquals(Rational.divisions(64), 92)
    assertEquals((fibonacci(93) < twoTo64, twoTo64 < fibonacci(94)), (true, true))
    for bits <- 1 to 8 do
      val limit = 1 << bits
      val most = (for a <- 0 until limit; b <- 1 until limit yield divisions(a, b)).max
      assertEquals(Rational.divisions(bits), most, s"$bits bits")
  }

  test("within agrees with apply on every pair to just past 2^bits, scaled, for 1 to 7 bits") {
    val factors = Seq(BigInt(1), BigInt(3), (BigInt(1) << 70) + 1, exactly(1024, 9))
    for
      bits <- 1 to 7
      limit = (1 << bits) + 2
      a <- -limit to limit
      b <- (-limit to limit).filter(_ != 0)
      g <- factors
    do
      assertEquals(
        Rational.within(g * a, g * b, bits),
        expected(a, b, bits),
        s"$bits bits: $a / $b times a factor of ${g.bitLength} bits"
      )
  }

  test("Lamé's worst case below 2^64 reduces in 92 divisions under a common factor of 100 KB") {
    val g = exactly(8 * 102400, 1)
    assertEquals(
      Rational.euclid(g * fibonacci(92), g * fibonacci(93), 64),
      Rational.Euclid(Some(g), 92)
    )
    assertEquals(
      Rational.within(g * fibonacci(92), g * fibonacci(93), 64),
      Some(Rational(fibonacci(92), fibonacci(93)))
    )
    assertEquals(Rational.within(g * fibonacci(93), g * fibonacci(94), 64), None)
  }

  test("within reads g / 2g as one half for a g of 100 KB, and gives up on two random numbers") {
    val g = exactly(8 * 102400, 2)
    assertEquals(Rational.within(g, g * 2, 64), Some(Rational(1, 2)))
    assertEquals(Rational.within(-g, g * 2, 64), Some(Rational(-1, 2)))
    val (n, d) = (exactly(8 * 102400, 3), exactly(8 * 102400, 4))
    val run = Rational.euclid(n, d, 64)
    assertEquals(run.gcd, None)
    assert(run.divisions <= 92, s"${run.divisions} divisions")
    assertEquals(Rational.within(n, d, 64), None)
    assertEquals(Rational.euclid(n, 1, 64), Rational.Euclid(None, 0))
    assertEquals(Rational.within(n, 1, 64), None)
  }

  test("within keeps the numerator's sign, gives a positive denominator, and bounds at 2^bits") {
    assertEquals(Rational.within(-6, -4, 64), Some(Rational(3, 2)))
    assertEquals(Rational.within(6, -4, 64), Some(Rational(-3, 2)))
    assertEquals(Rational.within(0, -5, 64), Some(Rational.zero))
    assertEquals(Rational.within(twoTo64 - 1, 1, 64), Some(Rational(twoTo64 - 1)))
    assertEquals(
      Rational.within(1 - twoTo64, twoTo64 - 2, 64),
      Some(Rational(1 - twoTo64, twoTo64 - 2))
    )
    assertEquals(Rational.within(twoTo64, 1, 64), None)
    assertEquals(Rational.within(1, twoTo64, 64), None)
    assertEquals(Rational.within(twoTo64 * 3, twoTo64 * 6, 64), Some(Rational(1, 2)))
  }
