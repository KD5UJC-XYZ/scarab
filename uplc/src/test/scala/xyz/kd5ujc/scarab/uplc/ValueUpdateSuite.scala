// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc

import java.lang.management.ManagementFactory
import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.uplc.Value.{Key, Quantity}
import xyz.kd5ujc.scarab.uplc.builtin.extended.Values

/** `insertCoin` updates a value and its cached figures in time logarithmic in its size, as the
  * reference's does (`Value.hs:382-450`, plutus 1.71.0.0), and gives what packing the updated
  * entries gave before.
  */
class ValueUpdateSuite extends ScalaCheckSuite:
  /** What `body` allocates the second time it runs: the first loads and initialises classes. */
  private def allocated[A](body: => A): (A, Long) =
    body
    val meter = ManagementFactory.getThreadMXBean match
      case bean: com.sun.management.ThreadMXBean
          if bean.isThreadAllocatedMemorySupported && bean.isThreadAllocatedMemoryEnabled =>
        bean
      case _ => fail("allocation counters unavailable")
    val thread = Thread.currentThread().threadId()
    val before = meter.getThreadAllocatedBytes(thread)
    val result = body
    val after = meter.getThreadAllocatedBytes(thread)
    assert(before >= 0 && after >= before, s"the allocation counter read $before, then $after")
    (result, after - before)

  private def key(i: Int): Bytes = Bytes.wrap(Array((i >> 8).toByte, i.toByte))
  private def k(i: Int): Key = Key(key(i)).getOrElse(fail(s"key $i"))
  private def q(n: BigInt): Quantity = Quantity(n).getOrElse(fail(s"quantity $n"))

  /** Far below what repacking a value of 10,000 currencies allocates (megabytes). */
  private val Small = 64 * 1024L

  private val wide: Value =
    Value.pack(SortedMap.from((0 until 10_000).map(i => k(i) -> SortedMap(k(0) -> q(1)))))

  test("insertCoin and deleteCoin on a value of 10,000 currencies copy only a path of it") {
    val cases = Vector(
      "replace" -> (() => Values.insertCoin(key(5000), key(0), BigInt(7), wide)),
      "add a token" -> (() => Values.insertCoin(key(5000), key(1), BigInt(-7), wide)),
      "add a currency" -> (() => Values.insertCoin(key(20_000), key(0), BigInt(7), wide)),
      "delete" -> (() => Values.insertCoin(key(5000), key(0), BigInt(0), wide))
    )
    cases.foreach { (name, run) =>
      val (result, bytes) = allocated(run())
      assert(result.isRight, s"$name: $result")
      assert(bytes < Small, s"$name allocated $bytes bytes")
    }
  }

  private def figures(v: Value): (Int, Int, Int) = (v.totalSize, v.maxInnerSize, v.negativeAmounts)

  /** `insertCoin` before: the entries updated and packed again, the figures counted from them. */
  private def previous(c: Key, t: Key, amount: BigInt, v: Value): Value =
    if amount.signum == 0 then
      if v.entries.get(c).exists(_.contains(t)) then
        Value.pack(v.entries.updatedWith(c)(_.map(_.removed(t))))
      else v
    else
      val inner = v.entries.getOrElse(c, SortedMap.empty[Key, Quantity])
      Value.pack(v.entries.updated(c, inner.updated(t, q(amount))))

  private val small = Gen.choose(0, 5)
  private val amounts = Gen.oneOf(Gen.const(BigInt(0)), Gen.choose(-3L, 3L).map(BigInt(_)))
  private val values: Gen[Value] =
    Gen
      .listOf(Gen.zip(small, small, amounts))
      .map(_.foldLeft(Value.empty)((v, e) => previous(k(e._1), k(e._2), e._3, v)))

  property("a sequence of insertCoin calls gives the packed value and the figures counted afresh") {
    Prop.forAll(values, Gen.listOf(Gen.zip(small, small, amounts))) { (start, steps) =>
      steps.foldLeft(start) { case (v, (c, t, amount)) =>
        val now = Values.insertCoin(key(c), key(t), amount, v).fold(e => fail(e.toString), identity)
        val expected = previous(k(c), k(t), amount, v)
        assertEquals(now, expected, s"insert ($c, $t, $amount) into $v")
        assertEquals(figures(now), figures(Value.pack(now.entries)), s"figures of $now")
        now
      }
      ()
    }
  }
