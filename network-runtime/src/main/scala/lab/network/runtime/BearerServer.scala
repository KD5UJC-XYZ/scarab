// SPDX-License-Identifier: Apache-2.0
package lab.network.runtime

import cats.effect.{Async, Resource}
import cats.effect.syntax.all.*
import cats.syntax.all.*
import com.comcast.ip4s.{Host, IpAddress, Port, SocketAddress}
import fs2.Stream
import fs2.io.net.Network
import scala.concurrent.duration.*

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

  /** The same on a Unix domain socket at `path` (an existing file there is replaced), for
    * node-to-client clients such as `cardano-cli`.
    */
  def listenUnix[F[_]: Async](path: java.nio.file.Path, maxConnections: Int = 16)(
      handle: Bearer[F] => F[Unit]
  ): Resource[F, Unit] =
    import fs2.io.net.unixsocket.{UnixSocketAddress, UnixSockets}
    Resource.eval(Async[F].blocking(java.nio.file.Files.deleteIfExists(path))) *>
      UnixSockets
        .forAsync[F]
        .server(UnixSocketAddress(path.toString))
        .map(socket => Stream.eval(handle(Bearer.fromSocket(socket)).attempt.void))
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
