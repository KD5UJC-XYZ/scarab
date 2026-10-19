// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.Temporal
import cats.mtl.Raise
import cats.syntax.all.*
import xyz.kd5ujc.scarab.protocol.ChainSync
import xyz.kd5ujc.scarab.protocol.ChainSync.{Point, Tip}
import scala.concurrent.duration.*

/** Strict Cardano ChainSync client facade over the generic typed runtime. Use from a single fiber.
  */
trait ChainSyncClient[F[_], P]:
  def findIntersect(points: Vector[Point])(using Raise[F, NetworkError]): F[Option[(Point, Tip)]]
  def requestNext(using Raise[F, NetworkError]): F[ChainSyncClient.Update[P]]
  def requestNextBatch(n: Int)(using Raise[F, NetworkError]): F[Vector[ChainSyncClient.Update[P]]]
  def done(using Raise[F, NetworkError]): F[Unit]
  def idle: F[Boolean]
  def idling: F[Boolean]

object ChainSyncClient:
  enum Update[+P]:
    case RollForward(payload: P, tip: Tip)
    case RollBackward(point: Point, tip: Tip)

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
    ChainSyncRuntime
      .client(
        channel,
        ChainSync.enginePointCodec,
        ChainSync.engineTipCodec,
        ChainSync.enginePayloadCodec(codec),
        ChainSyncRuntime.ClientSettings(
          settings.limits,
          settings.intersectTimeout,
          settings.canAwaitTimeout,
          settings.mustReplyTimeout
        )
      )
      .map { runtime =>
        new ChainSyncClient[F, P]:
          def findIntersect(points: Vector[Point])(using Raise[F, NetworkError]) =
            runtime.findIntersect(points)

          def requestNext(using Raise[F, NetworkError]): F[Update[P]] =
            runtime.requestNext.map {
              case ChainSyncRuntime.Update.RollForward(payload, tip) =>
                Update.RollForward(payload, tip)
              case ChainSyncRuntime.Update.RollBackward(point, tip) =>
                Update.RollBackward(point, tip)
            }

          def requestNextBatch(n: Int)(using
              Raise[F, NetworkError]
          ): F[Vector[Update[P]]] =
            runtime
              .requestNextBatch(n)
              .map(_.map {
                case ChainSyncRuntime.Update.RollForward(payload, tip) =>
                  Update.RollForward(payload, tip)
                case ChainSyncRuntime.Update.RollBackward(point, tip) =>
                  Update.RollBackward(point, tip)
              })

          def done(using Raise[F, NetworkError]): F[Unit] = runtime.done
          def idle: F[Boolean] = runtime.idle
          def idling: F[Boolean] = runtime.idling
      }
