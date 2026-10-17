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
import xyz.kd5ujc.scarab.ledger.{Block, ConfigError, ConfigProblem, NativeLibraryError, NodeError}
import cats.effect.{Deferred, ExitCode, IO, IOApp, Ref, Resource}
import cats.effect.metrics.CpuStarvationWarningMetrics
import xyz.kd5ujc.scarab.storage.{
  ChainPersistence,
  ChainStorage,
  ImmutableStore,
  MemoryUtxoStore,
  RocksUtxoStore,
  SnapshotFormat,
  SnapshotCheck,
  SnapshotMigration,
  SnapshotPolicy,
  SnapshotV5,
  StateBacking,
  UtxoBacking,
  UtxoHandle,
  UtxoStore
}
import cats.mtl.{Ask, Handle, Raise, Stateful}
import xyz.kd5ujc.scarab.consensus.state.{ConsensusMirrorEnv, MirrorState, ProtocolState}
import cats.syntax.all.*
import java.nio.file.Path
import com.comcast.ip4s.{Host, Port, SocketAddress}
import xyz.kd5ujc.scarab.protocol.NtNHeader
import xyz.kd5ujc.scarab.network.{
  BearerServer,
  ConnectionEvents,
  ConnectionManager,
  NodeToClientServer,
  NodeToNodeServer,
  NetworkError,
  ServerHandlers
}
import xyz.kd5ujc.scarab.node.forge.{ForgeEvent, ForgeEvents, Forger, HotKeySource, KeyFiles}
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
import xyz.kd5ujc.scarab.network.selection.{Churn, FetchMode, Targets}
import xyz.kd5ujc.scarab.node.relay.{ChainServer, Persistence}
import scala.concurrent.duration.*

