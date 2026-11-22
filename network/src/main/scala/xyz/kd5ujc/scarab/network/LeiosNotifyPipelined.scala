// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{Async, Deferred}
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import fs2.concurrent.SignallingRef
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.bls.Vote
import xyz.kd5ujc.scarab.protocol.{EbPoint, LeiosNotify, LeiosWireError, ProtocolError}
import xyz.kd5ujc.scarab.protocol.LeiosWord.Word32

/** A reply of the LeiosNotify server to a request (`Message (LeiosNotify ...) StBusy StIdle`,
  * `LeiosDemoOnlyTestNotify.hs` 156-199 of ouroboros-consensus 2416493b): an announcement, by the
  * hard-fork envelope of the header that announces it; an offer of an endorser block's body at its
  * size, or of its closure; votes; or the cancellation of the request.
  */
enum LeiosNotification:
  case Announcement(header: Bytes)
  case BlockOffer(point: EbPoint, ebSize: Word32)
  case BlockTxsOffer(point: EbPoint)
  case Votes(votes: Vector[Vote])
  case Canceled

/** The pipelined LeiosNotify client (`leiosNotifyClientPeerPipelined`, `LeiosDemoOnlyTestNotify.hs`
  * 529-634 at ouroboros-consensus 2416493b), run by the reference's pipelined driver
  * (`runPipelinedPeerWithLimits`, `aLeiosNotifyClient`, `NodeToNode.hs` 1866-1889).
  *
  *   - Before each request the client waits until it is told to stop, or until there is room below
  *     `depth` and then `ready`, the node's own gate (`awaitImmTipCanForecastNow`), holds; a stop
  *     comes first, during either wait.
  *   - Told to stop (`checkDone`'s `Left`), it sends `MsgQuit` at once, outstanding requests or
  *     not, and the replies still outstanding are collected and not handed on (`AlreadyDraining`),
  *     then the server's `MsgDone` ends it.
  *   - Each reply is received by a receiver of its own, as the driver's receiver queue receives
  *     them, handed to `handle` in the order they arrive, and counted as arrived only after. The
  *     handler raises through the receiver's capability, so a reply it refuses ends the connection
  *     as the reference's thread death does.
  *
  * Busy waits forever and Quit `longWait`, each under `smallByteLimit` (`timeLimitsLeiosNotify` and
  * `byteLimitsLeiosNotify`, `:241-255`); the decoder reads a message in steps
  * ([[LeiosNotify.incremental]]).
  */
