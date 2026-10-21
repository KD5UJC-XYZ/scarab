// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network.selection

import munit.FunSuite
import scala.collection.immutable.SortedSet
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.protocol.ConnectionState.Provenance

class WarmPromotionSuite extends FunSuite:
  private val now: Time = Duration.Zero
  private val config =
    RootConfig(false, DiffusionMode.InitiatorAndResponder, Provenance.Outbound, false)

  private def targets(big: Boolean): Targets =
    val values = if big then (0, 0, 0, 0, 2, 2, 1) else (0, 2, 2, 1, 0, 0, 0)
    Targets.validated.tupled(values).fold(e => fail(e.message), identity)

  private def warm(origin: Origin, tepid: Boolean): PeerRecord =
    PeerRecord.cold(origin).copy(status = Status.Warm(now), tepid = tepid)

  for local <- List(false, true); big <- List(false, true) if !local || !big do
    test(s"warm activation weights just-demoted peers: local=$local bigLedger=$big") {
      val roots =
        if local then
          LocalRoots.fromGroups(List(RootGroupSpec(1, 2, List(1 -> config, 2 -> config))))
        else LocalRoots.empty[Int]
      val origin = if local then Origin.Local else if big then Origin.BigLedger else Origin.Other
      val ts = targets(big)
      val initial = GovernorState.initial[Int](1L, roots, ts, Sensitivity.initial(false))
      val st = initial.copy(
        targets = ts,
        localRoots = roots,
        peers = initial.peers.updated(1, warm(origin, true)).updated(2, warm(origin, false))
      )
      PeerSelection.decide(st, now) match
        case Step.Act(d) =>
          assertEquals(d.jobs, List(Job.Activate(2)))
          assertEquals(d.state.peers(1).status, Status.Warm(now))
          assertEquals(d.state.peers(2).status, Status.Activating)
          assertEquals(GovernorState.invariant(d.state), Nil)
        case other => fail(s"expected warm promotion, got $other")
    }

  test("warm weighting changes only ranking, retaining one unsigned Word32 draw per candidate") {
    val candidates = SortedSet(1, 2)
    val (uniform, afterUniform) = Picks.promotion(candidates, 1, Rng(1L))
    val (weighted, afterWeighted) = Picks.warmPromotion(candidates, 1, Rng(1L), _ == 1)
    assertEquals(uniform, List(1))
    assertEquals(weighted, List(2))
    assertEquals(afterWeighted, afterUniform)
  }

  test("integer division ties retain native ascending peer order, including unsigned maximum") {
    assertEquals(Picks.warmPromotionWords(List(2 -> 7L, 1 -> 1L), 1, _ == 1), List(1))
    assertEquals(
      Picks.warmPromotionWords(List(1 -> 4294967295L, 2 -> 4294967295L), 1, _ == 1),
      List(2)
    )
  }

  test("completed churn demotion makes the old hot peer tepid before the next activation") {
    val ts = targets(false)
    val initial =
      GovernorState.initial[Int](1L, LocalRoots.empty[Int], ts, Sensitivity.initial(false))
    val st = initial.copy(
      targets = ts,
      peers = initial.peers
        .updated(1, warm(Origin.Other, false).copy(status = Status.Deactivating))
        .updated(2, warm(Origin.Other, false))
    )
    val completed = PeerSelection.apply(st, Event.Completed(Outcome.Deactivated(1)), now).state
    assert(completed.peers(1).tepid)
    PeerSelection.decide(completed, now) match
      case Step.Act(d) => assertEquals(d.jobs, List(Job.Activate(2)))
      case other       => fail(s"expected reactivation after churn, got $other")
  }
