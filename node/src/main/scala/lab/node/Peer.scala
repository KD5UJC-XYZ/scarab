// SPDX-License-Identifier: Apache-2.0
package lab.node

import cats.effect.{Async, Resource}
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import com.comcast.ip4s.{Host, SocketAddress}
import lab.cbor.Bytes
import lab.network.{CardanoBlockFetch, ChainSync, NtNHeader}
import lab.network.runtime.{ChainSyncClient, NetworkError, NodeToNode}

enum SyncEvent:
  case RollForward(header: Header, tip: ChainSync.Tip)
  case RollBackward(point: ChainSync.Point, tip: ChainSync.Tip)

/** Upstream chain source: one ChainSync conversation plus block retrieval. */
trait Peer[F[_]]:
  /** The newest point among `candidates` (ordered newest first) the peer also has, if any. */
  def intersect(candidates: Vector[ChainSync.Point]): F[Option[ChainSync.Point]]

  /** Next roll-forward or roll-backward instruction, waiting while the peer is at its tip. */
  def next: F[SyncEvent]

  /** Raw node-to-node block bytes for a header announced by `next`. */
  def fetch(header: Header): F[Bytes]

object Peer:
  final case class Settings(
      address: SocketAddress[Host],
      magic: Long,
      network: NodeToNode.Settings = NodeToNode.Settings()
  )

  /** `host:port` where host is a DNS name or an IP literal. */
  def parseAddress(text: String): Either[String, SocketAddress[Host]] =
    SocketAddress.fromString(text).toRight(s"invalid peer address '$text', expected <host>:<port>")

  /** The node-level classification of a network failure. */
  def peerError(error: NetworkError): PeerError = error match
    case _: NetworkError.Connect | _: NetworkError.Timeout | _: NetworkError.BearerClosed =>
      PeerError.Unavailable(error.message)
    case _: NetworkError.HandshakeRefused | _: NetworkError.VersionMismatch =>
      PeerError.Handshake(error.message)
    case _: NetworkError.IngressLimit | _: NetworkError.Decode |
        _: NetworkError.IllegalTransition =>
      PeerError.Protocol(error.message)

  /** Node-to-node interpreter over `network-runtime`: ChainSync and BlockFetch share one
    * multiplexed connection (KeepAlive runs in the background). Every network failure surfaces as a
    * typed [[PeerError]].
    */
  def nodeToNode[F[_]: Async](settings: Settings)(using
      peerRaise: Raise[F, PeerError]
  ): Resource[F, Peer[F]] =
    val F = Async[F]
    // Contravariance cannot lift a Raise over a different error type, so translate explicitly.
    given Raise[F, NetworkError] = new Raise[F, NetworkError]:
      def functor = F
      def raise[E2 <: NetworkError, A](e: E2): F[A] = peerRaise.raise(peerError(e))
    def protocol[A](reason: String): F[A] = PeerError.Protocol(reason).raise

    NodeToNode
      .connect[F, NtNHeader](
        settings.address,
        settings.magic,
        NtNHeader.codec,
        settings.network
      )
      .map { connection =>
        new Peer[F]:
          def intersect(candidates: Vector[ChainSync.Point]) =
            connection.chainSync.findIntersect(candidates).map(_.map(_._1))

          def next: F[SyncEvent] =
            connection.chainSync.requestNext.flatMap {
              case ChainSyncClient.Update.RollForward(h, tip) =>
                Header
                  .fromNtN(h)
                  .fold(e => protocol(e.message), h => F.pure(SyncEvent.RollForward(h, tip)))
              case ChainSyncClient.Update.RollBackward(point, tip) =>
                F.pure(SyncEvent.RollBackward(point, tip))
            }

          def fetch(header: Header): F[Bytes] =
            CardanoBlockFetch.SpecificPoint.from(header.point) match
              case Left(reason) => protocol(reason)
              case Right(specific) =>
                connection.blockFetch.requestRange(specific.point, specific.point).flatMap {
                  case Some(Vector(block)) => F.pure(block)
                  case Some(other) => protocol(s"expected one block, received ${other.size}")
                  case None        => protocol("peer does not have the announced block")
                }
      }
