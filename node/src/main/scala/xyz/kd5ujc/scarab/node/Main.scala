// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.consensus
import xyz.kd5ujc.scarab.consensus.{
  BlockRules,
  Anchor,
  BlockRef,
  FollowerEvent,
  FollowerEvents,
  Scheduling,
  VolatileChain
}
import xyz.kd5ujc.scarab.ledger.Header
import xyz.kd5ujc.scarab.blst.{Blst, BlstProblem, BlstReport}
import xyz.kd5ujc.scarab.plutus.{NativeCrypto, NativeCryptoProblem, NativeCryptoReport}
import xyz.kd5ujc.scarab.ledger.{
  Block,
  ConfigError,
  ConfigProblem,
  NativeLibraryError,
  NativeLibraryProblem,
  NodeError,
  ChainError,
  StorageError
}
import cats.effect.{Deferred, ExitCode, IO, IOApp, Ref, Resource}
import cats.effect.std.{Mutex, Supervisor}
import cats.effect.metrics.CpuStarvationWarningMetrics
import xyz.kd5ujc.scarab.storage.{
  ChainPersistence,
  ChainStorage,
  ImmutableStore,
  NetworkIdentityFile,
  MemoryUtxoStore,
  RocksUtxoStore,
  ReceivedBlocks,
  SnapshotFormat,
  SnapshotCheck,
  SnapshotMigration,
  SnapshotPolicy,
  SnapshotV7,
  StateBacking,
  UtxoBacking,
  UtxoHandle,
  UtxoStore
}
import cats.mtl.{Ask, Handle, Raise, Stateful, Tell}
import xyz.kd5ujc.scarab.consensus.state.{ConsensusMirrorEnv, MirrorState, Placement}
import cats.syntax.all.*
import java.nio.file.Path
import com.comcast.ip4s.{Host, Port, SocketAddress}
import xyz.kd5ujc.scarab.protocol.{ChainSync, Era}
import xyz.kd5ujc.scarab.protocol.NtNHeader
import xyz.kd5ujc.scarab.network.{
  AcceptedConnectionsLimit,
  Word32,
  BearerServer,
  ConnectionEvents,
  ConnectionManager,
  NodeToClientServer,
  LocalTxSubmissionServer,
  NodeToNodeServer,
  NetworkError,
  DiffusionFailure,
  LocalAddress,
  LocalServer,
  LocalSockets,
  ServerHandlers,
  SocketConfigError
}
import xyz.kd5ujc.scarab.node.forge.{
  ForgeEvent,
  ForgeEvents,
  Forger,
  HotKeySource,
  KeyFiles,
  KeyGuard,
  PoolCredentials
}
import xyz.kd5ujc.scarab.node.log.{LibraryLogs, Log, LogRouter, LogSettings, NodeEvent, Severity}
import xyz.kd5ujc.scarab.node.metrics.EventSink.alongside
import xyz.kd5ujc.scarab.node.metrics.{
  Catalogue,
  Collectors,
  EventMetrics,
  MeteredRules,
  Metrics,
  PhaseLabel
}
import xyz.kd5ujc.scarab.node.mempool.{
  LocalMonitor,
  LocalSubmission,
  Mempool,
  MempoolEnv,
  MempoolLedger
}
import xyz.kd5ujc.scarab.storage.{StorageEvent, StorageEvents}
import xyz.kd5ujc.scarab.network.ConnectionEvent
import xyz.kd5ujc.scarab.node.ntc.{EraHistory, QueryEnv, StateQuerySource}
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.network.NodeToNode
import xyz.kd5ujc.scarab.network.selection.{Churn, FetchMode, PeerMetrics, Targets, UseLedgerPeers}
import xyz.kd5ujc.scarab.node.relay.{ChainServer, Persistence}
import scala.concurrent.duration.*

/** `follow (--genesis-dir <dir> | --config <config.json>) (--peer <host>:<port> [--peer ...] |
  * --topology <topology.json>) [--listen <host>:<port>] [--seconds N] [--query-listen off|P]
  * [--query-port P] [--metrics-listen <host>:<port>] [--plutus-cases <dir>] [--db <dir>]
  * [--snapshot-every <N|Nslots|Nepochs>] [--keep-snapshots N] [--snapshot-check structure|full]
  * [--mempool-bytes N] [--utxo-store memory|rocksdb] [--utxo-cache-mb N] [--memory-report-seconds
  * N] [--stall-minutes N] [--read-ahead N] [--chain-sync-low N] [--chain-sync-high N]
  * [--header-first on|off] [--max-inbound N] [--inbound-sync on|off (default off)] [--cpu-threads
  * N] [--plutus-workers N] [--dump-state <dir> [--dump-state-at
  * <epoch-boundaries|epoch-boundaries/N|slot,slot,...>] [--dump-state-full]] [--local-socket
  * <path>] [--forge --vrf-skey <file> --kes-skey <file> --opcert <file>]`
  *
  * With `--config` the node reads the Haskell node's `config.json` as `cardano-node run` does (the
  * genesis files it names, relative to it, with every hash it lists verified, the hard-fork epochs,
  * `RequiresNetworkMagic`, `PBftSignatureThreshold`, and, unless given as flags, `SocketPath`,
  * `DatabasePath` and `MempoolCapacityBytesOverride`) and with `--topology` its P2P `topology.json`
  * (the peers of the local roots and the bootstrap peers). `--genesis-dir` and `--peer` keep
  * working; with `--config` too, `--genesis-dir` is where the named genesis files are looked up.
  *
  * Follows the peers from genesis, applying the full ledger rules and Praos chain selection among
  * the peers' chains, and serves the retained ledger states on loopback for comparison against a
  * reference node. With `--db` the blocks older than `k` and periodic state snapshots are
  * persisted, and a restart resumes from the newest valid snapshot plus a replay of the stored
  * blocks instead of from genesis. With `--listen` the node is a relay: it accepts node-to-node
  * connections and serves its chain (ChainSync, BlockFetch), collects transactions into its mempool
  * (TxSubmission2) and offers the mempool to the peers it follows. With `--forge` and the pool's
  * VRF key, KES key and operational certificate it also produces blocks in the slots it leads.
  */
