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
import xyz.kd5ujc.scarab.crypto.bls.Vote

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
    * offer of its body at its size, an offer of its closure, or votes.
    */
  enum Reply:
    case Announcement(header: HfcHeader)
    case BlockOffer(point: EbPoint, ebSize: Word32)
    case BlockTxsOffer(point: EbPoint)
    case Votes(votes: Vector[Vote])

  /** One run's replies (`hLeiosNotifyServer`, `NodeToNode.hs` 769-936 at 2416493b): `requested`
    * counts a request (`incr`), `next` takes the queue's next reply, waiting for one (`next`),
    * `poll` takes it only when one is queued, and `pump` feeds the queue for as long as the server
    * runs, which its failure ends (`withAsync pump ... link`, `:1898-1909`).
    */
  trait Replies[F[_]]:
    def requested: F[Requested]
    def next: F[Reply]
    def poll: F[Option[Reply]]
    def pump: F[Unit]

  /** What `responder` answers a held request with (`LeiosDemoOnlyTestNotify.hs` 647-655 at
    * ouroboros-consensus 2416493b): `MsgCanceled`, or a reply of the queue.
    */
  private[network] enum Answer derives CanEqual:
    case Canceled
    case Replied(reply: Reply)

  /** `responder` 's choice: once the client quit, `MsgCanceled`; else a reply queued already, or
    * the next reply, or `MsgCanceled` if the quit comes first. A reply taken is answered only if
    * the quit has not come by then, so the quit wins as `orElse` 's first branch does at its
    * commit; the reply it displaces goes with the queue, which no request reads after the quit.
    */
  private[network] def answer[F[_]](quit: Deferred[F, Unit], replies: Replies[F])(using
      F: Concurrent[F]
  ): F[Answer] =
    def unlessQuit(taken: Reply): F[Answer] =
      quit.tryGet.map(_.fold(Answer.Replied(taken))(_ => Answer.Canceled))
    quit.tryGet.flatMap {
      case Some(_) => F.pure(Answer.Canceled)
      case None =>
        replies.poll.flatMap {
          case Some(queued) => unlessQuit(queued)
          case None =>
            F.race(quit.get, replies.next).flatMap(_.fold(_ => F.pure(Answer.Canceled), unlessQuit))
        }
    }

  /** Replies with nothing to notify: the requests are counted and none is answered. */
  def silent[F[_]](depth: Int = PipelineDepth)(using F: Concurrent[F]): F[Replies[F]] =
    Ref.of[F, Int](0).map { credits =>
      new Replies[F]:
        def requested: F[Requested] =
          credits.modify(n =>
            if n == depth then (n, Requested.Excessive(depth)) else (n + 1, Requested.NotExcessive)
          )
        def next: F[Reply] = F.never
        def poll: F[Option[Reply]] = F.pure(None)
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
      case Reply.Votes(votes) => F.pure(LeiosNotify.Message.Votes(votes, votes.map(_.encode)))
    (Deferred[F, Unit], Queue.unbounded[F, Unit], SignallingRef.of[F, Long](0L)).flatMapN {
      (quit, held, answered) =>
        val reply: F[LeiosNotify.Message] = answer(quit, replies).flatMap {
          case Answer.Canceled       => F.pure(LeiosNotify.Message.Canceled)
          case Answer.Replied(taken) => message(taken)
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

  /** A message's key on the wire ([[LeiosNotifyPipelined.tag]]). */
  private def tag(message: LeiosNotify.Message): Int = LeiosNotifyPipelined.tag(message)
