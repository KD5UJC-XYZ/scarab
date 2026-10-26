// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.Temporal
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import fs2.Stream
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.{BlockFetch, CardanoBlockFetch}
import xyz.kd5ujc.scarab.protocol.ChainSync.Point
import scala.concurrent.duration.*

/** BlockFetch server: answers inclusive range requests from a [[BlockFetchServer.Blocks]] source,
  * one request at a time (a client that pipelines is served in order).
  */
object BlockFetchServer:
  trait Blocks[F[_]]:
    /** The blocks of the inclusive range on our chain, oldest first, or `None` when the range is
      * not one contiguous stretch of our chain (the client then gets `NoBlocks`). The length of a
      * range is not bounded: the reference server streams any range it holds
      * (`MiniProtocol/BlockFetch/Server.hs`, `blockFetchServer'`, ouroboros-consensus 82ecba32).
      */
    def range(from: Point, to: Point): F[Option[Stream[F, Bytes]]]

  final case class Settings(
      limits: BlockFetch.Limits = BlockFetch.Limits(),
      idleTimeout: FiniteDuration = 3373.seconds,
      idleDeadline: Option[ProtocolDeadline] = None
  )

  def run[F[_]: Temporal](
      channel: MuxChannel[F],
      blocks: Blocks[F],
      settings: Settings = Settings()
  )(using Raise[F, NetworkError]): F[Unit] =
    val F = Temporal[F]
    val id = Protocols.BlockFetch
    val codec = CardanoBlockFetch.payloadCodec()
    val genericBlocks = new BlockFetchRuntime.Blocks[F, Point, CardanoBlockFetch.RawNtNBlock]:
      def range(
          from: Point,
          to: Point
      ): F[Option[Stream[F, CardanoBlockFetch.RawNtNBlock]]] =
        blocks
          .range(from, to)
          .map(
            _.map(
              _.evalMap(raw =>
                CardanoBlockFetch.RawNtNBlock
                  .from(raw)
                  .fold(
                    error =>
                      NetworkError
                        .IllegalTransition(id, error)
                        .raise[F, CardanoBlockFetch.RawNtNBlock],
                    F.pure
                  )
              )
            )
          )

    BlockFetchRuntime.run(
      channel,
      genericBlocks,
      CardanoBlockFetch.pointCodec,
      codec,
      (from, to) =>
        CardanoBlockFetch.InclusiveRange
          .fromPoints(from, to)
          .map(range => (range.from.point, range.to.point)),
      BlockFetchRuntime.ServerSettings(
        settings.limits,
        settings.idleTimeout,
        settings.idleDeadline
      )
    )
