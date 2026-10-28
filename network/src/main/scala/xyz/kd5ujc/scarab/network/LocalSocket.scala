// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{Async, Resource}
import cats.effect.std.Mutex
import cats.effect.syntax.all.*
import cats.syntax.all.*
import fs2.{Chunk, Stream}
import java.io.IOException
import java.net.{StandardProtocolFamily, UnixDomainSocketAddress}
import java.nio.ByteBuffer
import java.nio.channels.{ServerSocketChannel, SocketChannel}
import java.lang.foreign.{
  Arena,
  FunctionDescriptor,
  Linker,
  MemoryLayout,
  MemorySegment,
  ValueLayout
}
import java.lang.invoke.MethodHandle
import java.nio.file.{Files, Path}
import scala.jdk.OptionConverters.*
import scala.jdk.CollectionConverters.*
import scala.util.Using
import xyz.kd5ujc.scarab.codec.DomainError

/** The address of the node-to-client socket: the path as it was configured, not normalised (the
  * reference's `LocalAddress`, ouroboros-network 2fb69829,
  * `ouroboros-network/framework/lib/Ouroboros/Network/Snocket.hs` 219-221).
  */
final case class LocalAddress(path: String):
  /** `show`: `LocalAddress "<path>"` (`deriving Show via Quiet LocalAddress`). */
  def shown: String = s"LocalAddress ${HaskellShow.string(path)}"

/** A connection to the local socket: the socket's address and the client's. A Unix socket client
  * has no address of its own, so the reference names the `n` th accepted client `<path>@<n>`
  * (`berkeleyAccept`, `Snocket.hs` 188-198).
  */
final case class LocalConnectionId(local: LocalAddress, remote: LocalAddress)

/** The descriptor of a socket, as the reference's traces show it (`Snocket.hs` 614-618). */
opaque type FileDescriptor = Int

object FileDescriptor:
  def apply(fd: Int): FileDescriptor = fd

  /** The reference's `invalidFileDescriptor` (`Snocket.hs` 637-639). */
  val Invalid: FileDescriptor = -1

  extension (fd: FileDescriptor)
    /** The descriptor's number. */
    def number: Int = fd

    /** `show`: `FileDescriptor <n>` (`deriving Show via Quiet FileDescriptor`). */
    def shown: String = s"FileDescriptor ${HaskellShow.intArgument(fd)}"

/** Why the sockets the node was configured with cannot be used; the node does not start. The local
  * case of the reference's `SocketConfigError` (cardano-node 11.1.3,
  * `cardano-node/src/Cardano/Node/Configuration/Socket.hs` 64-73): `removeFile` of what an earlier
  * run left at the path failed for another reason than that nothing is there.
  */
enum SocketConfigError extends DomainError:
  case LocalSocketError(path: String, cause: SystemCallError)

  /** `renderSocketConfigError` (`Socket.hs` 101-103), the text of the reference's start-up trace
    * for a human and of its message on standard error.
    */
  def message: String = this match
    case LocalSocketError(path, cause) =>
      s"Failure while attempting to remove the stale local socket: $path : ${cause.shown}"

  /** The derived `show` (`Socket.hs` 73), the `error` field of the reference's start-up trace: the
    * path is a `File`, whose `Show` is its string's.
    */
  def shown: String = this match
    case LocalSocketError(path, cause) =>
      s"LocalSocketError ${HaskellShow.string(path)} ${cause.shown}"

/** Why the node-to-client side of the network stopped: what the reference's `DiffusionErrored`
  * names and `DiffusionError` carries (ouroboros-network 2fb69829,
  * `ouroboros-network/lib/Ouroboros/Network/Diffusion.hs` 905-911). The node then exits.
  */
