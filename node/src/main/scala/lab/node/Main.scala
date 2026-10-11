// SPDX-License-Identifier: Apache-2.0
package lab.node

import cats.effect.{ExitCode, IO, IOApp, Ref, Resource}
import lab.node.storage.{
  ChainPersistence,
  ChainStorage,
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
import com.comcast.ip4s.{Host, SocketAddress}
import scala.concurrent.duration.*

/** `follow --genesis-dir <dir> --peer <host>:<port> [--seconds N] [--query-port P] [--plutus-cases
  * <dir>] [--db <dir>] [--snapshot-every <N|Nslots|Nepochs>] [--keep-snapshots N]`
  *
  * Follows the peer from genesis, applying the full ledger rules, and serves the retained ledger
  * states on loopback for comparison against a reference node. With `--db` the blocks older than
  * `k` and periodic state snapshots are persisted, and a restart resumes from the newest valid
  * snapshot plus a replay of the stored blocks instead of from genesis.
  */
object Main extends IOApp:
  final case class Options(
      genesisDir: Path,
      peer: SocketAddress[Host],
      seconds: Option[Long],
      queryPort: Int,
      plutusCases: Option[Path] = None,
      db: Option[Path] = None,
      snapshotEvery: SnapshotInterval = SnapshotInterval.Epochs(1),
      keepSnapshots: Int = 3,
      /** `memory` or `rocksdb`; the default is rocksdb with `--db` and memory without. */
      utxoStore: Option[String] = None,
      utxoCacheMb: Int = 256,
      /** Most ChainSync requests pipelined (and blocks fetched in one range) while far behind. */
      readAhead: Int = 64
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

  def run(args: List[String]): IO[ExitCode] =
    parse(args) match
      case Left(usage) => IO.println(usage).as(ExitCode(2))
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
      events = JsonLog.events
      cases = options.plutusCases.map { dir =>
        java.nio.file.Files.createDirectories(dir)
        PlutusCases(dir, perKind = 2, max = 400)
      }
      rules = NodeRules[IO](
        setup.praos,
        setup.globals,
        setup.byronRules,
        phase2Observer = cases.map(c => c.observe),
        utxoStore = Some(store),
        afterBlock =
          Some(s => IO.delay(Verification.noteEpochNonce(s.ledger.epoch, s.praos.epochNonce)))
      )
      k = genesis.geometry.securityParam
      storage = options.db.fold(
        Resource.pure[IO, (Option[BlockRef], NodeState, Option[ChainPersistence[IO, NodeState]])](
          (None, setup.start, Some(ChainPersistence.backingOnly[IO, NodeState](backing)))
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
          .map(r => (r.anchor, r.state, Some(r.persistence)))
      }
      _ <- storage.use { (anchor, state, persistence) =>
        for
          ref <- Ref.of[IO, VolatileChain[NodeState]](
            VolatileChain(k, Anchor(anchor, state), Vector.empty, setup.origin)
          )
          given Stateful[IO, VolatileChain[NodeState]] = RefStateful(ref)
          follower = Follower[IO, NodeState](
            rules,
            events,
            genesis.geometry,
            NodeRules.observe(genesis.geometry),
            persistence
          )
          settings = Peer.Settings(
            options.peer,
            genesis.networkMagic,
            readAhead = options.readAhead,
            byronEpochSlots = genesis.geometry.byron.epochSlots
          )
          retry = Follower.Retry(maxAttempts = 30, delay = 2.seconds)
          // Peer failures are handled by the supervisor; once retries are exhausted they become fatal.
          sync = Handle
            .allow[PeerError](
              Follower.supervise(
                follower,
                Peer.nodeToNode[IO](
                  settings,
                  Verification.warm[IO](_, setup.praos, Verification.defaultParallelism)
                ),
                events,
                retry
              )
            )
            .rescue(error => fail[Unit](error))
          run = QueryServer
            .resource[IO](ChainView.fromRef(ref), options.queryPort, Some(store))
            .surround(sync)
          _ <- JsonLog.started(options.peer, genesis, setup.config)
          _ <- options.seconds.fold(run)(s => run.timeoutTo(s.seconds, IO.unit))
          last <- ref.get
          _ <- JsonLog.finished(last)
        yield ()
      }
    yield ()

  private def parse(args: List[String]): Either[String, Options] =
    val usage =
      "usage: follow --genesis-dir <dir> --peer <host>:<port> [--seconds N] [--query-port P] [--plutus-cases <dir>] [--db <dir>] [--snapshot-every <N|Nslots|Nepochs>] [--keep-snapshots N] [--utxo-store memory|rocksdb] [--utxo-cache-mb N] [--read-ahead N]"
    def go(rest: List[String], acc: Map[String, String]): Either[String, Map[String, String]] =
      rest match
        case Nil => Right(acc)
        case flag :: value :: tail if flag.startsWith("--") =>
          go(tail, acc + (flag.drop(2) -> value))
        case _ => Left(usage)
    args match
      case "follow" :: rest =>
        for
          flags <- go(rest, Map.empty)
          dir <- flags.get("genesis-dir").toRight(usage).map(Path.of(_))
          peerText <- flags.get("peer").toRight(usage)
          peer <- Peer.parseAddress(peerText)
          seconds <- flags.get("seconds").traverse(_.toLongOption.toRight(usage))
          port <- flags.get("query-port").fold(Right(7071))(_.toIntOption.toRight(usage))
          interval <- flags
            .get("snapshot-every")
            .fold(Right(SnapshotInterval.Epochs(1)))(t => SnapshotInterval.parse(t).toRight(usage))
          keep <- flags
            .get("keep-snapshots")
            .fold(Right(3))(_.toIntOption.filter(_ > 0).toRight(usage))
          utxoKind <- flags
            .get("utxo-store")
            .traverse(k => Either.cond(Set("memory", "rocksdb")(k), k, usage))
          cacheMb <- flags
            .get("utxo-cache-mb")
            .fold(Right(256))(_.toIntOption.filter(_ > 0).toRight(usage))
          readAhead <- flags
            .get("read-ahead")
            .fold(Right(64))(_.toIntOption.filter(_ > 0).toRight(usage))
        yield Options(
          dir,
          peer,
          seconds,
          port,
          flags.get("plutus-cases").map(Path.of(_)),
          flags.get("db").map(Path.of(_)),
          interval,
          keep,
          utxoKind,
          cacheMb,
          readAhead
        )
      case _ => Left(usage)
