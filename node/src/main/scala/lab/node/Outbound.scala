// SPDX-License-Identifier: Apache-2.0
package lab.node

import cats.effect.{Async, Resource}
import cats.mtl.Raise
import cats.syntax.all.*
import com.comcast.ip4s.{Host, IpAddress, SocketAddress}
import lab.network.ConnectionState
import lab.network.runtime.*

/** Outbound connections through the connection manager (G2): one connection per peer address, a
  * limit on the connections we open, and reuse of an inbound duplex connection the peer already
  * made to us.
  */
object Outbound:
  /** What the dialler reports for the log. */
  enum Event:
    /** The peer has a duplex connection to us on which our client already runs (the inbound
      * governor started it): no second connection is opened; the dialler waits for it to end.
      */
    case Reused(remote: String)

  /** `open` (the transport, the handshake and the clients, see [[Peer.nodeToNode]]) for `address`,
    * with the connection recorded in `manager` for its whole life. A connection-manager refusal is
    * a [[PeerError.Unavailable]], so the supervisor retries it like any other peer failure.
    */
  def connect[F[_]: Async](
      manager: ConnectionManager[F],
      address: SocketAddress[Host],
      open: Resource[F, Peer[F]],
      report: Option[Event => F[Unit]] = None
  )(using peerRaise: Raise[F, PeerError]): Resource[F, Peer[F]] =
    val F = Async[F]
    given Raise[F, ConnectionError] = new Raise[F, ConnectionError]:
      def functor = F
      def raise[E2 <: ConnectionError, A](e: E2): F[A] =
        peerRaise.raise(PeerError.Unavailable(e.message))

    def resolved: F[SocketAddress[IpAddress]] =
      ConnectionManager
        .resolve[F](address)
        .handleErrorWith(t =>
          peerRaise.raise(
            PeerError.Unavailable(
              s"cannot resolve ${address.host}: ${Option(t.getMessage).getOrElse(t.toString)}"
            )
          )
        )

    /** While the peer's own duplex connection to us is up, our client runs on it already. */
    def awaitReusable(remote: SocketAddress[IpAddress]): F[Unit] =
      manager.lookup(remote).flatMap {
        case Some(live)
            if live.state.canRunClients && live.state.direction.contains(
              ConnectionState.Provenance.Inbound
            ) =>
          report.traverse_(_(Event.Reused(remote.toString))) *> live.closed *> awaitReusable(remote)
        case _ => F.unit
      }

    for
      remote <- Resource.eval(resolved)
      _ <- Resource.eval(awaitReusable(remote))
      connection <- manager.outbound(remote)
      peer <- open
      _ <- Resource.eval(
        connection.connected *> connection.negotiated(ConnectionState.DataFlow.Unidirectional)
      )
    yield peer
