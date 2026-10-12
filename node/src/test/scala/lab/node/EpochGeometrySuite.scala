// SPDX-License-Identifier: Apache-2.0
package lab.node

import java.time.Instant
import munit.ScalaCheckSuite
import org.scalacheck.Prop.forAll
import org.scalacheck.Gen

/** Slot/epoch arithmetic across the Byron prefix and the Shelley-based eras. */
class EpochGeometrySuite extends ScalaCheckSuite:
  private val start = Instant.parse("2026-01-01T00:00:00Z")

  /** Byron: `byronEpochs` epochs of `byronSlots` one-second slots; then `epochLength` 100 ms slots.
    */
  private def geometry(byronEpochs: Int, byronSlots: Int, epochLength: Int) =
    EpochGeometry(
      start,
      100,
      epochLength,
      5,
      BigDecimal("0.2"),
      if byronEpochs == 0 then ByronSpan.Absent else ByronSpan(byronEpochs, byronSlots, 1000)
    )

  private val geometries: Gen[EpochGeometry] = for
    epochs <- Gen.choose(0, 4)
    byronSlots <- Gen.choose(10, 200)
    length <- Gen.choose(300, 5000)
  yield geometry(epochs, byronSlots, length)

  test("the Byron epoch is 50 slots, then Shelley-based epochs of 500 (the up-eras cluster)") {
    val g = geometry(1, 50, 500)
    assertEquals(
      Seq(0, 49, 50, 549, 550, 1049, 1050).map(s => g.epochOf(s)),
      Seq(0, 0, 1, 1, 2, 2, 3).map(BigInt(_))
    )
    assertEquals(Seq(0, 1, 2, 3).map(e => g.firstSlot(e)), Seq(0, 50, 550, 1050).map(BigInt(_)))
    assertEquals(g.firstShelleyEpoch, BigInt(1))
    assert(g.isByronSlot(49) && !g.isByronSlot(50))
  }

  test("without a Byron prefix (Preview) nothing changes") {
    val g = geometry(0, 1, 86400)
    assertEquals(g.epochOf(86399), BigInt(0))
    assertEquals(g.epochOf(86400), BigInt(1))
    assertEquals(g.firstSlot(3), BigInt(3 * 86400))
    assertEquals(g.firstShelleyEpoch, BigInt(0))
  }

  test(
    "a derived Byron span is open-ended until resolved, and resolving it gives the same history"
  ) {
    val derived = geometry(0, 1, 500).copy(byron = ByronSpan.derived(50, 1000))
    assert(derived.byron.derived)
    // until the chain enters Shelley every slot is a Byron slot of the genesis epoch length
    assertEquals(
      Seq(0, 49, 50, 1000, 100000).map(s => derived.epochOf(s)),
      Seq(0, 0, 1, 20, 2000).map(BigInt(_))
    )
    assertEquals(derived.resolved(1), geometry(1, 50, 500))
    // resolving an already fixed history changes nothing
    assertEquals(geometry(2, 50, 500).resolved(7), geometry(2, 50, 500))
  }

  property("an epoch starts at the slot that maps back to it, and slots lie inside their epoch") {
    forAll(geometries, Gen.choose(0L, 100000L)) { (g, slot) =>
      val epoch = g.epochOf(slot)
      g.firstSlot(epoch) <= slot && slot < g.firstSlot(epoch + 1) &&
      g.epochOf(g.firstSlot(epoch)) == epoch &&
      g.firstSlot(epoch + 1) - g.firstSlot(epoch) == g.epochSlots(epoch)
    }
  }

  property("epochs are monotone in the slot") {
    forAll(geometries, Gen.choose(0L, 100000L), Gen.choose(0L, 1000L)) { (g, slot, delta) =>
      g.epochOf(slot) <= g.epochOf(slot + delta)
    }
  }

  property("slot start times invert slotAt, with the slot length changing at the Shelley start") {
    forAll(geometries, Gen.choose(0L, 100000L)) { (g, slot) =>
      g.slotAt(g.slotStart(slot)) == BigInt(slot) &&
      g.slotStart(slot + 1).isAfter(g.slotStart(slot))
    }
  }

  test("slot length changes at the Shelley start") {
    val g = geometry(1, 50, 500)
    assertEquals(g.slotStart(49), start.plusMillis(49000))
    assertEquals(g.slotStart(50), start.plusMillis(50000))
    assertEquals(g.slotStart(51), start.plusMillis(50100))
  }
