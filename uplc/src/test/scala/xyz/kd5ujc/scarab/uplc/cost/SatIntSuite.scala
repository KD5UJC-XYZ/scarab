// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.cost

import cats.Eval
import org.scalacheck.{Gen, Prop}

/** `SatInt` is the reference's `Data.SatInt`: every operation is the exact result clamped to 64
  * bits (`plusSI`, `minusSI`, `timesSI`, `negate`, `fromInteger`, `satint/src/Data/SatInt.hs`).
  */
final class SatIntSuite extends munit.ScalaCheckSuite:
  private val edges =
    Seq(Long.MinValue, Long.MinValue + 1, -2L, -1L, 0L, 1L, 2L, Long.MaxValue - 1, Long.MaxValue)
  private val longs: Gen[Long] =
    Gen.oneOf(Gen.oneOf(edges), Gen.choose(Long.MinValue, Long.MaxValue), Gen.choose(-4096L, 4096L))

  private def model(value: BigInt): Long = SatInt.clamp(value).toLong

  property("addition, subtraction and multiplication saturate to the exact result's bound") {
    Prop.forAll(longs, longs) { (a, b) =>
      val (x, y) = (SatInt(a), SatInt(b))
      assertEquals((x + y).toLong, model(BigInt(a) + BigInt(b)), s"$a + $b")
      assertEquals((x - y).toLong, model(BigInt(a) - BigInt(b)), s"$a - $b")
      assertEquals((x * y).toLong, model(BigInt(a) * BigInt(b)), s"$a * $b")
    }
  }

  test("every pair of edge values") {
    for a <- edges; b <- edges do
      assertEquals((SatInt(a) + SatInt(b)).toLong, model(BigInt(a) + BigInt(b)), s"$a + $b")
      assertEquals((SatInt(a) - SatInt(b)).toLong, model(BigInt(a) - BigInt(b)), s"$a - $b")
      assertEquals((SatInt(a) * SatInt(b)).toLong, model(BigInt(a) * BigInt(b)), s"$a * $b")
  }

  test("negating the minimum gives the maximum, and integers outside the range clamp") {
    assertEquals((-SatInt(Long.MinValue)).toLong, Long.MaxValue)
    assertEquals(SatInt.clamp(BigInt(Long.MaxValue) + 1).toLong, Long.MaxValue)
    assertEquals(SatInt.clamp(BigInt(Long.MinValue) - 1).toLong, Long.MinValue)
  }

  test("a budget repeated n times is the saturating product, as stimes is (ExBudget.hs:203)") {
    assertEquals(ExBudget.of(23000, 100).times(3), ExBudget.of(69000, 300))
    assertEquals(ExBudget.of(Long.MaxValue / 2, -1).times(3), ExBudget.of(Long.MaxValue, -3))
    assertEquals(ExBudget.of(Long.MinValue, 1).times(2).cpu.toLong, Long.MinValue)
  }

  test("a cost stream's total adds its budgets with saturation, in order") {
    val stream = CostStream.of(ExBudget.of(Long.MaxValue, 1), ExBudget.of(1, 2))
    assertEquals(stream.total, ExBudget.of(Long.MaxValue, 3), "total")
    assertEquals(
      stream.prefix(5),
      Vector(ExBudget.of(Long.MaxValue, 1), ExBudget.of(1, 2)),
      "budgets"
    )
    assertEquals(
      CostStream.of(ExBudget.of(7, 7)),
      CostStream.single(ExBudget.of(7, 7)),
      "one budget"
    )
  }

  test("a cost stream's tail is lazy: an endless stream can be read as far as it is spent") {
    def endless(n: Long): CostStream =
      CostStream.Cons(ExBudget.of(n, 1), Eval.later(endless(n + 1)))
    assertEquals(endless(0).head, ExBudget.of(0, 1), "head")
    assertEquals(endless(0).prefix(3).map(_.cpu.toLong), Vector(0L, 1L, 2L), "prefix")
    assertEquals(endless(0).prefix(100000).size, 100000, "a long prefix in constant stack")
  }

  test("dividedBy is Data.SatInt's: zero divisor, saturated values, flooring division") {
    assertEquals(SatInt(7).dividedBy(0).toLong, Long.MaxValue, "zero divisor")
    assertEquals(SatInt(Long.MaxValue).dividedBy(2).toLong, Long.MaxValue, "maxBound unchanged")
    assertEquals(SatInt(Long.MinValue).dividedBy(2).toLong, Long.MinValue, "minBound unchanged")
    assertEquals(SatInt(7).dividedBy(2).toLong, 3L, "7 div 2")
    assertEquals(SatInt(-7).dividedBy(2).toLong, -4L, "-7 div 2 floors")
  }
