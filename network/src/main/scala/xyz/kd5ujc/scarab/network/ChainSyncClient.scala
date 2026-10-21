// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.Applicative
import cats.effect.{Resource, Temporal}
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
  def requestNextBatchObserved(n: Int)(onUpdate: ChainSyncClient.Update[P] => F[Unit])(using
      Raise[F, NetworkError],
      cats.Monad[F]
  ): F[Vector[ChainSyncClient.Update[P]]] =
    requestNextBatch(n).flatTap(_.traverse_(onUpdate))
  def done(using Raise[F, NetworkError]): F[Unit]
  def idle: F[Boolean]
  def idling: F[Boolean]

  /** Observe AwaitReply immediately inside the collector, including a partially collected batch. */
  def observe(observer: ChainSyncClient.Observer[F])(using Applicative[F]): Resource[F, Unit] =
    Resource.pure[F, ChainSyncClient.Observer[F]](observer).void

object ChainSyncClient:
  trait Observer[F[_]]:
    def awaitReply: F[Unit]
    def reply: F[Unit]

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

          override def requestNextBatchObserved(n: Int)(onUpdate: Update[P] => F[Unit])(using
              Raise[F, NetworkError],
              cats.Monad[F]
          ): F[Vector[Update[P]]] =
            def translated(update: ChainSyncRuntime.Update[Point, Tip, P]): Update[P] = update match
              case ChainSyncRuntime.Update.RollForward(payload, tip) =>
                Update.RollForward(payload, tip)
              case ChainSyncRuntime.Update.RollBackward(point, tip) =>
                Update.RollBackward(point, tip)
            runtime.requestNextBatchObserved(n)(u => onUpdate(translated(u))).map(_.map(translated))

          def done(using Raise[F, NetworkError]): F[Unit] = runtime.done
          def idle: F[Boolean] = runtime.idle
          def idling: F[Boolean] = runtime.idling
          override def observe(observer: Observer[F])(using Applicative[F]): Resource[F, Unit] =
            runtime.observe(observer)
      }
