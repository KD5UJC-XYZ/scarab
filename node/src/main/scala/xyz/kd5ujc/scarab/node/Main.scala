// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.consensus
import xyz.kd5ujc.scarab.consensus.{
  Anchor,
  BlockRef,
  FollowerEvent,
  FollowerEvents,
  Scheduling,
  VolatileChain
}
import xyz.kd5ujc.scarab.ledger.Header
import xyz.kd5ujc.scarab.crypto.bls.{Blst, BlstProblem, BlstReport}
import xyz.kd5ujc.scarab.plutus.PlutusBackend
import xyz.kd5ujc.scarab.ledger.{
  Block,
  ClockProblem,
  ConfigError,
  ConfigProblem,
  LedgerRelay,
  NativeLibraryError,
  NativeLibraryProblem,
  NodeError,
  StorageError
}
import cats.data.EitherT
import cats.effect.{Deferred, ExitCode, IO, IOApp, Ref, Resource}
import cats.effect.std.{Mutex, Supervisor}
import cats.effect.metrics.CpuStarvationWarningMetrics
import xyz.kd5ujc.scarab.storage.{
  ChainPersistence,
  ChainStorage,
  ImmutableStore,
  LeiosStore,
  LeiosStoreLocks,
  NetworkIdentityFile,
  MemoryUtxoStore,
  RocksUtxoStore,
  ReceivedBlocks,
  SnapshotFormat,
  SnapshotCheck,
  SnapshotMigration,
  SnapshotPolicy,
  SnapshotV10,
  StateAdmission,
  StateBacking,
  UtxoBacking,
  UtxoHandle,
  UtxoStore
}
import cats.mtl.{Ask, Handle, Raise, Stateful, Tell}
import xyz.kd5ujc.scarab.consensus.state.{ConsensusMirrorEnv, MirrorState}
import cats.syntax.all.*
import java.nio.file.{InvalidPathException, Path}
import com.comcast.ip4s.{Host, IpAddress, Port, SocketAddress}
import xyz.kd5ujc.scarab.protocol.{ChainSync, ConnectionState, Era, ReferenceBuild}
import xyz.kd5ujc.scarab.storage.haskell.state.ReferenceLayout
import xyz.kd5ujc.scarab.protocol.NtNHeader
import xyz.kd5ujc.scarab.protocol.CardanoBlockFetch.RawNtNBlock
import xyz.kd5ujc.scarab.network.{
  AcceptedConnectionsLimit,
  OutboundLimit,
  Word32,
  BearerServer,
  ConnectionEvents,
  ConnectionManager,
  NodeToClientServer,
  ChainSyncServer,
  LocalStateQueryServer,
  LocalTxMonitorServer,
  LocalTxSubmissionServer,
  NodeToNodeServer,
  NetworkError,
  DiffusionFailure,
  Errno,
  LocalAddress,
  LocalServer,
  LocalSockets,
  NodeAddresses,
  ServerHandlers,
  SocketStep,
  TcpSocketError,
  SocketConfigError,
  TcpListeners
}
import xyz.kd5ujc.scarab.node.forge.{
  BlockForgingState,
  DijkstraSnapshots,
  ForgeEvent,
  ForgeEvents,
  Forger,
  HotKeySource,
  KeyGuard,
  PoolCredentials
}
import xyz.kd5ujc.scarab.node.assertions.{Assertions, Property}
import io.circe.{Encoder, JsonObject}
import io.circe.syntax.*
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
  GenTx,
  LocalMonitor,
  LocalSubmission,
  Mempool,
  MempoolEnv,
  MempoolState,
  MempoolSync,
  PoolLedger
}
import xyz.kd5ujc.scarab.ledger.rules.Hfc
import xyz.kd5ujc.scarab.storage.{StorageEvent, StorageEvents}
import xyz.kd5ujc.scarab.network.ConnectionEvent
import xyz.kd5ujc.scarab.node.ntc.{EraHistory, LedgerConfig, QueryEnv, StateQuerySource}
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.node.read.TxClock
import xyz.kd5ujc.scarab.network.{HotBundle, NodeToNode}
import xyz.kd5ujc.scarab.network.selection.{
  Churn,
  FetchMode,
  LedgerPeerSnapshot,
  PeerMetrics,
  Targets,
  UseLedgerPeers
}
import xyz.kd5ujc.scarab.node.relay.{ChainServer, Persistence}
import scala.concurrent.duration.*

