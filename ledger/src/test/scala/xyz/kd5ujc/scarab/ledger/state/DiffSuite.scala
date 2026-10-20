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
  private final case class Wrapped(value: Long)
  private final case class Pair(left: Wrapped, right: Wrapped)

  private enum Tree derives Diff:
    case Leaf(value: Long)
    case Branch(left: Tree, right: Tree)

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
    given AtomicInteger = built
    // A conditional given is a factory: unlike a parameterless given, each summon constructs an
    // instance. The old dispatcher called it per comparison; the memo calls it once per derivation.
    given firstCase(using counter: AtomicInteger): Diff[Example.First] =
      val _ = counter.incrementAndGet()
      Diff.leaf(Eq.fromUniversalEquals, (a: Example.First) => a.value.toString)
    val d = Diff.derived[Example]
    assertEquals(built.get(), 0, "deriving the sum does not force its cases")
    assert(d.diff(Example.Second("x"), Example.Second("y")).isDefined)
    assertEquals(built.get(), 0, "comparing another case does not force this case")
    (0 until 100).foreach { _ =>
      assert(d.diff(first, second).isDefined, "the two cases differ")
    }
    assertEquals(built.get(), 1, "the case's instance is built once, then reused")
    val other = Diff.derived[Example]
    assertEquals(built.get(), 1, "a second derivation still initializes lazily")
    assert(other.diff(first, second).isDefined)
    assertEquals(built.get(), 2, "each derivation owns its case instance")

  test("recursive enum cases initialize lazily and retain the first nested difference"):
    val left = Tree.Branch(Tree.Leaf(1), Tree.Branch(Tree.Leaf(2), Tree.Leaf(3)))
    val equal = Tree.Branch(Tree.Leaf(1), Tree.Branch(Tree.Leaf(2), Tree.Leaf(3)))
    val right = Tree.Branch(Tree.Leaf(1), Tree.Branch(Tree.Leaf(4), Tree.Leaf(5)))
    val d = Diff[Tree]
    assertEquals(d.diff(left, equal).map(_.path.render), None)
    assertEquals(d.diff(left, right).map(_.path.render), Some("right.left.value"))
    assertEquals(d.diff(right, left).map(_.path.render), Some("right.left.value"))

  test("a product retains its field instances without forcing them during derivation"):
    val built = new AtomicInteger(0)
    given AtomicInteger = built
    given wrapped(using counter: AtomicInteger): Diff[Wrapped] =
      val _ = counter.incrementAndGet()
      Diff.leaf(Eq.fromUniversalEquals, (a: Wrapped) => a.value.toString)
    val d = Diff.derived[Pair]
    val left = Pair(Wrapped(1), Wrapped(2))
    val right = Pair(Wrapped(1), Wrapped(3))
    assertEquals(built.get(), 0)
    assertEquals(d.diff(left, left), None)
    assertEquals(built.get(), 0, "the identity fast path does not force fields")
    assertEquals(d.diff(left, Pair(Wrapped(3), Wrapped(4))).map(_.path.render), Some("left"))
    assertEquals(built.get(), 1, "the first difference does not force later fields")
    (0 until 100).foreach { _ =>
      assertEquals(d.diff(left, right).map(_.path.render), Some("right"))
    }
    assertEquals(built.get(), 2, "each field instance is made once, then reused")
