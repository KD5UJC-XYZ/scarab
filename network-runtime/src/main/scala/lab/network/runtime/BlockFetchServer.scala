// SPDX-License-Identifier: Apache-2.0
package lab.network.runtime

import cats.effect.Temporal
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import fs2.Stream
import lab.cbor.Bytes
import lab.network.{BlockFetch, CardanoBlockFetch}
import lab.network.ChainSync.Point
import scala.concurrent.duration.*

/** BlockFetch server: answers inclusive range requests from a [[BlockFetchServer.Blocks]] source,
  * one request at a time (a client that pipelines is served in order).
  */
object BlockFetchServer:
  trait Blocks[F[_]]:
    /** The blocks of the inclusive range on our chain, oldest first, or `None` when the range is
      * not one contiguous stretch of our chain or has more than `maxBlocks` blocks (the client then
      * gets `NoBlocks`).
      */
    def range(from: Point, to: Point, maxBlocks: Int): F[Option[Stream[F, Bytes]]]

  final case class Settings(
      limits: BlockFetch.Limits = BlockFetch.Limits(),
      /** Ranges longer than this are refused with `NoBlocks`. */
      maxBlocks: Int = 2160,
      idleTimeout: FiniteDuration = 3373.seconds
  )

  def run[F[_]: Temporal](
      channel: MuxChannel[F],
      blocks: Blocks[F],
      settings: Settings = Settings()
  )(using Raise[F, NetworkError]): F[Unit] =
    val F = Temporal[F]
    val id = Protocols.BlockFetch
    val codec = CardanoBlockFetch.payloadCodec()
    type Msg = BlockFetch.Message[CardanoBlockFetch.RawNtNBlock]
    def illegal[A](detail: String): F[A] = NetworkError.IllegalTransition(id, detail).raise[F, A]

    def send(state: BlockFetch.State, message: Msg): F[Unit] =
      BlockFetch
        .encode(state, BlockFetch.Role.Server, message, codec, settings.limits)
        .fold(illegal, channel.send)

    def stream(source: Stream[F, Bytes]): F[Unit] =
      source
        .evalMap(raw =>
          CardanoBlockFetch.RawNtNBlock
            .from(raw)
            .fold(
              illegal[Unit],
              block => send(BlockFetch.State.Streaming, BlockFetch.Message.Block(block))
            )
        )
        .compile
        .drain

    val step: F[Boolean] =
      Timed
        .within(settings.idleTimeout, id, "idle")(
          channel.recv(settings.limits.messageBytes(BlockFetch.State.Idle))(
            BlockFetch
              .decodePrefix(
                BlockFetch.State.Idle,
                BlockFetch.Role.Client,
                _,
                codec,
                settings.limits
              )
          )
        )
        .flatMap {
          case BlockFetch.Message.ClientDone => F.pure(false)
          case request @ BlockFetch.Message.RequestRange(_, _) =>
            CardanoBlockFetch.checkedRange(request).fold(illegal, F.pure).flatMap { range =>
              blocks.range(range.from.point, range.to.point, settings.maxBlocks).flatMap {
                case None => send(BlockFetch.State.Busy, BlockFetch.Message.NoBlocks).as(true)
                case Some(source) =>
                  send(BlockFetch.State.Busy, BlockFetch.Message.StartBatch) *>
                    stream(source) *>
                    send(BlockFetch.State.Streaming, BlockFetch.Message.BatchDone).as(true)
              }
            }
          case other => illegal(s"unexpected client message $other")
        }
    step.iterateWhile(identity).void
