// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{Async, Resource}
import cats.effect.syntax.all.*
import cats.mtl.{Handle, Raise}
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import com.comcast.ip4s.{Host, IpAddress, Port, SocketAddress}
import fs2.Stream
import fs2.io.net.Network
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.DomainError

/** Inbound side of the transport: a listening TCP socket whose accepted connections are handed to
  * one handler each, at most `maxConnections` at a time. A handler that fails closes only its own
  * connection and is reported to the owner as a typed value. Each handler owns its error scope;
  * cancellation releases its socket without reporting a failure (ADR0017).
  */
object BearerServer:
  /** Listen on `host:port` (`None`: all interfaces / an ephemeral port). The resource yields the
    * bound address and keeps accepting until it is released.
    */
  def listen[F[_]: Async, E <: DomainError](
      host: Option[Host],
      port: Option[Port],
      maxConnections: Int = 64
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
        server.accept
          .map { socket =>
            Stream.eval(
              HandlerFailure
                .capture[F, E, Unit] {
                  handle(Bearer.fromSocket(socket), socket.peerAddress.asIpUnsafe)
                }
                .flatMap(_.fold(onFailure(socket.peerAddress.asIpUnsafe, _), _ => Async[F].unit))
            )
          }
          .parJoin(maxConnections)
          .compile
          .drain
          .background
          .as(server.address.asIpUnsafe)
      }

  /** Why the local socket path cannot be used. */
  enum SocketPathError extends DomainError:
    /** Something other than a socket (a file, a directory, a link) is at the path; it is left
      * alone.
      */
    case NotASocket(path: String)
    case Inspect(path: String, cause: java.io.IOException)
    case Remove(path: String, cause: java.io.IOException)
    case Restrict(path: String, cause: java.io.IOException)

    def message: String = this match
      case NotASocket(p)  => s"$p exists and is not a socket; refusing to remove it"
      case Inspect(p, e)  => s"cannot inspect $p: ${e.getMessage}"
      case Remove(p, e)   => s"cannot remove the stale socket $p: ${e.getMessage}"
      case Restrict(p, e) => s"cannot restrict the permissions of $p: ${e.getMessage}"

  /** Owner and group may read and write the socket, others nothing. The reference node leaves the
    * mode to the umask and traces a warning when others can read or write the socket
    * (`checkLocalSocket` in `Ouroboros.Network.Diffusion`).
    */
  val SocketPermissions: java.util.Set[java.nio.file.attribute.PosixFilePermission] =
    java.nio.file.attribute.PosixFilePermissions.fromString("rw-rw----")

  /** The file-type bits of `st_mode` (`S_IFMT`) and the value of a socket (`S_IFSOCK`). */
  private val FileTypeMask = 0xf000
  private val SocketType = 0xc000

  /** Remove a stale socket left at `path` by an earlier run, as the reference node's
    * `removeStaleLocalSocket` does (`Cardano.Node.Configuration.Socket`), but only a socket: a path
    * that is anything else (a link is inspected, not followed) is refused and left alone. A missing
    * path is fine.
    */
  private[network] def removeStale(path: java.nio.file.Path): Either[SocketPathError, Unit] =
    import java.io.IOException
    import java.nio.file.{Files, LinkOption, NoSuchFileException}
    val shown = path.toString
    Either
      .catchOnly[IOException](
        Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS).asInstanceOf[Integer].toInt
      )
      .fold(
        {
          case _: NoSuchFileException => Right(())
          case e                      => Left(SocketPathError.Inspect(shown, e))
        },
        mode =>
          if (mode & FileTypeMask) != SocketType then Left(SocketPathError.NotASocket(shown))
          else
            Either
              .catchOnly[IOException](Files.deleteIfExists(path))
              .void
              .leftMap(SocketPathError.Remove(shown, _))
      )

  /** Restrict the bound socket at `path` to [[SocketPermissions]]. */
  private[network] def restrict(path: java.nio.file.Path): Either[SocketPathError, Unit] =
    Either
      .catchOnly[java.io.IOException](
        java.nio.file.Files.setPosixFilePermissions(path, SocketPermissions)
      )
      .void
      .leftMap(SocketPathError.Restrict(path.toString, _))

  /** The same on a Unix domain socket at `path`, for node-to-client clients such as `cardano-cli`.
    * A stale socket there is replaced (see [[removeStale]]); anything else at the path is a
    * [[SocketPathError.NotASocket]]. Once bound, the socket's mode is restricted to
    * [[SocketPermissions]]. Between the bind and the restriction the socket has the mode the
    * process umask gives it: a directory that only the intended users can enter, or a restrictive
    * umask, is what keeps others out for certain.
    */
  def listenUnix[F[_]: Async, E <: DomainError](path: java.nio.file.Path, maxConnections: Int = 16)(
      onFailure: HandlerFailure[E] => F[Unit]
  )(
      handle: Handle[F, E] ?=> Bearer[F] => F[Unit]
  )(using Raise[F, SocketPathError]): Resource[F, Unit] =
    import com.comcast.ip4s.UnixSocketAddress
    import fs2.io.net.Network
    def lift[A](body: => Either[SocketPathError, A]): F[A] =
      Async[F].blocking(body).flatMap(_.fold(_.raise[F, A], Async[F].pure))
    Resource.eval(lift(removeStale(path))) *>
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
          .blocking(java.nio.file.Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS))
          .flatMap(found => Async[F].sleep(5.millis).unlessA(found).as(found))
          .iterateUntil(identity)
          .timeout(5.seconds)
          .void
      ) *>
      Resource.eval(lift(restrict(path)))
