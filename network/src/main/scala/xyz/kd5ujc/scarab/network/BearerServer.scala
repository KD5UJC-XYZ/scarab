// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{Async, Resource}
import cats.effect.syntax.all.*
import cats.mtl.Handle
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

  /** The same on a Unix domain socket at `path` (an existing file there is replaced), for
    * node-to-client clients such as `cardano-cli`.
    */
  def listenUnix[F[_]: Async, E <: DomainError](path: java.nio.file.Path, maxConnections: Int = 16)(
      onFailure: HandlerFailure[E] => F[Unit]
  )(
      handle: Handle[F, E] ?=> Bearer[F] => F[Unit]
  ): Resource[F, Unit] =
    import com.comcast.ip4s.UnixSocketAddress
    import fs2.io.net.Network
    Resource.eval(Async[F].blocking(java.nio.file.Files.deleteIfExists(path))) *>
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
          .blocking(java.nio.file.Files.exists(path))
          .flatMap(found => Async[F].sleep(5.millis).unlessA(found).as(found))
          .iterateUntil(identity)
          .timeout(5.seconds)
          .void
      )
