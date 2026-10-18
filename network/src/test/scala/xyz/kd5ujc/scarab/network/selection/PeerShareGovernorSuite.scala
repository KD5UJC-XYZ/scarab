// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network.selection

import munit.FunSuite
import scala.collection.immutable.SortedMap
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.network.NetworkError

/** KnownPeers.belowTarget and both job completions, checked against pinned 2fb69829 rules. */
class PeerShareGovernorSuite extends FunSuite:
  private val policy = Policy()
  private def state(known: Int = 20, count: Int = 3): GovernorState[Int] =
    val targets =
      Targets.validated(0, known, count, count, 0, 0, 0).toOption.getOrElse(fail("targets"))
    GovernorState
      .initial[Int](1L, LocalRoots.empty, targets, Sensitivity.initial(false))
      .copy(
        targets = targets,
        peers = SortedMap.from(
          (1 to count).map(p =>
            p -> PeerRecord
              .cold(Origin.Other)
              .copy(
                status = Status.Hot,
                everConnected = true,
                shareAt = Some(300.seconds),
                sharing = Some(true)
              )
          )
        ),
        sharing = PeerShare.Governor(enabled = true)
      )

  private def decision(
      st: GovernorState[Int],
      now: Time = 300.seconds,
      inbound: Map[Int, Boolean] = Map.empty
  ): Decision[Int] =
    PeerShare.belowTarget(st, now, policy, inbound) match
      case PeerSelection.Guard.Go(d) => d
      case other                     => fail(s"expected decision: $other")

  private def batch(d: Decision[Int]): (Long, List[Int]) = d.jobs match
    case List(Job.Share(id, _, peers)) => (id, peers)
    case other                         => fail(s"expected batch: $other")

  test("300s activation, two requests,900s retry and minimum8 split") {
    val st = state()
    assertEquals(
      PeerShare.belowTarget(st, 299.seconds, policy, Map.empty),
      PeerSelection.Guard.Skip(Some(300.seconds))
    )
    val d = decision(st)
    val (_, picked) = batch(d)
    assertEquals(picked.size, 2)
    assertEquals(d.state.sharing.inFlight, 2)
    assertEquals(d.jobs.collect { case Job.Share(_, amount, _) => amount.value }, List(8))
    picked.foreach(p => assertEquals(d.state.peers(p).shareAt, Some(1200.seconds)))
    assertEquals(
      PeerShare.belowTarget(d.state, 300.seconds, policy, Map.empty),
      PeerSelection.Guard.Skip(None)
    )
  }

  test(
    "amount saturates255 and disables at target, flag off or bootstrap-sensitive even after clean"
  ) {
    val d = decision(state(1000))
    assertEquals(d.jobs.collect { case Job.Share(_, amount, _) => amount.value }, List(255))
    val st = state()
    val variants = List(
      st.copy(targets = st.targets.revised(known = 3).toOption.getOrElse(fail("target"))),
      st.copy(sharing = st.sharing.copy(enabled = false)),
      st.copy(sensitivity = Sensitivity(LedgerStateJudgement.TooOld, true, true, None))
    )
    variants.foreach(s =>
      assertEquals(
        PeerShare.belowTarget(s, 300.seconds, policy, Map.empty),
        PeerSelection.Guard.Skip(None)
      )
    )
  }

  test(
    "inbound fallback max10/retry60 retains negotiated willingness and marks successful/advertised"
  ) {
    val st = state(40, 0)
    val inbound = (1 to 30).map(i => i -> (i % 2 == 0)).toMap
    val d = decision(st, inbound = inbound)
    assertEquals(d.state.peers.size, 10)
    assertEquals(d.state.sharing.inboundRetryAt, 360.seconds)
    assertEquals(d.jobs, Nil)
    d.state.peers.foreach { (p, r) =>
      assertEquals(r.sharing, inbound.get(p))
      assertEquals((r.everConnected, r.advertise, r.status), (true, Some(true), Status.Cold(None)))
    }
    assertEquals(
      PeerShare.belowTarget(d.state, 359.seconds, policy, inbound),
      PeerSelection.Guard.Skip(Some(360.seconds))
    )
    assertEquals(decision(d.state, 360.seconds, inbound).state.peers.size, 20)
  }

  test("both coin sides occur deterministically; no inbound pick while a request is in flight") {
    val st = state()
    val decisions =
      (0L until 100L).map(seed => decision(st.copy(rng = Rng(seed)), inbound = Map(99 -> true)))
    assert(decisions.exists(_.traces.exists { case Trace.PickInbound(_) => true; case _ => false }))
    assert(decisions.exists(_.jobs.exists { case Job.Share(_, _, _) => true; case _ => false }))
    val started = decision(st)
    assert(
      !decision(
        started.state.copy(sharing =
          started.state.sharing.copy(
            batches = started.state.sharing.batches.view
              .mapValues(b => b.copy(pending = b.pending.take(1)))
              .toMap
          )
        ),
        inbound = Map(99 -> true)
      ).traces.exists { case Trace.PickInbound(_) => true; case _ => false }
    )
  }

  test(
    "completion excludes known/big ledger, deduplicates, respects both-phase budget and keeps privacy"
  ) {
    val initial = state(5).copy(peers =
      state(5).peers
        .updated(100, PeerRecord.cold(Origin.BigLedger))
        .updated(1, state(5).peers(1).copy(advertise = Some(false)))
    )
    val started = decision(initial)
    val (id, asked) = batch(started)
    val first = PeerShare.completed(
      started.state,
      id,
      List(asked.head -> PeerShare.Result.Peers(Vector(1, 100, 20, 20))),
      301.seconds,
      policy
    )
    assert(first.state.peers.contains(20))
    assertEquals(first.state.peers(1).advertise, Some(false))
    assertEquals(first.state.peers(100).origin, Origin.BigLedger)
    assertEquals(first.state.peers(20).advertise, Some(true))
    assertEquals(first.state.sharing.inFlight, 1)
    val second = PeerShare.completed(
      first.state,
      id,
      List(asked.last -> PeerShare.Result.Peers(Vector(20, 21, 22, 23))),
      305.seconds,
      policy
    )
    val learnt = second.state.peers.keySet -- initial.peers.keySet
    assertEquals(learnt.size, 2)
    assert(learnt(20) && (learnt - 20).subsetOf(Set(21, 22, 23)))
    assertEquals(second.state.sharing.inFlight, 0)
    assertEquals(
      PeerShare
        .completed(
          second.state,
          id,
          List(asked.head -> PeerShare.Result.Peers(Vector(999))),
          306.seconds,
          policy
        )
        .state,
      second.state
    )
  }

  test("failures back off3600s, not-registered retains900s; neither changes peer failure counts") {
    val started = decision(state())
    val (id, asked) = batch(started)
    val d = PeerShare.completed(
      started.state,
      id,
      List(
        asked.head -> PeerShare.Result.Failed(
          NetworkError.BearerClosed(NetworkError.Closure.Aborted)
        ),
        asked.last -> PeerShare.Result.NotRegistered()
      ),
      305.seconds,
      policy
    )
    assertEquals(d.state.peers(asked.head).shareAt, Some(3905.seconds))
    assertEquals(d.state.peers(asked.last).shareAt, Some(1200.seconds))
    assert(d.state.peers.values.forall(_.failCount == 0))
    assertEquals(d.state.sharing.inFlight, 0)
  }

  test(
    "a late completion after disconnection never restores the connection or re-inserts its source"
  ) {
    val started = decision(state())
    val (id, asked) = batch(started)
    val lost = started.state.copy(peers = started.state.peers - asked.head)
    val d = PeerShare.completed(
      lost,
      id,
      List(asked.head -> PeerShare.Result.Peers(Vector(9))),
      305.seconds,
      policy
    )
    assert(!d.state.peers.contains(asked.head))
    assertEquals(d.state.peers(9).status, Status.Cold(None))
    assertEquals(GovernorState.invariant(d.state), Nil)
  }
