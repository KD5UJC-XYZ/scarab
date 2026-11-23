// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{Async, Deferred}
import cats.effect.std.{Queue, Semaphore, Supervisor}
import cats.mtl.{Handle, Raise}
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import fs2.concurrent.Signal
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
  *     `depth` and then `gate`, the node's own (`awaitImmTipCanForecastNow`), holds; a stop comes
  *     first, during either wait.
  *   - Told to stop (`checkDone`'s `Left`), it sends `MsgQuit` at once, outstanding requests or
  *     not, and the replies still outstanding are collected and not handed on (`AlreadyDraining`),
  *     then the server's `MsgDone` ends it.
  *   - Each reply is received by a receiver of its own, as the driver's receiver queue receives
  *     them, and prepared as it arrives ([[Handling]]): what is read ahead of its turn is read in a
  *     fiber of its own, at most `lookahead` replies received ahead of their handing on. The
  *     replies are handed to `handle` in the order they arrived, each with what was prepared for
  *     it, and counted as arrived only after. A reply's turn comes where a client that handles each
  *     reply as it receives it would receive it, and decides there whether the client has quit; a
  *     failure to receive, typed or not, is raised in its turn, after the replies before it are
  *     handed on; and `MsgDone` is waited for, under its limit, only once every reply before it is:
  *     the decisions, the waits and the requests are those of such a client. The handler raises
  *     through the capability of the fiber that hands the replies on, so a reply it refuses ends
  *     the connection as the reference's thread death does.
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

  /** How many replies may be received and prepared ahead of their handing on, at most. */
  val Lookahead: Int = 64

  final case class Settings(
      depth: Int = PipelineDepth,
      quitTimeout: FiniteDuration = QuitTimeout,
      lookahead: Int = Lookahead
  )

  /** What a reply's handling reads ahead of the reply's turn: nothing, with the value its turn
    * takes (`Ready`), or `reading`, run ahead in a fiber of its own while the replies before it are
    * still handed on (`Ahead`), whose turn takes what it gave where it has ended by then, and
    * `otherwise` where it has not, failed or was cancelled; a reading never holds its turn up, and
    * none is started once the client is seen to have quit (one started as it quits is let go of in
    * its turn). A reading is only a head start: it reads nothing an earlier reply's handling
    * changes, or its turn reads that again, and its turn with `otherwise` comes to what it would
    * have come to with the reading. It raises through no capability, a failure being only the head
    * start lost, and it ends soon once cancelled, as the conversation's end waits for that.
    */
  enum Prepared[F[_], P]:
    case Ready(value: P)
    case Ahead(reading: F[P], otherwise: P)

  /** How the replies are handled: each is prepared as it arrives ([[Prepared]]), then handed to
    * `handle` with what was prepared for it, in the order the replies arrived.
    */
  final case class Handling[F[_], P](
      prepare: LeiosNotification => Prepared[F, P],
      handle: P => Raise[F, NetworkError] ?=> F[Unit]
  )

  object Handling:
    /** Each reply handed to `handle` as it is, nothing read ahead. */
    def inOrder[F[_]](
        handle: LeiosNotification => Raise[F, NetworkError] ?=> F[Unit]
    ): Handling[F, LeiosNotification] =
      Handling(Prepared.Ready[F, LeiosNotification](_), handle)

  /** What the receiver passes on, in the order it received it: a reply, with what its turn takes
    * and how to let go of what it reads ahead where its turn finds the client quit; a reply whose
    * preparation failed, which fails the connection where its turn hands it on; the wait of
    * `MsgDone` for every reply before it to be handed on; the failure that ended the receiving; or
    * its end at `MsgDone`.
    */
  private enum Received[F[_], P]:
    case Reply(taken: F[P], released: F[Unit])
    case Unprepared(defect: Throwable)
    case Reached(turn: Deferred[F, Unit])
    case Failed(error: NetworkError)
    case Ended()

  /** What wakes the sender: a place below the depth, one for each reply collected and `depth` at
    * the start, or the stop.
    */
  private enum Wake derives CanEqual:
    case Room, Stop

  /** What the receiver collects next, in the order the sender sent it: the reply to a request, or,
    * after every request sent before the quit, the server's `MsgDone`.
    */
  private enum Expect derives CanEqual:
    case Reply, Done

  /** What the gate's turn found: the gate open, or the stop first. */
  private enum Gated derives CanEqual:
    case Open, Stopped

  def run[F[_], P](
      channel: MuxChannel[F],
      stop: Deferred[F, Unit],
      gate: Signal[F, Boolean],
      handling: Handling[F, P],
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
    // the fibers that read ahead end with the conversation
    Supervisor[F](await = false).use { supervisor =>
      (
        Queue.unbounded[F, Wake],
        Queue.unbounded[F, Expect],
        Deferred[F, Unit],
        Queue.unbounded[F, Received[F, P]],
        Semaphore[F](math.max(1, settings.lookahead).toLong)
      ).flatMapN { (wakes, expected, quitting, staged, ahead) =>
        val sender = Fibers.scoped[F, NetworkError, Unit](NetworkError.Defect(_)) {
          // `checkDone` (the stop, then the forecast, then the depth) and the wait for a reply at the
          // depth, which the reference reads in one transaction that retries until they hold
          // (`NodeToNode.hs` 645-659 and `LeiosDemoOnlyTestNotify.hs` 549-582). Here the stop first,
          // then a place below the depth, then the gate, observed open once there is room: a closed
          // gate is waited for against the stop, an open one is not waited for, and it may close
          // again before the request goes, as it may in the reference once its transaction commits
          val gated: F[Gated] = gate.get.flatMap { open =>
            if open then F.pure(Gated.Open)
            else
              F.race(stop.get, gate.waitUntil(identity))
                .map(_.fold(_ => Gated.Stopped, _ => Gated.Open))
          }
          def loop: F[Unit] =
            stop.tryGet.flatMap {
              case Some(_) => quit
              case None =>
                wakes.take.flatMap {
                  case Wake.Stop => quit
                  case Wake.Room =>
                    gated.flatMap {
                      case Gated.Stopped => quit
                      case Gated.Open =>
                        stop.tryGet.flatMap {
                          case Some(_) => quit
                          case None =>
                            expected.offer(Expect.Reply) *>
                              send(LeiosNotify.Message.RequestNext) *> loop
                        }
                    }
                }
            }
          // the quit recorded before `MsgQuit` goes out, so no reply after it is handed on, and
          // `MsgDone` expected after every reply outstanding
          def quit: F[Unit] =
            quitting.complete(()) *> expected.offer(Expect.Done) *>
              send(LeiosNotify.Message.Quit)
          loop
        }
        // the stop wakes a sender that waits for room; the sender quits only once the stop has come,
        // so this body ends before the conversation does
        val stopping = Fibers.scoped[F, NetworkError, Unit](NetworkError.Defect(_)) {
          stop.get *> wakes.offer(Wake.Stop)
        }
        val receiver = Fibers.scoped[F, NetworkError, Unit](NetworkError.Defect(_)) {
          // a failure to receive, typed or a defect, is passed on in its turn, so the replies before
          // it are handed on first, and the receiving ends
          def received[A](fa: F[A])(next: A => F[Unit]): F[Unit] =
            summon[Handle[F, NetworkError]].attempt(F.defer(fa)).attempt.flatMap {
              case Right(Right(a)) => next(a)
              case Right(Left(e))  => staged.offer(Received.Failed(e))
              case Left(defect)    => staged.offer(Received.Failed(NetworkError.Defect(defect)))
            }
          // a reading ahead runs alone and ends with a value; its turn takes it where it has ended,
          // and otherwise lets it go, cancelled without the turn waiting for that
          def started(p: Prepared[F, P]): F[Received[F, P]] = p match
            case Prepared.Ready(value) => F.pure(Received.Reply(F.pure(value), F.unit))
            case Prepared.Ahead(reading, otherwise) =>
              quitting.tryGet.flatMap {
                // the reply's turn will find the client quit and hand nothing on
                case Some(_) => F.pure(Received.Reply(F.pure(otherwise), F.unit))
                case None =>
                  Deferred[F, Either[Throwable, P]].flatMap { read =>
                    supervisor.supervise(reading.attempt.flatMap(read.complete).void).map { fiber =>
                      val released = supervisor.supervise(fiber.cancel).void
                      // the turn takes the reading where it has ended, and otherwise lets it go
                      Received.Reply(
                        read.tryGet.flatMap {
                          case Some(Right(value)) => F.pure(value)
                          case Some(Left(_))      => F.pure(otherwise)
                          case None               => released.as(otherwise)
                        },
                        released
                      )
                    }
                  }
              }
          // a preparation that fails is the handler's failure, in the reply's turn
          def staging(n: LeiosNotification): F[Unit] =
            F.delay(handling.prepare(n)).attempt.flatMap {
              case Right(prepared) => started(prepared).flatMap(staged.offer)
              case Left(defect)    => staged.offer(Received.Unprepared(defect))
            }
          def loop: F[Unit] =
            expected.take.flatMap {
              case Expect.Reply =>
                ahead.acquire *> received(
                  receive(LeiosNotify.State.Busy).flatMap(notification)
                )(n => staging(n) *> loop)
              case Expect.Done =>
                // `MsgDone` waited for, under its limit, once every reply before it is handed on
                Deferred[F, Unit].flatMap(turn =>
                  staged.offer(Received.Reached(turn)) *> turn.get
                ) *>
                  received(
                    Timed
                      .within(settings.quitTimeout, id, "Quit")(receive(LeiosNotify.State.Quit))
                      .flatMap {
                        case LeiosNotify.Message.Done => F.unit
                        case other =>
                          illegal[Unit](
                            ProtocolError.Leios(LeiosWireError.NotAdmitted(tag(other)))
                          )
                      }
                  )(_ => staged.offer(Received.Ended()))
            }
          loop
        }
        // the replies handed on in the order they arrived, each counted as arrived once handed on.
        // A reply's turn comes where a client that handles each reply as it receives it would
        // receive it, after the reply before it is handled, and it decides there, before it waits
        // for anything read ahead: once the client has quit, the reply is collected and not handed
        // on (`AlreadyDraining`). The handler refuses a reply through this fiber's capability
        val handing = Fibers.scoped[F, NetworkError, Unit](NetworkError.Defect(_)) {
          val arrived: F[Unit] = ahead.release *> wakes.offer(Wake.Room)
          def loop: F[Unit] =
            staged.take.flatMap {
              case Received.Reply(taken, released) =>
                quitting.tryGet.flatMap {
                  case Some(_) => released *> arrived *> loop
                  case None    => taken.flatMap(handling.handle(_)) *> arrived *> loop
                }
              case Received.Unprepared(defect) =>
                quitting.tryGet.flatMap {
                  case Some(_) => arrived *> loop
                  case None    => NetworkError.Defect(defect).raise[F, Unit]
                }
              case Received.Reached(turn) => turn.complete(()) *> loop
              case Received.Failed(error) => error.raise[F, Unit]
              case Received.Ended()       => F.unit
            }
          loop
        }
        wakes.offer(Wake.Room).replicateA_(settings.depth) *>
          Fibers
            .failFast(List(sender, receiver, handing, stopping))
            .flatMap(_.fold(_.raise[F, Unit], F.pure))
      }
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
