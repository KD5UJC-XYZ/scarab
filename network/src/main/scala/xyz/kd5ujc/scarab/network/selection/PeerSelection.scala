// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network.selection

import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.protocol.ConnectionState.Provenance

/** The decision function of the P2P governor, pure over its state, the time and a generator.
  *
  * [[decide]] is the reference's `guardedDecisions` (`Governor.hs` 666-727): it tries the families
  * of decisions in the reference's order, and the first one that applies gives the new state, the
  * jobs to start and the traces; if none applies it says when to look again. The order is the
  * priority, and the reference's comment at `Governor.hs` 719-727 says why the targets are read
  * before the local roots:
  *
  *   1. the sensitive state: the judgement of the ledger changed; the clean state was reached
  *   2. the targets and the local roots in force
  *   3. the request for public roots below the root target
  *   4. forgetting cold peers above the known target
  *   5. cold to warm below the established target (local roots first), warm to cold above it
  *   6. warm to hot below the active target (local roots first), hot to warm above it
  *
  * What the reference's monitors wait for (a connection changed, a job ended, the targets or the
  * judgement changed) arrives as an [[Event]] given to [[apply]]; the effect loop applies the
  * events it has before it asks [[decide]] again, which is the reference's priority of
  * `Monitor.connections` and `Monitor.jobs` over the rest. Big ledger peers, peer sharing and
  * inbound peers are later stages; their targets and counters are in the types and they decide
  * nothing yet.
  */
