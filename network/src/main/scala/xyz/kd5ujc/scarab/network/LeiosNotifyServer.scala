// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{Async, Concurrent, Deferred, Ref}
import cats.effect.std.Queue
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import fs2.concurrent.SignallingRef
import xyz.kd5ujc.scarab.protocol.{EbPoint, HfcHeader, LeiosNotify, LeiosWireError, ProtocolError}
import xyz.kd5ujc.scarab.protocol.LeiosWord.Word32

/** The Leios build's LeiosNotify server (`leiosNotifyServerPeerLookahead`,
  * `LeiosDemoOnlyTestNotify.hs` 636-722 of ouroboros-consensus 2416493b, run by
  * `aLeiosNotifyServer`, `Ouroboros/Consensus/Network/NodeToNode.hs` 1891-1909): each
  * `MsgLeiosNotificationRequestNext` is counted ([[Replies.requested]], `incr`) and held, and the
  * client's next message read ahead, while a sender answers the held requests in order, each with
  * the next reply the connection's queue gives ([[Replies.next]], `next`). `MsgQuit` makes every
  * reply not yet sent a `MsgCanceled` (`responder`'s `orElse`, the quit first), and once all the
  * held requests are answered (`drain`) the server sends `MsgDone`, which ends the conversation, so
  * the peer can demote the connection and start the protocol again on the same bearer. A request
  * the connection counts as excessive ends it ([[NetworkError.LeiosNotifyExcessiveRequests]]), as
  * `incr` refuses it (`NodeToNode.hs` 821-827). Idle waits forever, under its byte limit
  * (`smallByteLimit`, `byteLimitsLeiosNotify` and `timeLimitsLeiosNotify`, `:241-255`).
  */
object LeiosNotifyServer:
  /** `leiosNotifyPipelineDepth` (`NodeToNode.hs` 2146 at 2416493b): the most credits a connection
    * may hold.
    */
  val PipelineDepth: Int = 1000

  /** `WhetherExcessiveRequests` (`LeiosDemoOnlyTestNotify.hs` 525-527 at ouroboros-consensus
    * 2416493b): whether counting a request found the connection already at `depth` credits.
    */
  enum Requested derives CanEqual:
    case Excessive(depth: Int)
    case NotExcessive

  /** A reply to a held request (`Message ... StBusy StIdle` but `MsgCanceled`, which only the
    * server's quit makes): an endorser block's announcement, by the header that announces it, an
    * offer of its body at its size, or an offer of its closure. Votes are not among them: the node
    * holds none to relay (#54).
    */
  enum Reply:
    case Announcement(header: HfcHeader)
    case BlockOffer(point: EbPoint, ebSize: Word32)
    case BlockTxsOffer(point: EbPoint)

  /** One run's replies (`hLeiosNotifyServer`, `NodeToNode.hs` 769-936 at 2416493b): `requested`
    * counts a request (`incr`), `next` takes the queue's next reply, waiting for one (`next`), and
    * `pump` feeds the queue for as long as the server runs, which its failure ends (`withAsync pump
    * ... link`, `:1898-1909`).
    */
  trait Replies[F[_]]:
    def requested: F[Requested]
    def next: F[Reply]
    def pump: F[Unit]

  /** Replies with nothing to notify: the requests are counted and none is answered. */
  def silent[F[_]](depth: Int = PipelineDepth)(using F: Concurrent[F]): F[Replies[F]] =
    Ref.of[F, Int](0).map { credits =>
      new Replies[F]:
        def requested: F[Requested] =
          credits.modify(n =>
            if n == depth then (n, Requested.Excessive(depth)) else (n + 1, Requested.NotExcessive)
          )
        def next: F[Reply] = F.never
        def pump: F[Unit] = F.unit
    }

  def run[F[_]](channel: MuxChannel[F], replies: Replies[F])(using
      F: Async[F],
      raise: Raise[F, NetworkError]
  ): F[Unit] =
    val id = Protocols.LeiosNotify
    def illegal[A](detail: NetworkError.Violation): F[A] =
      NetworkError.IllegalTransition(id, detail).raise[F, A]
    def send(message: LeiosNotify.Message): F[Unit] =
      LeiosNotify.encode(message).fold(e => illegal(ProtocolError.Leios(e)), channel.send)
    def message(reply: Reply): F[LeiosNotify.Message] = reply match
      case Reply.Announcement(header) =>
        HfcHeader
          .encode(header)
          .fold(illegal, h => F.pure(LeiosNotify.Message.BlockAnnouncement(h)))
      case Reply.BlockOffer(point, size) => F.pure(LeiosNotify.Message.BlockOffer(point, size))
      case Reply.BlockTxsOffer(point)    => F.pure(LeiosNotify.Message.BlockTxsOffer(point))
    (Deferred[F, Unit], Queue.unbounded[F, Unit], SignallingRef.of[F, Long](0L)).flatMapN {
      (quit, held, answered) =>
        // `responder`: once the client quit, `MsgCanceled`; else the next reply, or `MsgCanceled`
        // if the quit comes first
        val reply: F[LeiosNotify.Message] = quit.tryGet.flatMap {
          case Some(_) => F.pure(LeiosNotify.Message.Canceled)
          case None =>
            F.race(quit.get, replies.next)
              .flatMap(_.fold(_ => F.pure(LeiosNotify.Message.Canceled), message))
        }
        // the sender thread: one reply per held request, in the order they arrived
        val sender: F[Unit] =
          (held.take *> reply.flatMap(send) *> answered.update(_ + 1)).foreverM[Unit]
        // `start` and `busy`, with `requests` read so far; `drain` after the quit
        def loop(requests: Long): F[Unit] =
          channel.recv(LeiosNotify.SmallByteLimit)(LeiosNotify.idleIncremental).flatMap {
            case LeiosNotify.Message.RequestNext =>
              replies.requested.flatMap {
                case Requested.Excessive(depth) =>
                  NetworkError.LeiosNotifyExcessiveRequests(depth).raise[F, Unit]
                case Requested.NotExcessive => held.offer(()) *> loop(requests + 1)
              }
            case LeiosNotify.Message.Quit =>
              quit.complete(()) *> answered.waitUntil(_ >= requests) *>
                send(LeiosNotify.Message.Done)
            // the Idle decoder admits no other message: the codec's defect, raised as the transition
            case other => illegal(ProtocolError.Leios(LeiosWireError.NotAdmitted(tag(other))))
          }
        // the pump lives exactly as long as the conversation, and its failure ends it
        F.race(replies.pump *> F.never[Unit], F.race(sender, loop(0L))).void
    }

  /** A message's key on the wire (`codecLeiosNotify`, `LeiosDemoOnlyTestNotify.hs:259-352` at
    * ouroboros-consensus 2416493b).
    */
  private def tag(message: LeiosNotify.Message): Int = message match
    case LeiosNotify.Message.Done                 => 0
    case LeiosNotify.Message.Quit                 => 1
    case LeiosNotify.Message.Canceled             => 2
    case LeiosNotify.Message.RequestNext          => 3
    case LeiosNotify.Message.BlockAnnouncement(_) => 4
    case LeiosNotify.Message.BlockOffer(_, _)     => 5
    case LeiosNotify.Message.BlockTxsOffer(_)     => 6
    case LeiosNotify.Message.Votes(_, _)          => 7
