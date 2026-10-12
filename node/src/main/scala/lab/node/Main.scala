// SPDX-License-Identifier: Apache-2.0
package lab.node

import cats.effect.{ExitCode, IO, IOApp, Ref, Resource}
import lab.node.storage.{
  ChainPersistence,
  ChainStorage,
  ImmutableStore,
  MemoryUtxoStore,
  NodeCodecs,
  RocksUtxoStore,
  StateBacking,
  UtxoBacking,
  UtxoStore
}
import NodeCodecs.given
import cats.mtl.{Handle, Stateful}
import cats.syntax.all.*
import java.nio.file.Path
import com.comcast.ip4s.{Host, Port, SocketAddress}
import lab.network.NtNHeader
import lab.network.runtime.{
  BearerServer,
  LocalTxSubmissionServer,
  NetworkError,
  NodeToNodeServer,
  ServerHandlers
}
import lab.node.forge.{Forger, KeyFiles}
import lab.node.mempool.{LocalSubmission, Mempool, MempoolEnv}
import lab.node.relay.{ChainServer, Persistence}
import scala.concurrent.duration.*

/** `follow --genesis-dir <dir> --peer <host>:<port> [--peer ...] [--listen <host>:<port>]
  * [--seconds N] [--query-port P] [--plutus-cases <dir>] [--db <dir>] [--snapshot-every
  * <N|Nslots|Nepochs>] [--keep-snapshots N] [--mempool-bytes N] [--utxo-store memory|rocksdb]
  * [--utxo-cache-mb N] [--read-ahead N] [--dump-state <dir> [--dump-state-at
  * <epoch-boundaries|epoch-boundaries/N|slot,slot,...>] [--dump-state-full]] [--local-socket
  * <path>] [--forge --vrf-skey <file> --kes-skey <file> --opcert <file>]`
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
      genesisDir: Path,
      peers: List[SocketAddress[Host]],
      seconds: Option[Long],
      queryPort: Int,
      plutusCases: Option[Path] = None,
      db: Option[Path] = None,
      snapshotEvery: SnapshotInterval = SnapshotInterval.Epochs(1),
      keepSnapshots: Int = 3,
      listen: Option[SocketAddress[Host]] = None,
      mempoolBytes: Long = 1048576L,
      localSocket: Option[Path] = None,
      forge: Option[ForgeFiles] = None,
      /** `memory` or `rocksdb`; the default is rocksdb with `--db` and memory without. */
      utxoStore: Option[String] = None,
      utxoCacheMb: Int = 256,
      /** Most ChainSync requests pipelined (and blocks fetched in one range) while far behind. */
      readAhead: Int = 64,
      /** `--dump-state <dir> [--dump-state-at epoch-boundaries|epoch-boundaries/N|<slot,slot,...>]`:
        * write a ledger-state digest ([[StateDump]]) at those points, for the db-analyser
        * comparison.
        */
      dumpState: Option[(Path, StateDump.Targets)] = None,
      /** `--dump-state-full`: also write the whole projection (UTxO included) next to each digest.
        */
      dumpStateFull: Boolean = false
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
    parse(args) match
      case Left(error) => IO.println(error.message).as(ExitCode(2))
      case Right(options) =>
        Handle
          .allow[NodeError](follow(options).as(ExitCode.Success))
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

  private def follow(options: Options)(using Handle[IO, NodeError]): IO[Unit] =
    utxoBacking(options).use((store, backing) => followWith(options, store, backing))

  private def followWith(
      options: Options,
      store: UtxoStore[IO],
      backing: StateBacking[IO, NodeState]
  )(using Handle[IO, NodeError]): IO[Unit] =
    def fail[A](e: NodeError): IO[A] = Handle[IO, NodeError].raise(e)
    for
      setup <- IO
        .blocking(NodeSetup.fromDirectory(options.genesisDir))
        .flatMap(_.fold(fail, IO.pure))
      genesis = setup.genesis
      blocks <- Ref.of[IO, Long](0L)
      connected <- Ref.of[IO, Boolean](false)
      events = new FollowerEvents[IO]:
        def emit(event: FollowerEvent): IO[Unit] =
          (event match
            case FollowerEvent.Connected(_) => connected.set(true)
            case _                          => IO.unit
          ) *> JsonLog.events.emit(event)
      cases = options.plutusCases.map { dir =>
        java.nio.file.Files.createDirectories(dir)
        PlutusCases(dir, perKind = 2, max = 400)
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
      rules = NodeRules[IO](
        setup.praos,
        setup.globals,
        setup.byronRules,
        phase2Observer = cases.map(c => c.observe),
        utxoStore = Some(store),
        afterBlock = Some { (header, s) =>
          IO.delay(Verification.noteEpochNonce(s.ledger.epoch, s.praos.epochNonce)) *>
            dumper.traverse_(_.afterBlock(header, s)) *>
            blocks.updateAndGet(_ + 1).flatMap { n =>
              IO.blocking(
                System.err.println(
                  s"verification verdicts found ahead (hits/lookups): ${Verification.summary}"
                )
              ).whenA(n % 20000 == 0)
            }
        }
      )
      k = genesis.geometry.securityParam
      storage = options.db.fold(
        Resource.pure[
          IO,
          (
              Option[BlockRef],
              NodeState,
              Option[ChainPersistence[IO, NodeState]],
              Option[ImmutableStore[IO]]
          )
        ](
          (None, setup.start, Some(ChainPersistence.backingOnly[IO, NodeState](backing)), None)
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
              byronEpochSlots = genesis.geometry.byron.epochSlots
            ),
            rules,
            setup.start,
            JsonLog.storage,
            backing,
            Verification.warm[IO](_, setup.praos, Verification.defaultParallelism)
          )
          .map(r => (r.anchor, r.state, Some(r.persistence), Some(r.store)))
      }
      _ <- storage.use { (anchor, state, persistence, immutable) =>
        for
          ref <- Ref.of[IO, VolatileChain[NodeState]](
            VolatileChain(k, Anchor(anchor, state), Vector.empty, setup.origin)
          )
          given Stateful[IO, VolatileChain[NodeState]] = RefStateful(ref)
          server <- ChainServer.open[IO](
            immutable,
            anchor,
            ChainServer.headerOfBlock(genesis.geometry.byron.epochSlots)
          )
          mempool <- Mempool.create[IO](
            state.ledger,
            anchor.fold(BigInt(0))(_.slot),
            MempoolEnv(setup.globals, genesis.geometry),
            options.mempoolBytes,
            JsonLog.mempool,
            utxoStore = Some(store)
          )
          served = server.persistence[NodeState]
          follower <- Follower.create[IO, NodeState](
            rules,
            events,
            genesis.geometry,
            NodeRules.observe(genesis.geometry),
            Some(persistence.fold(served)(Persistence.both(_, served))),
            Some((chain, added) =>
              val adopted = added.flatMap(raw =>
                Block.decode(raw, genesis.geometry.byron.epochSlots).toOption.toVector.flatMap {
                  case b: Block.Shelley => b.transactions.map(_.id)
                  case _                => Vector.empty
                }
              )
              chain.tipRef.traverse_(t =>
                mempool.sync(chain.state.ledger, t.slot, adopted.toSet).void
              )
            )
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
                  Peer.nodeToNode[IO](
                    settings,
                    Verification.warm[IO](_, setup.praos, Verification.defaultParallelism),
                    Some(mempool.source)
                  ),
                  events,
                  retry
                )
              }
            )
            .rescue(error => fail[Unit](error))
          relay = options.listen.fold(Resource.unit[IO])(address =>
            BearerServer
              .listen[IO](Some(address.host), Some(address.port)) { (bearer, remote) =>
                JsonLog.inbound(remote.toString, "accepted") *>
                  Handle
                    .allow[NetworkError](
                      NodeToNodeServer.accept(bearer, genesis.networkMagic, handlers)
                    )
                    .rescue(e => IO.pure(e.message))
                    .flatMap(reason => JsonLog.inbound(remote.toString, "closed", Some(reason)))
              }
              .evalMap(bound => JsonLog.listening(bound.toString))
          )
          local = options.localSocket.fold(Resource.unit[IO])(path =>
            BearerServer
              .listenUnix[IO](path) { bearer =>
                JsonLog.inbound(path.toString, "accepted") *>
                  Handle
                    .allow[NetworkError](
                      LocalTxSubmissionServer
                        .accept(bearer, genesis.networkMagic, LocalSubmission.handler(mempool))
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
                setup.praos,
                setup.globals,
                credentials,
                follower,
                mempool,
                JsonLog.forge,
                connected.get,
                utxoStore = Some(store)
              )
            yield forger.run
          }
          production = forging.fold(Resource.unit[IO])(_.background.void)
          run = QueryServer
            .resource[IO](ChainView.fromRef(ref), options.queryPort, Some(store))
            .flatMap(_ => relay)
            .flatMap(_ => local)
            .flatMap(_ => production)
            .surround(sync)
          _ <- JsonLog.started(options.peers, genesis, setup.config)
          _ <- options.seconds.fold(run)(s => run.timeoutTo(s.seconds, IO.unit))
          last <- ref.get
          _ <- JsonLog.finished(last)
        yield ()
      }
    yield ()

  private val Usage =
    "usage: follow --genesis-dir <dir> --peer <host>:<port> [--peer ...] [--listen <host>:<port>] [--seconds N] [--query-port P] [--plutus-cases <dir>] [--db <dir>] [--snapshot-every <N|Nslots|Nepochs>] [--keep-snapshots N] [--mempool-bytes N] [--utxo-store memory|rocksdb] [--utxo-cache-mb N] [--read-ahead N] [--dump-state <dir> [--dump-state-at <epoch-boundaries|epoch-boundaries/N|slot,slot,...>] [--dump-state-full]] [--local-socket <path>] [--forge --vrf-skey <file> --kes-skey <file> --opcert <file>]"

  /** Flags taking a value, other than `--peer`, which may repeat. */
  private val ValueFlags = Set(
    "genesis-dir",
    "seconds",
    "query-port",
    "plutus-cases",
    "db",
    "snapshot-every",
    "keep-snapshots",
    "listen",
    "mempool-bytes",
    "utxo-store",
    "utxo-cache-mb",
    "read-ahead",
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
    def go(
        rest: List[String],
        acc: Map[String, List[String]]
    ): Either[ConfigError, Map[String, List[String]]] =
      rest match
        case Nil => Right(acc)
        case "--forge" :: tail =>
          if acc.contains("forge") then bad("duplicate flag --forge")
          else go(tail, acc.updated("forge", List("true")))
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
          dir <- flags.get("genesis-dir").toRight(usage).map(Path.of(_))
          peerTexts = multi.getOrElse("peer", Nil)
          _ <- Either.cond(peerTexts.nonEmpty, (), usage)
          peers <- peerTexts.traverse(t => Peer.parseAddress(t).leftMap(ConfigError(_)))
          seconds <- flags.get("seconds").traverse(_.toLongOption.toRight(usage))
          port <- flags.get("query-port").fold(Right(7071))(_.toIntOption.toRight(usage))
          interval <- flags
            .get("snapshot-every")
            .fold(Right(SnapshotInterval.Epochs(1)))(t => SnapshotInterval.parse(t).toRight(usage))
          keep <- flags
            .get("keep-snapshots")
            .fold(Right(3))(_.toIntOption.filter(_ > 0).toRight(usage))
          listen <- flags.get("listen").traverse(t => Peer.parseAddress(t).leftMap(ConfigError(_)))
          localSocket = flags.get("local-socket").map(Path.of(_))
          mempool <- flags
            .get("mempool-bytes")
            .fold(Right(1048576L))(_.toLongOption.filter(_ > 0).toRight(usage))
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
          readAhead <- flags
            .get("read-ahead")
            .fold(Right(64))(_.toIntOption.filter(_ > 0).toRight(usage))
          dump <- flags.get("dump-state").traverse { d =>
            flags
              .get("dump-state-at")
              .fold(Right(StateDump.Targets.EpochBoundaries(1)))(t =>
                StateDump.Targets.parse(t).toRight(usage)
              )
              .map(Path.of(d) -> _)
          }
          _ <- Either.cond(dump.isDefined || !flags.contains("dump-state-at"), (), usage)
        yield Options(
          dir,
          peers,
          seconds,
          port,
          flags.get("plutus-cases").map(Path.of(_)),
          flags.get("db").map(Path.of(_)),
          interval,
          keep,
          listen,
          mempool,
          localSocket,
          forge,
          utxoKind,
          cacheMb,
          readAhead,
          dump,
          flags.contains("dump-state-full")
        )
      case _ => Left(usage)