enum DiffusionFailure extends DomainError:
  /** A call failed: creating, binding or listening on the socket, reading its mode or its
    * directory's, or an accept that is not `ECONNABORTED`.
    */
  case System(error: SystemCallError)

  /** The path does not fit a `sockaddr_un`: network 3.2.8.0's `pokeSockAddr` refuses a path of more
    * than 108 characters with `error`.
    */
  case PathTooLong(path: String)

  /** An exception that is not one of the socket's own, from a library or a bug. */
  case Defect(cause: Throwable)

  /** SIGINT reached the node while it ran: GHC's `UserInterrupt`, which the reference's diffusion
    * traces before it rethrows (`Diffusion.hs` 905-911); SIGTERM ends the reference with
    * `ExitSuccess`, which it does not trace.
    */
  case UserInterrupt

  /** The `show` of the exception, the `error` field of `DiffusionErrored`. */
  def shown: String = this match
    case System(error) => error.shown
    case PathTooLong(path) =>
      val length = path.codePointCount(0, path.length)
      s"pokeSockAddr: path is too long in SockAddrUnix ${HaskellShow.string(path)}, length $length, " +
        s"unixPathMax ${LocalSockets.UnixPathMax}\n" + LocalSockets.PokeSockAddrCallStack
    case Defect(cause) => cause.toString
    case UserInterrupt => "user interrupt"

/** The calls the local socket makes, one for each of the reference's (`removeFile`,
  * `getFileStatus`, the `Snocket` of a Unix socket), each failure a typed value.
  */
trait LocalSockets[F[_]]:
  /** `removeFile` (`removeLink`, one `unlink`): a directory is not removed. */
  def removeFile(path: String): F[Either[SystemCallError, Unit]]

  /** The `st_mode` of `path`, following a link (`getFileStatus`). */
  def fileMode(path: String): F[Either[SystemCallError, Int]]

  /** A new socket (`Snocket.open`); the resource closes it, and never removes its file. */
  def open(address: LocalAddress): Resource[F, Either[SystemCallError, LocalListener[F]]]

/** A socket to listen on. */
trait LocalListener[F[_]]:
  /** `Snocket.bind` to the address it was opened for. */
  def bind: F[Either[DiffusionFailure, Unit]]

  /** Its descriptor, if this platform tells it. */
  def descriptor: F[Option[FileDescriptor]]

  /** `Snocket.listen`, with the reference's backlog of 8. */
  def listen: F[Either[DiffusionFailure, Unit]]

  /** The next client (`Socket.accept`). The bearer is the caller's to close. */
  def accept: F[Either[SystemCallError, LocalStream[F]]]

/** An accepted client: its bearer, and how to close the connection. */
final case class LocalStream[F[_]](bearer: Bearer[F], close: F[Unit])