object PeerSelection:

  /** Whether `st` is the state to act on at `now`: bookkeeping that depends on time only
    * (`KnownPeers.setCurrentTime`): a peer that stayed hot long enough has its fail count reset.
    */
  private def tick[A](st: GovernorState[A], now: Time): GovernorState[A] =
    val due = st.peers.collect { case (p, r) if r.clearFailAt.exists(_ <= now) => p }
    if due.isEmpty then st
    else
      st.copy(peers = due.foldLeft(st.peers) { (m, p) =>
        m.updated(p, m(p).copy(failCount = 0, clearFailAt = None))
      })

  /** The first decision that applies in the reference's order, or when to look again. The peer
    * metrics are the feedback of the chain: the hot peers that were seldom first with a header or a
    * block are demoted first ([[HotScore]]); without them every peer scores alike and the generator
    * decides.
    */
  def decide[A](
      input: GovernorState[A],
      now: Time,
      metrics: PeerMetrics[A] = PeerMetrics.empty[A]()
  ): Step[A] =
    given Ordering[A] = input.peers.ordering
    val pass = Pass(tick(input, now), now, metrics)
    val families: List[() => Guard[A]] = List(
      () => pass.cleanState,
      () => pass.judgement,
      () => pass.targets,
      () => pass.localRoots,
      () => pass.requestRoots,
      () => pass.forget,
      () => pass.connectLocal,
      () => pass.connectOther,
      () => pass.closeOther,
      () => pass.activateLocal,
      () => pass.activateOther,
      () => pass.deactivateLocal,
      () => pass.deactivateOther
    )
    @scala.annotation.tailrec
    def first(rest: List[() => Guard[A]], wake: Option[Time]): Step[A] = rest match
      // a time that has passed is not a reason to look again: the decision that depends on it has
      // had its chance (the reference drops the times that are due when it sets the current time)
      case Nil => Step.Idle(pass.st, earliest(wake, pass.nextClearFail).filter(_ > now))
      case family :: more =>
        family() match
          case Guard.Go(decision) => Step.Act(decision)
          case Guard.Skip(at)     => first(more, earliest(wake, at))
    first(families, None)

  /** `abortGovernor` (`Governor.hs` 596): the node did not reach the clean state in time. */
  def abort[A](st: GovernorState[A], now: Time): Option[GovernorError] =
    st.sensitivity.timeout.filter(now >= _).map(_ => GovernorError.BootstrapPeersCriticalTimeout)

  private def earliest(a: Option[Time], b: Option[Time]): Option[Time] = (a, b) match
    case (Some(x), Some(y)) => Some(x.min(y))
    case (x, None)          => x
    case (None, y)          => y

  private enum Guard[A]:
    case Go(decision: Decision[A])
    case Skip(wakeAt: Option[Time])

  /** One pass of the decisions: the state, the sets derived from it, the time and the policy. */
  private final class Pass[A](val st: GovernorState[A], now: Time, metrics: PeerMetrics[A])(using
      ord: Ordering[A]
  ):
    val view: GovernorState.View[A] = st.view(now)
    import Guard.*

    /** The score of a hot peer for demotion, computed once and only if a demotion happens. */
    private lazy val hotScore: A => HotScore =
      val scores = metrics.hotScores
      peer => scores.getOrElse(peer, HotScore.Unknown)

    def nextClearFail: Option[Time] = st.peers.values.flatMap(_.clearFailAt).minOption

    private def go(
        state: GovernorState[A],
        jobs: List[Job[A]],
        traces: List[Trace[A]]
    ): Guard[A] = Go(Decision(state, jobs, traces))

    private def withStatus(
        peers: Iterable[A],
        status: Status,
        base: GovernorState[A] = st
    ): GovernorState[A] =
      base.copy(peers = peers.foldLeft(base.peers) { (m, p) =>
        m.updated(p, m(p).copy(status = status))
      })

    private def settings(peer: A): RootConfig =
      st.localRoots.config.getOrElse(
        peer,
        RootConfig(false, DiffusionMode.InitiatorAndResponder, Provenance.Outbound, false)
      )

    // ---------------------------------------------------------------- the sensitive state

    /** `waitForSystemToQuiesce` (`Cardano/.../Governor/Monitor.hs` 613-682): in the sensitive state
      * the node makes no progress until it knows only trusted peers and nothing is in flight; the
      * wake-up is the deadline after which the node stops.
      */
    def cleanState: Guard[A] =
      val s = st.sensitivity
      val trusted = view.bootstrap ++ st.localRoots.clampToTrustable.keys
      if s.sensitive && !s.hasOnlyBootstrapPeers &&
        st.localRoots.config.values.forall(_.trustable) &&
        view.known.subsetOf(trusted) &&
        view.connecting.isEmpty && view.activating.isEmpty && !st.publicRootsInFlight
      then
        go(
          st.copy(sensitivity = s.copy(hasOnlyBootstrapPeers = true, timeout = None)),
          Nil,
          List(Trace.OnlyBootstrapPeers())
        )
      else Skip(s.timeout)

    /** `monitorLedgerStateJudgement` for the Praos mode (`Monitor.hs` 474-595). Only when bootstrap
      * peers are in use. Too old: every target to zero, no local roots, and 15 minutes to reach the
      * clean state. Young enough: the bootstrap peers we are not connected to are forgotten and the
      * request for roots starts afresh, so other sources can take over.
      */
    def judgement: Guard[A] =
      val s = st.sensitivity
      if !s.useBootstrapPeers || st.reported == s.judgement then Skip(None)
      else
        st.reported match
          case LedgerStateJudgement.TooOld =>
            val demoted = st.peers.map { (p, r) =>
              p -> (if r.origin == Origin.Local then r.copy(origin = Origin.Other) else r)
            }
            go(
              st.copy(
                targets = Targets.zero,
                peers = demoted,
                localRoots = LocalRoots.empty[A],
                publicRootBackoffs = 0,
                publicRootRetryAt = now,
                sensitivity = s.copy(
                  judgement = LedgerStateJudgement.TooOld,
                  hasOnlyBootstrapPeers = false,
                  timeout = Some(now + Sensitivity.CleanStateTimeout)
                )
              ),
              Nil,
              List(Trace.JudgementChanged(LedgerStateJudgement.TooOld))
            )
          case LedgerStateJudgement.YoungEnough =>
            val dropped = view.bootstrap -- view.established -- view.connecting
            go(
              st.copy(
                peers = st.peers -- dropped,
                publicRootBackoffs = 0,
                publicRootRetryAt = now,
                sensitivity = s.copy(
                  judgement = LedgerStateJudgement.YoungEnough,
                  hasOnlyBootstrapPeers = false,
                  timeout = None
                )
              ),
              Nil,
              Trace.JudgementChanged(LedgerStateJudgement.YoungEnough) ::
                (if dropped.isEmpty then Nil else List(Trace.Forgotten(dropped.toList)))
            )

    // ---------------------------------------------------------------- targets and local roots

    /** `Cardano.targetPeers` (`Monitor.hs` 55-135): the targets the churn governor asks for become
      * the targets once the node is able to make progress. The local roots are clamped to the new
      * known target (and, in the sensitive state, to the trustable ones).
      */
    def targets: Guard[A] =
      val wanted = st.requested
      if wanted == Targets.zero || !st.sensitivity.ableToMakeProgress || wanted == st.targets then
        Skip(None)
      else
        val base =
          if st.sensitivity.sensitive then st.localRoots.clampToTrustable else st.localRoots
        val clamped = base.clampToLimit(wanted.known)
        val moved = reorigin(st.peers, st.localRoots, clamped)
        go(
          st.copy(targets = wanted, localRoots = clamped, peers = moved),
          Nil,
          List(Trace.TargetsChanged(wanted)) ++
            (if clamped.size != st.localRoots.size then
               List(Trace.LocalRootsChanged(st.localRoots.size, clamped.size))
             else Nil)
        )

    /** `Cardano.localRoots` (`Monitor.hs` 227-355): when the configured roots, within the targets
      * and the sensitive state, differ from those in force, the new ones become known; a removed
      * root that is hot is demoted to warm so that another can take its place.
      */
    def localRoots: Guard[A] =
      if !st.sensitivity.ableToMakeProgress then Skip(None)
      else
        val base =
          if st.sensitivity.sensitive then st.configuredRoots.clampToTrustable
          else st.configuredRoots
        val wanted = base.clampToLimit(st.targets.known)
        if wanted == st.localRoots then Skip(None)
        else
          val removed = st.localRoots.keys -- wanted.keys
          val added = wanted.keys -- st.localRoots.keys
          val withNew = added.foldLeft(st.peers) { (m, p) =>
            m.get(p) match
              case Some(r) => m.updated(p, r.copy(origin = Origin.Local))
              case None    => m.updated(p, PeerRecord.cold(Origin.Local))
          }
          val moved = reorigin(withNew, st.localRoots, wanted)
          val toDemote = removed.filter(p => st.peers.get(p).exists(_.status == Status.Hot))
          val known = moved.keySet
          val onlyTrusted = known.subsetOf((view.bootstrap ++ wanted.keys).toSet)
          val sensitivity =
            if st.sensitivity.sensitive && !onlyTrusted then
              st.sensitivity.copy(
                judgement = LedgerStateJudgement.YoungEnough,
                hasOnlyBootstrapPeers = false
              )
            else st.sensitivity.copy(hasOnlyBootstrapPeers = onlyTrusted)
          val next = withStatus(
            toDemote,
            Status.Deactivating,
            st.copy(localRoots = wanted, peers = moved, sensitivity = sensitivity)
          )
          go(
            next,
            toDemote.toList.map(Job.Deactivate(_)),
            List(Trace.LocalRootsChanged(st.localRoots.size, wanted.size))
          )

    /** A root that is no longer one keeps its record as a non-root; a peer that became one is a
      * local root, and a local root wins over a public one.
      */
    private def reorigin(
        peers: scala.collection.immutable.SortedMap[A, PeerRecord],
        before: LocalRoots[A],
        after: LocalRoots[A]
    ): scala.collection.immutable.SortedMap[A, PeerRecord] =
      val dropped = before.keys -- after.keys
      val peers1 = dropped.foldLeft(peers) { (m, p) =>
        m.get(p)
          .filter(_.origin == Origin.Local)
          .fold(m)(r => m.updated(p, r.copy(origin = Origin.Other)))
      }
      after.keys.foldLeft(peers1) { (m, p) =>
        m.get(p)
          .filter(_.origin != Origin.Local)
          .fold(m)(r => m.updated(p, r.copy(origin = Origin.Local)))
      }

    // ---------------------------------------------------------------- discovering peers

    /** `RootPeers.belowTarget` (`Governor/RootPeers.hs` 32-77): below the root target, ask for more
      * public roots, not while a request is in flight and not before the retry time.
      */
    def requestRoots: Guard[A] =
      val missing = st.targets.root - view.roots.size
      if missing > 0 && !st.publicRootsInFlight then
        if now >= st.publicRootRetryAt then
          val (_, next) = st.rng.split
          go(
            st.copy(publicRootsInFlight = true, rng = next),
            List(Job.RequestRoots(missing, st.sensitivity.sensitive)),
            List(Trace.RootsRequest(st.targets.root, view.roots.size))
          )
        else Skip(Some(st.publicRootRetryAt))
      else Skip(None)

    /** `KnownPeers.aboveTarget` (`Governor/KnownPeers.hs` 451-580): above the known target, forget
      * cold peers, never a local root and never a public root if that would put the roots below
      * their target.
      */
    def forget: Guard[A] =
      val known = view.known.size
      val canForgetRoots = view.roots.size - st.targets.root
      val protectedRoots: Set[A] =
        if canForgetRoots <= 0 then view.roots else Set.empty
      val available = view.known -- view.established -- st.localRoots.keys -- protectedRoots --
        view.connecting
      if known > st.targets.known && known > view.established.size && available.nonEmpty then
        val surplus = known - st.targets.known
        val n = if canForgetRoots > 0 then canForgetRoots.min(surplus) else surplus
        val (picked, next) =
          Picks.forget(available, n, st.rng, p => st.peers(p).failCount)
        go(
          st.copy(peers = st.peers -- picked, rng = next),
          Nil,
          List(Trace.ForgetCold(st.targets.known, known, picked))
        )
      else Skip(None)

    // ---------------------------------------------------------------- cold to warm

    private def retryTimes(exclude: A => Boolean): Option[Time] =
      st.peers.collect {
        case (p, PeerRecord(Status.Cold(Some(at)), _, _, _, _, _)) if !exclude(p) && at > now => at
      }.minOption

    /** `EstablishedPeers.belowTargetLocal` (`Governor/EstablishedPeers.hs` 106-239): every local
      * root group with fewer established members than its warm valency connects to the shortfall,
      * even if that is above the established target.
      */
    def connectLocal: Guard[A] =
      val below = st.localRoots.groups.filter { g =>
        g.members.intersect(view.established).size < g.warm.value
      }
      val localAvailable = view.availableToConnect.intersect(view.localRoots)
      val chosen = below.foldLeft((List.empty[A], st.rng)) { case ((acc, g0), group) =>
        val available = group.members.intersect(localAvailable)
        val n = group.warm.value - group.members.intersect(view.established).size -
          group.members.intersect(view.connecting).size
        if n > 0 && available.nonEmpty then
          val (picked, g1) = Picks.promotion(available, n, g0)
          (acc ++ picked, g1)
        else (acc, g0)
      }
      val (picked, next) = chosen
      if picked.nonEmpty then
        go(
          withStatus(picked, Status.Connecting).copy(rng = next),
          picked.map { p =>
            val c = settings(p)
            Job.Connect(p, c.diffusionMode, c.provenance)
          },
          List(
            Trace.Promote(
              Temperature.Cold,
              picked,
              local = true,
              st.targets.established,
              view.established.size
            )
          )
        )
      else if below.nonEmpty then
        val potential = view.localRoots -- view.established -- localAvailable
        if potential.nonEmpty then Skip(retryTimes(p => view.established(p) && !view.localRoots(p)))
        else Skip(None)
      else Skip(None)

    /** `EstablishedPeers.belowTargetOther` (265-311): connect to the shortfall of the established
      * target, among the cold peers that are available and not local roots.
      */
    def connectOther: Guard[A] =
      val missing = st.targets.established - view.established.size - view.connecting.size
      val available =
        view.availableToConnect -- view.established -- view.connecting -- view.localRoots
      if missing > 0 && available.nonEmpty then
        val (picked, next) = Picks.promotion(available, missing, st.rng)
        go(
          withStatus(picked, Status.Connecting).copy(rng = next),
          picked.map(Job.Connect(_, DiffusionMode.InitiatorAndResponder, Provenance.Outbound)),
          List(
            Trace.Promote(
              Temperature.Cold,
              picked,
              local = false,
              st.targets.established,
              view.established.size
            )
          )
        )
      else if missing > 0 then Skip(retryTimes(p => view.localRoots(p)))
      else Skip(None)

    // ---------------------------------------------------------------- warm to cold

    /** `EstablishedPeers.aboveTargetOther` (700-745): above the established target, close warm
      * peers that are not local roots and not being promoted, demoted already or lingering. The
      * number is the smaller of the excess over the target and the warm peers there are.
      */
    def closeOther: Guard[A] =
      val warmPeers = view.established -- view.active -- view.localRoots
      val localWarm = view.localRoots.intersect(view.warm).size
      val demotable = (view.established.size - st.targets.established)
        .min(view.established.size - localWarm - view.active.size) -
        view.closing.size - view.activating.size - warmPeers.intersect(view.cooling).size
      val available = warmPeers -- view.closing -- view.activating -- view.cooling
      if demotable > 0 && available.nonEmpty then
        val (picked, next) =
          Picks.warmDemotion(available, demotable, st.rng, p => st.peers(p).tepid)
        go(
          withStatus(picked, Status.Closing).copy(rng = next),
          picked.map(Job.Close(_)),
          List(
            Trace.Demote(
              Temperature.Warm,
              picked,
              local = false,
              st.targets.established,
              view.established.size
            )
          )
        )
      else Skip(None)

    // ---------------------------------------------------------------- warm to hot

    private def readyTimes: Option[Time] =
      st.peers.values.collect {
        case PeerRecord(Status.Warm(at), _, _, _, _, _) if at > now => at
      }.minOption

    /** `ActivePeers.belowTargetLocal` (201-324): every local root group with fewer active members
      * than its hot valency activates the shortfall from its ready warm members.
      */
    def activateLocal: Guard[A] =
      val below = st.localRoots.groups.filter { g =>
        g.members.intersect(view.active).size < g.hot.value
      }
      val readyLocal = view.localRoots.intersect(view.ready) -- view.active -- view.activating --
        view.closing -- view.cooling
      val (picked, next) = below.foldLeft((List.empty[A], st.rng)) { case ((acc, g0), group) =>
        val available = group.members.intersect(readyLocal)
        val n = group.hot.value - group.members.intersect(view.active).size -
          group.members.intersect(view.activating).size
        if n > 0 && available.nonEmpty then
          val (chosen, g1) = Picks.promotion(available, n, g0)
          (acc ++ chosen, g1)
        else (acc, g0)
      }
      if picked.nonEmpty then
        go(
          withStatus(picked, Status.Activating).copy(rng = next),
          picked.map(Job.Activate(_)),
          List(
            Trace.Promote(
              Temperature.Warm,
              picked,
              local = true,
              st.targets.active,
              view.active.size
            )
          )
        )
      else if below.nonEmpty then
        val potential = view.localRoots.intersect(view.established) -- view.active -- view.ready
        if potential.nonEmpty then Skip(readyTimes) else Skip(None)
      else Skip(None)

    /** `ActivePeers.belowTargetOther` (326-424): activate the shortfall of the active target among
      * the ready warm peers that are not local roots.
      */
    def activateOther: Guard[A] =
      val missing = st.targets.active - view.active.size - view.activating.size
      val available = view.ready -- view.active -- view.activating -- view.closing --
        view.cooling -- view.localRoots
      if missing > 0 && available.nonEmpty then
        val (picked, next) = Picks.promotion(available, missing, st.rng)
        go(
          withStatus(picked, Status.Activating).copy(rng = next),
          picked.map(Job.Activate(_)),
          List(
            Trace.Promote(
              Temperature.Warm,
              picked,
              local = false,
              st.targets.active,
              view.active.size
            )
          )
        )
      else if missing > 0 then Skip(readyTimes)
      else Skip(None)

    // ---------------------------------------------------------------- hot to warm

    /** `ActivePeers.aboveTargetLocal` (756-857): a local root group with more active members than
      * its hot valency deactivates the excess.
      */
    def deactivateLocal: Guard[A] =
      val above = st.localRoots.groups.filter { g =>
        g.members.intersect(view.active).size > g.hot.value
      }
      val availableLocal = view.localRoots.intersect(view.active) -- view.deactivating --
        view.cooling
      val inProgress = view.deactivating.size
      val (picked, next) = above.foldLeft((List.empty[A], st.rng)) { case ((acc, g0), group) =>
        val available = group.members.intersect(availableLocal)
        val n = group.members.intersect(view.active).size - group.hot.value - inProgress
        if n > 0 && available.nonEmpty then
          val (chosen, g1) = Picks.hotDemotion(available, n, g0, hotScore)
          (acc ++ chosen, g1)
        else (acc, g0)
      }
      if picked.nonEmpty then
        go(
          withStatus(picked, Status.Deactivating).copy(rng = next),
          picked.map(Job.Deactivate(_)),
          List(
            Trace.Demote(
              Temperature.Hot,
              picked,
              local = true,
              st.targets.active,
              view.active.size
            )
          )
        )
      else Skip(None)

    /** `ActivePeers.aboveTargetOther` (859-957): above the active target, deactivate hot peers that
      * are not local roots, the least useful first.
      */
    def deactivateOther: Guard[A] =
      val excess = view.active.size - st.targets.active - view.deactivating.size
      val available = view.active -- view.deactivating -- view.localRoots -- view.cooling
      if excess > 0 && available.nonEmpty then
        val (picked, next) = Picks.hotDemotion(available, excess, st.rng, hotScore)
        go(
          withStatus(picked, Status.Deactivating).copy(rng = next),
          picked.map(Job.Deactivate(_)),
          List(
            Trace.Demote(
              Temperature.Hot,
              picked,
              local = false,
              st.targets.active,
              view.active.size
            )
          )
        )
      else Skip(None)

  // =================================================================== events

  /** Apply an event to the state at `now`: what the reference's blocking monitors do when they
    * wake. A state change that depends on the status the peer has *now*, so an outcome that arrives
    * after the world moved on is traced as aborted and changes nothing.
    */
  def apply[A](
      st0: GovernorState[A],
      event: Event[A],
      now: Time,
      policy: Policy = Policy()
  ): Decision[A] =
    given Ordering[A] = st0.peers.ordering
    val st = tick(st0, now)
    event match
      case Event.TargetsRequested(t)         => Decision(st.copy(requested = t), Nil, Nil)
      case Event.JudgementReported(j)        => Decision(st.copy(reported = j), Nil, Nil)
      case Event.RootsConfigured(roots)      => Decision(st.copy(configuredRoots = roots), Nil, Nil)
      case Event.Wake(_)                     => Decision(st, Nil, Nil)
      case Event.Observed(peer, observation) => Events.observed(st, peer, observation, now, policy)
      case Event.Completed(outcome)          => Events.completed(st, outcome, now, policy)
