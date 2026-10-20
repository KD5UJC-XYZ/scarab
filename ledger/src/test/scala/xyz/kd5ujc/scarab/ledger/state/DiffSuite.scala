// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.state

import java.util.concurrent.atomic.AtomicInteger
import cats.Eq
import munit.FunSuite

/** The laws of [[Diff]] — empty exactly when the values are equal, and the same path from either
  * side — and the structural property of the enum dispatch: a case's instance is built once per
  * derivation, not once per comparison.
  */
class DiffSuite extends FunSuite:
  private enum Example:
    case First(value: Long)
    case Second(text: String)

  private final case class Record(a: Long, b: String, c: Long)

  private given Diff[Record] = Diff.derived[Record]

  private val first = Example.First(1)
  private val second = Example.First(2)

  test("a difference is empty exactly when the two values are equal"):
    val d = Diff[Record]
    assertEquals(d.diff(Record(1, "x", 2), Record(1, "x", 2)), None)
    assert(d.diff(Record(1, "x", 2), Record(1, "x", 3)).isDefined)

  test("the path of a difference is the same from either side"):
    val d = Diff[Record]
    val left = d.diff(Record(1, "x", 2), Record(1, "x", 3)).map(_.path)
    val right = d.diff(Record(1, "x", 3), Record(1, "x", 2)).map(_.path)
    assertEquals(left, right)

  test("a case's Diff is built once per derivation, not once per comparison"):
    val built = new AtomicInteger(0)
    // Every construction of the case's instance increments the counter. If the sum dispatch derives
    // a case per comparison, the comparisons drive the counter; with the memo, it is one.
    given Diff[Example.First] =
      built.incrementAndGet()
      Diff.leaf(Eq.fromUniversalEquals, (a: Example.First) => a.value.toString)
    val d = Diff.derived[Example]
    var i = 0
    while i < 100 do
      assert(d.diff(first, second).isDefined, "the two cases differ")
      i += 1
    assertEquals(built.get, 1, "the case's instance is built once, then reused")
