// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import NetworkError.RuntimeViolation

import cats.effect.{Ref, Temporal}
import cats.effect.std.Mutex
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.{BlockFetch, CardanoBlockFetch}
import xyz.kd5ujc.scarab.protocol.ChainSync.Point
import scala.concurrent.duration.*

/** Non-pipelined BlockFetch client: one range request at a time. */
trait BlockFetchClient[F[_]]:
  /** Raw block bytes for the inclusive range, or `None` when the peer does not have them. */
  def requestRange(from: Point, to: Point)(using Raise[F, NetworkError]): F[Option[Vector[Bytes]]]

  /** Tell the peer we are finished; valid only when no request is outstanding. */
  def done(using Raise[F, NetworkError]): F[Unit]

  /** Whether no request is outstanding, so `done` is valid. */
  def idle: F[Boolean]

object BlockFetchClient:
  final case class Settings(
      limits: BlockFetch.Limits = BlockFetch.Limits(),
      timeLimits: BlockFetch.TimeLimits = BlockFetch.TimeLimits(),
      request: BlockFetch.RequestLimits = BlockFetch.RequestLimits(maxBlocks = 128)
  )

  def apply[F[_]: Temporal](
      channel: MuxChannel[F],
      settings: Settings = Settings()
  ): F[BlockFetchClient[F]] =
    val F = Temporal[F]
    val id = Protocols.BlockFetch
    val codec = CardanoBlockFetch.payloadCodec()
    type Msg = BlockFetch.Message[CardanoBlockFetch.RawNtNBlock]
    def illegal[A](detail: NetworkError.Violation)(using Raise[F, NetworkError]): F[A] =
      NetworkError.IllegalTransition(id, detail).raise[F, A]

    (Ref.of[F, BlockFetch.State](BlockFetch.State.Idle), Mutex[F]).mapN { (state, lock) =>
      def send(message: BlockFetch.Message[Nothing])(using Raise[F, NetworkError]): F[Unit] =
        state.get.flatMap { current =>
          BlockFetch
            .encode(current, BlockFetch.Role.Client, message, codec, settings.limits)
            .fold(illegal(_), bytes => channel.send(bytes))
            .productR(
              BlockFetch
                .transition(current, BlockFetch.Role.Client, message)
                .fold(illegal(_), state.set)
            )
        }

      def receive(using Raise[F, NetworkError]): F[Msg] =
        state.get.flatMap { current =>
          val wait = current match
            case BlockFetch.State.Busy      => F.pure(settings.timeLimits.busy)
            case BlockFetch.State.Streaming => F.pure(settings.timeLimits.streaming)
            case other => illegal[FiniteDuration](RuntimeViolation.ReplyNotExpected(other))
          wait.flatMap { limit =>
            Timed
              .within(limit, id, current.toString)(
                channel.recv(settings.limits.messageBytes(current))(
                  BlockFetch
                    .decodePrefix(current, BlockFetch.Role.Server, _, codec, settings.limits)
                )
              )
              .flatMap(message =>
                BlockFetch
                  .transition(current, BlockFetch.Role.Server, message)
                  .fold(illegal(_), state.set)
                  .as(message)
              )
          }
        }

      def stream(received: BlockFetch.Received, blocks: Vector[Bytes])(using
          Raise[F, NetworkError]
      ): F[Vector[Bytes]] =
        receive.flatMap {
          case BlockFetch.Message.Block(block) =>
            BlockFetch.Received.add(received, block.bytes.size, settings.request) match
              case Left(_) =>
                NetworkError.IngressLimit(id, blocks.size + 1).raise[F, Vector[Bytes]]
              case Right(next) => stream(next, blocks :+ block.bytes)
          case BlockFetch.Message.BatchDone => F.pure(blocks)
          case other                        => illegal(RuntimeViolation.BlockFetchUnexpected(other))
        }

      new BlockFetchClient[F]:
        def done(using Raise[F, NetworkError]): F[Unit] =
          lock.lock.surround(send(BlockFetch.Message.ClientDone))

        def idle: F[Boolean] = state.get.map(_ == BlockFetch.State.Idle)

        def requestRange(from: Point, to: Point)(using
            Raise[F, NetworkError]
        ): F[Option[Vector[Bytes]]] =
          lock.lock.surround {
            CardanoBlockFetch.InclusiveRange.fromPoints(from, to).fold(illegal(_), F.pure).flatMap {
              range =>
                Timed.within(settings.timeLimits.wholeRequest, id, "request")(
                  send(range.request) *> receive.flatMap {
                    case BlockFetch.Message.NoBlocks => F.pure(None)
                    case BlockFetch.Message.StartBatch =>
                      stream(BlockFetch.Received.Empty, Vector.empty).map(Some(_))
                    case other => illegal(RuntimeViolation.BlockFetchUnexpected(other))
                  }
                )
            }
          }
    }