/** `follow (--genesis-dir <dir> | --config <config.json>) (--peer <host>:<port> [--peer ...] |
  * --topology <topology.json>) [--listen <host>:<port>] [--seconds N] [--query-port P]
  * [--metrics-listen <host>:<port>] [--plutus-cases <dir>] [--db <dir>] [--snapshot-every
  * <N|Nslots|Nepochs>] [--keep-snapshots N] [--snapshot-check structure|full] [--mempool-bytes N]
  * [--utxo-store memory|rocksdb] [--utxo-cache-mb N] [--memory-report-seconds N] [--stall-minutes
  * N] [--read-ahead N] [--header-first on|off] [--max-inbound N] [--inbound-sync on|off]
  * [--cpu-threads N] [--dump-state <dir> [--dump-state-at
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
  final case class Options(
      genesisDir: Option[Path],
      peers: List[SocketAddress[Host]],
      seconds: Option[Long],
      queryPort: Int,
      /** `--metrics-listen host:port`: where the Prometheus endpoint (and nothing else) listens. */
      metricsListen: Option[SocketAddress[Host]] = None,
      plutusCases: Option[Path] = None,
      db: Option[Path] = None,
      /** `--snapshot-every`: none is the reference node's policy ([[SnapshotPolicy.reference]]). */
      snapshotEvery: Option[SnapshotInterval] = None,
      keepSnapshots: Int = SnapshotPolicy.ReferenceKeep,
      /** `--snapshot-check structure|full`: what a snapshot write proves before it is kept. */
      snapshotCheck: SnapshotCheck = SnapshotCheck.Structure,
      listen: Option[SocketAddress[Host]] = None,
      /** `None`: the configuration's `MempoolCapacityBytesOverride`, else 1 MiB. */
      mempoolBytes: Option[Long] = None,
      /** `--config`: the Haskell node's configuration file. */
      configFile: Option[Path] = None,
      /** `--topology`: the Haskell node's P2P topology file. */
      topologyFile: Option[Path] = None,
      localSocket: Option[Path] = None,
      forge: Option[ForgeFiles] = None,
      /** `memory` or `rocksdb`; the default is rocksdb with `--db` and memory without. */
      utxoStore: Option[String] = None,
      utxoCacheMb: Int = 256,
      /** Log a `memory` event (heap, buffers, process, container, RocksDB) every N seconds; 0: off.
        */
      memoryReportSeconds: Int = 0,
      /** Log a `stalled` event (and count it) when no block was extended for N minutes while a peer
        * announced a longer chain ([[StallWatchdog]]); 0: off.
        */
      stallMinutes: Int = 10,
      /** End the node (exit code [[StallExitCode]], after the shutdown snapshot) when a stall has
        * lasted this long; needs `--stall-minutes` and is longer than it. Off by default.
        */
      stallExitAfter: Option[FiniteDuration] = None,
      /** Most ChainSync requests pipelined (and blocks fetched in one range) while far behind. */
      readAhead: Int = 128,
      /** Threads of the pool the CPU-bound rules run on (default: one per available CPU; 0: none).
        */
      cpuThreads: Int = math.max(1, Runtime.getRuntime.availableProcessors()),
      /** `--dump-state <dir> [--dump-state-at epoch-boundaries|epoch-boundaries/N|<slot,slot,...>]`:
        * write a ledger-state digest ([[StateDump]]) at those points, for the db-analyser
        * comparison.
        */
      dumpState: Option[(Path, StateDump.Targets)] = None,
      /** `--dump-state-full`: also write the whole projection (UTxO included) next to each digest.
        */
      dumpStateFull: Boolean = false,
      /** Validate the headers a peer announces before the blocks are fetched (G6). */
      headerFirst: Boolean = true,
      /** The most inbound connections accepted at once (G2). */
      maxInbound: Int = 64,
      /** Run our own clients on inbound duplex connections, so that the peer is a source of blocks
        * as well (G2).
        */
      inboundSync: Boolean = true,
      /** `--log-*`: the log's sinks, thresholds and sampling ([[LogSettings]]). */
      log: LogSettings = LogSettings.default,
      /** The roots of the peer governor: `--peer` as one local root group and the topology's
        * groups, public roots and bootstrap peers; filled in by [[configured]].
        */
      roots: Option[PeerNetwork.Roots] = None,
      /** `--churn-interval <Ns|Nm>`, for tests: the churn governor's rounds follow each other this
        * often and no step waits longer than it (the reference's are 3300 s and 900 s with fuzz and
        * waits of up to 300 s). Off by default.
        */
      churnInterval: Option[FiniteDuration] = None
  )

  val DefaultMempoolBytes: Long = 1048576L

  /** What the chain database (or its absence) hands the node at start. */
  private final case class Storage[S](
      anchor: Option[BlockRef],
      state: S,
      resumed: Vector[ChainStorage.Resumed[S]],
      persistence: Option[ChainPersistence[IO, S]],
      immutable: Option[ImmutableStore[IO]]
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
    if args.headOption.contains("import-haskell-db") then
      xyz.kd5ujc.scarab.node.tools.HaskellImportCommand.run(args.tail)
    else runFollow(args)

  private def runFollow(args: List[String]): IO[ExitCode] =
    parse(args) match
      case Left(error) => IO.println(error.message).as(ExitCode(2))
      case Right(options) =>
        LogRouter.resource[IO](options.log).use { case given Log[IO] =>
          def logged(event: NodeEvent) = Log[IO].emit(event)
          Handle
            .allow[NodeError](follow(options))
            .rescue(error => logged(NodeEvent.Failed(error)).as(ExitCode(1)))
        }

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
    for
      setup <- options.configFile match
        case Some(file) => NodeSetup.fromConfigFile(file, options.genesisDir)
        case None =>
          options.genesisDir
            .toRight(ConfigError("neither --config nor --genesis-dir given"))
            .flatMap(NodeSetup.fromDirectory)
      topology <- options.topologyFile.traverse(Topology.read)
      settings = setup.config.settings
      fromFile = options.configFile.isDefined
      peers = (options.peers ++ topology.toList.flatMap(_.peers)).distinct
      roots = PeerNetwork.roots(options.peers, topology)
      _ <- Either.cond(
        roots.hasPeers,
        (),
        ConfigError("no peer to follow: the topology names no local root, bootstrap or public root")
      )
    yield (
      options.copy(
        peers = peers,
        roots = Some(roots),
        db =
          options.db.orElse(Option.when(fromFile)(settings.databasePath).flatten.map(Path.of(_))),
        localSocket = options.localSocket.orElse(
          Option.when(fromFile)(settings.socketPath).flatten.map(Path.of(_))
        ),
        mempoolBytes = options.mempoolBytes.orElse(
          Option.when(fromFile)(settings.mempoolCapacityBytes).flatten
        )
      ),
      setup,
      topology
    )

  /** The BLS12-381 library the JVM is going to load, checked before anything evaluates a script: a
    * library built for processor features this one lacks kills the JVM with SIGILL, with no event
    * (ADR 0012). The report is logged; a refusal is a [[NativeLibraryError]].
    */
  private def nativeLibrary(using Handle[IO, NodeError], Log[IO]): IO[Unit] =
    Handle
      .allow[BlstProblem](Blst.verify[IO])
      .rescue(problem =>
        Handle[IO, NodeError].raise[NodeError, BlstReport](NativeLibraryError(problem))
      )
      .flatMap(report => Log[IO].emit[NodeEvent](NodeEvent.Blst(report)))

  private def follow(requested: Options)(using
      Handle[IO, NodeError],
      Log[IO]
  ): IO[ExitCode] =
    nativeLibrary *> IO
      .blocking(configured(requested))
      .flatMap(
        _.fold(
          e => Handle[IO, NodeError].raise[NodeError, (Options, NodeSetup, Option[Topology])](e),
          IO.pure
        )
      )
      .flatMap { (options, setup, topology) =>
        (
          utxoBacking(options),
          VerificationCache.resource[IO](),
          Scheduling.resource[IO](options.cpuThreads)
        ).tupled.use { case ((store, checkpoints), cache, scheduling) =>
          followWith(
            options,
            setup,
            topology,
            store,
            backing(store, checkpoints),
            cache,
            scheduling
          )
        }
      }

  private def followWith(
      options: Options,
      loaded: NodeSetup,
      topology: Option[Topology],
      store: UtxoStore[IO],
      backing: StateBacking[IO, MirrorState],
      cache: VerificationCache,
      scheduling: Scheduling[IO]
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
      stallExit <- Deferred[IO, Unit]
      watchdog <- StallWatchdog.create[IO](
        BigInt(0),
        options.stallMinutes.minutes,
        options.stallExitAfter,
        change =>
          measured.stall(change) *> (change match
            case StallWatchdog.Change.Began(idle, ours, peers) =>
              logged(NodeEvent.Stalled(idle, ours, peers))
            case StallWatchdog.Change.Ended => IO.unit
            case StallWatchdog.Change.ExitDue(idle, ours, peers) =>
              logged(NodeEvent.StallExit(idle, ours, peers)) *> stallExit.complete(()).void)
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
            case _ => IO.unit
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
        IO.delay(cache.noteEpochNonce(s.ledgerEpoch, s.protocol.epochNonce)) *>
          dumper.traverse_(_.afterBlock(header, s)) *>
          praosConfig
            .update(c => c.copy(geometry = s.geometry(c.geometry)))
            .whenA(s.consensus.byron.firstShelleyEpoch.isDefined) *>
          blocks.updateAndGet(_ + 1).flatMap { n =>
            logged(NodeEvent.VerificationCache(cache.summary)).whenA(n % 20000 == 0)
          }
      rules = MirrorRules[IO](
        setup.praos,
        setup.globals,
        setup.byronRules,
        setup.handOver,
        setup.byronHistory,
        phase2Observer = cases.map(c => c.observe),
        utxoStore = Some(store),
        afterBlock = Some(afterBlock),
        compute = Some(scheduling.compute)
      )
      k = genesis.geometry.securityParam
      // The mirror's environment as the snapshots ask for it: the configuration, and the eras this
      // node has seen begin so far.
      given Ask[IO, ConsensusMirrorEnv] = setup.mirrorAsk(eraStarts.get)
      given SnapshotFormat[IO, MirrorState] = SnapshotV5.format[IO](options.snapshotCheck)
      storage = options.db.fold(
        Resource.pure[IO, Storage[MirrorState]](
          Storage(
            None,
            setup.mirrorStart,
            Vector.empty,
            Some(ChainPersistence.backingOnly[IO, MirrorState](backing)),
            None
          )
        )
      ) { dir =>
        val policy = options.snapshotEvery match
          case None => SnapshotPolicy.reference(BigInt(genesis.geometry.securityParam))
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
              identity = Some(setup.identity)
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
                Storage(r.anchor, r.state, resumed, Some(r.persistence), Some(r.store))
              )
          }
      }
      stalled <- exposed.surround(storage.use {
        case Storage(anchor, state, resumed, persistence, immutable) =>
          // A chain whose Byron span is derived learns the first Shelley epoch when it enters
          // Shelley; a restored state past that point knows it, a fresh sync learns it on the way
          // (the mempool and the forger need a restart after that, see docs/compatibility.md).
          val geometry = state.geometry(genesis.geometry)
          val globals =
            state.consensus.byron.firstShelleyEpoch.fold(setup.globals)(e =>
              setup.globals.copy(firstEpoch = e)
            )
          for
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
            server <- ChainServer.open[IO](
              immutable,
              anchor,
              ChainServer.headerOfBlock(genesis.geometry.byron.epochSlots)
            )
            _ <- resumed.traverse_(r =>
              server
                .persistence[MirrorState]
                .extended(r.ref, r.raw, Vector.empty, Anchor(anchor, state))
            )
            mempool <- Mempool.create[IO](
              MempoolLedger.at(
                resumed.lastOption.fold(state)(_.state),
                resumed.lastOption.map(_.ref).orElse(anchor)
              ),
              resumed.lastOption.map(_.ref).orElse(anchor).fold(BigInt(0))(_.slot),
              MempoolEnv(globals, geometry),
              options.mempoolBytes.getOrElse(DefaultMempoolBytes),
              Log.mempool[IO].alongside(measured.mempool),
              utxoStore = Some(store)
            )
            _ <- collectors.add(Collectors.mempool(mempool))
            served = server.persistence[MirrorState]
            follower <- Follower.create[IO, MirrorState](
              MeteredRules[IO, MirrorState](rules),
              followerEvents,
              genesis.geometry,
              NodeRules.observe(genesis.geometry),
              Some(persistence.fold(served)(Persistence.both(_, served))),
              Some((chain, added) =>
                // decoding the added blocks is CPU-bound: off the compute pool like the rules
                scheduling.compute
                  .within(IO.delay {
                    added.flatMap { raw =>
                      Block
                        .decode(raw, genesis.geometry.byron.epochSlots)
                        .toOption
                        .toVector
                        .flatMap {
                          case b: Block.Shelley => b.transactions.map(_.id)
                          case _                => Vector.empty
                        }
                    }.toSet
                  })
                  .flatMap(adopted =>
                    chain.tipRef
                      .traverse_(t =>
                        mempool
                          .sync(MempoolLedger.at(chain.state, chain.tipRef), t.slot, adopted)
                          .void
                      )
                  )
              ),
              epochOf = Some((s, slot) => s.geometry(genesis.geometry).epochOf(slot)),
              initialViews = resumed.map(r => r.ref.hash -> r.view).toMap,
              compute = Some(scheduling.compute)
            )
            handlers = ServerHandlers[IO, NtNHeader](
              NtNHeader.codec,
              server.source,
              server.blocks,
              mempool.sink
            )
            // header-first validation (G6): signatures are verified through the cache's keeping
            // view, so the block's own validation does not repeat them. Every live session also
            // holds its headers to the node's clock (AUD-01, #153), with or without header-first.
            headerCheck = Some(
              HeaderClock.clocked[IO](
                SlotClock.system[IO](ref.get.map(_.state.geometry(genesis.geometry))),
                if options.headerFirst then
                  HeaderCheck.forChain[IO, MirrorState, ProtocolState](
                    NodeHeaderRules(setup.praos.copy(verdicts = cache.forHeaders), setup.globals),
                    scheduling.compute,
                    warm = Some((headers, state) =>
                      praosConfig.get.flatMap(cache.warmHeaders[IO](headers, state, _))
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
                    added = Some(measured.headers)
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
                case Peer.Observation.HeadersArrived(_, _) | Peer.Observation.BlocksArrived(_, _) =>
                  IO.unit)
            // blocks fetched ahead, shared by the sessions of all peers: one peer fetches each block
            blockPool <- BlockPool.create[IO](
              BlockPool.FetchPolicy(setup.config.settings.maxConcurrencyDeadline.getOrElse(1))
            )
            // the connection manager (G2): one connection per peer address, limits, accounting
            manager <- ConnectionManager.create[IO](
              ConnectionManager.Limits(maxInbound = options.maxInbound),
              Some(loggedConnections.alongside(measured.connection))
            )
            _ <- collectors.add(Collectors.connections(manager))
            governor <- InboundGovernor.create[IO](
              manager,
              InboundGovernor.Config(
                genesis.networkMagic,
                NodeToNodeServer.Settings(
                  offerPeerSharing = setup.config.settings.peerSharing.contains(true),
                  maxVersion = setup.config.settings.maxNtnVersion
                ),
                Peer.Settings(
                  SocketAddress(Host.fromString("0.0.0.0").get, Port.fromInt(0).get),
                  genesis.networkMagic,
                  readAhead = options.readAhead,
                  byronEpochSlots = genesis.geometry.byron.epochSlots
                ),
                runClients = options.inboundSync
              ),
              handlers,
              // the session of a peer that connected to us runs like one on a connection we opened
              follow = peer => scheduling.compute.within(follower.session(peer)),
              prepare = warm,
              headers = headerCheck,
              transactions = Some(mempool.source),
              events = Some(loggedInbound.alongside(measured.inbound)),
              observe = Some(observePeer)
            )
            // The outbound peer network (G1): the governor chooses the peers, moves them between
            // temperatures and replaces the ones that fail; the Genesis State Machine tells it
            // whether the ledger is too old.
            seed <- IO.delay(java.util.concurrent.ThreadLocalRandom.current().nextLong())
            defaults = if options.forge.isDefined then Targets.blockProducer else Targets.relay
            targets <- setup.config.settings
              .peerTargets(defaults)
              .fold(e => fail[Targets](e), IO.pure)
            losses <- Ref.of[IO, Int](0)
            fetchModeRef <- Ref.of[IO, FetchMode](FetchMode.BulkSync)
            chainView = new PeerNetwork.ChainView[IO]:
              def tip = ref.get.map(c => c.tipRef.map(t => (t.slot, t.hash.hashCode.toLong)))
              def geometry = ref.get.map(_.state.geometry(genesis.geometry))
            marker = options.db.fold(GsmRunner.Marker.none[IO])(d =>
              GsmRunner.Marker.file[IO](d.resolve("gsm"))
            )
            hot = OutboundPeers.Hot[IO](
              magic = genesis.networkMagic,
              network = NodeToNode.Settings(
                offerPeerSharing = setup.config.settings.peerSharing.contains(true),
                maxVersion = setup.config.settings.maxNtnVersion
              ),
              peer = Peer.Settings(
                SocketAddress(Host.fromString("0.0.0.0").get, Port.fromInt(0).get),
                genesis.networkMagic,
                readAhead = options.readAhead,
                byronEpochSlots = genesis.geometry.byron.epochSlots
              ),
              follow = (peer, track) => scheduling.compute.within(follower.session(peer, track)),
              prepare = warm,
              headers = headerCheck,
              transactions = Some(mempool.source),
              observe = Some(observePeer),
              pool = blockPool,
              versus = candidate => follower.versus(candidate),
              lost = error =>
                losses
                  .updateAndGet(_ + 1)
                  .flatMap(n => events.emit(FollowerEvent.Reconnecting(error, n))),
              fetchMode = Some(fetchModeRef.get)
            )
            emitOutput = (out: Output[PeerNetwork.Addr]) =>
              measured.peerSelection(out) *> (out match
                case Output.Traced(trace)  => logged(NodeEvent.PeerSelection(trace))
                case Output.Churned(trace) => logged(NodeEvent.PeerChurn(trace))
                case Output.Counted(c)     => logged(NodeEvent.PeerCounters(c))
                case Output.Connections(c) => logged(NodeEvent.OutboundConnections(c)))
            network = PeerNetwork.resource[IO](
              PeerNetwork.Settings(
                options.roots.getOrElse(PeerNetwork.Roots(Nil, Set.empty, None, false)),
                targets,
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
                measured.gsm(phase) *> logged(NodeEvent.GsmTransition(phase, event)),
              phase => measured.gsm(phase),
              mode => measured.fetchMode(mode) *> logged(NodeEvent.FetchModeChanged(mode)),
              marker
            )
            relay = options.listen.fold(Resource.unit[IO])(address =>
              BearerServer
                .listen[IO](
                  Some(address.host),
                  Some(address.port),
                  maxConnections = options.maxInbound * 2
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
                    BigInt(genesis.geometry.securityParam) * 10,
                    BigInt(20000),
                    BigInt(genesis.geometry.securityParam)
                  )
                )(b =>
                  EraHistory
                    .ByronParams(b.epochSlots, BigInt(b.slotLengthMillis), BigInt(b.securityParam))
                ),
                setup.globals.maxSupply,
                genesis.raw,
                setup.mirrorEnv(observed).ledger,
                observed
              )
            )
            local = options.localSocket.fold(Resource.unit[IO])(path =>
              BearerServer
                .listenUnix[IO](path) { bearer =>
                  logged(NodeEvent.Inbound(path.toString, InboundEvent.Accepted)) *>
                    Handle
                      .allow[NetworkError](
                        NodeToClientServer.accept(
                          bearer,
                          genesis.networkMagic,
                          LocalSubmission.handler(mempool),
                          Some(StateQuerySource[IO, MirrorState](ref, queryEnv, store)),
                          chainSync = Some(server.blockSource),
                          txMonitor = Some(LocalMonitor.source(mempool))
                        )
                      )
                      .rescue(e => IO.pure(e.message))
                      // A failure that is not a network error still ends the connection: say so,
                      // where the listener would drop it unseen.
                      .attempt
                      .map(
                        _.fold(
                          t => s"failed: ${t.getClass.getSimpleName}: ${t.getMessage}",
                          identity
                        )
                      )
                      .flatMap(reason =>
                        logged(NodeEvent.Inbound(path.toString, InboundEvent.Closed(reason)))
                      )
                }
                .evalMap(_ => logged(NodeEvent.Listening(path.toString)))
            )
            forging <- options.forge.traverse { files =>
              for
                credentials <- IO
                  .blocking(KeyFiles.load(files.vrfSkey, files.kesSkey, files.opCert))
                  .flatMap(_.fold(fail, IO.pure))
                source <- HotKeySource.files[IO](files.kesSkey, files.opCert)
                forger <- Forger.create[IO, MirrorState](
                  setup.praos.copy(geometry = geometry),
                  globals,
                  credentials,
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
            // SIGHUP re-reads the KES key and operational certificate (as the Haskell node does)
            production = forging.fold(Resource.unit[IO])(f =>
              Signals.hangup[IO](f.reload).flatMap(_ => f.run.background.void)
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
            run = network.use { net =>
              // the governor ends the node only by a fault (an invariant, the bootstrap timeout)
              val sync: IO[Unit] =
                collectors.add(Collectors.peerMetrics(net.metrics)) *>
                  IO.race(net.run, net.fatal.get).flatMap(_.fold(identity, fail[Unit]))
              QueryServer
                .resource[IO, MirrorState](
                  ChainView.fromRef(ref),
                  options.queryPort,
                  Some(store),
                  Some(scheduling.stats.total.map(Reports.schedulingTotal)),
                  Some(
                    (manager.snapshot, governor.sessions, net.state.get, IO.monotonic).mapN {
                      (connections, sessions, selection, now) =>
                        InboundGovernor
                          .json(connections, sessions)
                          .deepMerge(
                            io.circe.Json.obj("peerSelection" -> PeerNetwork.json(selection, now))
                          )
                    }
                  ),
                  forging.map(_.stats.byEpoch.map(Reports.forgeReport)),
                  Some(scrape)
                )
                .flatMap(_ => relay)
                .flatMap(_ => local)
                .flatMap(_ => production)
                .flatMap(_ => reporting)
                .flatMap(_ => watching)
                .surround(sync)
            }
            _ <- metrics.set(
              Catalogue.buildInfo,
              (BuildInfo.version, BuildInfo.scalaVersion, BuildInfo.gitCommit),
              1.0
            )
            _ <- logged(NodeEvent.started(options.peers, genesis, setup.config, topology))
            // a stall that lasts --stall-exit-after ends the run like a signal does: the stores
            // close and the shutdown snapshot is taken, then the process exits with its own code
            stalledOut <- IO
              .race(
                options.seconds.fold(run)(s => run.timeoutTo(s.seconds, IO.unit)),
                stallExit.get
              )
              .map(_.isRight)
            last <- ref.get
            _ <- logged(NodeEvent.finished(last))
          yield stalledOut
      })
    yield if stalled then StallExitCode else ExitCode.Success

  /** The exit code of a run that ended because a stall lasted `--stall-exit-after`. */
  val StallExitCode: ExitCode = ExitCode(4)

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
      ConfigError(
        s"--$flag was removed (ledger-mirror step 2, J2.9): $instead\n$usage",
        ConfigProblem.RemovedFlag(flag)
      )

  private object RemovedFlag:
    def named(argument: String): Option[RemovedFlag] =
      values.find(r => argument == s"--${r.flag}")

  private val Usage =
    "usage: follow (--genesis-dir <dir> | --config <config.json>) (--peer <host>:<port> [--peer ...] | --topology <topology.json>) [--listen <host>:<port>] [--seconds N] [--query-port P] [--metrics-listen <host>:<port>] [--plutus-cases <dir>] [--db <dir>] [--snapshot-every <N|Nslots|Nepochs>] [--keep-snapshots N] [--snapshot-check structure|full] [--mempool-bytes N] [--utxo-store memory|rocksdb] [--utxo-cache-mb N] [--memory-report-seconds N] [--stall-minutes N] [--stall-exit-after <Ns|Nm|Nh>] [--churn-interval <Ns|Nm>] [--read-ahead N] [--header-first on|off] [--max-inbound N] [--inbound-sync on|off] [--cpu-threads N] [--dump-state <dir> [--dump-state-at <epoch-boundaries|epoch-boundaries/N|slot,slot,...>] [--dump-state-full]] [--local-socket <path>] [--forge --vrf-skey <file> --kes-skey <file> --opcert <file>] " + LogSettings.Usage

  /** Flags that take a value and may repeat. */
  private val RepeatedFlags = Set("peer") ++ LogSettings.RepeatedFlags

  /** Flags taking a value, other than the repeated ones. */
  private val ValueFlags = LogSettings.ValueFlags ++ Set(
    "genesis-dir",
    "config",
    "topology",
    "seconds",
    "query-port",
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
    "churn-interval",
    "read-ahead",
    "header-first",
    "max-inbound",
    "inbound-sync",
    "cpu-threads",
    "dump-state",
    "dump-state-at",
    "local-socket",
    "vrf-skey",
    "kes-skey",
    "opcert"
  )

  private[node] def parse(args: List[String]): Either[ConfigError, Options] =
    def bad[A](reason: String): Either[ConfigError, A] =
      Left(ConfigError(s"$reason\n$Usage"))
    val usage = ConfigError(Usage)
    @annotation.tailrec
    def go(
        rest: List[String],
        acc: Map[String, List[String]]
    ): Either[ConfigError, Map[String, List[String]]] =
      rest match
        case Nil => Right(acc)
        case "--forge" :: tail =>
          if acc.contains("forge") then bad("duplicate flag --forge")
          else go(tail, acc.updated("forge", List("true")))
        case flag :: _ if RemovedFlag.named(flag).isDefined =>
          Left(RemovedFlag.named(flag).fold(usage)(_.refusal(Usage)))
        case "--dump-state-full" :: tail =>
          if acc.contains("dump-state-full") then bad("duplicate flag --dump-state-full")
          else go(tail, acc.updated("dump-state-full", List("true")))
        case flag :: value :: tail if RepeatedFlags(flag.drop(2)) && flag.startsWith("--") =>
          val name = flag.drop(2)
          go(tail, acc.updated(name, acc.getOrElse(name, Nil) :+ value))
        case flag :: value :: tail if flag.startsWith("--") =>
          val name = flag.drop(2)
          if !ValueFlags.contains(name) then bad(s"unknown flag $flag")
          else if acc.contains(name) then bad(s"duplicate flag $flag")
          else go(tail, acc.updated(name, List(value)))
        case flag :: Nil if flag.startsWith("--") => bad(s"flag $flag needs a value")
        case other :: _                           => bad(s"unexpected argument $other")
    args match
      case "follow" :: rest =>
        for
          multi <- go(rest, Map.empty)
          flags = multi.view.mapValues(_.last).toMap
          dir = flags.get("genesis-dir").map(Path.of(_))
          config = flags.get("config").map(Path.of(_))
          topology = flags.get("topology").map(Path.of(_))
          _ <- Either.cond(dir.isDefined || config.isDefined, (), usage)
          peerTexts = multi.getOrElse("peer", Nil)
          _ <- Either.cond(peerTexts.nonEmpty || topology.isDefined, (), usage)
          peers <- peerTexts.traverse(t => Peer.parseAddress(t).leftMap(ConfigError(_)))
          seconds <- flags.get("seconds").traverse(_.toLongOption.toRight(usage))
          port <- flags.get("query-port").fold(Right(7071))(_.toIntOption.toRight(usage))
          metricsListen <- flags
            .get("metrics-listen")
            .traverse(t => Peer.parseAddress(t).leftMap(ConfigError(_)))
          interval <- flags
            .get("snapshot-every")
            .traverse(t => SnapshotInterval.parse(t).toRight(usage))
          keep <- flags
            .get("keep-snapshots")
            .fold(Right(SnapshotPolicy.ReferenceKeep))(_.toIntOption.filter(_ > 0).toRight(usage))
          check <- flags
            .get("snapshot-check")
            .fold(Right(SnapshotCheck.Structure))(t => SnapshotCheck.parse(t).toRight(usage))
          listen <- flags.get("listen").traverse(t => Peer.parseAddress(t).leftMap(ConfigError(_)))
          localSocket = flags.get("local-socket").map(Path.of(_))
          mempool <- flags
            .get("mempool-bytes")
            .traverse(_.toLongOption.filter(_ > 0).toRight(usage))
          forge <-
            if !flags.contains("forge") then Right(None)
            else
              (
                flags.get("vrf-skey").toRight(usage),
                flags.get("kes-skey").toRight(usage),
                flags.get("opcert").toRight(usage)
              ).mapN((v, k, o) => Some(ForgeFiles(Path.of(v), Path.of(k), Path.of(o))))
          utxoKind <- flags
            .get("utxo-store")
            .traverse(k => Either.cond(Set("memory", "rocksdb")(k), k, usage))
          cacheMb <- flags
            .get("utxo-cache-mb")
            .fold(Right(256))(_.toIntOption.filter(_ > 0).toRight(usage))
          memoryReport <- flags
            .get("memory-report-seconds")
            .fold(Right(0))(_.toIntOption.filter(_ >= 0).toRight(usage))
          stallMinutes <- flags
            .get("stall-minutes")
            .fold(Right(10))(_.toIntOption.filter(_ >= 0).toRight(usage))
          stallExit <- flags
            .get("stall-exit-after")
            .traverse(v =>
              parseDuration(v)
                .filter(d => stallMinutes > 0 && d > stallMinutes.minutes)
                .toRight(usage)
            )
          churnInterval <- flags
            .get("churn-interval")
            .traverse(v => parseDuration(v).filter(_ > Duration.Zero).toRight(usage))
          readAhead <- flags
            .get("read-ahead")
            .fold(Right(128))(_.toIntOption.filter(_ > 0).toRight(usage))
          headerFirst <- flags
            .get("header-first")
            .fold(Right(true))(v => Map("on" -> true, "off" -> false).get(v).toRight(usage))
          maxInbound <- flags
            .get("max-inbound")
            .fold(Right(64))(_.toIntOption.filter(_ > 0).toRight(usage))
          inboundSync <- flags
            .get("inbound-sync")
            .fold(Right(true))(v => Map("on" -> true, "off" -> false).get(v).toRight(usage))
          cpuThreads <- flags
            .get("cpu-threads")
            .fold(Right(math.max(1, Runtime.getRuntime.availableProcessors())))(
              _.toIntOption.filter(_ >= 0).toRight(usage)
            )
          dump <- flags.get("dump-state").traverse { d =>
            flags
              .get("dump-state-at")
              .fold(Right(StateDump.Targets.EpochBoundaries(1)))(t =>
                StateDump.Targets.parse(t).toRight(usage)
              )
              .map(Path.of(d) -> _)
          }
          _ <- Either.cond(dump.isDefined || !flags.contains("dump-state-at"), (), usage)
          log <- LogSettings.parse(flags, multi).leftMap(e => ConfigError(s"${e.message}\n$Usage"))
        yield Options(
          dir,
          peers,
          seconds,
          port,
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
        ).copy(churnInterval = churnInterval)
      case _ => Left(usage)
