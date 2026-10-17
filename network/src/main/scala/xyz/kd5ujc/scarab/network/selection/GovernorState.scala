// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network.selection

import scala.collection.immutable.{SortedMap, SortedSet}

/** The state of the governor (`PeerSelectionState`, `Governor/Types.hs` 609-705 with the Cardano
  * `ExtraState`): the targets, the known peers with their status, the configured and the effective
  * local roots, the bookkeeping of the request for public roots, the generator, and the sensitive
  * state. `A` is the address of a peer.
  *
  * Time is an argument of every function that reads it and a [[Time]] wherever the state keeps a
  * deadline, so the state is a plain value: two runs with the same seed and the same events give
  * the same states.
  */
final case class GovernorState[A](
    targets: Targets,
    peers: SortedMap[A, PeerRecord],
    /** The local roots as the topology and the flags name them. */
    configuredRoots: LocalRoots[A],
    /** The local roots in force: configured, within the known target and, in the sensitive state,
      * only the trustable ones (`Monitor.localRoots`).
      */
    localRoots: LocalRoots[A],
    /** The targets the churn governor last asked for (the reference's `readPeerSelectionTargets`
      * variable): they become the targets when the node is able to make progress.
      */
    requested: Targets,
    /** Consecutive requests for public roots that found nothing new (`publicRootBackoffs`). */
    publicRootBackoffs: Int,
    publicRootRetryAt: Time,
    publicRootsInFlight: Boolean,
    rng: Rng,
    sensitivity: Sensitivity,
    /** What the Genesis State Machine last said of the ledger; the sensitive state follows it
      * ([[PeerSelection]], `monitorLedgerStateJudgement`).
      */
    reported: LedgerStateJudgement,
    /** The same three as the public roots' (`bigLedgerPeerBackoffs`, `bigLedgerPeerRetryTime`,
      * `inProgressBigLedgerPeersReq`), for the request for big ledger peers.
      */
    bigLedgerBackoffs: Int = 0,
    bigLedgerRetryAt: Time = Time.Zero,
    bigLedgerInFlight: Boolean = false
):
  def record(peer: A): Option[PeerRecord] = peers.get(peer)

  def view(now: Time): GovernorState.View[A] = GovernorState.View(this, now)

  /** Replace the record of a known peer; nothing for an unknown one. */
  def updated(peer: A)(f: PeerRecord => PeerRecord): GovernorState[A] =
    peers.get(peer).fold(this)(r => copy(peers = peers.updated(peer, f(r))))

  def withStatus(peer: A, status: Status): GovernorState[A] = updated(peer)(_.copy(status = status))

