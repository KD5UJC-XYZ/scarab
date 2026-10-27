// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{Async, Resource}
import cats.effect.syntax.all.*
import cats.mtl.Handle
import cats.syntax.all.*
import com.comcast.ip4s.{Host, IpAddress, Port, SocketAddress}
import fs2.Stream
import fs2.io.net.Network
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
