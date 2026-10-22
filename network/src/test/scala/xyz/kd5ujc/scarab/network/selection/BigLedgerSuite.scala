// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network.selection

import munit.FunSuite
import scala.concurrent.duration.*

/** The big ledger peers in the decision function, by example: the six families of the reference
  * (`BigLedgerPeers.belowTarget` and `aboveTarget`, `EstablishedPeers` and `ActivePeers`
  * `belowTargetBigLedgerPeers` and `aboveTargetBigLedgerPeers`), their completions, and the rule
  * that the ordinary families do not see them.
  */
class BigLedgerSuite extends FunSuite:
  private type S = GovernorState[Int]
  private val t0: Time = Duration.Zero

  private def targets(
      root: Int = 0,
      known: Int = 0,
      established: Int = 0,
      active: Int = 0,
      knownBig: Int = 0,
      establishedBig: Int = 0,
      activeBig: Int = 0
  ): Targets =
    Targets
      .validated(root, known, established, active, knownBig, establishedBig, activeBig)
      .fold(p => fail(p.message), identity)

  private def start(ts: Targets, bootstrap: Boolean = false): S =
    GovernorState
      .initial[Int](3L, LocalRoots.empty[Int], ts, Sensitivity.initial(bootstrap))
      .copy(targets = ts)

  private def big(status: Status = Status.Cold(None)): PeerRecord =
    PeerRecord.cold(Origin.BigLedger).copy(status = status)

  private def withPeers(st: S, peers: (Int, PeerRecord)*): S =
    st.copy(peers = peers.foldLeft(st.peers)((m, e) => m.updated(e._1, e._2)))

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

  private def count(jobs: List[Job[Int]])(f: PartialFunction[Job[Int], Unit]): Int =
    jobs.count(f.isDefinedAt)

  // ------------------------------------------------------------------ discovering

  test("below the known big ledger target big ledger peers are requested once, for the shortfall") {
    val st = withPeers(start(targets(knownBig = 5, establishedBig = 0)), 1 -> big(), 2 -> big())
    val (after, jobs, traces) = settle(st, t0)
    assertEquals(jobs, List(Job.RequestBigLedger(3)))
    assertEquals(traces, List(Trace.BigLedgerRequest(5, 2)))
    assert(after.bigLedgerInFlight)
  }

  test("the request is not made while one is in flight, nor before the retry time") {
    val st = start(targets(knownBig = 5))
    val (inFlight, _, _) = settle(st, t0)
    assertEquals(settle(inFlight, 1.second)._2, Nil)
    // an answer without a new peer backs off by 2^1 seconds, the next 2^2, as for the public roots
    val one =
      complete(inFlight, Outcome.BigLedgerFound(BigLedgerRoots.Ledger(Set.empty), 5.seconds), t0)
    assertEquals((one.bigLedgerBackoffs, one.bigLedgerRetryAt), (1, 2.seconds))
    assertEquals(settle(one, 1.second)._2, Nil)
    val Step.Idle(_, wake) = PeerSelection.decide(one, 1.second): @unchecked
    assertEquals(wake, Some(2.seconds))
    val (again, jobs, _) = settle(one, 2.seconds)
    assertEquals(jobs, List(Job.RequestBigLedger(5)))
    val two = complete(
      again,
      Outcome.BigLedgerFound(BigLedgerRoots.Ledger(Set.empty), 5.seconds),
      2.seconds
    )
    assertEquals((two.bigLedgerBackoffs, two.bigLedgerRetryAt), (2, 6.seconds))
  }

  test("a failed request moves the counter towards failure: 2, 4, ... seconds, at most 2^8") {
    val st = start(targets(knownBig = 5))
    val (inFlight, _, _) = settle(st, t0)
    val failed = complete(inFlight, Outcome.BigLedgerFailed(), t0)
    assertEquals((failed.bigLedgerBackoffs, failed.bigLedgerRetryAt), (-1, 2.seconds))
    val capped = (1 to 12).foldLeft(failed) { (s, i) =>
      complete(s.copy(bigLedgerInFlight = true), Outcome.BigLedgerFailed(), i.seconds)
    }
    assertEquals(capped.bigLedgerRetryAt, 12.seconds + 256.seconds)
  }

  test("an answer makes the new peers big ledger peers, and leaves the roots as they were") {
    val roots = LocalRoots.fromGroups(
      List(
        RootGroupSpec(
          1,
          1,
          List(
            1 -> RootConfig(
              false,
              DiffusionMode.InitiatorAndResponder,
              xyz.kd5ujc.scarab.protocol.ConnectionState.Provenance.Outbound,
              false
            )
          )
        )
      )
    )
    val base = GovernorState
      .initial[Int](3L, roots, targets(known = 10, knownBig = 6), Sensitivity.initial(false))
    val st = withPeers(
      base.copy(
        targets = targets(known = 10, knownBig = 6),
        localRoots = roots,
        bigLedgerInFlight = true
      ),
      1 -> PeerRecord.cold(Origin.Local),
      2 -> PeerRecord.cold(Origin.Ledger),
      3 -> PeerRecord.cold(Origin.Other)
    )
    val done =
      complete(st, Outcome.BigLedgerFound(BigLedgerRoots.Ledger(Set(1, 2, 3, 4)), 5.seconds), t0)
    assertEquals(done.peers(1).origin, Origin.Local)
    assertEquals(done.peers(2).origin, Origin.Ledger)
    // a peer that was no root becomes a big ledger peer, and a new one is known
    assertEquals(done.peers(3).origin, Origin.BigLedger)
    assertEquals(done.peers(4).origin, Origin.BigLedger)
    assertEquals((done.bigLedgerBackoffs, done.bigLedgerInFlight), (0, false))
    assertEquals(done.bigLedgerRetryAt, 5.seconds)
  }

  test("an ordinary answer does not turn a known big ledger peer into an ordinary one") {
    val base = start(targets(root = 5, known = 10))
    val st = withPeers(base.copy(publicRootsInFlight = true), 9 -> big())
    val done = complete(
      st,
      Outcome.RootsFound(PublicRoots(Set.empty[Int], Set.empty[Int], Set(9, 10)), 5.seconds),
      t0
    )
    assertEquals(done.peers(9).origin, Origin.BigLedger)
    assertEquals(done.peers(10).origin, Origin.Ledger)
  }

  test("above the known big ledger target cold big ledger peers are forgotten, not the others") {
    val st = withPeers(
      start(targets(known = 10, knownBig = 2)),
      1 -> big(),
      2 -> big(),
      3 -> big(),
      4 -> big(Status.Warm(t0)),
      5 -> PeerRecord.cold(Origin.Other)
    )
    val (after, _, traces) = settle(st, t0)
    // four big ledger peers, one established: two of the three cold ones go (target 2)
    assertEquals(after.view(t0).big.known.size, 2)
    assert(after.peers.contains(4) && after.peers.contains(5))
    assert(traces.exists { case Trace.ForgetBigLedger(2, 4, _) => true; case _ => false })
  }

  // ------------------------------------------------------------------ the families

  test("cold big ledger peers are connected up to the established target, as duplex outbound") {
    val st = withPeers(
      start(targets(establishedBig = 2, knownBig = 3)),
      1 -> big(),
      2 -> big(),
      3 -> big()
    )
    val (after, jobs, _) = settle(st, t0)
    assertEquals(
      count(jobs) { case Job.Connect(_, DiffusionMode.InitiatorAndResponder, _, _) => () },
      2
    )
    assertEquals(after.view(t0).counters.coldBigLedgerPromotions, 2)
  }

  test("connected big ledger peers are activated up to the active target, and no more") {
    val st = withPeers(
      start(targets(establishedBig = 3, activeBig = 2, knownBig = 3)),
      1 -> big(Status.Warm(t0)),
      2 -> big(Status.Warm(t0)),
      3 -> big(Status.Warm(t0))
    )
    val (after, jobs, _) = settle(st, t0)
    assertEquals(count(jobs) { case Job.Activate(_, _) => () }, 2)
    assertEquals(after.view(t0).counters.warmBigLedgerPromotions, 2)
    // a peer that is not ready yet is waited for
    val later = withPeers(
      start(targets(establishedBig = 1, activeBig = 1, knownBig = 1)),
      1 -> big(Status.Warm(30.seconds))
    )
    val Step.Idle(_, wake) = PeerSelection.decide(later, t0): @unchecked
    assertEquals(wake, Some(30.seconds))
  }

  test("above the established big ledger target warm big ledger peers are closed, not the hot") {
    val st = withPeers(
      start(targets(establishedBig = 1, activeBig = 1, knownBig = 5)),
      1 -> big(Status.Hot),
      2 -> big(Status.Warm(t0)),
      3 -> big(Status.Warm(t0)),
      4 -> big(Status.Warm(t0))
    )
    val (_, jobs, traces) = settle(st, t0)
    assertEquals(count(jobs) { case Job.Close(_, _) => () }, 3)
    assert(traces.exists {
      case Trace.DemoteBigLedger(Temperature.Warm, _, 1, 4, _) => true; case _ => false
    })
  }

  test("above the active big ledger target hot big ledger peers are deactivated") {
    val st = withPeers(
      start(targets(establishedBig = 5, activeBig = 2, knownBig = 5)),
      1 -> big(Status.Hot),
      2 -> big(Status.Hot),
      3 -> big(Status.Hot),
      4 -> big(Status.Hot)
    )
    val (_, jobs, _) = settle(st, t0)
    assertEquals(count(jobs) { case Job.Deactivate(_) => () }, 2)
  }

  // ------------------------------------------------------------------ a population of its own

  test(
    "the ordinary families do not see big ledger peers, and the ordinary counters leave them out"
  ) {
    // ordinary targets of zero and big ledger peers everywhere: nothing ordinary is demoted,
    // forgotten or closed, and the big ones stay as the big targets say
    val st = withPeers(
      start(targets(establishedBig = 2, activeBig = 1, knownBig = 3)),
      1 -> big(Status.Hot),
      2 -> big(Status.Warm(t0)),
      3 -> big()
    )
    val (after, jobs, _) = settle(st, t0)
    assertEquals(jobs, Nil)
    val c = after.view(t0).counters
    assertEquals((c.known, c.established, c.active), (0, 0, 0))
    assertEquals((c.knownBigLedger, c.establishedBigLedger, c.activeBigLedger), (3, 2, 1))
    assertEquals((c.coldBigLedger, c.warmBigLedger, c.hotBigLedger), (1, 1, 1))
    // they are roots (every public root counts towards the root target)
    assertEquals(c.root, 3)
    assertEquals(c.byOrigin(Origin.BigLedger), 3)
  }

  test("the big ledger sets nest as the ordinary ones do, in any state of the governor") {
    val st = withPeers(
      start(
        targets(
          known = 20,
          established = 10,
          active = 5,
          knownBig = 6,
          establishedBig = 4,
          activeBig = 2
        )
      ),
      (1 to 12).map(p =>
        p -> big(
          if p <= 3 then Status.Hot else if p <= 6 then Status.Warm(t0) else Status.Cold(None)
        )
      )*
    )
    val (after, _, _) = settle(st, t0)
    val v = after.view(t0)
    assert(v.big.active.subsetOf(v.big.established))
    assert(v.big.established.subsetOf(v.big.known))
    assert(v.big.known.intersect(v.known).isEmpty)
    assertEquals(GovernorState.invariant(after), Nil)
  }

  // ------------------------------------------------------------------ the sensitive state

  test(
    "in the sensitive state nothing is requested, connected or activated, but the targets of zero shed them"
  ) {
    val ts = targets(knownBig = 5, establishedBig = 3, activeBig = 2)
    val st0 = withPeers(
      start(ts, bootstrap = true),
      1 -> big(),
      2 -> big(),
      3 -> big(Status.Warm(t0))
    )
    val tooOld = st0.copy(
      sensitivity = st0.sensitivity.copy(judgement = LedgerStateJudgement.TooOld),
      reported = LedgerStateJudgement.TooOld
    )
    // no progress: the shortfalls are not acted on
    assertEquals(settle(tooOld, t0)._2, Nil)
    // the targets of zero a node sets on becoming too old: the warm big ledger peer is closed and
    // the cold ones are forgotten
    val zero = tooOld.copy(targets = Targets.zero)
    val (after, jobs, _) = settle(zero, t0)
    assertEquals(jobs, List(Job.Close(3, None)))
    assertEquals(after.view(t0).big.known.size, 1)
  }

  test("the clean state is reached only once the big ledger peers are forgotten") {
    val st = withPeers(start(Targets.zero, bootstrap = true), 7 -> big())
    val tooOld = st.copy(
      sensitivity = st.sensitivity.copy(judgement = LedgerStateJudgement.TooOld),
      reported = LedgerStateJudgement.TooOld
    )
    // a big ledger peer is not trusted: it is not known when the node is clean
    assert(!tooOld.copy(targets = targets(knownBig = 1)).peers.isEmpty)
    val (settled, _, traces) = settle(tooOld, t0)
    val forgotten = traces.indexWhere {
      case Trace.ForgetBigLedger(_, _, _) => true; case _ => false
    }
    val clean = traces.indexOf(Trace.OnlyBootstrapPeers())
    assert(forgotten >= 0 && clean > forgotten, traces.toString)
    assert(settled.sensitivity.hasOnlyBootstrapPeers && settled.peers.isEmpty)
  }

  // ------------------------------------------------------------------ the transition

  test(
    "young enough again: the request for ledger peers starts afresh and big ledger peers are asked for"
  ) {
    val ts = targets(root = 10, known = 20, established = 5, active = 2, knownBig = 4)
    val st = withPeers(
      start(ts, bootstrap = true),
      1 -> PeerRecord.cold(Origin.Bootstrap).copy(status = Status.Hot),
      2 -> PeerRecord.cold(Origin.Bootstrap)
    )
    val tooOld = st.copy(
      sensitivity = st.sensitivity.copy(judgement = LedgerStateJudgement.TooOld),
      reported = LedgerStateJudgement.TooOld
    )
    // in the sensitive state the node asks for bootstrap peers only
    val (_, jobsOld, _) = settle(tooOld, t0)
    assert(jobsOld.forall { case Job.RequestBigLedger(_, _) => false; case _ => true })
    val young = PeerSelection
      .apply(tooOld, Event.JudgementReported(LedgerStateJudgement.YoungEnough), t0)
      .state
    val (after, jobs, _) = settle(young, t0)
    // the bootstrap peer we are not connected to is forgotten, the hot one stays, and both kinds of
    // ledger peers are asked for
    assert(!after.peers.contains(2) && after.peers.contains(1))
    assert(jobs.contains(Job.RequestRoots(9, false)), jobs.toString)
    assert(jobs.contains(Job.RequestBigLedger(4)), jobs.toString)
  }

  test(
    "a ledger answer to the ordinary request makes ledger peers, which replace the bootstrap ones"
  ) {
    val ts = targets(root = 4, known = 10, established = 4, active = 2)
    val st = withPeers(
      start(ts).copy(publicRootsInFlight = true),
      1 -> PeerRecord.cold(Origin.Bootstrap).copy(status = Status.Hot)
    )
    val done = complete(
      st,
      Outcome.RootsFound(PublicRoots(Set.empty[Int], Set.empty[Int], Set(2, 3, 4)), 5.seconds),
      t0
    )
    assertEquals(Set(2, 3, 4).map(done.peers(_).origin), Set(Origin.Ledger))
    val (after, jobs, _) = settle(done, t0)
    assertEquals(count(jobs) { case Job.Connect(_, _, _, _) => () }, 3)
    assertEquals(after.view(t0).counters.byOrigin(Origin.Ledger), 3)
  }

  // ------------------------------------------------------------------ stack safety (ADR 0011)

  test("ten thousand big ledger peers are decided on a thread with a small stack") {
    val ts = targets(
      known = 100,
      knownBig = 50,
      establishedBig = 20,
      activeBig = 10,
      established = 30,
      active = 20
    )
    val peers = (1 to 10000).map(p => p -> big()).toList
    var steps = -1
    val worker = new Thread(
      null,
      () =>
        val st = withPeers(start(ts), peers*)
        steps = settle(st, t0)._2.size
      ,
      "small-stack",
      256 * 1024
    )
    worker.start()
    worker.join()
    assert(steps >= 0, "the decision overflowed the stack or failed")
  }

  test("a big-ledger fallback discovers ordinary roots without increasing big-ledger counts") {
    val initial = withPeers(
      start(targets(known = 10, knownBig = 6)).copy(bigLedgerInFlight = true),
      2 -> PeerRecord.cold(Origin.Other).copy(advertise = Some(true))
    )
    val fallback = BigLedgerRoots.Fallback(
      PublicRoots(Set(1, 2), Set.empty[Int], advertise = Map(1 -> true, 2 -> false))
    )
    val done = complete(initial, Outcome.BigLedgerFound(fallback, 900.seconds), t0)
    assertEquals(done.peers.values.map(_.origin).toSet, Set(Origin.Public))
    assertEquals(done.view(t0).big.known, Set.empty[Int])
    assertEquals(done.peers(1).advertise, Some(false))
    assertEquals(done.peers(2).advertise, Some(false))
    assertEquals(done.bigLedgerBackoffs, 0)
    assertEquals(done.bigLedgerRetryAt, 900.seconds)
    assert(!done.bigLedgerInFlight)
  }

  test("a fallback preserves existing local and ledger root classifications") {
    val initial = withPeers(
      start(targets(known = 10, knownBig = 6)).copy(bigLedgerInFlight = true),
      1 -> PeerRecord.cold(Origin.Local),
      2 -> big()
    )
    val done = complete(
      initial,
      Outcome.BigLedgerFound(
        BigLedgerRoots.Fallback(PublicRoots(Set(1, 2, 3), Set.empty[Int])),
        900.seconds
      ),
      t0
    )
    assertEquals(done.peers(1).origin, Origin.Local)
    assertEquals(done.peers(2).origin, Origin.BigLedger)
    assertEquals(done.peers(3).origin, Origin.Public)
    assertEquals(done.view(t0).big.known, Set(2))
  }
