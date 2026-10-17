// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network.selection

import munit.FunSuite
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.protocol.ConnectionState.Provenance

/** The decision function by example, one reference rule per test. */
class PeerSelectionSuite extends FunSuite:
  private type S = GovernorState[Int]

  private val config =
    RootConfig(false, DiffusionMode.InitiatorAndResponder, Provenance.Outbound, false)
  private val t0: Time = Duration.Zero

  private def group(hot: Int, warm: Int, members: Int*): RootGroupSpec[Int] =
    RootGroupSpec(hot, warm, members.toList.map(_ -> config))

  private def start(
      groups: List[RootGroupSpec[Int]],
      targets: Targets = Targets.relay,
      bootstrap: Boolean = false,
      seed: Long = 1L
  ): S =
    GovernorState
      .initial[Int](seed, LocalRoots.fromGroups(groups), targets, Sensitivity.initial(bootstrap))

  /** Decide until the governor has nothing to do now; the jobs it started and the final state. */
  private def settle(st0: S, now: Time): (S, List[Job[Int]], List[Trace[Int]]) =
    @scala.annotation.tailrec
    def go(
        st: S,
        jobs: List[Job[Int]],
        traces: List[Trace[Int]],
        fuel: Int
    ): (S, List[Job[Int]], List[Trace[Int]]) =
      require(fuel > 0, "the governor did not settle")
      PeerSelection.decide(st, now) match
        case Step.Act(d)     => go(d.state, jobs ++ d.jobs, traces ++ d.traces, fuel - 1)
        case Step.Idle(s, _) => (s, jobs, traces)
    go(st0, Nil, Nil, 100)

  private def complete(st: S, outcome: Outcome[Int], now: Time): S =
    PeerSelection.apply(st, Event.Completed(outcome), now).state

  private def statusOf(st: S, peer: Int): Status = st.peers(peer).status

  /** The world where every job succeeds at once: settle, complete the jobs, until nothing is left.
    */
  private def autopilot(st0: S, now: Time): (S, List[Trace[Int]]) =
    @scala.annotation.tailrec
    def go(st: S, traces: List[Trace[Int]], fuel: Int): (S, List[Trace[Int]]) =
      require(fuel > 0, "the world did not settle")
      val (settled, jobs, more) = settle(st, now)
      if jobs.isEmpty then (settled, traces ++ more)
      else
        val done = jobs.foldLeft(settled) { (s, job) =>
          val outcome: Outcome[Int] = job match
            case Job.Connect(p, _, _)   => Outcome.Connected(p)
            case Job.Activate(p)        => Outcome.Activated(p)
            case Job.Deactivate(p)      => Outcome.Deactivated(p)
            case Job.Close(p)           => Outcome.Closed(p)
            case Job.RequestRoots(_, _) => Outcome.RootsFailed()
          complete(s, outcome, now)
        }
        go(done, traces ++ more, fuel - 1)
    go(st0, Nil, 50)

  test("local roots are connected and activated up to their valencies, in the reference's steps") {
    val (st1, jobs1, _) = settle(start(List(group(2, 3, 1, 2, 3, 4))), t0)
    // the group wants three established: three connects, and not the fourth
    val connects = jobs1.collect { case Job.Connect(p, _, _) => p }
    assertEquals(connects.size, 3)
    assert(connects.forall(p => statusOf(st1, p) == Status.Connecting))
    assertEquals(st1.view(t0).counters.coldPromotions, 3)

    val st2 = connects.foldLeft(st1)((s, p) => complete(s, Outcome.Connected(p), t0))
    val (st3, jobs3, _) = settle(st2, t0)
    val activates = jobs3.collect { case Job.Activate(p) => p }
    assertEquals(activates.size, 2)
    val st4 = activates.foldLeft(st3)((s, p) => complete(s, Outcome.Activated(p), t0))
    val counters = st4.view(t0).counters
    assertEquals((counters.hot, counters.warm, counters.cold), (2, 1, 1))
    // everything is at target: nothing more to do
    assertEquals(settle(st4, t0)._2, Nil)
  }

  test("local roots are promoted above the established target, and never demoted") {
    val targets = Targets.validated(60, 150, 1, 1, 0, 0, 0).toOption.get
    val st0 = start(List(group(3, 3, 1, 2, 3)), targets)
    val (st1, jobs1, _) = settle(st0, t0)
    assertEquals(jobs1.collect { case Job.Connect(p, _, _) => p }.toSet, Set(1, 2, 3))
    val st2 = Set(1, 2, 3).foldLeft(st1)((s, p) => complete(s, Outcome.Connected(p), t0))
    val (st3, jobs3, _) = settle(st2, t0)
    assertEquals(jobs3.collect { case Job.Activate(p) => p }.toSet, Set(1, 2, 3))
    assert(!jobs3.exists { case Job.Close(_) | Job.Deactivate(_) => true; case _ => false })
    assertEquals(st3.view(t0).established.size, 3)
  }

  test("a failed connect backs off 5, 10, 20, 40, 80, 160 seconds with a fuzz of two seconds") {
    def failures(n: Int): List[FiniteDuration] =
      (1 to n)
        .foldLeft((start(List(group(1, 1, 7)), seed = 3L), List.empty[FiniteDuration], t0)) {
          case ((st, delays, now), _) =>
            val (s1, jobs, _) = settle(st, now)
            assert(
              jobs.contains(
                Job.Connect(7, DiffusionMode.InitiatorAndResponder, Provenance.Outbound)
              )
            )
            val s2 = complete(s1, Outcome.ConnectFailed(7, FailureClass.Transient), now)
            val Status.Cold(Some(at)) = statusOf(s2, 7): @unchecked
            (s2, delays :+ (at - now), at)
        }
        ._2
    val delays = failures(7)
    val bases = List(5, 10, 20, 40, 80, 160, 160)
    delays.zip(bases).foreach { (d, b) =>
      assert(d >= (b - 2).seconds && d <= (b + 2).seconds, s"$d for $b")
    }
  }

  test("a peer is not connected again before its retry time, and the governor says when to look") {
    val st0 = start(List(group(1, 1, 7)))
    val (st1, _, _) = settle(st0, t0)
    val st2 = complete(st1, Outcome.ConnectFailed(7, FailureClass.Transient), t0)
    val Status.Cold(Some(at)) = statusOf(st2, 7): @unchecked
    PeerSelection.decide(st2, 1.second) match
      case Step.Idle(_, wake) => assertEquals(wake, Some(at))
      case other              => fail(s"expected to wait, got $other")
    val (_, later, _) = settle(st2, at)
    assertEquals(later.collect { case Job.Connect(p, _, _) => p }, List(7))
  }

  test("a peer that is not a root is forgotten after six failures; a root never is") {
    val roots = settle(start(List(group(1, 1, 7))), t0)._1.withStatus(7, Status.Cold(None))
    val withOther = roots.copy(peers = roots.peers.updated(8, PeerRecord.cold(Origin.Other)))
    def fail(st: S, peer: Int, now: Time): S =
      val connecting = st.withStatus(peer, Status.Connecting)
      complete(connecting, Outcome.ConnectFailed(peer, FailureClass.Transient), now)
    val failed = (1 to 6).foldLeft(withOther)((s, i) => fail(s, 8, i.minutes))
    assert(
      !failed.peers.contains(8),
      "the sixth failure forgets the peer (the count is read before the increment)"
    )
    val rootFailed = (1 to 9).foldLeft(withOther)((s, i) => fail(s, 7, i.minutes))
    assert(rootFailed.peers.contains(7))
    assertEquals(rootFailed.peers(7).failCount, 9)
  }

  test(
    "a peer that misbehaved is forgotten, a root that misbehaved waits out the longest backoff"
  ) {
    val roots = settle(start(List(group(1, 1, 7))), t0)._1
    val st = roots.copy(peers = roots.peers.updated(8, PeerRecord.cold(Origin.Public)))
    val bad = (peer: Int) =>
      complete(
        st.withStatus(peer, Status.Connecting),
        Outcome.ConnectFailed(peer, FailureClass.Misbehaved),
        t0
      )
    assert(!bad(8).peers.contains(8))
    assertEquals(statusOf(bad(7), 7), Status.Cold(Some(160.seconds)))
  }

  test("a failed activation leaves the established set for ten seconds, fuzzed") {
    val (st1, _, _) = settle(start(List(group(1, 1, 7))), t0)
    val st2 = complete(st1, Outcome.Connected(7), t0)
    val (st3, jobs, _) = settle(st2, t0)
    assertEquals(jobs, List(Job.Activate(7)))
    val st4 = complete(st3, Outcome.ActivateFailed(7, FailureClass.Transient), 5.seconds)
    val Status.Cold(Some(at)) = statusOf(st4, 7): @unchecked
    assert(at >= 13.seconds && at <= 17.seconds, at.toString)
    assertEquals(st4.peers(7).failCount, 1)
  }

  test("a peer that stayed hot loses its fail count after the clearing delay") {
    val (st1, _, _) = settle(start(List(group(1, 1, 7))), t0)
    val st2 = complete(st1, Outcome.Connected(7), t0)
    val (st3, _, _) = settle(st2, t0)
    val hot = complete(st3.updated(7)(_.copy(failCount = 3)), Outcome.Activated(7), 10.seconds)
    assertEquals(hot.peers(7).clearFailAt, Some(130.seconds))
    val Step.Idle(cleared, _) = PeerSelection.decide(hot, 131.seconds): @unchecked
    assertEquals(cleared.peers(7).failCount, 0)
  }

  test("a hot peer whose protocol ends is warm again; a lost connection lingers, then is cold") {
    val (st1, _, _) = settle(start(List(group(2, 2, 7, 8))), t0)
    val st2 = List(7, 8).foldLeft(st1)((s, p) => complete(s, Outcome.Connected(p), t0))
    val (st3, _, _) = settle(st2, t0)
    val hot = List(7, 8).foldLeft(st3)((s, p) => complete(s, Outcome.Activated(p), t0))
    val ended = PeerSelection
      .apply(hot, Event.Observed(7, Observation.HotEnded(Some(30.seconds))), 1.minute)
      .state
    val Status.Warm(readyAt) = statusOf(ended, 7): @unchecked
    assert(readyAt >= 90.seconds && readyAt <= 101.seconds, readyAt.toString)
    val cooling = PeerSelection
      .apply(
        ended,
        Event.Observed(8, Observation.Lost(FailureClass.Transient, 5.seconds, false, "lost")),
        2.minutes
      )
      .state
    assertEquals(statusOf(cooling, 8), Status.Cooling)
    val cold = PeerSelection
      .apply(
        cooling,
        Event.Observed(8, Observation.Lost(FailureClass.Transient, 5.seconds, true, "lost")),
        2.minutes + 1.second
      )
      .state
    assert(statusOf(cold, 8).isInstanceOf[Status.Cold])
    assertEquals(cold.peers(8).failCount, 1)
  }

  test("an outcome that arrives after the peer was lost changes nothing and is traced as aborted") {
    val (st1, _, _) = settle(start(List(group(1, 1, 7))), t0)
    val st2 = complete(st1, Outcome.Connected(7), t0)
    val (st3, _, _) = settle(st2, t0)
    val lost = PeerSelection
      .apply(
        st3,
        Event.Observed(7, Observation.Lost(FailureClass.Transient, 1.second, false, "lost")),
        1.second
      )
      .state
    val late = PeerSelection.apply(lost, Event.Completed(Outcome.Activated(7)), 2.seconds)
    assertEquals(late.state, lost)
    assertEquals(late.traces, List(Trace.PromoteAborted(Temperature.Warm, 7)))
  }

  test("above the established target the surplus warm peers that are not roots are closed") {
    val targets = Targets.validated(0, 150, 2, 1, 0, 0, 0).toOption.get
    val base = start(Nil, targets)
    val peers = (1 to 5).foldLeft(base.peers)((m, p) =>
      m.updated(p, PeerRecord.cold(Origin.Public).copy(status = Status.Warm(t0)))
    )
    val st = base.copy(peers = peers, targets = targets, requested = targets)
    val (after, jobs, _) = settle(st, t0)
    // one is activated for the active target, then the surplus of the established target is closed
    assertEquals(jobs.count { case Job.Close(_) => true; case _ => false }, 3)
    assertEquals(jobs.count { case Job.Activate(_) => true; case _ => false }, 1)
    assertEquals(after.view(t0).established.size, 5)
  }

  test("above the active target the surplus hot peers that are not roots are deactivated") {
    val targets = Targets.validated(0, 150, 10, 2, 0, 0, 0).toOption.get
    val base = start(Nil, targets)
    val peers = (1 to 5).foldLeft(base.peers)((m, p) =>
      m.updated(p, PeerRecord.cold(Origin.Public).copy(status = Status.Hot))
    )
    val (_, jobs, _) = settle(base.copy(peers = peers, targets = targets), t0)
    assertEquals(jobs.count { case Job.Deactivate(_) => true; case _ => false }, 3)
  }

  test(
    "above the known target cold peers that are not roots are forgotten, within the root target"
  ) {
    val targets = Targets.validated(2, 4, 2, 1, 0, 0, 0).toOption.get
    val base = start(Nil, targets)
    val peers = (1 to 10).foldLeft(base.peers)((m, p) =>
      m.updated(p, PeerRecord.cold(if p <= 3 then Origin.Public else Origin.Other))
    )
    val (after, _, traces) =
      settle(base.copy(peers = peers, targets = targets, requested = targets), t0)
    // known 10 > 4: the six non-roots are forgotten first; the roots stay (3 > root target 2, so one may go)
    assert(after.peers.size <= 4)
    assert(traces.exists { case Trace.ForgetCold(_, _, _) => true; case _ => false })
  }

  test(
    "below the root target public roots are requested once, and not again before the retry time"
  ) {
    val targets = Targets.validated(5, 20, 0, 0, 0, 0, 0).toOption.get
    val (st1, jobs, _) = settle(start(Nil, targets), t0)
    assertEquals(jobs, List(Job.RequestRoots(5, bootstrap = false)))
    assert(st1.publicRootsInFlight)
    val found = PublicRoots(Set(11, 12), Set.empty[Int])
    val st2 = complete(st1, Outcome.RootsFound(found, 3.minutes), t0)
    assertEquals(st2.peers.keySet, Set(11, 12))
    assertEquals(st2.publicRootRetryAt, 3.minutes)
    // still below (2 of 5): wait for the retry time
    val Step.Idle(_, wake) = PeerSelection.decide(st2, 1.minute): @unchecked
    assertEquals(wake, Some(3.minutes))
    // a request that finds nothing new backs off 2^1, 2^2 ... seconds
    val st3 = complete(
      st2.copy(publicRootsInFlight = true),
      Outcome.RootsFound(found, 3.minutes),
      4.minutes
    )
    assertEquals((st3.publicRootBackoffs, st3.publicRootRetryAt), (1, 4.minutes + 2.seconds))
    val st4 = complete(
      st3.copy(publicRootsInFlight = true),
      Outcome.RootsFound(found, 3.minutes),
      5.minutes
    )
    assertEquals((st4.publicRootBackoffs, st4.publicRootRetryAt), (2, 5.minutes + 4.seconds))
    // a failure goes the other way
    val st5 = complete(st4.copy(publicRootsInFlight = true), Outcome.RootsFailed(), 6.minutes)
    assertEquals((st5.publicRootBackoffs, st5.publicRootRetryAt), (-1, 6.minutes + 2.seconds))
  }

  test("the same seed and the same events give the same decisions") {
    def run(seed: Long) =
      val roots = (1 to 20).map(p => p -> PeerRecord.cold(Origin.Public)).toMap
      val base = start(Nil, Targets.relay, seed = seed)
      settle(
        base.copy(
          peers = base.peers ++ roots,
          targets = Targets.relay,
          requested = Targets.relay
        ),
        t0
      )
    assertEquals(run(9L), run(9L))
    assertNotEquals(run(9L)._2, run(10L)._2)
  }

  // ------------------------------------------------------------------ the sensitive state

  private val trusted = config.copy(trustable = true)

  private def bootstrapState: S =
    val roots = LocalRoots.fromGroups(List(RootGroupSpec(1, 1, List(1 -> trusted, 2 -> config))))
    val st = GovernorState.initial[Int](5L, roots, Targets.relay, Sensitivity.initial(true))
    // the local roots are in force and so are the targets
    autopilot(st, t0)._1

  test("too old with bootstrap peers: targets to zero, no local roots, 15 minutes to be clean") {
    val running = bootstrapState
    assertEquals(running.targets, Targets.relay)
    val told = PeerSelection
      .apply(running, Event.JudgementReported(LedgerStateJudgement.TooOld), 10.seconds)
      .state
    val Step.Act(d) = PeerSelection.decide(told, 10.seconds): @unchecked
    assertEquals(d.state.targets, Targets.zero)
    assert(d.state.localRoots.isEmpty)
    assertEquals(d.state.sensitivity.timeout, Some(10.seconds + 15.minutes))
    assert(d.state.sensitivity.sensitive && !d.state.sensitivity.ableToMakeProgress)
    // the former local roots are known, no longer roots
    assertEquals(d.state.peers.values.map(_.origin).toSet, Set(Origin.Other))
  }

  test("the clean state is reached when only trusted peers are known; then the targets return") {
    val running = bootstrapState
    val told =
      PeerSelection.apply(running, Event.JudgementReported(LedgerStateJudgement.TooOld), t0).state
    // peers are demoted and forgotten against the zero targets, then the node is clean
    val (clean, traces) = autopilot(told, t0)
    assert(traces.contains(Trace.OnlyBootstrapPeers()), traces.toString)
    assert(clean.sensitivity.hasOnlyBootstrapPeers)
    assertEquals(clean.sensitivity.timeout, None)
    // the trustable local root is back in force within the regime's targets
    assertEquals(clean.targets, Targets.relay)
    // nothing trustable is left to talk to: the local root 2 is not trustable, and 1 was forgotten
    assert(clean.localRoots.isEmpty || clean.localRoots.keys.toList == List(1))
  }

  test("the node stops when it has not become clean within the timeout") {
    val running = bootstrapState
    val told =
      PeerSelection.apply(running, Event.JudgementReported(LedgerStateJudgement.TooOld), t0).state
    val Step.Act(d) = PeerSelection.decide(told, t0): @unchecked
    // a connection in flight keeps it from being clean
    val stuck = d.state.copy(peers =
      d.state.peers.updated(9, PeerRecord.cold(Origin.Public).copy(status = Status.Connecting))
    )
    assertEquals(PeerSelection.abort(stuck, 14.minutes), None)
    assertEquals(
      PeerSelection.abort(stuck, 15.minutes),
      Some(GovernorError.BootstrapPeersCriticalTimeout)
    )
  }

  test("young enough again: the bootstrap peers we are not connected to are forgotten") {
    val running = bootstrapState
    val withBootstrap = running.copy(peers =
      running.peers ++ Map(
        20 -> PeerRecord.cold(Origin.Bootstrap),
        21 -> PeerRecord.cold(Origin.Bootstrap).copy(status = Status.Hot)
      )
    )
    val told = PeerSelection
      .apply(
        withBootstrap.copy(sensitivity =
          withBootstrap.sensitivity.copy(judgement = LedgerStateJudgement.TooOld)
        ),
        Event.JudgementReported(LedgerStateJudgement.YoungEnough),
        t0
      )
      .state
    val Step.Act(d) = PeerSelection.decide(told, t0): @unchecked
    assert(!d.state.peers.contains(20) && d.state.peers.contains(21))
    assertEquals(d.state.sensitivity.judgement, LedgerStateJudgement.YoungEnough)
    assertEquals(d.state.publicRootRetryAt, t0)
  }

  test("without bootstrap peers the judgement changes nothing") {
    val st = start(List(group(1, 1, 1)))
    val told =
      PeerSelection.apply(st, Event.JudgementReported(LedgerStateJudgement.TooOld), t0).state
    val (after, _, _) = settle(told, t0)
    assert(!after.sensitivity.sensitive)
    assertEquals(after.targets, Targets.relay)
  }