/** `follow (--genesis-dir <dir> | --config <config.json>) (--peer <host>:<port> [--peer ...] |
  * --topology <topology.json>) [--listen <host>:<port>] [--seconds N] [--query-listen off|P]
  * [--query-port P] [--metrics-listen <host>:<port>] [--plutus-cases <dir>] [--db <dir>]
  * [--snapshot-every <N|Nslots|Nepochs>] [--keep-snapshots N] [--snapshot-check structure|full]
  * [--mempool-bytes N] [--utxo-store memory|rocksdb] [--utxo-cache-mb N] [--memory-report-seconds
  * N] [--stall-minutes N] [--read-ahead N] [--chain-sync-low N] [--chain-sync-high N]
  * [--header-first on|off] [--max-inbound N] [--max-outbound N] [--cpu-threads N] [--plutus-workers
  * N] [--dump-state <dir> [--dump-state-at <epoch-boundaries|epoch-boundaries/N|slot,slot,...>]
  * [--dump-state-full]] [--local-socket <path>] [--forge --vrf-skey <file> --kes-skey <file>
  * --opcert <file>]`
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

  /** What serves one node-to-client connection: [[NodeToClientServer.acceptBackend]]'s backends. */
  private[node] final case class LocalBackends(
      submit: xyz.kd5ujc.scarab.codec.Bytes => IO[LocalTxSubmissionServer.Response],
      stateQuery: Option[LocalStateQueryServer.Source[IO, StorageError]],
      chainSync: Option[Resource[IO, ChainSyncServer.Source[IO, RawNtNBlock]]],
      txMonitor: Option[LocalTxMonitorServer.Source[IO]]
  )

  /** The node-to-client server at `path`: each client is served by the backends `backends` makes
    * for it, given how that client's own fault ends it. Whatever ends a client ends that client
    * alone and is logged ([[ListenerFailures.reportLocal]]); the value completes only if the accept
    * loop stops, which stops the node as the reference's `DiffusionErrored` does.
    */
  private[node] def localServer(
      sockets: LocalSockets[IO],
      path: String,
      magic: Long,
      logged: NodeEvent => IO[Unit]
  )(backends: (Fatal => IO[Unit]) => LocalBackends)(using
      Raise[IO, DiffusionFailure]
  ): Resource[IO, IO[DiffusionFailure]] =
    LocalServer
      .resource[IO, NetworkError](
        sockets,
        LocalAddress(path),
        trace => logged(NodeEvent.LocalServer(trace))
      )((_, failure) => ListenerFailures.reportLocal(path, failure, logged)) { (bearer, _) =>
        // what would stop the node on a node-to-node connection ends this client
        // only (the reference's local rethrow policy): the backends report it here
        Deferred[IO, Fatal].flatMap { failed =>
          val serving = backends(fault => failed.complete(fault).void)
          val served = landLocalBackend(
            NodeToClientServer.acceptBackend[IO, StorageError](
              bearer,
              magic,
              serving.submit,
              GenTx.reader,
              serving.stateQuery,
              chainSync = serving.chainSync,
              txMonitor = serving.txMonitor
            )
          )
          logged(NodeEvent.Inbound(path, InboundEvent.Accepted)) *>
            IO.race(failed.get.map(InboundEnd.Fault(_)), served)
              .map(_.merge)
              .flatMap(reason => logged(NodeEvent.Inbound(path, InboundEvent.Closed(reason))))
        }
      }
      .evalTap(_ => logged(NodeEvent.Listening(path)))

  /** Where the mempool's watcher revalidates the pool ([[MempoolSync]]), decided once the pool's
    * lock is held from the chain as it is then ([[Mempool.synchronise]]). Nowhere when the pool is
    * at the chain's tip and its slot (the reference's `TraceMempoolSyncNotNeeded`,
    * ouroboros-consensus 82ecba32 `Mempool/Update.hs` 589-594); otherwise the chain's state ticked
    * to the slot after its tip, with the ids of the transactions in the blocks the pool has not
    * been revalidated past ([[MempoolSync.unseen]]), which leave it as adopted. Those blocks are
    * read from the chain's volatile window, whose bytes `bytesOf` gives (the chain server holds
    * them), so nothing is kept for the pool but `synced`: the chain it was last revalidated at, as
    * the hashes of its anchor and blocks. A transaction in a block that is no longer volatile when
    * the pool catches up leaves it with its reason, as the reference reports every transaction it
    * removes (`TraceMempoolRemoveTxs`).
    */
  private[node] def mempoolTarget(
      scheduling: Scheduling[IO],
      epochSlots: BigInt,
      transition: Hfc.Transition,
      bytesOf: xyz.kd5ujc.scarab.codec.Bytes => IO[Option[xyz.kd5ujc.scarab.codec.Bytes]],
      synced: Ref[IO, Vector[Option[xyz.kd5ujc.scarab.codec.Bytes]]]
  )(using Ask[IO, MempoolEnv]): MempoolSync.Target[IO, VolatileChain[MirrorState]] =
    new MempoolSync.Target[IO, VolatileChain[MirrorState]]:
      def apply(pool: MempoolState, chain: VolatileChain[MirrorState])(using
          Raise[IO, NodeError]
      ): IO[Option[Mempool.Target]] =
        val identities = chain.anchor.block.map(_.hash) +: chain.blocks.map(b => Some(b._1.hash))
        val tipHash = chain.tipRef.map(_.hash)
        val tipSlot = chain.tipRef.fold(BigInt(-1))(_.slot)
        synced.getAndSet(identities).flatMap { previous =>
          if pool.tipHash == tipHash && pool.tipSlot == tipSlot then IO.pure(None)
          else
            val fresh =
              chain.blocks.takeRight(MempoolSync.unseen(previous, pool.tipHash, identities))
            for
              raws <- fresh.traverse(b => bytesOf(b._1.hash)).map(_.flatten)
              decoded <- scheduling.compute.within(
                IO.delay(raws.traverse(Block.decode(_, epochSlots)))
              )
              blocks <- decoded.fold(error => summon[Raise[IO, NodeError]].raise(error), IO.pure)
              // a ranking block's own transactions; a certifying block's closure is not read here
              // from the endorser-block store, so its held transactions leave as dropped (#54)
              adopted = blocks.flatMap {
                case block: Block.Shelley  => block.transactions.map(_.id)
                case block: Block.Dijkstra => block.block.transactions.map(_.id)
                case _                     => Vector.empty
              }.toSet
              environment <- Ask[IO, MempoolEnv].ask
              ledger <- scheduling.compute
                .within(
                  IO.delay(
                    PoolLedger
                      .at(
                        chain.state,
                        chain.tipRef,
                        environment.copy(geometry = chain.state.geometry(environment.geometry)),
                        transition
                      )
                      .leftMap(_.stop(chain.tipRef.fold(BigInt(0))(_.slot + 1)))
                  )
                )
                .flatMap(Raise[IO, NodeError].fromEither(_))
            // a transaction revalidation has no verdict on leaves the pool with its reason (#567)
            yield Some(Mempool.Target(ledger, tipSlot, adopted, tipHash))
        }

  /** Each block of the selected chain that the start resumed, given to the received log
    * ([[ReceivedBlocks.restoreSelected]]): a block the log lacks only if it passes the log's
    * start-up check ([[ReceivedBlockRecovery.startUp]]), which every record the open indexed has
    * passed, so that the pool is seeded from the log without checking a body again
    * ([[seedReceived]]). A resume does not check all of a block (a Byron boundary block's body is
    * not decoded). A block the check refuses is logged ([[NodeEvent.ReceivedBlockDiscarded]]) and
    * not added.
    */
  private[node] def restoreReceived(
      received: ReceivedBlocks[IO],
      resumed: Vector[(BlockRef, xyz.kd5ujc.scarab.codec.Bytes)],
      epochSlots: BigInt
  )(using Raise[IO, NodeError], Log[IO]): IO[Unit] =
    received.holds.flatMap(held =>
      resumed.traverse_ { (ref, raw) =>
        val restore = received.restoreSelected(ref, raw)
        if held(ref.hash) then restore
        else
          ReceivedBlockRecovery
            .checked(ref, raw, epochSlots)
            .fold(
              error => Log[IO].emit(NodeEvent.ReceivedBlockDiscarded(ref.hash, error)),
              _ => restore
            )
      }
    )

  private[node] def seedReceived(
      pool: BlockPool[IO],
      received: Option[ReceivedBlocks[IO]],
      epochSlots: BigInt
  )(using Raise[IO, NodeError], Log[IO]): IO[Unit] =
    received.traverse_(cache =>
      cache.refs.flatMap { refs =>
        refs
          .traverse { ref =>
            // each body is read and dropped at once: the forest keeps the metadata and reads the
            // body back from the log, so the start does not hold every recovered body. Each block
            // has passed the log's start-up check (ReceivedBlockRecovery.startUp): the open ran it
            // on every record it indexed, and the start on each block of the selected chain it
            // added after it, nothing else written since; so only its header is read here, and a
            // refusal, not expected, is of a block the log keeps but the pool is not seeded with
            cache.get(ref.hash).flatMap {
              case None => IO.pure(None)
              case Some((_, raw)) =>
                ReceivedBlockRecovery.seeded(raw, epochSlots) match
                  case Right(metadata) => IO.pure(Some(metadata))
                  case Left(error) =>
                    Log[IO].emit(NodeEvent.ReceivedBlockDiscarded(ref.hash, error)).as(None)
            }
          }
          .flatMap(items => pool.seedRecoveredMetadata(items.flatten))
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
      ): IO[Unit] = store(header, raw) *> preverify(Vector(raw))
      def store(header: Header, raw: xyz.kd5ujc.scarab.codec.Bytes)(using
          Raise[IO, NodeError]
      ): IO[Unit] =
        received.traverse_(
          _.put(
            BlockRef(header.slot, header.hash, header.blockNo, header.isBoundary),
            raw
          )
        )
    Resource
      .eval(work.fold(SelectionWork.create[IO](pool.fetchCoordinator.refreshGenesis))(IO.pure))
      .flatMap(shared => follower.blockSelection(pool, shared, fatal, origin))

  private def createGenesisRuntime(
      setup: NodeSetup,
      follower: Follower[IO, MirrorState],
      blockPool: BlockPool[IO],
      geometry: IO[xyz.kd5ujc.scarab.ledger.EpochGeometry],
      headers: HeaderCheck.Grafting[IO]
  ): IO[Option[GenesisRuntime[IO]]] =
    val windowAt =
      (state: MirrorState, tip: BigInt, slot: BigInt) =>
        GenesisHistory.window(state, tip, slot, setup)
    for
      governor <- Option
        .when(
          setup.config.settings.consensusMode ==
            xyz.kd5ujc.scarab.network.selection.ConsensusMode.GenesisMode
        )(
          follower
            .genesisView(windowAt)
            .flatMap(view =>
              GenesisGovernor.create[IO](
                view,
                GenesisGovernor.Config(
                  setup.config.settings.genesis.enableLoEAndGDD,
                  ((setup.config.settings.genesis.gddIntervalPicos.max(0) + 999) / 1000)
                    .min(BigInt(Long.MaxValue))
                    .toLong
                    .nanos
                )
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
          setup.retainedHeaders,
          IO.realTime.map(now =>
            BigInt(now.toNanos) * 1000 -
              (BigInt(setup.geometry.systemStart.getEpochSecond) * 1000000000000L +
                BigInt(setup.geometry.systemStart.getNano) * 1000)
          ),
          blockPool.fetchCoordinator.refreshGenesis,
          headers
        )
      )
      _ <- runtime.traverse_ { runtime =>
        follower.installGenesis(runtime.governor, windowAt) *>
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
    *   bytes, a `Word32` as the configuration's, rounded up to whole blocks. `None`: no override,
    *   two blocks of the protocol parameters in force.
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
    *   `--key-lock on|off`: with `--forge`, hold an exclusive lock for the KES key file, taken
    *   before the credentials are read (see [[forge.KeyGuard]]).
    * @param startAsNonProducingNode
    *   `--start-as-non-producing-node`, or its hidden, deprecated alias `--non-producing-node`
    *   (cardano-node 11.1.3 `Parsers.hs` 414-436): start with forging disabled even though
    *   credentials are given (see [[startsNonProducing]]).
    * @param headerFirst
    *   Validate the headers a peer announces before the blocks are fetched (G6).
    * @param maxInbound
    *   `--max-inbound`: overrides the hard limit of accepted connections (G2), the `hardLimit` of
    *   the configuration's `AcceptedConnectionsLimit` (default 512, with soft 384 and delay 5 s:
    *   see [[AcceptedConnectionsLimit]] and [[ConfigSettings]]).
    * @param maxOutbound
    *   `--max-outbound`: the opt-in limit on the connections the node dials and still uses
    *   ([[OutboundLimit]]); it wins over the configuration's `MaxOutboundConnections`, and without
    *   either there is none, as in the reference.
    * @param log
    *   `--log-*`: the log's sinks, thresholds and sampling ([[LogSettings]]).
    * @param roots
    *   The roots of the peer governor: `--peer` as one local root group and the topology's groups,
    *   public roots and bootstrap peers; filled in by [[configured]].
    * @param snapshot
    *   The peer snapshot the start read from the file the topology names; filled in by
    *   [[configured]].
    * @param churnInterval
    *   `--churn-interval <Ns|Nm>`, for tests: the churn governor's rounds follow each other this
    *   often and no step waits longer than it (the reference's are 3300 s and 900 s with fuzz and
    *   waits of up to 300 s). Off by default.
    * @param assertionsFile
    *   `--assertions-file <path>`: record the node's Antithesis assertions there, in the fallback
    *   SDK's lines ([[xyz.kd5ujc.scarab.node.assertions.Assertions.recording]], #1032). Off by
    *   default.
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
      mempoolBytes: Option[Word32] = None,
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
      log: LogSettings = LogSettings.default,
      roots: Option[PeerNetwork.Roots] = None,
      snapshot: SnapshotUse = SnapshotUse.Unused,
      churnInterval: Option[FiniteDuration] = None,
      adoptLegacyNetworkIdentity: Boolean = false,
      plutusWorkers: ParallelPlutus.Workers = ParallelPlutus.Workers.default,
      chainSyncWindow: xyz.kd5ujc.scarab.network.ChainSyncPipelineDecision.Window =
        xyz.kd5ujc.scarab.network.ChainSyncPipelineDecision.Window.Default,
      stallDump: Option[Path] = None,
      keyLock: Boolean = true,
      referenceBuild: ReferenceBuild = ReferenceBuild.Default,
      startAsNonProducingNode: Boolean = false,
      assertionsFile: Option[Path] = None,
      maxOutbound: Option[OutboundLimit.Connections] = None
  ):
    /** `ncStartAsNonProducingNode` of the reference: the flags give `Just True` or nothing
      * (`Parsers.hs` 70-72), the configuration file's `StartAsNonProducingNode` a value or nothing
      * (`Configuration/POM.hs` 325), and the last value given wins in
      * `defaultPartialNodeConfiguration <> configYamlPc <> cmdPc` (`Run.hs` 217), whose default is
      * false (`POM.hs` 738). So a flag starts the node non-producing whatever the file says.
      */
    def startsNonProducing(settings: ConfigSettings): Boolean =
      startAsNonProducingNode || settings.startAsNonProducingNode.getOrElse(false)

    /** The configuration's limits, with the hard limit of `--max-inbound` where given. */
    def accepted(settings: ConfigSettings): AcceptedConnectionsLimit =
      maxInbound.fold(settings.acceptedConnectionsLimit)(hard =>
        settings.acceptedConnectionsLimit.copy(acceptedConnectionsHardLimit = hard)
      )

    /** The outbound limit: `--max-outbound`, else the configuration's `MaxOutboundConnections`,
      * else none.
      */
    def outboundLimit(settings: ConfigSettings): OutboundLimit =
      maxOutbound
        .orElse(settings.maxOutboundConnections)
        .fold(OutboundLimit.Unlimited)(OutboundLimit.AtMost(_))

  /** What the chain database (or its absence) hands the node at start. */
  private final case class Storage[S](
      anchor: Option[BlockRef],
      state: S,
      resumed: Vector[ChainStorage.Resumed[S]],
      persistence: Option[ChainPersistence[IO, S]],
      immutable: Option[ImmutableStore[IO]],
      received: Option[ReceivedBlocks[IO]],
      acquired: xyz.kd5ujc.scarab.consensus.AcquiredLeiosEbs
  )

  /** `--forge --vrf-skey <file> --kes-skey <file> --opcert <file> [--shelley-bls-key <file>]`: the
    * pool's key files, and its Leios voting keys, read with the others (`shelleyBLSFile`,
    * cardano-node b004fe0c `Cardano/Node/Types.hs:183`).
    */
  final case class ForgeFiles(
      vrfSkey: Path,
      kesSkey: Path,
      opCert: Path,
      blsSkey: Option[Path] = None
  )

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

  // the owner's rule for names (#953): an argument the rule refuses is refused before any file is
  // touched. A name read from a file that the JVM cannot encode, which the JDK refuses with
  // `InvalidPathException` where the name is used, gives the same kind of line; the configuration's
  // own names are checked once `follow` has read its setup, so this catches the names read before
  // that or never checked (the genesis files read first, those of the import commands)
  def run(args: List[String]): IO[ExitCode] =
    CommandLine.utf8Output[IO] *> CommandLine
      .encoding[IO]
      .flatMap(encoding =>
        CommandLine
          .exact(args, encoding)
          .fold(
            refused,
            dispatch(_).recoverWith { case refusal: InvalidPathException =>
              refused(NameError.of(refusal, encoding))
            }
          )
      )

  /** A name the rule for names refuses: said on standard error, and exit code 2. */
  private def refused(error: NameError): IO[ExitCode] = failedStart(error.message).as(ExitCode(2))

  private def dispatch(args: List[String]): IO[ExitCode] = args match
    case "export-scls" :: rest       => xyz.kd5ujc.scarab.node.tools.SclsCommand.runExport(rest)
    case "import-scls" :: rest       => xyz.kd5ujc.scarab.node.tools.SclsCommand.runImport(rest)
    case "import-haskell-db" :: rest => xyz.kd5ujc.scarab.node.tools.HaskellImportCommand.run(rest)
    case "import-mithril" :: rest => xyz.kd5ujc.scarab.node.tools.MithrilBootstrapCommand.run(rest)
    case "import-ledger-snapshot" :: rest =>
      xyz.kd5ujc.scarab.node.tools.LedgerSnapshotImportCommand.run(rest)
    case "export-ledger-snapshot" :: rest =>
      xyz.kd5ujc.scarab.node.tools.LedgerSnapshotExportCommand.run(rest)
    case _ => runFollow(args)

  private def runFollow(args: List[String]): IO[ExitCode] =
    parse(args) match
      case Left(error) => IO.println(error.message).as(ExitCode(2))
      case Right(options) =>
        LogRouter.resource[IO](options.log).use { case given Log[IO] =>
          def logged(event: NodeEvent) = Log[IO].emit(event)
          // released once the run is over, the start's failure included, so that what the node
          // asserted last is written
          assertions(options.assertionsFile).use { case given Assertions[IO] =>
            Handle
              .allow[NodeError](follow(options))
              .rescue { error =>
                val (event, code) = startupFailure(error)
                startupFatal(error).traverse_(stopped) *>
                  logged(event) *> failedStart(error.message).as(code)
              }
          }
        }

  /** The node's Antithesis assertions: with `--assertions-file`, recorded to that file, appended
    * to; without it, none. A file that cannot be written is said once, as an `error` line, and the
    * node goes on without them.
    */
  private def assertions(file: Option[Path])(using Log[IO]): Resource[IO, Assertions[IO]] =
    file.fold(Resource.pure[IO, Assertions[IO]](Assertions.noop[IO]))(path =>
      Assertions.recording[IO](
        Assertions.appending[IO](path),
        failure => Log[IO].emit[NodeEvent](NodeEvent.AssertionsFailed(path, failure))
      )
    )

  /** Replay has no verdict when the evaluator is unavailable, just as live adoption has none. A
    * stored block was adopted by an earlier run, so the chain cannot be rebuilt past a block the
    * node has no verdict on, and the node stops.
    */
  private[scarab] def startupFailure(error: NodeError): (NodeEvent, ExitCode) =
    startupFatal(error).fold((NodeEvent.Failed(error), ExitCode(1)))(faulted)

  /** The fault a failed start stops the node for, as a fault of the run would: none for an error
    * that only fails the start.
    */
  private[node] def startupFatal(error: NodeError): Option[Fatal] =
    SessionEnd.of(error) match
      case SessionEnd.Unavailable(unavailable) => Some(Fatal.Unavailable(unavailable))
      case SessionEnd.Unverifiable(defect)     => Some(Fatal.Unverifiable(defect))
      case SessionEnd.Returned | SessionEnd.NoMoreIntersection |
          SessionEnd.RolledBackPastIntersection(_) | SessionEnd.Failed(_) | SessionEnd.Rejected(_) |
          SessionEnd.Storage(_) | SessionEnd.Internal(_) | SessionEnd.Defect(_) |
          SessionEnd.Cancelled | SessionEnd.Stopped(_) =>
        None

  /** The node stops on `fault`: asserted unreachable, except for the stop for a clock moved back
    * further than the forger's rewind limit ([[ClockProblem.SystemClockMovedBack]]), which
    * cardano-node makes too. It is left out by its typed cause. The run's faults and the start's
    * come here, where they are logged as `fatal`.
    */
  private[node] def stopped(fault: Fatal)(using Assertions[IO]): IO[Unit] = fault match
    case Fatal.Internal(ClockProblem.SystemClockMovedBack(_, _)) => IO.unit
    case Fatal.Unavailable(_) | Fatal.Storage(_) | Fatal.Internal(_) | Fatal.Defect(_) |
        Fatal.Unverifiable(_) =>
      Assertions[IO].unreachable(Property.Unreachable.StoppedOnFault, Stop(fault))

  /** The reporter of the faults that stop the node ([[FatalReporter]]): the first is logged (`log`,
    * cancelable), then, in the step that runs once the fault is claimed whatever becomes of the
    * log, asserted ([[stopped]]) and handed to `faulted`, which ends the run. A log that is
    * cancelled or fails loses neither the assertion nor the stop.
    */
  private[node] def faultReporter(faulted: Deferred[IO, Fatal], log: Fatal => IO[Unit])(using
      Assertions[IO]
  ): IO[FatalReporter[IO]] =
    FatalReporter.create[IO](log, fault => stopped(fault).guarantee(faulted.complete(fault).void))

  /** What the assertion of a stop reports: the fault, as the `fatal` line gives it. */
  private final case class Stop(fault: Fatal)

  private object Stop:
    given Encoder.AsObject[Stop] = Encoder.AsObject.instance(s =>
      JsonObject(
        "kind" -> s.fault.productPrefix.asJson,
        "message" -> s.fault.message.asJson,
        "exitCode" -> FaultExitCode(s.fault).code.asJson
      )
    )

  /** A ledger snapshot written, asserted from the storage's typed event. */
  private[node] def snapshots(using Assertions[IO]): StorageEvents[IO] = new StorageEvents[IO]:
    def emit(event: StorageEvent): IO[Unit] = event match
      case StorageEvent.SnapshotWritten(name, anchor, bytes, _) =>
        Assertions[IO].sometimes(
          Property.Sometimes.SnapshotWritten,
          true,
          JsonObject(
            "name" -> name.asJson,
            "slot" -> anchor.map(_.slot).asJson,
            "bytes" -> bytes.asJson
          )
        )
      case StorageEvent.FormatMigrated(_) | StorageEvent.ImmutableOpened(_) |
          StorageEvent.SnapshotSkipped(_, _) | StorageEvent.SnapshotRestored(_, _) |
          StorageEvent.SnapshotUnbound(_, _) | StorageEvent.SnapshotFailed(_) |
          StorageEvent.SnapshotRefused(_, _, _) | StorageEvent.ReplayStarted(_, _) |
          StorageEvent.ReplayProgress(_, _, _) | StorageEvent.ReplayFinished(_, _, _) |
          StorageEvent.NetworkRecorded(_, _, _) | StorageEvent.VolatileOpened(_) |
          StorageEvent.ReceivedOpened(_) | StorageEvent.ReceivedTruncated(_) |
          StorageEvent.ReceivedInvalidFileNames(_) | StorageEvent.ReceivedBlockAlreadyHere(_) |
          StorageEvent.ScheduledGC(_, _) | StorageEvent.PerformedGC(_) |
          StorageEvent.VolatileResumed(_, _, _) | StorageEvent.ShutdownSnapshot(_, _) |
          StorageEvent.SnapshotDeleted(_) | StorageEvent.SnapshotNotDeleted(_, _) |
          StorageEvent.CheckpointNotDeleted(_, _) =>
        IO.unit

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
  ): StateBacking[IO, MirrorState] =
    checkpoints.fold(StateBacking.none[IO, MirrorState])(new UtxoBacking[IO, MirrorState](store, _))

  /** The node configuration and topology the options point to, and the options completed from them:
    * the peers of the topology follow the `--peer` ones, and, with `--config`, the socket, database
    * and mempool capacity the file names are the defaults of the flags.
    */
  private[node] def configured(
      options: Options
  ): Either[ConfigError, (Options, NodeSetup, Option[Topology])] =
    for
      setup <- nodeSetup(options)
      read <- readTopology(options, setup)
      done <- completed(options, setup, read.map(_.topology))
    yield done

  /** The node configuration and genesis the options point to. */
  private[node] def nodeSetup(options: Options): Either[ConfigError, NodeSetup] =
    options.configFile match
      case Some(file) => NodeSetup.fromConfigFile(options.referenceBuild)(file, options.genesisDir)
      case None =>
        options.genesisDir
          .toRight(ConfigError(ConfigProblem.RequiredOneOf(Set("--config", "--genesis-dir"))))
          .flatMap(NodeSetup.fromDirectory(options.referenceBuild))

  /** The top-level keys of the configuration the node does not read, which it reports at start for
    * a genesis directory's configuration (`--genesis-dir` alone), its own way to be configured. A
    * `--config` file is read as cardano-node 11.1.3 reads it, which reports none.
    */
  private[node] def ignoredKeysReported(
      options: Options,
      setup: NodeSetup
  ): Option[cats.data.NonEmptyList[String]] =
    options.configFile.fold(cats.data.NonEmptyList.fromList(setup.config.ignoredKeys))(_ => None)

  /** The node-to-client socket path: `--local-socket`, else, with `--config`, the file's
    * `SocketPath`, as it was written (the reference's `ncSocketPath`, not normalised).
    */
  private[node] def socketPath(options: Options, setup: NodeSetup): Option[String] =
    options.localSocket.orElse(
      Option.when(options.configFile.isDefined)(setup.config.settings.socketPath).flatten
    )

  /** The options completed from the configuration `setup` and the `topology` read from the file
    * they point to ([[readTopology]]).
    */
  private[node] def completed(
      options: Options,
      setup: NodeSetup,
      topology: Option[Topology]
  ): Either[ConfigError, (Options, NodeSetup, Option[Topology])] =
    for
      _ <- outboundLimitFits(options, setup)
      (roots, snapshot) <- topologyRoots(options, setup, topology)
      settings = setup.config.settings
      fromFile = options.configFile.isDefined
      peers = (options.peers ++ topology.toList.flatMap(_.peers)).distinct
    yield (
      options.copy(
        peers = peers,
        roots = Some(roots),
        snapshot = snapshot,
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

  /** The network configuration in force, the reference's variables (cardano-node 11.1.3 `Run.hs`
    * 367-372): the topology's roots with the snapshot file they name, and the peer snapshot written
    * last.
    */
  private[node] final case class NetworkConfig(
      roots: PeerNetwork.Roots,
      snapshot: Option[LedgerPeerSnapshot[LedgerRelay]]
  )

  /** How a SIGHUP's reload of the network configuration ended ([[reloadNetwork]]). Where it stops
    * short, the reference's handler ends on an exception and does nothing after it.
    */
  private[node] enum NetworkReload:
    /** It got to its end. */
    case Completed

    /** The topology could not be read or used: nothing changed. */
    case TopologyRefused

    /** The new topology is installed and the snapshot refused: the snapshot in force stays. */
    case SnapshotRefused

  /** A SIGHUP's reload of the network configuration, as the reference's handlers do it
    * (cardano-node 11.1.3, `cardano-node/src/Cardano/Node/Run.hs` 419-430 before consensus has
    * initialised, 603-612 after): the topology read again and installed
    * (`updateTopologyConfiguration`, 673-703), then, on its own, the peer snapshot it names
    * (`updateLedgerPeerSnapshot` in `phase`, 705-757). `network` holds the configuration in force,
    * the reference's variables (367-372); `install` hands the network a new topology, and `write` a
    * new snapshot.
    *
    *   - A topology that cannot be read or used is logged and changes nothing, and the snapshot is
    *     not read again: the reference's `readTopologyFileOrError` calls `error`, which the `try`
    *     for a `FatalError` there does not catch (684-689; checked by running, 2026-11-17). A
    *     `peerSnapshotFile` the JDK cannot make a path of is refused the same way (#953).
    *   - The new topology is installed, once, before the snapshot is read, as the reference writes
    *     the topology's variables (697-702) before it reads the file. Then the snapshot is read
    *     again ([[PeerSnapshot.use]]): what was read is said ([[snapshotSaid]]), and the snapshot
    *     it gives is written, `None` included, whether it changed or not (757); a refusal (any in
    *     [[SnapshotPhase.Startup]], one older than `useLedgerAfterSlot` in
    *     [[SnapshotPhase.Runtime]]) is logged and writes nothing, so the snapshot in force stays
    *     (721, 755).
    *
    * Without `--topology` there is nothing to read again. `options.peers` includes the topology
    * loaded at start-up, so the roots are read with the command line's own peers only (`cliPeers`):
    * a peer removed from the topology does not become permanent.
    */
  private[node] def reloadNetwork(
      options: Options,
      cliPeers: List[SocketAddress[Host]],
      loaded: NodeSetup,
      phase: SnapshotPhase,
      network: Ref[IO, NetworkConfig],
      install: PeerNetwork.Roots => IO[Unit],
      write: Option[LedgerPeerSnapshot[LedgerRelay]] => IO[Unit]
  )(using Log[IO]): IO[NetworkReload] =
    val reread = options.copy(peers = cliPeers)
    def refused(what: String, error: ConfigError, outcome: NetworkReload): IO[NetworkReload] =
      Log[IO]
        .emit[NodeEvent](
          NodeEvent.Library("scarab.topology", Severity.Warning, s"$what: ${error.message}", None)
        )
        .as(outcome)
    def snapshotRead(topology: PeerNetwork.Roots): IO[NetworkReload] =
      network.update(_.copy(roots = topology)) *> install(topology) *>
        IO.blocking(ledgerPeerSnapshot(topology, loaded, phase)).flatMap {
          case Left(refusal) =>
            refused(
              "peer snapshot reload refused, the snapshot in force is kept",
              ConfigError(ConfigProblem.PeerSnapshot(refusal)),
              NetworkReload.SnapshotRefused
            )
          case Right(use) =>
            snapshotSaid(use) *> network.update(_.copy(snapshot = use.snapshot)) *>
              write(use.snapshot).as(NetworkReload.Completed)
        }
    options.topologyFile.fold(IO.pure(NetworkReload.Completed)) { _ =>
      (for
        read <- EitherT(IO.blocking(readTopology(reread, loaded)))
        _ <- EitherT.liftF(noticed(read))
        topology <- EitherT.fromEither[IO](networkRoots(reread, read.map(_.topology)))
      yield topology).foldF(
        refused("topology reload refused", _, NetworkReload.TopologyRefused),
        snapshotRead
      )
    }

  /** What was made of a peer snapshot file just read, said once where it is read, at start-up and
    * at a reload, as the reference traces `LedgerPeerSnapshotLoaded` where it reads the file
    * (`Run.hs` 746-753): `snapshotLoaded`, or `snapshotIgnored` for a file Praos mode could not
    * read. Nothing for no file, or a dropped one, of which the reference says nothing either.
    */
  private def snapshotSaid(use: SnapshotUse)(using Log[IO]): IO[Unit] =
    def said(trace: LedgerPeersTrace) = Log[IO].emit[NodeEvent](NodeEvent.LedgerPeers(trace))
    use match
      case SnapshotUse.Loaded(s)      => said(LedgerPeersTrace.SnapshotLoaded(s.slot, s.pools.size))
      case SnapshotUse.Ignored(error) => said(LedgerPeersTrace.SnapshotIgnored(error))
      case SnapshotUse.Dropped(_) | SnapshotUse.Unused => IO.unit

  /** What the reading of the topology said of it, each as the reference traces it. */
  private def noticed(read: Option[TopologyNotice.Read])(using Log[IO]): IO[Unit] =
    read.toList
      .flatMap(_.notices)
      .traverse_(n => Log[IO].emit[NodeEvent](NodeEvent.NetworkConfigUpdate(n)))

  /** The network configuration at start-up, in the order of the reference's `handleSimpleNode`
    * (cardano-node 11.1.3, `cardano-node/src/Cardano/Node/Run.hs` 349-379): the topology and what
    * its reading says of it, the warning of a producer with peer sharing on (360-366), then the
    * roots and the peer snapshot, whose refusal stops the start. The options completed, as
    * [[completed]] makes them.
    */
  private[node] def networkAtStart(requested: Options, setup: NodeSetup)(using
      Log[IO]
  ): IO[Either[ConfigError, (Options, NodeSetup, Option[Topology])]] =
    EitherT(IO.blocking(readTopology(requested, setup)))
      .semiflatTap(noticed)
      .semiflatTap(_ =>
        Log[IO]
          .emit[NodeEvent](NodeEvent.PeerSharingOnProducer)
          .whenA(
            PeerSharingWarning.due(
              NodeRole.of(requested.forge),
              setup.config.settings.peerSharingEnabled(requested.forge.isDefined)
            )
          )
      )
      .flatMapF(read => IO.blocking(completed(requested, setup, read.map(_.topology))))
      .semiflatTap((options, _, _) => snapshotSaid(options.snapshot))
      .value

  /** The topology file as the reference's `readTopologyFile` reads it (cardano-node 11.1.3,
    * `cardano-node/src/Cardano/Node/Configuration/TopologyP2P.hs` 53-108): parsed and checked
    * ([[Topology.read]]), then what the consensus mode and the node's role call for, and in Genesis
    * mode without its bootstrap peers ([[TopologyNotice.read]]). None without `--topology`.
    */
  private[node] def readTopology(
      options: Options,
      setup: NodeSetup
  ): Either[ConfigError, Option[TopologyNotice.Read]] =
    options.topologyFile.traverse(
      Topology
        .read(_)
        .map(
          TopologyNotice.read(_, setup.config.settings.consensusMode, NodeRole.of(options.forge))
        )
    )

  /** The topology read from the file the options point to and its roots, the snapshot read, before
    * any running network state changes.
    */
  private[node] def topologyRoots(
      options: Options,
      setup: NodeSetup
  ): Either[ConfigError, (Option[Topology], PeerNetwork.Roots)] =
    for
      read <- readTopology(options, setup)
      topology = read.map(_.topology)
      (roots, _) <- topologyRoots(options, setup, topology)
    yield (topology, roots)

  /** The roots of the `topology` read ([[readTopology]]), with the peers of the command line, and
    * the peer snapshot of the file it names, as at start-up: [[networkRoots]], then the snapshot
    * ([[ledgerPeerSnapshot]]), whose refusal stops the start.
    */
  private[node] def topologyRoots(
      options: Options,
      setup: NodeSetup,
      topology: Option[Topology]
  ): Either[ConfigError, (PeerNetwork.Roots, SnapshotUse)] =
    networkRoots(options, topology).flatMap(roots =>
      ledgerPeerSnapshot(roots, setup, SnapshotPhase.Startup)
        .bimap(r => ConfigError(ConfigProblem.PeerSnapshot(r)), roots -> _)
    )

  /** The roots of the `topology` read ([[readTopology]]), with the peers of the command line, and
    * the peer snapshot file it names: what the reference's `updateTopologyConfiguration` writes
    * (`Run.hs` 694-702); the snapshot is not among them. A topology that names no peer at all is
    * not refused, as the reference does not refuse one (its own
    * `testnet-template-topology-empty-p2p.json` is such a file).
    */
  private[node] def networkRoots(
      options: Options,
      topology: Option[Topology]
  ): Either[ConfigError, PeerNetwork.Roots] =
    // `peerSnapshotFile` is relative to the topology file, as the reference reads it. A name the
    // JDK cannot make a path of (NUL, or a character its file-name encoding cannot encode) is a
    // typed refusal, not an exception, so a SIGHUP reload refuses it as any other topology (#953)
    (for
      file <- options.topologyFile
      name <- topology.flatMap(_.peerSnapshotFile)
    yield Either
      .catchOnly[InvalidPathException](file.resolveSibling(name))
      .leftMap(e => ConfigError(ConfigProblem.InvalidPath(name, e)))).sequence
      .map(file => PeerNetwork.roots(options.peers, topology).copy(snapshotFile = file))

  /** The governor's targets: the deadline ones (the defaults of the node's role with the
    * configuration's `TargetNumberOf...`) and the sync ones (`SyncTargetNumberOf...`), or why they
    * are not sane.
    */
  private[node] def governorTargets(
      options: Options,
      setup: NodeSetup
  ): Either[ConfigError, (Targets, Targets)] =
    val defaults = if options.forge.isDefined then Targets.blockProducer else Targets.relay
    (
      setup.config.settings.peerTargets(defaults),
      setup.config.settings.syncTargets(Targets.sync)
    ).tupled

  /** The outbound limit in force, refused below the counted dials the governor uses at once
    * ([[OutboundLimit.Needed]]): its largest established target and its largest established target
    * of big ledger peers. Local roots are left out of the limit, so the topology does not enter it,
    * and a topology reload is never refused for it. Without a limit nothing is checked.
    */
  private[node] def outboundLimitFits(
      options: Options,
      setup: NodeSetup
  ): Either[ConfigError, OutboundLimit] =
    options.outboundLimit(setup.config.settings) match
      case OutboundLimit.Unlimited => Right(OutboundLimit.Unlimited)
      case limit @ OutboundLimit.AtMost(_) =>
        governorTargets(options, setup).flatMap { (deadline, sync) =>
          limit
            .fits(OutboundLimit.Needed.of(deadline, sync, setup.config.settings.consensusMode))
            .leftMap(below =>
              ConfigError(
                ConfigProblem.OutboundLimitBelowTargets(
                  below.limit.value,
                  below.needed.established,
                  below.needed.establishedBig
                )
              )
            )
        }

  /** The peer snapshot of the file `roots` name, as the reference's `updateLedgerPeerSnapshot`
    * reads it in `phase` (`Run.hs` 705-757; [[PeerSnapshot.use]]), or why it is refused: the
    * snapshot in force then stays.
    */
  private[node] def ledgerPeerSnapshot(
      roots: PeerNetwork.Roots,
      setup: NodeSetup,
      phase: SnapshotPhase
  ): Either[PeerSnapshotRefusal, SnapshotUse] =
    PeerSnapshot.use(
      roots.snapshotFile,
      setup.genesis.networkMagic,
      roots.ledger,
      setup.config.settings.consensusMode,
      phase
    )

  /** The native library the JVM is going to load, checked before anything evaluates a script: the
    * BLS12-381 library of Leios certificates and of the own evaluator's BLS12-381 builtins (ADR
    * 0012, ADR 0025, ADR 0027). One built for processor features this one lacks kills the JVM with
    * SIGILL, with no event (ADR 0012). The report is logged; a refusal is a [[NativeLibraryError]].
    */
  private def nativeLibrary(using Handle[IO, NodeError], Log[IO]): IO[Unit] =
    Handle
      .allow[BlstProblem](Blst.verify[IO])
      .rescue(problem =>
        Handle[IO, NodeError]
          .raise[NodeError, BlstReport](NativeLibraryError(NativeLibraryProblem.Bls(problem)))
      )
      .flatMap(report => Log[IO].emit[NodeEvent](NodeEvent.Blst(report)))

  /** A start that fails says why on standard error as well as in the log, as the reference's
    * top-level handler does before it exits with 1 (cardano-node 11.1.3,
    * `cardano-node/src/Cardano/Node/Handlers/TopLevel.hs` 190-195).
    */
  private def failedStart(text: String): IO[Unit] = IO.consoleForIO.errorln(s"scarab: $text")

  /** The node's own address from `--listen`, resolved once before any socket is opened: the
    * listener binds to it, and outbound connections in `InitiatorAndResponder` mode are bound to it
    * ([[NodeAddresses]]). An IPv6 address is refused here, before any bind (an approved deviation,
    * issue 854; [[NodeAddresses.listening]]).
    */
  private def nodeAddress(
      address: SocketAddress[Host]
  ): IO[Either[ConfigError, Listening]] =
    def refused(cause: TcpSocketError) =
      ConfigError(ConfigProblem.ListenAddress(address.toString, cause))
    ConnectionManager.resolve[IO](address).attempt.flatMap {
      case Right(resolved) =>
        IO.pure(
          NodeAddresses
            .listening(resolved)
            .bimap(refused, Listening(resolved, _))
        )
      case Left(e: java.io.IOException) =>
        IO.pure(Left(refused(TcpSocketError.Failed(SocketStep.Resolve(address.host), Errno.of(e)))))
      case Left(other) => IO.raiseError(other)
    }

  /** Where the node listens for node-to-node connections, and its own addresses for the outbound
    * ones.
    */
  private final case class Listening(address: SocketAddress[IpAddress], own: NodeAddresses)

  private def follow(requested: Options)(using
      Handle[IO, NodeError],
      Log[IO],
      Assertions[IO]
  ): IO[ExitCode] =
    def admitted[A](result: Either[ConfigError, A]): IO[A] =
      result.fold(e => Handle[IO, NodeError].raise[NodeError, A](e), IO.pure)
    // the VRF key's permissions, then the credentials: before the sockets, the topology and the
    // database, as the reference's `runNode` reads them before `handleSimpleNode` (`Run.hs` 193-201).
    // The key lock, which the reference does not have, is taken just before them and held until the
    // node ends, so a second instance reads no key file ([[KeyGuard.credentials]])
    def credentials(files: ForgeFiles): Resource[IO, PoolCredentials] =
      KeyGuard.credentials[IO](
        files.vrfSkey,
        files.kesSkey,
        files.opCert,
        requested.keyLock,
        files.blsSkey
      )
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
    // the start once the setup's names are taken
    def startFrom(setup: NodeSetup): IO[ExitCode] = for
      layout <- referenceLayout(
        requested.referenceBuild,
        setup.config.settings.experimentalHardForksEnabled
      )
      code <- requested.forge.traverse(credentials).use { pool =>
        for
          listening <- requested.listen.traverse(nodeAddress(_).flatMap(admitted))
          removal <- removedStale(setup)
          code <- removal.fold(
            error =>
              Log[IO].emit[NodeEvent](NodeEvent.SocketConfigError(error)) *>
                failedStart(error.message).as(ExitCode.Error),
            _ =>
              networkAtStart(requested, setup)
                .flatMap(admitted)
                .flatMap((options, setup, topology) =>
                  // the endorser-block store a Leios build's rules read, in the database directory
                  // the run opens (its `--db` or the configuration's), before any store opens
                  admitted(
                    LeiosStorage.follows(
                      options.referenceBuild,
                      setup.config.settings.leios.database,
                      options.db.map(fs2.io.file.Path.fromNioPath)
                    )
                  ).flatMap(leiosStore =>
                    started(
                      options,
                      setup,
                      topology,
                      requested.peers,
                      pool,
                      layout,
                      listening,
                      leiosStore
                    )
                  )
                )
          )
        yield code
      }
    yield code
    for
      _ <- nativeLibrary
      // the configuration, then the VRF key's permissions, then the genesis and the credentials
      // (`Run.hs` 183-201)
      _ <- requested.configFile.traverse_(file =>
        IO.blocking(NodeSetup.readConfig(requested.referenceBuild)(file)).flatMap(admitted)
      )
      _ <- requested.forge.traverse_(files =>
        KeyGuard.permissions[IO](files.vrfSkey, Log.sink[ForgeEvents, ForgeEvent, IO])
      )
      setup <- IO.blocking(nodeSetup(requested)).flatMap(admitted)
      _ <- ignoredKeysReported(requested, setup).traverse_(keys =>
        Log[IO].emit[NodeEvent](NodeEvent.ConfigKeysIgnored(keys))
      )
      encoding <- CommandLine.encoding[IO]
      // the file names of the configuration the setup was read from, under the owner's rule for
      // names (#953), before any is used to remove or make a file: the configuration's text is
      // decoded leniently, so a byte that is not UTF-8 reads as U+FFFD. The genesis files it names
      // are only read, and their hashes checked, before this
      code <- NameError.among(setup.config.fileNames, encoding).fold(startFrom(setup))(refused)
    yield code

  /** The reference build the node mirrors, logged with its pins, and a warning when an unstable
    * build runs without `ExperimentalHardForksEnabled`; then the codec of its layout, or the
    * refusal of a build whose rules are not implemented yet, before any store opens.
    */
  private def referenceLayout(build: ReferenceBuild, experimentalHardForksEnabled: Boolean)(using
      Handle[IO, NodeError],
      Log[IO]
  ): IO[ReferenceLayout] =
    Log[IO].emit[NodeEvent](NodeEvent.ReferenceBuildChosen(build)) *>
      Log[IO]
        .emit[NodeEvent](NodeEvent.UnstableReferenceBuild(build))
        .whenA(ReferenceBuildFlag.warns(build, experimentalHardForksEnabled)) *>
      ReferenceBuildFlag
        .follows(build)
        .fold(e => Handle[IO, NodeError].raise[NodeError, ReferenceLayout](e), IO.pure)

  /** The node from its completed options on: the database, the stores and the run. */
  private def started(
      options: Options,
      setup: NodeSetup,
      topology: Option[Topology],
      cliPeers: List[SocketAddress[Host]],
      credentials: Option[PoolCredentials],
      layout: ReferenceLayout,
      listening: Option[Listening],
      leiosStore: LeiosStorage.Plan
  )(using Handle[IO, NodeError], Log[IO], Assertions[IO]): IO[ExitCode] =
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
    // from here a SIGHUP is the reference's initial one until the run installs the full handler
    val hangup = Resource
      .eval(
        (
          Ref.of[IO, Option[NetworkConfig]](None),
          Ref.of[IO, NetworkConfig](
            NetworkConfig(
              options.roots.getOrElse(
                PeerNetwork.Roots(Nil, Set.empty, None, UseLedgerPeers.Disabled)
              ),
              options.snapshot.snapshot
            )
          ),
          Ref.of[IO, ForgeEvents[IO]](Log.sink[ForgeEvents, ForgeEvent, IO])
        ).mapN(StartupHangup.apply)
      )
      .flatTap(h => Signals.hangup[IO](startupHangup(options, cliPeers, setup, h)))
    // the endorser-block store once the database is ours and of this network, as the reference
    // opens its LeiosDB once the database's marker is checked (`Run.hs:372-402` at b004fe0c); opened
    // first and released last, after the chain database, as the reference closes it after the run
    val leios = LeiosStoreLocks
      .resource[IO]
      .flatMap(LeiosStorage.resource[IO](leiosStore, _))
    (
      hangup,
      ownership *> compatible *> (
        leios,
        utxoBacking(options),
        VerificationCache.resource[IO](),
        Scheduling.resource[IO](options.cpuThreads),
        Supervisor[IO](await = false),
        // the node's own fibers that write endorser-block answers and claim them: released before
        // the store, a write in progress ends first, as it cannot be cancelled
        Supervisor[IO](await = false)
      ).tupled
    ).tupled.use {
      case (hup, (leiosStore, (store, checkpoints), cache, scheduling, supervisor, settling)) =>
        followWith(
          options,
          cliPeers,
          setup,
          topology,
          store,
          leiosStore,
          backing(store, checkpoints),
          cache,
          scheduling,
          StallDiagnostics.abandoning(supervisor),
          settling,
          credentials,
          layout,
          listening,
          hup
        )
    }

  /** What every phase of a run reads: the completed options and setup, and what was opened before
    * the run ([[started]]). `setup` is `loaded` with the verification cache as its verdicts.
    */
  private final case class Startup(
      options: Options,
      cliPeers: List[SocketAddress[Host]],
      loaded: NodeSetup,
      setup: NodeSetup,
      topology: Option[Topology],
      store: UtxoStore[IO],
      leios: Option[LeiosStore[IO]],
      cache: VerificationCache,
      scheduling: Scheduling[IO],
      settling: Supervisor[IO],
      credentials: Option[PoolCredentials],
      listening: Option[Listening],
      hangup: StartupHangup,
      layout: ReferenceLayout
  ):
    def genesis: xyz.kd5ujc.scarab.ledger.ShelleyGenesis = setup.genesis

    /** The Plutus backend, one for the run: the rules, the mempool and the forger evaluate and
      * admit scripts with it, and with nothing else (ADR 0027).
      */
    val plutus: PlutusBackend = PlutusBackend.default

  /** What a SIGHUP does before the node runs its forger and its network: the reference's initial
    * handler (cardano-node 11.1.3, `cardano-node/src/Cardano/Node/Run.hs` 412-435), installed
    * before the chain database is opened and replayed. It reads the network configuration again as
    * at start-up ([[reloadNetwork]], [[SnapshotPhase.Startup]]) into `network`, the configuration
    * in force, which both handlers share, and the network starts from what it read (`pending`,
    * until the network takes it); it does not reload the forging credentials, which only the full
    * handler does once consensus has started, and says so (`BlockForgingUpdate NotEffective`, to
    * `events`: the log, and the metrics once they exist). Without it a SIGHUP would end the JVM.
    */
  private[node] final case class StartupHangup(
      pending: Ref[IO, Option[NetworkConfig]],
      network: Ref[IO, NetworkConfig],
      events: Ref[IO, ForgeEvents[IO]]
  )

  /** The initial handler's work (`Run.hs` 419-432): the network configuration read again, then
    * `BlockForgingUpdate NotEffective`, which a reload that stops short does not reach, as an
    * exception ends the reference's handler before it.
    */
  private[node] def startupHangup(
      options: Options,
      cliPeers: List[SocketAddress[Host]],
      setup: NodeSetup,
      h: StartupHangup
  )(using Log[IO]): IO[Unit] =
    val handedOn = h.network.get.flatMap(c => h.pending.set(Some(c)))
    reloadNetwork(
      options,
      cliPeers,
      setup,
      SnapshotPhase.Startup,
      h.network,
      _ => handedOn,
      _ => handedOn
    )
      .flatMap {
        case NetworkReload.Completed =>
          h.events.get.flatMap(
            _.emit(ForgeEvent.BlockForgingUpdate(BlockForgingState.NotEffective))
          )
        case NetworkReload.TopologyRefused | NetworkReload.SnapshotRefused => IO.unit
      }

  /** What the run measures and reports with, made before any store is open. */
  private final case class Instruments(
      metrics: Metrics[IO],
      measured: EventMetrics[IO],
      stall: StallDiagnostics.Watch[IO],
      faulted: Deferred[IO, Fatal],
      fatalSink: Fatal => IO[Unit],
      watchdog: StallWatchdog[IO],
      collectors: Collectors.Registry[IO],
      eraStarts: Ref[IO, Map[Era, BigInt]],
      followerEvents: FollowerEvents[IO],
      headersLogged: Ref[IO, Long],
      rounds: Ref[IO, (Long, Long, Long, Long, Int)]
  )

  /** The block rules, and the rule configuration the signature pre-verification reads. */
  private final case class Rules(
      praosConfig: Ref[IO, consensus.PraosConfig],
      warm: Peer.Prepare[IO],
      rules: MirrorRules[IO]
  )

  /** The selected chain once the stores are open, with what serves and extends it, and the
    * mempool's watcher on its tip (`synchronization`), run beside the selection worker.
    */
  private final case class Chain(
      ref: Ref[IO, VolatileChain[MirrorState]],
      server: ChainServer[IO],
      txClock: TxClock[MirrorState],
      mempoolEnvironment: Ask[IO, MempoolEnv],
      mempool: Mempool[IO],
      synchronization: Resource[IO, Unit],
      chainGate: Mutex[IO],
      follower: Follower[IO, MirrorState],
      stateful: Stateful[IO, VolatileChain[MirrorState]]
  )

  /** How the sessions check headers, and what they observe. */
  private final case class Headers(
      nodeChain: HeaderCheck.Grafting[IO],
      check: Option[HeaderCheck[IO]],
      observePeer: Peer.Observation => IO[Unit],
      slotClock: SlotClock[IO],
      rules: NodeHeaderRules[MirrorState]
  )

  /** The blocks fetched ahead, shared by every session, the Genesis runtime when it runs, and the
    * endorser-block store whose completions chain selection reads ([[LeiosAcquired.notifying]]):
    * the store its writers use, and the Leios build's endorser-block fetch over it.
    */
  private final case class Supply(
      blockPool: BlockPool[IO],
      genesisRuntime: Option[GenesisRuntime[IO]],
      leios: Option[LeiosStore[IO]],
      leiosFetch: Option[LeiosFetchNode[IO]],
      leiosVotes: Option[LeiosVotes[IO]]
  )

  /** The connections: the manager, the inbound governor, the outbound network and the listener. */
  private final case class Connections(
      manager: ConnectionManager[IO],
      governor: InboundGovernor[IO],
      network: Resource[IO, PeerNetwork.Running[IO, PeerNetwork.Addr]],
      relay: Raise[IO, DiffusionFailure] => Resource[IO, IO[DiffusionFailure]]
  )

  /** The services beside the network: the node-to-client server, the forger and the watchers. */
  private final case class Services(
      local: Raise[IO, DiffusionFailure] => Resource[IO, IO[DiffusionFailure]],
      forging: Option[Forger[IO, MirrorState]],
      production: Resource[IO, Unit],
      watching: Resource[IO, Unit],
      reporting: Resource[IO, Unit]
  )

  private def followWith(
      options: Options,
      cliPeers: List[SocketAddress[Host]],
      loaded: NodeSetup,
      topology: Option[Topology],
      store: UtxoStore[IO],
      leios: Option[LeiosStore[IO]],
      backing: StateBacking[IO, MirrorState],
      cache: VerificationCache,
      scheduling: Scheduling[IO],
      background: IO[Unit] => IO[Unit],
      settling: Supervisor[IO],
      credentials: Option[PoolCredentials],
      layout: ReferenceLayout,
      listening: Option[Listening],
      hangup: StartupHangup
  )(using
      handle: Handle[IO, NodeError],
      log: Log[IO],
      assertions: Assertions[IO]
  ): IO[ExitCode] =
    val setup = loaded.copy(praos = loaded.praos.copy(verdicts = cache))
    val run =
      Startup(
        options,
        cliPeers,
        loaded,
        setup,
        topology,
        store,
        leios,
        cache,
        scheduling,
        settling,
        credentials,
        listening,
        hangup,
        layout
      )
    instrumented(run, background).flatTap(i => hangup.events.set(forgeEvents(i.measured))).flatMap {
      instruments =>
        given Metrics[IO] = instruments.metrics
        ruled(run).flatMap { rules =>
          // The mirror's environment as the snapshots ask for it: the configuration, and the eras this
          // node has seen begin so far.
          given Ask[IO, ConsensusMirrorEnv] = setup.mirrorAsk(instruments.eraStarts.get)
          // A restored state no chain of the build reaches is refused (`StateAdmission`).
          given SnapshotFormat[IO, MirrorState] =
            SnapshotV10
              .format[IO](options.snapshotCheck, layout)
              .admitting(StateAdmission.restored(layout.build))
          // What a scrape reads besides the counters the events keep: each source answers for itself
          // and is added when it exists, so the endpoint can listen before the stores are opened (a
          // collector that is not there yet leaves its gauges absent).
          val exposed = options.metricsListen.fold(Resource.unit[IO])(address =>
            MetricsServer.resource[IO](address, instruments.collectors.scrape).void
          )
          exposed
            .surround(
              storage(run, instruments, rules, backing).use(following(run, instruments, rules, _))
            )
            .map(exitCode)
        }
    }

  /** The run's instruments, in the order the run makes them. */
  private def instrumented(run: Startup, background: IO[Unit] => IO[Unit])(using
      Log[IO],
      Assertions[IO]
  ): IO[Instruments] =
    import run.{options, scheduling}
    def logged(event: NodeEvent): IO[Unit] = Log[IO].emit(event)
    // The measurements: every event goes to the log and to the metrics ([[EventSink]]).
    Metrics.create[IO](Catalogue.all).flatMap { metrics =>
      given Metrics[IO] = metrics
      for
        headersLogged <- Ref.of[IO, Long](0L)
        rounds <- Ref.of[IO, (Long, Long, Long, Long, Int)]((0L, 0L, 0L, 0L, 0))
        eraStarts <- Ref.of[IO, Map[xyz.kd5ujc.scarab.protocol.Era, BigInt]](Map.empty)
        measured <- EventMetrics.create[IO]
        // a follower that stops extending its chain while a peer has more says so (issue 130)
        // (and, from issue 371, where its report reads from once the block pool exists)
        stall <- StallDiagnostics.Watch.create[IO](options.stallDump, e => logged(e), background)
        // a session that ends in a way no peer policy handles stops the node ([[Fatal]]): the event
        // is logged where it happens, and the run ends like a signal does (the stores close, the
        // shutdown snapshot is taken) with an exit code of its own
        faulted <- Deferred[IO, Fatal]
        fatalReporter <- faultReporter(faulted, fault => logged(NodeEvent.Faulted(fault)))
        watchdog <- StallWatchdog.create[IO](
          BigInt(0),
          options.stallMinutes.minutes,
          options.stallExitAfter,
          stall.react(measured.stall)
        )
        collectors <- Collectors.Registry.create[IO]
        _ <- collectors.add(Collectors.jvm[IO])
        _ <- collectors.add(Collectors.scheduling(scheduling.stats))
      yield
        val followerLog = Log.follower[IO](Some(scheduling.stats))
        val events = new FollowerEvents[IO]:
          def emit(event: FollowerEvent): IO[Unit] =
            (event match
              case FollowerEvent.EraEntered(_, to, _, epoch) =>
                eraStarts.update(_.updatedWith(to)(_.orElse(Some(epoch))))
              case FollowerEvent.Extended(block, _, peerTip, _) =>
                peerTip.traverse_(t => watchdog.announced(t.blockNo.value)) *>
                  watchdog.extended(block.blockNo)
              case _ => IO.unit
            ) *> followerLog.emit(event)
        Instruments(
          metrics,
          measured,
          stall,
          faulted,
          (fault: Fatal) => fatalReporter.report(fault),
          watchdog,
          collectors,
          eraStarts,
          events.alongside(measured.follower),
          headersLogged,
          rounds
        )
    }

  /** The block rules: what each applied block also does, and where its scripts run. */
  private def ruled(run: Startup)(using Log[IO]): IO[Rules] =
    import run.{cache, options, scheduling, setup, store}
    def logged(event: NodeEvent): IO[Unit] = Log[IO].emit(event)
    for
      blocks <- Ref.of[IO, Long](0L)
      // The rule configuration the signature pre-verification uses: a chain with a derived Byron
      // span learns its first Shelley epoch from the state.
      praosConfig <- Ref.of[IO, consensus.PraosConfig](setup.praos)
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
      afterBlock = (header: Header, before: MirrorState, s: MirrorState) =>
        (storageError: cats.mtl.Raise[IO, xyz.kd5ujc.scarab.ledger.StorageError]) ?=>
          IO.delay(cache.noteEpochNonce(s.ledgerEpoch, s.protocol.epochNonce)) *>
            dumper.traverse_(_.afterBlock(header, before, s)) *>
            praosConfig
              .update(c => c.copy(geometry = s.geometry(c.geometry)))
              .whenA(s.consensus.byron.firstShelleyEpoch.isDefined) *>
            blocks.updateAndGet(_ + 1).flatMap { n =>
              logged(NodeEvent.VerificationCache(cache.summary)).whenA(n % 20000 == 0)
            }
      parallelPlutus <- ParallelPlutus.create[IO](options.plutusWorkers)
      committees <- Ref.of[IO, Option[
        (
            (xyz.kd5ujc.scarab.ledger.state.LeiosCommittee, xyz.kd5ujc.scarab.codec.Rational),
            xyz.kd5ujc.scarab.consensus.leios.TipCommittee
        )
      ]](None)
    yield Rules(
      praosConfig,
      raw => praosConfig.get.flatMap(cache.warm[IO](raw, _)),
      MirrorRules[IO](
        setup.praos,
        setup.globals,
        setup.byronRules,
        setup.handOver,
        setup.byronHistory,
        run.plutus,
        phase2Observer = cases.map(c => c.observe),
        utxoStore = Some(store),
        afterBlock = Some(afterBlock),
        compute = Some(scheduling.compute),
        parallelPlutus = Some(parallelPlutus),
        leios = run.leios.map(MirrorRules.Leios(_, committees))
      )
    )

  /** The chain database, or without `--db` the state the node starts from. */
  private def storage(
      run: Startup,
      instruments: Instruments,
      rules: Rules,
      backing: StateBacking[IO, MirrorState]
  )(using
      SnapshotFormat[IO, MirrorState],
      Handle[IO, NodeError],
      Log[IO],
      Assertions[IO]
  ): Resource[IO, Storage[MirrorState]] =
    import run.{genesis, options, scheduling, setup}
    val k = setup.chainStoreK.blocks
    val storageEvents =
      Log
        .sink[StorageEvents, StorageEvent, IO]
        .alongside(instruments.measured.storage)
        .alongside(snapshots)
    options.db.fold(
      Resource.pure[IO, Storage[MirrorState]](
        Storage(
          None,
          setup.mirrorStart,
          Vector.empty,
          Some(ChainPersistence.backingOnly[IO, MirrorState](backing)),
          None,
          None,
          xyz.kd5ujc.scarab.consensus.AcquiredLeiosEbs.empty
        )
      )
    ) { dir =>
      val policy = options.snapshotEvery match
        case None                            => setup.defaultSnapshotPolicy
        case Some(SnapshotInterval.Slots(n)) => SnapshotPolicy.EverySlots(BigInt(n))
        case Some(SnapshotInterval.Epochs(n)) =>
          SnapshotPolicy.EverySlots(BigInt(n) * genesis.geometry.epochLength)
      // the run's endorser-block store goes to the chain database, whose copies promote in it and
      // whose collections collect it, as the reference's ChainDB background drives its LeiosDB
      // (`copyToImmutableDB`, `garbageCollectBlocks`, `ChainDB/Impl/Background.hs:230-292,
      // 547-559` at ouroboros-consensus 2416493b)
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
          rules.rules,
          setup.mirrorStart,
          storageEvents,
          backing,
          rules.warm,
          Some(scheduling.compute),
          Some(SnapshotMigration.toCurrent[IO]),
          Some(TransitionRecount.reopen[IO](genesis.geometry)),
          run.leios
        )(ReceivedBlockRecovery.startUp(genesis.geometry.byron.epochSlots))
        // a failed collection of the received log stops the node, as the failure of the
        // reference's collection ends the thread it is linked to (consensus 82ecba329
        // Storage/ChainDB/Impl/Background.hs:116-121,140-142)
        .flatTap(r =>
          r.collectionsStopped.flatMap {
            case ChainStorage.CollectionStopped.Failed(error) =>
              instruments.fatalSink(Fatal.Storage(error))
            case ChainStorage.CollectionStopped.Defect(cause) =>
              instruments.fatalSink(Fatal.Defect(cause))
          }.background
        )
        .evalMap { r =>
          // the acquired endorser blocks at the open, before the volatile chain is replayed, as the
          // ChainDB seeds them before its initial chain selection (`ChainDB/Impl.hs:236-259` at
          // ouroboros-consensus 2416493b): the replay parks what that selection hides
          run.leios
            .fold(IO.pure(xyz.kd5ujc.scarab.consensus.AcquiredLeiosEbs.empty))(
              LeiosAcquired.scanned[IO](_, r.anchor.map(_.slot))
            )
            .flatMap(acquired =>
              ChainStorage
                .resume[IO, MirrorState](
                  r,
                  k,
                  setup.origin,
                  rules.rules,
                  genesis.geometry.byron.epochSlots,
                  storageEvents,
                  rules.warm,
                  acquired,
                  Some(scheduling.compute)
                )
                .map(resumed =>
                  Storage(
                    r.anchor,
                    r.state,
                    resumed,
                    Some(r.persistence),
                    Some(r.store),
                    Some(r.received),
                    acquired
                  )
                )
            )
        }
    }

  /** The run once the stores are open and the stored blocks applied, until it ends. */
  private def following(
      run: Startup,
      instruments: Instruments,
      rules: Rules,
      opened: Storage[MirrorState]
  )(using Handle[IO, NodeError], Log[IO], Metrics[IO], Assertions[IO]): IO[RunEnd] =
    import run.{genesis, setup}
    // A chain whose Byron span is derived learns the first Shelley epoch when it enters
    // Shelley; a restored state past that point knows it, a fresh sync learns it on the way
    // (the forger still needs a restart after that, see docs/compatibility.md).
    val globals =
      opened.state.consensus.byron.firstShelleyEpoch.fold(setup.globals)(e =>
        setup.globals.copy(firstEpoch = e)
      )
    selected(run, instruments, rules, opened, globals).flatMap { chain =>
      given Stateful[IO, VolatileChain[MirrorState]] = chain.stateful
      given Ask[IO, consensus.ChainSelection.Local] = new Ask[
        IO,
        consensus.ChainSelection.Local
      ]:
        val applicative: cats.Applicative[IO] = cats.Applicative[IO]
        def ask[E2 >: consensus.ChainSelection.Local]: IO[E2] =
          chain.follower.fetchSelection.widen[E2]
      given Tell[IO, xyz.kd5ujc.scarab.ledger.GenesisInternalProblem] =
        new Tell[IO, xyz.kd5ujc.scarab.ledger.GenesisInternalProblem]:
          val functor: cats.Functor[IO] = cats.Functor[IO]
          def tell(problem: xyz.kd5ujc.scarab.ledger.GenesisInternalProblem): IO[Unit] =
            instruments.fatalSink(Fatal.Internal(problem))
      val headers = headerChecks(run, instruments, rules, chain)
      for
        supply <- supplied(
          run,
          instruments,
          rules,
          chain,
          headers,
          opened.received,
          opened.acquired
        )
        connections <- connected(run, instruments, chain, headers, supply, opened.immutable)
        services <- served(
          run,
          instruments,
          chain,
          globals,
          opened.state.geometry(genesis.geometry),
          supply.leiosFetch
        )
        ended <- ran(run, instruments, rules, chain, supply, connections, services, opened.received)
      yield ended
    }

  /** The selected chain: the volatile chain from the stored blocks, its server, the mempool at its
    * tip and the follower that extends it.
    */
  private def selected(
      run: Startup,
      instruments: Instruments,
      rules: Rules,
      opened: Storage[MirrorState],
      globals: xyz.kd5ujc.scarab.ledger.LedgerGlobals
  )(using Handle[IO, NodeError], Log[IO], Metrics[IO], Assertions[IO]): IO[Chain] =
    import run.{genesis, options, scheduling, setup, store}
    import instruments.{collectors, fatalSink, measured}
    import opened.{anchor, persistence, resumed, state}
    def fail[A](e: NodeError): IO[A] = Handle[IO, NodeError].raise(e)
    val geometry = state.geometry(genesis.geometry)
    val k = setup.chainStoreK.blocks
    for
      // Older databases have only the selected-chain log. Preserve those known originals
      // without inventing downloaded fork history that the old writer never recorded.
      _ <- opened.received.traverse_(received =>
        restoreReceived(
          received,
          resumed.map(block => block.ref -> block.raw),
          genesis.geometry.byron.epochSlots
        )
      )
      // the stores are open and the stored blocks applied
      _ <- measured.phase(PhaseLabel.Following)
      _ <- rules.praosConfig.update(c => c.copy(geometry = state.geometry(c.geometry)))
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
      _ <- opened.received.traverse_(r => collectors.add(Collectors.received(r)))
      server <- ChainServer.open[IO](
        opened.immutable,
        anchor,
        ChainServer.headerOfBlock(genesis.geometry.byron.epochSlots),
        fatalSink,
        ChainServer.Held(opened.received, setup.origin)
      )
      _ <- resumed.traverse_(r =>
        server
          .persistence[MirrorState]
          .extended(r.ref, r.raw, Vector.empty, Anchor(anchor, state))
      )
      // the transactions' time translation, placed by the node's era history parameters: the
      // mempool and the forger use it as the block rules translate time
      txClock = setup.txClock
      mempoolEnvironment = new cats.mtl.Ask[IO, MempoolEnv]:
        val applicative = summon[cats.Applicative[IO]]
        def ask[E >: MempoolEnv]: IO[E] =
          ref.get.map { chain =>
            val tip = chain.tipRef.fold(BigInt(0))(_.slot)
            // the clock of a transaction of the pool's era, in a block of the slot after the tip
            val clock = (era: Era) => txClock.at(chain.state, era, tip + 1)
            MempoolEnv(
              globals,
              chain.state.geometry(genesis.geometry),
              run.plutus.interruptible,
              run.plutus.decoder,
              clock = Some(clock)
            ): E
          }
      mempoolLedger <- scheduling.compute
        .within(IO.delay {
          val tip = resumed.lastOption.map(_.ref).orElse(anchor)
          val current = resumed.lastOption.fold(state)(_.state)
          PoolLedger
            .at(
              current,
              tip,
              MempoolEnv(
                globals,
                current.geometry(genesis.geometry),
                run.plutus.evaluator,
                run.plutus.decoder
              ),
              setup.praos.forks.transition(genesis.geometry.k)
            )
            .left
            .map(_.stop(tip.fold(BigInt(0))(_.slot + 1)))
        })
        .flatMap(_.fold(fail, IO.pure))
      mempool <- Mempool.create[IO](
        mempoolLedger,
        resumed.lastOption.map(_.ref).orElse(anchor).fold(BigInt(-1))(_.slot),
        MempoolEnv(globals, geometry, run.plutus.interruptible, run.plutus.decoder),
        options.mempoolBytes,
        Log.mempool[IO].alongside(measured.mempool),
        utxoStore = Some(store),
        environment = Some(mempoolEnvironment),
        tipHash = resumed.lastOption.map(_.ref).orElse(anchor).map(_.hash),
        timeouts = Some(setup.config.settings.mempoolTimeouts)
      )
      _ <- collectors.add(Collectors.mempool(mempool))
      served = server.persistence[MirrorState]
      chainGate <- Mutex[IO]
      follower <- Follower.create[IO, MirrorState](
        MeteredRules[IO, MirrorState](rules.rules),
        instruments.followerEvents,
        genesis.geometry,
        NodeRules.observe(genesis.geometry),
        Some(persistence.fold(served)(Persistence.both(_, served))),
        epochOf = Some((s, slot) => s.geometry(genesis.geometry).epochOf(slot)),
        initialViews = resumed.map(r => r.ref.hash -> r.view).toMap,
        compute = Some(scheduling.compute),
        chainGate = Some(chainGate)
      )
      // the pool was made at this chain's tip: the chain it was last revalidated at
      synced <- ref.get.flatMap(c =>
        Ref.of[IO, Vector[Option[xyz.kd5ujc.scarab.codec.Bytes]]](
          c.anchor.block.map(_.hash) +: c.blocks.map(b => Some(b._1.hash))
        )
      )
      synchronization = MempoolSync.resource[IO, VolatileChain[MirrorState]](
        mempool,
        follower.committedChain,
        _.tip,
        mempoolTarget(
          scheduling,
          genesis.geometry.byron.epochSlots,
          setup.praos.forks.transition(genesis.geometry.k),
          server.volatileBlock,
          synced
        )(using mempoolEnvironment),
        fatalSink
      )
    yield Chain(
      ref,
      server,
      txClock,
      mempoolEnvironment,
      mempool,
      synchronization,
      chainGate,
      follower,
      summon[Stateful[IO, VolatileChain[MirrorState]]]
    )

  /** The sessions' header checks and what they observe of each peer. */
  private def headerChecks(
      run: Startup,
      instruments: Instruments,
      rules: Rules,
      chain: Chain
  )(using
      Log[IO],
      Stateful[IO, VolatileChain[MirrorState]],
      Ask[IO, consensus.ChainSelection.Local],
      Tell[IO, xyz.kd5ujc.scarab.ledger.GenesisInternalProblem]
  ): Headers =
    import run.{cache, genesis, options, scheduling, setup}
    import instruments.{headersLogged, measured, rounds}
    def logged(event: NodeEvent): IO[Unit] = Log[IO].emit(event)
    // header-first validation (G6): signatures are verified through the cache's keeping
    // view, so the block's own validation does not repeat them. Every live session also
    // holds its headers to the node's clock (AUD-01, #153), with or without header-first.
    val slotClock = SlotClock.system[IO](chain.ref.get.map(_.state.geometry(genesis.geometry)))
    val headerRules = {
      given HeaderTimeRead[MirrorState] = HeaderTimeRead.mirror(setup.byronHistory)
      NodeHeaderRules(
        setup.praos.copy(verdicts = cache.forHeaders),
        setup.globals,
        setup.byronRules,
        setup.handOver
      )
    }
    // the node's own check: a Genesis node is built with it, as its gates can graft
    val nodeChain = HeaderClock.grafting[IO](
      slotClock,
      HeaderCheck.forNodeChain[IO, MirrorState](
        headerRules,
        scheduling.compute,
        setup.retainedHeaders,
        warm = Some((headers, state) =>
          state.protocol.traverse_(protocol =>
            rules.praosConfig.get.flatMap(cache.warmHeaders[IO](headers, protocol, _))
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
        changes = Some(chain.follower.headerChanges)
      )
    )
    val headerCheck = Some(
      if options.headerFirst || setup.config.settings.consensusMode ==
          xyz.kd5ujc.scarab.network.selection.ConsensusMode.GenesisMode
      then nodeChain
      else HeaderClock.clocked[IO](slotClock, HeaderCheck.none[IO])
    )
    // how long the ChainSync pipeline's rounds take (D3), summarised every 200 rounds
    val observeRounds = (requested: Int, received: Int, millis: Long) =>
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
    val observePeer = (o: Peer.Observation) =>
      measured.peer(o) *> (o match
        case Peer.Observation.Fetched(millis) =>
          logged(NodeEvent.SlowFetch(millis)).whenA(millis >= 250)
        case Peer.Observation.Round(requested, received, millis) =>
          observeRounds(requested, received, millis)
        case Peer.Observation.Announced(tip) =>
          instruments.watchdog.announced(tip.blockNo.value) *> chain.follower.announce(tip)
        case Peer.Observation.HeaderReceived | Peer.Observation.HeadersArrived(_, _) |
            Peer.Observation.BlocksArrived(_, _) | Peer.Observation.Intersected(_) =>
          IO.unit)
    Headers(nodeChain, headerCheck, observePeer, slotClock, headerRules)

  /** The blocks fetched ahead, shared by the sessions of all peers (one peer fetches each block),
    * seeded from the received log, and the Genesis runtime in Genesis mode.
    */
  private def supplied(
      run: Startup,
      instruments: Instruments,
      rules: Rules,
      chain: Chain,
      headers: Headers,
      receivedBlocks: Option[ReceivedBlocks[IO]],
      acquired: xyz.kd5ujc.scarab.consensus.AcquiredLeiosEbs
  )(using
      Handle[IO, NodeError],
      Log[IO],
      Metrics[IO]
  ): IO[Supply] =
    import run.{genesis, setup}
    import instruments.{collectors, fatalSink, stall}
    import chain.follower
    for
      fetchSalt <- cats.effect.std.Random.scalaUtilRandom[IO].flatMap(_.nextLong)
      fetchPolicy = BlockPool.FetchPolicy(
        deadlineConcurrency = setup.config.settings.maxConcurrencyDeadline.getOrElse(1),
        bulkConcurrency = setup.config.settings.maxConcurrencyBulkSync.getOrElse(1),
        peerSalt = fetchSalt
      )
      // a block read back from the received log in the calling session's fiber, under a scope of
      // its own: a failure of the file system stops the node, as one of `put` does
      readBack = (
        read: xyz.kd5ujc.scarab.codec.Bytes => cats.mtl.Raise[IO, StorageError] ?=> IO[
          Option[(BlockRef, xyz.kd5ujc.scarab.codec.Bytes)]
        ]
      ) =>
        (hash: xyz.kd5ujc.scarab.codec.Bytes) =>
          Handle
            .allow[StorageError](read(hash).map(found => Right(found.map(_._2))))
            .rescue(error => IO.pure(Left(error)))
            .flatMap {
              case Right(found) => IO.pure(found)
              case Left(error)  => fatalSink(Fatal.Storage(error)) *> IO.never
            }
      blockPool <- receivedBlocks.fold(
        BlockPool.create[IO](fetchPolicy, follower.fetchSelection)
      )(received =>
        BlockPool.create[IO](
          fetchPolicy,
          follower.fetchSelection,
          BlockPool.Recovered[IO](
            received.holds,
            readBack(received.get),
            readBack(received.original),
            received.invalidate
          )
        )
      )
      _ <- stall.reporter.install(
        StallDiagnostics.Sources(
          blockPool.fetchCoordinator,
          blockPool.admissions,
          blockPool.noVerdictBlocks
        )
      ) *> seedReceived(blockPool, receivedBlocks, genesis.geometry.byron.epochSlots) *>
        follower.installFetchCoordinator(blockPool.fetchCoordinator) *>
        follower.installInvalidBlocks(blockPool.invalidBlocks) *>
        collectors.add(Collectors.pool(blockPool)) *>
        // the acquired endorser blocks the chain database opened with, before the first chain
        // selection (`ChainDB/Impl.hs:236-247` at ouroboros-consensus 2416493b)
        blockPool.leios.seed(acquired)
      genesisRuntime <- createGenesisRuntime(
        setup,
        follower,
        blockPool,
        chain.ref.get.map(_.state.geometry(genesis.geometry)),
        headers.nodeChain
      )
      leios = run.leios.map(blockPool.leios.notifying)
      // the fetch logic's state from the store's complete closures at or after the immutable tip,
      // its generator a draw of the node's (`initInternalState`, `NodeKernel.hs:938-951` at
      // ouroboros-consensus 2416493b)
      // a slot's onset since the system start, as `hdrSlotTime` places it
      onset = (slot: BigInt) =>
        headers.slotClock.geometry.map(g =>
          Option(java.time.Duration.between(g.systemStart, g.slotStart(slot)).toNanos.nanos)
        )
      // the immutable tip's slot (`getImmTipSlot`, `NodeKernel.hs:1206-1215` at
      // ouroboros-consensus 2416493b), which the LeiosNotify servers hold offers to the lead from
      immutableTipSlot = chain.ref.get.map(
        _.anchor.block.map(b => xyz.kd5ujc.scarab.protocol.LeiosWord.Word64.clamped(b.slot))
      )
      leiosFetch <- leios.traverse { store =>
        (
          cats.effect.std.Random.scalaUtilRandom[IO].flatMap(_.nextLong),
          chain.ref.get.map(_.anchor.block.map(_.slot).getOrElse(BigInt(0)))
        ).flatMapN { (seed, tip) =>
          // a slot past every `Word64` has no endorser block at or after it, and prunes all
          LeiosFetchNode.create[IO](
            store,
            xyz.kd5ujc.scarab.consensus.leios.StdGen.mk(seed),
            xyz.kd5ujc.scarab.protocol.LeiosWord.Word64.clamped(tip),
            run.settling,
            e => fatalSink(Fatal.Storage(StorageError.LeiosStore(e))),
            cause => fatalSink(Fatal.Defect(cause)),
            onset,
            blockPool.leios.notifications.subscribe,
            immutableTipSlot,
            setup.config.settings.leios.minOfferLead.fold(LeiosMinOfferLead.Default)(lead =>
              LeiosMinOfferLead(xyz.kd5ujc.scarab.protocol.LeiosWord.Word64.clamped(lead.toBigInt))
            )
          )(using summon, LeiosStorage.lifted[IO])
        }
      }
      // the vote state of the Leios build, of the committee of the selection's tip, read for each
      // vote (`newLeiosVoteState`, `NodeKernel.hs:988-991` at ouroboros-consensus 2416493b)
      leiosVotes <- leiosFetch.traverse(_ =>
        LeiosVotes.create[IO](
          chain.ref.get.flatMap(c => rules.rules.committeeOf(c.state)).map(_.getOrElse(Right(None)))
        )
      )
    yield Supply(blockPool, genesisRuntime, leios, leiosFetch, leiosVotes)

  /** The node's connections: the manager, the inbound governor, the outbound peer network and the
    * relay's listener.
    */
  private def connected(
      run: Startup,
      instruments: Instruments,
      chain: Chain,
      headers: Headers,
      supply: Supply,
      immutable: Option[ImmutableStore[IO]]
  )(using
      Handle[IO, NodeError],
      Log[IO],
      Metrics[IO],
      Assertions[IO]
  ): IO[Connections] =
    import run.{genesis, options, scheduling, setup}
    import instruments.{collectors, fatalSink, measured}
    import chain.{follower, mempool, ref, server}
    import supply.{blockPool, genesisRuntime, leiosFetch, leiosVotes}
    def fail[A](e: NodeError): IO[A] = Handle[IO, NodeError].raise(e)
    def logged(event: NodeEvent): IO[Unit] = Log[IO].emit(event)
    val genesisGovernor = genesisRuntime.map(_.governor)
    // the Leios build adds LeiosNotify and LeiosFetch to every connection's hot protocols, where it
    // has an endorser-block store; a release build's connections are as they were
    val bundle = leiosFetch.fold(HotBundle.Release)(_ => HotBundle.Leios)
    val peerSharing = setup.config.settings.peerSharingEnabled(options.forge.isDefined)
    // the connection manager (G2): one connection per peer address, limits, accounting
    val accepted = options.accepted(setup.config.settings)
    val connectionEvents =
      Log.sink[ConnectionEvents, ConnectionEvent, IO].alongside(measured.connection)
    for
      seed <- IO.delay(java.util.concurrent.ThreadLocalRandom.current().nextLong())
      sharing <- PeerSharingRuntime.create[IO](seed)
      // whether the immutable tip can forecast the current slot, which the LeiosNotify clients wait
      // for before each request (`awaitImmTipCanForecastNow`), kept beside the fetch decision
      leiosGate <- LeiosForecastGate.create[IO]
      handlers = ServerHandlers[IO, NtNHeader](
        NtNHeader.codec,
        server.source,
        server.blocks,
        mempool.sink(fatalSink),
        peerSharing = Some(sharing.source),
        leiosFetch = leiosFetch.map(_.responder),
        leiosNotify = (leiosFetch, leiosVotes).mapN((node, votes) => node.notifier(votes.subscribe))
      )
      manager <- ConnectionManager.create[IO](
        ConnectionManager.Limits(
          accepted = accepted,
          outbound = options.outboundLimit(setup.config.settings),
          seed = seed
        ),
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
      // The inbound governor serves our chain on accepted connections and tracks the peer's
      // activity; our clients run only where the outbound governor promotes a peer.
      governor <- InboundGovernor.create[IO](
        manager,
        InboundGovernor.Config(
          genesis.networkMagic,
          NodeToNodeServer.Settings(
            offerPeerSharing = peerSharing,
            maxVersion = setup.config.settings.maxNtnVersion,
            bundle = bundle
          )
        ),
        handlers,
        events = Some(Log.inbound[IO].alongside(measured.inbound)),
        sharing = Some(sharing)
      )
      // The outbound peer network (G1): the governor chooses the peers, moves them between
      // temperatures and replaces the ones that fail; the Genesis State Machine tells it
      // whether the ledger is too old.
      (targets, syncTargets) <- governorTargets(options, setup)
        .fold(e => fail[(Targets, Targets)](e), IO.pure)
      lost <- Reconnects.create[IO](instruments.followerEvents)
    yield
      val chainView = new PeerNetwork.ChainView[IO]:
        def tip = ref.get.map(c => c.tipRef.map(t => (t.slot, t.hash.hashCode.toLong)))
        def geometry = ref.get.map(_.state.geometry(genesis.geometry))
      val marker =
        options.db.fold(GsmRunner.Marker.none[IO])(d => GsmRunner.Marker.file[IO](d.resolve("gsm")))
      // the inbound governor runs beside the server, so dialled duplex connections are registered
      // with it only when the node listens (responder mode)
      val inboundGovernor =
        run.listening.map(_ => sharing.tracked(_, ConnectionState.DataFlow.Duplex, _))
      val hot = OutboundPeers.Hot[IO](
        magic = genesis.networkMagic,
        network = NodeToNode
          .Settings(
            offerPeerSharing = peerSharing,
            maxVersion = setup.config.settings.maxNtnVersion,
            bundle = bundle
          )
          .pipelining(options.chainSyncWindow.high),
        peer = Peer.Settings(
          SocketAddress.Wildcard,
          genesis.networkMagic,
          readAhead = options.readAhead,
          network = NodeToNode.Settings().pipelining(options.chainSyncWindow.high),
          byronEpochSlots = genesis.geometry.byron.epochSlots,
          pipelineWindow = options.chainSyncWindow
        ),
        follow = (peer, track) => scheduling.compute.within(follower.session(peer, track)),
        heightSnapshot = chainSyncHeights,
        headers = headers.check,
        transactions = Some(mempool.source),
        observe = Some(headers.observePeer),
        pool = blockPool,
        versus = candidate => follower.versus(candidate),
        lost = lost,
        fatal = fatalSink,
        serve = Some(handlers),
        genesis = genesisRuntime,
        inboundGovernor = inboundGovernor,
        leios = leiosFetch,
        leiosNotify = (leiosVotes, headers.rules.announcementRules).mapN { (votes, check) =>
          LeiosNotifyContext[IO](
            votes,
            new LeiosNotifyView[IO]:
              def immutableTip =
                ref.get.map(
                  _.anchor.block.map(b =>
                    xyz.kd5ujc.scarab.protocol.LeiosWord.Word64.clamped(b.slot)
                  )
                )
              def onset(header: xyz.kd5ujc.scarab.ledger.LeiosHeader) =
                ref.get.map(c =>
                  headers.rules.slotOnset(c.anchor.state, c.anchor.block, header.slot)
                )
              def validate(header: xyz.kd5ujc.scarab.ledger.LeiosHeader) =
                ref.get.map(c => check(c.anchor.state, c.anchor.block, header))
              def ready = leiosGate.ready
            ,
            headers.slotClock,
            event => logged(NodeEvent.LeiosNotify(event)),
            AnnouncedHeaders(genesis.geometry.byron.epochSlots)
          )
        }
      )
      val emitOutput = (out: Output[PeerNetwork.Addr]) =>
        measured.peerSelection(out) *> (out match
          case Output.EstablishmentFailed(peer, error) =>
            logged(NodeEvent.PeerEstablishmentFailed(peer, error))
          case Output.Traced(trace)      => logged(NodeEvent.PeerSelection(trace))
          case Output.Churned(trace)     => logged(NodeEvent.PeerChurn(trace))
          case Output.LedgerPeers(trace) => logged(NodeEvent.LedgerPeers(trace))
          case Output.LocalRoots(trace)  => logged(NodeEvent.LocalRoots(trace))
          case Output.Counted(c)         => logged(NodeEvent.PeerCounters(c))
          case Output.Connections(c)     => logged(NodeEvent.OutboundConnections(c)))
      // the network starts from the configuration in force, which a SIGHUP during start-up may
      // have read again, as the reference's diffusion reads the variables its initial handler
      // wrote (`Run.hs` 412-435)
      // the Leios fetch decision loop runs as long as the peer network does, reading the slot the
      // node's blockchain time publishes and the closure's bound forecast from the immutable tip
      // (`NodeKernel.hs:580-593` at ouroboros-consensus 2416493b)
      val leiosKernel = leiosFetch.traverse_(node =>
        BlockchainTime
          .ofChain[IO, MirrorState](
            headers.slotClock,
            ref.get,
            chain.txClock,
            problem => fatalSink(Fatal.Internal(problem)),
            cause => fatalSink(Fatal.Defect(cause))
          )
          .flatMap(time =>
            node.kernel[LeiosFetchNode.Unforecast](
              time.currentSlot.map(_.map(xyz.kd5ujc.scarab.protocol.LeiosWord.Word64.clamped)),
              ref.get.map(current =>
                slot => LeiosFetchNode.forecast(headers.rules, current.anchor, slot)
              ),
              blockPool.leios.immutableSlots,
              cause => fatalSink(Fatal.Defect(cause))
            ) *> leiosGate.kept(
              time,
              blockPool.leios.immutableSlots,
              slot =>
                ref.get.map(current =>
                  LeiosFetchNode
                    .forecast(
                      headers.rules,
                      current.anchor,
                      xyz.kd5ujc.scarab.protocol.LeiosWord.Word64.clamped(slot)
                    )
                    .isRight
                ),
              cause => fatalSink(Fatal.Defect(cause))
            )
          )
      )
      val network = leiosKernel *> Resource
        .eval(run.hangup.pending.set(None) *> run.hangup.network.get)
        .flatMap(inForce =>
          PeerNetwork.resource[IO](
            PeerNetwork.Settings(
              inForce.roots,
              targets,
              consensusMode = setup.config.settings.consensusMode,
              syncTargets = syncTargets,
              minBigLedgerPeersForTrustedState =
                setup.config.settings.minBigLedgerPeersForTrustedState,
              peerSharing = peerSharing,
              intervals = options.churnInterval.fold(Churn.Intervals())(Churn.Intervals.every),
              timeouts = options.churnInterval.fold(Churn.Timeouts())(Churn.Timeouts().within),
              seed = seed,
              snapshot = inForce.snapshot
            ),
            PeerNetwork.tcp[IO](10.seconds, run.listening.fold(NodeAddresses.none)(_.own)),
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
        )
      // the node-to-node server; a failure to set up its socket, or an accept loop that stops,
      // stops the node, as the reference's `Server.with` that throws ends its diffusion
      // (`Diffusion.hs` 738-760 and 905-911)
      val relay = (raise: Raise[IO, DiffusionFailure]) =>
        given Raise[IO, TcpSocketError] =
          Peer.contramapped[IO, DiffusionFailure, TcpSocketError](raise)(
            DiffusionFailure.NodeToNode(_)
          )
        run.listening.fold(Resource.pure[IO, IO[DiffusionFailure]](IO.never))(listening =>
          BearerServer
            .listen[IO, NetworkError](
              TcpListeners.configured[IO],
              listening.address,
              trace => logged(NodeEvent.RemoteServer(trace)),
              maxConnections = None,
              beforeAccept = Some(
                AcceptedConnectionsLimit.rateLimit(manager, accepted, connectionEvents)
              )
            )((remote, failure) =>
              ListenerFailures.report(remote.toString, failure, logged, fatalSink)
            ) { (bearer, remote) =>
              governor.serve(bearer, remote).void
            }
            // the inbound governor's loop runs while the server does, and a defect of it ends the
            // server, as the reference's `Server.with` runs both and ends with either
            .evalMap(server =>
              logged(NodeEvent.Listening(server.address.toString))
                .as(IO.race(server.stopped, governor.run).map(_.merge))
            )
        )
      Connections(manager, governor, network, relay)

  /** What the forger reports: to the log and the metrics. */
  private def forgeEvents(measured: EventMetrics[IO])(using Log[IO]): ForgeEvents[IO] =
    Log.forge(measured.forge)

  /** The services beside the peer network: the node-to-client server, the forger with `--forge`,
    * the stall watchdog and the memory report.
    */
  private def served(
      run: Startup,
      instruments: Instruments,
      chain: Chain,
      globals: xyz.kd5ujc.scarab.ledger.LedgerGlobals,
      geometry: xyz.kd5ujc.scarab.ledger.EpochGeometry,
      leiosFetch: Option[LeiosFetchNode[IO]]
  )(using
      Log[IO],
      Metrics[IO],
      Stateful[IO, VolatileChain[MirrorState]],
      Assertions[IO]
  ): IO[Services] =
    import run.{credentials, genesis, options, setup, store}
    import instruments.{collectors, fatalSink, measured, watchdog}
    import chain.{chainGate, follower, mempool, ref, server}
    def logged(event: NodeEvent): IO[Unit] = Log[IO].emit(event)
    val byronGenesis = setup.byronParams
    val ledgerConfig = LedgerConfig.answer(setup.ledgerConfig)
    val queryEnv = instruments.eraStarts.get.map(observed =>
      QueryEnv(
        byronGenesis
          .map(_.startTime)
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
            .ByronParams(b.epochSlots, b.slotLengthMillis, b.securityParam)
        ),
        setup.globals.maxSupply,
        genesis.raw,
        setup.mirrorEnv(observed).ledger,
        observed,
        genesis.networkMagic,
        ledgerConfig,
        run.layout.layout
      )
    )
    // the node-to-client server; a failure to set it up, or an accept loop that stops,
    // stops the node as the reference's `DiffusionErrored` does
    val local = (raise: Raise[IO, DiffusionFailure]) =>
      given Raise[IO, DiffusionFailure] = raise
      options.localSocket.fold(Resource.pure[IO, IO[DiffusionFailure]](IO.never))(path =>
        localServer(LocalSockets.jdk[IO], path, genesis.networkMagic, logged)(ends =>
          LocalBackends(
            LocalSubmission.handler(mempool, globals.referenceBuild)(ends),
            Some(
              StateQuerySource[IO, MirrorState](ref, queryEnv, store, chainGate, run.layout.layout)
            ),
            Some(server.blockSource(ends)),
            Some(LocalMonitor.source(mempool))
          )
        )
      )
    for
      // the credentials were read at start-up, before the sockets ([[follow]]), also for a node
      // started as non-producing, as the reference reads them (`Run.hs` 195-201)
      forging <- (options.forge, credentials).tupled.traverse { (files, pool) =>
        for
          source <- HotKeySource.files[IO](
            files.vrfSkey,
            files.kesSkey,
            files.opCert,
            files.blsSkey
          )
          forger <- {
            given TxClock[MirrorState] = chain.txClock
            // the pool's snapshot of a Dijkstra slot (`getSnapshotFor`) is its held transactions
            // revalidated on the ticked state of the slot (`DijkstraSnapshots.ofPool`);
            // the endorser blocks it forges go to the fetch's handlers and store; the node does not
            // adapt their capacity (`LeiosAdaptiveEndorsements`, #54)
            Forger.create[IO, MirrorState](
              setup.praos.copy(geometry = geometry),
              globals,
              run.plutus,
              setup.forgedProtocolVersion,
              pool,
              follower,
              mempool,
              forgeEvents(measured),
              utxoStore = Some(store),
              source = Some(source),
              enabled = !options.startsNonProducing(setup.config.settings),
              dijkstraSnapshots = Some(
                DijkstraSnapshots.ofPool[IO](
                  mempool,
                  Some(store),
                  chain.mempoolEnvironment.ask[MempoolEnv]
                )
              ),
              onForgedEb = leiosFetch.map(_.onForged)
            )
          }
        yield forger
      }
      // a node without credentials traces that it does not forge, as the reference does
      // (`Run.hs` 248-252, with no block forging)
      _ <- forgeEvents(measured)
        .emit(ForgeEvent.BlockForgingUpdate(BlockForgingState.DisabledBlockForging))
        .whenA(forging.isEmpty)
      _ <- forging.traverse_(f =>
        collectors.add(Collectors.forging(f.stats)) *>
          collectors.add(Collectors.schedule(f.scheduleView))
      )
      // the chain we start from is progress made: the watchdog counts from here
      _ <- ref.get.flatMap(c => c.tipRef.traverse_(t => watchdog.extended(t.blockNo)))
    yield
      // The process owns the forger loop; SIGHUP is installed with the live network below.
      val production = forging.fold(Resource.unit[IO])(f =>
        f.run.flatMap(_.fold(fatalSink, _ => IO.unit)).background.void
      )
      val watching =
        if options.stallMinutes <= 0 then Resource.unit[IO]
        else
          val period = (options.stallMinutes.minutes / 10).max(1.second).min(30.seconds)
          watchdog.run(period).background.void
      val reporting =
        if options.memoryReportSeconds <= 0 then Resource.unit[IO]
        else
          val stores = Handle
            .allow[NodeError](store.memory)
            .rescue(_ => IO.pure(Map.empty[String, Long]))
          (IO.sleep(options.memoryReportSeconds.seconds) *>
            MemoryReport
              .sample(stores)
              .flatMap(sample => logged(NodeEvent.Memory(sample)))).foreverM.background.void
      Services(local, forging, production, watching, reporting)

  /** The run itself: the decision loops, the selection worker and the peer network with the
    * services beside it, until a signal, a fault, a stall exit, `--seconds` or a failure of the
    * node-to-client side ends it.
    */
  private def ran(
      run: Startup,
      instruments: Instruments,
      rules: Rules,
      chain: Chain,
      supply: Supply,
      connections: Connections,
      services: Services,
      receivedBlocks: Option[ReceivedBlocks[IO]]
  )(using
      Handle[IO, NodeError],
      Log[IO],
      Metrics[IO]
  ): IO[RunEnd] =
    import run.{cliPeers, genesis, loaded, options, scheduling, setup, store, topology}
    import instruments.{collectors, fatalSink, metrics, stall}
    import chain.{follower, ref}
    import supply.{blockPool, genesisRuntime}
    import connections.{manager, network, relay}
    import services.{forging, local, production, reporting, watching}
    def fail[A](e: NodeError): IO[A] = Handle[IO, NodeError].raise(e)
    def logged(event: NodeEvent): IO[Unit] = Log[IO].emit(event)
    val decisionMaintenance = maintainDecisions(blockPool, genesisRuntime, fatalSink)
    val running = (raise: Raise[IO, DiffusionFailure]) =>
      decisionMaintenance
        .flatMap(_ => blockPool.maintenance)
        // the mempool's watcher, forked before anything adopts, as `openMempool` forks it
        .flatMap(_ => chain.synchronization)
        .flatMap(_ =>
          selectionWorker(
            follower,
            blockPool,
            genesisRuntime.map(_.work),
            receivedBlocks,
            rules.warm,
            fatalSink,
            setup.origin
          )
        )
        .surround(network.use { net =>
          val reloadTopology: IO[Unit] =
            reloadNetwork(
              options,
              cliPeers,
              loaded,
              SnapshotPhase.Runtime,
              run.hangup.network,
              net.reloadRoots,
              net.setSnapshot
            ).void
          // a node without credentials finds none again (`updateBlockForging` with no credential
          // files reads an empty list, `Run.hs` 641-648)
          val reload =
            forging.fold(
              forgeEvents(instruments.measured)
                .emit(ForgeEvent.BlockForgingUpdate(BlockForgingState.DisabledBlockForging))
            )(f => f.reload.flatMap(_.fold(fatalSink, _ => IO.unit))) *>
              reloadTopology
          // the full handler (`installSigHUPHandler`, `Run.hs` 596), on every node as in the
          // reference; what a start-up SIGHUP read after the network took its own is applied
          val signals =
            Signals.hangup[IO](reload) *> Resource.eval(
              run.hangup.pending
                .getAndSet(None)
                .flatMap(_.traverse_(c => net.reloadRoots(c.roots) *> net.setSnapshot(c.snapshot)))
            )
          // the governor ends the node only by a fault (an invariant, the bootstrap timeout)
          val sync: IO[Unit] =
            collectors.add(Collectors.peerMetrics(net.metrics)) *>
              collectors.add(
                Collectors.txSubmissionServed(
                  net.outbound.sessionReports.map(_.map(_._2.lifetime))
                )
              ) *>
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
                (manager.snapshot, net.state.get, net.outbound.sessionReports, IO.monotonic).mapN {
                  (connections, selection, sessions, now) =>
                    InboundGovernor
                      .json(connections)
                      .deepMerge(io.circe.Json.obj("sessions" -> OutboundPeers.json(sessions)))
                      .deepMerge(
                        io.circe.Json.obj(
                          "peerSelection" -> PeerNetwork.json(selection, now)
                        )
                      )
                }
              ),
              forging.map(f => (f.stats.byEpoch, f.hot.enabled).mapN(Reports.forgeReport)),
              Some(collectors.scrape)
            )(fatalSink)
            .flatMap(_ => relay(raise))
            .flatMap(relayStopped => local(raise).map(IO.race(relayStopped, _).map(_.merge)))
            .flatTap(_ => signals)
            .flatTap(_ => production)
            .flatTap(_ => reporting)
            .flatTap(_ => watching)
            .use(serverStopped =>
              IO.race(sync, serverStopped.flatMap(raise.raise[DiffusionFailure, Unit])).void
            )
        })
    for
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
                val raised = running(summon[Raise[IO, DiffusionFailure]])
                options.seconds
                  .fold(raised)(s => raised.timeoutTo(s.seconds, IO.unit))
                  .as(Option.empty[DiffusionFailure])
              }
              .rescue(failure => IO.pure(Some(failure))),
            IO.race(IO.race(stall.exit.get, instruments.faulted.get), interrupted.get)
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
            failedStart(s"DiffusionError ${failure.show}")
        case _ => IO.unit
      last <- ref.get
      _ <- finishedLine(ended, logged(NodeEvent.finished(last)))
    yield ended

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

    /** A server of the node stopped, the node-to-node listener or the node-to-client one (the
      * reference's `DiffusionErrored`): the process exits with 1, as the reference's top-level
      * handler does.
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

  /** The flags of the rule sets and the harness that J2.9 removed with them, and `--plutus-vm`,
    * which chose the Plutus evaluator of the rules until the own evaluator became the only one
    * (#43). A node started with one is refused, not run: `--rules old` would otherwise run the
    * era-indexed rules while the operator believes it runs the old ones, and a flag that selected a
    * rule set must never be ignored in a consensus node. The refusal is a typed
    * [[ConfigProblem.RemovedFlag]] and says what to do.
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
    case PlutusVm
        extends RemovedFlag(
          "plutus-vm",
          "the own evaluator is the node's only Plutus evaluator; drop the flag"
        )

    def refusal(usage: String): ConfigError =
      ConfigError(ConfigProblem.WithUsage(ConfigProblem.RemovedFlag(flag), s"$instead\n$usage"))

  private object RemovedFlag:
    def named(argument: String): Option[RemovedFlag] =
      values.find(r => argument == s"--${r.flag}")

  private val Usage =
    "usage: follow (--genesis-dir <dir> | --config <config.json>) (--peer <host>:<port> [--peer ...] | --topology <topology.json>) [--listen <host>:<port>] [--seconds N] [--query-listen off|P] [--query-port P] [--metrics-listen <host>:<port>] [--plutus-cases <dir>] [--db <dir>] [--adopt-legacy-network-identity] [--snapshot-every <N|Nslots|Nepochs>] [--keep-snapshots N] [--snapshot-check structure|full] [--mempool-bytes N] [--utxo-store memory|rocksdb] [--utxo-cache-mb N] [--memory-report-seconds N] [--stall-minutes N] [--stall-exit-after <Ns|Nm|Nh>] [--stall-dump <dir>] [--churn-interval <Ns|Nm>] [--read-ahead N] [--chain-sync-low N] [--chain-sync-high N] [--header-first on|off] [--max-inbound N] [--max-outbound N] [--cpu-threads N] [--plutus-workers N] [--dump-state <dir> [--dump-state-at <epoch-boundaries|epoch-boundaries/N|slot,slot,...>] [--dump-state-full]] [--local-socket <path>] [--forge --vrf-skey <file> --kes-skey <file> --opcert <file> [--shelley-bls-key <file>] [--key-lock on|off]] [--start-as-non-producing-node] [--reference-build <build>] [--assertions-file <path>] " + LogSettings.Usage +
      "\n" + ReferenceBuildFlag.Help

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
    "max-outbound",
    "cpu-threads",
    "plutus-workers",
    "dump-state",
    "dump-state-at",
    "local-socket",
    "vrf-skey",
    "kes-skey",
    "opcert",
    "shelley-bls-key",
    "key-lock",
    "reference-build",
    "assertions-file"
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
        case (flag @ ("--start-as-non-producing-node" | "--non-producing-node")) :: tail =>
          val name = flag.drop(2)
          if acc.contains(name) then bad(ConfigProblem.DuplicateFlag(flag))
          else go(tail, acc.updated(name, List("true")))
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
          // a flag given more than once (a repeated flag) reads its last value here
          flags = multi.flatMap((name, values) => values.lastOption.map(name -> _))
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
            .traverse(_.toLongOption.flatMap(Word32.fromLong).toRight(invalid("mempool-bytes")))
          forge <-
            if !flags.contains("forge") then Right(None)
            else
              (
                flags.get("vrf-skey").toRight(invalid("vrf-skey")),
                flags.get("kes-skey").toRight(invalid("kes-skey")),
                flags.get("opcert").toRight(invalid("opcert"))
              ).mapN((v, k, o) =>
                Some(
                  ForgeFiles(
                    Path.of(v),
                    Path.of(k),
                    Path.of(o),
                    flags.get("shelley-bls-key").map(Path.of(_))
                  )
                )
              )
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
          maxOutbound <- flags
            .get("max-outbound")
            .traverse(
              _.toIntOption
                .flatMap(OutboundLimit.Connections.from(_).toOption)
                .toRight(invalid("max-outbound"))
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
          referenceBuild <- ReferenceBuildFlag.parse(flags.get("reference-build"), Usage)
          _ <- ReferenceBuildFlag
            .headerFirst(referenceBuild, headerFirst)
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
          log
        ).copy(
          churnInterval = churnInterval,
          stallDump = flags.get("stall-dump").map(Path.of(_)),
          plutusWorkers = plutusWorkers,
          chainSyncWindow = chainSyncWindow,
          adoptLegacyNetworkIdentity = flags.contains("adopt-legacy-network-identity"),
          keyLock = keyLock,
          referenceBuild = referenceBuild,
          startAsNonProducingNode =
            flags.contains("start-as-non-producing-node") || flags.contains("non-producing-node"),
          assertionsFile = flags.get("assertions-file").map(Path.of(_)),
          maxOutbound = maxOutbound
        )
      case _ => Left(usage)
