// SPDX-License-Identifier: Apache-2.0
package lab.node

import cats.effect.{ExitCode, IO, IOApp, Ref, Resource}
import lab.node.storage.{ChainPersistence, ChainStorage, ImmutableStore, NodeCodecs}
import NodeCodecs.given
import cats.mtl.{Handle, Stateful}
import cats.syntax.all.*
import java.nio.file.Path
import com.comcast.ip4s.{Host, Port, SocketAddress}
import lab.network.NtNHeader
import lab.network.runtime.{BearerServer, NetworkError, NodeToNodeServer, ServerHandlers}
import lab.node.mempool.{Mempool, MempoolEnv}
import lab.node.relay.{ChainServer, Persistence}
import scala.concurrent.duration.*

/** `follow --genesis-dir <dir> --peer <host>:<port> [--peer ...] [--listen <host>:<port>]
  * [--seconds N] [--query-port P] [--plutus-cases <dir>] [--db <dir>] [--snapshot-every
  * <N|Nslots|Nepochs>] [--keep-snapshots N] [--mempool-bytes N]`
  *
  * Follows the peers from genesis, applying the full ledger rules and Praos chain selection among
  * the peers' chains, and serves the retained ledger states on loopback for comparison against a
  * reference node. With `--db` the blocks older than `k` and periodic state snapshots are
  * persisted, and a restart resumes from the newest valid snapshot plus a replay of the stored
  * blocks instead of from genesis. With `--listen` the node is a relay: it accepts node-to-node
  * connections and serves its chain (ChainSync, BlockFetch), collects transactions into its mempool
  * (TxSubmission2) and offers the mempool to the peers it follows.
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
      mempoolBytes: Long = 1048576L
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

  private def follow(options: Options)(using Handle[IO, NodeError]): IO[Unit] =
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
        phase2Observer = cases.map(c => c.observe)
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
          (None, setup.start, None, None)
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
            JsonLog.storage
          )
          .map(r => (r.anchor, r.state, Some(r.persistence), Some(r.store)))
      }
      _ <- storage.use { (anchor, state, persistence, store) =>
        for
          ref <- Ref.of[IO, VolatileChain[NodeState]](
            VolatileChain(k, Anchor(anchor, state), Vector.empty, setup.origin)
          )
          given Stateful[IO, VolatileChain[NodeState]] = RefStateful(ref)
          server <- ChainServer.open[IO](
            store,
            anchor,
            ChainServer.headerOfBlock(genesis.geometry.byron.epochSlots)
          )
          mempool <- Mempool.create[IO](
            state.ledger,
            anchor.fold(BigInt(0))(_.slot),
            MempoolEnv(setup.globals, genesis.geometry),
            options.mempoolBytes,
            JsonLog.mempool
          )
          served = server.persistence[NodeState]
          follower <- Follower.create[IO, NodeState](
            rules,
            events,
            genesis.geometry,
            NodeRules.observe(genesis.geometry),
            Some(persistence.fold(served)(Persistence.both(_, served))),
            Some(chain =>
              chain.tipRef.traverse_(t => mempool.sync(chain.state.ledger, t.slot).void)
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
                  byronEpochSlots = genesis.geometry.byron.epochSlots
                )
                Follower.supervise(
                  follower,
                  Peer.nodeToNode[IO](settings, Some(mempool.source)),
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
          run = QueryServer
            .resource[IO](ChainView.fromRef(ref), options.queryPort)
            .flatMap(_ => relay)
            .surround(sync)
          _ <- JsonLog.started(options.peers, genesis, setup.config)
          _ <- options.seconds.fold(run)(s => run.timeoutTo(s.seconds, IO.unit))
          last <- ref.get
          _ <- JsonLog.finished(last)
        yield ()
      }
    yield ()

  private def parse(args: List[String]): Either[String, Options] =
    val usage =
      "usage: follow --genesis-dir <dir> --peer <host>:<port> [--peer ...] [--listen <host>:<port>] [--seconds N] [--query-port P] [--plutus-cases <dir>] [--db <dir>] [--snapshot-every <N|Nslots|Nepochs>] [--keep-snapshots N] [--mempool-bytes N]"
    def go(
        rest: List[String],
        acc: Map[String, List[String]]
    ): Either[String, Map[String, List[String]]] =
      rest match
        case Nil => Right(acc)
        case flag :: value :: tail if flag.startsWith("--") =>
          go(tail, acc.updated(flag.drop(2), acc.getOrElse(flag.drop(2), Nil) :+ value))
        case _ => Left(usage)
    args match
      case "follow" :: rest =>
        for
          multi <- go(rest, Map.empty)
          flags = multi.view.mapValues(_.last).toMap
          dir <- flags.get("genesis-dir").toRight(usage).map(Path.of(_))
          peerTexts = multi.getOrElse("peer", Nil)
          _ <- Either.cond(peerTexts.nonEmpty, (), usage)
          peers <- peerTexts.traverse(Peer.parseAddress)
          seconds <- flags.get("seconds").traverse(_.toLongOption.toRight(usage))
          port <- flags.get("query-port").fold(Right(7071))(_.toIntOption.toRight(usage))
          interval <- flags
            .get("snapshot-every")
            .fold(Right(SnapshotInterval.Epochs(1)))(t => SnapshotInterval.parse(t).toRight(usage))
          keep <- flags
            .get("keep-snapshots")
            .fold(Right(3))(_.toIntOption.filter(_ > 0).toRight(usage))
          listen <- flags.get("listen").traverse(Peer.parseAddress)
          mempool <- flags
            .get("mempool-bytes")
            .fold(Right(1048576L))(_.toLongOption.filter(_ > 0).toRight(usage))
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
          mempool
        )
      case _ => Left(usage)
