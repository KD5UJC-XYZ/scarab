// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.network.recvPrefix
import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.std.{Mutex, Queue}
import cats.mtl.Handle
import cats.syntax.all.*
import java.nio.file.Path
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.consensus.{Anchor, VolatileChain}
import xyz.kd5ujc.scarab.consensus.state.MirrorState
import xyz.kd5ujc.scarab.network.{
  AcceptFailure,
  Bearer,
  ChainSyncServer,
  ConnectionEnd,
  DiffusionFailure,
  HandlerFailure,
  HandshakeClient,
  LocalAddress,
  LocalListener,
  LocalServer,
  LocalSockets,
  LocalStateQueryServer,
  LocalStream,
  LocalTxSubmissionServer,
  Mux,
  MuxChannel,
  NetworkError,
  SystemCallError
}
import xyz.kd5ujc.scarab.ledger.{ChainError, StorageError, TxIn, TxOut}
import xyz.kd5ujc.scarab.protocol.{
  Handshake,
  LocalStateQuery,
  NtcVersion,
  ReferenceBuild,
  StateLayout
}
import xyz.kd5ujc.scarab.protocol.CardanoBlockFetch.RawNtNBlock
import xyz.kd5ujc.scarab.protocol.ChainSync.{Point, Role, Tip}
import xyz.kd5ujc.scarab.node.log.NodeEvent
import xyz.kd5ujc.scarab.node.ntc.{LedgerConfig, QueryEnv, QueryView, StateQuerySource}
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.storage.{MemoryUtxoStore, UtxoStore}