object GovernorState:
  def initial[A: Ordering](
      seed: Long,
      configured: LocalRoots[A],
      requested: Targets,
      sensitivity: Sensitivity
  ): GovernorState[A] =
    GovernorState(
      targets = Targets.zero,
      peers = SortedMap.empty[A, PeerRecord],
      configuredRoots = configured,
      localRoots = LocalRoots.empty[A],
      requested = requested,
      publicRootBackoffs = 0,
      publicRootRetryAt = Time.Zero,
      publicRootsInFlight = false,
      rng = Rng(seed),
      sensitivity = sensitivity,
      reported = sensitivity.judgement
    )

  /** The sets the decisions read, derived once from the statuses at one instant
    * (`peerSelectionStateToView`, `Types.hs` 1268-1325). Established peers include those whose
    * connection is closing or lingering; active peers include those being deactivated.
    *
    * The ordinary sets (`known`, `established`, `active`, ...) leave the big ledger peers out, as
    * the reference's do (`viewKnownPeers` is "the known peers excluding big ledger peers",
    * `Types.hs` 1314-1316): a big ledger peer is a population of its own, with its own targets,
    * counters and decisions, which are the sets of [[big]]. The roots are the exception: every
    * public root, the big ledger peers among them, counts towards the root target (`rootPeersSet`,
    * 1310).
    */
  final class View[A] private[selection] (val state: GovernorState[A], val now: Time):
    given Ordering[A] = state.peers.ordering

    private def where(f: (A, PeerRecord) => Boolean): SortedSet[A] =
      SortedSet.from(state.peers.collect { case (p, r) if !r.origin.big && f(p, r) => p })

    lazy val known: SortedSet[A] = where((_, _) => true)
    lazy val established: SortedSet[A] = where((_, r) => r.status.established)
    lazy val active: SortedSet[A] = where((_, r) => r.status.active)

    /** Cold and past its retry time (`availableToConnect`). */
    lazy val availableToConnect: SortedSet[A] = where((_, r) => View.available(r, now))

    /** Warm and past its activation time (`readyPeers`). */
    lazy val ready: SortedSet[A] = where((_, r) => View.ready(r, now))
    lazy val connecting: SortedSet[A] = where((_, r) => r.status == Status.Connecting)
    lazy val activating: SortedSet[A] = where((_, r) => r.status == Status.Activating)
    lazy val deactivating: SortedSet[A] = where((_, r) => r.status == Status.Deactivating)
    lazy val closing: SortedSet[A] = where((_, r) => r.status == Status.Closing)
    lazy val cooling: SortedSet[A] = where((_, r) => r.status == Status.Cooling)
    lazy val localRoots: SortedSet[A] = state.localRoots.keys
    lazy val roots: SortedSet[A] =
      SortedSet.from(state.peers.collect { case (p, r) if r.origin.root => p })
    lazy val bootstrap: SortedSet[A] = where((_, r) => r.origin == Origin.Bootstrap)

    /** The established peers that are not active and not being closed: the warm peers. */
    lazy val warm: SortedSet[A] = established -- active

    /** The big ledger peers, as sets of the same names. */
    lazy val big: Big[A] = new Big(this)

    lazy val counters: Counters = Counters.of(this)

  object View:
    private[selection] def available(r: PeerRecord, now: Time): Boolean = r.status match
      case Status.Cold(retry) => retry.forall(_ <= now)
      case _                  => false

    private[selection] def ready(r: PeerRecord, now: Time): Boolean = r.status match
      case Status.Warm(readyAt) => readyAt <= now
      case _                    => false

  /** The sets of the big ledger peers (`viewKnownBigLedgerPeers` and the others of `Types.hs`
    * 900-920): the peers whose origin is [[Origin.BigLedger]].
    */
  final class Big[A] private[selection] (view: View[A]):
    given Ordering[A] = view.state.peers.ordering

    private def where(f: PeerRecord => Boolean): SortedSet[A] =
      SortedSet.from(view.state.peers.collect { case (p, r) if r.origin.big && f(r) => p })

    lazy val known: SortedSet[A] = where(_ => true)
    lazy val established: SortedSet[A] = where(_.status.established)
    lazy val active: SortedSet[A] = where(_.status.active)
    lazy val availableToConnect: SortedSet[A] = where(View.available(_, view.now))
    lazy val ready: SortedSet[A] = where(View.ready(_, view.now))
    lazy val connecting: SortedSet[A] = where(_.status == Status.Connecting)
    lazy val activating: SortedSet[A] = where(_.status == Status.Activating)
    lazy val deactivating: SortedSet[A] = where(_.status == Status.Deactivating)
    lazy val closing: SortedSet[A] = where(_.status == Status.Closing)
    lazy val cooling: SortedSet[A] = where(_.status == Status.Cooling)

  /** `OutboundConnectionsState` (`Cardano/Network/PeerSelection/Governor/Types.hs` 57-109, Praos
    * mode): trusted when the node is connected only to peers it trusts. In the `LocalRootsOnly`
    * mode that means only trustable local roots; with bootstrap peers in use, only those and
    * trustable local roots with at least one bootstrap peer active; with public roots and no
    * bootstrap peers, never.
    */
  def outboundConnectionsState[A](
      mode: AssociationMode,
      view: View[A]
  ): OutboundConnectionsState =
    given Ordering[A] = view.state.peers.ordering
    val trustableRoots = view.state.localRoots.trustable
    val establishedBootstrap = view.established.intersect(view.bootstrap)
    import OutboundConnectionsState.*
    mode match
      case AssociationMode.LocalRootsOnly =>
        if view.established.subsetOf(trustableRoots) then TrustedStateWithExternalPeers
        else UntrustedState
      case AssociationMode.Unrestricted if view.state.sensitivity.useBootstrapPeers =>
        if view.established.subsetOf(establishedBootstrap ++ trustableRoots) &&
          view.active.exists(view.bootstrap)
        then TrustedStateWithExternalPeers
        else UntrustedState
      case AssociationMode.Unrestricted => UntrustedState

  /** What a state fails to satisfy of the reference's invariants (`assertPeerSelectionState`,
    * `Types.hs` 1328-1427) that the status representation does not already guarantee: the local
    * roots are known and are the peers whose origin is local, and the effective roots are within
    * the configured ones. A violation is a bug and ends the governor
    * ([[GovernorError.InvariantBroken]]).
    */
  def invariant[A](st: GovernorState[A]): List[Violation] =
    val local = st.peers.collect { case (p, r) if r.origin == Origin.Local => p }.toSet
    val roots = st.localRoots.keys
    List(
      Option.when(!roots.forall(st.peers.contains))(Violation.LocalRootNotKnown),
      Option.when(local != roots.toSet)(Violation.OriginDisagreesWithLocalRoots),
      Option.when(!roots.forall(st.configuredRoots.contains))(Violation.RootNotConfigured),
      Option.when(st.localRoots.size > st.targets.known.max(0) && st.targets != Targets.zero)(
        Violation.RootsAboveKnownTarget
      ),
      Option.when(st.peers.exists((_, r) => r.failCount < 0))(Violation.NegativeFailCount)
    ).flatten

  enum Violation:
    case LocalRootNotKnown
    case OriginDisagreesWithLocalRoots
    case RootNotConfigured
    case RootsAboveKnownTarget
    case NegativeFailCount

  /** The counters of the reference (`PeerSelectionCounters`, `Types.hs` 851-1060), derived from the
    * view. The ordinary ones leave the big ledger peers out; theirs are the `*BigLedger*` ones.
    */
  final case class Counters(
      root: Int,
      known: Int,
      availableToConnect: Int,
      coldPromotions: Int,
      established: Int,
      warmDemotions: Int,
      warmPromotions: Int,
      active: Int,
      activeDemotions: Int,
      knownLocalRoots: Int,
      availableToConnectLocalRoots: Int,
      coldLocalRootsPromotions: Int,
      establishedLocalRoots: Int,
      warmLocalRootsPromotions: Int,
      activeLocalRoots: Int,
      activeLocalRootsDemotions: Int,
      knownNonRoot: Int,
      coldNonRootPromotions: Int,
      establishedNonRoot: Int,
      warmNonRootDemotions: Int,
      warmNonRootPromotions: Int,
      activeNonRoot: Int,
      activeNonRootDemotions: Int,
      knownBootstrap: Int,
      coldBootstrapPromotions: Int,
      establishedBootstrap: Int,
      warmBootstrapDemotions: Int,
      warmBootstrapPromotions: Int,
      activeBootstrap: Int,
      activeBootstrapDemotions: Int,
      knownBigLedger: Int,
      availableToConnectBigLedger: Int,
      coldBigLedgerPromotions: Int,
      establishedBigLedger: Int,
      warmBigLedgerDemotions: Int,
      warmBigLedgerPromotions: Int,
      activeBigLedger: Int,
      activeBigLedgerDemotions: Int,
      /** The known peers by where they came from (not the reference's; the proof that a peer was
        * taken from the ledger and not only from the bootstrap list).
        */
      byOrigin: Map[Origin, Int]
  ):
    /** `PeerSelectionCountersHWC`: cold, warm and hot are the known beyond the established, the
      * established beyond the active, and the active.
      */
    def cold: Int = known - established
    def warm: Int = established - active
    def hot: Int = active
    def coldLocalRoots: Int = knownLocalRoots - establishedLocalRoots
    def warmLocalRoots: Int = establishedLocalRoots - activeLocalRoots
    def hotLocalRoots: Int = activeLocalRoots
    def coldBigLedger: Int = knownBigLedger - establishedBigLedger
    def warmBigLedger: Int = establishedBigLedger - activeBigLedger
    def hotBigLedger: Int = activeBigLedger

  object Counters:
    def of[A](view: View[A]): Counters =
      val local = view.localRoots
      val nonRoot = view.known -- view.roots
      val boot = view.bootstrap
      val big = view.big
      def n(s: Set[A]): Int = s.size
      Counters(
        root = n(view.roots),
        known = n(view.known),
        availableToConnect = n(view.availableToConnect),
        coldPromotions = n(view.connecting),
        established = n(view.established),
        warmDemotions = n(view.closing),
        warmPromotions = n(view.activating),
        active = n(view.active),
        activeDemotions = n(view.deactivating),
        knownLocalRoots = n(local),
        availableToConnectLocalRoots = n(view.availableToConnect.intersect(local)),
        coldLocalRootsPromotions = n(view.connecting.intersect(local)),
        establishedLocalRoots = n(view.established.intersect(local)),
        warmLocalRootsPromotions = n(view.activating.intersect(local)),
        activeLocalRoots = n(view.active.intersect(local)),
        activeLocalRootsDemotions = n(view.deactivating.intersect(local)),
        knownNonRoot = n(nonRoot),
        coldNonRootPromotions = n(view.connecting.intersect(nonRoot)),
        establishedNonRoot = n(view.established.intersect(nonRoot)),
        warmNonRootDemotions = n(view.closing.intersect(nonRoot)),
        warmNonRootPromotions = n(view.activating.intersect(nonRoot)),
        activeNonRoot = n(view.active.intersect(nonRoot)),
        activeNonRootDemotions = n(view.deactivating.intersect(nonRoot)),
        knownBootstrap = n(boot),
        coldBootstrapPromotions = n(view.connecting.intersect(boot)),
        establishedBootstrap = n(view.established.intersect(boot)),
        warmBootstrapDemotions = n(view.closing.intersect(boot)),
        warmBootstrapPromotions = n(view.activating.intersect(boot)),
        activeBootstrap = n(view.active.intersect(boot)),
        activeBootstrapDemotions = n(view.deactivating.intersect(boot)),
        knownBigLedger = n(big.known),
        availableToConnectBigLedger = n(big.availableToConnect),
        coldBigLedgerPromotions = n(big.connecting),
        establishedBigLedger = n(big.established),
        warmBigLedgerDemotions = n(big.closing),
        warmBigLedgerPromotions = n(big.activating),
        activeBigLedger = n(big.active),
        activeBigLedgerDemotions = n(big.deactivating),
        byOrigin = Origin.values.map(o => o -> view.state.peers.count(_._2.origin == o)).toMap
      )

object Time:
  val Zero: Time = scala.concurrent.duration.Duration.Zero
