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
            case Job.Connect(p, _, _, generation)    => Outcome.Connected(p, generation)
            case Job.Activate(p)                     => Outcome.Activated(p)
            case Job.Deactivate(p)                   => Outcome.Deactivated(p)
            case Job.Close(p, _)                     => Outcome.Closed(p)
            case Job.RequestRoots(_, _, generation)  => Outcome.RootsFailed(generation)
            case Job.RequestBigLedger(_, generation) => Outcome.BigLedgerFailed(generation)
            case Job.Share(id, _, peers) =>
              Outcome.Shared(id, peers.map(_ -> PeerShare.Result.NotRegistered()))
          complete(s, outcome, now)
        }
        go(done, traces ++ more, fuel - 1)
    go(st0, Nil, 50)

  test("local roots are connected and activated up to their valencies, in the reference's steps") {
    val (st1, jobs1, _) = settle(start(List(group(2, 3, 1, 2, 3, 4))), t0)
    // the group wants three established: three connects, and not the fourth
    val connects = jobs1.collect { case Job.Connect(p, _, _, _) => p }
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
    assertEquals(jobs1.collect { case Job.Connect(p, _, _, _) => p }.toSet, Set(1, 2, 3))
    val st2 = Set(1, 2, 3).foldLeft(st1)((s, p) => complete(s, Outcome.Connected(p), t0))
    val (st3, jobs3, _) = settle(st2, t0)
    assertEquals(jobs3.collect { case Job.Activate(p) => p }.toSet, Set(1, 2, 3))
    assert(!jobs3.exists { case Job.Close(_, _) | Job.Deactivate(_) => true; case _ => false })
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
    assertEquals(later.collect { case Job.Connect(p, _, _, _) => p }, List(7))
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

  test("late cached-block rejection forgets its disconnected source, never its consumer") {
    val base = start(Nil)
    val source = PeerRecord.cold(Origin.Public)
    val consumer = PeerRecord.cold(Origin.Public).copy(status = Status.Hot)
    val state = base.copy(peers = base.peers ++ Map(7 -> source, 8 -> consumer))
    val rejected = PeerSelection.apply(
      state,
      Event.Observed(7, Observation.CachedBlockRejected("invalid")),
      1.minute
    )
    assert(!rejected.state.peers.contains(7))
    assertEquals(rejected.state.peers(8), consumer)
    assertEquals(rejected.jobs, List(Job.Close(7, None)))
    assert(rejected.traces.contains(Trace.Forgotten(List(7))))
    val innocent = PeerSelection.apply(
      rejected.state,
      Event.Observed(8, Observation.Lost(FailureClass.Transient, 5.seconds, true, "foreign block")),
      2.minutes
    )
    assert(innocent.state.peers.contains(8))
    assertEquals(innocent.state.peers(8).failCount, 1)
  }

  test("late cached-block rejection closes a newer source session and backs off local roots") {
    val (base, _, _) = settle(start(List(group(1, 1, 7))), t0)
    val state = base.updated(7)(_.copy(status = Status.Hot))
    val rejected = PeerSelection.apply(
      state,
      Event.Observed(7, Observation.CachedBlockRejected("invalid")),
      1.minute
    )
    assert(rejected.state.peers.contains(7))
    assertEquals(rejected.jobs, List(Job.Close(7, None)))
    assertEquals(rejected.state.peers(7).failCount, 1)
    val Status.Cold(Some(retryAt)) = rejected.state.peers(7).status: @unchecked
    assertEquals(
      retryAt,
      1.minute + Policy().baseColdRetry * (1L << Policy().maxColdRetryBackoff.toLong)
    )
    val replay =
      PeerSelection.apply(rejected.state, Event.Completed(Outcome.Activated(7)), 2.minutes)
    assertEquals(replay.state, rejected.state)
  }

  test(
    "a source connecting when its cached block is refused is closed even if the job finishes later"
  ) {
    val base = start(Nil)
    val state = base.copy(peers =
      base.peers.updated(7, PeerRecord.cold(Origin.Public).copy(status = Status.Connecting))
    )
    val rejected = PeerSelection.apply(
      state,
      Event.Observed(7, Observation.CachedBlockRejected("invalid")),
      1.minute
    )
    val connected =
      PeerSelection.apply(rejected.state, Event.Completed(Outcome.Connected(7)), 2.minutes)
    assert(!connected.state.peers.contains(7))
    assertEquals(connected.jobs, List(Job.Close(7, Some(BigInt(0)))))
    assertEquals(connected.traces, List(Trace.PromoteAborted(Temperature.Cold, 7)))
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
    assertEquals(jobs.count { case Job.Close(_, _) => true; case _ => false }, 3)
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
    "above the active target the hot peers that were seldom first go first (hotDemotionPolicy)"
  ) {
    val targets = Targets.validated(0, 150, 10, 3, 0, 0, 0).toOption.get
    val base = start(Nil, targets)
    val peers = (1 to 5).foldLeft(base.peers)((m, p) =>
      m.updated(p, PeerRecord.cold(Origin.Public).copy(status = Status.Hot))
    )
    // peers 1 and 2 presented most headers and blocks, 3 one header, 4 and 5 are not on the board:
    // they rank as zero before every peer that is
    val metrics = (10L to 19L)
      .foldLeft(PeerMetrics.empty[Int]())((m, s) =>
        m.reportHeader(if s % 2 == 0 then 1 else 2, s, s.seconds)
          .reportFetch(if s % 2 == 0 then 2 else 1, s, 1000, s.seconds)
      )
      .reportHeader(3, 30, 30.seconds)
    val withMetrics = base.copy(peers = peers, targets = targets)
    PeerSelection.decide(withMetrics, t0, metrics) match
      case Step.Act(d) =>
        // two surplus hot peers: the two lowest scores
        assertEquals(d.jobs.collect { case Job.Deactivate(p) => p }.toSet, Set(4, 5))
      case other => fail(s"expected a demotion, got $other")
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

  test("Genesis mode installs sync targets while TooOld and restores requested targets") {
    val sensitivity = Sensitivity.initial(
      useBootstrapPeers = false,
      consensusMode = ConsensusMode.GenesisMode,
      syncTargets = Targets.sync
    )
    val initial = GovernorState
      .initial[Int](7L, LocalRoots.empty, Targets.relay, sensitivity)
      .copy(targets = Targets.relay, requested = Targets.relay)
    val reportedOld = PeerSelection
      .apply(
        initial,
        Event.JudgementReported(LedgerStateJudgement.TooOld),
        t0
      )
      .state
    val sync = PeerSelection.decide(reportedOld, t0) match
      case Step.Act(decision)  => decision.state
      case Step.Idle(state, _) => state
    assertEquals(sync.targets, Targets.sync)
    assert(sync.sensitivity.syncingGenesis)
    assert(!sync.sensitivity.sensitive)

    val atGenesisBaseline = PeerSelection
      .apply(
        sync,
        Event.TargetsRequested(Targets.sync),
        t0
      )
      .state
    val reportedYoung = PeerSelection
      .apply(
        atGenesisBaseline.copy(reported = LedgerStateJudgement.YoungEnough),
        Event.JudgementReported(LedgerStateJudgement.YoungEnough),
        t0
      )
      .state
    val steady = PeerSelection.decide(reportedYoung, t0) match
      case Step.Act(decision)  => decision.state
      case Step.Idle(state, _) => state
    assertEquals(steady.targets, Targets.relay)
    assertEquals(steady.sensitivity.judgement, LedgerStateJudgement.YoungEnough)

    val intermediate = Targets.sync.withActive(4).toOption.get
    val inProgress = PeerSelection
      .apply(
        initial.copy(requested = intermediate),
        Event.JudgementReported(LedgerStateJudgement.TooOld),
        t0
      )
      .state
    val preserved = PeerSelection.decide(inProgress, t0) match
      case Step.Act(decision)  => decision.state
      case Step.Idle(state, _) => state
    assertEquals(preserved.targets, intermediate)
  }

  test("Genesis trusted-state output counts active big ledger peers") {
    val sensitivity = Sensitivity.initial(
      useBootstrapPeers = false,
      consensusMode = ConsensusMode.GenesisMode,
      minBigLedgerPeersForTrustedState = 2
    )
    val initial = GovernorState.initial[Int](7L, LocalRoots.empty, Targets.relay, sensitivity)
    val one = initial.copy(peers =
      initial.peers.updated(1, PeerRecord.cold(Origin.BigLedger).copy(status = Status.Hot))
    )
    val two = one.copy(peers =
      one.peers.updated(2, PeerRecord.cold(Origin.BigLedger).copy(status = Status.Hot))
    )
    assertEquals(
      GovernorState.outboundConnectionsState(AssociationMode.Unrestricted, one.view(t0)),
      OutboundConnectionsState.UntrustedState
    )
    assertEquals(
      GovernorState.outboundConnectionsState(AssociationMode.Unrestricted, two.view(t0)),
      OutboundConnectionsState.TrustedStateWithExternalPeers
    )
  }

  test("Genesis LocalRootsOnly trust is decided before the big-ledger threshold") {
    val trustable = config.copy(trustable = true)
    val roots = LocalRoots.fromGroups(
      List(RootGroupSpec(1, 1, List(1 -> trustable)))
    )
    val sensitivity = Sensitivity.initial(
      useBootstrapPeers = false,
      consensusMode = ConsensusMode.GenesisMode,
      minBigLedgerPeersForTrustedState = 2
    )
    val initial = GovernorState.initial[Int](7L, roots, Targets.relay, sensitivity)
    val established = initial.copy(
      localRoots = roots,
      peers = initial.peers.updated(
        1,
        PeerRecord.cold(Origin.Local).copy(status = Status.Warm(t0))
      )
    )
    assertEquals(
      GovernorState.outboundConnectionsState(AssociationMode.LocalRootsOnly, established.view(t0)),
      OutboundConnectionsState.TrustedStateWithExternalPeers
    )
  }

  test("reloading roots demotes every removed root population") {
    val initial = start(Nil)
    val withRoots = initial.copy(peers =
      initial.peers ++ Map(
        1 -> PeerRecord.cold(Origin.Public),
        2 -> PeerRecord.cold(Origin.Bootstrap),
        3 -> PeerRecord.cold(Origin.Ledger),
        4 -> PeerRecord.cold(Origin.BigLedger)
      )
    )
    val reloaded = PeerSelection
      .apply(withRoots, Event.RootsConfigured(LocalRoots.empty, false, Set.empty), t0)
      .state

    assertEquals(reloaded.peers.values.map(_.origin).toSet, Set(Origin.Other))
  }

  test("a local DNS root rotation installs origins and demotions atomically") {
    val oldRoots = LocalRoots.fromGroups(List(group(1, 1, 1)))
    val newRoots = LocalRoots.fromGroups(List(group(1, 1, 2)))
    val original = start(List(group(1, 1, 1)))
    val initial = original.copy(
      targets = Targets.relay,
      configuredRoots = oldRoots,
      localRoots = oldRoots,
      peers = original.peers.updated(
        1,
        PeerRecord.cold(Origin.Local).copy(status = Status.Hot)
      )
    )

    val rotated = PeerSelection.apply(
      initial,
      Event.RootsConfigured(newRoots, false, Set.empty, Some(BigInt(1))),
      t0
    )

    assertEquals(rotated.state.configuredRoots, newRoots)
    assertEquals(rotated.state.localRoots, newRoots)
    assertEquals(rotated.state.peers(1).origin, Origin.Other)
    assertEquals(statusOf(rotated.state, 1), Status.Deactivating)
    assertEquals(rotated.state.peers(2).origin, Origin.Local)
    assertEquals(rotated.jobs, List(Job.Deactivate(1)))
    assertEquals(GovernorState.invariant(rotated.state), Nil)
  }

  test("a DNS rotation at zero targets becomes known when targets and local roots resume") {
    val oldRoots = LocalRoots.fromGroups(List(group(1, 1, 1)))
    val newRoots = LocalRoots.fromGroups(List(group(1, 1, 2)))
    val initial = start(Nil).copy(
      configuredRoots = oldRoots,
      peers = scala.collection.immutable.SortedMap(1 -> PeerRecord.cold(Origin.Other))
    )
    val rotated = PeerSelection.apply(
      initial,
      Event.RootsConfigured(newRoots, false, Set.empty, Some(BigInt(1))),
      t0
    )
    assertEquals(rotated.state.localRoots, LocalRoots.empty[Int])
    assertEquals(GovernorState.invariant(rotated.state), Nil)

    val targetDecision = PeerSelection.decide(rotated.state, t0) match
      case Step.Act(decision) => decision
      case Step.Idle(_, _)    => fail("the configured targets should become active")
    assertEquals(GovernorState.invariant(targetDecision.state), Nil)

    val rootsDecision = PeerSelection.decide(targetDecision.state, t0) match
      case Step.Act(decision) => decision
      case Step.Idle(_, _)    => fail("configured local roots should become effective")
    assertEquals(rootsDecision.state.localRoots, newRoots)
    assertEquals(rootsDecision.state.peers(2).origin, Origin.Local)
    assertEquals(GovernorState.invariant(rootsDecision.state), Nil)
  }

  test("a sensitive DNS rotation installs only trustable local roots") {
    val trustable = config.copy(trustable = true)
    val roots = LocalRoots.fromGroups(
      List(RootGroupSpec(1, 2, List(1 -> trustable, 2 -> config)))
    )
    val sensitive = Sensitivity
      .initial(useBootstrapPeers = true)
      .copy(
        judgement = LedgerStateJudgement.TooOld
      )
    val initial = start(Nil).copy(targets = Targets.relay, sensitivity = sensitive)

    val rotated = PeerSelection.apply(
      initial,
      Event.RootsConfigured(roots, true, Set.empty, Some(BigInt(1))),
      t0
    )

    assertEquals(rotated.state.localRoots.keys.toSet, Set(1))
    assertEquals(rotated.state.peers(1).origin, Origin.Local)
    assert(!rotated.state.peers.contains(2))
    assertEquals(GovernorState.invariant(rotated.state), Nil)
  }

  test("a DNS rotation retains addresses that remain in the answer set") {
    val rootsA = LocalRoots.fromGroups(List(group(1, 1, 1)))
    val rootsAB = LocalRoots.fromGroups(List(group(1, 2, 1, 2)))
    val original = start(List(group(1, 1, 1)))
    val initial = original.copy(
      targets = Targets.relay,
      configuredRoots = rootsA,
      localRoots = rootsA,
      peers = original.peers.updated(
        1,
        PeerRecord.cold(Origin.Local).copy(status = Status.Hot)
      )
    )

    val rotated = PeerSelection.apply(
      initial,
      Event.RootsConfigured(rootsAB, false, Set.empty, Some(BigInt(1))),
      t0
    )

    assertEquals(statusOf(rotated.state, 1), Status.Hot)
    assertEquals(rotated.state.peers(1).origin, Origin.Local)
    assertEquals(rotated.state.peers(2).origin, Origin.Local)
    assertEquals(rotated.jobs, Nil)
    assertEquals(GovernorState.invariant(rotated.state), Nil)
  }

  test("a delayed older roots event cannot replace a newer installed generation") {
    val initial = start(Nil)
    val newerRoots = LocalRoots.fromGroups(List(group(1, 1, 22)))
    val installed = PeerSelection
      .apply(
        initial,
        Event.RootsConfigured(newerRoots, false, Set(22), Some(BigInt(2))),
        t0
      )
      .state
    val delayed = PeerSelection
      .apply(
        installed,
        Event.RootsConfigured(LocalRoots.empty, false, Set.empty, Some(BigInt(1))),
        t0
      )
      .state

    assertEquals(installed.rootsGeneration, BigInt(2))
    assertEquals(delayed.rootsGeneration, BigInt(2))
    assertEquals(delayed.configuredRoots, newerRoots)
  }

  test("roots generation changes release pending connects and close late successes") {
    val (connecting, _, _) = settle(start(List(group(1, 1, 7))), t0)
    assertEquals(statusOf(connecting, 7), Status.Connecting)

    val reloaded = PeerSelection
      .apply(
        connecting,
        Event.RootsConfigured(LocalRoots.empty, false, Set.empty, Some(BigInt(1))),
        t0
      )
      .state
    assertEquals(statusOf(reloaded, 7), Status.Cold(None))

    val late = PeerSelection.apply(
      reloaded,
      Event.Completed(Outcome.Connected(7, BigInt(0))),
      t0
    )
    assertEquals(late.jobs, List(Job.Close(7, Some(BigInt(0)))))
    assertEquals(statusOf(late.state, 7), Status.Cold(None))
  }

  test("a root request completing after reload cannot restore the previous roots") {
    val initial = start(Nil).copy(publicRootsInFlight = true, bigLedgerInFlight = true)
    val reloaded = PeerSelection
      .apply(initial, Event.RootsConfigured(LocalRoots.empty, false, Set.empty), t0)
      .state
    val staleRoots = PeerSelection
      .apply(
        reloaded,
        Event.Completed(
          Outcome.RootsFound(
            PublicRoots(Set(8), Set.empty),
            5.seconds,
            initial.rootsGeneration
          )
        ),
        t0
      )
      .state
    val staleBigLedger = PeerSelection
      .apply(
        staleRoots,
        Event.Completed(
          Outcome.BigLedgerFound(Set(9), 5.seconds, initial.rootsGeneration)
        ),
        t0
      )
      .state

    assertEquals(reloaded.rootsGeneration, initial.rootsGeneration + 1)
    assert(!reloaded.publicRootsInFlight && !reloaded.bigLedgerInFlight)
    assertEquals(staleBigLedger.peers.get(8), None)
    assertEquals(staleBigLedger.peers.get(9), None)
    assertEquals(staleBigLedger.rootsGeneration, reloaded.rootsGeneration)
  }

  test("churn targets computed under an old roots generation are ignored") {
    val initial = start(Nil).copy(rootsGeneration = BigInt(2), requested = Targets.relay)
    val stale = PeerSelection.apply(
      initial,
      Event.TargetsRequested(Targets.sync, Some(BigInt(1))),
      t0
    )
    assertEquals(stale.state.requested, Targets.relay)
  }

  test(
    "the decision, the events and the invariant need no deep stack for thousands of peers (ADR 0011)"
  ) {
    val peers = 4000
    val result =
      new java.util.concurrent.atomic.AtomicReference[Either[Throwable, (Int, List[String])]]
    val worker = new Thread(
      null,
      () =>
        result.set(
          scala.util.Try {
            val st0 = start(List(group(peers, peers, (1 to peers)*)))
            val (settled, jobs, _) = settle(st0, t0)
            val connects = jobs.collect { case Job.Connect(p, _, _, _) => p }
            val connected =
              connects.foldLeft(settled)((s, p) => complete(s, Outcome.Connected(p), t0))
            val (after, _, _) = settle(connected, t0)
            (connects.size, GovernorState.invariant(after).toList.map(_.toString))
          }.toEither
        ),
      "small-stack",
      256L * 1024
    )
    worker.start()
    worker.join()
    result.get match
      case Right((connects, broken)) =>
        assert(connects > 0)
        assertEquals(broken, Nil)
      case Left(e) => fail(s"the governor needs a deep stack: $e")
  }
