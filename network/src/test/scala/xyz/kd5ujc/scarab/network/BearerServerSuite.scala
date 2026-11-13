// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.std.Queue
import cats.effect.testkit.TestControl
import cats.mtl.syntax.all.*
import com.comcast.ip4s.*
import fs2.io.net.{Network, Socket}
import scala.concurrent.duration.*
import fs2.{Chunk, Stream}

class BearerServerSuite extends FailsOnUncaught:
  private val network = Network.forAsync[IO]
  private val refused = NetworkError.BearerClosed(NetworkError.Closure.PeerClosed)
  private val bug = new IllegalStateException("handler defect")
  private val jdk = TcpListeners.jdk[IO]
  private val quiet = (_: BearerServer.Trace) => IO.unit
  private val exhausted = SystemCallError(SystemCall.Accept, ErrnoCause.Known(Errno.EMFILE), None)
  private val aborted =
    SystemCallError(SystemCall.Accept, ErrnoCause.Known(Errno.ECONNABORTED), None)

  /** A listener whose accepts return `results` in turn, then wait for ever; `calls` counts them. */
  private def scripted(
      results: List[Either[AcceptFailure, TcpStream[IO]]],
      calls: Ref[IO, Int]
  ): TcpListeners[IO] = new TcpListeners[IO]:
    def bind(requested: SocketAddress[IpAddress]): Resource[IO, TcpListener[IO]] =
      Resource.eval(Ref.of[IO, List[Either[AcceptFailure, TcpStream[IO]]]](results)).map { left =>
        new TcpListener[IO]:
          val address: SocketAddress[IpAddress] = SocketAddress(ip"127.0.0.1", port"3001")
          def accept: IO[Either[AcceptFailure, TcpStream[IO]]] =
            calls.update(_ + 1) *> left
              .modify {
                case next :: rest => (rest, Some(next))
                case Nil          => (Nil, None)
              }
              .flatMap(_.fold(IO.never)(IO.pure))
      }

  /** A connection with nothing to read whose close completes `closed`. */
  private def idle(closed: Deferred[IO, Unit]): TcpStream[IO] =
    TcpStream(
      new Bearer[IO]:
        def reads: Stream[IO, Byte] = Stream.empty
        def write(bytes: Chunk[Byte]): IO[Unit] = IO.unit
        def close: IO[Unit] = IO.unit
      ,
      SocketAddress(ip"127.0.0.2", port"4000"),
      closed.complete(()).void
    )

  private def exercise(
      connect: Resource[IO, Socket[IO]],
      failures: Queue[IO, HandlerFailure[NetworkError]]
  ): IO[Unit] =
    for
      _ <- connect.use(_.read(1).map(bytes => assertEquals(bytes, None)))
      domain <- failures.take.timeout(3.seconds)
      _ <- IO(assertEquals(domain, HandlerFailure.Domain(refused)))
      _ <- connect.use(_.read(1).map(bytes => assertEquals(bytes, None)))
      defect <- failures.take.timeout(3.seconds)
      _ <- IO(assert(defect match
        case HandlerFailure.Defect(cause) => cause eq bug
        case _                            => false))
      // With one handler slot, this also proves each failed connection released its permit/socket.
      healthy <- connect.use(_.read(1))
      _ <- IO(assertEquals(healthy.map(_.toVector), Some(Vector(42.toByte))))
      extra <- failures.tryTake
      _ <- IO(assertEquals(extra, None))
    yield ()

  test("TCP reports original domain/defect values and accepts a healthy subsequent connection") {
    for
      count <- Ref.of[IO, Int](0)
      failures <- Queue.unbounded[IO, HandlerFailure[NetworkError]]
      _ <- BearerServer
        .listen[IO, NetworkError](jdk, Some(ip"127.0.0.1"), None, quiet, maxConnections = Some(1))(
          (_, error) => failures.offer(error)
        ) { (bearer, _) =>
          count.getAndUpdate(_ + 1).flatMap {
            case 0 => refused.raise[IO, Unit]
            case 1 => IO.raiseError(bug)
            case _ => bearer.write(Chunk.singleton(42.toByte))
          }
        }
        .use(listening => exercise(network.connect(listening.address), failures))
    yield ()
  }

  test("beforeAccept runs before each accept, so a pause there holds the next accept back") {
    for
      gate <- Deferred[IO, Unit]
      held <- Deferred[IO, Unit]
      calls <- Ref.of[IO, Int](0)
      served <- Queue.unbounded[IO, Unit]
      before = calls.getAndUpdate(_ + 1).flatMap {
        case 0 => IO.unit
        case _ => held.complete(()) *> gate.get
      }
      _ <- BearerServer
        .listen[IO, NetworkError](jdk, Some(ip"127.0.0.1"), None, quiet, None, Some(before))(
          (_, _) => IO.unit
        )((_, _) => served.offer(()))
        .use { listening =>
          val bound = listening.address
          for
            // the served connection is closed although the next accept is held back
            _ <- network.connect(bound).use(_.read(1).timeout(3.seconds).map(assertEquals(_, None)))
            _ <- served.take.timeout(3.seconds)
            // the second accept waits in `beforeAccept`: this connection is not served yet
            _ <- held.get.timeout(3.seconds)
            second <- network.connect(bound).use(_ => served.tryTake)
            _ <- IO(assertEquals(second, None))
            _ <- gate.complete(())
            _ <- served.take.timeout(3.seconds)
          yield ()
        }
    yield ()
  }

  test("a defect while constructing a handler effect is reported with its original cause") {
    for
      failures <- Queue.unbounded[IO, HandlerFailure[NetworkError]]
      _ <- BearerServer
        .listen[IO, NetworkError](jdk, Some(ip"127.0.0.1"), None, quiet)((_, error) =>
          failures.offer(error)
        ) { (_, _) =>
          throw bug
        }
        .use { listening =>
          network
            .connect(listening.address)
            .use(_.read(1)) *> failures.take.timeout(3.seconds).map { failure =>
            assertEquals(failure, HandlerFailure.Defect(bug))
          }
        }
    yield ()
  }

  test("listener cancellation releases the active handler and reports no failure") {
    for
      entered <- Deferred[IO, Unit]
      released <- Deferred[IO, Unit]
      failures <- Ref.of[IO, Vector[HandlerFailure[NetworkError]]](Vector.empty)
      _ <- BearerServer
        .listen[IO, NetworkError](jdk, Some(ip"127.0.0.1"), None, quiet)((_, error) =>
          failures.update(_ :+ error)
        ) { (_, _) =>
          (entered.complete(()) *> IO.never).guarantee(released.complete(()).void)
        }
        .use(listening =>
          network.connect(listening.address).use(_ => entered.get.timeout(3.seconds))
        )
      _ <- released.get.timeout(3.seconds)
      errors <- failures.get
    yield assertEquals(errors, Vector.empty)
  }

  test("an accept that fails other than with ECONNABORTED is traced and stops the server with it") {
    TestControl
      .executeEmbed(
        for
          traces <- Ref.of[IO, Vector[BearerServer.Trace]](Vector.empty)
          calls <- Ref.of[IO, Int](0)
          stopped <- BearerServer
            .listen[IO, NetworkError](
              scripted(List(Left(AcceptFailure.System(exhausted))), calls),
              None,
              None,
              trace => traces.update(_ :+ trace)
            )((_, _) => IO.unit)((_, _) => IO.unit)
            .use(_.stopped)
          seen <- traces.get
          tried <- calls.get
        yield (stopped, seen, tried)
      )
      .map { (stopped, seen, tried) =>
        assertEquals(stopped, DiffusionFailure.System(exhausted))
        assertEquals(
          seen,
          Vector(
            BearerServer.Trace.AcceptError(AcceptFailure.System(exhausted)),
            BearerServer.Trace.ServerStopped,
            BearerServer.Trace.ServerError(DiffusionFailure.System(exhausted))
          )
        )
        assertEquals(tried, 1, "the server does not accept again after the failure")
      }
  }

  test(
    "an accept that fails with ECONNABORTED is traced, and the server accepts again 500 ms later"
  ) {
    TestControl
      .executeEmbed(
        for
          traces <- Ref.of[IO, Vector[BearerServer.Trace]](Vector.empty)
          calls <- Ref.of[IO, Int](0)
          closed <- Deferred[IO, Unit]
          served <- Deferred[IO, FiniteDuration]
          start <- IO.monotonic
          result <- BearerServer
            .listen[IO, NetworkError](
              scripted(List(Left(AcceptFailure.System(aborted)), Right(idle(closed))), calls),
              None,
              None,
              trace => traces.update(_ :+ trace)
            )((_, _) => IO.unit)((_, _) => IO.monotonic.flatMap(served.complete).void)
            .use(_ =>
              for
                at <- served.get
                _ <- closed.get
                seen <- traces.get
              yield (at - start, seen)
            )
        yield result
      )
      .map { (elapsed, seen) =>
        assertEquals(elapsed, 500.millis, "the second accept waits for the reference's delay")
        assertEquals(seen, Vector(BearerServer.Trace.AcceptError(AcceptFailure.System(aborted))))
      }
  }

  test("the owner learns that the accept loop stopped even if tracing it fails") {
    val unwritable = new java.io.IOException("No space left on device")
    TestControl
      .executeEmbed(
        Ref
          .of[IO, Int](0)
          .flatMap(calls =>
            BearerServer
              .listen[IO, NetworkError](
                scripted(List(Left(AcceptFailure.System(exhausted))), calls),
                None,
                None,
                {
                  case BearerServer.Trace.ServerStopped => IO.raiseError(unwritable)
                  case _                                => IO.unit
                }
              )((_, _) => IO.unit)((_, _) => IO.unit)
              .use(_.stopped)
          )
      )
      .map(stopped => assertEquals(stopped, DiffusionFailure.System(exhausted)))
  }

  test("an accept that fails with a defect is traced and stops the server with it") {
    val broken = new IllegalStateException("accept pending")
    TestControl
      .executeEmbed(
        for
          traces <- Ref.of[IO, Vector[BearerServer.Trace]](Vector.empty)
          calls <- Ref.of[IO, Int](0)
          stopped <- BearerServer
            .listen[IO, NetworkError](
              scripted(List(Left(AcceptFailure.Defect(broken))), calls),
              None,
              None,
              trace => traces.update(_ :+ trace)
            )((_, _) => IO.unit)((_, _) => IO.unit)
            .use(_.stopped)
          seen <- traces.get
        yield (stopped, seen)
      )
      .map { (stopped, seen) =>
        assertEquals(stopped, DiffusionFailure.Defect(broken))
        assertEquals(
          seen.headOption,
          Some(BearerServer.Trace.AcceptError(AcceptFailure.Defect(broken)))
        )
      }
  }

  test("a connection whose close fails still frees its slot") {
    TestControl
      .executeEmbed(
        for
          calls <- Ref.of[IO, Int](0)
          first <- Deferred[IO, Unit]
          second <- Deferred[IO, Unit]
          served <- Queue.unbounded[IO, Unit]
          failing = idle(first).copy(close =
            first.complete(()) *> IO.raiseError(new java.io.IOException("close failed"))
          )
          _ <- BearerServer
            .listen[IO, NetworkError](
              scripted(List(Right(failing), Right(idle(second))), calls),
              None,
              None,
              quiet,
              maxConnections = Some(1)
            )((_, _) => IO.unit)((_, _) => served.offer(()))
            .use(_ => served.take *> served.take *> second.get)
          tried <- calls.get
        yield tried
      )
      // with one slot, the second client is served only once the first one's slot is free
      .map(tried => assert(tried >= 2, s"accepts: $tried"))
  }
