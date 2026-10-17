// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.governor

import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
import org.scalacheck.rng.Seed
import org.scalacheck.Prop.*
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.network.selection.*
import xyz.kd5ujc.scarab.node.Output

import MockEnvironment.*
import Signals.*

/** The reference's properties for big ledger peers (`Test.Cardano.Network.PeerSelection`, the group
  * "big ledger peers" and the ledger-peers property of the group "bootstrap peers",
  * ouroboros-network `main` 1.3.0.0, whose tests agree with the pin at the cited names), by name,
  * on the real governor loop of the node in the mock environment on `TestControl` (see
  * [[GovernorPropertiesSuite]] for the ordinary ones). The environments add what a ledger gives the
  * governor: relays for the ordinary request and for the request for big ledger peers, and the
  * targets for the big ledger sets.
  *
  * A big ledger peer here is a peer whose origin is [[Origin.BigLedger]]: the reference's
  * `takeBigLedgerPeers`. A peer an answer names that is a root already stays what it was
  * (`RootPeers.hs` 163-168, `BigLedgerPeers.hs` 138-147), so a big ledger peer of the environment
  * is not always one of the governor's.
  */
class BigLedgerPropertiesSuite extends ScalaCheckSuite:
  override def scalaCheckInitialSeed = "B2s7sCwOUZ0cxIBtxjwLpWmauQHR1D29SlLKhIrI_5I="

  override def scalaCheckTestParameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(15)

  // ---------------------------------------------------------------- environments

  private[governor] object Env:
    /** Sane targets for both populations, of a size the environment can meet. */
    val targets: Gen[Targets] =
      for
        known <- Gen.choose(0, 30)
        established <- Gen.choose(0, known)
        active <- Gen.choose(0, established)
        root <- Gen.choose(0, known)
        knownBig <- Gen.choose(0, 20)
        establishedBig <- Gen.choose(0, knownBig)
        activeBig <- Gen.choose(0, establishedBig)
      yield Targets
        .validated(root, known, established, active, knownBig, establishedBig, activeBig)
        .getOrElse(Targets.zero)

    private def timedTargets(horizon: FiniteDuration): Gen[Timed[Targets]] =
      for
        first <- targets
        changes <- Gen.choose(0, 3)
        later <- Gen.listOfN(
          changes,
          for
            minutes <- Gen.choose(5, horizon.toMinutes.toInt - 5)
            ts <- targets
          yield minutes.minutes -> ts
        )
      yield Timed(first, later.sortBy(_._1).distinctBy(_._1).toVector)

    /** An environment whose ledger offers relays: some ordinary, some big (peers of the graph). */
    val environment: Gen[Env] =
      for
        base <- Generators.environment.suchThat(_.peers >= 6)
        ts <- timedTargets(6.hours)
        big <- Gen.someOf(0 until base.peers).map(_.toSet)
        ordinary <- Gen.someOf(0 until base.peers).map(_.toSet -- big)
      yield base.copy(targets = ts, bigLedgerPeers = big, ledgerPeers = ordinary)

    /** The same with bootstrap peers and a judgement that changes. */
    val bootstrapEnvironment: Gen[Env] =
      for
        base <- Generators.bootstrapEnvironment
        ts <- timedTargets(6.hours)
        big <- Gen.someOf(0 until base.peers).map(_.toSet)
        ordinary <- Gen.someOf(0 until base.peers).map(_.toSet -- big)
      yield base.copy(targets = ts, bigLedgerPeers = big, ledgerPeers = ordinary)

  private def over(gen: Gen[Env])(check: Recording => Prop): Prop =
    forAll(gen) { env =>
      val rec = run(env)
      (!rec.overflowed :| "the run recorded more than 200000 things: the governor does not rest") &&
      check(rec)
    }

  private def noneOr[A](found: Option[A])(describe: A => String): Prop =
    found.fold(Prop.passed)(a => Prop.falsified :| describe(a))

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

  private def overdue(found: Option[(Peer, Time)]): Prop =
    noneOr(found)((peer, since) => s"peer $peer was an opportunity from $since and was ignored")

  private def bigOf(st: GovernorState[Peer]): Set[Peer] =
    st.peers.collect { case (p, r) if r.origin.big => p }.toSet

  // ---------------------------------------------------------------- known

  /** `prop_governor_target_known_5_no_shrink_big_ledger_peers_below` (as
    * `prop_governor_target_known_big_ledger_peers_below`): when the known big ledger peers shrink,
    * they do not shrink below the target, unless a peer was forgotten after its failures or became
    * a local root.
    */
  property("prop_governor_target_known_big_ledger_peers_below") {
    over(Env.environment) { rec =>
      val states = rec.states
      val forgotten: Map[Time, Set[Peer]] = rec.traces
        .collect { case (t, Trace.Forgotten(ps)) => t -> ps.toSet }
        .groupMapReduce(_._1)(_._2)(_ ++ _)
      val bad = states.zip(states.drop(1)).collectFirst {
        case ((_, before), (t, after)) if {
              val shrunk = bigOf(before) -- bigOf(after) -- after.localRoots.keys.toSet --
                forgotten.getOrElse(t, Set.empty)
              shrunk.nonEmpty && bigOf(after).size < after.targets.knownBig
            } =>
          t
      }
      noneOr(bad)(t => s"the known big ledger peers shrank below the target at $t")
    }
  }

  /** `prop_governor_target_known_big_ledger_peers_above`: above the known big ledger target the
    * governor forgets the cold big ledger peers it may (not established, not being connected to).
    */
  property("prop_governor_target_known_big_ledger_peers_above") {
    over(Env.environment) { rec =>
      overdue(ignored(rec, 0.seconds, Vector.empty, 1.second) { (st, t, _) =>
        val v = st.view(t)
        if v.big.known.size <= st.targets.knownBig then Set.empty
        else (v.big.known -- v.big.established -- v.big.connecting).toSet
      })
    }
  }

  // ---------------------------------------------------------------- established

  /** `prop_governor_target_established_big_ledger_peers`: the reference tabulates the big ledger
    * peers that are established (it asserts nothing); here the sets nest at every instant, and the
    * generator reaches the family: some environment of the sample has an established big ledger
    * peer.
    */
  property("prop_governor_target_established_big_ledger_peers") {
    forAll(Env.environment) { env =>
      val rec = run(env)
      val nested = rec.states.forall { (t, st) =>
        val v = st.view(t)
        v.big.established.subsetOf(v.big.known) && v.big.active.subsetOf(v.big.established) &&
        v.big.known.intersect(v.known).isEmpty
      }
      val reached = rec.states.exists { (t, st) => st.view(t).big.established.nonEmpty }
      (nested :| "the big ledger sets do not nest") &&
      Prop.classify(reached, "an established big ledger peer", "none established")(Prop.passed)
    }
  }

  test("the generator reaches established and active big ledger peers") {
    val envs = (1L to 12L).toList.flatMap(i =>
      Env.environment.apply(Gen.Parameters.default.withSize(100), Seed(i))
    )
    assert(envs.size >= 8, "the generator failed too often")
    val seen = envs.map { env =>
      val rec = run(env)
      val c = rec.states.map((t, st) => st.view(t).counters)
      (c.exists(_.establishedBigLedger > 0), c.exists(_.activeBigLedger > 0))
    }
    assert(seen.exists(_._1), "no run established a big ledger peer")
    assert(seen.exists(_._2), "no run activated a big ledger peer")
  }

  private val coldRetry = 162.seconds // 5 * 2^5 s, plus the fuzz

  /** `prop_governor_target_established_big_ledger_peers_below`. */
  property("prop_governor_target_established_big_ledger_peers_below") {
    over(Env.environment) { rec =>
      val failed = failures(rec)
      overdue(ignored(rec, coldRetry, failed, 1.second) { (st, t, recentFailures) =>
        val v = st.view(t)
        if v.big.established.size + v.big.connecting.size >= st.targets.establishedBig then
          Set.empty
        else (v.big.known -- v.big.established -- recentFailures -- v.big.connecting).toSet
      })
    }
  }

  /** `prop_governor_target_established_big_ledger_peers_above`. */
  property("prop_governor_target_established_big_ledger_peers_above") {
    over(Env.environment) { rec =>
      overdue(ignored(rec, 0.seconds, Vector.empty, 1.second) { (st, t, _) =>
        val v = st.view(t)
        val demotions = v.big.closing ++ v.big.cooling
        if v.big.established.size - demotions.size <= st.targets.establishedBig then Set.empty
        else
          (v.big.established -- v.big.active -- (v.big.cooling ++ v.big.activating ++ v.big.closing)).toSet
      })
    }
  }

  // ---------------------------------------------------------------- active

  /** `prop_governor_target_active_big_ledger_peers_below`. */
  property("prop_governor_target_active_big_ledger_peers_below") {
    over(Env.environment) { rec =>
      val failed = failures(rec)
      overdue(ignored(rec, 180.seconds, failed, 11.seconds) { (st, t, recentFailures) =>
        val v = st.view(t)
        if v.big.active.size >= st.targets.activeBig then Set.empty
        else
          (v.big.established -- v.big.active -- recentFailures --
            (v.big.cooling ++ v.big.activating ++ v.big.closing)).toSet
      })
    }
  }

  /** `prop_governor_target_active_big_ledger_peers_above`. */
  property("prop_governor_target_active_big_ledger_peers_above") {
    over(Env.environment) { rec =>
      overdue(ignored(rec, 0.seconds, Vector.empty, 1.second) { (st, t, _) =>
        val v = st.view(t)
        if v.big.active.size <= st.targets.activeBig then Set.empty
        else (v.big.active -- (v.big.cooling ++ v.big.deactivating)).toSet
      })
    }
  }

  // ---------------------------------------------------------------- bootstrap peers and ledger peers

  private def trusted(st: GovernorState[Peer], configured: Set[Peer]): Set[Peer] =
    st.localRoots.clampToTrustable.keys.toSet ++ configured ++ st.peers.collect {
      case (p, r) if r.origin == Origin.Bootstrap => p
    }

  private def trustableRoots(env: Env): Set[Peer] =
    env.localRoots.flatMap(_.members.collect { case (p, c) if c.trustable => p }).toSet

  /** `prop_governor_only_bootstrap_peers_in_fallback_state` with the big ledger peers among the
    * known peers: in the sensitive state the governor does not keep a peer it does not trust, of
    * any kind, for more than ten seconds.
    */
  property("prop_governor_only_bootstrap_peers_in_fallback_state, big ledger peers included") {
    over(Env.bootstrapEnvironment) { rec =>
      overdue(ignored(rec, 0.seconds, Vector.empty, 10.seconds) { (st, _, _) =>
        if st.sensitivity.sensitive then
          st.peers.keySet.toSet -- trusted(st, trustableRoots(rec.env))
        else Set.empty
      })
    }
  }

  /** `prop_governor_uses_ledger_peers`: when the node is not in the sensitive state, the roots the
    * governor learns are not bootstrap peers (the reference states it on the last results of the
    * request for roots; the peers a result names that the governor took as bootstrap peers are the
    * ones it asked the bootstrap list for). A result that lands in the instant the judgement
    * changes is the request of the sensitive state ending.
    */
  property("prop_governor_uses_ledger_peers") {
    over(Env.bootstrapEnvironment) { rec =>
      val flips: Set[Time] = {
        val sts = rec.states
        sts
          .zip(sts.drop(1))
          .collect {
            case ((_, a), (t, b)) if a.sensitivity.sensitive && !b.sensitivity.sensitive => t
          }
          .toSet
      }
      val bad = rec.traces.collectFirst {
        case (t, Trace.RootsResults(found, _, _)) if !flips(t) && stateAt(rec, t).exists { st =>
              !st.sensitivity.sensitive &&
              found.exists(p => st.peers.get(p).exists(_.origin == Origin.Bootstrap))
            } =>
          t
      }
      noneOr(bad)(t => s"bootstrap peers were taken as roots at $t when not sensitive")
    }
  }

  /** The big ledger peers are asked for only when the node is not in the sensitive state
    * (`enableProgressMakingActions`): the state the governor set when it decided the request is not
    * the sensitive one (the governor sets the state, then tells the trace).
    */
  property("no request for big ledger peers is made in the sensitive state") {
    over(Env.bootstrapEnvironment) { rec =>
      val bad = rec.seen
        .foldLeft((Option.empty[GovernorState[Peer]], Option.empty[Time])) {
          case ((_, found), (_, Seen.State(st))) => (Some(st), found)
          case ((last, None), (t, Seen.Governor(Output.Traced(Trace.BigLedgerRequest(_, _)))))
              if last.exists(_.sensitivity.sensitive) =>
            (last, Some(t))
          case (other, _) => other
        }
        ._2
      noneOr(bad)(t => s"big ledger peers were requested at $t in the sensitive state")
    }
  }

  /** `prop_governor_nofail` with big ledger peers: no invariant breaks. */
  property("prop_governor_nofail, big ledger peers included") {
    over(Env.bootstrapEnvironment) { rec =>
      val broken = rec.states.flatMap((t, st) => GovernorState.invariant(st).map(t -> _))
      (rec.fatal.isEmpty :| s"the governor stopped: ${rec.fatal}") &&
      (broken.isEmpty :| s"an invariant broke: ${broken.take(3)}")
    }
  }
