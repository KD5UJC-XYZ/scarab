// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{Async, Resource}
import cats.effect.std.Mutex
import cats.syntax.all.*
import java.net.{StandardProtocolFamily, UnixDomainSocketAddress}
import java.nio.channels.SocketChannel

/** The client end of a local socket for tests: a JDK channel connected to `path`, read and written
  * as the node's own local sockets are ([[LocalSockets.stream]]). A client on fs2's Unix sockets
  * leaves a failure nobody joins when it is closed while a read is blocked (issue 644).
  */
object LocalClient:
  def connect[F[_]](path: java.nio.file.Path)(using F: Async[F]): Resource[F, Bearer[F]] =
    Resource
      .make(F.blocking {
        val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
        channel.connect(UnixDomainSocketAddress.of(path))
        channel
      })(channel => F.blocking(channel.close()))
      .evalMap(channel => Mutex[F].map(LocalSockets.stream(channel, _)))
