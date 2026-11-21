// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.Async
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import xyz.kd5ujc.scarab.protocol.{LeiosNotify, LeiosWireError, ProtocolError}

/** The Leios build's LeiosNotify server, as the reference's runs it with nothing to notify
  * (`leiosNotifyServerPeerLookahead`, `LeiosDemoOnlyTestNotify.hs` 636-722 of ouroboros-consensus
  * 2416493b, run by `aLeiosNotifyServer`, `Ouroboros/Consensus/Network/NodeToNode.hs` 1891-1909):
  * each `MsgLeiosNotificationRequestNext` is counted and held, its reply waiting for a notification
  * that never comes, and the client's next message read ahead; `MsgQuit` is answered with a
  * `MsgCanceled` for each request held, then `MsgDone`, which ends the conversation, so the peer can
  * demote the connection and start the protocol again on the same bearer. A request that arrives
  * with [[PipelineDepth]] held ends the connection ([[NetworkError.LeiosNotifyExcessiveRequests]]),
  * as `incr` refuses it (`NodeToNode.hs` 821-827). Idle waits forever, under its byte limit
  * (`smallByteLimit`, `byteLimitsLeiosNotify` and `timeLimitsLeiosNotify`, `:241-255`).
  *
  * Not mirrored (#54): the node notifies nothing, neither announcements, offers nor votes, so every
  * request is held until the client quits.
  */
object LeiosNotifyServer:
  /** `leiosNotifyPipelineDepth` (`NodeToNode.hs` 2146 at 2416493b): the most requests a client may
    * leave outstanding.
    */
  val PipelineDepth: Int = 1000

  def run[F[_]](channel: MuxChannel[F], depth: Int = PipelineDepth)(using
      F: Async[F],
      raise: Raise[F, NetworkError]
  ): F[Unit] =
    val id = Protocols.LeiosNotify
    def send(message: LeiosNotify.Message): F[Unit] =
      LeiosNotify
        .encode(message)
        .fold(e => NetworkError.IllegalTransition(id, ProtocolError.Leios(e)).raise[F, Unit], channel.send)
    def loop(held: Int): F[Unit] =
      channel.recv(LeiosNotify.SmallByteLimit)(LeiosNotify.idleIncremental).flatMap {
        case LeiosNotify.Message.RequestNext =>
          if held >= depth then NetworkError.LeiosNotifyExcessiveRequests(depth).raise[F, Unit]
          else loop(held + 1)
        // the held requests' replies go first, then the end of the conversation (`drain`)
        case LeiosNotify.Message.Quit =>
          send(LeiosNotify.Message.Canceled).replicateA_(held) *> send(LeiosNotify.Message.Done)
        // the Idle decoder admits no other message: the codec's defect, raised as the transition
        case other =>
          NetworkError
            .IllegalTransition(id, ProtocolError.Leios(LeiosWireError.NotAdmitted(tag(other))))
            .raise[F, Unit]
      }
    loop(0)

  /** A message's key on the wire (`codecLeiosNotify`, `LeiosDemoOnlyTestNotify.hs:259-352`). */
  private def tag(message: LeiosNotify.Message): Int = message match
    case LeiosNotify.Message.Done                 => 0
    case LeiosNotify.Message.Quit                 => 1
    case LeiosNotify.Message.Canceled             => 2
    case LeiosNotify.Message.RequestNext          => 3
    case LeiosNotify.Message.BlockAnnouncement(_) => 4
    case LeiosNotify.Message.BlockOffer(_, _)     => 5
    case LeiosNotify.Message.BlockTxsOffer(_)     => 6
    case LeiosNotify.Message.Votes(_, _)          => 7
