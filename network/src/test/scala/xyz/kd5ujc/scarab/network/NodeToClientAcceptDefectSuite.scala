// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.Functor
import cats.effect.{Async, Deferred, IO, Ref, Resource}
import cats.mtl.{Handle, Raise}
import cats.syntax.all.*
import java.nio.file.Files
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.{Handshake, LocalStateQuery, LocalTxSubmission, ProtocolError}
import xyz.kd5ujc.scarab.protocol.ChainSync.Role
import xyz.kd5ujc.scarab.protocol.LocalStateQuery.{Message, State, Target}

/** A decoder that throws on a node-to-client connection accepted on a local socket (issue 464).
  * `accept` builds its own `Mux` and a real codec never throws, so `DecoderDefectSuite` can only
  * show the policy where a mini-protocol runs, with the channel handed to it. Here the connection
  * is the one the node runs over its socket, and the multiplexer it is served on decodes one of its
  * mini-protocols by throwing: the reference's `RethrowPolicy.hs` takes that for `ShutdownPeer`, a
  * decision about the one connection, so the connection ends with `DecoderDefect` and the listener
  * serves the next client.
  */
class NodeToClientAcceptDefectSuite extends FailsOnUncaught:
  /** A socket this test cannot set up fails the test, as the node refuses its start. */
  private given Raise[IO, DiffusionFailure] with
    def functor: Functor[IO] = Functor[IO]
    def raise[E <: DiffusionFailure, A](e: E): IO[A] = IO(fail(e.show))

  private val magic = 42L
  private val cause = new IllegalStateException("injected decoder defect")

  private type Path = java.nio.file.Path

  /** The node on its local socket: how each connection ended, and the end of the first one, so the
    * client that follows it does not race the connection that failed.
    */
  private final case class Node(
      path: Path,
      failures: Ref[IO, Vector[NetworkError]],
      firstEnded: Deferred[IO, Unit]
  )

  /** The state the toy ledger answers with. */
  private val state: Bytes = Bytes(Vector(0x2a))

  /** A ledger that answers every query with [[state]], for as long as the client holds the state.
    */
  private val ledger: LocalStateQueryServer.Source[IO, Nothing] =
    new LocalStateQueryServer.Source[IO, Nothing]:
      def queries = LocalStateQuery.QueryReader.items

      def acquire(target: Target) =
        Resource.pure[IO, Either[
          LocalStateQueryServer.AcquireFailure[Nothing],
          LocalStateQueryServer.Session[IO, Nothing]
        ]](
          Right(
            new LocalStateQueryServer.Session[IO, Nothing]:
              def answer(query: Bytes) =
                IO.pure(
                  Right(Right(state)): Either[
                    Nothing,
                    Either[LocalStateQueryServer.Refusal, Bytes]
                  ]
                )
          )
        )

  /** A socket after the reference's own: its file stays when the server stops, so the directory is
    * removed here by the test that made it.
    */
  private def socket: Resource[IO, Path] =
    Resource
      .make(IO.blocking(Files.createTempDirectory("scarab-ntc-accept-defect")))(dir =>
        IO.blocking {
          Files.deleteIfExists(dir.resolve("node.socket"))
          Files.deleteIfExists(dir)
        }.void
      )
      .map(_.resolve("node.socket"))

  /** The node's local socket, each connection served by [[NodeToClientServer.acceptOn]] over a
    * multiplexer whose `poisoned` protocol decodes by throwing. Only the first connection is
    * poisoned, so the second one is served by the node that survived it.
    */
  private def withNode(
      poisoned: Int,
      stateQuery: Option[LocalStateQueryServer.Source[IO, Nothing]]
  )(body: Node => IO[Unit]): IO[Unit] =
    socket
      .flatMap { path =>
        for
          refs <- Resource.eval(
            (
              Ref.of[IO, Int](0),
              Ref.of[IO, Vector[NetworkError]](Vector.empty),
              Deferred[IO, Unit]
            ).tupled
          )
          (accepted, failures, firstEnded) = refs
          _ <- LocalServer.resource[IO, NetworkError](
            LocalSockets.jdk[IO],
            LocalAddress(path.toString),
            _ => IO.unit
          )((_, failure) =>
            failure match
              case HandlerFailure.Domain(error) =>
                failures.update(_ :+ error).void *> firstEnded.complete(()).void
              case HandlerFailure.Defect(thrown) =>
                IO(fail(s"the connection was a defect of the node: $thrown"))
          )((bearer, _) =>
            NodeToClientServer
              .acceptBackend[IO, Nothing](
                bearer,
                magic,
                _ => IO.pure(LocalTxSubmissionServer.Response.Accepted),
                stateQuery,
                openMux = new NodeToClientServer.MuxFactory[IO]:
                  def open(bearer: Bearer[IO], served: Set[Int])(using
                      Async[IO],
                      Raise[IO, NetworkError]
                  ): Resource[IO, Mux[IO]] =
                    NodeToClientServer.MuxFactory
                      .nodeToClient[IO]
                      .open(bearer, served)
                      .evalMap(opened =>
                        accepted
                          .updateAndGet(_ + 1)
                          .map(nth =>
                            if nth == 1 then PoisonedMux(opened, poisoned, cause) else opened
                          )
                      )
              )
              .map(_.fold(identity, identity))
              .void
          )
        yield Node(path, failures, firstEnded)
      }
      .use(body)

  /** The node's response to one client of `protocol` on the socket, or the network error that ended
    * its connection.
    */
  private def client[A](path: Path, protocol: Int)(
      body: MuxChannel[IO] => Handle[IO, NetworkError] ?=> IO[A]
  ): IO[Either[NetworkError, A]] =
    LocalClient.connect[IO](path).use { bearer =>
      Handle
        .allow[NetworkError] {
          Mux
            .resource(
              bearer,
              Set(Protocols.Handshake, protocol),
              Mux.Config(role = Mux.Role.Initiator)
            )
            .use { mux =>
              for
                handshake <- mux.channel(Protocols.Handshake)
                _ <- HandshakeClient.run(
                  handshake,
                  Vector(23 -> Handshake.Data(magic)),
                  suite = Handshake.Suite.NodeToClient
                )
                channel <- mux.channel(protocol)
                answer <- body(channel)
              yield answer.asRight[NetworkError]
            }
        }
        .rescue(error => IO.pure(error.asLeft[A]))
    }

  private def send(
      channel: MuxChannel[IO],
      encoded: Either[ProtocolError, Bytes]
  )(using Handle[IO, NetworkError]): IO[Unit] =
    encoded.fold(
      error =>
        Handle[IO, NetworkError].raise(NetworkError.IllegalTransition(channel.protocol, error)),
      channel.send
    )

  /** Submits one transaction and waits for the node's answer. */
  private def submit(path: Path): IO[Either[NetworkError, LocalTxSubmission.Message]] =
    client(path, Protocols.LocalTxSubmission) { channel =>
      for
        _ <- send(
          channel,
          LocalTxSubmission.encode(
            LocalTxSubmission.State.Idle,
            Role.Client,
            LocalTxSubmission.Message.SubmitTx(Bytes(Vector(0x80.toByte)))
          )
        )
        answer <- channel.recv(LocalTxSubmission.MaxMessageBytes)(
          LocalTxSubmission.decodePrefix(LocalTxSubmission.State.Busy, Role.Server, _)
        )
      yield answer
    }

  /** Acquires the volatile tip and queries the toy ledger. */
  private def query(path: Path): IO[Either[NetworkError, Message]] =
    client(path, Protocols.LocalStateQuery) { channel =>
      for
        _ <- send(
          channel,
          LocalStateQuery.encode(State.Idle, Role.Client, Message.Acquire(Target.VolatileTip))
        )
        acquired <- channel.recv(LocalStateQuery.MaxResultBytes)(
          LocalStateQuery.decodePrefix(State.Acquiring, Role.Server, _)
        )
        _ <- IO(assertEquals(acquired, Message.Acquired))
        _ <- send(
          channel,
          LocalStateQuery.encode(State.Acquired, Role.Client, Message.Query(Bytes(Vector(0x01))))
        )
        answer <- channel.recv(LocalStateQuery.MaxResultBytes)(
          LocalStateQuery.decodePrefix(State.Querying, Role.Server, _)
        )
      yield answer
    }

  /** The one connection that failed, as the node saw it. */
  private def onlyDecoderDefect(node: Node, protocol: Int): IO[Unit] =
    node.failures.get.map {
      case Vector(NetworkError.DecoderDefect(seen, thrown)) =>
        assertEquals(seen, protocol)
        assert(thrown eq cause, s"the original exception is kept, got $thrown")
      case other => fail(s"expected one DecoderDefect, got $other")
    }

  /** Two clients of `protocol`: the first on a connection whose decoder throws, the second on a
    * healthy one that must be served with `expected`.
    */
  private def failsAlone[A](
      protocol: Int,
      stateQuery: Option[LocalStateQueryServer.Source[IO, Nothing]]
  )(request: Path => IO[Either[NetworkError, A]])(expected: A): IO[Unit] =
    withNode(protocol, stateQuery) { node =>
      for
        failed <- request(node.path)
        _ <- node.firstEnded.get
        served <- request(node.path)
        _ <- onlyDecoderDefect(node, protocol)
      yield
        failed match
          case Left(_: NetworkError.BearerClosed) => ()
          case other => fail(s"expected the connection to close without an answer, got $other")
        assertEquals(served, Right(expected))
    }

  test(
    "local submission: a throwing decoder ends that connection only, and the listener serves the next client"
  ) {
    failsAlone(Protocols.LocalTxSubmission, None)(submit)(LocalTxSubmission.Message.AcceptTx)
  }

  test(
    "local state query: a throwing decoder ends that connection only, and the listener serves the next client"
  ) {
    failsAlone(Protocols.LocalStateQuery, Some(ledger))(query)(Message.Result(state))
  }
