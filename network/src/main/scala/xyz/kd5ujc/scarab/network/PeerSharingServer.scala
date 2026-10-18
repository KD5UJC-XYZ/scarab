// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import xyz.kd5ujc.scarab.protocol.ProtocolError
import cats.effect.Temporal
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import scala.annotation.tailrec
import xyz.kd5ujc.scarab.protocol.PeerSharing
import xyz.kd5ujc.scarab.protocol.PeerSharing.{Address, Amount}

import NetworkError.RuntimeViolation

/** PeerSharing responder: answers every `ShareRequest` with the peers the source names, until the
  * client says `Done`. In `StIdle` the client may keep us waiting for ever
  * (`timeLimitsPeerSharing`, `Codec.hs:160`), so there is no idle timeout; the source decides who
  * is shared (the policy is [[selection.PeerShare]]).
  *
  * The driver keeps the protocol whatever the source does: an answer is cut to the amount asked
  * for, since a longer one is a protocol error (`Type.hs:80-82`), and to what fits the 5760 bytes
  * of a message.
  */
object PeerSharingServer:
  /** The peers to tell about, given how many were asked for. */
  type Source[F[_]] = Amount => F[Vector[Address]]

  def empty[F[_]: Temporal]: Source[F] = _ => Temporal[F].pure(Vector.empty)

  final case class Settings(limits: PeerSharing.Limits = PeerSharing.Limits())

  /** The longest prefix of `peers`, at most `amount` of them, that encodes within the limit. */
  private[network] def fitting(
      peers: Vector[Address],
      amount: Amount,
      settings: Settings
  ): PeerSharing.Message.SharePeers =
    def encodes(n: Int) =
      PeerSharing
        .encode(
          PeerSharing.State.Busy,
          PeerSharing.Role.Server,
          PeerSharing.Message.SharePeers(peers.take(n)),
          settings.limits
        )
        .isRight
    // binary search: the encoding only grows with n
    @tailrec def search(low: Int, high: Int): Int =
      if low >= high then low
      else
        val mid = (low + high + 1) / 2
        if encodes(mid) then search(mid, high) else search(low, mid - 1)
    PeerSharing.Message.SharePeers(peers.take(search(0, math.min(amount.value, peers.size))))

  /** Serve one conversation; it ends at `Done`. Run it again for the next one on the same channel.
    */
  def run[F[_]: Temporal](
      channel: MuxChannel[F],
      source: Source[F],
      settings: Settings = Settings()
  )(using Raise[F, NetworkError]): F[Unit] =
    val id = Protocols.PeerSharing
    def illegal[A](detail: NetworkError.Violation): F[A] =
      NetworkError.IllegalTransition(id, detail).raise[F, A]
    val step: F[Boolean] =
      channel
        .recv(settings.limits.maxMessageBytes)(
          PeerSharing.decoder(PeerSharing.State.Idle, PeerSharing.Role.Client, settings.limits)
        )
        .flatMap {
          case PeerSharing.Message.ShareRequest(amount) =>
            source(amount).flatMap { peers =>
              PeerSharing
                .encode(
                  PeerSharing.State.Busy,
                  PeerSharing.Role.Server,
                  fitting(peers, amount, settings),
                  settings.limits
                )
                .fold(e => illegal[Unit](ProtocolError.PeerSharing(e)), channel.send)
                .as(true)
            }
          case PeerSharing.Message.Done => Temporal[F].pure(false)
          case other => illegal[Boolean](RuntimeViolation.PeerSharingUnexpected(other))
        }
    step.iterateWhile(identity).void
