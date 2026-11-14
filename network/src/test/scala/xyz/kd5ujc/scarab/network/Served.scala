// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{Async, Resource}
import cats.mtl.{Handle, Raise}
import cats.syntax.all.*
import com.comcast.ip4s.*

/** [[NodeToNodeServer.accept]] for suites about the protocols rather than the connection manager:
  * the connection is admitted by a manager of its own, from a fixed address.
  */
object Served:
  val remote: SocketAddress[IpAddress] = SocketAddress(ip"192.0.2.1", port"3001")

  def accept[F[_]: Async, P](
      bearer: Bearer[F],
      magic: Long,
      handlers: ServerHandlers[F, P],
      settings: NodeToNodeServer.Settings = NodeToNodeServer.Settings(),
      limits: ConnectionManager.Limits = ConnectionManager.Limits(),
      negotiated: (HandshakeClient.Agreed, Mux[F]) => Resource[F, Unit] = (_, _: Mux[F]) =>
        Resource.unit[F]
  )(using Raise[F, NetworkError]): F[ConnectionEnd] =
    ConnectionManager.create[F](limits).flatMap { manager =>
      Handle
        .allow[ConnectionError](
          manager
            .include(remote, None)
            .use(NodeToNodeServer.accept(bearer, magic, handlers, _, settings, negotiated))
        )
        .rescue(error => Async[F].pure(ConnectionEnd.Refused(error)))
    }
