// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.Applicative
import cats.syntax.all.*
import com.comcast.ip4s.{Host, IpAddress, SocketAddress}
import fs2.Chunk
import fs2.io.net.Socket
import xyz.kd5ujc.scarab.protocol.ConnectionIdentityError

/** A bearer over an fs2 socket, for tests whose other end is a plain fs2 socket (an IPv6 one, or a
  * scripted peer); the node's own sockets are [[TcpSockets]]'.
  */
object Fs2Bearer:
  private def numeric(host: Host): Either[ConnectionIdentityError, IpAddress] = host match
    case ip: IpAddress => Right(ip)
    case other         => Left(ConnectionIdentityError.UnresolvedAddress(other.toString))

  def fromSocket[F[_]: Applicative](socket: Socket[F]): Bearer[F] = new Bearer[F]:
    override def connectionId: Option[F[Either[ConnectionIdentityError, ConnectionId]]] =
      (socket.address, socket.peerAddress) match
        case (local: SocketAddress[?], remote: SocketAddress[?]) =>
          Some(
            Applicative[F].pure(
              (numeric(local.host), numeric(remote.host)).tupled.flatMap { (a, b) =>
                ConnectionId.fromSocketAddresses(
                  SocketAddress(a, local.port),
                  SocketAddress(b, remote.port)
                )
              }
            )
          )
        case _ => None
    def reads = socket.reads
    def write(bytes: Chunk[Byte]) = socket.write(bytes)
    def close = socket.endOfOutput
