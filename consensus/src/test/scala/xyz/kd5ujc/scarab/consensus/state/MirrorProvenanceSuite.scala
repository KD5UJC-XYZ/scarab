// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus.state

import xyz.kd5ujc.scarab.ledger.TestGeometry
import munit.FunSuite
import xyz.kd5ujc.scarab.ledger.EpochGeometry
import xyz.kd5ujc.scarab.ledger.state.Gap
import xyz.kd5ujc.scarab.protocol.Era

class MirrorProvenanceSuite extends FunSuite:
  private val gaps = Set(
    Gap.NonMyopic,
    Gap.RewardAttribution,
    Gap.PendingPulsingUpdate,
    Gap.NumDelegatorsLowerBound,
    Gap.ShelleyTransition
  )
  private val observed = Map(Era.Shelley -> BigInt(4), Era.Allegra -> BigInt(7))
  private val imported = MirrorProvenance.recorded(gaps, observed)

  test(
    "an epoch discards the historical update and resets the consensus counter, not ranking history"
  ) {
    val one = imported.epochBoundary
    assertEquals(one.gaps, Set(Gap.NonMyopic, Gap.NumDelegatorsLowerBound))
    assertEquals(one.observed, observed)
    assertEquals(imported.gaps, gaps)
  }

  test("three actual rotations replace every potentially incomplete imported snapshot") {
    val one = imported.epochBoundary
    val two = one.epochBoundary
    assert(two.gaps.contains(Gap.NumDelegatorsLowerBound))
    val three = two.epochBoundary
    assertEquals(three.gaps, Set(Gap.NonMyopic))
    assertEquals(three.epochBoundary.gaps, Set(Gap.NonMyopic))
  }

  test("restore retains flags and conservatively restarts the snapshot replacement witness") {
    val two = imported.epochBoundary.epochBoundary
    val restored = MirrorProvenance.recorded(two.gaps, two.observed)
    assert(
      !summon[cats.Eq[MirrorProvenance]].eqv(two, restored),
      "rotation evidence affects future healing"
    )
    assertEquals(restored.epochBoundary.epochBoundary.gaps, two.gaps)
    assertEquals(restored.epochBoundary.epochBoundary.epochBoundary.gaps, Set(Gap.NonMyopic))
  }

  test("complete sources remain complete and source era observations win") {
    assertEquals(MirrorProvenance.complete.epochBoundary.gaps, Set.empty[Gap])
    val entered = imported.entered(Era.Shelley, 90).entered(Era.Mary, 12)
    assertEquals(entered.observed, observed.updated(Era.Mary, BigInt(12)))
    assertEquals(entered.gaps, gaps)
  }

  private val geometry =
    EpochGeometry(java.time.Instant.EPOCH, 1000, 500, TestGeometry.k(5), TestGeometry.f("0.5"))
  private def count(slot: Int, epoch: Option[Int], previous: Int): Long =
    MirrorStep
      .afterVoting(geometry, BigInt(slot), epoch.map(BigInt(_)), Word32.fromUnsigned(previous))
      .fold(e => fail(e.toString), _.toLong)

  test(
    "native transition count increments at the inclusive voting deadline and resets at the next epoch"
  ) {
    // 3k/f = 30; epoch end 500, voting deadline 440.
    assertEquals(count(439, Some(0), 8), 8L)
    assertEquals(count(440, Some(0), 8), 9L)
    assertEquals(count(499, Some(0), 9), 10L)
    assertEquals(count(500, Some(0), 10), 0L)
    assertEquals(count(940, Some(0), 10), 1L)
    assertEquals(count(1940, Some(0), 10), 1L)
  }

  test("native transition count starts at zero and refuses Word32 successor overflow") {
    assertEquals(count(440, None, 123), 1L)
    assertEquals(
      MirrorStep.afterVoting(geometry, BigInt(440), Some(BigInt(0)), Word32.fromUnsigned(-1)),
      Left(StateMirrorError.TransitionCounterOverflow)
    )
    assertEquals(count(439, Some(0), -1), 0xffffffffL)
    assertEquals(count(500, Some(0), -1), 0L)
  }

  test("the native voting deadline saturates at slot zero for a short configured epoch") {
    val short = geometry.copy(epochLength = 50)
    val result = MirrorStep.afterVoting(short, BigInt(0), None, Word32.fromUnsigned(0))
    assertEquals(result.map(_.toLong), Right(1L))
  }
