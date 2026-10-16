// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.consensus
import xyz.kd5ujc.scarab.consensus.{
  BlockRules,
  Anchor,
  BlockRef,
  FollowerEvent,
  FollowerEvents,
  NodeState,
  Scheduling,
  VolatileChain
}
import xyz.kd5ujc.scarab.blst.{Blst, BlstProblem, BlstReport}
import xyz.kd5ujc.scarab.ledger.{Block, ConfigError, NativeLibraryError, NodeError, PeerError}
import cats.effect.{ExitCode, IO, IOApp, Ref, Resource}
import xyz.kd5ujc.scarab.storage.{
  ChainPersistence,
  ChainStorage,
  ImmutableStore,
  MemoryUtxoStore,
  RocksUtxoStore,
  SnapshotFormat,
  SnapshotCheck,
  SnapshotMigration,
  SnapshotV4,
  StateBacking,
  UtxoBacking,
  UtxoStore
}
import cats.mtl.{Ask, Handle, Raise, Stateful}
import xyz.kd5ujc.scarab.consensus.state.{ConsensusMirrorEnv, ProtocolState}
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
import xyz.kd5ujc.scarab.node.differential.{
  DifferentialRules,
  Divergence,
  NewRules,
  Policy,
  Repro,
  Sampling,
  Shadowed
}
import xyz.kd5ujc.scarab.node.forge.{Forger, KeyFiles}
import xyz.kd5ujc.scarab.node.metrics.EventSink.alongside
import xyz.kd5ujc.scarab.node.metrics.{Catalogue, Collectors, EventMetrics, MeteredRules, Metrics}
import xyz.kd5ujc.scarab.node.mempool.{LocalMonitor, LocalSubmission, Mempool, MempoolEnv}
import xyz.kd5ujc.scarab.node.ntc.{EraHistory, QueryEnv, StateQuerySource}
import xyz.kd5ujc.scarab.node.relay.{ChainServer, Persistence}
import scala.concurrent.duration.*

