// SPDX-License-Identifier: Apache-2.0
package lab.vrf

import lab.Blake2b
import lab.cbor.Bytes

class PraosLeaderThresholdSuite extends munit.FunSuite:
  import PraosLeaderThreshold.*
  private def fraction(n: Int, d: Int): Fraction = Fraction.checked(n, d).fold(fail(_), identity)
  test("leader range extension is L-prefixed Blake2b256, not raw VRF output") {
    val output = Bytes(Vector.tabulate(64)(_.toByte))
    val expected =
      BigInt(1, Blake2b.hash256.hash(Bytes(Vector(0x4c.toByte) ++ output.value)).toArray)
    assertEquals(leaderValue(output), Right(expected))
    assertNotEquals(expected, BigInt(1, Blake2b.hash256.hash(output).toArray))
    assert(leaderValue(Bytes.empty).isLeft)
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
    assert(Fraction.checked(-1, 3).isLeft)
    assert(Fraction.checked(4, 3).isLeft)
    assert(Fraction.checked(0, 0).isLeft)
    assert(check(Range, fraction(1, 1), fraction(1, 20)).isLeft)
    assert(check(0, fraction(1, 1), fraction(0, 1)).isLeft)
    assert(check(0, fraction(1, 1), fraction(3, 4)).isLeft)
  }
