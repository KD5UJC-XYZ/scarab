// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.Functor
import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.std.Queue
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import com.comcast.ip4s.*
import fs2.io.net.{Network, Socket}
import scala.concurrent.duration.*
import fs2.Chunk

class BearerServerSuite extends FailsOnUncaught:
  private val network = Network.forAsync[IO]
  private val refused = NetworkError.BearerClosed(NetworkError.Closure.PeerClosed)
  private val bug = new IllegalStateException("handler defect")

  /** A socket path error is a test failure unless the test reads it as a value. */
  private def pathErrors(into: Ref[IO, Option[BearerServer.SocketPathError]]) =
    new Raise[IO, BearerServer.SocketPathError]:
      val functor: Functor[IO] = Functor[IO]
      def raise[E2 <: BearerServer.SocketPathError, A](e: E2): IO[A] =
        into.set(Some(e)) *> IO.raiseError(new IllegalStateException(e.message))

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
        raised <- Ref.of[IO, Option[BearerServer.SocketPathError]](None)
        given Raise[IO, BearerServer.SocketPathError] = pathErrors(raised)
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

  test("a defect while constructing a handler effect is reported with its original cause") {
    for
      failures <- Queue.unbounded[IO, HandlerFailure[NetworkError]]
      _ <- BearerServer
        .listen[IO, NetworkError](Some(ip"127.0.0.1"), None)((_, error) => failures.offer(error)) {
          (_, _) => throw bug
        }
        .use { bound =>
          network.connect(bound).use(_.read(1)) *> failures.take.timeout(3.seconds).map { failure =>
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

  private def stat(path: java.nio.file.Path) =
    java.nio.file.Files.getPosixFilePermissions(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)

  test("the Unix socket is restricted to owner and group, and a stale socket is replaced") {
    fs2.io.file.Files.forAsync[IO].tempDirectory.use { dir =>
      val path = dir.toNioPath.resolve("node.socket")
      val listen = (raised: Ref[IO, Option[BearerServer.SocketPathError]]) =>
        given Raise[IO, BearerServer.SocketPathError] = pathErrors(raised)
        BearerServer.listenUnix[IO, NetworkError](path)(_ => IO.unit)(_ => IO.unit)
      for
        raised <- Ref.of[IO, Option[BearerServer.SocketPathError]](None)
        // a run that died leaves its socket behind: bound by the platform, closed, never unlinked
        _ <- IO.blocking {
          val channel = java.nio.channels.ServerSocketChannel
            .open(java.net.StandardProtocolFamily.UNIX)
          channel.bind(java.net.UnixDomainSocketAddress.of(path))
          channel.close()
        }
        stale <- IO.blocking(
          java.nio.file.Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)
        )
        _ <- listen(raised).use { _ =>
          IO(
            assertEquals(
              stat(path),
              java.nio.file.attribute.PosixFilePermissions.fromString("rw-rw----")
            )
          )
        }
        none <- raised.get
      yield
        assert(stale, "the earlier run left a socket file to replace")
        assertEquals(none, None)
    }
  }

  test("a path that is not a socket is refused and left alone") {
    fs2.io.file.Files.forAsync[IO].tempDirectory.use { dir =>
      val file = dir.toNioPath.resolve("not-a-socket")
      val link = dir.toNioPath.resolve("link")
      val directory = dir.toNioPath.resolve("directory")
      def attempt(path: java.nio.file.Path) =
        for
          raised <- Ref.of[IO, Option[BearerServer.SocketPathError]](None)
          given Raise[IO, BearerServer.SocketPathError] = pathErrors(raised)
          result <- BearerServer
            .listenUnix[IO, NetworkError](path)(_ => IO.unit)(_ => IO.unit)
            .use(_ => IO.unit)
            .attempt
          error <- raised.get
        yield (result.isLeft, error)
      for
        _ <- IO.blocking {
          java.nio.file.Files.writeString(file, "precious")
          java.nio.file.Files.createSymbolicLink(link, file)
          java.nio.file.Files.createDirectory(directory)
        }
        onFile <- attempt(file)
        onLink <- attempt(link)
        onDirectory <- attempt(directory)
        kept <- IO.blocking(java.nio.file.Files.readString(file))
      yield
        assertEquals(onFile, (true, Some(BearerServer.SocketPathError.NotASocket(file.toString))))
        assertEquals(onLink, (true, Some(BearerServer.SocketPathError.NotASocket(link.toString))))
        assertEquals(
          onDirectory,
          (true, Some(BearerServer.SocketPathError.NotASocket(directory.toString)))
        )
        assertEquals(kept, "precious")
        assert(
          java.nio.file.Files.isSymbolicLink(link) && java.nio.file.Files.isDirectory(directory)
        )
    }
  }