class ListenerFailuresSuite extends xyz.kd5ujc.scarab.network.FailsOnUncaught:
  private def reported(failure: HandlerFailure[NetworkError]) =
    for
      events <- Ref.of[IO, Vector[NodeEvent]](Vector.empty)
      fatals <- Ref.of[IO, Vector[Fatal]](Vector.empty)
      _ <- ListenerFailures.report[IO](
        "listener-test",
        failure,
        event => events.update(_ :+ event),
        fatal => fatals.update(_ :+ fatal)
      )
      observed <- events.get
      stopped <- fatals.get
    yield (observed, stopped)

  private val socket = "/ipc/node.socket"
  private val magic = 42L
  private val setup = NodeSetup
    .fromDirectory(ReferenceBuild.Default)(Path.of("fixtures/devnet/lsq/genesis"))
    .fold(e => fail(e.message), identity)
  private val queryEnv = QueryEnv(
    setup.geometry.systemStart,
    setup.geometry,
    setup.praos.forks,
    setup.mirrorEnv().byron,
    setup.globals.maxSupply,
    setup.genesis.raw,
    setup.mirrorEnv().ledger,
    networkMagic = setup.genesis.networkMagic.toLong,
    ledgerConfig = LedgerConfig.answer(setup.ledgerConfig),
    layout = StateLayout.Node11_1
  )

  /** Sockets whose clients are the test's in-memory connections, accepted in turn. */
  private final class InMemorySockets(accepts: Queue[IO, Either[AcceptFailure, LocalStream[IO]]])
      extends LocalSockets[IO]:
    def removeFile(path: String) = IO.pure(Right(()))
    // a socket only its owner reads and writes, in a directory only its owner writes: no warning
    def fileMode(path: String) =
      IO.pure(Right(Integer.parseInt(if path == socket then "140700" else "40755", 8)))
    def open(address: LocalAddress) =
      Resource.pure[IO, Either[SystemCallError, LocalListener[IO]]](Right(listener))
    private val listener: LocalListener[IO] = new LocalListener[IO]:
      def bind = IO.pure(Right(()))
      def descriptor = IO.pure(None)
      def listen = IO.pure(Right(()))
      def accept = accepts.take

  /** A node-to-client client on `bearer`: it agrees a version, then runs `body` on its
    * LocalStateQuery channel; how its connection ended if it did not finish.
    */
  private def client[A](bearer: Bearer[IO])(
      body: MuxChannel[IO] => Handle[IO, NetworkError] ?=> IO[A]
  ): IO[Either[NetworkError, A]] =
    Handle
      .allow[NetworkError] {
        Mux
          .resource(
            bearer,
            Set(0, 5, 6, 7),
            Mux.Config.nodeToClient.copy(role = Mux.Role.Initiator)
          )
          .use { mux =>
            for
              handshake <- mux.channel(0)
              _ <- HandshakeClient.run(
                handshake,
                Vector(23 -> Handshake.Data(magic)),
                suite = Handshake.Suite.NodeToClient
              )
              query <- mux.channel(7)
              out <- body(query)
            yield Right(out)
          }
      }
      .rescue(error => IO.pure(Left(error)))

  /** `source`, whose acquisitions wait until `started`: the connection's sibling protocol is up. */
  private def afterSibling(
      started: IO[Unit],
      source: LocalStateQueryServer.Source[IO, StorageError]
  ): LocalStateQueryServer.Source[IO, StorageError] =
    new LocalStateQueryServer.Source[IO, StorageError]:
      override def forVersion(version: NtcVersion) =
        afterSibling(started, source.forVersion(version))
      def queries = source.queries
      def acquire(target: LocalStateQuery.Target) =
        Resource.eval(started) *> source.acquire(target)

  /** `store`, telling `released` when a pinned read view is released. */
  private def viewObserved(store: UtxoStore[IO], released: Ref[IO, Boolean]): UtxoStore[IO] =
    new UtxoStore[IO]:
      export store.{
        write,
        flushed,
        stamp,
        read,
        all,
        select,
        selectAddresses,
        scan,
        entries,
        size,
        checkpoint,
        restore,
        reset,
        memory
      }
      def readView = store.readView.onFinalize(released.set(true))

  private val idleChainSync: ChainSyncServer.Source[IO, RawNtNBlock] =
    new ChainSyncServer.Source[IO, RawNtNBlock]:
      def intersect(points: Vector[Point]) = IO.pure(Left(Tip.Origin))
      def poll = IO.pure(None)
      def await = IO.never

  test("an intentional network close reports the original reason without stopping the node") {
    val error = NetworkError.BearerClosed(NetworkError.Closure.PeerClosed)
    reported(HandlerFailure.Domain(error)).map { (events, fatals) =>
      assertEquals(
        events,
        Vector(
          NodeEvent.Inbound(
            "listener-test",
            InboundEvent.Closed(InboundEnd.Connection(ConnectionEnd.fromNetwork(error)))
          )
        )
      )
      assertEquals(fatals, Vector.empty)
    }
  }

  // only LocalStateQuery raises it, so no node-to-node connection does; on the node-to-client
  // socket it ends that client alone (the test after this one)
  test("the node-to-node reporter makes a state acquisition failure an internal fatal") {
    val cause: NetworkError.StateAcquireFailure =
      NetworkError.StateAcquireFailure(Point.Origin, 5L, 4L, 9)
    reported(HandlerFailure.Domain(cause)).map { (events, fatals) =>
      assertEquals(fatals, Vector(Fatal.Internal(ChainError.StateAcquire(cause))))
      assertEquals(
        FailurePolicy.disposition(SessionEnd.of(ChainError.StateAcquire(cause))),
        Disposition.StopNode(Fatal.Internal(ChainError.StateAcquire(cause)))
      )
      assertEquals(
        events,
        Vector(
          NodeEvent.Inbound(
            "listener-test",
            InboundEvent.Closed(InboundEnd.Connection(ConnectionEnd.fromNetwork(cause)))
          )
        )
      )
    }
  }

  test(
    "an incoherent local state acquisition ends that client alone, after its resources, and the node goes on"
  ) {
    val pinned = 5L
    val cause = NetworkError.StateAcquireFailure(
      Point.Origin,
      QueryView(Point.Origin, None, setup.mirrorStart).utxo.flushed,
      pinned,
      BigInt(-1)
    )
    for
      events <- Ref.of[IO, Vector[NodeEvent]](Vector.empty)
      viewReleased <- Ref.of[IO, Boolean](false)
      siblingStarted <- Deferred[IO, Unit]
      siblingReleased <- Ref.of[IO, Boolean](false)
      releasedAtClose <- Deferred[IO, (Boolean, Boolean)]
      firstClosed <- Deferred[IO, Unit]
      accepts <- Queue.unbounded[IO, Either[AcceptFailure, LocalStream[IO]]]
      // a backing store whose generation is newer than the volatile tip's: no ledger view and
      // backing generation describe one state
      store <- Handle
        .allow[StorageError](
          MemoryUtxoStore[IO].flatTap(_.write(Vector.empty[(TxIn, Option[TxOut])], pinned))
        )
        .rescue(error => IO(fail(s"store not opened: ${error.message}")))
      chain <- Ref.of[IO, VolatileChain[MirrorState]](
        VolatileChain(5, Anchor(None, setup.mirrorStart), Vector.empty)
      )
      gate <- Mutex[IO]
      first <- Bearer.inMemory[IO]()
      second <- Bearer.inMemory[IO]()
      // the released flags are read before the line is recorded, so they say what was released
      // before it was logged
      logged = (event: NodeEvent) =>
        (event match
          case NodeEvent.Inbound(_, InboundEvent.Closed(_)) =>
            (viewReleased.get, siblingReleased.get).tupled.flatMap(releasedAtClose.complete).void
          case _ => IO.unit
        ) *> events.update(_ :+ event)
      backends = (_: Fatal => IO[Unit]) =>
        Main.LocalBackends(
          _ => IO.pure(LocalTxSubmissionServer.Response.Accepted),
          Some(
            afterSibling(
              siblingStarted.get,
              StateQuerySource(
                chain,
                IO.pure(queryEnv),
                viewObserved(store, viewReleased),
                gate,
                queryEnv.layout
              )
            )
          ),
          Some(
            Resource.make(siblingStarted.complete(()).as(idleChainSync))(_ =>
              siblingReleased.set(true)
            )
          ),
          None
        )
      outcome <- Handle
        .allow[DiffusionFailure] {
          Main.localServer(InMemorySockets(accepts), socket, magic, logged)(backends).use {
            stopped =>
              for
                _ <- accepts.offer(
                  Right(LocalStream(first._2, first._2.close *> firstClosed.complete(()).void))
                )
                answered <- client(first._1) { query =>
                  for
                    acquire <- IO.fromEither(
                      LocalStateQuery
                        .encode(
                          LocalStateQuery.State.Idle,
                          Role.Client,
                          LocalStateQuery.Message.Acquire(LocalStateQuery.Target.VolatileTip)
                        )
                        .leftMap(error => new AssertionError(error.message))
                    )
                    _ <- query.send(acquire) *> query.flush
                    reply <- query.recvPrefix(LocalStateQuery.MaxResultBytes)(
                      LocalStateQuery.decodePrefix(LocalStateQuery.State.Acquiring, Role.Server, _)
                    )
                  yield reply
                }.timeout(10.seconds)
                _ <- firstClosed.get.timeout(10.seconds)
                atClose <- releasedAtClose.get.timeout(10.seconds)
                // the server goes on accepting: a second client agrees a version while the
                // server's stop, which would stop the node, has not come
                _ <- accepts.offer(Right(LocalStream(second._2, second._2.close)))
                next <- IO.race(stopped, client(second._1)(_ => IO.unit)).timeout(10.seconds)
                during <- events.get
              yield (answered, atClose, next, during)
          }
        }
        .rescue(failure => IO(fail(s"the local server did not start: $failure")))
    yield
      val (answered, atClose, next, during) = outcome
      assertEquals(
        answered.map(_ => ()),
        Left(NetworkError.BearerClosed(NetworkError.Closure.PeerClosed))
      )
      assertEquals(atClose, (true, true), "the view and the sibling protocol released first")
      assertEquals(next, Right(Right(())))
      assertEquals(
        during.collect { case NodeEvent.Inbound(remote, event) => remote -> event },
        Vector(
          socket -> InboundEvent.Accepted,
          socket -> InboundEvent.Closed(InboundEnd.Connection(ConnectionEnd.Failed(cause))),
          socket -> InboundEvent.Accepted
        )
      )
      assertEquals(
        during.collect {
          case NodeEvent.LocalServer(
                trace @ (LocalServer.Trace.ServerStopped | LocalServer.Trace.ServerError(_))
              ) =>
            trace
        },
        Vector.empty
      )
  }

  // issue 567: a handler's own exception ends its connection; the node goes on
  test("an unexpected handler failure reports the connection with its defect and does not stop") {
    val cause = new IllegalStateException("listener handler defect")
    reported(HandlerFailure.Defect(cause)).map { (events, fatals) =>
      assertEquals(
        events,
        Vector(
          NodeEvent.Inbound(
            "listener-test",
            InboundEvent.Closed(InboundEnd.Defect(cause))
          )
        )
      )
      assertEquals(fatals, Vector.empty[Fatal])
    }
  }

  test("a typed network defect also retains its original cause and does not stop the node") {
    val cause = new IllegalArgumentException("typed defect")
    reported(HandlerFailure.Domain(NetworkError.Defect(cause))).map { (events, fatals) =>
      assertEquals(
        events,
        Vector(
          NodeEvent.Inbound(
            "listener-test",
            InboundEvent.Closed(InboundEnd.Connection(ConnectionEnd.Defect(cause)))
          )
        )
      )
      assertEquals(fatals, Vector.empty[Fatal])
    }
  }
