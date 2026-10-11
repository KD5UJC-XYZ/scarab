// SPDX-License-Identifier: Apache-2.0
package lab.network.runtime

import cats.effect.{Async, Resource}
import cats.effect.syntax.all.*
import cats.syntax.all.*
import com.comcast.ip4s.{Host, IpAddress, Port, SocketAddress}
import fs2.Stream
import fs2.io.net.Network

/** Inbound side of the transport: a listening TCP socket whose accepted connections are handed to
  * one handler each, at most `maxConnections` at a time. A handler that fails closes only its own
  * connection.
  */
object BearerServer:
  /** Listen on `host:port` (`None`: all interfaces / an ephemeral port). The resource yields the
    * bound address and keeps accepting until it is released.
    */
  def listen[F[_]: Async](
      host: Option[Host],
      port: Option[Port],
      maxConnections: Int = 64
  )(
      handle: (Bearer[F], SocketAddress[IpAddress]) => F[Unit]
  ): Resource[F, SocketAddress[IpAddress]] =
    Network
      .forAsync[F]
      .serverResource(host, port)
      .flatMap { (bound, sockets) =>
        sockets
          .map { socket =>
            Stream.eval(
              socket.remoteAddress
                .flatMap(remote => handle(Bearer.fromSocket(socket), remote))
                .attempt
                .void
            )
          }
          .parJoin(maxConnections)
          .compile
          .drain
          .background
          .as(bound)
      }
