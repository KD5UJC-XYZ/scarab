// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.cost

import cats.Eval
import xyz.kd5ujc.scarab.uplc.SmallStack

/** The stream operations of `CostStream.hs`, `CostingFun/Core.hs` and `ExBudgetStream.hs` keep the
  * reference's element structure, saturate as `SatInt` does, are lazy and run in constant stack.
  */
final class CostsSuite extends munit.FunSuite:
  private def costs(values: Long*): Costs =
    Costs.of(values.map(SatInt(_))).getOrElse(fail("empty"))

  private def longs(c: Costs): Vector[Long] = c.toVector.map(_.toLong)

  /** An endless stream of `value`: only a lazy operation can take a prefix of it. */
  private def endless(value: Long): Costs = Costs.Cons(SatInt(value), Eval.later(endless(value)))

  private val Max = Long.MaxValue
  private val Min = Long.MinValue

  test("addCostStream: two single costs add up; otherwise the streams interleave from the left") {
    assertEquals(Costs.add(costs(2), costs(3)), costs(5))
    // addCostStreamGo (CostLast l) r = CostCons l r
    assertEquals(longs(Costs.add(costs(1), costs(10, 20))), Vector(1L, 10, 20))
    // CostCons a [b] with [r]: a, then r (its last), then b.
    assertEquals(longs(Costs.add(costs(1, 2), costs(9))), Vector(1L, 9, 2))
    // [a, b, c] with [x, y]: a, x, b, y (y is the last of its side), c.
    assertEquals(longs(Costs.add(costs(1, 2, 3), costs(7, 8))), Vector(1L, 7, 2, 8, 3))
  }

  test("minCostStream: emits the smaller head and puts the difference back on the other side") {
    assertEquals(Costs.min(costs(10), costs(7)), costs(7))
    // 4 = 4 and 5 = 5: [4, 5].
    assertEquals(longs(Costs.min(costs(4, 5), costs(4, 5))), Vector(4L, 5))
    // 10 > 3: emit 3, left [7]; 7 > 4: emit 4, the right side ends: [3, 4], sum 7 = min(10, 7).
    assertEquals(longs(Costs.min(costs(10), costs(3, 4))), Vector(3L, 4))
    // 3 < 10: emit 3, right [7]; 4 < 7: emit 4, the left side ends: [3, 4].
    assertEquals(longs(Costs.min(costs(3, 4), costs(10))), Vector(3L, 4))
    // 5>3 -> 3, L=[2,5] R=[3,3]; 2<3 -> 2, L=[5] R=[1,3]; 5>1 -> 1, L=[4] R=[3]; 4>3 -> 3, R ends.
    assertEquals(longs(Costs.min(costs(5, 5), costs(3, 3, 3))), Vector(3L, 2, 1, 3))
  }

  test("scaleLinearly folds the intercept into a single cost and puts it first otherwise") {
    // 100 + 7 * 3
    assertEquals(Costs.scaleLinearly(SatInt(100), SatInt(7), costs(3)), costs(121))
    assertEquals(
      longs(Costs.scaleLinearly(SatInt(100), SatInt(7), costs(3, 4))),
      Vector(100L, 21, 28)
    )
  }

  test("scaleLinearlyTwoVariables interleaves the two scaled streams after the intercept") {
    // 1 + (2 * 3 + 5 * 7) = 42
    assertEquals(
      Costs.scaleLinearlyTwoVariables(SatInt(1), SatInt(2), costs(3), SatInt(5), costs(7)),
      costs(42)
    )
    // add(Last 1, add([2*3, 2*4], [5*7])) = add(Last 1, [6, 35, 8]) = [1, 6, 35, 8]
    assertEquals(
      longs(
        Costs.scaleLinearlyTwoVariables(SatInt(1), SatInt(2), costs(3, 4), SatInt(5), costs(7))
      ),
      Vector(1L, 6, 35, 8)
    )
  }

  test("scaleQuadratically uses the running total of the costs before each one") {
    // c0 = 1, c1 = 2, c2 = 3 at x = 5: 1 + (0 + 3*5*5 + 2*5) = 1 + 85 = 86
    assertEquals(Costs.scaleQuadratically(SatInt(1), SatInt(2), SatInt(3), costs(5)), costs(86))
    // [2, 3]: f(2, r=0) = 0 + 12 + 4 = 16; f(3, r=2) = 2*3*3*2 + 3*3*3 + 2*3 = 36 + 27 + 6 = 69.
    // [1, 16, 69] sums to 86, the value at 5.
    assertEquals(
      longs(Costs.scaleQuadratically(SatInt(1), SatInt(2), SatInt(3), costs(2, 3))),
      Vector(1L, 16, 69)
    )
  }

  test("zipCostStream pairs CPU and memory, the shorter side continued with zeros") {
    assertEquals(Costs.zip(costs(5), costs(6)), CostStream.single(ExBudget.of(5, 6)))
    assertEquals(
      Costs.zip(costs(1, 2, 3), costs(9)).total,
      ExBudget.of(6, 9)
    )
    assertEquals(
      Costs.zip(costs(1, 2, 3), costs(9)).prefix(5),
      Vector(ExBudget.of(1, 9), ExBudget.of(2, 0), ExBudget.of(3, 0))
    )
    assertEquals(
      Costs.zip(costs(4), costs(7, 8)).prefix(5),
      Vector(ExBudget.of(4, 7), ExBudget.of(0, 8))
    )
    assertEquals(
      Costs.zip(costs(1, 2), costs(3, 4, 5)).prefix(5),
      Vector(ExBudget.of(1, 3), ExBudget.of(2, 4), ExBudget.of(0, 5))
    )
  }

  test("costs saturate at the Int64 bounds, in the reference's order of operations") {
    // maxBound + maxBound * 2: the product saturates, then the sum.
    assertEquals(Costs.scaleLinearly(SatInt(Max), SatInt(Max), costs(2)), costs(Max))
    assertEquals(Costs.scaleLinearly(SatInt(Min), SatInt(Min), costs(1)), costs(Min))
    // maxBound + minBound * 2 = maxBound + minBound = -1: the product saturates at minBound first.
    assertEquals(Costs.scaleLinearly(SatInt(Max), SatInt(Min), costs(2)), costs(-1))
    // The sum of a stream saturates and stays saturated only while the costs keep their sign.
    assertEquals(costs(Max, 1, -1).sum, SatInt(Max - 1))
    assertEquals(costs(Min, -1).sum, SatInt(Min))
  }

  test("every operation is lazy: a prefix of an endless stream is computed") {
    assertEquals(Costs.add(endless(1), endless(2)).take(4).map(_.toLong), Vector(1L, 2, 1, 2))
    // 3 < 5: 3, right [2, 5, ...]; 3 > 2: 2, left [1, 3, ...]; 1 < 5: 1.
    assertEquals(Costs.min(endless(3), endless(5)).take(3).map(_.toLong), Vector(3L, 2, 1))
    assertEquals(
      Costs.scaleLinearly(SatInt(9), SatInt(2), endless(1)).take(3).map(_.toLong),
      Vector(9L, 2, 2)
    )
    assertEquals(
      Costs.scaleQuadratically(SatInt(0), SatInt(0), SatInt(1), endless(1)).take(4).map(_.toLong),
      // 0, then x_i^2 + 2 x_i r with r = 0, 1, 2: 1, 3, 5
      Vector(0L, 1, 3, 5)
    )
    assertEquals(
      Costs.zip(endless(1), endless(2)).prefix(3),
      Vector.fill(3)(ExBudget.of(1, 2))
    )
  }

  test("a long stream is summed, scaled and zipped in constant stack") {
    def counting(n: Int): Costs =
      if n <= 1 then Costs.single(1) else Costs.Cons(SatInt(1), Eval.later(counting(n - 1)))
    val size = 1000000
    SmallStack.run {
      assertEquals(counting(size).sum, SatInt(size.toLong))
      assertEquals(
        Costs.scaleLinearly(SatInt(0), SatInt(3), Costs.add(counting(size), counting(size))).sum,
        SatInt(6L * size)
      )
      assertEquals(
        Costs.min(counting(size), counting(size)).sum,
        SatInt(size.toLong)
      )
      assertEquals(Costs.zip(counting(size), counting(size)).total, ExBudget.of(size, size))
    }
  }
