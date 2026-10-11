// SPDX-License-Identifier: Apache-2.0
package lab.network.runtime

import cats.effect.{Ref, Temporal}
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import lab.network.ChainSync
import lab.network.ChainSync.{Message, Point, State, Tip}
import scala.concurrent.duration.*

/** Non-pipelined ChainSync client. Every message is checked against the pure state machine. Use
  * from a single fiber.
  */
trait ChainSyncClient[F[_], P]:
  /** The newest of `points` the peer also has, with the peer's tip; `None` if there is none. */
  def findIntersect(points: Vector[Point]): F[Option[(Point, Tip)]]

  /** The next instruction. An `AwaitReply` is waited out rather than reported. */
  def requestNext: F[ChainSyncClient.Update[P]]

  /** Tell the peer we are finished; the protocol ends. */
  def done: F[Unit]

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
  )(using Raise[F, NetworkError]): F[ChainSyncClient[F, P]] =
    val F = Temporal[F]
    val id = Protocols.ChainSync
    def illegal[A](detail: String): F[A] = NetworkError.IllegalTransition(id, detail).raise[F, A]

    Ref.of[F, State](State.Idle).map { state =>
      def send(message: Message[P]): F[Unit] =
        state.get.flatMap { current =>
          val next = ChainSync.transition(current, ChainSync.Role.Client, message)
          ChainSync
            .encode(current, ChainSync.Role.Client, message, codec, settings.limits)
            .fold(illegal, bytes => channel.send(bytes))
            .productR(next.fold(illegal, state.set))
        }

      def receive: F[Message[P]] =
        state.get.flatMap { current =>
          val limit = current match
            case State.Intersect     => F.pure(settings.intersectTimeout)
            case State.NextCanAwait  => F.pure(settings.canAwaitTimeout)
            case State.NextMustReply => F.pure(settings.mustReplyTimeout)
            case other               => illegal[FiniteDuration](s"no reply is expected in $other")
          limit.flatMap { wait =>
            Timed
              .within(wait, id, current.toString)(
                channel.recv(settings.limits.maxMessageBytes)(
                  ChainSync.decodePrefix(current, ChainSync.Role.Server, _, codec, settings.limits)
                )
              )
              .flatMap(message =>
                ChainSync
                  .transition(current, ChainSync.Role.Server, message)
                  .fold(illegal, state.set)
                  .as(message)
              )
          }
        }

      new ChainSyncClient[F, P]:
        def findIntersect(points: Vector[Point]) =
          send(Message.FindIntersect(points)) *> receive.flatMap {
            case Message.IntersectFound(point, tip) if points.contains(point) =>
              F.pure(Some(point -> tip))
            case Message.IntersectFound(_, _) => illegal("intersection was not offered")
            case Message.IntersectNotFound(_) => F.pure(None)
            case other                        => illegal(s"unexpected intersect reply $other")
          }

        def requestNext: F[Update[P]] =
          send(Message.RequestNext) *> collect

        private def collect: F[Update[P]] =
          receive.flatMap {
            case Message.AwaitReply                => collect
            case Message.RollForward(payload, tip) => F.pure(Update.RollForward(payload, tip))
            case Message.RollBackward(point, tip)  => F.pure(Update.RollBackward(point, tip))
            case other                             => illegal(s"unexpected reply $other")
          }

        def done = send(Message.Done)
    }
