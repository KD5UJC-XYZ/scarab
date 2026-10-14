// SPDX-License-Identifier: Apache-2.0
package lab.node.ledger

import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
import org.scalacheck.Prop.*

/** The pure view of a stored table (roadmap D1): against a `Map` model, with the counters kept
  * incrementally, and the contract of a read the view cannot answer. The store interpreters are
  * tested in `LedgerTablesSuite`.
  */
class TableSuite extends ScalaCheckSuite:
  private val space = Table.Space("t")

  // a table of (Int -> BigInt) where the weights are the value and twice the value
  private given Weigh[BigInt] = v => (v, v * 2)

  private enum Op:
    case Put(k: Int, v: Int)
    case Del(k: Int)
    case With(k: Int, v: Option[Int])
    case Stamp(n: Int)

  private val genOp: Gen[Op] = Gen.frequency(
    5 -> (for k <- Gen.choose(0, 8); v <- Gen.choose(0, 100) yield Op.Put(k, v)),
    3 -> Gen.choose(0, 8).map(Op.Del(_)),
    2 -> (for k <- Gen.choose(0, 8); v <- Gen.option(Gen.choose(0, 100)) yield Op.With(k, v)),
    1 -> Gen.choose(0, 50).map(Op.Stamp(_))
  )

  private def applyTable(t: Table[Int, BigInt], op: Op) = op match
    case Op.Put(k, v)  => t.updated(k, BigInt(v))
    case Op.Del(k)     => t - k
    case Op.With(k, v) => t.updatedWith(k)(_ => v.map(BigInt(_)))
    case Op.Stamp(n)   => t.at(n.toLong)

  private def applyModel(m: Map[Int, BigInt], op: Op) = op match
    case Op.Put(k, v)  => m.updated(k, BigInt(v))
    case Op.Del(k)     => m - k
    case Op.With(k, v) => v.fold(m - k)(x => m.updated(k, BigInt(x)))
    case Op.Stamp(_)   => m

  property("a table built in memory answers like a Map, and its counters are exact") {
    forAll(Gen.listOf(genOp)) { ops =>
      val t = ops.foldLeft(Table.empty[Int, BigInt](space))(applyTable)
      val m = ops.foldLeft(Map.empty[Int, BigInt])(applyModel)
      (t.toMap ?= m) &&
      (t.count ?= m.size.toLong) &&
      (t.sumA ?= m.valuesIterator.sum) &&
      (t.sumB ?= m.valuesIterator.sum * 2) &&
      (0 to 9).forall(k => t.get(k) == m.get(k) && t.contains(k) == m.contains(k))
    }
  }

  property("flushing what changed and trimming it keeps the rows, once they are fetched") {
    forAll(Gen.listOf(genOp), Gen.choose(0, 50)) { (ops, upTo) =>
      val t = ops.foldLeft(Table.empty[Int, BigInt](space))(applyTable)
      val m = ops.foldLeft(Map.empty[Int, BigInt])(applyModel)
      // the store takes every change up to `upTo` (what is newer stays in the diff)
      val stored = t.diff.collect { case (k, Table.Change(Some(v), s)) if s <= upTo => k -> v }
      val trimmed = t.settled(upTo.toLong, force = true)
      // without the stored rows a read that the diff does not decide is a miss...
      val misses = (0 to 8).filter(k => Table.guarded(trimmed.get(k)).isLeft)
      // ...and once the rows are fetched every answer is the model's
      val fetched =
        trimmed.withLoaded(stored.view.filterKeys(k => !t.diff.get(k).exists(_.stamp > upTo)).toMap)
      (fetched.toMap ?= m) && (fetched.count ?= m.size.toLong) &&
      misses.forall(k => !trimmed.diff.contains(k))
    }
  }

  test("a table with nothing flushed never misses; one that was flushed reports what it lacks") {
    val whole = Table.fromMap(space, Map(1 -> BigInt(5)))
    assertEquals(whole.get(1), Some(BigInt(5)))
    assertEquals(whole.get(2), None)
    val flushed = whole.settled(0, force = true)
    assertEquals(flushed.diff, Map.empty)
    val miss = Table.guarded(flushed.get(1)).swap.getOrElse(fail("answered"))
    assertEquals(miss.keys, Set[Any](1))
    assert(!miss.all)
    assert(Table.guarded(flushed.toMap).swap.exists(_.all))
    // a fetched absent key is known absent
    assertEquals(flushed.withRead(Map(2 -> None)).get(2), None)
    assertEquals(flushed.withRead(Map(1 -> Some(BigInt(5)))).get(1), Some(BigInt(5)))
  }

  test("changes carry the stamp of the block that made them and only newer ones are written") {
    val t = Table.empty[Int, BigInt](space).at(3).updated(1, BigInt(1)).at(5).updated(2, BigInt(2))
    assertEquals(t.changesAfter(3).toMap, Map(2 -> Some(BigInt(2))))
    assertEquals(t.changesAfter(-1).toMap.keySet, Set(1, 2))
    assertEquals(t.newestStamp(0), 5L)
    assertEquals(t.settled(3, force = true).changesAfter(-1).toMap.keySet, Set(2))
  }

  test("the logical view of tables split differently is the same") {
    val whole = Table.fromMap(space, Map(1 -> BigInt(5), 2 -> BigInt(6)), 7)
    val split = whole.settled(7, force = true).withLoaded(Map(1 -> BigInt(5), 2 -> BigInt(6)))
    assertEquals(split.logical, whole.logical)
  }
