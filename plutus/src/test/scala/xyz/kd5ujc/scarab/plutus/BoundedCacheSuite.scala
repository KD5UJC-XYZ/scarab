// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import java.util.concurrent.atomic.AtomicInteger
import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
import org.scalacheck.Prop.forAll
import xyz.kd5ujc.scarab.uplc.flat.ScriptDecodeError
import xyz.kd5ujc.scarab.uplc.ledger.Unavailability

class BoundedCacheSuite extends ScalaCheckSuite:
  test("the cache keeps only what its caller allows, and what it does not keep evicts nothing") {
    type Result = Either[ScriptDecodeFailure, Int]
    val cache = BoundedCache.counted[String, Result](1)
    val calls = AtomicInteger(0)
    val retained: Result => Boolean = {
      case Left(_: ScriptDecodeFailure.Unavailable) => false
      case _                                        => true
    }
    def lookup(key: String)(value: Result): Result =
      cache.getOrElseUpdate(key, { calls.incrementAndGet(); value })(retained)
    val unavailable = ScriptDecodeFailure.Unavailable(
      BackendFailure.Uplc(Unavailability.Thrown(new IllegalStateException("injected")))
    )
    assertEquals(lookup("valid")(Right(1)), Right(1))
    assert(lookup("retry")(Left(unavailable)).isLeft)
    // An unavailable attempt did not consume the single cache slot or evict the known program.
    assertEquals(lookup("valid")(Right(2)), Right(1))
    val malformed =
      Left(ScriptDecodeFailure.Malformed(ScriptProblem.Uplc(ScriptDecodeError.ExpectedBytes)))
    assertEquals(lookup("bad")(malformed), malformed)
    assertEquals(lookup("bad")(Right(3)), malformed)
    assertEquals(lookup("retry")(Right(4)), Right(4))
    assertEquals(lookup("retry")(Right(5)), Right(4))
    assertEquals(calls.get(), 4)
  }

  private def lru(entries: Int, weight: Long): Lru[String, Int] =
    Lru.empty(Limits(entries, Weight.bytes(weight)))

  test("entries are evicted by weight, the least recently used first, however few they are") {
    val full =
      lru(entries = 512, weight = 100).put("a", 1, Weight.bytes(40)).put("b", 2, Weight.bytes(40))
    assertEquals(full.keys, Vector("a", "b"))
    assertEquals(full.weight.toLong, 80L)
    val used = full.touched("a")
    assertEquals(used.keys, Vector("b", "a"))
    val after = used.put("c", 3, Weight.bytes(40))
    assertEquals(after.keys, Vector("a", "c"), "b was the least recently used")
    assertEquals(after.weight.toLong, 80L)
    assertEquals(after.peek("b"), None)
    val heavy = after.put("d", 4, Weight.bytes(90))
    assertEquals(heavy.keys, Vector("d"), "a and c both had to go to make room")
    assertEquals(heavy.weight.toLong, 90L)
  }

  test("a value heavier than the whole bound is not kept, and evicts nothing") {
    val kept = lru(entries = 512, weight = 100).put("a", 1, Weight.bytes(60))
    val refused = kept.put("b", 2, Weight.bytes(101))
    assertEquals(refused.keys, Vector("a"))
    assertEquals(refused.peek("b"), None)
    assertEquals(refused.weight.toLong, 60L)
    val exact = kept.put("c", 3, Weight.bytes(100))
    assertEquals(exact.keys, Vector("c"))
  }

  test("a lookup leaves the key that was put in place") {
    val put = new String("key")
    val looked = new String("key")
    assert(!(put eq looked))
    val used = lru(entries = 4, weight = 100).put(put, 1, Weight.bytes(10)).touched(looked)
    assert(used.keys.head eq put, "the map holds the key it was given to keep")
    assert(used.entries.keys.head eq put)
  }

  test("weights at the top of a Long are added and evicted exactly") {
    val unbounded = Lru.empty[String, Int](Limits(2, Weight.unbounded))
    val top = unbounded.put("a", 1, Weight.unbounded)
    assertEquals(top.weight, Weight.unbounded)
    val after = top.put("b", 2, Weight.bytes(1))
    assertEquals(after.keys, Vector("b"), "a is evicted to make room for b")
    assertEquals(after.weight.toLong, 1L)
    val both = unbounded.put("a", 1, Weight.bytes(Long.MaxValue - 1)).put("b", 2, Weight.bytes(1))
    assertEquals(both.keys, Vector("a", "b"))
    assertEquals(both.weight, Weight.unbounded)
  }

  test("putting a key again replaces its value and its weight") {
    val once =
      lru(entries = 4, weight = 100).put("a", 1, Weight.bytes(30)).put("b", 2, Weight.bytes(30))
    val again = once.put("a", 5, Weight.bytes(50))
    assertEquals(again.keys, Vector("b", "a"))
    assertEquals(again.peek("a"), Some(5))
    assertEquals(again.weight.toLong, 80L)
  }

  test("the cache weighs what it keeps and stays within its weight bound") {
    val cache = BoundedCache[Int, String](
      Limits(512, Weight.bytes(1000)),
      (_, v) => Weight.bytes(v.length.toLong)
    )
    val calls = AtomicInteger(0)
    def lookup(key: Int): String =
      cache.getOrElseUpdate(key, { calls.incrementAndGet(); "x" * (100 * key) })(_ => true)
    (1 to 4).foreach(lookup(_))
    assertEquals(cache.snapshot.keys, Vector(1, 2, 3, 4))
    assertEquals(cache.snapshot.weight.toLong, 1000L)
    assertEquals(lookup(2).length, 200)
    assertEquals(calls.get(), 4, "2 was kept")
    assertEquals(lookup(5).length, 500)
    assertEquals(cache.snapshot.keys, Vector(2, 5), "1, 3 and 4 were the least recently used")
    assertEquals(cache.snapshot.weight.toLong, 700L)
    assertEquals(lookup(1).length, 100)
    assertEquals(calls.get(), 6, "1 had been evicted")
    assertEquals(cache.snapshot.keys, Vector(2, 5, 1))
  }

  /** An operation on a map: a lookup, or a put of a value of some weight. */
  private enum Op:
    case Get(key: Int)
    case Put(key: Int, value: Int, weight: Long)

  private val op: Gen[Op] = Gen.oneOf(
    Gen.choose(0, 12).map(Op.Get(_)),
    for
      key <- Gen.choose(0, 12)
      value <- Gen.choose(0, 1000)
      weight <- Gen.frequency(9 -> Gen.choose(0L, 40L), 1 -> Gen.choose(41L, 150L))
    yield Op.Put(key, value, weight)
  )

  /** The map as a list of keys, values and weights, the least recently used first. */
  private final case class Model(entries: Int, bound: Long, held: Vector[(Int, Int, Long)]):
    def apply(o: Op): Model = o match
      case Op.Get(key) =>
        held.find(_._1 == key).fold(this)(e => copy(held = held.filterNot(_._1 == key) :+ e))
      case Op.Put(key, value, weight) =>
        if entries < 1 || weight > bound then this
        else
          val added = held.filterNot(_._1 == key) :+ (key, value, weight)
          copy(held = added.drop(fits(added)))
    // the fewest of the oldest entries whose eviction brings the rest within the bounds
    private def fits(all: Vector[(Int, Int, Long)]): Int =
      (0 to all.size)
        .find(n => all.size - n <= entries && all.drop(n).map(_._3).sum <= bound)
        .getOrElse(all.size)

  property(
    "a map is the model's least-recently-used list, within its bounds after every operation"
  ) {
    forAll(Gen.choose(0, 6), Gen.choose(0L, 120L), Gen.listOf(op)) { (entries, bound, ops) =>
      val start = (
        Lru.empty[Int, Int](Limits(entries, Weight.bytes(bound))),
        Model(entries, bound, Vector.empty)
      )
      val steps = ops.scanLeft(start) { case ((map, model), o) =>
        val next = o match
          case Op.Get(key)                => map.touched(key)
          case Op.Put(key, value, weight) => map.put(key, value, Weight.bytes(weight))
        (next, model(o))
      }
      Prop.all(steps.zipWithIndex.map { case ((map, model), i) =>
        val at = s"after ${ops.take(i)}"
        Prop(
          map.keys == model.held.map(_._1)
        ) :| s"keys ${map.keys} != ${model.held.map(_._1)} $at" &&
        Prop(map.keys.map(map.peek) == model.held.map(e => Some(e._2))) :| s"values $at" &&
        Prop(map.weight.toLong == model.held.map(_._3).sum) :| s"weight ${map.weight.toLong} $at" &&
        Prop(map.size <= entries) :| s"size ${map.size} > $entries $at" &&
        Prop(map.weight.toLong <= bound) :| s"weight ${map.weight.toLong} > $bound $at"
      }*)
    }
  }
