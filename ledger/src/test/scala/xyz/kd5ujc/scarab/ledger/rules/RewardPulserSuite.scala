// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import cats.syntax.all.*
import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
import xyz.kd5ujc.scarab.ledger.state.*

class RewardPulserSuite extends ScalaCheckSuite:
  import RewardPulserFixture.*
  given ShelleyBasedEra[Conway] = ShelleyBasedEra.SConway

  private def result[A](e: Either[VocabError, A]): A =
    e.fold(e => fail(s"reward vocabulary: $e"), identity)
  private def pending(n: NewEpochState[Conway]): PulsingRewUpdate =
    result(Rewards.start(n.es, n.bprev, globals, n.epoch.toBigInt))

  property("completing any pulse partition has the same attributed rewards and exact pots") {
    Prop.forAll(Gen.choose(1, 200), Gen.choose(0, 220)) { (accounts, pulses) =>
      val n = rewarding(conway(5, accounts, 4))
      val initial = pending(n)
      val progressed =
        result((0 until pulses).toVector.foldLeftM(initial)((p, _) => Rewards.pulse(p)))
      assertEquals(Rewards.complete(progressed), Rewards.complete(initial))
    }
  }

  test("start freezes inputs and normal pulses consume at most the reference prefix") {
    val n = rewarding(conway(5, 41, 4))
    pending(n) match
      case PulsingRewUpdate.Pulsing(snapshot, p) =>
        assertEquals(p.size.value, 3L)
        assertEquals(p.answer, RewardAns.empty)
        assertEquals(p.remaining, n.es.snapshots.go.activeStake.entries)
        val advanced = result(Rewards.pulse(PulsingRewUpdate.Pulsing(snapshot, p)))
        advanced match
          case PulsingRewUpdate.Pulsing(s, next) =>
            assertEquals(s, snapshot)
            assertEquals(next.free, p.free)
            assertEquals(next.remaining, p.remaining.drop(3))
            assert(
              next.answer.accumulated.nonEmpty,
              "control must exercise positive member rewards"
            )
            assert(next.answer.accumulated.size <= 3)
            assert(next.answer.recent.size <= 3)
          case other => fail(s"completed before its prefix: $other")
      case other => fail(s"start did work eagerly: $other")
  }

  test("the last member pulse remains Pulsing and only the next call completes") {
    val n = rewarding(conway(5, 1, 1))
    val last = result(Rewards.pulse(pending(n)))
    last match
      case PulsingRewUpdate.Pulsing(_, p) => assert(p.remaining.isEmpty)
      case other                          => fail(s"last member call completed: $other")
    assert(result(Rewards.pulse(last)).isInstanceOf[PulsingRewUpdate.Complete])
  }

  test("immutable forks resume the same captured prefix independently") {
    val start = pending(rewarding(conway(5, 53, 4)))
    val a = result(Rewards.pulse(start))
    val b = result(Rewards.pulse(start))
    assertEquals(a, b)
    val ahead = result(Rewards.pulse(a))
    assertEquals(Rewards.complete(ahead), Rewards.complete(b))
    assertEquals(result(Rewards.pulse(start)), a)
  }

  test("empty stake starts, then completes with unchanged monetary accounting") {
    val n = conway(5, 0, 1)
    val start = pending(n)
    assert(result(Rewards.pulse(start)).isInstanceOf[PulsingRewUpdate.Complete])
    assert(Rewards.complete(start).isRight)
  }

  test("unbounded pool counts and full positive Haskell Int pulse sizes are checked") {
    assertEquals(
      result(RewardBlockCount.fromBigInt((BigInt(1) << 80) + 7)).value,
      (BigInt(1) << 80) + 7
    )
    assert(RewardBlockCount.fromBigInt(-1).isLeft)
    assertEquals(result(RewardPulseSize.fromBigInt(Long.MaxValue)).value, Long.MaxValue)
    assert(RewardPulseSize.fromBigInt(0).isLeft)
    assert(RewardPulseSize.fromBigInt(-1).isLeft)
  }

  test("protocol version capture refuses overflow instead of narrowing BigInts") {
    assert(ProtVer.fromBigInt(BigInt(1) << 64, 0).isLeft)
    assert(ProtVer.fromBigInt(10, BigInt(1) << 32).isLeft)
    assert(ProtVer.fromBigInt(-1, 0).isLeft)
    assert(ProtVer.fromBigInt(10, -1).isLeft)
    assertEquals(
      ProtVer.fromBigInt(13, (BigInt(1) << 32) - 1),
      Right(ProtVer(Word64.fromUnsigned(13), Word64.fromUnsigned(0xffffffffL)))
    )
  }

  test("forced completion of twenty thousand captured members is stack safe") {
    val captured = pending(rewarding(conway(5, 20000, 4)))
    val completed = xyz.kd5ujc.scarab.ledger.PreprodDeepTx.onSmallStack {
      result(Rewards.complete(captured))
    }
    assert(completed.rs.size >= 20000, "wide control must retain positive member rewards")
    assertEquals(Rewards.complete(captured), Right(completed))
  }
