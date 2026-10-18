// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import NetworkError.RuntimeViolation

import cats.effect.{Ref, Temporal}
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import xyz.kd5ujc.scarab.protocol.ChainSync
import xyz.kd5ujc.scarab.protocol.ChainSync.{Message, Point, State, Tip}
import scala.concurrent.duration.*

/** Non-pipelined ChainSync client. Every message is checked against the pure state machine. Use
  * from a single fiber.
  */
trait ChainSyncClient[F[_], P]:
  /** The newest of `points` the peer also has, with the peer's tip; `None` if there is none. */
  def findIntersect(points: Vector[Point])(using Raise[F, NetworkError]): F[Option[(Point, Tip)]]

  /** The next instruction. An `AwaitReply` is waited out rather than reported. */
  def requestNext(using Raise[F, NetworkError]): F[ChainSyncClient.Update[P]]

  /** `n` instructions with all requests sent before any reply is read (pipelined). Only valid while
    * the peer is known to be at least `n` blocks ahead; at its tip the later replies wait for new
    * blocks. Replies are checked against the protocol state machine in order.
    */
  def requestNextBatch(n: Int)(using Raise[F, NetworkError]): F[Vector[ChainSyncClient.Update[P]]]

  /** Tell the peer we are finished; the protocol ends. */
  def done(using Raise[F, NetworkError]): F[Unit]

  /** Whether the protocol is idle (no request outstanding): the only state in which `done` is
    * valid, and the state a conversation must be in before the connection can be handed to a new
    * conversation.
    */
  def idle: F[Boolean]

  /** Whether the last message the peer sent was `MsgAwaitReply`: it has nothing newer than what it
    * told us (the reference's `csIdling`, `ChainSync/Client/State.hs`), the signal the Genesis
    * State Machine reads to decide the node has caught up.
    */
  def idling: F[Boolean]

