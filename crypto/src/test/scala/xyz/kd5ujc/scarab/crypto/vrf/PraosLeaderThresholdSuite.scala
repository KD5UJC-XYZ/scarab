// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.crypto.vrf

import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.codec.Bytes

class PraosLeaderThresholdSuite extends munit.FunSuite:
  import PraosLeaderThreshold.*
  private def fraction(n: Int, d: Int): Fraction =
    Fraction.checked(n, d).fold(e => fail(e.message), identity)
  test("leader range extension is L-prefixed Blake2b256, not raw VRF output") {
    val output = Bytes(Vector.tabulate(64)(_.toByte))
    val expected =
      BigInt(1, Blake2b.hash256.hash(Bytes(Vector(0x4c.toByte) ++ output.value)).toArray)
    assertEquals(leaderValue(output), Right(expected))
    assertNotEquals(expected, BigInt(1, Blake2b.hash256.hash(output).toArray))
    assertEquals(leaderValue(Bytes.empty), Left(Error.InvalidVrfOutputLength(0)))
  }
  test("zero/whole stake and the exact f=1 reference exception") {
    val f = fraction(1, 20)
    assertEquals(check(0, fraction(0, 1), f), Right(Decision.Ineligible))
    assertEquals(check(0, fraction(1, 1), f), Right(Decision.Eligible))
    assertEquals(check(Range / 40, fraction(1, 1), f), Right(Decision.Eligible))
    assertEquals(check(Range / 10, fraction(1, 1), f), Right(Decision.Ineligible))
    assertEquals(check(Range - 1, fraction(0, 1), fraction(1, 1)), Right(Decision.Eligible))
  }
  test("fixed-point decisions agree with independent exact rational powers away from boundaries") {
    for
      (fn, fd) <- Vector((1, 20), (1, 10), (1, 2));
      (sn, sd) <- Vector((1, 1), (1, 2), (1, 3), (2, 3), (1, 10));
      i <- 0 until 100
    do
      val value = Range * i / 100
      // Mathematical inequality: (1-p)^sd > (1-f)^sn, evaluated with integer powers.
      val lhs = (Range - value).pow(sd) * BigInt(fd).pow(sn)
      val rhs = Range.pow(sd) * BigInt(fd - fn).pow(sn)
      if (lhs - rhs).abs * BigInt(10).pow(20) > rhs then
        val expected = if lhs > rhs then Decision.Eligible else Decision.Ineligible
        assertEquals(
          check(value, fraction(sn, sd), fraction(fn, fd)),
          Right(expected),
          s"f=$fn/$fd stake=$sn/$sd value=$i/100"
        )
  }
  test("fixed quantization is preserved for tiny stake; unsupported context is not accepted") {
    val tiny = Fraction.checked(1, (BigInt(1) << 64) - 1).toOption.get
    // A negative nonzero product floors to -1 E34 unit before prefix negation.
    assertEquals(check(0, tiny, tiny), Right(Decision.Eligible))
    assertEquals(check(Range / Scale, tiny, tiny), Right(Decision.Ineligible))
  }

  test("fraction errors retain inputs, including nulls and the Word64 boundary") {
    assertEquals(Fraction.checked(null, 1), Left(Error.MissingNumerator))
    assertEquals(Fraction.checked(1, null), Left(Error.MissingDenominator))
    val overWord = BigInt(1) << 64
    for (n, d) <- Vector(
        (BigInt(-1), BigInt(3)),
        (BigInt(4), BigInt(3)),
        (BigInt(0), BigInt(0)),
        (BigInt(1), BigInt(-1)),
        (BigInt(1), overWord)
      )
    do assertEquals(Fraction.checked(n, d), Left(Error.InvalidFraction(n, d)))
    assert(Fraction.checked(overWord - 1, overWord - 1).isRight)
    val reduced = Fraction.checked(2, 4).fold(e => fail(e.message), identity)
    assertEquals((reduced.numerator, reduced.denominator), (BigInt(1), BigInt(2)))
  }

  test("both leader value domains reject missing or wrong-sized output with typed causes") {
    for extract <- Vector(leaderValue, rawLeaderValue) do
      assertEquals(extract(null), Left(Error.MissingVrfOutput))
      assertEquals(extract(Bytes(null)), Left(Error.MissingVrfOutput))
      for size <- Vector(0, 63, 65) do
        assertEquals(
          extract(Bytes(Vector.fill(size)(0.toByte))),
          Left(Error.InvalidVrfOutputLength(size))
        )
    val output = Bytes(Vector.fill(64)(0xff.toByte))
    assertEquals(rawLeaderValue(output), Right(Range512 - 1))
  }

  test(
    "comparison errors distinguish missing context, invalid domains and unsupported coefficients"
  ) {
    val stake = fraction(1, 1)
    val active = fraction(1, 20)
    assertEquals(check(null, stake, active), Left(Error.MissingLeaderValue))
    assertEquals(check(0, stake, active, null), Left(Error.MissingRange))
    for range <- Vector(BigInt(0), BigInt(-1)) do
      assertEquals(check(0, stake, active, range), Left(Error.InvalidRange(range)))
    for range <- Vector(Range, Range512); value <- Vector(BigInt(-1), range) do
      assertEquals(
        check(value, stake, active, range),
        Left(Error.LeaderValueOutOfRange(value, range))
      )
    assertEquals(check(0, null, active), Left(Error.MissingStake))
    assertEquals(check(0, stake, null), Left(Error.MissingActiveSlotsCoefficient))
    for (n, d) <- Vector((0, 1), (3, 4)) do
      assertEquals(
        check(0, stake, fraction(n, d)),
        Left(Error.UnsupportedActiveSlotsCoefficient(n, d))
      )
  }
