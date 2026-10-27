// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.std.Queue
import cats.mtl.{Handle, Raise}
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import com.comcast.ip4s.*
import fs2.io.net.{Network, Socket}
import scala.concurrent.duration.*
import fs2.Chunk
import java.nio.file.{Files, LinkOption, Path}
import java.nio.file.attribute.PosixFilePermissions

import BearerServer.{InsecureLocalSocket, LocalSocketError}

class BearerServerSuite extends FailsOnUncaught:
  private val network = Network.forAsync[IO]
  private val refused = NetworkError.BearerClosed(NetworkError.Closure.PeerClosed)
  private val bug = new IllegalStateException("handler defect")

  /** `body` with its local socket errors as values. */
  private def attempt[A](body: Raise[IO, LocalSocketError] ?=> IO[A]) =
    Handle.allow[LocalSocketError](body.map(Right(_))).rescue(e => IO.pure(Left(e)))

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
        .listen[IO, NetworkError](Some(ip"127.0.0.1"), None, maxConnections = Some(1))((_, error) =>
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

  test("beforeAccept runs before each accept, so a pause there holds the next accept back") {
    for
      gate <- Deferred[IO, Unit]
      calls <- Ref.of[IO, Int](0)
      served <- Queue.unbounded[IO, Unit]
      before = calls.getAndUpdate(_ + 1).flatMap {
        case 0 => IO.unit
        case _ => gate.get
      }
      _ <- BearerServer
        .listen[IO, NetworkError](Some(ip"127.0.0.1"), None, maxConnections = None, Some(before))(
          (_, _) => IO.unit
        )((_, _) => served.offer(()))
        .use { bound =>
          for
            // the served connection is closed although the next accept is held back
            _ <- network.connect(bound).use(_.read(1).timeout(3.seconds).map(assertEquals(_, None)))
            _ <- served.take.timeout(3.seconds)
            // the second accept waits in `beforeAccept`: this connection is not served yet
            second <- network.connect(bound).use(_ => IO.sleep(300.millis) *> served.tryTake)
            _ <- IO(assertEquals(second, None))
            _ <- gate.complete(())
            _ <- served.take.timeout(3.seconds)
          yield ()
        }
    yield ()
  }

  test("Unix reports original domain/defect values and accepts a healthy subsequent connection") {
    fs2.io.file.Files.forAsync[IO].tempDirectory.use { dir =>
      val path = dir.toNioPath.resolve("node.socket")
      for
        count <- Ref.of[IO, Int](0)
        failures <- Queue.unbounded[IO, HandlerFailure[NetworkError]]
        served <- attempt(
          BearerServer
            .listenUnix[IO, NetworkError](path, maxConnections = 1)(failures.offer, _ => IO.unit) {
              bearer =>
                count.getAndUpdate(_ + 1).flatMap {
                  case 0 => refused.raise[IO, Unit]
                  case 1 => IO.raiseError(bug)
                  case _ => bearer.write(Chunk.singleton(42.toByte))
                }
            }
            .use(_ => exercise(network.connect(UnixSocketAddress(path.toString)), failures))
        )
      yield assertEquals(served, Right(()))
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

  /** Bind and close a Unix socket at `path`, as a run that died leaves it: never unlinked. */
  private def staleSocket(path: Path): IO[Unit] = IO.blocking {
    val channel = java.nio.channels.ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX)
    channel.bind(java.net.UnixDomainSocketAddress.of(path))
    channel.close()
  }

  private def statMode(path: Path): IO[Int] =
    IO.blocking(Files.getAttribute(path, "unix:mode").asInstanceOf[Integer].toInt)

  private def octal(digits: String): Int = Integer.parseInt(digits, 8)

  private def chmod(path: Path, mode: String): IO[Unit] =
    IO.blocking(Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode))).void

  /** Listen at `path` with the socket's warnings collected, and connect once while it is up: the
    * bound socket's mode, or the error, and the warnings.
    */
  private def listenOnce(path: Path) =
    for
      warnings <- Ref.of[IO, Vector[InsecureLocalSocket]](Vector.empty)
      result <- attempt(
        BearerServer
          .listenUnix[IO, NetworkError](path)(_ => IO.unit, w => warnings.update(_ :+ w))(_ =>
            IO.unit
          )
          .use(_ =>
            network.connect(UnixSocketAddress(path.toString)).use(_ => IO.unit) *> statMode(path)
          )
      )
      seen <- warnings.get
    yield (result, seen)

  test(
    "a stale socket, a file and a symbolic link at the socket path are removed before the bind"
  ) {
    fs2.io.file.Files.forAsync[IO].tempDirectory.use { dir =>
      val root = dir.toNioPath
      val path = root.resolve("node.socket")
      val target = root.resolve("target")
      val targetDirectory = root.resolve("target-directory")
      def isSocket(p: Path) = statMode(p).map(m => (m & 0xf000) == 0xc000)
      for
        _ <- staleSocket(path)
        stale <- isSocket(path)
        onSocket <- listenOnce(path).map(_._1.void)
        _ <- IO.blocking(Files.writeString(path, "a file"))
        onFile <- listenOnce(path).map(_._1.void)
        _ <- IO.blocking {
          Files.writeString(target, "kept")
          Files.deleteIfExists(path)
          Files.createSymbolicLink(path, target)
        }
        onLink <- listenOnce(path).map(_._1.void)
        _ <- IO.blocking {
          Files.createDirectory(targetDirectory)
          Files.deleteIfExists(path)
          Files.createSymbolicLink(path, targetDirectory)
        }
        onDirectoryLink <- listenOnce(path).map(_._1.void)
        kept <- IO.blocking(Files.readString(target))
        keptDirectory <- IO.blocking(Files.isDirectory(targetDirectory))
      yield
        assert(stale, "the earlier run left a socket to replace")
        assertEquals(List(onSocket, onFile, onLink, onDirectoryLink), List.fill(4)(Right(())))
        assertEquals(kept, "kept")
        assert(keptDirectory, "the directory a link pointed at is left")
    }
  }

  test("a missing socket path is fine, and a directory there refuses the start and is left") {
    fs2.io.file.Files.forAsync[IO].tempDirectory.use { dir =>
      val root = dir.toNioPath
      val empty = root.resolve("empty")
      val full = root.resolve("full")
      for
        missing <- listenOnce(root.resolve("node.socket")).map(_._1.void)
        _ <- IO.blocking {
          Files.createDirectory(empty)
          Files.createDirectory(full)
          Files.writeString(full.resolve("inside"), "kept")
        }
        onEmpty <- listenOnce(empty).map(_._1.void)
        onFull <- listenOnce(full).map(_._1.void)
        left <- IO.blocking(
          Files.isDirectory(empty, LinkOption.NOFOLLOW_LINKS) &&
            Files.readString(full.resolve("inside")) == "kept"
        )
      yield
        assertEquals(missing, Right(()))
        assertEquals(onEmpty, Left(LocalSocketError.Directory(empty.toString)))
        assertEquals(onFull, Left(LocalSocketError.Directory(full.toString)))
        assert(left, "both directories are left as they were")
    }
  }

  test("the bound socket is checked: warned exactly as its mode and its directory's mode say") {
    fs2.io.file.Files.forAsync[IO].tempDirectory.use { dir =>
      val root = dir.toNioPath
      val path = root.resolve("node.socket")
      val shown = path.toString
      for
        // the socket's mode is the umask's, which the test does not set: expected from what it is
        first <- listenOnce(path)
        _ <- chmod(root, "rwx-w----")
        groupWritable <- listenOnce(path)
        _ <- chmod(root, "rwx---r-x")
        othersRead <- listenOnce(path)
        directoryMode <- statMode(root)
      yield
        val modes = List(first, groupWritable, othersRead).map(_._1)
        // the mode is the whole st_mode, a socket's (S_IFSOCK)
        modes.foreach(mode => assert(mode.exists(m => (m & 0xf000) == 0xc000), mode))
        val socketWarning =
          modes.map(_.toOption.flatMap(BearerServer.permissions(shown, _)).toVector)
        assertEquals(first._2, socketWarning(0))
        assertEquals(
          groupWritable._2,
          socketWarning(1) :+ InsecureLocalSocket.Directory(shown, octal("40720"))
        )
        assertEquals(othersRead._2, socketWarning(2))
        assertEquals(directoryMode, octal("40705"))
    }
  }

  test(
    "the warnings: others reading or writing the socket, the group or others writing its directory"
  ) {
    val bits = (0 until 9).map(1 << _)
    val socketWarned = bits.filter(b => BearerServer.permissions("s", 0xc000 | b).nonEmpty)
    val directoryWarned = bits.filter(b => BearerServer.directory("s", 0x4000 | b).nonEmpty)
    // S_IWOTH and S_IROTH; S_IWOTH and S_IWGRP
    assertEquals(socketWarned, Vector(octal("2"), octal("4")))
    assertEquals(directoryWarned, Vector(octal("2"), octal("20")))
    assertEquals(
      BearerServer.permissions("/run/node.socket", octal("140755")),
      Some(InsecureLocalSocket.Permissions("/run/node.socket", octal("140755")))
    )
    assertEquals(BearerServer.permissions("/run/node.socket", octal("140770")), None)
    assertEquals(
      BearerServer.directory("/run/node.socket", octal("40777")),
      Some(InsecureLocalSocket.Directory("/run/node.socket", octal("40777")))
    )
    assertEquals(BearerServer.directory("/run/node.socket", octal("40755")), None)
  }

  test("the socket's directory is what is before the last slash, as checkLocalSocket takes it") {
    val cases = List(
      "node.socket" -> ".",
      "./node.socket" -> ".",
      "ipc/node.socket" -> "ipc",
      "a//node.socket" -> "a/",
      "/node.socket" -> "/",
      "//node.socket" -> "/",
      "/run/cardano/node.socket" -> "/run/cardano",
      "ipc/" -> "ipc"
    )
    assertEquals(cases.map((p, _) => p -> BearerServer.parentDirectory(p)), cases)
  }
