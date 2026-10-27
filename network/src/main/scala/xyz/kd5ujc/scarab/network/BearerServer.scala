// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{Async, Resource}
import cats.effect.syntax.all.*
import cats.mtl.{Handle, Raise}
import cats.syntax.all.*
import com.comcast.ip4s.{Host, IpAddress, Port, SocketAddress}
import fs2.Stream
import fs2.io.net.Network
import java.io.IOException
import java.nio.file.{Files, LinkOption, NoSuchFileException, Path}
import java.nio.file.attribute.BasicFileAttributes
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.DomainError

/** Inbound side of the transport: a listening TCP socket whose accepted connections are handed to
  * one handler each, at most `maxConnections` at a time (`None`: not bounded here, the caller's
  * `beforeAccept` and connection manager limit them). A handler that fails closes only its own
  * connection and is reported to the owner as a typed value. Each handler owns its error scope;
  * cancellation releases its socket without reporting a failure (ADR0017).
  */
object BearerServer:
  /** Listen on `host:port` (`None`: all interfaces / an ephemeral port). The resource yields the
    * bound address and keeps accepting until it is released. `beforeAccept`, if given, runs before
    * each accept, as the reference's accept loop runs `runConnectionRateLimits` (ouroboros-network
    * 2fb69829, `Server.hs`).
    */
  def listen[F[_]: Async, E <: DomainError](
      host: Option[Host],
      port: Option[Port],
      maxConnections: Option[Int] = Some(64),
      beforeAccept: Option[F[Unit]] = None
  )(
      onFailure: (SocketAddress[IpAddress], HandlerFailure[E]) => F[Unit]
  )(
      handle: Handle[F, E] ?=> (Bearer[F], SocketAddress[IpAddress]) => F[Unit]
  ): Resource[F, SocketAddress[IpAddress]] =
    val network = Network.forAsync[F]
    val wildcard = IpAddress.fromString("0.0.0.0").get
    Resource
      .eval(
        host
          .traverse(_.resolve[F](using network.dns, Async[F]))
          .map(ip => SocketAddress(ip.getOrElse(wildcard), port.getOrElse(Port.fromInt(0).get)))
      )
      .flatMap(requested => network.bind(requested))
      .flatMap { server =>
        // one accept per round, so that the socket of the previous round is released as soon as its
        // handler ends, however long `beforeAccept` then pauses
        val accepted = beforeAccept
          .fold(server.accept)(pause => (Stream.exec(pause) ++ server.accept.take(1)).repeat)
        val handlers = accepted
          .map { socket =>
            Stream.eval(
              HandlerFailure
                .capture[F, E, Unit] {
                  handle(Bearer.fromSocket(socket), socket.peerAddress.asIpUnsafe)
                }
                .flatMap(_.fold(onFailure(socket.peerAddress.asIpUnsafe, _), _ => Async[F].unit))
            )
          }
        maxConnections
          .fold(handlers.parJoinUnbounded)(handlers.parJoin(_))
          .compile
          .drain
          .background
          .as(server.address.asIpUnsafe)
      }

  /** Why the node-to-client socket cannot be set up; the node does not start. Before the bind, the
    * reference's `LocalSocketError` (cardano-node 11.1.3,
    * `cardano-node/src/Cardano/Node/Configuration/Socket.hs` 65-73, 101-103).
    */
  enum LocalSocketError extends DomainError:
    /** A directory, empty or not, is at the path: `removeFile` (unlink) does not remove one. */
    case Directory(path: String)

    /** What is at the path could not be removed, for another reason than that it is missing. */
    case Remove(path: String, cause: IOException)

    /** The bound socket or its directory could not be read for the check after the bind
      * (`getFileStatus` in `checkLocalSocket`, which throws there).
      */
    case Inspect(path: String, cause: IOException)

    def message: String = this match
      case Directory(p) =>
        s"Failure while attempting to remove the stale local socket: $p : is a directory"
      case Remove(p, e) =>
        s"Failure while attempting to remove the stale local socket: $p : ${e.getMessage}"
      case Inspect(p, e) => s"cannot check the permissions of the local socket $p: ${e.getMessage}"

  /** A warning of the check after the bind: the socket, or the directory it is in, is open to more
    * users than its owner's group. The reference's `InsecureLocalSocketPermissions` and
    * `InsecureLocalSocketDirectory` (ouroboros-network 2fb69829,
    * `ouroboros-network/lib/Ouroboros/Network/Diffusion.hs` 966-990); `mode` is the whole
    * `st_mode`, file type included, as `Unix.fileMode` gives it.
    */
  enum InsecureLocalSocket:
    /** Others may read or write the socket. */
    case Permissions(path: String, mode: Int)

    /** The group or others may write the directory that holds the socket. */
    case Directory(path: String, mode: Int)

  /** `S_IROTH | S_IWOTH` (`0o006`) and `S_IWGRP | S_IWOTH` (`0o022`). */
  private val OtherReadWrite = 0x6
  private val GroupOtherWrite = 0x12

  /** The warning for a socket whose `st_mode` is `mode`, if others may read or write it. */
  private[network] def permissions(path: String, mode: Int): Option[InsecureLocalSocket] =
    Option.when((mode & OtherReadWrite) != 0)(InsecureLocalSocket.Permissions(path, mode))

  /** The warning for a socket directory whose `st_mode` is `mode`, if the group or others may write
    * it.
    */
  private[network] def directory(path: String, mode: Int): Option[InsecureLocalSocket] =
    Option.when((mode & GroupOtherWrite) != 0)(InsecureLocalSocket.Directory(path, mode))

  /** The directory of the socket at `path`, as the `parentDirectory` of `checkLocalSocket` takes
    * it: what is before the last `/`, `/` when that is empty, `.` when there is no `/`.
    */
  private[network] def parentDirectory(path: String): String =
    path.lastIndexOf('/') match
      case -1 => "."
      case 0  => "/"
      case at => path.substring(0, at)

  /** Remove what an earlier run left at `path` before the bind, as `removeStaleLocalSocket` does
    * (`Socket.hs` 211-224): `removeFile`, which is unlink, so a socket, a file or a symbolic link
    * (not its target) is removed, a missing path is fine, and a directory is refused. Java's own
    * delete would remove an empty directory, so the type is read first, without following a link.
    */
  private[network] def removeStale(path: Path): Either[LocalSocketError, Unit] =
    val shown = path.toString
    def missingIsFine(e: IOException): Either[LocalSocketError, Unit] = e match
      case _: NoSuchFileException => Right(())
      case other                  => Left(LocalSocketError.Remove(shown, other))
    Either
      .catchOnly[IOException](
        Files.readAttributes(path, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS)
      )
      .fold(
        missingIsFine,
        attributes =>
          if attributes.isDirectory then Left(LocalSocketError.Directory(shown))
          else Either.catchOnly[IOException](Files.delete(path)).fold(missingIsFine, Right(_))
      )

  /** The `st_mode` of `path`, following a link as `getFileStatus` (stat) does. */
  private def mode(path: Path): Either[IOException, Int] =
    Either.catchOnly[IOException](Files.getAttribute(path, "unix:mode").asInstanceOf[Integer].toInt)

  /** The check after the bind, `checkLocalSocket`: warn when others may read or write the socket,
    * then when the group or others may write its directory. It only warns; no mode is set, the
    * socket has the one the umask gives it.
    */
  private def checkLocalSocket[F[_]: Async](path: Path)(
      warn: InsecureLocalSocket => F[Unit]
  )(using Raise[F, LocalSocketError]): F[Unit] =
    val shown = path.toString
    def stat(of: Path): F[Int] =
      Async[F]
        .blocking(mode(of))
        .flatMap(m =>
          Raise[F, LocalSocketError].fromEither(m.leftMap(LocalSocketError.Inspect(shown, _)))
        )
    stat(path).flatMap(m => permissions(shown, m).traverse_(warn)) *>
      stat(Path.of(parentDirectory(shown))).flatMap(m => directory(shown, m).traverse_(warn))

  /** The same on a Unix domain socket at `path`, for node-to-client clients such as `cardano-cli`.
    * What an earlier run left at the path is removed first ([[removeStale]]), and the bound socket
    * is checked ([[checkLocalSocket]]): `onInsecure` receives its warnings. Either failing is a
    * [[LocalSocketError]] raised while the resource is acquired.
    */
  def listenUnix[F[_]: Async, E <: DomainError](path: Path, maxConnections: Int = 16)(
      onFailure: HandlerFailure[E] => F[Unit],
      onInsecure: InsecureLocalSocket => F[Unit]
  )(
      handle: Handle[F, E] ?=> Bearer[F] => F[Unit]
  )(using Raise[F, LocalSocketError]): Resource[F, Unit] =
    import com.comcast.ip4s.UnixSocketAddress
    import fs2.io.net.Network
    Resource.eval(
      Async[F].blocking(removeStale(path)).flatMap(Raise[F, LocalSocketError].fromEither)
    ) *>
      Network
        .forAsync[F]
        .bindAndAccept(UnixSocketAddress(path.toString))
        .map(socket =>
          Stream.eval(
            HandlerFailure
              .capture[F, E, Unit](handle(Bearer.fromSocket(socket)))
              .flatMap(_.fold(onFailure, _ => Async[F].unit))
          )
        )
        .parJoin(maxConnections)
        .compile
        .drain
        .background *>
      // the socket file appears once the server has bound it
      Resource.eval(
        Async[F]
          .blocking(Files.exists(path))
          .flatMap(found => Async[F].sleep(5.millis).unlessA(found).as(found))
          .iterateUntil(identity)
          .timeout(5.seconds)
          .void
      ) *>
      Resource.eval(checkLocalSocket(path)(onInsecure))
