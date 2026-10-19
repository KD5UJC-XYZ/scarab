// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.std.Queue
import cats.mtl.syntax.all.*
import com.comcast.ip4s.*
import fs2.io.net.{Network, Socket}
import scala.concurrent.duration.*
import fs2.Chunk

class BearerServerSuite extends FailsOnUncaught:
  private val network = Network.forAsync[IO]
  private val refused = NetworkError.BearerClosed(NetworkError.Closure.PeerClosed)
  private val bug = new IllegalStateException("handler defect")

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
        .listen[IO, NetworkError](Some(ip"127.0.0.1"), None, maxConnections = 1)((_, error) =>
          failures.offer(error)
        ) { (bearer, _) =>
          count.getAndUpdate(_ + 1).flatMap {
            case 0 => refused.raise[IO, Unit]
            case 1 => IO.raiseError(bug)
            case _ => bearer.write(Chunk.singleton(42.toByte))
          }
        }
        .use(bound => exercise(network.connect(bound), failures))
    yield ()
  }

  test("Unix reports original domain/defect values and accepts a healthy subsequent connection") {
    fs2.io.file.Files.forAsync[IO].tempDirectory.use { dir =>
      val path = dir.toNioPath.resolve("node.socket")
      for
        count <- Ref.of[IO, Int](0)
        failures <- Queue.unbounded[IO, HandlerFailure[NetworkError]]
        _ <- BearerServer
          .listenUnix[IO, NetworkError](path, maxConnections = 1)(failures.offer) { bearer =>
            count.getAndUpdate(_ + 1).flatMap {
              case 0 => refused.raise[IO, Unit]
              case 1 => IO.raiseError(bug)
              case _ => bearer.write(Chunk.singleton(42.toByte))
            }
          }
          .use(_ => exercise(network.connect(UnixSocketAddress(path.toString)), failures))
      yield ()
    }
  }

  test("listener cancellation releases the active handler and reports no failure") {
    for
      entered <- Deferred[IO, Unit]
      released <- Deferred[IO, Unit]
      failures <- Ref.of[IO, Vector[HandlerFailure[NetworkError]]](Vector.empty)
      _ <- BearerServer
        .listen[IO, NetworkError](Some(ip"127.0.0.1"), None)((_, error) =>
          failures.update(_ :+ error)
        ) { (_, _) =>
          (entered.complete(()) *> IO.never).guarantee(released.complete(()).void)
        }
        .use(bound => network.connect(bound).use(_ => entered.get.timeout(3.seconds)))
      _ <- released.get.timeout(3.seconds)
      errors <- failures.get
    yield assertEquals(errors, Vector.empty)
  }
