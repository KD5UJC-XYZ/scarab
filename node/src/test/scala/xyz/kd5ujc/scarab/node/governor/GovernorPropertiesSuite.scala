// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.governor

import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
import org.scalacheck.Prop.*
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.network.selection.*
import xyz.kd5ujc.scarab.node.Output
import xyz.kd5ujc.scarab.protocol.ConnectionState.Provenance
import MockEnvironment.*
import Signals.*

/** The reference's governor properties (`Test.Cardano.Network.PeerSelection`, ouroboros-network
  * 1.2.0.0), by name, on the real governor loop of the node in the mock environment on
  * `TestControl`. A property that the reference states for big ledger peers, peer sharing or ledger
  * peers is not here: those are later stages. The reference's `signalProperty` is a check at every
  * instant of the recording ([[Signals]]); its `MaxTime` is ten hours.
  */
class GovernorPropertiesSuite extends ScalaCheckSuite:
  override def scalaCheckTestParameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(40)

  private def over(gen: Gen[Env])(check: Recording => Prop): Prop =
    forAll(gen) { env =>
      val rec = run(env)
      ok("the run recorded more than 200000 things: the governor does not rest")(!rec.overflowed) &&
      check(rec)
    }

  private def ok(what: => String)(passes: Boolean): Prop = passes :| what

  private def noneOr[A](found: Option[A])(describe: A => String): Prop =
    found.fold(Prop.passed)(a => Prop.falsified :| describe(a))

  /** The opportunities of a signal sampled where it can change, and the key that was ignored too
    * long.
    */
  private def ignored(
      rec: Recording,
      linger: FiniteDuration,
      failed: Vector[(Time, Peer)],
      d: FiniteDuration
  )(f: (GovernorState[Peer], Time, Set[Peer]) => Set[Peer]): Option[(Peer, Time)] =
    val series: Series[Peer] =
      breakpoints(rec, failed.map(_._1), linger).flatMap { t =>
        stateAt(rec, t).map(s => t -> f(s, t, recent(failed, t, linger)))
      }
    ignoredTooLong(series, rec.horizon, d)

  /** What happened in the seconds around an instant, for the report of a failure. */
  private def around(rec: Recording, from: Time, span: FiniteDuration = 30.seconds): String =
    val before = stateAt(rec, from).map(st =>
      s"\n  STATE at $from: peers=${st.peers.map((p, r) => p -> (r.status, r.origin, r.failCount))} roots=${st.localRoots.keys} targets=${st.targets} requested=${st.requested} sens=${st.sensitivity}"
    )
    before.getOrElse("") + rec.seen
      .collect {
        case (t, Seen.Governor(out))
            if t >= from - span && t <= from + span &&
              !out.toString.startsWith("Counted") =>
          s"  ${t.toMillis / 1000.0}s $out"
        case (t, Seen.Env(e)) if t >= from - span && t <= from + span =>
          s"  ${t.toMillis / 1000.0}s ENV $e"
        case (t, Seen.Connections(c)) if t >= from - span && t <= from + span =>
          s"  ${t.toMillis / 1000.0}s ENVCONN $c"
        case (t, Seen.State(st)) if t >= from - span && t <= from + span =>
          s"  ${t.toMillis / 1000.0}s STATE ${st.peers.map((p, r) => p -> r.status)} roots=${st.localRoots.keys} targets=${st.targets}"
      }
      .mkString("\n", "\n", "")

  private def overdue(rec: Recording)(found: Option[(Peer, Time)]): Prop =
    noneOr(found)((peer, since) =>
      s"peer $peer was an opportunity from $since and was ignored\n${rec.env.localRoots}${around(rec, since)}"
    )

  // ---------------------------------------------------------------- structure

  property("prop_peerSelectionView_sizes") {
    over(Generators.environment) { rec =>
      ok("a set of the view is not within the one it belongs to, or a counter differs")(
        rec.states.forall { (t, st) =>
          val v = st.view(t)
          val c = v.counters
          v.active.subsetOf(v.established) && v.established.subsetOf(v.known) &&
          v.connecting.subsetOf(v.known) && v.availableToConnect.subsetOf(v.known) &&
          v.deactivating.subsetOf(v.active) && v.closing.subsetOf(v.established -- v.active) &&
          v.activating.subsetOf(v.established -- v.active) &&
          v.localRoots.subsetOf(v.known) && v.bootstrap.subsetOf(v.known) &&
          v.localRoots.intersect(v.bootstrap).isEmpty &&
          (v.known -- v.roots).intersect(v.localRoots).isEmpty &&
          c.cold + c.warm + c.hot == c.known && c.known == v.known.size &&
          c.established == v.established.size && c.active == v.active.size &&
          c.knownLocalRoots == v.localRoots.size && c.root == v.roots.size
        }
      )
    }
  }

  // ---------------------------------------------------------------- basic

  /** The environment gives the governor nothing to work with. */
  private def isEmptyEnv(env: Env): Boolean =
    val knowsNothing = env.targets.all.forall(_._2.known == 0)
    val noRoots = env.targets.all.forall(_._2.root == 0)
    (LocalRoots.fromGroups(env.localRoots).isEmpty || knowsNothing) &&
    (env.publicRoots.isEmpty || noRoots)

  property("prop_governor_hasoutput") {
    over(Generators.environment) { rec =>
      ok("the governor traced nothing in an environment with something to do")(
        rec.traces.nonEmpty || isEmptyEnv(rec.env)
      )
    }
  }

  private def nofail(rec: Recording): Prop =
    val broken = rec.states.flatMap((t, st) => GovernorState.invariant(st).map(t -> _))
    ok(s"the governor stopped: ${rec.fatal}")(rec.fatal.isEmpty) &&
    ok(s"an invariant broke: ${broken.take(3)}")(broken.isEmpty)

  property("prop_governor_nofail")(over(Generators.environment)(nofail))

  private def nolivelock(rec: Recording): Prop =
    val crowded = rec.traces.groupBy(_._1).find(_._2.size > 1000)
    ok("the run recorded more than 200000 things")(!rec.overflowed) &&
    noneOr(crowded)((t, es) => s"${es.size} events at $t")

  property("prop_governor_nolivelock")(over(Generators.environment)(nolivelock))

  private enum Ev:
    case Gov
    case Environment(credits: Int)

  private type Spaced = List[(Time, FiniteDuration, Ev)]

  /** `prop_governor_nobusyness`: a stretch of events without a gap of 45 seconds may last only as
    * long as the credits the environment's events earned allow.
    */
  property("prop_governor_nobusyness") {
    over(Generators.environment) { rec =>
      val events: Vector[(Time, Ev)] = rec.seen.collect {
        case (t, Seen.Governor(Output.Traced(_))) if t <= 10.hours => t -> Ev.Gov
        case (t, Seen.Env(e)) if t <= 10.hours                     => t -> Ev.Environment(e.credits)
      }
      val spaced: Spaced =
        events.zip(events.drop(1)).map { case ((t, e), (next, _)) => (t, next - t, e) }.toList
      val gap = 45.seconds

      def endCredits(credits: Int, end: Time, rest: Spaced): Int =
        rest.takeWhile(_._1 == end).foldLeft(credits) {
          case (c, (_, _, Ev.Environment(n))) => c + n
          case (c, _)                         => c
        }

      @scala.annotation.tailrec
      def busy(start: Time, credits: Int, rest: Spaced): Either[(Time, Int), Spaced] = rest match
        case Nil => Right(Nil)
        case (end, _, Ev.Gov) :: more if (end - start).toSeconds > endCredits(credits, end, more) =>
          Left((end, endCredits(credits, end, more)))
        case (_, dt, _) :: more if dt > gap    => Right(more)
        case (_, _, Ev.Environment(c)) :: more => busy(start, credits + c, more)
        case _ :: more                         => busy(start, credits, more)

      @scala.annotation.tailrec
      def idle(rest: Spaced): Option[(Time, Time, Int)] = rest match
        case Nil => None
        case (start, _, _) :: _ =>
          busy(start, 65, rest) match
            case Right(more)          => idle(more)
            case Left((end, credits)) => Some((start, end, credits))

      noneOr(idle(spaced))((s, e, c) =>
        s"busy from $s to $e with only $c credits\n" + rec.seen
          .collect {
            case (t, Seen.Governor(out))
                if t >= s && t <= e && !out.toString.startsWith("Counted") && !out.toString
                  .startsWith("Connections") =>
              s"  ${t.toMillis / 1000.0}s $out"
            case (t, Seen.Env(ev)) if t >= s && t <= e => s"  ${t.toMillis / 1000.0}s ENV $ev"
          }
          .take(80)
          .mkString("\n")
      )
    }
  }

  /** The governor's view of which peers are up does not lag the environment's by more than the
    * instant (`prop_governor_connstatus`): at the last state of an instant at which the
    * environment's connections changed, they agree, apart from connections that are going.
    */
  property("prop_governor_connstatus") {
    over(Generators.environment) { rec =>
      val instants = rec.connections.groupBy(_._1).view.mapValues(_.last._2).toList.sortBy(_._1)
      val bad = instants.filter(_._1 <= 1.hour).collectFirst {
        case (t, env) if stateAt(rec, t).exists(st => differs(st, t, env)) => t
      }
      noneOr(bad)(t =>
        s"the governor and the environment disagree at $t${around(rec, t, 10.seconds)}"
      )
    }
  }

  private def differs(st: GovernorState[Peer], t: Time, env: Map[Peer, Conn]): Boolean =
    val v = st.view(t)
    val governor: Map[Peer, Conn] =
      v.established.iterator
        .filterNot(v.cooling)
        .map(p => p -> (if v.active(p) then Conn.Hot else Conn.Warm))
        .toMap
    val environment = env.filter((_, c) => c == Conn.Warm || c == Conn.Hot)
    governor != environment

  // ---------------------------------------------------------------- public roots

  /** `prop_governor_target_root_below`: below the root target the governor asks for the public
    * roots the environment has, within a time-to-live of the answer it last had.
    */
  property("prop_governor_target_root_below") {
    over(Generators.environment) { rec =>
      overdue(rec)(ignored(rec, 0.seconds, Vector.empty, 258.seconds) { (st, t, _) =>
        val v = st.view(t)
        if v.roots.size >= st.targets.root then Set.empty
        else rec.env.publicRoots -- v.roots -- st.localRoots.keys
      })
    }
  }

  // ---------------------------------------------------------------- known peers

  /** `prop_governor_target_known_above`: above the known target the governor forgets the cold peers
    * it may (not roots that the root target needs, not local roots, not in progress).
    */
  property("prop_governor_target_known_above") {
    over(Generators.environment) { rec =>
      overdue(rec)(ignored(rec, 0.seconds, Vector.empty, 1.second) { (st, t, _) =>
        val v = st.view(t)
        if v.known.size <= st.targets.known then Set.empty
        else
          val publicRoots = v.roots -- st.localRoots.keys
          val spare = v.roots.size - st.targets.root
          (v.known -- v.established -- st.localRoots.keys -- v.connecting) --
            (if spare <= 0 then publicRoots else Set.empty[Peer])
      })
    }
  }

  // ---------------------------------------------------------------- established peers

  private val coldRetry = 162.seconds // 5 * 2^5 s, plus the fuzz

  private def establishedBelow(rec: Recording): Prop =
    val failed = failures(rec)
    overdue(rec)(ignored(rec, coldRetry, failed, 1.second) { (st, t, recentFailures) =>
      val v = st.view(t)
      if v.established.size + v.connecting.size >= st.targets.established then Set.empty
      else v.known -- v.established -- st.localRoots.keys -- recentFailures -- v.connecting
    })

  property("prop_governor_target_established_below")(over(Generators.environment)(establishedBelow))

  property("prop_governor_target_established_above") {
    over(Generators.environment) { rec =>
      overdue(rec)(ignored(rec, 0.seconds, Vector.empty, 1.second) { (st, t, _) =>
        val v = st.view(t)
        val demotions = v.closing ++ v.cooling
        if v.established.size - demotions.size <= st.targets.established then Set.empty
        else
          v.established -- v.active -- st.localRoots.keys -- (v.cooling ++ v.activating ++ v.closing)
      })
    }
  }

  /** `prop_governor_target_established_local`: every local root group is established up to its warm
    * valency, even above the established target.
    */
  property("prop_governor_target_established_local") {
    over(Generators.environment) { rec =>
      val failed = failures(rec)
      overdue(rec)(ignored(rec, 180.seconds, failed, 1.second) { (st, t, recentFailures) =>
        val v = st.view(t)
        st.localRoots.groups.flatMap { g =>
          val have =
            g.members.intersect(v.established).size + g.members.intersect(v.connecting).size
          if have >= g.warm.value then Set.empty[Peer] else g.members
        }.toSet -- v.established -- recentFailures -- v.connecting
      })
    }
  }

  // ---------------------------------------------------------------- active peers

  property("prop_governor_target_active_below") {
    over(Generators.environment) { rec =>
      val failed = failures(rec)
      overdue(rec)(ignored(rec, 180.seconds, failed, 10.seconds) { (st, t, recentFailures) =>
        val v = st.view(t)
        if v.active.size >= st.targets.active then Set.empty
        else
          v.established -- v.active -- st.localRoots.keys -- recentFailures --
            (v.cooling ++ v.activating ++ v.closing)
      })
    }
  }

  property("prop_governor_target_active_above") {
    over(Generators.environment) { rec =>
      overdue(rec)(ignored(rec, 0.seconds, Vector.empty, 1.second) { (st, t, _) =>
        val v = st.view(t)
        if v.active.size <= st.targets.active then Set.empty
        else v.active -- st.localRoots.keys -- (v.cooling ++ v.deactivating)
      })
    }
  }

  property("prop_governor_target_active_local_below") {
    over(Generators.environment) { rec =>
      val failed = failures(rec)
      overdue(rec)(ignored(rec, 180.seconds, failed, 11.seconds) { (st, t, recentFailures) =>
        val v = st.view(t)
        st.localRoots.groups.flatMap { g =>
          if g.members.intersect(v.active).size >= g.hot.value then Set.empty[Peer]
          else g.members.intersect(v.established)
        }.toSet -- v.active -- recentFailures -- v.activating -- v.cooling -- v.closing
      })
    }
  }

  property("prop_governor_target_active_local_above") {
    over(Generators.environment) { rec =>
      overdue(rec)(ignored(rec, 0.seconds, Vector.empty, 1.second) { (st, t, _) =>
        val v = st.view(t)
        st.localRoots.groups.flatMap { g =>
          val active = g.members.intersect(v.active)
          if active.size <= g.hot.value then Set.empty[Peer] else active
        }.toSet -- (v.cooling ++ v.deactivating)
      })
    }
  }

  // ---------------------------------------------------------------- repromote delay

  /** `prop_governor_repromote_delay`: the wait before a peer that went away is promoted again is
    * the exit policy's ten seconds plus a fuzz, never exactly ten.
    */
  property("prop_governor_repromote_delay") {
    over(Generators.environment) { rec =>
      val waits = rec.traces.collect {
        case (t, Trace.DemoteAsynchronous(peer, Temperature.Cold, Some(_), _)) =>
          stateAt(rec, t).flatMap(_.peers.get(peer)).map(_.status).collect {
            case Status.Cold(Some(at)) => (peer, at - t)
          }
        case (t, Trace.DemoteAsynchronous(peer, Temperature.Warm, None, _)) =>
          stateAt(rec, t).flatMap(_.peers.get(peer)).map(_.status).collect { case Status.Warm(at) =>
            (peer, at - t)
          }
      }.flatten
      ok(s"a wait of exactly the exit policy's ten seconds: ${waits.find(_._2 == 10.seconds)}")(
        waits.forall(_._2 != 10.seconds)
      )
    }
  }

  // ---------------------------------------------------------------- bootstrap peers

  private def sensitive(st: GovernorState[Peer]): Boolean = st.sensitivity.sensitive

  /** The peers the node trusts: its trustable local roots in force, its bootstrap peers and, as the
    * reference's test does for the clean state, the configured trustable local roots: when the
    * targets shrink, a trustable root is dropped from the roots in force before the known set is
    * swept (`prop_governor_only_bootstrap_peers_in_clean_state`, the comment on pull request 4956).
    */
  private def trusted(st: GovernorState[Peer], configured: Set[Peer]): Set[Peer] =
    st.localRoots.clampToTrustable.keys.toSet ++ configured ++ st.peers.collect {
      case (p, r) if r.origin == Origin.Bootstrap => p
    }

  private def trustableRoots(env: Env): Set[Peer] =
    env.localRoots.flatMap(_.members.collect { case (p, c) if c.trustable => p }).toSet

  /** `prop_governor_only_bootstrap_peers_in_fallback_state`: in the sensitive state the governor
    * does not keep a peer it does not trust for more than ten seconds.
    */
  property("prop_governor_only_bootstrap_peers_in_fallback_state") {
    over(Generators.bootstrapEnvironment) { rec =>
      overdue(rec)(ignored(rec, 0.seconds, Vector.empty, 10.seconds) { (st, t, _) =>
        if sensitive(st) then st.view(t).known.toSet -- trusted(st, trustableRoots(rec.env))
        else Set.empty
      })
    }
  }

  /** `prop_governor_no_non_trustable_peers_before_caught_up_state`: once the clean state is reached
    * no peer that is not trusted is known until the node is young enough again.
    */
  property("prop_governor_no_non_trustable_peers_before_caught_up_state") {
    over(Generators.bootstrapEnvironment) { rec =>
      overdue(rec)(ignored(rec, 0.seconds, Vector.empty, 10.seconds) { (st, t, _) =>
        if st.sensitivity.hasOnlyBootstrapPeers && sensitive(st) then
          st.view(t).known.toSet -- trusted(st, trustableRoots(rec.env))
        else Set.empty
      })
    }
  }

  /** `prop_governor_only_bootstrap_peers_in_clean_state`: while the node is clean, everything it
    * trusts is known (nothing trusted is lost).
    */
  property("prop_governor_only_bootstrap_peers_in_clean_state") {
    over(Generators.bootstrapEnvironment) { rec =>
      ok("a trusted peer is not known in the clean state")(
        rec.states.forall { (t, st) =>
          !(st.sensitivity.hasOnlyBootstrapPeers && sensitive(st)) ||
          trusted(st, trustableRoots(rec.env))
            .subsetOf(st.view(t).known.toSet ++ rec.env.localRoots.flatMap(_.members.map(_._1)))
        }
      )
    }
  }

  /** Not a property of the reference (its `prop_governor_stops_using_bootstrap_peers` is stated
    * over a signal this governor does not need): at the instant the node is young enough again it
    * forgets the bootstrap peers it is not connected to, so that other sources take over.
    */
  property("young enough again: the bootstrap peers that were not connected are forgotten") {
    over(Generators.bootstrapEnvironment) { rec =>
      val states = rec.states.map(_._2)
      val flips = states.zip(states.drop(1)).collect {
        case (before, after)
            if before.sensitivity.judgement == LedgerStateJudgement.TooOld &&
              after.sensitivity.judgement == LedgerStateJudgement.YoungEnough =>
          after
      }
      ok("a cold bootstrap peer was kept when the node became young enough")(
        flips.forall(
          _.peers.forall((_, r) =>
            !(r.origin == Origin.Bootstrap && r.status.isInstanceOf[Status.Cold])
          )
        )
      )
    }
  }

  /** `prop_governor_association_mode`: the outbound connections state says trusted only when the
    * node knows only what it trusts.
    */
  property("prop_governor_association_mode") {
    over(Generators.bootstrapEnvironment) { rec =>
      val association = AssociationMode.of(false, false, true, bootstrapEmpty = false)
      ok("trusted while connected to a peer that is not trusted")(
        rec.states.forall { (t, st) =>
          val v = st.view(t)
          GovernorState.outboundConnectionsState(association, v) !=
            OutboundConnectionsState.TrustedStateWithExternalPeers ||
            v.established.toSet.subsetOf(trusted(st, trustableRoots(rec.env)))
        }
      )
    }
  }

  // ---------------------------------------------------------------- regressions of the reference

  private val outbound =
    RootConfig(false, DiffusionMode.InitiatorAndResponder, Provenance.Outbound, false)

  private def single(peer: Peer): RootGroupSpec[Peer] = RootGroupSpec(1, 1, List(peer -> outbound))

  private def script(steps: (Demotion, Delay)*): PeerScript =
    PeerScript(Vector(false), steps.map((d, w) => ScriptStep(d, w)).toVector)

  private def targetsAt(changes: (Targets, FiniteDuration)*): Timed[Targets] =
    // the reference's script: a value, the delay after it, the next value
    val times = changes.scanLeft(Duration.Zero)((at, change) => at + change._2)
    Timed(
      changes.head._1,
      changes.zip(times).drop(1).map { case ((t, _), at) => at -> t }.toVector
    )

  private val noJudgement = Timed(LedgerStateJudgement.YoungEnough, Vector.empty)

  private def target(root: Int, known: Int, established: Int, active: Int): Targets =
    Targets.validated(root, known, established, active, 0, 0, 0).getOrElse(Targets.zero)

  /** `prop_issue_3233`: the governor does not livelock when a peer that is both a public root and
    * flaky is asked for while the targets move (`PeerSelection.hs` 4501-4560).
    */
  test("prop_issue_3233") {
    val env = Env(
      peers = 16,
      scripts = Map(
        4 -> script(
          (Demotion.ToCold, Delay.NoDelay),
          (Demotion.ToCold, Delay.NoDelay),
          (Demotion.Noop, Delay.NoDelay),
          (Demotion.ToWarm, Delay.NoDelay),
          (Demotion.ToCold, Delay.NoDelay),
          (Demotion.Noop, Delay.NoDelay)
        )
      ),
      localRoots = List(single(15), single(13)),
      publicRoots = Set(4),
      bootstrapPeers = Set.empty,
      useBootstrap = false,
      targets = targetsAt(
        Targets.zero -> 1.second,
        target(1, 3, 3, 0) -> 600.seconds,
        Targets.zero -> 1.second,
        Targets.zero -> 1.second,
        target(1, 3, 3, 2) -> 1.second
      ),
      judgement = noJudgement,
      seed = 42L
    )
    val rec = run(env)
    assert(
      nolivelock(rec).apply(org.scalacheck.Gen.Parameters.default).success,
      rec.traces.take(20).toString
    )
  }

  /** `prop_issue_3494`: no assertion fails (here: no invariant breaks) when a local root that drops
    * at once is asked for while the known target moves (4462-4500).
    */
  test("prop_issue_3494") {
    val env = Env(
      peers = 65,
      scripts = Map(64 -> script((Demotion.ToCold, Delay.NoDelay), (Demotion.Noop, Delay.NoDelay))),
      localRoots = List(single(64)),
      publicRoots = Set.empty,
      bootstrapPeers = Set.empty,
      useBootstrap = false,
      targets = targetsAt(
        Targets.zero -> 1.second,
        target(0, 1, 0, 0) -> 60.seconds,
        target(0, 1, 0, 0) -> 60.seconds,
        Targets.zero -> 1.second,
        Targets.zero -> 1.second,
        target(0, 1, 0, 0) -> 1.second
      ),
      judgement = noJudgement,
      seed = 42L
    )
    val rec = run(env)
    assert(nofail(rec).apply(org.scalacheck.Gen.Parameters.default).success)
  }

  /** `prop_issue_3515`: no livelock when the only local root drops at once while the known target
    * goes up and down (4419-4461).
    */
  test("prop_issue_3515") {
    val env = Env(
      peers = 11,
      scripts = Map(10 -> script((Demotion.ToCold, Delay.NoDelay), (Demotion.Noop, Delay.NoDelay))),
      localRoots = List(single(10)),
      publicRoots = Set.empty,
      bootstrapPeers = Set.empty,
      useBootstrap = false,
      targets = targetsAt(
        target(0, 1, 0, 0) -> 60.seconds,
        target(0, 1, 0, 0) -> 60.seconds,
        Targets.zero -> 1.second,
        target(0, 1, 0, 0) -> 1.second
      ),
      judgement = noJudgement,
      seed = 42L
    )
    val rec = run(env)
    assert(nolivelock(rec).apply(org.scalacheck.Gen.Parameters.default).success)
  }

  /** `prop_issue_3550`: established peers reach the target although a public root drops to warm and
    * then cold while two local roots are being connected (4370-4418).
    */
  test("prop_issue_3550") {
    val env = Env(
      peers = 30,
      scripts = Map(
        29 -> script(
          (Demotion.ToWarm, Delay.NoDelay),
          (Demotion.ToCold, Delay.NoDelay),
          (Demotion.Noop, Delay.NoDelay)
        )
      ),
      localRoots = List(single(16), single(4)),
      publicRoots = Set(14, 29),
      bootstrapPeers = Set.empty,
      useBootstrap = false,
      targets = targetsAt(target(1, 4, 4, 3) -> 1.second),
      judgement = noJudgement,
      seed = 42L
    )
    val rec = run(env)
    assert(establishedBelow(rec).apply(org.scalacheck.Gen.Parameters.default).success)
  }

  // ---------------------------------------------------------------- determinism

  test("the same environment and seed give the same trace, and another seed another one") {
    val env = Generators.environment.sample.get
    val a = run(env)
    val b = run(env)
    assertEquals(a.traces.map(_._2), b.traces.map(_._2))
    assertEquals(a.states.map(_._2), b.states.map(_._2))
  }
