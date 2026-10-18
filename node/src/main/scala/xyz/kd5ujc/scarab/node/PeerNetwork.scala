// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Async, Clock, Deferred, Ref, Resource}
import cats.effect.std.{Queue, Supervisor}
import cats.mtl.{Ask, Handle, Raise, Stateful, Tell}
import cats.syntax.all.*
import com.comcast.ip4s.{Host, IpAddress, SocketAddress}
import java.time.Instant
import scala.concurrent.duration.*
import io.circe.Json
import io.circe.syntax.*
import xyz.kd5ujc.scarab.ledger.{EpochGeometry, GovernorFault, NodeError, PeerError}
import xyz.kd5ujc.scarab.network.*
import xyz.kd5ujc.scarab.network.selection.*

/** The outbound peer network of the node: the governor ([[PeerGovernor]]), the connections it moves
  * between temperatures ([[OutboundPeers]]), the Genesis State Machine ([[GsmRunner]]) that tells
  * it whether the ledger is too old, and the regime that sets its targets. This assembles them and
  * gives the node one effect to run, which never ends unless the governor stops the node.
  */
object PeerNetwork:
  /** A peer is named by its access point, as the topology writes it. */
  type Addr = SocketAddress[Host]

  given Ordering[Addr] = Ordering.by(_.toString)

  /** The roots of the node, from `--peer` and the topology. */
  final case class Roots(
      /** The local root groups: `--peer` as one group first, then the topology's. */
      local: List[RootGroupSpec[Addr]],
      /** The public roots of the topology, used when the node is not in the sensitive state. */
      public: Set[Addr],
      /** The bootstrap peers, the only source in the sensitive state; none when not configured. */
      bootstrap: Option[Vector[Addr]],
      /** `useLedgerAfterSlot`: whether and after which slot the node takes ledger peers. */
      ledger: UseLedgerPeers,
      /** The peer snapshot file of the topology, as the node starts with it. */
      snapshot: SnapshotUse = SnapshotUse.Unused,
      advertise: Map[Addr, Boolean] = Map.empty
  ):
    /** Whether the topology enables ledger peers (it matters for the association mode). */
    def ledgerPeers: Boolean = ledger.isEnabled

  final case class Settings(
      roots: Roots,
      /** The targets of the node when it is not syncing: the relay or producer defaults, as
        * configured.
        */
      targets: Targets,
      policy: Policy = Policy(),
      peerSharing: Boolean = false,
      /** The sleep between churn rounds and the waits of their steps (3300 s with fuzz 600 s in the
        * fetch mode deadline, 900 s with 60 s in bulk sync, the reference's; shortened by the
        * test-only flag of the `governor` scenario).
        */
      intervals: Churn.Intervals = Churn.Intervals(),
      timeouts: Churn.Timeouts = Churn.Timeouts(),
      seed: Long
  )

  /** What the node reads of its chain for the Genesis State Machine and the fetch mode. */
  trait ChainView[F[_]]:
    /** The slot of the tip and an identity of the selection; none for an empty chain. */
    def tip: F[Option[(BigInt, Long)]]

    /** The era history as the chain knows it now (a chain whose Byron span is derived learns it).
      */
    def geometry: F[EpochGeometry]

  /** The running network: what is shared with the rest of the node. */
  final class Running[F[_], A](
      val state: Ref[F, GovernorState[A]],
      val outbound: OutboundPeers[F, A],
      val connections: Ref[F, OutboundConnectionsState],
      val fetchMode: Ref[F, FetchMode],
      val judgement: Ref[F, LedgerStateJudgement],
      /** The peer metrics the hot sessions report to and the governor ranks demotions by. */
      val metrics: Ref[F, PeerMetrics[A]],
      /** Never returns normally; raises through `fatal` when the governor stops the node. */
      val run: F[Nothing],
      val fatal: Deferred[F, GovernorFault]
  )

  /** The roots of a node: `--peer` as one local root group with `hotValency = warmValency = n`
    * (owner decision 1), then the groups of the topology with their own valencies, flags and
    * provenance; the public roots and the bootstrap peers of the topology.
    */
  def roots(
      peers: List[Addr],
      topology: Option[Topology],
      snapshot: SnapshotUse = SnapshotUse.Unused
  ): Roots =
    val given_ = RootConfig(
      advertise = false,
      DiffusionMode.InitiatorAndResponder,
      xyz.kd5ujc.scarab.protocol.ConnectionState.Provenance.Outbound,
      trustable = false
    )
    val cli =
      Option.when(peers.nonEmpty)(RootGroupSpec(peers.size, peers.size, peers.map(_ -> given_)))
    val file = topology.toList.flatMap(_.localRoots).map { g =>
      val config = RootConfig(
        g.advertise,
        if g.diffusionMode == "InitiatorOnly" then DiffusionMode.InitiatorOnly
        else DiffusionMode.InitiatorAndResponder,
        if g.behindFirewall then xyz.kd5ujc.scarab.protocol.ConnectionState.Provenance.Inbound
        else xyz.kd5ujc.scarab.protocol.ConnectionState.Provenance.Outbound,
        g.trustable
      )
      RootGroupSpec(
        g.hotValency,
        g.warmValency,
        g.accessPoints.toList.map(_.socketAddress -> config)
      )
    }
    Roots(
      local = cli.toList ++ file,
      public =
        topology.toList.flatMap(_.publicRoots).flatMap(_.accessPoints).map(_.socketAddress).toSet,
      bootstrap = topology.flatMap(_.bootstrapPeers).map(_.map(_.socketAddress)),
      ledger = topology.fold[UseLedgerPeers](UseLedgerPeers.Disabled)(_.useLedgerAfterSlot),
      snapshot = snapshot,
      advertise = topology.toList
        .flatMap(_.publicRoots)
        .flatMap(g => g.accessPoints.map(_.socketAddress -> g.advertise))
        .toMap
    )

  /** Whether there is anything to start from: a local root, a public root or a bootstrap peer. */
  extension (roots: Roots)
    def hasPeers: Boolean =
      roots.local.exists(_.members.nonEmpty) || roots.public.nonEmpty ||
        roots.bootstrap.exists(_.nonEmpty)

  /** The peers the governor knows, with their temperature, for `GET /connections`. */
  def json(state: GovernorState[Addr], now: Time): Json =
    val view = state.view(now)
    def temperature(r: PeerRecord): String = r.status match
      case Status.Cold(_)      => "cold"
      case Status.Connecting   => "connecting"
      case Status.Warm(_)      => "warm"
      case Status.Activating   => "activating"
      case Status.Hot          => "hot"
      case Status.Deactivating => "deactivating"
      case Status.Closing      => "closing"
      case Status.Cooling      => "cooling"
    val c = view.counters
    Json.obj(
      "counters" -> Json.obj(
        "cold" -> c.cold.asJson,
        "warm" -> c.warm.asJson,
        "hot" -> c.hot.asJson,
        "known" -> c.known.asJson,
        "root" -> c.root.asJson
      ),
      "bigLedger" -> Json.obj(
        "known" -> c.knownBigLedger.asJson,
        "established" -> c.establishedBigLedger.asJson,
        "active" -> c.activeBigLedger.asJson
      ),
      "targets" -> Json.obj(
        "root" -> state.targets.root.asJson,
        "known" -> state.targets.known.asJson,
        "established" -> state.targets.established.asJson,
        "active" -> state.targets.active.asJson,
        "knownBigLedger" -> state.targets.knownBig.asJson,
        "establishedBigLedger" -> state.targets.establishedBig.asJson,
        "activeBigLedger" -> state.targets.activeBig.asJson
      ),
      "judgement" -> state.sensitivity.judgement.toString.asJson,
      "sensitive" -> state.sensitivity.sensitive.asJson,
      "peers" -> state.peers.toList.map { (peer, r) =>
        Json.obj(
          "peer" -> peer.toString.asJson,
          "temperature" -> temperature(r).asJson,
          "origin" -> r.origin.toString.asJson,
          "failCount" -> r.failCount.asJson,
          "advertise" -> r.advertise.asJson,
          "everConnected" -> r.everConnected.asJson,
          "peerSharing" -> r.sharing.asJson
        )
      }.asJson
    )

  /** The local roots as the governor takes them: `--peer` and the topology, repaired. */
  def configuredRoots(roots: Roots): LocalRoots[Addr] = LocalRoots.fromGroups(roots.local)

  def resource[F[_]: Async](
      settings: Settings,
      transport: Transport[F, Addr],
      manager: ConnectionManager[F],
      config: OutboundPeers.Config,
      hot: OutboundPeers.Hot[F],
      chain: ChainView[F],
      /** The fetch mode, written here every second and read by BlockFetch ([[BlockPool]]) and by
        * the churn governor; the caller owns the cell so that it can hand it to both before the
        * network starts.
        */
      fetchMode: Ref[F, FetchMode],
      emit: Output[Addr] => F[Unit],
      gsm: (Gsm.Phase, Gsm.Event) => F[Unit],
      gsmInitial: Gsm.Phase => F[Unit],
      fetchModeChanged: FetchMode => F[Unit],
      marker: GsmRunner.Marker[F],
      /** The ledger the ledger peers are taken from. */
      ledgerSource: LedgerPeersSource[F],
      sharing: Option[PeerSharingRuntime[F]] = None
  )(using Handle[F, NodeError]): Resource[F, Running[F, Addr]] =
    val F = Async[F]
    val roots = configuredRoots(settings.roots)
    // Without ledger peers there are no big ledger peers to want: the targets for them are zero,
    // and the churn governor leaves its six steps for them out (a node that never has any would
    // only wait out their timeouts). The reference asks all the same and gets the public roots.
    val base: Targets =
      if settings.roots.ledgerPeers then settings.targets
      else
        settings.targets
          .revised(knownBig = 0, establishedBig = 0, activeBig = 0)
          .getOrElse(settings.targets)
    val bootstrapOn = settings.roots.bootstrap.isDefined
    val localHot = roots.groups.map(_.hot.value).sum
    val association = AssociationMode.of(
      useLedgerPeers = settings.roots.ledgerPeers,
      peerSharing = settings.peerSharing,
      useBootstrapPeers = bootstrapOn,
      bootstrapEmpty = settings.roots.bootstrap.exists(_.isEmpty)
    )
    for
      supervisor <- Supervisor[F](await = false)
      events <- Resource.eval(Queue.unbounded[F, Event[Addr]])
      _ <- sharing.traverse_(runtime =>
        Resource
          .eval(
            supervisor.supervise(
              (runtime.changed *> Clock[F].monotonic.flatMap(at => events.offer(Event.Wake(at))))
                .foreverM[Unit]
            )
          )
          .void
      )
      fatal <- Resource.eval(Deferred[F, GovernorFault])
      initialKind = Regime.kind(FetchMode.BulkSync, bootstrapOn)
      state <- Resource.eval(
        Ref.of[F, GovernorState[Addr]](
          GovernorState
            .initial[Addr](
              settings.seed,
              roots,
              Regime.initial(initialKind, base, localHot),
              Sensitivity.initial(bootstrapOn)
            )
            .copy(sharing = PeerShare.Governor[Addr](enabled = settings.peerSharing))
        )
      )
      connections <- Resource.eval(
        Ref.of[F, OutboundConnectionsState](OutboundConnectionsState.UntrustedState)
      )
      metrics <- Resource.eval(Ref.of[F, PeerMetrics[Addr]](PeerMetrics.empty[Addr]()))
      judgement <- Resource.eval(Ref.of[F, LedgerStateJudgement](LedgerStateJudgement.TooOld))
      worker <- Resource.eval {
        given Tell[F, LedgerPeersTrace] = new Tell[F, LedgerPeersTrace]:
          def functor = F
          def tell(l: LedgerPeersTrace): F[Unit] = emit(Output.LedgerPeers(l))
        val snapshot = settings.roots.snapshot
        // the worker's generator is split from the governor's seed: a stream of its own
        val workerRng = Rng(settings.seed).split._1
        LedgerPeersWorker
          .create[F](settings.roots.ledger, ledgerSource, snapshot.snapshot, workerRng)
          .flatTap(_ =>
            snapshot match
              case SnapshotUse.Loaded(s) =>
                emit(
                  Output.LedgerPeers(LedgerPeersTrace.SnapshotLoaded(s.slot, s.pools.size))
                )
              case SnapshotUse.Ignored(error) =>
                emit(Output.LedgerPeers(LedgerPeersTrace.SnapshotIgnored(error)))
              case SnapshotUse.Unused => F.unit
          )
      }
      outbound <- Resource.eval(
        OutboundPeers.create[F, Addr](
          transport,
          manager,
          config,
          hot,
          supervisor,
          events.offer,
          RefStateful(metrics),
          sharing.map(s =>
            (peer: Addr, remote: SocketAddress[IpAddress]) => s.connected(peer, remote)
          )
        )
      )
    yield
      given Stateful[F, GovernorState[Addr]] = RefStateful(state)
      given Ask[F, GovernorConfig] = Ask.const[F, GovernorConfig](
        GovernorConfig(settings.policy, association)
      )
      given Ask[F, PeerMetrics[Addr]] = RefStateful.reader(metrics)
      given Raise[F, GovernorError] = new Raise[F, GovernorError]:
        def functor = F
        def raise[E2 <: GovernorError, B](e: E2): F[B] =
          val fault = e match
            case GovernorError.InvariantBroken(vs) =>
              GovernorFault.InvariantBroken(vs)
            case GovernorError.BootstrapPeersCriticalTimeout =>
              GovernorFault.BootstrapPeersCriticalTimeout
          fatal.complete(fault) *> F.never[B]
      given Tell[F, Output[Addr]] = new Tell[F, Output[Addr]]:
        def functor = F
        def tell(l: Output[Addr]): F[Unit] =
          (l match
            case Output.Connections(s) => connections.set(s)
            case _                     => F.unit
          ) *> emit(l)

      val rootProvider: RootProvider[F, Addr] =
        NodeRootProvider[F](
          settings.roots.public,
          settings.roots.bootstrap.getOrElse(Vector.empty),
          worker,
          settings.roots.advertise
        )

      val modeCell: Ref[F, FetchMode] = fetchMode
      // the fetch mode of the node, once a second (the reference reads it from consensus when it needs
      // it); published for BlockFetch and the churn governor, told when it changes
      val modes: F[Nothing] =
        F.tailRecM[FetchMode, Nothing](FetchMode.BulkSync) { previous =>
          F.sleep(1.second) *> (for
            mode <- currentFetchMode(state, chain)
            _ <- fetchMode.set(mode)
            _ <- fetchModeChanged(mode).whenA(mode != previous)
          yield Left(mode))
        }

      // the targets: the head start, then the churn rounds; the one writer of the governor's targets
      val churn: F[Nothing] =
        given Ask[F, ChurnLoop.Config] = Ask.const[F, ChurnLoop.Config](
          ChurnLoop.Config(
            base = base,
            regime = Regime.kind(_, bootstrapOn),
            bigLedger = settings.roots.ledgerPeers,
            intervals = settings.intervals,
            timeouts = settings.timeouts,
            seed = settings.seed
          )
        )
        given Tell[F, Churn.Trace] = new Tell[F, Churn.Trace]:
          def functor = F
          def tell(l: Churn.Trace): F[Unit] = emit(Output.Churned(l))
        ChurnLoop.run[F](new ChurnWorld[F]:
          def counters = (state.get, Clock[F].monotonic).mapN((st, now) => st.view(now).counters)
          def localHot = state.get.map { st =>
            val effective = st.localRoots.groups.map(_.hot.value).sum
            if effective > 0 then effective else st.configuredRoots.groups.map(_.hot.value).sum
          }
          def fetchMode = modeCell.get
          def requested = state.get.map(_.requested)
          def request(targets: Targets) = events.offer(Event.TargetsRequested(targets)))

      val gsmLoop: F[Nothing] =
        GsmRunner.run[F](
          GsmRunner.Env[F](
            reading = gsmReading(outbound, connections, chain),
            marker = marker,
            publish = j => judgement.set(j) *> events.offer(Event.JudgementReported(j)),
            initial = gsmInitial,
            emit = gsm,
            seed = settings.seed
          )
        )

      val governor: F[Nothing] =
        val actions = sharing.map { runtime =>
          new PeerSharingActions[F, Addr]:
            def inbound = runtime.inbound
            def publish(st: GovernorState[Addr]) = runtime.publish(st)
            def request(peer: Addr, amount: xyz.kd5ujc.scarab.protocol.PeerSharing.Amount) =
              outbound.requestPeerShare(peer, amount)(address =>
                PeerSharingAddress.socket(address).map(ip => SocketAddress[Host](ip.host, ip.port))
              )
        }
        PeerGovernor.run[F, Addr](outbound, rootProvider, events, supervisor, actions)

      val loops: F[Nothing] =
        F.race(governor, F.race(modes, F.race(churn, gsmLoop)))
          .flatMap(_.fold(identity, _.fold(identity, _.fold(identity, identity))))
      Running(state, outbound, connections, fetchMode, judgement, metrics, loops, fatal)

  /** `readFetchModeDefault` (Praos): bulk sync in the sensitive state, else by the distance of the
    * chain from the wall clock.
    */
  private def currentFetchMode[F[_]: Async](
      state: Ref[F, GovernorState[Addr]],
      chain: ChainView[F]
  ): F[FetchMode] =
    for
      st <- state.get
      tip <- chain.tip
      now <- Clock[F].realTime.map(d => Instant.ofEpochMilli(d.toMillis))
      geometry <- chain.geometry
      started = geometry.systemStart.isBefore(now)
      mode = FetchMode.of(
        st.sensitivity.sensitive,
        Option.when(started)(geometry.slotAt(now)),
        tip.map(_._1)
      )
    yield mode

  /** The conditions of the Genesis State Machine: peers, selection, the assumption. */
  private def gsmReading[F[_]: Async](
      outbound: OutboundPeers[F, Addr],
      connections: Ref[F, OutboundConnectionsState],
      chain: ChainView[F]
  ): F[Gsm.Reading] =
    for
      peers <- outbound.readings
      assumption <- connections.get.map(_ == OutboundConnectionsState.TrustedStateWithExternalPeers)
      tip <- chain.tip
      geometry <- chain.geometry
      now <- Clock[F].realTime.map(d => Instant.ofEpochMilli(d.toMillis))
      onset = geometry.slotStart(tip.fold(BigInt(0))(_._1))
      limit = onset.plusSeconds(MaxCaughtUpAge.toSeconds)
      age =
        if !limit.isAfter(now) then Gsm.DurationFromNow.Already
        else Gsm.DurationFromNow.After((limit.toEpochMilli - now.toEpochMilli).millis)
    yield Gsm.Reading(assumption, peers, tip.fold(-1L)(_._2), Some(age))

  /** The oldest tip a caught up node tolerates (`llrnMaxCaughtUpAge`, `Node.hs` 1106). */
  val MaxCaughtUpAge: FiniteDuration = 20.minutes

  /** The reason text of a failed resolution. */
  def unresolved(host: Host, t: Throwable): PeerError =
    PeerError.Unavailable(PeerError.Unavailability.Resolution(host.toString, t))

  /** The TCP transport: the first address a name resolves to, and a plain connection to it. */
  def tcp[F[_]: Async](connectTimeout: FiniteDuration): Transport[F, Addr] = new Transport[F, Addr]:
    def resolve(peer: Addr) =
      ConnectionManager
        .resolve[F](peer)
        .map(_.asRight[PeerError])
        .handleError(t => unresolved(peer.host, t).asLeft[SocketAddress[IpAddress]])

    def bearer(peer: Addr, remote: SocketAddress[IpAddress])(using Raise[F, NetworkError]) =
      Bearer.tcp[F](SocketAddress[Host](remote.host, remote.port), connectTimeout)
