// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.*
import scala.concurrent.duration.*

class SnapshotPolicySuite extends ScalaCheckSuite:
  import SnapshotPolicy.*

  private val k = BigInt(2160)
  private val reference = SnapshotPolicy.reference(k)
  private def facts(
      previous: Long,
      slot: Long,
      last: Long = 0,
      since: Option[FiniteDuration] = None
  ) =
    Facts(previous, slot, last, since)

  test("the reference defaults: 40 k slots, a 10 minute rate limit, 2 snapshots kept") {
    assertEquals(reference, Aligned(86400, 10.minutes))
    assertEquals(SnapshotPolicy.ReferenceKeep, 2)
  }

  test("Aligned: due exactly when the tip crosses a multiple of the interval") {
    assert(!reference.due(facts(1, 2)))
    assert(!reference.due(facts(86398, 86399)))
    assert(reference.due(facts(86399, 86400)))
    assert(reference.due(facts(86399, 90000)))
    assert(!reference.due(facts(86400, 86401)))
    assert(reference.due(facts(86400, 172800)))
  }

  test("Aligned: the rate limit skips a crossing that comes too soon, and does not postpone it") {
    assert(!reference.due(facts(86399, 86400, since = Some(9.minutes + 59.seconds))))
    assert(reference.due(facts(86399, 86400, since = Some(10.minutes))))
    assert(reference.due(facts(86399, 86400, since = None)))
    // the next block is not a crossing: the skipped boundary is gone
    assert(!reference.due(facts(86400, 86401, since = Some(20.minutes))))
  }

  property("Aligned: never due without a crossing, whatever the time") {
    forAll(Gen.choose(0L, 1000000L), Gen.choose(0L, 100L), Gen.choose(0L, 3600L)) {
      (previous, step, seconds) =>
        val slot = previous + step
        val crossed = BigInt(slot) / 86400 > BigInt(previous) / 86400
        val decision = reference.due(facts(previous, slot, since = Some(seconds.seconds)))
        assertEquals(decision, crossed && seconds >= 600)
    }
  }

  property(
    "Aligned: a bulk sync (a block a slot, 12 hours of replay per minute) snapshots at most once per rate limit"
  ) {
    // 1 slot per block, 2,000 blocks per second of wall time: 86,400 slots a crossing every 43 s
    forAll(Gen.choose(1, 50)) { minutes =>
      val perSecond = 2000
      val seconds = minutes * 60
      val taken = (1 to seconds * perSecond by 1000)
        .foldLeft((Option.empty[Int], 0)) { case ((last, count), slot) =>
          val now = slot / perSecond
          val due = reference.due(
            facts(slot - 1000, slot, since = last.map(l => (now - l).seconds))
          )
          if due then (Some(now), count + 1) else (last, count)
        }
        ._2
      assert(taken <= minutes / 10 + 1, s"$taken snapshots in $minutes minutes")
    }
  }

  test("EverySlots: the override, by distance from the last snapshot, whatever the time") {
    val every = EverySlots(21600)
    assert(!every.due(facts(21598, 21599, last = 0)))
    assert(every.due(facts(21599, 21600, last = 0, since = Some(1.second))))
    assert(!every.due(facts(30000, 30001, last = 21600)))
    assert(every.due(facts(43199, 43200, last = 21600)))
  }