object LeiosNotifyPipelined:
  /** `leiosNotifyPipelineDepth` (`NodeToNode.hs` 2146 at 2416493b), the server's bound too. */
  val PipelineDepth: Int = LeiosNotifyServer.PipelineDepth

  /** `longWait`, the time limit of Quit. */
  val QuitTimeout: FiniteDuration = 60.seconds

  final case class Settings(depth: Int = PipelineDepth, quitTimeout: FiniteDuration = QuitTimeout)

  /** The requests outstanding, and whether the client has quit. */
  private final case class Pipe(outstanding: Int, draining: Boolean)

  def run[F[_]](
      channel: MuxChannel[F],
      stop: Deferred[F, Unit],
      ready: F[Unit],
      handle: LeiosNotification => Raise[F, NetworkError] ?=> F[Unit],
      settings: Settings = Settings()
  )(using
      F: Async[F],
      raise: Raise[F, NetworkError],
      reader: LeiosNotify.AnnouncementReader
  ): F[Unit] =
    val id = Protocols.LeiosNotify
    def illegal[B](cause: NetworkError.Violation)(using Raise[F, NetworkError]): F[B] =
      NetworkError.IllegalTransition(id, cause).raise[F, B]
    def send(message: LeiosNotify.Message)(using Raise[F, NetworkError]): F[Unit] =
      LeiosNotify.encode(message).fold(e => illegal(ProtocolError.Leios(e)), channel.send)
    // through the capability of the fiber that receives, so a reply's refusal is that fiber's
    def receive(state: LeiosNotify.State)(using Raise[F, NetworkError]): F[LeiosNotify.Message] =
      channel.recv(LeiosNotify.SmallByteLimit)(LeiosNotify.incremental(state))
    SignallingRef.of[F, Pipe](Pipe(0, draining = false)).flatMap { pipe =>
      val sender = Fibers.scoped[F, NetworkError, Unit](NetworkError.Defect(_)) {
        // `checkDone` and the wait for a reply at the depth, which the reference reads in one
        // transaction: the stop before either; the gate read once there is room, so a request is
        // sent only while the gate holds as the room comes. Only this fiber adds to what is
        // outstanding, so the room found stays while the gate is read
        val next: F[Boolean] =
          F.race(stop.get, pipe.waitUntil(_.outstanding < settings.depth) *> ready).map(_.isLeft)
        def loop: F[Unit] =
          stop.tryGet.flatMap {
            case Some(_) => quit
            case None =>
              next.flatMap { stopped =>
                if stopped then quit
                else
                  stop.tryGet.flatMap {
                    case Some(_) => quit
                    case None =>
                      pipe.update(p => p.copy(outstanding = p.outstanding + 1)) *>
                        send(LeiosNotify.Message.RequestNext) *> loop
                  }
              }
          }
        // the draining flag set before `MsgQuit` goes out, so no reply after it is handed on
        def quit: F[Unit] =
          pipe.update(_.copy(draining = true)) *> send(LeiosNotify.Message.Quit)
        loop
      }
      val receiver = Fibers.scoped[F, NetworkError, Unit](NetworkError.Defect(_)) {
        def loop: F[Unit] =
          pipe.waitUntil(p => p.outstanding > 0 || p.draining) *> pipe.get.flatMap { p =>
            if p.outstanding > 0 then
              receive(LeiosNotify.State.Busy)
                .flatMap(notification)
                .flatMap(n => pipe.get.flatMap(now => handle(n).unlessA(now.draining))) *>
                pipe.update(q => q.copy(outstanding = q.outstanding - 1)) *> loop
            else
              Timed
                .within(settings.quitTimeout, id, "Quit")(receive(LeiosNotify.State.Quit))
                .flatMap {
                  case LeiosNotify.Message.Done => F.unit
                  case other => illegal(ProtocolError.Leios(LeiosWireError.NotAdmitted(tag(other))))
                }
          }
        loop
      }
      Fibers.failFast(List(sender, receiver)).flatMap(_.fold(_.raise[F, Unit], F.pure))
    }

  /** A message the Busy decoder admitted, as the reply it is; the decoder admits no other, so
    * another is the codec's defect, raised as the transition it would be.
    */
  private def notification[F[_]](message: LeiosNotify.Message)(using
      F: cats.Applicative[F],
      raise: Raise[F, NetworkError]
  ): F[LeiosNotification] = message match
    case LeiosNotify.Message.BlockAnnouncement(h) => F.pure(LeiosNotification.Announcement(h))
    case LeiosNotify.Message.BlockOffer(p, size)  => F.pure(LeiosNotification.BlockOffer(p, size))
    case LeiosNotify.Message.BlockTxsOffer(p)     => F.pure(LeiosNotification.BlockTxsOffer(p))
    case LeiosNotify.Message.Votes(votes, _)      => F.pure(LeiosNotification.Votes(votes))
    case LeiosNotify.Message.Canceled             => F.pure(LeiosNotification.Canceled)
    case other @ (LeiosNotify.Message.Done | LeiosNotify.Message.Quit |
        LeiosNotify.Message.RequestNext) =>
      NetworkError
        .IllegalTransition(
          Protocols.LeiosNotify,
          ProtocolError.Leios(LeiosWireError.NotAdmitted(tag(other)))
        )
        .raise[F, LeiosNotification]

  /** A message's key on the wire (`codecLeiosNotify`, `LeiosDemoOnlyTestNotify.hs:259-352` at
    * ouroboros-consensus 2416493b).
    */
  private[network] def tag(message: LeiosNotify.Message): Int = message match
    case LeiosNotify.Message.Done                 => 0
    case LeiosNotify.Message.Quit                 => 1
    case LeiosNotify.Message.Canceled             => 2
    case LeiosNotify.Message.RequestNext          => 3
    case LeiosNotify.Message.BlockAnnouncement(_) => 4
    case LeiosNotify.Message.BlockOffer(_, _)     => 5
    case LeiosNotify.Message.BlockTxsOffer(_)     => 6
    case LeiosNotify.Message.Votes(_, _)          => 7
