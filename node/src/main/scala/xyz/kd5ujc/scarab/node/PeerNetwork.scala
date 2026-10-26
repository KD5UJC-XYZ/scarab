// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Async, Clock, Deferred, Ref, Resource}
import cats.effect.std.{Queue, Supervisor}
import cats.effect.syntax.all.*
import cats.mtl.{Ask, Handle, Raise, Stateful, Tell}
import cats.syntax.all.*
import com.comcast.ip4s.{Host, IpAddress, SocketAddress}
import java.time.Instant
import scala.concurrent.duration.*
import io.circe.Json
import io.circe.syntax.*
import xyz.kd5ujc.scarab.ledger.{EpochGeometry, GovernorFault, NodeError, PeerError, StorageError}
import xyz.kd5ujc.scarab.network.*
import xyz.kd5ujc.scarab.network.selection.*
import xyz.kd5ujc.scarab.storage.ImmutableStore

/** The outbound peer network of the node: the governor ([[PeerGovernor]]), the connections it moves
  * between temperatures ([[OutboundPeers]]), the Genesis State Machine ([[GsmRunner]]) that tells
  * it whether the ledger is too old, and the regime that sets its targets. This assembles them and
  * gives the node one effect to run, which never ends unless the governor stops the node.
  */
object PeerNetwork:
  /** A peer is named by its access point, as the topology writes it. */
  type Addr = SocketAddress[Host]

  given Ordering[Addr] = Ordering.by(_.toString)

  private[node] def runSnapshotCheck[F[_]: Async, A](
      check: F[Boolean],
      canceled: F[Unit],
      generation: BigInt,
      events: Queue[F, Event[A]]
  ): F[Unit] =
    Async[F].race(check, canceled).flatMap {
      case Left(matches) => events.offer(Event.SnapshotVerificationCompleted(generation, matches))
      case Right(())     => Async[F].unit
    }

  private[node] def publishSnapshotVerification[F[_]: Async, A](
      event: Event[A],
      activeGeneration: F[BigInt],
      publish: Boolean => F[Unit]
  ): F[Unit] = event match
    case Event.SnapshotVerificationCompleted(generation, matches) =>
      activeGeneration.flatMap(current =>
        if current == generation then publish(matches) else Async[F].unit
      )
    case _ => Async[F].unit

  /** Long-lived supervisor children report unexpected failures to the network owner. Cancellation
    * remains cancellation; the fatal Deferred carries the original defect cause unchanged.
    */
  private[node] def reportBackgroundDefect[F[_]: Async, A](
      job: F[A],
      fatal: Deferred[F, GovernorFault],
      toFault: Throwable => GovernorFault
  ): F[A] =
    val F = Async[F]
    F.handleErrorWith(job) { cause =>
      fatal.complete(toFault(cause)).void *> F.never[A]
    }

  private[node] def fatalForGovernor(fault: GovernorFault): Option[Fatal] = fault match
    case GovernorFault.SnapshotStorage(error)            => Some(Fatal.Storage(error))
    case GovernorFault.SnapshotVerificationDefect(cause) => Some(Fatal.Defect(cause))
    case GovernorFault.LocalDnsRefreshDefect(cause)      => Some(Fatal.Defect(cause))
    case _                                               => None

  /** The roots of the node, from `--peer` and the topology.
    *
    * @param local
    *   The local root groups: `--peer` as one group first, then the topology's.
    * @param public
    *   The public roots of the topology, used when the node is not in the sensitive state.
    * @param bootstrap
    *   The bootstrap peers, the only source in the sensitive state; none when not configured.
    * @param ledger
    *   `useLedgerAfterSlot`: whether and after which slot the node takes ledger peers.
    * @param snapshot
    *   The peer snapshot file of the topology, as the node starts with it.
    */
  final case class Roots(
      local: List[RootGroupSpec[Addr]],
      public: Set[Addr],
      bootstrap: Option[Vector[Addr]],
      ledger: UseLedgerPeers,
      snapshot: SnapshotUse = SnapshotUse.Unused,
      advertise: Map[Addr, Boolean] = Map.empty,
      publicSrv: Vector[(DnsName, Boolean)] = Vector.empty,
      bootstrapSrv: Vector[DnsName] = Vector.empty,
      localSrv: List[List[(DnsName, RootConfig)]] = Nil
  ):
    /** Whether the topology enables ledger peers (it matters for the association mode). */
    def ledgerPeers: Boolean = ledger.isEnabled

    /** An explicitly empty bootstrap list still enables the policy, as does an SRV-only source. */
    def bootstrapConfigured: Boolean = bootstrap.isDefined || bootstrapSrv.nonEmpty

  /** @param targets
    *   The targets of the node when it is not syncing: the relay or producer defaults, as
    *   configured.
    * @param intervals
    *   The sleep between churn rounds and the waits of their steps (3300 s with fuzz 600 s in the
    *   fetch mode deadline, 900 s with 60 s in bulk sync, the reference's; shortened by the
    *   test-only flag of the `governor` scenario).
    */
  final case class Settings(
      roots: Roots,
      targets: Targets,
      consensusMode: ConsensusMode = ConsensusMode.PraosMode,
      syncTargets: Targets = Targets.sync,
      minBigLedgerPeersForTrustedState: Int = 5,
      policy: Policy = Policy(),
      peerSharing: Boolean = false,
      intervals: Churn.Intervals = Churn.Intervals(),
      timeouts: Churn.Timeouts = Churn.Timeouts(),
      seed: Long
  )

  final case class Runtime[F[_]](
      generation: BigInt,
      roots: Roots,
      resolvedLocalRoots: List[RootGroupSpec[Addr]],
      localDnsCache: LocalRootDnsCache.State[NodeRootProvider.LocalDnsKey, Addr],
      worker: LedgerPeersWorker[F],
      association: AssociationMode,
      churn: ChurnLoop.Config,
      snapshotCheckStarted: Boolean,
      snapshotCheckCanceled: Deferred[F, Unit]
  )

  /** What the node reads of its chain for the Genesis State Machine and the fetch mode. */
  trait ChainView[F[_]]:
    /** The slot of the tip and an identity of the selection; none for an empty chain. */
    def tip: F[Option[(BigInt, Long)]]

    /** The era history as the chain knows it now (a chain whose Byron span is derived learns it).
      */
    def geometry: F[EpochGeometry]

  /** The running network: what is shared with the rest of the node.
    *
    * @param metrics
    *   The peer metrics the hot sessions report to and the governor ranks demotions by.
    * @param reloadRoots
    *   Replace a fully parsed topology and peer snapshot as one root-provider update.
    * @param run
    *   Never returns normally; raises through `fatal` when the governor stops the node.
    */
  final class Running[F[_], A](
      val state: Ref[F, GovernorState[A]],
      val outbound: OutboundPeers[F, A],
      val connections: Ref[F, OutboundConnectionsState],
      val fetchMode: Ref[F, FetchMode],
      val judgement: Ref[F, LedgerStateJudgement],
      val metrics: Ref[F, PeerMetrics[A]],
      val reloadRoots: Roots => F[Unit],
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
    val fileSrv = topology.toList.flatMap(_.localRoots).map { group =>
      val config = RootConfig(
        group.advertise,
        if group.diffusionMode == "InitiatorOnly" then DiffusionMode.InitiatorOnly
        else DiffusionMode.InitiatorAndResponder,
        if group.behindFirewall then xyz.kd5ujc.scarab.protocol.ConnectionState.Provenance.Inbound
        else xyz.kd5ujc.scarab.protocol.ConnectionState.Provenance.Outbound,
        group.trustable
      )
      group.srvAccessPoints.toList.map(_ -> config)
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
        .toMap,
      publicSrv = topology.toList
        .flatMap(_.publicRoots)
        .flatMap(group => group.srvAccessPoints.map(_ -> group.advertise))
        .toVector,
      bootstrapSrv = topology.toList.flatMap(_.bootstrapSrvPeers).toVector,
      localSrv = cli.toList.map(_ => List.empty) ++ fileSrv
    )

  /** Whether there is anything to start from: a local root, a public root or a bootstrap peer. */
  extension (roots: Roots)
    def hasPeers: Boolean =
      roots.local.exists(_.members.nonEmpty) || roots.public.nonEmpty ||
        roots.bootstrap.exists(
          _.nonEmpty
        ) || roots.publicSrv.nonEmpty || roots.bootstrapSrv.nonEmpty ||
        roots.localSrv.exists(_.nonEmpty)

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

  /** @param fetchMode
    *   The fetch mode, written here every second and read by BlockFetch ([[BlockPool]]) and by the
    *   churn governor; the caller owns the cell so that it can hand it to both before the network
    *   starts.
    * @param ledgerSource
    *   The ledger the ledger peers are taken from.
    * @param peerMetrics
    *   A caller-owned board shared with inbound duplex clients; otherwise allocate a private board.
    */
  def resource[F[_]: Async](
      settings: Settings,
      transport: Transport[F, Addr],
      manager: ConnectionManager[F],
      config: OutboundPeers.Config,
      hot: OutboundPeers.Hot[F],
      chain: ChainView[F],
      fetchMode: Ref[F, FetchMode],
      emit: Output[Addr] => F[Unit],
      gsm: (Gsm.Phase, Gsm.Event) => F[Unit],
      gsmInitial: Gsm.Phase => F[Unit],
      fetchModeChanged: FetchMode => F[Unit],
      marker: GsmRunner.Marker[F],
      ledgerSource: LedgerPeersSource[F],
      sharing: Option[PeerSharingRuntime[F]] = None,
      immutable: Option[ImmutableStore[F]] = None,
      genesisReadings: Option[F[Vector[Gsm.PeerReading]]] = None,
      peerMetrics: Option[Ref[F, PeerMetrics[Addr]]] = None
  )(using
      node: Handle[F, NodeError],
      dnsResource: Resource[F, PeerDns[F]]
  ): Resource[F, Running[F, Addr]] =
    val F = Async[F]
    val base: Targets = settings.targets
    val bootstrapOn = settings.roots.bootstrapConfigured
    val association = AssociationMode.of(
      useLedgerPeers = settings.roots.ledgerPeers,
      peerSharing = settings.peerSharing,
      useBootstrapPeers = bootstrapOn,
      bootstrapEmpty =
        settings.roots.bootstrap.exists(_.isEmpty) && settings.roots.bootstrapSrv.isEmpty
    )
    val workerTrace = new Tell[F, LedgerPeersTrace]:
      def functor = F
      def tell(l: LedgerPeersTrace): F[Unit] = emit(Output.LedgerPeers(l))
    def createWorker(roots: Roots): F[LedgerPeersWorker[F]] =
      given Tell[F, LedgerPeersTrace] = workerTrace
      val snapshot = roots.snapshot
      val workerRng = Rng(settings.seed).split._1
      LedgerPeersWorker
        .create[F](roots.ledger, ledgerSource, snapshot.snapshot, workerRng)
        .flatTap(_ =>
          snapshot match
            case SnapshotUse.Loaded(s) =>
              emit(Output.LedgerPeers(LedgerPeersTrace.SnapshotLoaded(s.slot, s.pools.size)))
            case SnapshotUse.Ignored(error) =>
              emit(Output.LedgerPeers(LedgerPeersTrace.SnapshotIgnored(error)))
            case SnapshotUse.Unused => F.unit
        )
    for
      supervisor <- Supervisor[F](await = false)
      dns <- dnsResource
      dnsRng <- Resource.eval(Ref.of[F, Rng](Rng(settings.seed).split._2))
      // Static roots are usable before the owned refresh worker obtains DNS answers.
      initialDnsTime <- Resource.eval(Clock[F].monotonic)
      initialLocalDnsCache = LocalRootDnsCache.track(
        LocalRootDnsCache.empty[NodeRootProvider.LocalDnsKey, Addr],
        NodeRootProvider.localDnsEntries(settings.roots.local, settings.roots.localSrv).map(_.key),
        initialDnsTime
      )
      initialLocal = NodeRootProvider.localGroupsFromCache(
        settings.roots.local,
        settings.roots.localSrv,
        initialLocalDnsCache
      )
      roots = LocalRoots.fromGroups(initialLocal)
      localHot = roots.groups.map(_.hot.value).sum
      events <- Resource.eval(Queue.unbounded[F, Event[Addr]])
      rootInstallAcks <- Resource.eval(
        Ref.of[F, Map[BigInt, (Runtime[F], Deferred[F, Unit])]](Map.empty)
      )
      nextRootGeneration <- Resource.eval(Ref.of[F, BigInt](BigInt(0)))
      rootRefreshWake <- Resource.eval(Queue.bounded[F, Unit](1))
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
      initialKind = Regime.kind(settings.consensusMode, FetchMode.BulkSync, bootstrapOn)
      initialBase = Churn.targetsFor(
        settings.consensusMode,
        LedgerStateJudgement.TooOld,
        base,
        settings.syncTargets
      )
      state <- Resource.eval(
        Ref.of[F, GovernorState[Addr]](
          GovernorState
            .initial[Addr](
              settings.seed,
              roots,
              Regime.initial(settings.consensusMode, initialKind, initialBase, localHot),
              Sensitivity.initial(
                bootstrapOn,
                settings.consensusMode,
                settings.syncTargets,
                base,
                settings.minBigLedgerPeersForTrustedState
              )
            )
            .copy(sharing = PeerShare.Governor[Addr](enabled = settings.peerSharing))
        )
      )
      initialCounters <- Resource.eval(
        (state.get, Clock[F].monotonic).mapN((st, now) => st.view(now).counters)
      )
      churnTargets <- Resource.eval(
        ChurnTargets.create[F](
          initialCounters,
          Churn.Proposal.from(
            Regime.initial(settings.consensusMode, initialKind, initialBase, localHot)
          )
        )
      )
      connections <- Resource.eval(
        Ref.of[F, OutboundConnectionsState](OutboundConnectionsState.UntrustedState)
      )
      metrics <- Resource.eval(
        peerMetrics.fold(Ref.of[F, PeerMetrics[Addr]](PeerMetrics.empty[Addr]()))(F.pure)
      )
      judgement <- Resource.eval(Ref.of[F, LedgerStateJudgement](LedgerStateJudgement.TooOld))
      worker <- Resource.eval(createWorker(settings.roots))
      initialSnapshotCheckCanceled <- Resource.eval(Deferred[F, Unit])
      runtime <- Resource.eval(
        Ref.of[F, Runtime[F]](
          Runtime(
            BigInt(0),
            settings.roots,
            initialLocal,
            initialLocalDnsCache,
            worker,
            association,
            ChurnLoop.Config(
              base = base,
              regime = Regime.kind(settings.consensusMode, _, bootstrapOn),
              consensusMode = settings.consensusMode,
              syncTargets = settings.syncTargets,
              intervals = settings.intervals,
              timeouts = settings.timeouts,
              seed = settings.seed
            ),
            snapshotCheckStarted = false,
            snapshotCheckCanceled = initialSnapshotCheckCanceled
          )
        )
      )
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
      given Ask[F, GovernorConfig] = new Ask[F, GovernorConfig]:
        def applicative = F
        def ask[E2 >: GovernorConfig]: F[E2] =
          runtime.get.map(value => GovernorConfig(settings.policy, value.association): E2)
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
            case Output.Connections(s)    => connections.set(s)
            case Output.Counted(counters) => churnTargets.publish(counters)
            case _                        => F.unit
          ) *> emit(l)

      val rootProvider: RootProvider[F, Addr] = NodeRootProvider.dynamic(runtime, dns, dnsRng)

      def publishRootUpdate(
          staged: Runtime[F],
          local: List[RootGroupSpec[Addr]],
          public: Set[Addr]
      ): F[Unit] =
        Deferred[F, Unit].flatMap { installed =>
          F.uncancelable { _ =>
            for
              _ <- rootInstallAcks.update(_ + (staged.generation -> (staged, installed)))
              _ <- events.offer(
                Event.RootsConfigured(
                  LocalRoots.fromGroups(local),
                  staged.roots.bootstrapConfigured,
                  public,
                  Some(staged.generation)
                )
              )
            yield ()
          } *> installed.get
        }

      val judgementCell: Ref[F, LedgerStateJudgement] = judgement

      /** Start one snapshot monitor per published root generation. The result is only traced; a
        * reload that wins while the wait is suspended makes the old completion stale.
        */
      def verifySnapshot(j: LedgerStateJudgement): F[Unit] =
        if settings.consensusMode != ConsensusMode.GenesisMode ||
          j != LedgerStateJudgement.TooOld
        then F.unit
        else
          runtime.get.flatMap { captured =>
            val point = captured.roots.snapshot.snapshot.collect {
              case LedgerPeerSnapshot.V23(SnapshotPoint.Block(slot, hash), _, _) => slot -> hash
            }
            point.traverse_ { (slot, hash) =>
              runtime
                .modify { current =>
                  if current.generation != captured.generation || current.snapshotCheckStarted then
                    (current, false)
                  else (current.copy(snapshotCheckStarted = true), true)
                }
                .flatMap {
                  case false => F.unit
                  case true =>
                    supervisor
                      .supervise(
                        reportBackgroundDefect(
                          {
                            val check = immutable.fold(F.pure(false)) { store =>
                              given Raise[F, StorageError] = new Raise[F, StorageError]:
                                def functor = F
                                def raise[E2 <: StorageError, B](error: E2): F[B] =
                                  fatal.complete(GovernorFault.SnapshotStorage(error)) *> F.never[B]
                              ImmutablePeerSnapshotVerifier.matches(store, slot, hash)
                            }
                            runSnapshotCheck(
                              check,
                              captured.snapshotCheckCanceled.get,
                              captured.generation,
                              events
                            )
                          },
                          fatal,
                          GovernorFault.SnapshotVerificationDefect.apply
                        )
                      )
                      .void
                }
            }
          }
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
        given Ask[F, ChurnLoop.Config] = new Ask[F, ChurnLoop.Config]:
          def applicative = F
          def ask[E2 >: ChurnLoop.Config]: F[E2] = runtime.get.map(_.churn: E2)
        given Tell[F, Churn.Trace] = new Tell[F, Churn.Trace]:
          def functor = F
          def tell(l: Churn.Trace): F[Unit] = emit(Output.Churned(l))
        ChurnLoop.run[F](new ChurnWorld[F]:
          def counters = churnTargets.counters
          def awaitCounters(predicate: GovernorState.Counters => Boolean) =
            churnTargets.awaitCounters(predicate)
          def localHot = state.get.map { st =>
            val effective = st.localRoots.groups.map(_.hot.value).sum
            if effective > 0 then effective else st.configuredRoots.groups.map(_.hot.value).sum
          }
          def fetchMode = currentFetchMode(state, chain)
          def judgement = judgementCell.get
          def requested = churnTargets.requested
          def request(targets: Churn.Proposal) = updateTargets(_ => targets).void
          def updateTargets(change: Churn.Proposal => Churn.Proposal) =
            runtime.get.flatMap(current =>
              churnTargets
                .change(current.generation)(change)
                .flatTap(_ => events.offer(Event.ChurnTargetsChanged(current.generation)))
            ))

      val gsmLoop: F[Nothing] =
        GsmRunner.run[F](
          GsmRunner.Env[F](
            reading = gsmReading(outbound, connections, chain, genesisReadings),
            marker = marker,
            publish = j =>
              judgement.set(j) *> events.offer(Event.JudgementReported(j)) *> verifySnapshot(j),
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
        PeerGovernor.run[F, Addr](
          outbound,
          rootProvider,
          events,
          supervisor,
          actions,
          readRequestedTargets = Some(churnTargets.accepted),
          connections = Some(outbound),
          beforeEventApplied = Some {
            case Event.RootsConfigured(_, _, _, Some(generation)) =>
              rootInstallAcks.get.flatMap(_.get(generation).traverse_ { (staged, _) =>
                installRuntimeGeneration(runtime, staged).void
              }) *> judgement.get.flatMap(verifySnapshot)
            case _ => F.unit
          },
          onEventApplied = Some {
            case event @ Event.SnapshotVerificationCompleted(_, _) =>
              publishSnapshotVerification(
                event,
                runtime.get.map(_.generation),
                matches => emit(Output.LedgerPeers(LedgerPeersTrace.SnapshotVerified(matches)))
              )
            case Event.RootsConfigured(_, _, _, Some(generation)) =>
              state.get.flatMap { current =>
                if current.rootsGeneration < generation then F.unit
                else
                  rootInstallAcks
                    .modify { pending =>
                      val (installed, remaining) =
                        pending.partition(_._1 <= current.rootsGeneration)
                      (remaining, installed.values.map(_._2).toList)
                    }
                    .flatMap(_.traverse_(_.complete(()).void)) *>
                    // The owner wakes DNS even if a reload caller stopped waiting for its ack.
                    rootRefreshWake.tryOffer(()).void
              }
            case _ => F.unit
          }
        )

      val reloadRoots: Roots => F[Unit] = next =>
        for
          previous <- runtime.get
          nextWorker <- createWorker(next)
          now <- Clock[F].monotonic
          nextLocalDnsCache = LocalRootDnsCache.reconfigure(
            previous.localDnsCache,
            NodeRootProvider.localDnsEntries(next.local, next.localSrv).map(_.key),
            _.input,
            now
          )
          resolvedLocal = NodeRootProvider.localGroupsFromCache(
            next.local,
            next.localSrv,
            nextLocalDnsCache
          )
          known <- state.get
          resolvedPublic = retainedPublicRoots(previous.roots, next, known)
          nextAssociation = AssociationMode.of(
            useLedgerPeers = next.ledgerPeers,
            peerSharing = settings.peerSharing,
            useBootstrapPeers = next.bootstrapConfigured,
            bootstrapEmpty = next.bootstrap.exists(_.isEmpty) && next.bootstrapSrv.isEmpty
          )
          generation <- nextRootGeneration.modify(value => (value + 1, value + 1))
          snapshotCheckCanceled <- Deferred[F, Unit]
          staged = Runtime(
            generation,
            next,
            resolvedLocal,
            nextLocalDnsCache,
            nextWorker,
            nextAssociation,
            previous.churn.copy(
              base = settings.targets,
              regime = Regime.kind(settings.consensusMode, _, next.bootstrapConfigured)
            ),
            snapshotCheckStarted = false,
            snapshotCheckCanceled = snapshotCheckCanceled
          )
          _ <- publishRootUpdate(staged, resolvedLocal, resolvedPublic)
        yield ()

      val refreshLocalRoots: F[Nothing] =
        F.tailRecM[Unit, Nothing](()) { _ =>
          runtime.get.flatMap { captured =>
            // The reference restarts its resolvers only when the configuration it resolves has
            // changed (`localRootPeersProvider`, `check (a /= domainsGroups)`, LocalRootPeers.hs of
            // ouroboros-network 2fb69829). A wake whose generation this pass already resolves is
            // spent: restarting would cancel lookups of the installed configuration for nothing.
            F.race(
              refreshLocalDnsCache(captured.roots, captured.localDnsCache, dns, dnsRng),
              (rootRefreshWake.take *> runtime.get).iterateUntil(
                _.generation != captured.generation
              )
            ).flatMap {
              case Right(_) => F.pure(Left(()))
              case Left(cache) =>
                val resolved = NodeRootProvider.localGroupsFromCache(
                  captured.roots.local,
                  captured.roots.localSrv,
                  cache
                )
                for
                  latest <- runtime.get
                  current <-
                    if latest.generation != captured.generation then F.pure(latest)
                    else if resolved != captured.resolvedLocalRoots then
                      for
                        known <- state.get
                        public = retainedPublicRoots(captured.roots, captured.roots, known)
                        generation <- nextRootGeneration.modify { value =>
                          if value == captured.generation then (value + 1, Some(value + 1))
                          else (value, None)
                        }
                        _ <- generation.traverse_ { installedGeneration =>
                          for
                            snapshotCheckCanceled <- Deferred[F, Unit]
                            staged = captured.copy(
                              generation = installedGeneration,
                              resolvedLocalRoots = resolved,
                              localDnsCache = cache,
                              snapshotCheckStarted = false,
                              snapshotCheckCanceled = snapshotCheckCanceled
                            )
                            _ <- publishRootUpdate(staged, resolved, public)
                          yield ()
                        }
                        installed <- runtime.get
                      yield installed
                    else
                      runtime.modify { current =>
                        if current.generation == captured.generation then
                          (current.copy(localDnsCache = cache), current.copy(localDnsCache = cache))
                        else (current, current)
                      }
                  now <- Clock[F].monotonic
                  delay = LocalRootDnsCache
                    .nextDelay(current.localDnsCache, now)
                    .getOrElse(900.seconds)
                  _ <- F.race(F.sleep(delay), rootRefreshWake.take).void
                yield Left(())
            }
          }
        }

      val loops: F[Nothing] =
        F.race(governor, F.race(modes, F.race(churn, gsmLoop)))
          .flatMap(_.fold(identity, _.fold(identity, _.fold(identity, identity))))
      Running(
        state,
        outbound,
        connections,
        fetchMode,
        judgement,
        metrics,
        reloadRoots,
        supervisor
          .supervise(
            reportBackgroundDefect(
              refreshLocalRoots,
              fatal,
              GovernorFault.LocalDnsRefreshDefect.apply
            )
          )
          .void *> loops,
        fatal
      )

  /** Reload must publish configured IP roots and kept DNS answers before new lookups, as in
    * ouroboros-network 6a49b6f6, RootPeersDNS/LocalRootPeers.hs. Public DNS answers are requested
    * separately by the governor. Keep their existing attribution only while their configured source
    * set is unchanged; a removed name must not leave its old answer configured forever.
    */
  private[node] def retainedPublicRoots(
      previous: Roots,
      next: Roots,
      known: GovernorState[Addr]
  ): Set[Addr] =
    val publicUnchanged = previous.public == next.public && previous.publicSrv == next.publicSrv
    val bootstrapUnchanged =
      previous.bootstrap == next.bootstrap && previous.bootstrapSrv == next.bootstrapSrv
    val retained = known.peers.collect {
      case (peer, record)
          if (record.origin == Origin.Public && publicUnchanged) ||
            (record.origin == Origin.Bootstrap && bootstrapUnchanged) =>
        peer
    }.toSet
    retained ++ (next.public ++ next.bootstrap.toVector.flatten)
      .filter(_.host.isInstanceOf[IpAddress])

  private[node] def installRuntimeGeneration[F[_]: Async](
      runtime: Ref[F, Runtime[F]],
      staged: Runtime[F]
  ): F[Boolean] =
    runtime
      .modify { current =>
        if staged.generation > current.generation then (staged, Some(current))
        else (current, None)
      }
      .flatMap {
        case Some(previous) => previous.snapshotCheckCanceled.complete(()).as(true)
        case None           => Async[F].pure(false)
      }

  private val LocalDnsParallelism = 4

  /** Run due local DNS lookups with a fixed job bound and apply each result to its own key. */
  private[node] def refreshLocalDnsCache[F[_]: Async](
      roots: Roots,
      previous: LocalRootDnsCache.State[NodeRootProvider.LocalDnsKey, Addr],
      dns: PeerDns[F],
      rng: Ref[F, Rng]
  ): F[LocalRootDnsCache.State[NodeRootProvider.LocalDnsKey, Addr]] =
    val F = Async[F]
    for
      now <- Clock[F].monotonic
      entries = NodeRootProvider.localDnsEntries(roots.local, roots.localSrv)
      activeKeys = entries.map(_.key)
      tracked = LocalRootDnsCache.track(previous, activeKeys, now)
      requests = LocalRootDnsCache.due(tracked, activeKeys, now)
      byKey = entries.map(entry => entry.key -> entry).toMap
      completed <- requests.parTraverseN(LocalDnsParallelism) { request =>
        byKey.get(request.key).traverse { entry =>
          NodeRootProvider
            .resolveLocalDns(entry, dns, rng)
            .product(Clock[F].monotonic)
            .map { case (result, at) => (request, result, at) }
        }
      }
      updated <- completed.flatten.foldLeftM(tracked) { case (state, (request, result, at)) =>
        LocalRootDnsCache.complete(state, request, at, result) match
          case Left(LocalRootDnsCache.CacheError.StaleRequest) => F.pure(state)
          case Right(next)                                     => F.pure(next)
      }
    yield updated

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
      chain: ChainView[F],
      genesisReadings: Option[F[Vector[Gsm.PeerReading]]]
  ): F[Gsm.Reading] =
    for
      peers <- genesisReadings.getOrElse(outbound.readings)
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

  /** A failed DNS resolution, retaining its operating-system cause. */
  def unresolved(host: Host, t: java.io.IOException): PeerError =
    PeerError.Unavailable(PeerError.Unavailability.Resolution(host.toString, t))

  /** Only DNS/operating-system failures are recoverable peer failures. The caller's worker owns
    * every unexpected defect; keep its original throwable for that boundary.
    */
  private[node] def resolve[F[_]: cats.MonadThrow, A](
      host: Host,
      result: F[A]
  ): F[Either[PeerError, A]] =
    result.attempt.flatMap {
      case Right(value)                     => value.asRight[PeerError].pure[F]
      case Left(cause: java.io.IOException) => unresolved(host, cause).asLeft[A].pure[F]
      case Left(cause)                      => cats.MonadThrow[F].raiseError(cause)
    }

  /** The TCP transport: the first address a name resolves to, and a plain connection to it. */
  def tcp[F[_]: Async](connectTimeout: FiniteDuration): Transport[F, Addr] = new Transport[F, Addr]:
    def resolve(peer: Addr) = PeerNetwork.resolve(peer.host, ConnectionManager.resolve[F](peer))

    def bearer(peer: Addr, remote: SocketAddress[IpAddress])(using Raise[F, NetworkError]) =
      Bearer.tcp[F](SocketAddress[Host](remote.host, remote.port), connectTimeout)
