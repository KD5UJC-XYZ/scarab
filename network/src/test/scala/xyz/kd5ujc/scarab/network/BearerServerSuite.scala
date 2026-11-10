// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.std.Queue
import cats.mtl.Handle
import cats.mtl.syntax.all.*
import com.comcast.ip4s.*
import fs2.io.net.{Network, Socket}
import scala.concurrent.duration.*
import fs2.Chunk
import xyz.kd5ujc.scarab.network.selection.DiffusionMode

class BearerServerSuite extends FailsOnUncaught:
  private val network = Network.forAsync[IO]
  private val refused = NetworkError.BearerClosed(NetworkError.Closure.PeerClosed)
  private val bug = new IllegalStateException("handler defect")

  /** The server's end of a connection closes with linger 0 once its handler ends: the client reads
    * a reset, not the end of the stream (`configureSocket`, `Socket.hs` 148-170).
    */
  private def reset(socket: Socket[IO]): IO[Unit] =
    socket.read(1).attempt.timeout(3.seconds).map {
      case Left(e: java.io.IOException) => assertEquals(e.getMessage, "Connection reset")
      case other                        => fail(s"expected a reset, got $other")
    }

  private def exercise(
      connect: Resource[IO, Socket[IO]],
      failures: Queue[IO, HandlerFailure[NetworkError]]
  ): IO[Unit] =
    for
      _ <- connect.use(reset)
      domain <- failures.take.timeout(3.seconds)
      _ <- IO(assertEquals(domain, HandlerFailure.Domain(refused)))
      _ <- connect.use(reset)
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
      _ <- Listeners
        .loopback[NetworkError](maxConnections = Some(1))((_, error) => failures.offer(error)) {
          (bearer, _) =>
            // The client has completed connect before sending this byte; linger 0 cannot race it.
            bearer.reads.take(1).compile.drain *> count.getAndUpdate(_ + 1).flatMap {
              case 0 => refused.raise[IO, Unit]
              case 1 => IO.raiseError(bug)
              case _ => bearer.write(Chunk.singleton(42.toByte))
            }
        }
        .use(bound =>
          exercise(network.connect(bound).evalTap(_.write(Chunk.singleton(0.toByte))), failures)
        )
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
      _ <- Listeners
        .loopback[NetworkError](maxConnections = None, Some(before))((_, _) => IO.unit)(
          (bearer, _) => bearer.reads.take(1).compile.drain *> served.offer(())
        )
        .use { bound =>
          for
            // the served connection is closed although the next accept is held back
            _ <- network.connect(bound).evalTap(_.write(Chunk.singleton(0.toByte))).use(reset)
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
      _ <- Listeners
        .loopback[NetworkError]()((_, error) => failures.offer(error)) { (_, _) =>
          throw bug
        }
        .use { bound =>
          network.connect(bound).use(_.read(1)).attempt *> failures.take.timeout(3.seconds).map {
            failure =>
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
      _ <- Listeners
        .loopback[NetworkError]()((_, error) => failures.update(_ :+ error)) { (_, _) =>
          (entered.complete(()) *> IO.never).guarantee(released.complete(()).void)
        }
        .use(bound => network.connect(bound).use(_ => entered.get.timeout(3.seconds)))
      _ <- released.get.timeout(3.seconds)
      errors <- failures.get
    yield assertEquals(errors, Vector.empty)
  }

  test(
    "the listener sets SO_REUSEPORT: the node's outbound socket binds to its port and connects"
  ) {
    // binding to the port of a listener without SO_REUSEPORT (fs2's) fails with EADDRINUSE
    // (`TcpSocketsSuite`), and the reference binds its outbound sockets to the listening address
    Listeners.loopback[NetworkError]()((_, _) => IO.unit)((_, _) => IO.never).use { node =>
      network.bind(Listeners.Loopback).use { server =>
        val (peer, accepted) = (server.address.asIpUnsafe, server.accept)
        val own = NodeAddresses(Some(SocketAddress(ipv4"127.0.0.1", node.port)), None)
        TcpSockets
          .connect[IO](
            OutboundPlan.of(peer, own, DiffusionMode.InitiatorAndResponder),
            3.seconds
          )
          .use(dialled =>
            IO(assertEquals(dialled.map(_.local), Right(node))) *>
              accepted.take(1).compile.lastOrError.flatMap { inbound =>
                IO(assertEquals(inbound.peerAddress.asIpUnsafe, node))
              }
          )
      }
    }
  }

  test("a listener that cannot bind raises its typed error") {
    network.bind(Listeners.Loopback).map(_.address.asIpUnsafe).use { taken =>
      Handle
        .allow[TcpSocketError](
          BearerServer
            .listen[IO, NetworkError](taken)((_, _) => IO.unit)((_, _) => IO.unit)
            .use(_ => IO.pure(Option.empty[TcpSocketError]))
        )
        .rescue(error => IO.pure(Some(error)))
        .map(raised =>
          assertEquals(
            raised,
            Some(
              TcpSocketError
                .Failed(SocketStep.Bind(taken), ErrnoCause.Known(Errno.EADDRINUSE))
            )
          )
        )
    }
  }