/** `follow (--genesis-dir <dir> | --config <config.json>) (--peer <host>:<port> [--peer ...] |
  * --topology <topology.json>) [--listen <host>:<port>] [--seconds N] [--query-port P]
  * [--metrics-listen <host>:<port>] [--plutus-cases <dir>] [--db <dir>] [--snapshot-every
  * <N|Nslots|Nepochs>] [--keep-snapshots N] [--snapshot-check structure|full] [--mempool-bytes N]
  * [--utxo-store memory|rocksdb] [--utxo-cache-mb N] [--memory-report-seconds N] [--read-ahead N]
  * [--header-first on|off] [--max-inbound N] [--inbound-sync on|off] [--cpu-threads N]
  * [--dump-state <dir> [--dump-state-at <epoch-boundaries|epoch-boundaries/N|slot,slot,...>]
  * [--dump-state-full]] [--mirror-check] [--rules old|new|differential [--on-divergence
  * halt|reseed] [--repro-dir <dir>]] [--local-socket <path>] [--forge --vrf-skey <file> --kes-skey
  * <file> --opcert <file>]`
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
      snapshotEvery: SnapshotInterval = SnapshotInterval.Epochs(1),
      keepSnapshots: Int = 3,
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
      /** `--mirror-check`: at the first block and at every epoch boundary, turn the ledger state
        * into the mirror of the Haskell state and back, and log the result ([[MirrorCheck]]).
        */
      mirrorCheck: Boolean = false,
      /** Validate the headers a peer announces before the blocks are fetched (G6). */
      headerFirst: Boolean = true,
      /** The most inbound connections accepted at once (G2). */
      maxInbound: Int = 64,
      /** Run our own clients on inbound duplex connections, so that the peer is a source of blocks
        * as well (G2).
        */
      inboundSync: Boolean = true,
      /** `--rules old|new|differential`: the flat rules (the default), the era-indexed rules (not
        * ported yet: refused), or both side by side on every block
        * ([[differential.DifferentialRules]]).
        */
      rules: RulesMode = RulesMode.Old,
      /** `--on-divergence halt|reseed` (with `--rules differential`): stop at the first
        * disagreement, or adopt the old side and go on.
        */
      onDivergence: Policy = Policy.Halt,
      /** `--repro-dir <dir>` (with `--rules differential`): where the repro bundle of a
        * disagreement is kept; by default `differential` in the database directory, else none.
        */
      reproDir: Option[Path] = None
  )

  val DefaultMempoolBytes: Long = 1048576L

  /** What the chain database (or its absence) hands the node at start. */
  private final case class Storage(
      anchor: Option[BlockRef],
      state: NodeState,
      resumed: Vector[ChainStorage.Resumed[NodeState]],
      persistence: Option[ChainPersistence[IO, NodeState]],
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

  def run(args: List[String]): IO[ExitCode] =
    if args.headOption.contains("import-haskell-db") then
      xyz.kd5ujc.scarab.node.tools.HaskellImportCommand.run(args.tail)
    else runFollow(args)

  private def runFollow(args: List[String]): IO[ExitCode] =
    parse(args) match
      case Left(error) => IO.println(error.message).as(ExitCode(2))
      case Right(options) =>
        Handle
          .allow[NodeError](
            Handle
              .allow[Divergence](follow(options).as(ExitCode.Success))
              .rescue(d => JsonLog.rulesHalted(d).as(ExitCode(3)))
          )
          .rescue(error => JsonLog.error(error).as(ExitCode(1)))

  /** The UTxO store and the backing that keeps it in step with the immutable tip and the snapshots.
    */
  private def utxoBacking(
      options: Options
  )(using Handle[IO, NodeError]): Resource[IO, (UtxoStore[IO], StateBacking[IO, NodeState])] =
    val kind = options.utxoStore.getOrElse(if options.db.isDefined then "rocksdb" else "memory")
    (kind, options.db) match
      case ("rocksdb", Some(dir)) =>
        val root = fs2.io.file.Path.fromNioPath(dir) / "utxo"
        RocksUtxoStore
          .resource[IO](root / "live", options.utxoCacheMb.toLong * 1024 * 1024)
          .map(s => (s: UtxoStore[IO]) -> (new UtxoBacking[IO](s, root / "checkpoints")))
      case (_, db) =>
        val root =
          db.fold(fs2.io.file.Path("utxo-memory"))(d => fs2.io.file.Path.fromNioPath(d) / "utxo")
        Resource
          .eval(MemoryUtxoStore[IO])
          .map(s =>
            (s: UtxoStore[IO]) -> (
              if db.isDefined then new UtxoBacking[IO](s, root / "checkpoints")
              else StateBacking.none[IO, NodeState]
            )
          )

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
      _ <- Either.cond(
        peers.nonEmpty,
        (),
        ConfigError("no peer to follow: the topology names no local root, bootstrap or public root")
      )
    yield (
      options.copy(
        peers = peers,
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
  private def nativeLibrary(using Handle[IO, NodeError]): IO[Unit] =
    Handle
      .allow[BlstProblem](Blst.verify[IO])
      .rescue(problem =>
        Handle[IO, NodeError].raise[NodeError, BlstReport](NativeLibraryError(problem))
      )
      .flatMap(JsonLog.blst)

  private def follow(requested: Options)(using
      Handle[IO, NodeError],
      Raise[IO, Divergence]
  ): IO[Unit] =
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
        ).tupled.use { case ((store, backing), cache, scheduling) =>
          followWith(options, setup, topology, store, backing, cache, scheduling)
        }
      }

  private def followWith(
      options: Options,
      loaded: NodeSetup,
      topology: Option[Topology],
      store: UtxoStore[IO],
      backing: StateBacking[IO, NodeState],
      cache: VerificationCache,
      scheduling: Scheduling[IO]
  )(using Handle[IO, NodeError], Raise[IO, Divergence]): IO[Unit] =
    def fail[A](e: NodeError): IO[A] = Handle[IO, NodeError].raise(e)
    val setup = loaded.copy(praos = loaded.praos.copy(verdicts = cache))
    for
      _ <- fail[Unit](
        ConfigError(
          "--rules new: the era-indexed rules are not ported yet (use --rules old or differential)"
        )
      ).whenA(options.rules == RulesMode.New)
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
      // The measurements: every event goes to the JSON log and to the metrics ([[EventSink]]).
      metrics <- Metrics.create[IO](Catalogue.all)
      given Metrics[IO] = metrics
      measured <- EventMetrics.create[IO]
      logged = JsonLog.events(Some(scheduling.stats))
      events = new FollowerEvents[IO]:
        def emit(event: FollowerEvent): IO[Unit] =
          (event match
            case FollowerEvent.Connected(_) => connected.set(true)
            case FollowerEvent.EraEntered(_, to, _, epoch) =>
              eraStarts.update(_.updatedWith(to)(_.orElse(Some(epoch))))
            case _ => IO.unit
          ) *> logged.emit(event)
      followerEvents = events.alongside(measured.follower)
      storageEvents = JsonLog.storage.alongside(measured.storage)
      loggedConnections: ConnectionEvents[IO] = JsonLog.connection(_)
      loggedInbound: InboundEvents[IO] = JsonLog.inboundEvent(_, _)
      cases = options.plutusCases.map { dir =>
        java.nio.file.Files.createDirectories(dir)
        tools.PlutusCases(dir, perKind = 2, max = 400)
      }
      dumper <- options.dumpState.traverse { (dir, targets) =>
        StateDump.Dumper.create[IO](
          dir,
          targets,
          Some(store),
          json => IO.println(json.noSpaces),
          options.dumpStateFull
        )
      }
      mirrorChecker <- Option
        .when(options.mirrorCheck)(())
        .traverse(_ =>
          MirrorCheck.Checker.create[IO](eraStarts.get.map(setup.mirrorEnv), JsonLog.mirrorChecked)
        )
      oldRules = NodeRules[IO](
        setup.praos,
        setup.globals,
        setup.byronRules,
        phase2Observer = cases.map(c => c.observe),
        utxoStore = Some(store),
        compute = Some(scheduling.compute),
        afterBlock = Some { (header, s) =>
          IO.delay(cache.noteEpochNonce(s.ledger.epoch, s.protocol.epochNonce)) *>
            dumper.traverse_(_.afterBlock(header, s)) *>
            mirrorChecker.traverse_(_.afterBlock(header, s)) *>
            praosConfig
              .update(c => c.copy(geometry = s.geometry(c.geometry)))
              .whenA(s.byron.firstShelleyEpoch.isDefined) *>
            blocks.updateAndGet(_ + 1).flatMap { n =>
              IO.blocking(
                System.err.println(
                  s"verification verdicts found ahead (hits/lookups): ${cache.summary}"
                )
              ).whenA(n % 20000 == 0)
            }
        }
      )
      k = genesis.geometry.securityParam
      // `--rules differential`: the same rules, with the era-indexed side beside them
      reproDir = options.reproDir.orElse(options.db.map(_.resolve("differential")))
      differential <-
        if options.rules != RulesMode.Differential then IO.pure(None)
        else
          DifferentialRules
            .create[IO](
              oldRules,
              NewRules.current[IO],
              eraStarts.get.map(setup.mirrorEnv),
              Sampling.Default,
              options.onDivergence,
              reproDir.fold(Repro.none[IO])(dir =>
                Repro.files[IO](dir, eraStarts.get.map(setup.mirrorEnv), Some(store))
              ),
              JsonLog.differential,
              Some((s, h) => NodeRules.tick(s, h, setup.praos, setup.globals, setup.byronRules))
            )
            .map(Some(_))
      rules <- differential.fold[IO[BlockRules[IO, NodeState]]](IO.pure(oldRules))(
        Shadowed(_, 2L * k)
      )
      // The mirror's environment as the snapshots ask for it: the configuration, and the eras this
      // node has seen begin so far.
      given Ask[IO, ConsensusMirrorEnv] = setup.mirrorAsk(eraStarts.get)
      given SnapshotFormat[IO, NodeState] = SnapshotV4.format[IO](options.snapshotCheck)
      storage = options.db.fold(
        Resource.pure[IO, Storage](
          Storage(
            None,
            setup.start,
            Vector.empty,
            Some(ChainPersistence.backingOnly[IO, NodeState](backing)),
            None
          )
        )
      ) { dir =>
        val every = options.snapshotEvery match
          case SnapshotInterval.Slots(n)  => BigInt(n)
          case SnapshotInterval.Epochs(n) => BigInt(n) * genesis.geometry.epochLength
        ChainStorage
          .open[IO, NodeState](
            fs2.io.file.Path.fromNioPath(dir),
            ChainStorage.Config(
              every,
              options.keepSnapshots,
              byronEpochSlots = genesis.geometry.byron.epochSlots,
              identity = Some(setup.identity)
            ),
            rules,
            setup.start,
            storageEvents,
            backing,
            warm,
            Some(scheduling.compute),
            Some(SnapshotMigration.toCurrent[IO])
          )
          .evalMap { r =>
            ChainStorage
              .resume[IO, NodeState](
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
      _ <- storage.use { case Storage(anchor, state, resumed, persistence, immutable) =>
        // A chain whose Byron span is derived learns the first Shelley epoch when it enters
        // Shelley; a restored state past that point knows it, a fresh sync learns it on the way
        // (the mempool and the forger need a restart after that, see docs/compatibility.md).
        val geometry = state.geometry(genesis.geometry)
        val globals =
          state.byron.firstShelleyEpoch.fold(setup.globals)(e => setup.globals.copy(firstEpoch = e))
        for
          _ <- praosConfig.update(c => c.copy(geometry = state.geometry(c.geometry)))
          ref <- Ref.of[IO, VolatileChain[NodeState]](
            VolatileChain(
              k,
              Anchor(anchor, state),
              resumed.map(r => r.ref -> r.state),
              setup.origin
            )
          )
          given Stateful[IO, VolatileChain[NodeState]] = RefStateful(ref)
          server <- ChainServer.open[IO](
            immutable,
            anchor,
            ChainServer.headerOfBlock(genesis.geometry.byron.epochSlots)
          )
          _ <- resumed.traverse_(r =>
            server
              .persistence[NodeState]
              .extended(r.ref, r.raw, Vector.empty, Anchor(anchor, state))
          )
          mempool <- Mempool.create[IO](
            resumed.lastOption.fold(state)(_.state).ledger,
            resumed.lastOption.map(_.ref).orElse(anchor).fold(BigInt(0))(_.slot),
            MempoolEnv(globals, geometry),
            options.mempoolBytes.getOrElse(DefaultMempoolBytes),
            JsonLog.mempool.alongside(measured.mempool),
            utxoStore = Some(store)
          )
          served = server.persistence[NodeState]
          follower <- Follower.create[IO, NodeState](
            MeteredRules[IO, NodeState](rules),
            followerEvents,
            genesis.geometry,
            NodeRules.observe(genesis.geometry),
            Some(persistence.fold(served)(Persistence.both(_, served))),
            Some((chain, added) =>
              // decoding the added blocks is CPU-bound: off the compute pool like the rules
              scheduling.compute
                .within(IO.delay {
                  added.flatMap { raw =>
                    Block.decode(raw, genesis.geometry.byron.epochSlots).toOption.toVector.flatMap {
                      case b: Block.Shelley => b.transactions.map(_.id)
                      case _                => Vector.empty
                    }
                  }.toSet
                })
                .flatMap(adopted =>
                  chain.tipRef
                    .traverse_(t => mempool.sync(chain.state.ledger, t.slot, adopted).void)
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
          retry = Follower.Retry(
            // one upstream peer is the whole node: give up when it is gone; with several, keep trying
            maxAttempts = if options.peers.size > 1 then Int.MaxValue else 30,
            delay = 2.seconds
          )
          // header-first validation (G6): signatures are verified through the cache's keeping
          // view, so the block's own validation does not repeat them
          headerCheck = Option.when(options.headerFirst)(
            HeaderCheck.forChain[IO, NodeState, ProtocolState](
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
                    JsonLog
                      .headersChecked(counts.validated, counts.unchecked, counts.waited)
                      .whenA(_)
                  )
              ),
              added = Some(measured.headers)
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
                JsonLog.chainSyncRounds(n, headers, total / n, max, depth)
              })
          observePeer = (o: Peer.Observation) =>
            measured.peer(o) *> (o match
              case Peer.Observation.Fetched(millis) =>
                JsonLog.slowFetch(millis).whenA(millis >= 250)
              case Peer.Observation.Round(requested, received, millis) =>
                observeRounds(requested, received, millis))
          // the connection manager (G2): one connection per peer address, limits, accounting
          manager <- ConnectionManager.create[IO](
            ConnectionManager.Limits(maxInbound = options.maxInbound),
            Some(loggedConnections.alongside(measured.connection))
          )
          governor <- InboundGovernor.create[IO](
            manager,
            InboundGovernor.Config(
              genesis.networkMagic,
              NodeToNodeServer.Settings(),
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
          // Peer failures are handled by the supervisor; once retries are exhausted they become fatal.
          sync = Handle
            .allow[PeerError](
              options.peers.parTraverse_ { address =>
                val settings = Peer.Settings(
                  address,
                  genesis.networkMagic,
                  readAhead = options.readAhead,
                  byronEpochSlots = genesis.geometry.byron.epochSlots
                )
                Follower.supervise(
                  follower,
                  Outbound.connect[IO](
                    manager,
                    address,
                    Peer.nodeToNode[IO](
                      settings,
                      warm,
                      Some(mempool.source),
                      headerCheck,
                      Some(observePeer)
                    ),
                    Some { case Outbound.Event.Reused(remote) =>
                      JsonLog.inbound(
                        remote,
                        "reused",
                        Some(s"outbound $address served by the inbound duplex connection")
                      )
                    },
                    reuseInbound = options.inboundSync
                  ),
                  events,
                  retry,
                  Some(scheduling.compute)
                )
              }
            )
            .rescue(error => fail[Unit](error))
          relay = options.listen.fold(Resource.unit[IO])(address =>
            BearerServer
              .listen[IO](
                Some(address.host),
                Some(address.port),
                maxConnections = options.maxInbound * 2
              ) { (bearer, remote) =>
                governor.serve(bearer, remote).void
              }
              .evalMap(bound => JsonLog.listening(bound.toString))
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
                JsonLog.inbound(path.toString, "accepted") *>
                  Handle
                    .allow[NetworkError](
                      NodeToClientServer.accept(
                        bearer,
                        genesis.networkMagic,
                        LocalSubmission.handler(mempool),
                        Some(StateQuerySource[IO](ref, queryEnv, store)),
                        chainSync = Some(server.blockSource),
                        txMonitor = Some(LocalMonitor.source(mempool))
                      )
                    )
                    .rescue(e => IO.pure(e.message))
                    .flatMap(reason => JsonLog.inbound(path.toString, "closed", Some(reason)))
              }
              .evalMap(_ => JsonLog.listening(path.toString))
          )
          forging <- options.forge.traverse { files =>
            for
              credentials <- IO
                .blocking(KeyFiles.load(files.vrfSkey, files.kesSkey, files.opCert))
                .flatMap(_.fold(fail, IO.pure))
              forger <- Forger.create[IO](
                setup.praos.copy(geometry = geometry),
                globals,
                credentials,
                follower,
                mempool,
                JsonLog.forge.alongside(measured.forge),
                connected.get,
                utxoStore = Some(store)
              )
            yield forger
          }
          production = forging.fold(Resource.unit[IO])(_.run.background.void)
          reporting =
            if options.memoryReportSeconds <= 0 then Resource.unit[IO]
            else
              val stores = Handle
                .allow[NodeError](store.memory)
                .rescue(_ => IO.pure(Map.empty[String, Long]))
              (IO.sleep(options.memoryReportSeconds.seconds) *>
                MemoryReport.sample(stores).flatMap(JsonLog.memory)).foreverM.background.void
          // What a scrape reads besides the counters the events keep: each source answers for
          // itself (a collector that fails leaves its gauges as they were).
          collectors = List(
            Collectors.chain[IO, NodeState](_.geometry(genesis.geometry)),
            Collectors.mempool(mempool),
            Collectors.connections(manager),
            Collectors.scheduling(scheduling.stats),
            Collectors.jvm[IO]
          ) ++ forging.map(f => Collectors.forging(f.stats))
          scrape = metrics.scrape(collectors.map(_.handleError(_ => ())))
          exposed = options.metricsListen.fold(Resource.unit[IO])(address =>
            MetricsServer.resource[IO](address, scrape).void
          )
          run = QueryServer
            .resource[IO](
              ChainView.fromRef(ref),
              options.queryPort,
              Some(store),
              Some(scheduling.stats.total.map(JsonLog.schedulingTotal)),
              Some(
                (manager.snapshot, governor.sessions).mapN(InboundGovernor.json)
              ),
              forging.map(_.stats.byEpoch.map(JsonLog.forgeReport)),
              Some(scrape)
            )
            .flatMap(_ => exposed)
            .flatMap(_ => relay)
            .flatMap(_ => local)
            .flatMap(_ => production)
            .flatMap(_ => reporting)
            .surround(sync)
          _ <- metrics.set(
            Catalogue.buildInfo,
            (BuildInfo.version, BuildInfo.scalaVersion, BuildInfo.gitCommit),
            1.0
          )
          _ <- JsonLog.started(options.peers, genesis, setup.config, topology)
          _ <- options.seconds.fold(run)(s => run.timeoutTo(s.seconds, IO.unit))
          last <- ref.get
          _ <- differential.traverse_(_.stats.flatMap(JsonLog.differentialSummary))
          _ <- JsonLog.finished(last)
        yield ()
      }
    yield ()

  private val Usage =
    "usage: follow (--genesis-dir <dir> | --config <config.json>) (--peer <host>:<port> [--peer ...] | --topology <topology.json>) [--listen <host>:<port>] [--seconds N] [--query-port P] [--metrics-listen <host>:<port>] [--plutus-cases <dir>] [--db <dir>] [--snapshot-every <N|Nslots|Nepochs>] [--keep-snapshots N] [--snapshot-check structure|full] [--mempool-bytes N] [--utxo-store memory|rocksdb] [--utxo-cache-mb N] [--memory-report-seconds N] [--read-ahead N] [--header-first on|off] [--max-inbound N] [--inbound-sync on|off] [--cpu-threads N] [--dump-state <dir> [--dump-state-at <epoch-boundaries|epoch-boundaries/N|slot,slot,...>] [--dump-state-full]] [--mirror-check] [--rules old|new|differential [--on-divergence halt|reseed] [--repro-dir <dir>]] [--local-socket <path>] [--forge --vrf-skey <file> --kes-skey <file> --opcert <file>]"

  /** Flags taking a value, other than `--peer`, which may repeat. */
  private val ValueFlags = Set(
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
    "read-ahead",
    "header-first",
    "max-inbound",
    "inbound-sync",
    "cpu-threads",
    "dump-state",
    "dump-state-at",
    "rules",
    "on-divergence",
    "repro-dir",
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
        case "--mirror-check" :: tail =>
          if acc.contains("mirror-check") then bad("duplicate flag --mirror-check")
          else go(tail, acc.updated("mirror-check", List("true")))
        case "--dump-state-full" :: tail =>
          if acc.contains("dump-state-full") then bad("duplicate flag --dump-state-full")
          else go(tail, acc.updated("dump-state-full", List("true")))
        case "--peer" :: value :: tail =>
          go(tail, acc.updated("peer", acc.getOrElse("peer", Nil) :+ value))
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
            .fold(Right(SnapshotInterval.Epochs(1)))(t => SnapshotInterval.parse(t).toRight(usage))
          keep <- flags
            .get("keep-snapshots")
            .fold(Right(3))(_.toIntOption.filter(_ > 0).toRight(usage))
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
          rules <- flags.get("rules").fold(Right(RulesMode.Old))(RulesMode.parse(_).toRight(usage))
          onDivergence <- flags
            .get("on-divergence")
            .fold(Right(Policy.Halt))(v =>
              Map("halt" -> Policy.Halt, "reseed" -> Policy.Reseed).get(v).toRight(usage)
            )
          _ <- Either.cond(
            rules == RulesMode.Differential ||
              !(flags.contains("on-divergence") || flags.contains("repro-dir")),
            (),
            ConfigError(s"--on-divergence and --repro-dir need --rules differential\n$Usage")
          )
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
          readAhead,
          cpuThreads,
          dump,
          flags.contains("dump-state-full"),
          flags.contains("mirror-check"),
          headerFirst,
          maxInbound,
          inboundSync,
          rules,
          onDivergence,
          flags.get("repro-dir").map(Path.of(_))
        )
      case _ => Left(usage)
