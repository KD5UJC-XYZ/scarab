// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.std.Queue
import cats.effect.testkit.TestControl
import cats.mtl.{Handle, Raise}
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import fs2.Chunk
import java.net.{StandardProtocolFamily, UnixDomainSocketAddress}
import java.nio.ByteBuffer
import java.nio.channels.{ServerSocketChannel, SocketChannel}
import java.nio.file.{Files, LinkOption, Path}
import java.nio.file.attribute.PosixFilePermissions
import scala.concurrent.duration.*

import LocalServer.Trace

class LocalServerSuite extends FailsOnUncaught:
  private val jdk = LocalSockets.jdk[IO]

  private def attempt[E, A](body: Raise[IO, E] ?=> IO[A]): IO[Either[E, A]] =
    Handle.allow[E](body.map(_.asRight[E])).rescue(e => IO.pure(Left(e)))

  private def octal(digits: String): Int = Integer.parseInt(digits, 8)

  private def statMode(path: Path): IO[Int] =
    IO.blocking(Files.getAttribute(path, "unix:mode").asInstanceOf[Integer].toInt)

  private def chmod(path: Path, mode: String): IO[Unit] =
    IO.blocking(Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode))).void

  private val temporary = fs2.io.file.Files.forAsync[IO].tempDirectory.map(_.toNioPath)

  /** Bind and close a Unix socket at `path`, as a run that died leaves it: never unlinked. */
  private def staleSocket(path: Path): IO[Unit] = IO.blocking {
    val channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
    channel.bind(UnixDomainSocketAddress.of(path))
    channel.close()
  }

  private def removeStale(path: Path): IO[Either[SocketConfigError, Unit]] =
    attempt[SocketConfigError, Unit](LocalServer.removeStale(jdk, path.toString))

  test("a stale socket, a file and a symbolic link are removed; what a link points at is left") {
    temporary.use { root =>
      val path = root.resolve("node.socket")
      val target = root.resolve("target")
      val targetDirectory = root.resolve("target-directory")
      for
        _ <- staleSocket(path)
        onSocket <- removeStale(path)
        _ <- IO.blocking(Files.writeString(path, "a file"))
        onFile <- removeStale(path)
        _ <- IO.blocking {
          Files.writeString(target, "kept")
          Files.createSymbolicLink(path, target)
        }
        onLink <- removeStale(path)
        _ <- IO.blocking {
          Files.createDirectory(targetDirectory)
          Files.createSymbolicLink(path, targetDirectory)
        }
        onDirectoryLink <- removeStale(path)
        gone <- IO.blocking(!Files.exists(path, LinkOption.NOFOLLOW_LINKS))
        kept <- IO.blocking(Files.readString(target))
        keptDirectory <- IO.blocking(Files.isDirectory(targetDirectory))
      yield
        assertEquals(List(onSocket, onFile, onLink, onDirectoryLink), List.fill(4)(Right(())))
        assert(gone)
        assertEquals(kept, "kept")
        assert(keptDirectory, "the directory a link pointed at is left")
    }
  }

  test("nothing at the path, or no directory for it, is fine") {
    temporary.use { root =>
      (removeStale(root.resolve("node.socket")), removeStale(root.resolve("absent/node.socket")))
        .mapN((missing, noDirectory) =>
          assertEquals((missing, noDirectory), (Right(()), Right(())))
        )
    }
  }

  test("a directory at the path, empty or not, refuses with unlink's error and is left") {
    temporary.use { root =>
      val empty = root.resolve("empty")
      val full = root.resolve("full")
      def refusal(path: Path) = SocketConfigError.LocalSocketError(
        path.toString,
        SystemCallError(SystemCall.RemoveLink, ErrnoCause.Known(Errno.EISDIR), Some(path.toString))
      )
      for
        _ <- IO.blocking {
          Files.createDirectory(empty)
          Files.createDirectory(full)
          Files.writeString(full.resolve("inside"), "kept")
        }
        onEmpty <- removeStale(empty)
        onFull <- removeStale(full)
        left <- IO.blocking(
          Files.isDirectory(empty, LinkOption.NOFOLLOW_LINKS) &&
            Files.readString(full.resolve("inside")) == "kept"
        )
      yield
        assertEquals(onEmpty, Left(refusal(empty)))
        assertEquals(onFull, Left(refusal(full)))
        assertEquals(
          onEmpty.left.map(_.message),
          Left(
            s"Failure while attempting to remove the stale local socket: $empty : " +
              s"$empty: removeLink: inappropriate type (Is a directory)"
          )
        )
        assert(left, "both directories are left as they were")
    }
  }

  test("a socket in a directory the node may not write refuses with EACCES") {
    temporary.use { root =>
      val locked = root.resolve("locked")
      val path = locked.resolve("node.socket")
      for
        _ <- IO.blocking(Files.createDirectory(locked)) *> staleSocket(path)
        _ <- chmod(locked, "r-xr-xr-x")
        // root may write any directory: then there is nothing to refuse
        writable <- IO.blocking(Files.isWritable(locked))
        result <- removeStale(path)
        _ <- chmod(locked, "rwx------")
      yield
        assume(!writable, "running as root")
        assertEquals(
          result.left.map(_.message),
          Left(
            s"Failure while attempting to remove the stale local socket: $path : " +
              s"$path: removeLink: permission denied (Permission denied)"
          )
        )
    }
  }

  test("a write-only directory is enough to remove the stale socket, as unlink needs") {
    temporary.use { root =>
      val dropBox = root.resolve("drop-box")
      val path = dropBox.resolve("node.socket")
      for
        _ <- IO.blocking(Files.createDirectory(dropBox)) *> staleSocket(path)
        _ <- chmod(dropBox, "-wx------")
        // root may read any directory: then the case is not exercised
        readable <- IO.blocking(Files.isReadable(dropBox))
        result <- removeStale(path)
        _ <- chmod(dropBox, "rwx------")
        gone <- IO.blocking(!Files.exists(path, LinkOption.NOFOLLOW_LINKS))
      yield
        assume(!readable, "running as root")
        assertEquals(result, Right(()))
        assert(gone)
    }
  }

  test("a path ending in a slash names a directory, as unlink takes it") {
    temporary.use { root =>
      val file = root.resolve("file")
      val directory = root.resolve("directory")
      def refusal(path: String, errno: Errno) = Left(
        SocketConfigError.LocalSocketError(
          path,
          SystemCallError(SystemCall.RemoveLink, ErrnoCause.Known(errno), Some(path))
        )
      )
      def remove(path: String) =
        attempt[SocketConfigError, Unit](LocalServer.removeStale(jdk, path))
      for
        _ <- IO.blocking(Files.writeString(file, "kept")) *> IO.blocking(
          Files.createDirectory(directory)
        )
        onFile <- remove(s"$file/")
        onDirectory <- remove(s"$directory/")
        onNothing <- remove(s"${root.resolve("absent")}/")
        kept <- IO.blocking(Files.exists(file) && Files.isDirectory(directory))
        bound <- jdk
          .open(LocalAddress(s"${root.resolve("absent")}/"))
          .use(
            _.fold(e => IO.pure(Left(DiffusionFailure.System(e))), _.bind)
          )
      yield
        assertEquals(onFile, refusal(s"$file/", Errno.ENOTDIR))
        assertEquals(onDirectory, refusal(s"$directory/", Errno.EISDIR))
        assertEquals(onNothing, Right(()))
        assert(kept)
        assertEquals(
          bound,
          Left(
            DiffusionFailure.System(
              SystemCallError(SystemCall.Bind, ErrnoCause.Known(Errno.ENOENT), None)
            )
          )
        )
    }
  }

  test("a blocking call cancelled by closing its channel leaves no uncaught failure (#644)") {
    // On one FIFO worker thread the order is fixed: the cancel drops the join of `cancelable` and
    // runs `closing`, which wakes the call and then yields, as the blocking close of a real channel
    // moves to another pool and back. The call raises and ends before `cancelable` joins it again,
    // so an exception it ends with has no observer. The runtime keeps every failure it reports.
    val program =
      for
        started <- Deferred[IO, Unit]
        closed <- Deferred[IO, Unit]
        call = IO.uncancelable(_ =>
          started.complete(()) *> closed.get *>
            IO.raiseError[Int](java.nio.channels.AsynchronousCloseException())
        )
        fiber <- LocalSockets.interruptible(call, closed.complete(()) *> IO.cede).start
        _ <- started.get
        _ <- fiber.cancel
        outcome <- fiber.join
      yield outcome
    Resource
      .make(IO(java.util.concurrent.Executors.newSingleThreadExecutor()))(pool =>
        IO(pool.shutdown())
      )
      .flatMap(pool =>
        Resource
          .make(IO(cats.effect.unsafe.IORuntime.createDefaultScheduler()))((_, close) =>
            IO(close())
          )
          .map((scheduler, _) => (pool, scheduler))
      )
      .flatMap { (pool, scheduler) =>
        val reported = java.util.concurrent.ConcurrentLinkedQueue[Throwable]()
        val worker = new scala.concurrent.ExecutionContext:
          def execute(task: Runnable): Unit = pool.execute(task)
          def reportFailure(cause: Throwable): Unit = reported.add(cause): Unit
        // the runtime's own shutdown unregisters what `IORuntime.apply` registers
        Resource
          .make(
            IO(
              cats.effect.unsafe.IORuntime(
                worker,
                worker,
                scheduler,
                () => (),
                cats.effect.unsafe.IORuntimeConfig()
              )
            )
          )(runtime => IO(runtime.shutdown()))
          .map(runtime => (runtime, reported))
      }
      .use { (runtime, reported) =>
        IO.fromFuture(IO(program.unsafeToFuture()(using runtime))).map { outcome =>
          assert(outcome.isCanceled, outcome.toString)
          assertEquals(reported.toArray.toList, Nil)
        }
      }
  }

  test("a blocking call that is not cancelled raises its failure as before (#644)") {
    val failure = java.nio.channels.ClosedChannelException()
    LocalSockets
      .interruptible(IO.raiseError[Int](failure), IO.unit)
      .attempt
      .map(result => assertEquals(result, Left(failure)))
  }

  /** The calls of the server on the sockets, in order. */
  private enum Call:
    case Open, Bind, Listen, Accept
    case Status(path: String)

  /** Sockets whose calls are recorded and answered as the test says. */
  private final class Recorded(
      log: Ref[IO, Vector[Call]],
      modes: String => Either[SystemCallError, Int],
      bound: Either[DiffusionFailure, Unit],
      accepts: Queue[IO, Either[SystemCallError, LocalStream[IO]]]
  ) extends LocalSockets[IO]:
    def removeFile(path: String) = IO.pure(Right(()))
    def fileMode(path: String) = log.update(_ :+ Call.Status(path)).as(modes(path))
    def open(address: LocalAddress) =
      Resource.eval(log.update(_ :+ Call.Open)).as(Right(listener))
    private val listener: LocalListener[IO] = new LocalListener[IO]:
      def bind = log.update(_ :+ Call.Bind).as(bound)
      def descriptor = IO.pure(Some(FileDescriptor(20)))
      def listen = log.update(_ :+ Call.Listen).as(Right(()))
      def accept = log.update(_ :+ Call.Accept) *> accepts.take

  private val address = LocalAddress("/run/cardano/node.socket")
  private val fd = FileDescriptor(20)
  private val socketMode = octal("140777")
  private val directoryMode = octal("40777")

  private def modes(path: String): Either[SystemCallError, Int] =
    Right(if path == address.path then socketMode else directoryMode)

  private def acceptFailure(errno: Errno) =
    SystemCallError(SystemCall.Accept, ErrnoCause.Known(errno), None)

  /** The server on recorded sockets, used by `body`: its traces, its calls and what it raised. */
  private def serve[A](
      accepts: List[Either[SystemCallError, LocalStream[IO]]],
      modes: String => Either[SystemCallError, Int] = modes,
      bound: Either[DiffusionFailure, Unit] = Right(())
  )(body: IO[DiffusionFailure] => IO[A]) =
    for
      log <- Ref.of[IO, Vector[Call]](Vector.empty)
      traces <- Ref.of[IO, Vector[Trace]](Vector.empty)
      queue <- Queue.unbounded[IO, Either[SystemCallError, LocalStream[IO]]]
      _ <- accepts.traverse_(queue.offer)
      result <- attempt[DiffusionFailure, A](
        LocalServer
          .resource[IO, NetworkError](
            Recorded(log, modes, bound, queue),
            address,
            t => traces.update(_ :+ t)
          )((_, _) => IO.unit)((_, _) => IO.never)
          .use(body)
      )
      calls <- log.get
      seen <- traces.get
    yield (result, calls, seen)

  private val setUp = Vector(
    Trace.CreateSystemdSocketForSnocketPath(address),
    Trace.CreatedLocalSocket(address),
    Trace.ConfiguringLocalSocket(address, fd),
    Trace.InsecureLocalSocketPermissions(address, socketMode),
    Trace.InsecureLocalSocketDirectory(address, directoryMode),
    Trace.ConfiguredLocalSocket(address, fd),
    Trace.ListeningLocalSocket(address, fd),
    Trace.LocalSocketUp(address, fd),
    Trace.RunLocalServer(address),
    Trace.ServerStarted(List(address))
  )

  test("bind, check, listen, then accept, each step traced as the reference's withLocalSocket") {
    serve(Nil)(_ => IO.sleep(50.millis)).map { (result, calls, traces) =>
      assertEquals(result, Right(()))
      assertEquals(
        calls,
        Vector(
          Call.Open,
          Call.Bind,
          Call.Status(address.path),
          Call.Status("/run/cardano"),
          Call.Listen,
          Call.Accept
        )
      )
      assertEquals(traces, setUp :+ Trace.ServerStopped)
    }
  }

  test("a bind that fails is raised to the caller and the socket is never checked or listened") {
    val failure = DiffusionFailure.System(
      SystemCallError(SystemCall.Bind, ErrnoCause.Known(Errno.ENOENT), None)
    )
    serve(Nil, bound = Left(failure))(_ => IO.unit).map { (result, calls, traces) =>
      assertEquals(result, Left(failure))
      assertEquals(calls, Vector(Call.Open, Call.Bind))
      assertEquals(traces, setUp.take(3))
      assertEquals(
        result.left.map(_.shown),
        Left("Network.Socket.bind: does not exist (No such file or directory)")
      )
    }
  }

  test("a status that cannot be read stops the set-up, naming the file it could not read") {
    val unreadable = SystemCallError(
      SystemCall.GetFileStatus,
      ErrnoCause.Known(Errno.EACCES),
      Some("/run/cardano")
    )
    serve(Nil, modes = p => if p == address.path then Right(octal("140700")) else Left(unreadable))(
      _ => IO.unit
    ).map { (result, calls, traces) =>
      assertEquals(result, Left(DiffusionFailure.System(unreadable)))
      assertEquals(
        result.left.map(_.shown),
        Left("/run/cardano: getFileStatus: permission denied (Permission denied)")
      )
      assert(!calls.contains(Call.Listen), calls)
      assertEquals(traces, setUp.take(3))
    }
  }

  test("ECONNABORTED is traced and the next accept waits half a second; the count is kept") {
    val aborted = acceptFailure(Errno.ECONNABORTED)
    val program =
      for
        ends <- Bearer.inMemory[IO]()
        times <- Ref.of[IO, Vector[FiniteDuration]](Vector.empty)
        log <- Ref.of[IO, Vector[Call]](Vector.empty)
        traces <- Ref.of[IO, Vector[Trace]](Vector.empty)
        queue <- Queue.unbounded[IO, Either[SystemCallError, LocalStream[IO]]]
        _ <- queue.offer(Left(aborted)) *> queue.offer(Right(LocalStream(ends._1, IO.unit)))
        timed = new LocalSockets[IO]:
          private val inner = Recorded(log, modes, Right(()), queue)
          def removeFile(path: String) = inner.removeFile(path)
          def fileMode(path: String) = inner.fileMode(path)
          def open(address: LocalAddress) = inner
            .open(address)
            .map(_.map { listener =>
              new LocalListener[IO]:
                def bind = listener.bind
                def descriptor = listener.descriptor
                def listen = listener.listen
                def accept = IO.monotonic.flatMap(t => times.update(_ :+ t)) *> listener.accept
            })
        start <- IO.monotonic
        result <- attempt[DiffusionFailure, Unit](
          LocalServer
            .resource[IO, NetworkError](timed, address, t => traces.update(_ :+ t))((_, _) =>
              IO.unit
            )((_, _) => IO.never)
            .use(_ => IO.sleep(2.seconds))
        )
        accepted <- times.get
        seen <- traces.get
      yield (result, accepted.map(_ - start), seen.drop(setUp.length))
    TestControl.executeEmbed(program).map { (result, accepted, traces) =>
      assertEquals(result, Right(()))
      assertEquals(accepted, Vector(Duration.Zero, 500.millis, 500.millis))
      assertEquals(
        traces,
        Vector(
          Trace.AcceptError(aborted),
          Trace.AcceptConnection(LocalConnectionId(address, LocalAddress(s"${address.path}@0"))),
          Trace.ServerStopped
        )
      )
    }
  }

  test("any other accept failure ends the server: AcceptError, ServerStopped, ServerError") {
    val exhausted = acceptFailure(Errno.EMFILE)
    for
      first <- Bearer.inMemory[IO]()
      second <- Bearer.inMemory[IO]()
      outcome <- serve(
        List(
          Right(LocalStream(first._1, IO.unit)),
          Right(LocalStream(second._1, IO.unit)),
          Left(exhausted)
        )
      )(_.timeout(3.seconds))
    yield
      val (result, _, traces) = outcome
      assertEquals(result, Right(DiffusionFailure.System(exhausted)))
      assertEquals(
        traces.drop(setUp.length),
        Vector(
          Trace.AcceptConnection(LocalConnectionId(address, LocalAddress(s"${address.path}@0"))),
          Trace.AcceptConnection(LocalConnectionId(address, LocalAddress(s"${address.path}@1"))),
          Trace.AcceptError(exhausted),
          Trace.ServerStopped,
          Trace.ServerError(DiffusionFailure.System(exhausted))
        )
      )
      assertEquals(
        DiffusionFailure.System(exhausted).shown,
        "Network.Socket.accept: resource exhausted (No file descriptors available)"
      )
  }

  private val refused = NetworkError.BearerClosed(NetworkError.Closure.PeerClosed)
  private val bug = new IllegalStateException("handler defect")

  /** A server on the JDK's sockets at `path`, used by `body`, with its traces. */
  private def onJdk[A](
      path: Path,
      failures: (LocalConnectionId, HandlerFailure[NetworkError]) => IO[Unit] = (_, _) => IO.unit
  )(handle: cats.mtl.Handle[IO, NetworkError] ?=> (Bearer[IO], LocalConnectionId) => IO[Unit])(
      body: IO[DiffusionFailure] => IO[A]
  ): IO[(Either[DiffusionFailure, A], Vector[Trace])] =
    Ref.of[IO, Vector[Trace]](Vector.empty).flatMap { traces =>
      attempt[DiffusionFailure, A](
        LocalServer
          .resource[IO, NetworkError](jdk, LocalAddress(path.toString), t => traces.update(_ :+ t))(
            failures
          )(handle)
          .use(body)
      ).product(traces.get)
    }

  /** Connect to `path`, write `out`, close the output and read until the server closes. */
  private def exchange(path: Path, out: Array[Byte]): IO[Vector[Byte]] = IO.blocking {
    val client = SocketChannel.open(StandardProtocolFamily.UNIX)
    try
      client.connect(UnixDomainSocketAddress.of(path))
      client.write(ByteBuffer.wrap(out))
      client.shutdownOutput()
      val buffer = ByteBuffer.allocate(1024)
      Iterator
        .continually { buffer.clear(); client.read(buffer) }
        .takeWhile(_ >= 0)
        .flatMap(n => buffer.array.take(n).toVector)
        .toVector
    finally client.close()
  }

  test("on the JDK: clients are served, handler failures are values, and the file stays") {
    temporary.use { root =>
      val path = root.resolve("node.socket")
      for
        count <- Ref.of[IO, Int](0)
        failures <- Queue.unbounded[IO, (LocalConnectionId, HandlerFailure[NetworkError])]
        outcome <- onJdk(path, (id, f) => failures.offer(id -> f)) { (bearer, _) =>
          count.getAndUpdate(_ + 1).flatMap {
            case 0 => refused.raise[IO, Unit]
            case 1 => IO.raiseError(bug)
            case _ =>
              bearer.reads.chunks.evalMap(bearer.write).compile.drain *> bearer.close
          }
        } { _ =>
          for
            _ <- exchange(path, Array.emptyByteArray)
            domain <- failures.take.timeout(3.seconds)
            _ <- exchange(path, Array.emptyByteArray)
            defect <- failures.take.timeout(3.seconds)
            echoed <- exchange(path, Array[Byte](3, 4, 5))
          yield (domain, defect, echoed)
        }
        left <- IO.blocking(Files.getAttribute(path, "unix:mode").asInstanceOf[Integer].toInt)
      yield
        val (result, traces) = outcome
        val (domain, defect, echoed) = result.fold(e => fail(e.shown), identity)
        assertEquals(
          domain,
          LocalConnectionId(LocalAddress(path.toString), LocalAddress(s"$path@0")) -> HandlerFailure
            .Domain(refused)
        )
        assertEquals(defect._2, HandlerFailure.Defect(bug))
        assertEquals(echoed, Vector[Byte](3, 4, 5))
        // closed, the socket is left where it was bound, as the reference leaves it
        assertEquals(left & 0xf000, 0xc000)
        assertEquals(traces.last, Trace.ServerStopped)
    }
  }

  test("on the JDK: the clients are not limited in number") {
    temporary.use { root =>
      val path = root.resolve("node.socket")
      val clients = 40
      for
        entered <- Ref.of[IO, Int](0)
        all <- Deferred[IO, Unit]
        release <- Deferred[IO, Unit]
        outcome <- onJdk(path) { (_, _) =>
          entered.updateAndGet(_ + 1).flatMap(n => all.complete(()).whenA(n == clients)) *>
            release.get
        } { _ =>
          Resource
            .make(IO.blocking {
              (1 to clients).map { _ =>
                val c = SocketChannel.open(StandardProtocolFamily.UNIX)
                c.connect(UnixDomainSocketAddress.of(path))
                c
              }
            })(cs => IO.blocking(cs.foreach(_.close())))
            .use(_ => all.get.timeout(10.seconds).attempt.flatTap(_ => release.complete(())))
        }
        count <- entered.get
      yield
        assertEquals(outcome._1.map(_.isRight), Right(true), s"$count of $clients served at once")
        assertEquals(count, clients)
    }
  }

  test("on the JDK: the backlog is the reference's 8") {
    temporary.use { root =>
      val path = root.resolve("node.socket")
      jdk.open(LocalAddress(path.toString)).use { opened =>
        val listener = opened.fold(e => fail(e.shown), identity)
        for
          bound <- listener.bind
          // nobody accepts: Linux queues backlog + 1 connections, the next one would block
          queued <- IO.blocking {
            val kept = scala.collection.mutable.ListBuffer.empty[SocketChannel]
            try
              Iterator
                .continually {
                  val c = SocketChannel.open(StandardProtocolFamily.UNIX)
                  kept += c
                  c.configureBlocking(false)
                  scala.util.Try(c.connect(UnixDomainSocketAddress.of(path))).getOrElse(false)
                }
                .takeWhile(identity)
                .take(100)
                .length
            finally kept.foreach(_.close())
          }
        yield
          assertEquals(bound, Right(()))
          assertEquals(queued, LocalSockets.Backlog + 1)
      }
    }
  }

  test("on the JDK: the traces name the listening socket's descriptor") {
    temporary.use { root =>
      val path = root.resolve("node.socket")
      for
        traces <- Ref.of[IO, Vector[Trace]](Vector.empty)
        link <- attempt[DiffusionFailure, String](
          LocalServer
            .resource[IO, NetworkError](
              jdk,
              LocalAddress(path.toString),
              t => traces.update(_ :+ t)
            )((_, _) => IO.unit)((_, _) => IO.unit)
            .use(_ =>
              traces.get.flatMap(seen =>
                seen
                  .collectFirst { case Trace.LocalSocketUp(_, fd) => fd.number }
                  .fold(IO.pure("no LocalSocketUp"))(fd =>
                    IO.blocking(Files.readSymbolicLink(Path.of(s"/proc/self/fd/$fd")).toString)
                  )
              )
            )
        )
        seen <- traces.get
      yield
        val fds = seen.collect {
          case Trace.ConfiguringLocalSocket(_, fd) => fd
          case Trace.ConfiguredLocalSocket(_, fd)  => fd
          case Trace.ListeningLocalSocket(_, fd)   => fd
          case Trace.LocalSocketUp(_, fd)          => fd
        }
        assertEquals(fds.length, 4)
        assertEquals(fds.distinct.length, 1, fds)
        assert(link.exists(_.startsWith("socket:[")), link)
    }
  }

  test("on the JDK: the bound socket is checked as its mode and its directory's mode say") {
    temporary.use { root =>
      val path = root.resolve("node.socket")
      val address = LocalAddress(path.toString)
      def warnings(traces: Vector[Trace]) = traces.collect {
        case w: Trace.InsecureLocalSocketPermissions => w
        case w: Trace.InsecureLocalSocketDirectory   => w
      }
      def once = onJdk(path)((_, _) => IO.unit)(_ => statMode(path))
      for
        _ <- chmod(root, "rwx-w----")
        groupWritable <- once
        _ <- chmod(root, "rwx---r-x")
        othersRead <- once
      yield
        val socketWarning = List(groupWritable, othersRead).map(
          _._1.toOption.flatMap(LocalServer.permissions(address, _)).toVector
        )
        assertEquals(
          warnings(groupWritable._2),
          socketWarning(0) :+ Trace.InsecureLocalSocketDirectory(address, octal("40720"))
        )
        assertEquals(warnings(othersRead._2), socketWarning(1))
    }
  }

  test("on the JDK: a bind that fails raises its error, with the reference's text") {
    temporary.use { root =>
      val path = root.resolve("absent/node.socket")
      onJdk(path)((_, _) => IO.unit)(_ => IO.unit).map { (result, traces) =>
        assertEquals(
          result.left.map(_.shown),
          Left("Network.Socket.bind: does not exist (No such file or directory)")
        )
        assert(
          traces.lastOption.exists {
            case _: Trace.ConfiguringLocalSocket => true
            case _                               => false
          },
          traces
        )
      }
    }
  }

  test("on the JDK: a path of more than 108 characters is refused as pokeSockAddr refuses it") {
    temporary.use { root =>
      val path = root.resolve("m" * 120)
      onJdk(path)((_, _) => IO.unit)(_ => IO.unit).map { (result, _) =>
        assertEquals(result, Left(DiffusionFailure.PathTooLong(path.toString)))
      }
    }
  }

  test(
    "the warnings: others reading or writing the socket, the group or others writing its directory"
  ) {
    val bits = (0 until 9).map(1 << _)
    val socketWarned = bits.filter(b => LocalServer.permissions(address, 0xc000 | b).nonEmpty)
    val directoryWarned = bits.filter(b => LocalServer.directory(address, 0x4000 | b).nonEmpty)
    // S_IWOTH and S_IROTH; S_IWOTH and S_IWGRP
    assertEquals(socketWarned, Vector(octal("2"), octal("4")))
    assertEquals(directoryWarned, Vector(octal("2"), octal("20")))
    assertEquals(LocalServer.permissions(address, octal("140770")), None)
    assertEquals(LocalServer.directory(address, octal("40755")), None)
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
    assertEquals(cases.map((p, _) => p -> LocalServer.parentDirectory(p)), cases)
  }