object LocalSockets:
  /** `unixPathMax` of network 3.2.8.0 (`Network/Socket/Types.hsc`). */
  val UnixPathMax: Int = 108

  /** What GHC appends to the message of the `error` call in `pokeSockAddr` of the reference's
    * network 3.2.8.0 build (observed from `cardano-reference-11.1.3:local`).
    */
  private[network] val PokeSockAddrCallStack: String =
    "CallStack (from HasCallStack):\n  error, called at Network/Socket/Types.hsc:1162:33 in " +
      "network-3.2.8.0-DfzdidsgO8q74pRUILGgOE:Network.Socket.Types"

  /** The listen backlog of the reference's local snocket (`Snocket.hs` 578). */
  val Backlog: Int = 8

  private val ReadSize = 65536

  /** The calls through the JDK. The JDK binds and listens in one call (`bind(address, backlog)`),
    * so [[LocalListener.listen]] has nothing left to do; it keeps the errno to itself, so a failure
    * is read back from the exception ([[Errno.of]]); and it does not tell a socket's descriptor, so
    * that is read from `/proc` (Linux).
    */
  def jdk[F[_]](using F: Async[F]): LocalSockets[F] = new LocalSockets[F]:
    def removeFile(path: String): F[Either[SystemCallError, Unit]] =
      F.blocking(Unlink(path)).map(_.leftMap(SystemCallError(SystemCall.RemoveLink, _, Some(path))))

    def fileMode(path: String): F[Either[SystemCallError, Int]] =
      F.blocking(
        Either
          .catchOnly[IOException](Files.getAttribute(Path.of(path), "unix:mode"))
          .map(_.asInstanceOf[Integer].toInt)
          .leftMap(SystemCallError.of(SystemCall.GetFileStatus, Some(path)))
      )

    def open(address: LocalAddress): Resource[F, Either[SystemCallError, LocalListener[F]]] =
      Resource
        .make(
          F.blocking(
            Either.catchOnly[IOException] {
              val before = Proc.socketDescriptors
              val channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
              val opened = Proc.socketDescriptors.keySet -- before.keySet
              (channel, Option.when(opened.sizeIs == 1)(opened.head))
            }
          )
        )(_.traverse_((channel, _) => F.blocking(channel.close())))
        .map(
          _.bimap(
            SystemCallError.of(SystemCall.Socket, None),
            (channel, opened) => listener(channel, address, opened)
          )
        )

    private def listener(
        channel: ServerSocketChannel,
        address: LocalAddress,
        opened: Option[Int]
    ): LocalListener[F] = new LocalListener[F]:
      def bind: F[Either[DiffusionFailure, Unit]] =
        if address.path.codePointCount(0, address.path.length) > UnixPathMax then
          F.pure(Left(DiffusionFailure.PathTooLong(address.path)))
        // a socket cannot be made at a path that ends in `/` (the kernel's `ENOENT`); the JDK
        // would drop the slash and bind the path without it
        else if address.path.endsWith("/") then
          F.pure(
            Left(
              DiffusionFailure.System(
                SystemCallError(SystemCall.Bind, ErrnoCause.Known(Errno.ENOENT), None)
              )
            )
          )
        else
          F.blocking(
            Either
              .catchOnly[IOException](
                channel.bind(UnixDomainSocketAddress.of(address.path), Backlog)
              )
              .void
              .leftMap(e => DiffusionFailure.System(SystemCallError.of(SystemCall.Bind, None)(e)))
          )

      // bound, the socket is the listening one at its path in `/proc/net/unix`; unbound, the one
      // socket `open` saw appear, if it saw exactly one
      def descriptor: F[Option[FileDescriptor]] =
        F.blocking(
          Option(channel.getLocalAddress)
            .collect { case bound: UnixDomainSocketAddress => bound.getPath.toString }
            .flatMap(Proc.listening)
            .orElse(opened)
            .map(FileDescriptor(_))
        )

      def listen: F[Either[DiffusionFailure, Unit]] = F.pure(Right(()))

      def accept: F[Either[SystemCallError, LocalStream[F]]] =
        F.blocking(Either.catchOnly[IOException](channel.accept()))
          .cancelable(F.blocking(channel.close()))
          .flatMap(
            _.leftMap(SystemCallError.of(SystemCall.Accept, None)).traverse(client =>
              Mutex[F].map(lock => LocalStream(stream(client, lock), F.blocking(client.close())))
            )
          )

    private def stream(client: SocketChannel, writes: Mutex[F]): Bearer[F] = new Bearer[F]:
      private val closing = F.blocking(client.close())

      private val chunk: F[Option[Chunk[Byte]]] =
        F.blocking {
          val buffer = ByteBuffer.allocate(ReadSize)
          val read = client.read(buffer)
          Option.when(read >= 0)(Chunk.array(buffer.array, 0, read))
        }.cancelable(closing)

      def reads: Stream[F, Byte] = Stream.repeatEval(chunk).unNoneTerminate.unchunks

      def write(bytes: Chunk[Byte]): F[Unit] =
        def rest(buffer: ByteBuffer): F[Unit] =
          F.blocking(client.write(buffer)).cancelable(closing) *>
            F.delay(buffer.hasRemaining).ifM(rest(buffer), F.unit)
        writes.lock.surround(F.delay(bytes.toByteBuffer).flatMap(rest))

      def close: F[Unit] = F.blocking(client.shutdownOutput()).void

  /** `unlink(2)` of the path as written, one call, as the reference's `removeFile`: it needs write
    * and search permission on the directory only, refuses a directory (`EISDIR`), and takes a path
    * that ends in `/` as naming a directory (`ENOTDIR` on anything else). The JDK has no such call
    * (its deletes read the type first and remove an empty directory with `rmdir`), so it is made
    * through the foreign-function interface. The release JAR's `Enable-Native-Access` manifest
    * attribute allows the native access; started from a classpath instead, the JVM writes one
    * warning about it (no flag is added to the command line).
    */
  private object Unlink:
    private val linker = Linker.nativeLinker()
    private val state = Linker.Option.captureStateLayout()
    private val errnoOffset = state.byteOffset(MemoryLayout.PathElement.groupElement("errno"))
    private lazy val call: Option[MethodHandle] =
      linker
        .defaultLookup()
        .find("unlink")
        .toScala
        .map(
          linker.downcallHandle(
            _,
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
            Linker.Option.captureCallState("errno")
          )
        )

    def apply(path: String): Either[ErrnoCause, Unit] =
      call.fold(ErrnoCause.Unrecognised("no unlink in the C library").asLeft[Unit]) { unlink =>
        Using.resource(Arena.ofConfined()) { arena =>
          val bytes = path.getBytes(java.nio.charset.StandardCharsets.UTF_8)
          val name = arena.allocate(bytes.length + 1L)
          MemorySegment.copy(bytes, 0, name, ValueLayout.JAVA_BYTE, 0L, bytes.length)
          name.set(ValueLayout.JAVA_BYTE, bytes.length.toLong, 0.toByte)
          val captured = arena.allocate(state)
          val result = unlink.invokeWithArguments(captured, name).asInstanceOf[Integer].intValue
          Either.cond(
            result == 0,
            (),
            Errno.ofNumber(captured.get(ValueLayout.JAVA_INT, errnoOffset))
          )
        }
      }

  /** The process's own sockets, read from `/proc` (Linux). */
  private object Proc:
    private val SocketLink = """socket:\[(\d+)\]""".r

    /** Descriptor to inode, for each socket the process holds open. */
    def socketDescriptors: Map[Int, Long] =
      Either
        .catchOnly[IOException](
          Using.resource(Files.list(Path.of("/proc/self/fd")))(_.iterator.asScala.toList)
        )
        .getOrElse(Nil)
        .flatMap { link =>
          Either
            .catchOnly[IOException](Files.readSymbolicLink(link).toString)
            .toOption
            .collect { case SocketLink(inode) =>
              link.getFileName.toString.toIntOption.map(_ -> inode.toLong)
            }
            .flatten
        }
        .toMap

    /** `__SO_ACCEPTCON`: the socket listens (`/proc/net/unix`'s flags). */
    private val Listening = 0x10000

    /** The descriptor of the socket this process listens with at `path`. */
    def listening(path: String): Option[Int] =
      val inodes = Either
        .catchOnly[IOException](Files.readAllLines(Path.of("/proc/net/unix")).asScala.toList)
        .getOrElse(Nil)
        .drop(1)
        .flatMap { line =>
          line.trim.split("\\s+", 8).toList match
            case List(_, _, _, flags, _, _, inode, shown) if shown == path =>
              (
                Either
                  .catchOnly[NumberFormatException](java.lang.Long.parseLong(flags, 16))
                  .toOption
                  .filter(f => (f & Listening) != 0),
                inode.toLongOption
              ).mapN((_, i) => i)
            case _ => None
        }
        .toSet
      socketDescriptors.collectFirst { case (fd, inode) if inodes.contains(inode) => fd }
