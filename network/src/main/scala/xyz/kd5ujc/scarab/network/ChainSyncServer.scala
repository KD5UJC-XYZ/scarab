// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import NetworkError.RuntimeViolation

import cats.effect.Temporal
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import xyz.kd5ujc.scarab.protocol.ChainSync
import xyz.kd5ujc.scarab.protocol.ChainSync.{Message, Point, State, Tip}
import scala.concurrent.duration.*

/** ChainSync server for one client. The chain is reached through a [[ChainSyncServer.Source]] that
  * holds this client's read pointer, so the protocol driver knows nothing about storage. Requests
  * are answered strictly in order, which also serves clients that pipeline.
  */
object ChainSyncServer:
  enum Update[+P]:
    case RollForward(payload: P, tip: Tip)
    case RollBackward(point: Point, tip: Tip)

  /** One client's cursor over our chain. */
  trait Source[F[_], P]:
    /** Move the cursor to the newest of `points` that is on our chain, or report our tip. */
    def intersect(points: Vector[Point]): F[Either[Tip, (Point, Tip)]]

    /** The next instruction if the cursor is behind the tip (or the chain forked away from it). */
    def poll: F[Option[Update[P]]]

    /** The next instruction, waiting for the chain to change; cancelable. */
    def await: F[Update[P]]

  /** `idleTimeout` is the upstream bound on a client that sends nothing (3373 s). */
  final case class Settings(
      limits: ChainSync.Limits = ChainSync.Limits(),
      idleTimeout: FiniteDuration = 3373.seconds,
      /** The mini-protocol number, for error messages: 2 on node-to-node, 5 on node-to-client. */
      protocol: Int = Protocols.ChainSync
  )

  /** The node-to-client profile: whole blocks, so the message bound is that of a mux message. */
  val NodeToClient: Settings =
    Settings(ChainSync.Limits.NodeToClient, protocol = Protocols.LocalChainSync)

  def run[F[_]: Temporal, P](
      channel: MuxChannel[F],
      codec: ChainSync.PayloadCodec[P],
      source: Source[F, P],
      settings: Settings = Settings()
  )(using Raise[F, NetworkError]): F[Unit] =
    val F = Temporal[F]
    val id = settings.protocol
    def illegal[A](detail: NetworkError.Violation): F[A] = NetworkError.IllegalTransition(id, detail).raise[F, A]

    def send(state: State, message: Message[P]): F[State] =
      ChainSync
        .encode(state, ChainSync.Role.Server, message, codec, settings.limits)
        .fold(illegal, channel.send)
        .productR(
          ChainSync.transition(state, ChainSync.Role.Server, message).fold(illegal, F.pure)
        )

    def instruction(update: Update[P]): Message[P] = update match
      case Update.RollForward(p, tip)   => Message.RollForward(p, tip)
      case Update.RollBackward(pt, tip) => Message.RollBackward(pt, tip)

    val step: F[Boolean] =
      Timed
        .within(settings.idleTimeout, id, "idle")(
          channel.recv(settings.limits.maxMessageBytes)(
            ChainSync.decodePrefix(State.Idle, ChainSync.Role.Client, _, codec, settings.limits)
          )
        )
        .flatMap {
          case Message.FindIntersect(points) =>
            source.intersect(points).flatMap {
              case Right((point, tip)) =>
                send(State.Intersect, Message.IntersectFound(point, tip)).as(true)
              case Left(tip) => send(State.Intersect, Message.IntersectNotFound(tip)).as(true)
            }
          case Message.RequestNext =>
            source.poll.flatMap {
              case Some(update) => send(State.NextCanAwait, instruction(update)).as(true)
              case None =>
                send(State.NextCanAwait, Message.AwaitReply).flatMap(waiting =>
                  source.await.flatMap(update => send(waiting, instruction(update))).as(true)
                )
            }
          case Message.Done => F.pure(false)
          case other        => illegal(RuntimeViolation.ChainSyncUnexpected(other))
        }
    step.iterateWhile(identity).void
