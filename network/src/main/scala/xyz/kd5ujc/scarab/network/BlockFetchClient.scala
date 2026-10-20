// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{Async, Resource, Temporal}
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.{BlockFetch, CardanoBlockFetch}
import xyz.kd5ujc.scarab.protocol.ChainSync.Point

/** BlockFetch ranges; production resources submit concurrent calls as a bounded FIFO pipeline. */
trait BlockFetchClient[F[_]]:
  /** Raw block bytes for the inclusive range, or `None` when the peer does not have them. */
  def requestRange(from: Point, to: Point)(using Raise[F, NetworkError]): F[Option[Vector[Bytes]]]

  /** Test interpreters can ignore transport observations; production reports each block promptly.
    */
  def requestRangeObserved(from: Point, to: Point)(
      @scala.annotation.unused observe: BlockFetchRuntime.Event => F[Unit]
  )(using Raise[F, NetworkError]): F[Option[Vector[Bytes]]] = requestRange(from, to)

  /** Tell the peer we are finished; valid only when no request is outstanding. */
  def done(using Raise[F, NetworkError]): F[Unit]

  /** Whether no request is outstanding, so `done` is valid. */
  def idle: F[Boolean]

object BlockFetchClient:
  final case class Settings(
      limits: BlockFetch.Limits = BlockFetch.Limits(),
      timeLimits: BlockFetch.TimeLimits = BlockFetch.TimeLimits(),
      request: BlockFetch.RequestLimits = BlockFetch.RequestLimits(maxBlocks = 128),
      maxInFlightRequests: Int = 100
  )

  def apply[F[_]: Temporal](
      channel: MuxChannel[F],
      settings: Settings = Settings()
  ): F[BlockFetchClient[F]] =
    val codec = CardanoBlockFetch.payloadCodec()
    val runtimeSettings = BlockFetchRuntime.ClientSettings(
      settings.limits,
      settings.timeLimits,
      settings.request
    )
    BlockFetchRuntime
      .client(channel, CardanoBlockFetch.pointCodec, codec, _.bytes.size, runtimeSettings)
      .map(adapt(_))

  def resource[F[_]: Async](
      channel: MuxChannel[F],
      abort: NetworkError => F[Unit],
      settings: Settings = Settings()
  )(using Raise[F, NetworkError]): Resource[F, BlockFetchClient[F]] =
    BlockFetchRuntime
      .resource(
        channel,
        CardanoBlockFetch.pointCodec,
        CardanoBlockFetch.payloadCodec(),
        _.bytes.size,
        abort,
        BlockFetchRuntime.ClientSettings(
          settings.limits,
          settings.timeLimits,
          settings.request,
          settings.maxInFlightRequests
        )
      )
      .map(adapt(_))

  private def adapt[F[_]: cats.Monad](
      runtime: BlockFetchRuntime.Client[F, Point, CardanoBlockFetch.RawNtNBlock]
  ): BlockFetchClient[F] =
    def range(from: Point, to: Point)(using
        Raise[F, NetworkError]
    ): F[CardanoBlockFetch.InclusiveRange] =
      CardanoBlockFetch.InclusiveRange
        .fromPoints(from, to)
        .fold(
          e => NetworkError.IllegalTransition(Protocols.BlockFetch, e).raise,
          _.pure[F]
        )
    new BlockFetchClient[F]:
      def done(using Raise[F, NetworkError]): F[Unit] = runtime.done
      def idle: F[Boolean] = runtime.idle
      def requestRange(from: Point, to: Point)(using
          Raise[F, NetworkError]
      ): F[Option[Vector[Bytes]]] =
        range(from, to)
          .flatMap(r => runtime.requestRange(r.from.point, r.to.point))
          .map(_.map(_.map(_.bytes)))
      override def requestRangeObserved(from: Point, to: Point)(
          observe: BlockFetchRuntime.Event => F[Unit]
      )(using Raise[F, NetworkError]): F[Option[Vector[Bytes]]] =
        range(from, to)
          .flatMap(r => runtime.requestRangeObserved(r.from.point, r.to.point)(observe))
          .map(_.map(_.map(_.bytes)))