object ChainSyncClient:
  enum Update[+P]:
    case RollForward(payload: P, tip: Tip)
    case RollBackward(point: Point, tip: Tip)

  /** Waits per protocol state; the defaults follow the upstream short wait and must-reply bounds.
    */
  final case class Settings(
      limits: ChainSync.Limits = ChainSync.Limits(),
      intersectTimeout: FiniteDuration = 10.seconds,
      canAwaitTimeout: FiniteDuration = 10.seconds,
      mustReplyTimeout: FiniteDuration = 300.seconds
  )

  def apply[F[_]: Temporal, P](
      channel: MuxChannel[F],
      codec: ChainSync.PayloadCodec[P],
      settings: Settings = Settings()
  ): F[ChainSyncClient[F, P]] =
    val F = Temporal[F]
    val id = Protocols.ChainSync
    def illegal[A](detail: NetworkError.Violation)(using Raise[F, NetworkError]): F[A] =
      NetworkError.IllegalTransition(id, detail).raise[F, A]

    (Ref.of[F, State](State.Idle), Ref.of[F, Boolean](false), Ref.of[F, Boolean](false)).mapN {
      (state, awaiting, busy) =>
        def send(message: Message[P])(using Raise[F, NetworkError]): F[Unit] =
          state.get.flatMap { current =>
            val next = ChainSync.transition(current, ChainSync.Role.Client, message)
            ChainSync
              .encode(current, ChainSync.Role.Client, message, codec, settings.limits)
              .fold(illegal(_), bytes => channel.send(bytes))
              .productR(next.fold(illegal(_), state.set))
          }

        /** The next server message in protocol state `current`; the state is not advanced. */
        def receiveAt(current: State)(using Raise[F, NetworkError]): F[Message[P]] = {
          val limit = current match
            case State.Intersect     => F.pure(settings.intersectTimeout)
            case State.NextCanAwait  => F.pure(settings.canAwaitTimeout)
            case State.NextMustReply => F.pure(settings.mustReplyTimeout)
            case other               => illegal[FiniteDuration](RuntimeViolation.ReplyNotExpected(other))
          limit.flatMap { wait =>
            Timed
              .within(wait, id, current.toString)(
                channel.recv(settings.limits.maxMessageBytes)(
                  ChainSync.decodePrefix(current, ChainSync.Role.Server, _, codec, settings.limits)
                )
              )
          }
        }

        def advance(current: State, message: Message[P])(using Raise[F, NetworkError]): F[State] =
          ChainSync.transition(current, ChainSync.Role.Server, message).fold(illegal(_), F.pure)

        /** Note what the peer last said: `AwaitReply` makes it idling, anything else does not. */
        def noted(message: Message[P]): F[Unit] = message match
          case Message.AwaitReply => awaiting.set(true)
          case _                  => awaiting.set(false)

        def receive(using Raise[F, NetworkError]): F[Message[P]] =
          state.get.flatMap(current =>
            receiveAt(current).flatMap(m =>
              advance(current, m).flatMap(state.set) *> noted(m).as(m)
            )
          )

        /** An operation that may leave requests outstanding. A failure or a cancellation leaves the
          * client busy for good: the conversation is in a state only the connection's end can
          * clear.
          */
        def flight[A](operation: F[A]): F[A] = busy.set(true) *> operation <* busy.set(false)

        new ChainSyncClient[F, P]:
          def findIntersect(points: Vector[Point])(using Raise[F, NetworkError]) =
            flight(findIntersect_(points))

          private def findIntersect_(points: Vector[Point])(using
              Raise[F, NetworkError]
          ): F[Option[(Point, Tip)]] =
            send(Message.FindIntersect(points)) *> receive.flatMap {
              case Message.IntersectFound(point, tip) if points.contains(point) =>
                F.pure(Some(point -> tip))
              case Message.IntersectFound(_, _) => illegal(RuntimeViolation.IntersectionNotOffered)
              case Message.IntersectNotFound(_) => F.pure(None)
              case other                        => illegal(RuntimeViolation.ChainSyncUnexpected(other))
            }

          def requestNext(using Raise[F, NetworkError]): F[Update[P]] = flight(requestNext_)

          private def requestNext_(using Raise[F, NetworkError]): F[Update[P]] =
            send(Message.RequestNext) *> collect

          def requestNextBatch(n: Int)(using Raise[F, NetworkError]): F[Vector[Update[P]]] =
            flight(requestNextBatch_(n))

          private def requestNextBatch_(n: Int)(using
              Raise[F, NetworkError]
          ): F[Vector[Update[P]]] =
            if n <= 1 then requestNext_.map(Vector(_))
            else
              state.get.flatMap {
                case State.Idle =>
                  ChainSync
                    .encode(
                      State.Idle,
                      ChainSync.Role.Client,
                      Message.RequestNext,
                      codec,
                      settings.limits
                    )
                    .fold(illegal(_), bytes => List.fill(n)(bytes).traverse_(channel.send))
                    .productR(List.fill(n)(()).traverse(_ => collectOne))
                    .map(_.toVector)
                case other => illegal(RuntimeViolation.PipelinedRequest(other))
              }

          // One reply to an outstanding request: waits out AwaitReply; the state returns to Idle.
          private def collectOne(using Raise[F, NetworkError]): F[Update[P]] =
            def go(current: State)(using Raise[F, NetworkError]): F[Update[P]] =
              receiveAt(current).flatMap { message =>
                noted(message) *> advance(current, message).flatMap { next =>
                  message match
                    case Message.AwaitReply => go(next)
                    case Message.RollForward(payload, tip) =>
                      F.pure(Update.RollForward(payload, tip))
                    case Message.RollBackward(point, tip) => F.pure(Update.RollBackward(point, tip))
                    case other                            => illegal(RuntimeViolation.ChainSyncUnexpected(other))
                }
              }
            go(State.NextCanAwait)

          private def collect(using Raise[F, NetworkError]): F[Update[P]] =
            receive.flatMap {
              case Message.AwaitReply                => collect
              case Message.RollForward(payload, tip) => F.pure(Update.RollForward(payload, tip))
              case Message.RollBackward(point, tip)  => F.pure(Update.RollBackward(point, tip))
              case other                             => illegal(RuntimeViolation.ChainSyncUnexpected(other))
            }

          def done(using Raise[F, NetworkError]) = send(Message.Done)

          def idle = (state.get, busy.get).mapN((s, b) => s == State.Idle && !b)

          def idling = awaiting.get
    }