object Main extends IOApp:
  /** Land a backend error after the network adapter has released its protocol resources: on a
    * node-to-client connection it ends that connection only ([[ListenerFailures.reportLocal]]).
    */
  private[node] def landLocalBackend(
      result: IO[Either[StorageError, LocalTxSubmissionServer.End]]
  ): IO[InboundEnd] =
    result.map(_.fold(InboundEnd.Storage(_), InboundEnd.Local(_)))

  private[node] def synchronizeMempool(
      mempool: Mempool[IO],
      scheduling: Scheduling[IO],
      epochSlots: BigInt
  )(
      chain: VolatileChain[MirrorState],
      raws: Vector[xyz.kd5ujc.scarab.codec.Bytes]
  )(using Ask[IO, MempoolEnv], Raise[IO, NodeError]): IO[Unit] =
    for
      decoded <- scheduling.compute.within(IO.delay(raws.traverse(Block.decode(_, epochSlots))))
      blocks <- decoded.fold(error => summon[Raise[IO, NodeError]].raise(error), IO.pure)
      adopted = blocks.flatMap {
        case block: Block.Shelley => block.transactions.map(_.id)
        case _                    => Vector.empty
      }.toSet
      environment <- Ask[IO, MempoolEnv].ask
      ledger <- scheduling.compute
        .within(
          IO.delay(
            MempoolLedger
              .at(
                chain.state,
                chain.tipRef,
                environment.copy(geometry = chain.state.geometry(environment.geometry))
              )
              .leftMap(ChainError.MempoolTick(chain.tipRef.fold(BigInt(0))(_.slot + 1), _))
          )
        )
        .flatMap(Raise[IO, NodeError].fromEither(_))
      dropped <- mempool.sync(
        ledger,
        chain.tipRef.fold(BigInt(-1))(_.slot),
        adopted
      )
      _ <- Mempool
        .unverifiable(dropped, chain.tip)
        .traverse_(Raise[IO, NodeError].raise[NodeError, Unit](_))
    yield ()

  private def seedReceived(
      pool: BlockPool[IO],
      received: Option[ReceivedBlocks[IO]],
      epochSlots: BigInt
  )(using Raise[IO, NodeError], Log[IO]): IO[Unit] =
    received.traverse_(cache =>
      cache.refs.flatMap { refs =>
        refs
          .traverse { ref =>
            cache.get(ref.hash).flatMap {
              case None => IO.pure(None)
              case Some((_, raw)) =>
                ReceivedBlockRecovery.checked(ref, raw, epochSlots) match
                  case Right(delivered) => IO.pure(Some(delivered))
                  case Left(error) =>
                    Log[IO].emit(NodeEvent.ReceivedBlockDiscarded(ref.hash, error)) *>
                      cache.discardRecovered(ref.hash).as(None)
            }
          }
          .flatMap(items => pool.seedRecoveredHeaders(items.flatten))
      }
    )

  private def selectionWorker(
      follower: Follower[IO, MirrorState],
      pool: BlockPool[IO],
      work: Option[SelectionWork[IO]],
      received: Option[ReceivedBlocks[IO]],
      preverify: Peer.Prepare[IO],
      fatal: Fatal => IO[Unit],
      origin: Option[xyz.kd5ujc.scarab.codec.Bytes]
  )(using Raise[IO, NodeError]): Resource[IO, Unit] =
    given BlockSelection.Before[IO] with
      def prepare(header: Header, raw: xyz.kd5ujc.scarab.codec.Bytes)(using
          Raise[IO, NodeError]
      ): IO[Unit] =
        received.traverse_(
          _.put(
            BlockRef(header.slot, header.hash, header.blockNo, header.isBoundary),
            raw
          )
        ) *> preverify(Vector(raw))
    Resource
      .eval(work.fold(SelectionWork.create[IO](pool.fetchCoordinator.refreshGenesis))(IO.pure))
      .flatMap(shared => follower.blockSelection(pool, shared, fatal, origin))

  private def createGenesisRuntime(
      setup: NodeSetup,
      follower: Follower[IO, MirrorState],
      blockPool: BlockPool[IO],
      geometry: IO[xyz.kd5ujc.scarab.ledger.EpochGeometry]
  ): IO[Option[GenesisRuntime[IO]]] =
    for
      governor <- Option
        .when(
          setup.config.settings.consensusMode ==
            xyz.kd5ujc.scarab.network.selection.ConsensusMode.GenesisMode
        )(
          GenesisGovernor.create[IO](
            follower
              .genesisView((state, tip, slot) => GenesisHistory.window(state, tip, slot, setup)),
            GenesisGovernor.Config(
              setup.config.settings.genesis.enableLoEAndGDD,
              ((setup.config.settings.genesis.gddIntervalPicos.max(0) + 999) / 1000)
                .min(BigInt(Long.MaxValue))
                .toLong
                .nanos
            )
          )
        )
        .sequence
      runtime <- governor.traverse(g =>
        GenesisRuntime.create[IO](
          g,
          setup.config.settings.genesis,
          follower.fetchSelection,
          geometry,
          IO.realTime.map(now =>
            BigInt(now.toNanos) * 1000 -
              (BigInt(setup.geometry.systemStart.getEpochSecond) * 1000000000000L +
                BigInt(setup.geometry.systemStart.getNano) * 1000)
          ),
          blockPool.fetchCoordinator.refreshGenesis
        )
      )
      _ <- runtime.traverse_ { runtime =>
        follower.installGenesis(runtime.governor) *>
          blockPool.fetchCoordinator.installGenesis(
            FetchCoordinator.GenesisControl(
              (runtime.governor.phase, IO.monotonic, runtime.work.snapshot).mapN {
                (phase, now, starvation) =>
                  FetchCoordinator.GenesisContext(
                    phase,
                    BigInt(now.toNanos) * 1000,
                    starvation,
                    setup.config.settings.genesis.blockFetchGracePicos
                  )
              },
              runtime.demoteDynamo
            )
          )
      }
    yield runtime

  /** NodeKernel.hs:382–393 (consensus 82ecba329): the node owns the BlockFetch loop, independently
    * of how many peer sessions currently exist.
    */
  private def maintainDecisions(
      pool: BlockPool[IO],
      runtime: Option[GenesisRuntime[IO]],
      fatal: Fatal => IO[Unit]
  ): Resource[IO, Unit] =
    IO.both(pool.fetchCoordinator.run, runtime.fold(IO.never[Unit])(_.governor.run.void))
      .handleErrorWith(cause => fatal(Fatal.Defect(cause)) *> IO.never)
      .background
      .void

  /** @param metricsListen
    *   `--metrics-listen host:port`: where the Prometheus endpoint (and nothing else) listens.
    * @param snapshotEvery
    *   `--snapshot-every`: none is the reference node's policy ([[SnapshotPolicy.reference]]).
    * @param snapshotCheck
    *   `--snapshot-check structure|full`: what a snapshot write proves before it is kept.
    * @param mempoolBytes
    *   `--mempool-bytes`, else the configuration's `MempoolCapacityBytesOverride`: the override in
    *   bytes, rounded up to whole blocks. `None`: no override, two blocks of the protocol
    *   parameters in force.
    * @param configFile
    *   `--config`: the Haskell node's configuration file.
    * @param topologyFile
    *   `--topology`: the Haskell node's P2P topology file.
    * @param utxoStore
    *   `memory` or `rocksdb`; the default is rocksdb with `--db` and memory without.
    * @param memoryReportSeconds
    *   Log a `memory` event (heap, buffers, process, container, RocksDB) every N seconds; 0: off.
    * @param stallMinutes
    *   Log a `stalled` event (and count it) when no block was extended for N minutes while a peer
    *   announced a longer chain ([[StallWatchdog]]); 0: off.
    * @param stallDump
    *   `--stall-dump <dir>`: when a stall begins, also write the runtime's live-fiber snapshot to
    *   `<dir>/fiber-dump-<epoch ms>-<n>.txt` ([[StallDiagnostics.fiberDump]]); the `stallState`
    *   event is logged either way. Off by default; the devnet scenarios turn it on.
    * @param stallExitAfter
    *   End the node (exit code [[StallExitCode]], after the shutdown snapshot) when a stall has
    *   lasted this long; needs `--stall-minutes` and is longer than it. Off by default.
    * @param readAhead
    *   Maximum headers per ordered body-publication chunk; ChainSync request watermarks are
    *   separate.
    * @param cpuThreads
    *   Threads of the pool the CPU-bound rules run on (default: one per available CPU; 0: none).
    * @param dumpState
    *   `--dump-state <dir> [--dump-state-at epoch-boundaries|epoch-boundaries/N|<slot,slot,...>]`:
    *   write a ledger-state digest ([[StateDump]]) at those points, for the db-analyser comparison.
    * @param dumpStateFull
    *   `--dump-state-full`: also write the whole projection (UTxO included) next to each digest.
    * @param keyLock
    *   `--key-lock on|off`: with `--forge`, hold an exclusive lock beside the KES key file (see
    *   [[forge.KeyGuard]]).
    * @param headerFirst
    *   Validate the headers a peer announces before the blocks are fetched (G6).
    * @param maxInbound
    *   `--max-inbound`: overrides the hard limit of accepted connections (G2), the `hardLimit` of
    *   the configuration's `AcceptedConnectionsLimit` (default 512, with soft 384 and delay 5 s:
    *   see [[AcceptedConnectionsLimit]] and [[ConfigSettings]]).
    * @param inboundSync
    *   Run our own clients on inbound duplex connections, so that the peer is a source of blocks as
    *   well (G2). Off by default: the reference starts its clients only on connections its peer
    *   governor chose, up to its targets (ADR 0008).
    * @param log
    *   `--log-*`: the log's sinks, thresholds and sampling ([[LogSettings]]).
    * @param roots
    *   The roots of the peer governor: `--peer` as one local root group and the topology's groups,
    *   public roots and bootstrap peers; filled in by [[configured]].
    * @param churnInterval
    *   `--churn-interval <Ns|Nm>`, for tests: the churn governor's rounds follow each other this
    *   often and no step waits longer than it (the reference's are 3300 s and 900 s with fuzz and
    *   waits of up to 300 s). Off by default.
    */
  final case class Options(
      genesisDir: Option[Path],
      peers: List[SocketAddress[Host]],
      seconds: Option[Long],
      query: QueryListen,
      metricsListen: Option[SocketAddress[Host]] = None,
      plutusCases: Option[Path] = None,
      db: Option[Path] = None,
      snapshotEvery: Option[SnapshotInterval] = None,
      keepSnapshots: Int = SnapshotPolicy.ReferenceKeep,
      snapshotCheck: SnapshotCheck = SnapshotCheck.Structure,
      listen: Option[SocketAddress[Host]] = None,
      mempoolBytes: Option[Long] = None,
      configFile: Option[Path] = None,
      topologyFile: Option[Path] = None,
      localSocket: Option[String] = None,
      forge: Option[ForgeFiles] = None,
      utxoStore: Option[String] = None,
      utxoCacheMb: Int = 256,
      memoryReportSeconds: Int = 0,
      stallMinutes: Int = 10,
      stallExitAfter: Option[FiniteDuration] = None,
      readAhead: Int = 128,
      cpuThreads: Int = math.max(1, Runtime.getRuntime.availableProcessors()),
      dumpState: Option[(Path, StateDump.Targets)] = None,
      dumpStateFull: Boolean = false,
      headerFirst: Boolean = true,
      maxInbound: Option[Word32] = None,
      inboundSync: Boolean = false,
      log: LogSettings = LogSettings.default,
      roots: Option[PeerNetwork.Roots] = None,
      churnInterval: Option[FiniteDuration] = None,
      adoptLegacyNetworkIdentity: Boolean = false,
      plutusWorkers: ParallelPlutus.Workers = ParallelPlutus.Workers.default,
      chainSyncWindow: xyz.kd5ujc.scarab.network.ChainSyncPipelineDecision.Window =
        xyz.kd5ujc.scarab.network.ChainSyncPipelineDecision.Window.Default,
      stallDump: Option[Path] = None,
      keyLock: Boolean = true
  ):
    /** The configuration's limits, with the hard limit of `--max-inbound` where given. */
    def accepted(settings: ConfigSettings): AcceptedConnectionsLimit =
      maxInbound.fold(settings.acceptedConnectionsLimit)(hard =>
        settings.acceptedConnectionsLimit.copy(acceptedConnectionsHardLimit = hard)
      )

  /** What the chain database (or its absence) hands the node at start. */
  private final case class Storage[S](
      anchor: Option[BlockRef],
      state: S,
      resumed: Vector[ChainStorage.Resumed[S]],
      persistence: Option[ChainPersistence[IO, S]],
      immutable: Option[ImmutableStore[IO]],
      received: Option[ReceivedBlocks[IO]]
  )

  /** `--forge --vrf-skey <file> --kes-skey <file> --opcert <file>`: the pool's key files. */
  final case class ForgeFiles(vrfSkey: Path, kesSkey: Path, opCert: Path)

  /** `--snapshot-every`: a number of slots (`50000` or `50000slots`) or of epochs (`2epochs`). */
  enum SnapshotInterval:
    case Slots(n: Long)
    case Epochs(n: Long)

  object SnapshotInterval:
    def parse(text: String): Option[SnapshotInterval] =
      val digits = text.takeWhile(_.isDigit)
      val unit = text.drop(digits.length)
      digits.toLongOption.filter(_ > 0).flatMap { n =>
        unit match
          case "" | "slots"       => Some(Slots(n))
          case "epochs" | "epoch" => Some(Epochs(n))
          case _                  => None
      }

  /** The runtime's CPU starvation warning as a `Node.CpuStarvation` line in the node's log, in
    * place of the plain text cats-effect writes to stderr, which a reader of the JSON log (the
    * devnet harness among them) cannot parse.
    */
  override protected def onCpuStarvationWarn(metrics: CpuStarvationWarningMetrics): IO[Unit] =
    val event = NodeEvent.CpuStarvation(
      metrics.clockDrift,
      metrics.starvationThreshold,
      metrics.starvationInterval
    )
    IO(
      LibraryLogs.report(
        event,
        Severity.Warning,
        s"cpu starvation: clock drift ${metrics.clockDrift}"
      )
    )

  def run(args: List[String]): IO[ExitCode] =
    if args.headOption.contains("export-scls") then
      xyz.kd5ujc.scarab.node.tools.SclsCommand.runExport(args.tail)
    else if args.headOption.contains("import-scls") then
      xyz.kd5ujc.scarab.node.tools.SclsCommand.runImport(args.tail)
    else if args.headOption.contains("import-haskell-db") then
      xyz.kd5ujc.scarab.node.tools.HaskellImportCommand.run(args.tail)
    else if args.headOption.contains("import-mithril") then
      xyz.kd5ujc.scarab.node.tools.MithrilBootstrapCommand.run(args.tail)
    else if args.headOption.contains("import-ledger-snapshot") then
      xyz.kd5ujc.scarab.node.tools.LedgerSnapshotImportCommand.run(args.tail)
    else if args.headOption.contains("export-ledger-snapshot") then
      xyz.kd5ujc.scarab.node.tools.LedgerSnapshotExportCommand.run(args.tail)
    else runFollow(args)

  private def runFollow(args: List[String]): IO[ExitCode] =
    parse(args) match
      case Left(error) => IO.println(error.message).as(ExitCode(2))
      case Right(options) =>
        LogRouter.resource[IO](options.log).use { case given Log[IO] =>
          def logged(event: NodeEvent) = Log[IO].emit(event)
          Handle
            .allow[NodeError](follow(options))
            .rescue { error =>
              val (event, code) = startupFailure(error)
              logged(event) *> failedStart(error.message).as(code)
            }
        }

  /** Replay has no verdict when the evaluator is unavailable, just as live adoption has none. A
    * stored block was adopted by an earlier run, so a defect it now triggers is no reason to hold
    * it: the chain cannot be rebuilt past it, and the node stops as for a missing implementation.
    */
  private[scarab] def startupFailure(error: NodeError): (NodeEvent, ExitCode) =
    SessionEnd.of(error) match
      case SessionEnd.Unavailable(unavailable) => faulted(Fatal.Unavailable(unavailable))
      case SessionEnd.Held(unavailable)        => faulted(Fatal.Unavailable(unavailable))
      case SessionEnd.Unverifiable(defect)     => faulted(Fatal.Unverifiable(defect))
      case _                                   => (NodeEvent.Failed(error), ExitCode(1))

  private def faulted(fatal: Fatal): (NodeEvent, ExitCode) =
    (NodeEvent.Faulted(fatal), FaultExitCode(fatal))

  /** The UTxO store, and where the checkpoints that keep it in step with the snapshots live (none:
    * the store is in memory and nothing is kept). The backing itself ([[backing]]) is of the form
    * of the chain's state.
    */
  private def utxoBacking(
      options: Options
  )(using Handle[IO, NodeError]): Resource[IO, (UtxoStore[IO], Option[fs2.io.file.Path])] =
    val kind = options.utxoStore.getOrElse(if options.db.isDefined then "rocksdb" else "memory")
    (kind, options.db) match
      case ("rocksdb", Some(dir)) =>
        val root = fs2.io.file.Path.fromNioPath(dir) / "utxo"
        RocksUtxoStore
          .resource[IO](root / "live", options.utxoCacheMb.toLong * 1024 * 1024)
          .map(s => (s: UtxoStore[IO]) -> Option(root / "checkpoints"))
      case (_, db) =>
        val root =
          db.fold(fs2.io.file.Path("utxo-memory"))(d => fs2.io.file.Path.fromNioPath(d) / "utxo")
        Resource
          .eval(MemoryUtxoStore[IO])
          .map(s => (s: UtxoStore[IO]) -> db.map(_ => root / "checkpoints"))

  /** The backing of the UTxO set of the chain's state. */
  private def backing(
      store: UtxoStore[IO],
      checkpoints: Option[fs2.io.file.Path]
  )(using Handle[IO, NodeError]): StateBacking[IO, MirrorState] =
    checkpoints.fold(StateBacking.none[IO, MirrorState])(new UtxoBacking[IO, MirrorState](store, _))

  /** The node configuration and topology the options point to, and the options completed from them:
    * the peers of the topology follow the `--peer` ones, and, with `--config`, the socket, database
    * and mempool capacity the file names are the defaults of the flags.
    */
  private[node] def configured(
      options: Options
  ): Either[ConfigError, (Options, NodeSetup, Option[Topology])] =
    nodeSetup(options).flatMap(completed(options, _))

  /** The node configuration and genesis the options point to. */
  private[node] def nodeSetup(options: Options): Either[ConfigError, NodeSetup] =
    options.configFile match
      case Some(file) => NodeSetup.fromConfigFile(file, options.genesisDir)
      case None =>
        options.genesisDir
          .toRight(ConfigError(ConfigProblem.RequiredOneOf(Set("--config", "--genesis-dir"))))
          .flatMap(NodeSetup.fromDirectory)

  /** The node-to-client socket path: `--local-socket`, else, with `--config`, the file's
    * `SocketPath`, as it was written (the reference's `ncSocketPath`, not normalised).
    */
  private[node] def socketPath(options: Options, setup: NodeSetup): Option[String] =
    options.localSocket.orElse(
      Option.when(options.configFile.isDefined)(setup.config.settings.socketPath).flatten
    )

  /** The options completed from the configuration `setup` and the topology they point to. */
  private[node] def completed(
      options: Options,
      setup: NodeSetup
  ): Either[ConfigError, (Options, NodeSetup, Option[Topology])] =
    for
      loadedTopology <- topologyRoots(options, setup)
      topology = loadedTopology._1
      roots = loadedTopology._2
      settings = setup.config.settings
      fromFile = options.configFile.isDefined
      peers = (options.peers ++ topology.toList.flatMap(_.peers)).distinct
    yield (
      options.copy(
        peers = peers,
        roots = Some(roots),
        db =
          options.db.orElse(Option.when(fromFile)(settings.databasePath).flatten.map(Path.of(_))),
        localSocket = socketPath(options, setup),
        mempoolBytes = options.mempoolBytes.orElse(
          Option.when(fromFile)(settings.mempoolCapacityBytes).flatten
        )
      ),
      setup,
      topology
    )

  /** Read a complete topology and its optional snapshot before any running network state changes.
    */
  private[node] def topologyRoots(
      options: Options,
      setup: NodeSetup
  ): Either[ConfigError, (Option[Topology], PeerNetwork.Roots)] =
    for
      topology <- options.topologyFile.traverse(Topology.read)
      _ <- Either.cond(
        setup.config.settings.consensusMode !=
          xyz.kd5ujc.scarab.network.selection.ConsensusMode.GenesisMode ||
          topology.flatMap(_.bootstrapPeers).isEmpty,
        (),
        ConfigError(ConfigProblem.BootstrapPeersInGenesisMode)
      )
      // `peerSnapshotFile` is relative to the topology file, as the reference reads it
      snapshotFile = for
        file <- options.topologyFile
        name <- topology.flatMap(_.peerSnapshotFile)
      yield file.resolveSibling(name)
      snapshot <- PeerSnapshot
        .use(
          snapshotFile,
          setup.genesis.networkMagic.toLong,
          topology.fold[UseLedgerPeers](UseLedgerPeers.Disabled)(_.useLedgerAfterSlot),
          setup.config.settings.consensusMode
        )
        .leftMap(r => ConfigError(ConfigProblem.PeerSnapshot(r)))
      roots = PeerNetwork.roots(options.peers, topology, snapshot)
      _ <- Either.cond(roots.hasPeers, (), ConfigError(ConfigProblem.NoPeers))
    yield (topology, roots)

  /** The native libraries the JVM is going to load, checked before anything evaluates a script. The
    * BLS12-381 library of Leios certificates: one built for processor features this one lacks kills
    * the JVM with SIGILL, with no event (ADR 0012). The library of the Plutus builtins: without the
    * pinned build no signature builtin can be evaluated (ADR 0020). Each report is logged; a
    * refusal is a [[NativeLibraryError]].
    */
  private def nativeLibrary(using Handle[IO, NodeError], Log[IO]): IO[Unit] =
    Handle
      .allow[BlstProblem](Blst.verify[IO])
      .rescue(problem =>
        Handle[IO, NodeError]
          .raise[NodeError, BlstReport](NativeLibraryError(NativeLibraryProblem.Bls(problem)))
      )
      .flatMap(report => Log[IO].emit[NodeEvent](NodeEvent.Blst(report))) *>
      Handle
        .allow[NativeCryptoProblem](NativeCrypto.verify[IO])
        .rescue(problem =>
          Handle[IO, NodeError].raise[NodeError, NativeCryptoReport](
            NativeLibraryError(NativeLibraryProblem.PlutusCrypto(problem))
          )
        )
        .flatMap(report => Log[IO].emit[NodeEvent](NodeEvent.PlutusCrypto(report)))

  /** A start that fails says why on standard error as well as in the log, as the reference's
    * top-level handler does before it exits with 1 (cardano-node 11.1.3,
    * `cardano-node/src/Cardano/Node/Handlers/TopLevel.hs` 190-195).
    */
  private def failedStart(text: String): IO[Unit] = IO.consoleForIO.errorln(s"scarab: $text")

  private def follow(requested: Options)(using
      Handle[IO, NodeError],
      Log[IO]
  ): IO[ExitCode] =
    def admitted[A](result: Either[ConfigError, A]): IO[A] =
      result.fold(e => Handle[IO, NodeError].raise[NodeError, A](e), IO.pure)
    // the VRF key's permissions, then the credentials: before the sockets, the topology and the
    // database, as the reference's `runNode` reads them before `handleSimpleNode` (`Run.hs` 193-201)
    def credentials(files: ForgeFiles): IO[PoolCredentials] =
      IO.blocking(KeyFiles.load(files.vrfSkey, files.kesSkey, files.opCert))
        .flatMap(_.fold(e => Handle[IO, NodeError].raise[NodeError, PoolCredentials](e), IO.pure))
    // the stale socket goes once the configuration is read, before the topology and the database,
    // as the reference's `gatherConfiguredSockets` (`Run.hs` 317-324)
    def removedStale(setup: NodeSetup): IO[Either[SocketConfigError, Unit]] =
      Handle
        .allow[SocketConfigError](
          socketPath(requested, setup)
            .traverse_(LocalServer.removeStale(LocalSockets.jdk[IO], _))
            .map(_.asRight[SocketConfigError])
        )
        .rescue(error => IO.pure(error.asLeft[Unit]))
    for
      _ <- nativeLibrary
      // the configuration, then the VRF key's permissions, then the genesis and the credentials
      // (`Run.hs` 183-201)
      _ <- requested.configFile.traverse_(file =>
        IO.blocking(NodeSetup.readConfig(file)).flatMap(admitted)
      )
      _ <- requested.forge.traverse_(files =>
        KeyGuard.permissions[IO](files.vrfSkey, Log.sink[ForgeEvents, ForgeEvent, IO])
      )
      setup <- IO.blocking(nodeSetup(requested)).flatMap(admitted)
      pool <- requested.forge.traverse(credentials)
      removal <- removedStale(setup)
      code <- removal.fold(
        error =>
          Log[IO].emit[NodeEvent](NodeEvent.SocketConfigError(error)) *>
            failedStart(error.message).as(ExitCode.Error),
        _ =>
          IO.blocking(completed(requested, setup))
            .flatMap(admitted)
            .flatMap((options, setup, topology) =>
              started(options, setup, topology, requested.peers, pool)
            )
      )
    yield code

  /** The node from its completed options on: the database, the stores and the run. */
  private def started(
      options: Options,
      setup: NodeSetup,
      topology: Option[Topology],
      cliPeers: List[SocketAddress[Host]],
      credentials: Option[PoolCredentials]
  )(using Handle[IO, NodeError], Log[IO]): IO[ExitCode] =
    val ownership = options.db.fold(Resource.unit[IO])(db =>
      xyz.kd5ujc.scarab.storage.DatabaseOwnership.resource[IO](fs2.io.file.Path.fromNioPath(db))
    )
    val compatible = Resource.eval(
      options.db.traverse_(db =>
        ChainStorage
          .preflight[IO](
            fs2.io.file.Path.fromNioPath(db),
            Some(setup.identity),
            options.adoptLegacyNetworkIdentity,
            setup.genesis.geometry.byron.epochSlots
          )
          .flatMap(admission =>
            NetworkIdentityFile.admit[IO](
              fs2.io.file.Path.fromNioPath(db),
              setup.identity,
              admission
            )
          )
      )
    )
    val keys =
      options.forge.fold(Resource.unit[IO])(f => KeyGuard.held[IO](f.kesSkey, options.keyLock))
    (ownership *> keys *> compatible *> (
      utxoBacking(options),
      VerificationCache.resource[IO](),
      Scheduling.resource[IO](options.cpuThreads),
      Supervisor[IO](await = false)
    ).tupled).use { case ((store, checkpoints), cache, scheduling, supervisor) =>
      followWith(
        options,
        cliPeers,
        setup,
        topology,
        store,
        backing(store, checkpoints),
        cache,
        scheduling,
        StallDiagnostics.abandoning(supervisor),
        credentials
      )
    }

  private def followWith(
      options: Options,
      cliPeers: List[SocketAddress[Host]],
      loaded: NodeSetup,
      topology: Option[Topology],
      store: UtxoStore[IO],
      backing: StateBacking[IO, MirrorState],
      cache: VerificationCache,
      scheduling: Scheduling[IO],
      background: IO[Unit] => IO[Unit],
      credentials: Option[PoolCredentials]
  )(using
      handle: Handle[IO, NodeError],
      log: Log[IO]
  ): IO[ExitCode] =
    def fail[A](e: NodeError): IO[A] = Handle[IO, NodeError].raise(e)
    def logged(event: NodeEvent): IO[Unit] = Log[IO].emit(event)
    val setup = loaded.copy(praos = loaded.praos.copy(verdicts = cache))
    for
      genesis <- IO.pure(setup.genesis)
      blocks <- Ref.of[IO, Long](0L)
      headersLogged <- Ref.of[IO, Long](0L)
      rounds <- Ref.of[IO, (Long, Long, Long, Long, Int)]((0L, 0L, 0L, 0L, 0))
      // The rule configuration the signature pre-verification uses: a chain with a derived Byron
      // span learns its first Shelley epoch from the state.
      praosConfig <- Ref.of[IO, consensus.PraosConfig](setup.praos)
      warm = (raw: Vector[xyz.kd5ujc.scarab.codec.Bytes]) =>
        praosConfig.get.flatMap(cache.warm[IO](raw, _))
      connected <- Ref.of[IO, Boolean](false)
      eraStarts <- Ref.of[IO, Map[xyz.kd5ujc.scarab.protocol.Era, BigInt]](Map.empty)
      // The measurements: every event goes to the log and to the metrics ([[EventSink]]).
      metrics <- Metrics.create[IO](Catalogue.all)
      given Metrics[IO] = metrics
      measured <- EventMetrics.create[IO]
      // a follower that stops extending its chain while a peer has more says so (issue 130)
      // (and, from issue 371, where its report reads from once the block pool exists)
      stall <- StallDiagnostics.Watch.create[IO](options.stallDump, e => logged(e), background)
      // a session that ends in a way no peer policy handles stops the node ([[Fatal]]): the event
      // is logged where it happens, and the run ends like a signal does (the stores close, the
      // shutdown snapshot is taken) with an exit code of its own
      faulted <- Deferred[IO, Fatal]
      fatalReporter <- FatalReporter.create[IO](
        fault => logged(NodeEvent.Faulted(fault)),
        fault => faulted.complete(fault).void
      )
      fatalSink = (fault: Fatal) => fatalReporter.report(fault)
      watchdog <- StallWatchdog.create[IO](
        BigInt(0),
        options.stallMinutes.minutes,
        options.stallExitAfter,
        stall.react(measured.stall)
      )
      // What a scrape reads besides the counters the events keep: each source answers for itself
      // and is added when it exists, so the endpoint can listen before the stores are opened (a
      // collector that is not there yet leaves its gauges absent).
      collectors <- Collectors.Registry.create[IO]
      _ <- collectors.add(Collectors.jvm[IO])
      _ <- collectors.add(Collectors.scheduling(scheduling.stats))
      scrape = collectors.scrape
      exposed = options.metricsListen.fold(Resource.unit[IO])(address =>
        MetricsServer.resource[IO](address, scrape).void
      )
      followerLog = Log.follower[IO](Some(scheduling.stats))
      events = new FollowerEvents[IO]:
        def emit(event: FollowerEvent): IO[Unit] =
          (event match
            case FollowerEvent.Connected(_) => connected.set(true)
            case FollowerEvent.EraEntered(_, to, _, epoch) =>
              eraStarts.update(_.updatedWith(to)(_.orElse(Some(epoch))))
            case FollowerEvent.Extended(block, _, peerTip, _) =>
              watchdog.announced(peerTip.blockNo.value) *> watchdog.extended(block.blockNo)
            case FollowerEvent.Held(point, failure) => watchdog.held(point, failure)
            case _                                  => IO.unit
          ) *> followerLog.emit(event)
      followerEvents = events.alongside(measured.follower)
      storageEvents = Log.sink[StorageEvents, StorageEvent, IO].alongside(measured.storage)
      loggedConnections = Log.sink[ConnectionEvents, ConnectionEvent, IO]
      loggedInbound = Log.inbound[IO]
      cases = options.plutusCases.map { dir =>
        java.nio.file.Files.createDirectories(dir)
        tools.PlutusCases(dir, perKind = 2, max = 400)
      }
      dumper <- options.dumpState.traverse { (dir, targets) =>
        StateDump.Dumper.create[IO, MirrorState](
          dir,
          targets,
          Some(store),
          logged,
          options.dumpStateFull
        )
      }
      afterBlock = (header: Header, s: MirrorState) =>
        (storageError: cats.mtl.Raise[IO, xyz.kd5ujc.scarab.ledger.StorageError]) ?=>
          IO.delay(cache.noteEpochNonce(s.ledgerEpoch, s.protocol.epochNonce)) *>
            dumper.traverse_(_.afterBlock(header, s)) *>
            praosConfig
              .update(c => c.copy(geometry = s.geometry(c.geometry)))
              .whenA(s.consensus.byron.firstShelleyEpoch.isDefined) *>
            blocks.updateAndGet(_ + 1).flatMap { n =>
              logged(NodeEvent.VerificationCache(cache.summary)).whenA(n % 20000 == 0)
            }
      parallelPlutus <- ParallelPlutus.create[IO](options.plutusWorkers)
      rules = MirrorRules[IO](
        setup.praos,
        setup.globals,
        setup.byronRules,
        setup.handOver,
        setup.byronHistory,
        phase2Observer = cases.map(c => c.observe),
        utxoStore = Some(store),
        afterBlock = Some(afterBlock),
        compute = Some(scheduling.compute),
        parallelPlutus = Some(parallelPlutus)
      )
      k = setup.chainStoreK.blocks
      // The mirror's environment as the snapshots ask for it: the configuration, and the eras this
      // node has seen begin so far.
      given Ask[IO, ConsensusMirrorEnv] = setup.mirrorAsk(eraStarts.get)
      given SnapshotFormat[IO, MirrorState] = SnapshotV7.format[IO](options.snapshotCheck)
      storage = options.db.fold(
        Resource.pure[IO, Storage[MirrorState]](
          Storage(
            None,
            setup.mirrorStart,
            Vector.empty,
            Some(ChainPersistence.backingOnly[IO, MirrorState](backing)),
            None,
            None
          )
        )
      ) { dir =>
        val policy = options.snapshotEvery match
          case None                            => SnapshotPolicy.reference(genesis.geometry.k)
          case Some(SnapshotInterval.Slots(n)) => SnapshotPolicy.EverySlots(BigInt(n))
          case Some(SnapshotInterval.Epochs(n)) =>
            SnapshotPolicy.EverySlots(BigInt(n) * genesis.geometry.epochLength)
        ChainStorage
          .open[IO, MirrorState](
            fs2.io.file.Path.fromNioPath(dir),
            ChainStorage.Config(
              policy,
              options.keepSnapshots,
              byronEpochSlots = genesis.geometry.byron.epochSlots,
              identity = Some(setup.identity),
              adoptLegacyNetworkIdentity = options.adoptLegacyNetworkIdentity
            ),
            rules,
            setup.mirrorStart,
            storageEvents,
            backing,
            warm,
            Some(scheduling.compute),
            Some(SnapshotMigration.toCurrent[IO])
          )
          .evalMap { r =>
            ChainStorage
              .resume[IO, MirrorState](
                r,
                k,
                setup.origin,
                rules,
                genesis.geometry.byron.epochSlots,
                storageEvents,
                warm,
                Some(scheduling.compute)
              )
              .map(resumed =>
                Storage(
                  r.anchor,
                  r.state,
                  resumed,
                  Some(r.persistence),
                  Some(r.store),
                  Some(r.received)
                )
              )
          }
      }
      ended <- exposed.surround(storage.use {
        case Storage(anchor, state, resumed, persistence, immutable, receivedBlocks) =>
          // A chain whose Byron span is derived learns the first Shelley epoch when it enters
          // Shelley; a restored state past that point knows it, a fresh sync learns it on the way
          // (the forger still needs a restart after that, see docs/compatibility.md).
          val geometry = state.geometry(genesis.geometry)
          val globals =
            state.consensus.byron.firstShelleyEpoch.fold(setup.globals)(e =>
              setup.globals.copy(firstEpoch = e)
            )
          for
            // Older databases have only the selected-chain log. Preserve those known originals
            // without inventing downloaded fork history that the old writer never recorded.
            _ <- receivedBlocks.traverse_(received =>
              resumed.traverse_(block => received.restoreSelected(block.ref, block.raw))
            )
            receivedBody: Option[Peer.BodySink[IO]] = receivedBlocks.map {
              received => (header, raw) =>
                Handle
                  .allow[StorageError] {
                    received
                      .put(
                        BlockRef(header.slot, header.hash, header.blockNo, header.isBoundary),
                        raw
                      )
                      .as(Option.empty[StorageError])
                  }
                  .rescue(error => IO.pure(Some(error)))
                  .flatMap {
                    case None        => IO.unit
                    case Some(error) => fatalSink(Fatal.Storage(error)) *> IO.never[Unit]
                  }
            }
            // the stores are open and the stored blocks applied
            _ <- measured.phase(PhaseLabel.Following)
            _ <- praosConfig.update(c => c.copy(geometry = state.geometry(c.geometry)))
            ref <- Ref.of[IO, VolatileChain[MirrorState]](
              VolatileChain(
                k,
                Anchor(anchor, state),
                resumed.map(r => r.ref -> r.state),
                setup.origin
              )
            )
            given Stateful[IO, VolatileChain[MirrorState]] = RefStateful(ref)
            _ <- collectors.add(Collectors.chain[IO, MirrorState](_.geometry(genesis.geometry)))
            _ <- receivedBlocks.traverse_(r => collectors.add(Collectors.received(r)))
            server <- ChainServer.open[IO](
              immutable,
              anchor,
              ChainServer.headerOfBlock(genesis.geometry.byron.epochSlots),
              fatalSink
            )
            _ <- resumed.traverse_(r =>
              server
                .persistence[MirrorState]
                .extended(r.ref, r.raw, Vector.empty, Anchor(anchor, state))
            )
            mempoolEnvironment = new cats.mtl.Ask[IO, MempoolEnv]:
              val applicative = summon[cats.Applicative[IO]]
              def ask[E >: MempoolEnv]: IO[E] =
                ref.get.map { chain =>
                  val geometry = chain.state.geometry(genesis.geometry)
                  val placement = Placement(geometry, setup.byronHistory, setup.praos.forks)
                  val tip = chain.tipRef.fold(BigInt(0))(_.slot)
                  MempoolEnv(
                    globals,
                    geometry,
                    clock = Some(chain.state.clock(placement, Era.Conway, tip + 1))
                  ): E
                }
            mempoolLedger <- scheduling.compute
              .within(IO.delay {
                val tip = resumed.lastOption.map(_.ref).orElse(anchor)
                val current = resumed.lastOption.fold(state)(_.state)
                MempoolLedger
                  .at(current, tip, MempoolEnv(globals, current.geometry(genesis.geometry)))
                  .left
                  .map(ChainError.MempoolTick(tip.fold(BigInt(0))(_.slot + 1), _))
              })
              .flatMap(_.fold(fail, IO.pure))
            mempool <- Mempool.create[IO](
              mempoolLedger,
              resumed.lastOption.map(_.ref).orElse(anchor).fold(BigInt(-1))(_.slot),
              MempoolEnv(globals, geometry),
              options.mempoolBytes,
              Log.mempool[IO].alongside(measured.mempool),
              utxoStore = Some(store),
              environment = Some(mempoolEnvironment)
            )
            _ <- collectors.add(Collectors.mempool(mempool))
            served = server.persistence[MirrorState]
            chainGate <- Mutex[IO]
            follower <- Follower.create[IO, MirrorState](
              MeteredRules[IO, MirrorState](rules),
              followerEvents,
              genesis.geometry,
              NodeRules.observe(genesis.geometry),
              Some(persistence.fold(served)(Persistence.both(_, served))),
              Some(
                new Follower.OnTip[IO, MirrorState]:
                  def apply(
                      chain: VolatileChain[MirrorState],
                      added: Vector[xyz.kd5ujc.scarab.codec.Bytes]
                  )(using
                      Raise[IO, NodeError]
                  ): IO[Unit] =
                    given Ask[IO, MempoolEnv] = mempoolEnvironment
                    synchronizeMempool(mempool, scheduling, genesis.geometry.byron.epochSlots)(
                      chain,
                      added
                    )
              ),
              epochOf = Some((s, slot) => s.geometry(genesis.geometry).epochOf(slot)),
              initialViews = resumed.map(r => r.ref.hash -> r.view).toMap,
              compute = Some(scheduling.compute),
              chainGate = Some(chainGate)
            )
            given Ask[IO, consensus.ChainSelection.Local] = new Ask[
              IO,
              consensus.ChainSelection.Local
            ]:
              val applicative: cats.Applicative[IO] = cats.Applicative[IO]
              def ask[E2 >: consensus.ChainSelection.Local]: IO[E2] =
                follower.fetchSelection.widen[E2]
            given Tell[IO, xyz.kd5ujc.scarab.ledger.GenesisInternalProblem] =
              new Tell[IO, xyz.kd5ujc.scarab.ledger.GenesisInternalProblem]:
                val functor: cats.Functor[IO] = cats.Functor[IO]
                def tell(problem: xyz.kd5ujc.scarab.ledger.GenesisInternalProblem): IO[Unit] =
                  fatalSink(Fatal.Internal(problem))
            seed <- IO.delay(java.util.concurrent.ThreadLocalRandom.current().nextLong())
            sharing <- PeerSharingRuntime.create[IO](seed)
            peerSharing = setup.config.settings.peerSharingEnabled(options.forge.isDefined)
            handlers = ServerHandlers[IO, NtNHeader](
              NtNHeader.codec,
              server.source,
              server.blocks,
              mempool.sink(fatalSink),
              peerSharing = Some(sharing.source)
            )
            // header-first validation (G6): signatures are verified through the cache's keeping
            // view, so the block's own validation does not repeat them. Every live session also
            // holds its headers to the node's clock (AUD-01, #153), with or without header-first.
            headerCheck = Some(
              HeaderClock.clocked[IO](
                SlotClock.system[IO](ref.get.map(_.state.geometry(genesis.geometry))),
                if options.headerFirst || setup.config.settings.consensusMode ==
                    xyz.kd5ujc.scarab.network.selection.ConsensusMode.GenesisMode
                then
                  HeaderCheck.forNodeChain[IO, MirrorState](
                    {
                      given HeaderTimeRead[MirrorState] = HeaderTimeRead.mirror(setup.byronHistory)
                      NodeHeaderRules(
                        setup.praos.copy(verdicts = cache.forHeaders),
                        setup.globals,
                        setup.byronRules,
                        setup.handOver
                      )
                    },
                    scheduling.compute,
                    GenesisRuntime.retainedHeaders(genesis.geometry),
                    warm = Some((headers, state) =>
                      state.protocol.traverse_(protocol =>
                        praosConfig.get.flatMap(cache.warmHeaders[IO](headers, protocol, _))
                      )
                    ),
                    observe = Some(counts =>
                      headersLogged
                        .modify(last =>
                          if counts.validated - last >= 50000 then (counts.validated, true)
                          else (last, false)
                        )
                        .flatMap(
                          logged(
                            NodeEvent
                              .HeadersChecked(counts.validated, counts.unchecked, counts.waited)
                          ).whenA(_)
                        )
                    ),
                    added = Some(measured.headers),
                    changes = Some(follower.headerChanges)
                  )
                else HeaderCheck.none[IO]
              )
            )
            // how long the ChainSync pipeline's rounds take (D3), summarised every 200 rounds
            observeRounds = (requested: Int, received: Int, millis: Long) =>
              rounds
                .modify { case (n, headers, total, max, depth) =>
                  val next = (
                    n + 1,
                    headers + received,
                    total + millis,
                    max.max(millis),
                    depth.max(requested)
                  )
                  if next._1 % 200 == 0 then (next, Some(next)) else (next, None)
                }
                .flatMap(_.traverse_ { case (n, headers, total, max, depth) =>
                  logged(NodeEvent.ChainSyncRounds(n, headers, total / n, max, depth))
                })
            observePeer = (o: Peer.Observation) =>
              measured.peer(o) *> (o match
                case Peer.Observation.Fetched(millis) =>
                  logged(NodeEvent.SlowFetch(millis)).whenA(millis >= 250)
                case Peer.Observation.Round(requested, received, millis) =>
                  observeRounds(requested, received, millis)
                case Peer.Observation.Announced(tip) => watchdog.announced(tip.blockNo.value)
                case Peer.Observation.HeaderReceived | Peer.Observation.HeadersArrived(_, _) |
                    Peer.Observation.BlocksArrived(_, _) =>
                  IO.unit)
            // blocks fetched ahead, shared by the sessions of all peers: one peer fetches each block
            fetchSalt <- cats.effect.std.Random.scalaUtilRandom[IO].flatMap(_.nextLong)
            fetchPolicy = BlockPool.FetchPolicy(
              deadlineConcurrency = setup.config.settings.maxConcurrencyDeadline.getOrElse(1),
              bulkConcurrency = setup.config.settings.maxConcurrencyBulkSync.getOrElse(1),
              peerSalt = fetchSalt
            )
            blockPool <- receivedBlocks.fold(
              BlockPool.create[IO](fetchPolicy, follower.fetchSelection)
            )(received =>
              BlockPool.create[IO](
                fetchPolicy,
                follower.fetchSelection,
                BlockPool.Recovered[IO](
                  received.hashes,
                  // read back from the received log in the calling session's fiber, under a
                  // scope of its own: a failure of the file system stops the node, as one of
                  // `put` does
                  hash =>
                    Handle
                      .allow[StorageError](received.get(hash).map(found => Right(found.map(_._2))))
                      .rescue(error => IO.pure(Left(error)))
                      .flatMap {
                        case Right(found) => IO.pure(found)
                        case Left(error)  => fatalSink(Fatal.Storage(error)) *> IO.never
                      },
                  received.invalidate
                )
              )
            )
            _ <- stall.reporter.install(
              StallDiagnostics.Sources(blockPool.fetchCoordinator, blockPool.admissions)
            ) *> seedReceived(blockPool, receivedBlocks, genesis.geometry.byron.epochSlots) *>
              follower.installFetchCoordinator(blockPool.fetchCoordinator) *>
              follower.installInvalidBlocks(blockPool.invalidBlocks)
            genesisRuntime <- createGenesisRuntime(
              setup,
              follower,
              blockPool,
              ref.get.map(_.state.geometry(genesis.geometry))
            )
            genesisGovernor = genesisRuntime.map(_.governor)
            decisionMaintenance = maintainDecisions(blockPool, genesisRuntime, fatalSink)
            // the connection manager (G2): one connection per peer address, limits, accounting
            accepted = options.accepted(setup.config.settings)
            connectionEvents = loggedConnections.alongside(measured.connection)
            manager <- ConnectionManager.create[IO](
              ConnectionManager.Limits(accepted = accepted),
              Some(connectionEvents)
            )
            _ <- collectors.add(Collectors.connections(manager))
            fetchModeRef <- Ref.of[IO, FetchMode](FetchMode.BulkSync)
            peerMetrics <- Ref.of[IO, PeerMetrics[PeerNetwork.Addr]](PeerMetrics.empty())
            chainSyncHeights = new Peer.HeightSnapshot[IO]:
              def capture = ref.get.map { chain =>
                val refs = chain.anchor.block.toVector ++ chain.blocks.map(_._1)
                Peer.HeightSnapshot.Captured(
                  chain.blocks.reverse.map(_._1.point) :+ chain.anchor.point,
                  refs
                    .map(block => block.point -> Option(block.blockNo))
                    .toMap
                    .updated(ChainSync.Point.Origin, None)
                )
              }
              def unavailable[A](cause: xyz.kd5ujc.scarab.ledger.ChainSyncInternalProblem): IO[A] =
                fatalSink(Fatal.Internal(cause)) *> IO.never[A]
            // Inbound duplex sessions use the same follower and compute pool as outbound sessions.
            governor <- InboundGovernor.create[IO](
              manager,
              InboundGovernor.Config(
                genesis.networkMagic,
                NodeToNodeServer.Settings(
                  offerPeerSharing = peerSharing,
                  maxVersion = setup.config.settings.maxNtnVersion
                ),
                Peer.Settings(
                  SocketAddress(Host.fromString("0.0.0.0").get, Port.fromInt(0).get),
                  genesis.networkMagic,
                  readAhead = options.readAhead,
                  network = NodeToNode.Settings().pipelining(options.chainSyncWindow.high),
                  byronEpochSlots = genesis.geometry.byron.epochSlots,
                  pipelineWindow = options.chainSyncWindow
                ),
                runClients = options.inboundSync
              ),
              handlers,
              follow = peer => scheduling.compute.within(follower.session(peer)),
              prepare = warm,
              fatal = fatalSink,
              heightSnapshot = chainSyncHeights,
              headers = headerCheck,
              transactions = Some(mempool.source),
              events = Some(loggedInbound.alongside(measured.inbound)),
              observe = Some(observePeer),
              sharing = Some(sharing),
              pool = blockPool,
              genesis = genesisRuntime,
              receivedBody = receivedBody,
              peerMetrics = Some(peerMetrics)
            )
            // The outbound peer network (G1): the governor chooses the peers, moves them between
            // temperatures and replaces the ones that fail; the Genesis State Machine tells it
            // whether the ledger is too old.
            defaults = if options.forge.isDefined then Targets.blockProducer else Targets.relay
            targets <- setup.config.settings
              .peerTargets(defaults)
              .fold(e => fail[Targets](e), IO.pure)
            syncTargets <- setup.config.settings
              .syncTargets(Targets.sync)
              .fold(e => fail[Targets](e), IO.pure)
            lost <- Reconnects.create[IO](followerEvents)
            chainView = new PeerNetwork.ChainView[IO]:
              def tip = ref.get.map(c => c.tipRef.map(t => (t.slot, t.hash.hashCode.toLong)))
              def geometry = ref.get.map(_.state.geometry(genesis.geometry))
            marker = options.db.fold(GsmRunner.Marker.none[IO])(d =>
              GsmRunner.Marker.file[IO](d.resolve("gsm"))
            )
            hot = OutboundPeers.Hot[IO](
              magic = genesis.networkMagic,
              network = NodeToNode
                .Settings(
                  offerPeerSharing = peerSharing,
                  maxVersion = setup.config.settings.maxNtnVersion
                )
                .pipelining(options.chainSyncWindow.high),
              peer = Peer.Settings(
                SocketAddress(Host.fromString("0.0.0.0").get, Port.fromInt(0).get),
                genesis.networkMagic,
                readAhead = options.readAhead,
                network = NodeToNode.Settings().pipelining(options.chainSyncWindow.high),
                byronEpochSlots = genesis.geometry.byron.epochSlots,
                pipelineWindow = options.chainSyncWindow
              ),
              follow = (peer, track) => scheduling.compute.within(follower.session(peer, track)),
              prepare = warm,
              heightSnapshot = chainSyncHeights,
              headers = headerCheck,
              transactions = Some(mempool.source),
              observe = Some(observePeer),
              pool = blockPool,
              versus = candidate => follower.versus(candidate),
              lost = lost,
              fatal = fatalSink,
              serve = Some(handlers),
              genesis = genesisRuntime,
              receivedBody = receivedBody
            )
            emitOutput = (out: Output[PeerNetwork.Addr]) =>
              measured.peerSelection(out) *> (out match
                case Output.EstablishmentFailed(peer, error) =>
                  logged(NodeEvent.PeerEstablishmentFailed(peer, error))
                case Output.Traced(trace)      => logged(NodeEvent.PeerSelection(trace))
                case Output.Churned(trace)     => logged(NodeEvent.PeerChurn(trace))
                case Output.LedgerPeers(trace) => logged(NodeEvent.LedgerPeers(trace))
                case Output.Counted(c)         => logged(NodeEvent.PeerCounters(c))
                case Output.Connections(c)     => logged(NodeEvent.OutboundConnections(c)))
            network = PeerNetwork.resource[IO](
              PeerNetwork.Settings(
                options.roots.getOrElse(
                  PeerNetwork.Roots(Nil, Set.empty, None, UseLedgerPeers.Disabled)
                ),
                targets,
                consensusMode = setup.config.settings.consensusMode,
                syncTargets = syncTargets,
                minBigLedgerPeersForTrustedState =
                  setup.config.settings.minBigLedgerPeersForTrustedState,
                peerSharing = peerSharing,
                intervals = options.churnInterval.fold(Churn.Intervals())(Churn.Intervals.every),
                timeouts = options.churnInterval.fold(Churn.Timeouts())(Churn.Timeouts().within),
                seed = seed
              ),
              PeerNetwork.tcp[IO](10.seconds),
              manager,
              OutboundPeers.Config(),
              hot,
              chainView,
              fetchModeRef,
              emitOutput,
              (phase, event) =>
                blockPool.fetchCoordinator.transitionGenesis(
                  genesisGovernor.traverse_(_.setPhase(phase))
                ) *> measured.gsm(phase) *> logged(NodeEvent.GsmTransition(phase, event)),
              phase =>
                blockPool.fetchCoordinator.transitionGenesis(
                  genesisGovernor.traverse_(_.setPhase(phase))
                ) *> measured.gsm(phase),
              mode =>
                blockPool.fetchCoordinator.setMode(mode) *>
                  measured.fetchMode(mode) *> logged(NodeEvent.FetchModeChanged(mode)),
              marker,
              LedgerPeersSource.fromChain[IO, MirrorState](ref.get),
              sharing = Some(sharing),
              immutable = immutable,
              genesisReadings = genesisRuntime.map(_.gsmReadings(follower.versusHeaders)),
              peerMetrics = Some(peerMetrics)
            )
            relay = options.listen.fold(Resource.unit[IO])(address =>
              BearerServer
                .listen[IO, NetworkError](
                  Some(address.host),
                  Some(address.port),
                  maxConnections = None,
                  beforeAccept = Some(
                    AcceptedConnectionsLimit.rateLimit(manager, accepted, connectionEvents)
                  )
                )((remote, failure) =>
                  ListenerFailures.report(remote.toString, failure, logged, fatalSink)
                ) { (bearer, remote) =>
                  governor.serve(bearer, remote).void
                }
                .evalMap(bound => logged(NodeEvent.Listening(bound.toString)))
            )
            byronGenesis = setup.byronParams
            queryEnv = eraStarts.get.map(observed =>
              QueryEnv(
                byronGenesis
                  .flatMap(_.startTime)
                  .fold(genesis.geometry.systemStart)(java.time.Instant.ofEpochSecond),
                genesis.geometry,
                setup.praos.forks,
                byronGenesis.fold(
                  EraHistory.ByronParams(
                    genesis.geometry.k * 10,
                    BigInt(20000),
                    genesis.geometry.k
                  )
                )(b =>
                  EraHistory
                    .ByronParams(b.epochSlots, BigInt(b.slotLengthMillis), BigInt(b.securityParam))
                ),
                setup.globals.maxSupply,
                genesis.raw,
                setup.mirrorEnv(observed).ledger,
                observed,
                genesis.networkMagic.toLong
              )
            )
            // the node-to-client server; a failure to set it up, or an accept loop that stops,
            // stops the node as the reference's `DiffusionErrored` does
            local = (raise: Raise[IO, DiffusionFailure]) =>
              given Raise[IO, DiffusionFailure] = raise
              options.localSocket.fold(Resource.pure[IO, IO[DiffusionFailure]](IO.never))(path =>
                LocalServer
                  .resource[IO, NetworkError](
                    LocalSockets.jdk[IO],
                    LocalAddress(path),
                    trace => logged(NodeEvent.LocalServer(trace))
                  )((_, failure) => ListenerFailures.reportLocal(path, failure, logged)) {
                    (bearer, _) =>
                      // what would stop the node on a node-to-node connection ends this client
                      // only (the reference's local rethrow policy): the backends report it here
                      Deferred[IO, Fatal].flatMap { failed =>
                        val ends = (fault: Fatal) => failed.complete(fault).void
                        val served = landLocalBackend(
                          NodeToClientServer.acceptBackend[IO, StorageError](
                            bearer,
                            genesis.networkMagic,
                            LocalSubmission.handler(mempool)(ends),
                            Some(
                              StateQuerySource[IO, MirrorState](ref, queryEnv, store, chainGate)
                            ),
                            chainSync = Some(server.blockSource(ends)),
                            txMonitor = Some(LocalMonitor.source(mempool))
                          )
                        )
                        logged(NodeEvent.Inbound(path, InboundEvent.Accepted)) *>
                          IO.race(failed.get.map(InboundEnd.Fault(_)), served)
                            .map(_.merge)
                            .flatMap(reason =>
                              logged(NodeEvent.Inbound(path, InboundEvent.Closed(reason)))
                            )
                      }
                  }
                  .evalTap(_ => logged(NodeEvent.Listening(path)))
              )
            // the credentials were read at start-up, before the sockets ([[follow]])
            forging <- (options.forge, credentials).tupled.traverse { (files, pool) =>
              for
                source <- HotKeySource.files[IO](files.kesSkey, files.opCert)
                forger <- Forger.create[IO, MirrorState](
                  setup.praos.copy(geometry = geometry),
                  globals,
                  pool,
                  follower,
                  mempool,
                  Log.sink[ForgeEvents, ForgeEvent, IO].alongside(measured.forge),
                  connected.get,
                  utxoStore = Some(store),
                  source = Some(source)
                )
              yield forger
            }
            _ <- forging.traverse_(f => collectors.add(Collectors.forging(f.stats)))
            // The process owns the forger loop; SIGHUP is installed with the live network below.
            production = forging.fold(Resource.unit[IO])(f =>
              f.run.flatMap(_.fold(fatalSink, _ => IO.unit)).background.void
            )
            // the chain we start from is progress made: the watchdog counts from here
            _ <- ref.get.flatMap(c => c.tipRef.traverse_(t => watchdog.extended(t.blockNo)))
            watching =
              if options.stallMinutes <= 0 then Resource.unit[IO]
              else
                val period = (options.stallMinutes.minutes / 10).max(1.second).min(30.seconds)
                watchdog.run(period).background.void
            reporting =
              if options.memoryReportSeconds <= 0 then Resource.unit[IO]
              else
                val stores = Handle
                  .allow[NodeError](store.memory)
                  .rescue(_ => IO.pure(Map.empty[String, Long]))
                (IO.sleep(options.memoryReportSeconds.seconds) *>
                  MemoryReport
                    .sample(stores)
                    .flatMap(sample => logged(NodeEvent.Memory(sample)))).foreverM.background.void
            run = (raise: Raise[IO, DiffusionFailure]) =>
              decisionMaintenance
                .flatMap(_ => blockPool.maintenance)
                .flatMap(_ =>
                  selectionWorker(
                    follower,
                    blockPool,
                    genesisRuntime.map(_.work),
                    receivedBlocks,
                    warm,
                    fatalSink,
                    setup.origin
                  )
                )
                .surround(network.use { net =>
                  val reloadTopology: IO[Unit] = options.topologyFile.traverse_ { _ =>
                    // `options.peers` includes the topology loaded at startup. Keep reload roots tied
                    // to the original CLI peers so a removed topology peer cannot become permanent.
                    IO.blocking(topologyRoots(options.copy(peers = cliPeers), loaded)).flatMap {
                      case Left(error) =>
                        logged(
                          NodeEvent.Library(
                            "scarab.topology",
                            Severity.Warning,
                            s"topology reload refused: ${error.message}",
                            None
                          )
                        )
                      case Right((_, nextRoots)) => net.reloadRoots(nextRoots)
                    }
                  }
                  val reload =
                    forging.traverse_(f => f.reload.flatMap(_.fold(fatalSink, _ => IO.unit))) *>
                      reloadTopology
                  val signals =
                    if options.topologyFile.isDefined || forging.isDefined then
                      Signals.hangup[IO](reload)
                    else Resource.unit[IO]
                  // the governor ends the node only by a fault (an invariant, the bootstrap timeout)
                  val sync: IO[Unit] =
                    collectors.add(Collectors.peerMetrics(net.metrics)) *>
                      IO.race(net.run, net.fatal.get).flatMap {
                        case Left(done) => done
                        case Right(fault) =>
                          PeerNetwork.fatalForGovernor(fault) match
                            case Some(nodeFault) => fatalSink(nodeFault) *> IO.never
                            case None            => fail[Unit](fault)
                      }
                  QueryServer
                    .resource[IO, MirrorState](
                      ChainView.fromRef(ref),
                      options.query,
                      Some(store),
                      Some(scheduling.stats.total.map(Reports.schedulingTotal)),
                      Some(
                        (manager.snapshot, governor.sessions, net.state.get, IO.monotonic).mapN {
                          (connections, sessions, selection, now) =>
                            InboundGovernor
                              .json(connections, sessions)
                              .deepMerge(
                                io.circe.Json.obj(
                                  "peerSelection" -> PeerNetwork.json(selection, now)
                                )
                              )
                        }
                      ),
                      forging.map(_.stats.byEpoch.map(Reports.forgeReport)),
                      Some(scrape)
                    )(fatalSink)
                    .flatMap(_ => relay)
                    .flatMap(_ => local(raise))
                    .flatTap(_ => signals)
                    .flatTap(_ => production)
                    .flatTap(_ => reporting)
                    .flatTap(_ => watching)
                    .use(localStopped =>
                      IO.race(sync, localStopped.flatMap(raise.raise[DiffusionFailure, Unit])).void
                    )
                })
            _ <- metrics.set(
              Catalogue.buildInfo,
              (BuildInfo.version, BuildInfo.scalaVersion, BuildInfo.gitCommit),
              1.0
            )
            _ <- logged(NodeEvent.started(options.peers, genesis, setup.config, topology))
            // Praos mode with far to sync and no trusted roots says so once (ADR 0022)
            advisory <- (IO.realTimeInstant, ref.get).mapN((now, chain) =>
              SyncAdvisory.assess(
                setup.config.settings.consensusMode,
                topology,
                SyncAdvisory.position(chain.tipRef.map(_.slot), now, genesis.geometry)
              )
            )
            _ <- advisory.traverse_(logged)
            // a stall that lasts --stall-exit-after ends the run like a signal does: the stores
            // close and the shutdown snapshot is taken, then the process exits with its own code
            // SIGINT while the node runs ends the run as the reference's `UserInterrupt` does: traced
            // as `DiffusionErrored` (`Diffusion.hs` 905-911), the process then exits as interrupted
            interrupted <- Deferred[IO, Unit]
            ended <- Signals
              .interrupt[IO](interrupted.complete(()).void)
              .surround(
                IO.race(
                  Handle
                    .allow[DiffusionFailure] {
                      val running = run(summon[Raise[IO, DiffusionFailure]])
                      options.seconds
                        .fold(running)(s => running.timeoutTo(s.seconds, IO.unit))
                        .as(Option.empty[DiffusionFailure])
                    }
                    .rescue(failure => IO.pure(Some(failure))),
                  IO.race(IO.race(stall.exit.get, faulted.get), interrupted.get)
                )
              )
              .map {
                case Left(None)                  => RunEnd.Finished
                case Left(Some(failure))         => RunEnd.Errored(failure)
                case Right(Left(Left(_)))        => RunEnd.Stalled
                case Right(Left(Right(failure))) => RunEnd.Faulted(failure)
                case Right(Right(_))             => RunEnd.Interrupted
              }
            _ <- ended match
              case RunEnd.Interrupted =>
                logged(NodeEvent.DiffusionErrored(DiffusionFailure.UserInterrupt))
              case RunEnd.Errored(failure) =>
                logged(NodeEvent.DiffusionErrored(failure)) *>
                  failedStart(s"DiffusionError ${failure.shown}")
              case _ => IO.unit
            last <- ref.get
            _ <- finishedLine(ended, logged(NodeEvent.finished(last)))
          yield ended
      })
    yield exitCode(ended)

  /** The `finished` line. After a stall exit the log may be the very thing that is stuck, and the
    * exit code must not wait for its last line.
    */
  private def finishedLine(ended: RunEnd, line: IO[Unit]): IO[Unit] = ended match
    case RunEnd.Stalled => StallDiagnostics.bounded[IO](5.seconds)(line)
    case _              => line

  /** How a run ended. */
  private enum RunEnd:
    case Finished
    case Stalled
    case Faulted(fault: Fatal)

    /** The node-to-client side of the network stopped (the reference's `DiffusionErrored`): the
      * process exits with 1, as the reference's top-level handler does.
      */
    case Errored(failure: DiffusionFailure)

    /** SIGINT ended the run. */
    case Interrupted

  private def exitCode(end: RunEnd): ExitCode = end match
    case RunEnd.Finished       => ExitCode.Success
    case RunEnd.Stalled        => StallExitCode
    case RunEnd.Faulted(fault) => FaultExitCode(fault)
    case RunEnd.Errored(_)     => ExitCode.Error
    case RunEnd.Interrupted    => InterruptExitCode

  /** The exit code of a run that ended because a stall lasted `--stall-exit-after`. */
  val StallExitCode: ExitCode = ExitCode(4)

  /** A run ended by SIGINT exits as a process killed by it would (128 + 2), as the reference's
    * does: GHC re-raises the signal once `UserInterrupt` reaches its top-level handler.
    */
  val InterruptExitCode: ExitCode = ExitCode(130)

  /** The exit code of a run that a session's failure stopped: the storage failed (5), the node
    * failed itself (6), something threw that nobody expected (7), or evaluation was unavailable
    * (8). A supervisor restarts the node from its database; a storage failure is the one the
    * reference also ends the node for (`consensusRethrowPolicy`, `ShutdownNode`).
    */
  def FaultExitCode(fault: Fatal): ExitCode = fault match
    case _: Fatal.Unavailable => ExitCode(8)
    case _: Fatal.Storage     => ExitCode(5)
    case _: Fatal.Internal    => ExitCode(6)
    case _: Fatal.Defect      => ExitCode(7)
    // a library failed where a verdict is needed: the same class as an exception from a library
    case _: Fatal.Unverifiable => ExitCode(7)

  /** `30s`, `10m`, `2h`: a positive whole number of seconds, minutes or hours. */
  private[node] def parseDuration(text: String): Option[FiniteDuration] =
    val Pattern = raw"(\d+)([smh])".r
    text match
      case Pattern(n, unit) =>
        n.toLongOption.filter(_ > 0).map { count =>
          unit match
            case "s" => count.seconds
            case "m" => count.minutes
            case _   => count.hours
        }
      case _ => None

  /** The flags of the rule sets and the harness that J2.9 removed with them. A node started with
    * one is refused, not run: `--rules old` would otherwise run the era-indexed rules while the
    * operator believes it runs the old ones, and a flag that selected a rule set must never be
    * ignored in a consensus node. The refusal is a typed [[ConfigProblem.RemovedFlag]] and says
    * what to do.
    */
  private enum RemovedFlag(val flag: String, val instead: String):
    case Rules
        extends RemovedFlag(
          "rules",
          "the era-indexed rules are the only rules; drop the flag"
        )
    case MirrorCheck
        extends RemovedFlag(
          "mirror-check",
          "the chain's state is the mirror, so there is nothing to convert; `--snapshot-check full` reads each snapshot back and compares it"
        )
    case OnDivergence
        extends RemovedFlag("on-divergence", "the differential rules are gone; drop the flag")
    case ReproDir extends RemovedFlag("repro-dir", "the differential rules are gone; drop the flag")

    def refusal(usage: String): ConfigError =
      ConfigError(ConfigProblem.WithUsage(ConfigProblem.RemovedFlag(flag), s"$instead\n$usage"))

  private object RemovedFlag:
    def named(argument: String): Option[RemovedFlag] =
      values.find(r => argument == s"--${r.flag}")

  private val Usage =
    "usage: follow (--genesis-dir <dir> | --config <config.json>) (--peer <host>:<port> [--peer ...] | --topology <topology.json>) [--listen <host>:<port>] [--seconds N] [--query-listen off|P] [--query-port P] [--metrics-listen <host>:<port>] [--plutus-cases <dir>] [--db <dir>] [--adopt-legacy-network-identity] [--snapshot-every <N|Nslots|Nepochs>] [--keep-snapshots N] [--snapshot-check structure|full] [--mempool-bytes N] [--utxo-store memory|rocksdb] [--utxo-cache-mb N] [--memory-report-seconds N] [--stall-minutes N] [--stall-exit-after <Ns|Nm|Nh>] [--stall-dump <dir>] [--churn-interval <Ns|Nm>] [--read-ahead N] [--chain-sync-low N] [--chain-sync-high N] [--header-first on|off] [--max-inbound N] [--inbound-sync on|off] [--cpu-threads N] [--plutus-workers N] [--dump-state <dir> [--dump-state-at <epoch-boundaries|epoch-boundaries/N|slot,slot,...>] [--dump-state-full]] [--local-socket <path>] [--forge --vrf-skey <file> --kes-skey <file> --opcert <file> [--key-lock on|off]] " + LogSettings.Usage

  /** Flags that take a value and may repeat. */
  private val RepeatedFlags = Set("peer") ++ LogSettings.RepeatedFlags

  /** Flags taking a value, other than the repeated ones. */
  private val ValueFlags = LogSettings.ValueFlags ++ Set(
    "genesis-dir",
    "config",
    "topology",
    "seconds",
    "query-port",
    "query-listen",
    "metrics-listen",
    "plutus-cases",
    "db",
    "snapshot-every",
    "keep-snapshots",
    "snapshot-check",
    "listen",
    "mempool-bytes",
    "utxo-store",
    "utxo-cache-mb",
    "memory-report-seconds",
    "stall-minutes",
    "stall-exit-after",
    "stall-dump",
    "churn-interval",
    "read-ahead",
    "chain-sync-low",
    "chain-sync-high",
    "header-first",
    "max-inbound",
    "inbound-sync",
    "cpu-threads",
    "plutus-workers",
    "dump-state",
    "dump-state-at",
    "local-socket",
    "vrf-skey",
    "kes-skey",
    "opcert",
    "key-lock"
  )

  private[node] def parse(args: List[String]): Either[ConfigError, Options] =
    def bad[A](problem: ConfigProblem): Either[ConfigError, A] =
      Left(ConfigError(ConfigProblem.WithUsage(problem, Usage)))
    val usage = ConfigError(ConfigProblem.WithUsage(ConfigProblem.Usage("follow"), Usage))
    @annotation.tailrec
    def go(
        rest: List[String],
        acc: Map[String, List[String]]
    ): Either[ConfigError, Map[String, List[String]]] =
      rest match
        case Nil => Right(acc)
        case "--forge" :: tail =>
          if acc.contains("forge") then bad(ConfigProblem.DuplicateFlag("--forge"))
          else go(tail, acc.updated("forge", List("true")))
        case flag :: _ if RemovedFlag.named(flag).isDefined =>
          Left(RemovedFlag.named(flag).fold(usage)(_.refusal(Usage)))
        case "--dump-state-full" :: tail =>
          if acc.contains("dump-state-full") then
            bad(ConfigProblem.DuplicateFlag("--dump-state-full"))
          else go(tail, acc.updated("dump-state-full", List("true")))
        case "--adopt-legacy-network-identity" :: tail =>
          if acc.contains("adopt-legacy-network-identity") then
            bad(ConfigProblem.DuplicateFlag("--adopt-legacy-network-identity"))
          else go(tail, acc.updated("adopt-legacy-network-identity", List("true")))
        case flag :: value :: tail if RepeatedFlags(flag.drop(2)) && flag.startsWith("--") =>
          val name = flag.drop(2)
          go(tail, acc.updated(name, acc.getOrElse(name, Nil) :+ value))
        case flag :: value :: tail if flag.startsWith("--") =>
          val name = flag.drop(2)
          if !ValueFlags.contains(name) then bad(ConfigProblem.UnknownFlag(flag))
          else if acc.contains(name) then bad(ConfigProblem.DuplicateFlag(flag))
          else go(tail, acc.updated(name, List(value)))
        case flag :: Nil if flag.startsWith("--") => bad(ConfigProblem.MissingFlagValue(flag))
        case other :: _                           => bad(ConfigProblem.UnexpectedArgument(other))
    args match
      case "follow" :: rest =>
        for
          multi <- go(rest, Map.empty)
          flags = multi.view.mapValues(_.last).toMap
          invalid = (flag: String) =>
            ConfigError(
              ConfigProblem.WithUsage(
                ConfigProblem.FlagValue(flag, flags.getOrElse(flag, "")),
                Usage
              )
            )
          dir = flags.get("genesis-dir").map(Path.of(_))
          config = flags.get("config").map(Path.of(_))
          topology = flags.get("topology").map(Path.of(_))
          _ <- Either.cond(
            dir.isDefined || config.isDefined,
            (),
            ConfigError(ConfigProblem.RequiredOneOf(Set("--genesis-dir", "--config")))
          )
          _ <- Either.cond(
            !flags.contains("adopt-legacy-network-identity") || flags.contains("db"),
            (),
            ConfigError(
              ConfigProblem.WithUsage(
                ConfigProblem.FlagDependency(Set("--adopt-legacy-network-identity"), "--db"),
                Usage
              )
            )
          )
          peerTexts = multi.getOrElse("peer", Nil)
          _ <- Either.cond(
            peerTexts.nonEmpty || topology.isDefined,
            (),
            ConfigError(ConfigProblem.RequiredOneOf(Set("--peer", "--topology")))
          )
          peers <- peerTexts.traverse(t =>
            Peer.parseAddress(t).leftMap(e => ConfigError(ConfigProblem.PeerAddress(e)))
          )
          seconds <- flags.get("seconds").traverse(_.toLongOption.toRight(invalid("seconds")))
          query <- (flags.get("query-listen"), flags.get("query-port")) match
            case (Some(_), Some(_)) => Left(invalid("query-listen"))
            case (Some(t), None)    => QueryListen.parse(t).toRight(invalid("query-listen"))
            case (None, Some(t)) =>
              Port.fromString(t).map(QueryListen.Loopback(_)).toRight(invalid("query-port"))
            case (None, None) => Right(QueryListen.Loopback(QueryListen.DefaultPort))
          metricsListen <- flags
            .get("metrics-listen")
            .traverse(t =>
              Peer.parseAddress(t).leftMap(e => ConfigError(ConfigProblem.PeerAddress(e)))
            )
          interval <- flags
            .get("snapshot-every")
            .traverse(t => SnapshotInterval.parse(t).toRight(invalid("snapshot-every")))
          keep <- flags
            .get("keep-snapshots")
            .fold(Right(SnapshotPolicy.ReferenceKeep))(
              _.toIntOption.filter(_ > 0).toRight(invalid("keep-snapshots"))
            )
          check <- flags
            .get("snapshot-check")
            .fold(Right(SnapshotCheck.Structure))(t =>
              SnapshotCheck.parse(t).toRight(invalid("snapshot-check"))
            )
          listen <- flags
            .get("listen")
            .traverse(t =>
              Peer.parseAddress(t).leftMap(e => ConfigError(ConfigProblem.PeerAddress(e)))
            )
          localSocket = flags.get("local-socket")
          mempool <- flags
            .get("mempool-bytes")
            .traverse(_.toLongOption.filter(_ > 0).toRight(invalid("mempool-bytes")))
          forge <-
            if !flags.contains("forge") then Right(None)
            else
              (
                flags.get("vrf-skey").toRight(invalid("vrf-skey")),
                flags.get("kes-skey").toRight(invalid("kes-skey")),
                flags.get("opcert").toRight(invalid("opcert"))
              ).mapN((v, k, o) => Some(ForgeFiles(Path.of(v), Path.of(k), Path.of(o))))
          utxoKind <- flags
            .get("utxo-store")
            .traverse(k => Either.cond(Set("memory", "rocksdb")(k), k, invalid("utxo-store")))
          cacheMb <- flags
            .get("utxo-cache-mb")
            .fold(Right(256))(_.toIntOption.filter(_ > 0).toRight(invalid("utxo-cache-mb")))
          memoryReport <- flags
            .get("memory-report-seconds")
            .fold(Right(0))(_.toIntOption.filter(_ >= 0).toRight(invalid("memory-report-seconds")))
          stallMinutes <- flags
            .get("stall-minutes")
            .fold(Right(10))(_.toIntOption.filter(_ >= 0).toRight(invalid("stall-minutes")))
          stallExit <- flags
            .get("stall-exit-after")
            .traverse(v =>
              parseDuration(v)
                .filter(d => stallMinutes > 0 && d > stallMinutes.minutes)
                .toRight(invalid("stall-exit-after"))
            )
          churnInterval <- flags
            .get("churn-interval")
            .traverse(v =>
              parseDuration(v).filter(_ > Duration.Zero).toRight(invalid("churn-interval"))
            )
          readAhead <- flags
            .get("read-ahead")
            .fold(Right(128))(_.toIntOption.filter(_ > 0).toRight(invalid("read-ahead")))
          chainSyncLow <- flags
            .get("chain-sync-low")
            .fold(Right(200))(_.toIntOption.toRight(invalid("chain-sync-low")))
          chainSyncHigh <- flags
            .get("chain-sync-high")
            .fold(Right(300))(_.toIntOption.filter(_ > 0).toRight(invalid("chain-sync-high")))
          chainSyncWindow <- xyz.kd5ujc.scarab.network.ChainSyncPipelineDecision.Window
            .checked(chainSyncLow, chainSyncHigh)
            .leftMap(_ => invalid("chain-sync-low/chain-sync-high"))
          headerFirst <- flags
            .get("header-first")
            .fold(Right(true))(v =>
              Map("on" -> true, "off" -> false).get(v).toRight(invalid("header-first"))
            )
          keyLock <- flags
            .get("key-lock")
            .fold(Right(true))(v =>
              Map("on" -> true, "off" -> false).get(v).toRight(invalid("key-lock"))
            )
          maxInbound <- flags
            .get("max-inbound")
            .traverse(
              _.toLongOption
                .flatMap(Word32.fromLong)
                .filter(_.toLong > 0)
                .toRight(invalid("max-inbound"))
            )
          inboundSync <- flags
            .get("inbound-sync")
            .fold(Right(false))(v =>
              Map("on" -> true, "off" -> false).get(v).toRight(invalid("inbound-sync"))
            )
          cpuThreads <- flags
            .get("cpu-threads")
            .fold(Right(math.max(1, Runtime.getRuntime.availableProcessors())))(
              _.toIntOption.filter(_ >= 0).toRight(invalid("cpu-threads"))
            )
          plutusWorkers <- flags
            .get("plutus-workers")
            .fold(Right(ParallelPlutus.Workers.default))(
              _.toIntOption
                .flatMap(n => ParallelPlutus.Workers.from(n).toOption)
                .toRight(invalid("plutus-workers"))
            )
          dump <- flags.get("dump-state").traverse { d =>
            flags
              .get("dump-state-at")
              .fold(Right(StateDump.Targets.EpochBoundaries(1)))(t =>
                StateDump.Targets.parse(t).toRight(invalid("dump-state-at"))
              )
              .map(Path.of(d) -> _)
          }
          _ <- Either.cond(
            dump.isDefined || !flags.contains("dump-state-at"),
            (),
            invalid("dump-state-at")
          )
          log <- LogSettings
            .parse(flags, multi)
            .leftMap(e => ConfigError(ConfigProblem.WithUsage(e.problem, Usage)))
        yield Options(
          dir,
          peers,
          seconds,
          query,
          metricsListen,
          flags.get("plutus-cases").map(Path.of(_)),
          flags.get("db").map(Path.of(_)),
          interval,
          keep,
          check,
          listen,
          mempool,
          config,
          topology,
          localSocket,
          forge,
          utxoKind,
          cacheMb,
          memoryReport,
          stallMinutes,
          stallExit,
          readAhead,
          cpuThreads,
          dump,
          flags.contains("dump-state-full"),
          headerFirst,
          maxInbound,
          inboundSync,
          log
        ).copy(
          churnInterval = churnInterval,
          stallDump = flags.get("stall-dump").map(Path.of(_)),
          plutusWorkers = plutusWorkers,
          chainSyncWindow = chainSyncWindow,
          adoptLegacyNetworkIdentity = flags.contains("adopt-legacy-network-identity"),
          keyLock = keyLock
        )
      case _ => Left(usage)
