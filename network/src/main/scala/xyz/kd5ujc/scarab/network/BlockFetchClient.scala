// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.Temporal
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.{BlockFetch, CardanoBlockFetch}
import xyz.kd5ujc.scarab.protocol.ChainSync.Point

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
    val id = Protocols.BlockFetch
    val codec = CardanoBlockFetch.payloadCodec()
    val runtimeSettings = BlockFetchRuntime.ClientSettings(
      settings.limits,
      settings.timeLimits,
      settings.request
    )
    BlockFetchRuntime
      .client(channel, CardanoBlockFetch.pointCodec, codec, _.bytes.size, runtimeSettings)
      .map { runtime =>
        def illegal[A](detail: NetworkError.Violation)(using Raise[F, NetworkError]): F[A] =
          NetworkError.IllegalTransition(id, detail).raise[F, A]

        new BlockFetchClient[F]:
          def done(using Raise[F, NetworkError]): F[Unit] = runtime.done

          def idle: F[Boolean] = runtime.idle

          def requestRange(from: Point, to: Point)(using
              Raise[F, NetworkError]
          ): F[Option[Vector[Bytes]]] =
            CardanoBlockFetch.InclusiveRange
              .fromPoints(from, to)
              .fold(
                illegal(_),
                { range =>
                  runtime
                    .requestRange(range.from.point, range.to.point)
                    .map(
                      _.map(_.map(_.bytes))
                    )
                }
              )
      }
