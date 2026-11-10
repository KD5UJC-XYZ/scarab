// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.Functor
import cats.effect.{IO, Resource}
import cats.mtl.{Handle, Raise}
import com.comcast.ip4s.*
import xyz.kd5ujc.scarab.codec.DomainError

/** The node-to-node listener on loopback for tests, on a port the kernel chooses. */
object Listeners:
  val Loopback: SocketAddress[IpAddress] = SocketAddress(ip"127.0.0.1", Port.Wildcard)

  /** A listener that cannot be set up fails the test with its typed error. */
  given failing: Raise[IO, TcpSocketError] = new Raise[IO, TcpSocketError]:
    def functor: Functor[IO] = Functor[IO]
    def raise[E2 <: TcpSocketError, A](e: E2): IO[A] =
      IO.raiseError(new AssertionError(s"the listener failed: $e"))

  def loopback[E <: DomainError](
      maxConnections: Option[Int] = Some(64),
      beforeAccept: Option[IO[Unit]] = None
  )(
      onFailure: (SocketAddress[IpAddress], HandlerFailure[E]) => IO[Unit]
  )(
      handle: Handle[IO, E] ?=> (Bearer[IO], SocketAddress[IpAddress]) => IO[Unit]
  ): Resource[IO, SocketAddress[IpAddress]] =
    BearerServer
      .listen[IO, E](Loopback, maxConnections, beforeAccept)(onFailure)(handle)
      .map(_.address)

/** Connection failures as the node's TCP transport raises them, for tests whose transport is not a
  * socket.
  */
object ConnectFailures:
  /** The typed cause of a connection to `remote` that the peer refused. */
  def refused(remote: SocketAddress[IpAddress]): TcpSocketError =
    TcpSocketError.Failed(SocketStep.Connect(remote), ErrnoCause.Known(Errno.ECONNREFUSED))

  /** A refused connection to `address`, as `NetworkError.Connect`. */
  def connect(address: SocketAddress[IpAddress]): NetworkError.Connect =
    NetworkError.Connect(address.toString, refused(address))
