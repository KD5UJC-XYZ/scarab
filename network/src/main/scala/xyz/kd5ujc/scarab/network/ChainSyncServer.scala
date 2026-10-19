// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.Temporal
import cats.mtl.Raise
import cats.syntax.all.*
import xyz.kd5ujc.scarab.protocol.ChainSync
import xyz.kd5ujc.scarab.protocol.ChainSync.{Point, Tip}
import scala.concurrent.duration.*

/** Strict Cardano ChainSync server facade over the generic typed runtime. */
object ChainSyncServer:
  enum Update[+P]:
    case RollForward(payload: P, tip: Tip)
    case RollBackward(point: Point, tip: Tip)

  trait Source[F[_], P]:
    def intersect(points: Vector[Point]): F[Either[Tip, (Point, Tip)]]
    def poll: F[Option[Update[P]]]
    def await: F[Update[P]]

  final case class Settings(
      limits: ChainSync.Limits = ChainSync.Limits(),
      idleTimeout: FiniteDuration = 3373.seconds,
      protocol: Int = Protocols.ChainSync
  )

  val NodeToClient: Settings =
    Settings(ChainSync.Limits.NodeToClient, protocol = Protocols.LocalChainSync)

  def run[F[_]: Temporal, P](
      channel: MuxChannel[F],
      codec: ChainSync.PayloadCodec[P],
      source: Source[F, P],
      settings: Settings = Settings()
  )(using Raise[F, NetworkError]): F[Unit] =
    val genericSource = new ChainSyncRuntime.Source[F, Point, Tip, P]:
      def intersect(points: Vector[Point]) = source.intersect(points)
      def poll = source.poll.map(_.map {
        case Update.RollForward(payload, tip) =>
          ChainSyncRuntime.Update.RollForward(payload, tip)
        case Update.RollBackward(point, tip) =>
          ChainSyncRuntime.Update.RollBackward(point, tip)
      })
      def await = source.await.map {
        case Update.RollForward(payload, tip) =>
          ChainSyncRuntime.Update.RollForward(payload, tip)
        case Update.RollBackward(point, tip) =>
          ChainSyncRuntime.Update.RollBackward(point, tip)
      }

    ChainSyncRuntime.run(
      channel,
      ChainSync.enginePointCodec,
      ChainSync.engineTipCodec,
      ChainSync.enginePayloadCodec(codec),
      genericSource,
      ChainSyncRuntime.ServerSettings(settings.limits, settings.idleTimeout, settings.protocol)
    )
