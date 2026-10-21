// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.Applicative
import cats.effect.{Async, Resource}
import cats.mtl.Raise
import cats.syntax.all.*
import xyz.kd5ujc.scarab.protocol.ChainSync
import xyz.kd5ujc.scarab.protocol.ChainSync.{Point, Tip}
import scala.concurrent.duration.*

/** Strict Cardano ChainSync client facade over the generic typed runtime. Use from a single fiber.
  */
trait ChainSyncClient[F[_], P]:
  def findIntersectResult(points: Vector[Point])(using
      Raise[F, NetworkError]
  ): F[ChainSyncClient.Intersection]
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
  type Intersection = ChainSyncRuntime.Intersection[Point, Tip]

  /** A receiver-owned service. Its operation takes the receiver's fresh capability rather than
    * retaining a caller's error scope. Capture immutable request context before submission.
    */
  trait ReceiverHook[F[_]]:
    def awaitReply(using Raise[F, NetworkError]): F[Unit]

  trait Pipeline[F[_], P] extends ChainSyncClient[F, P]:
    def submit(using Raise[F, NetworkError]): F[Unit]
    def submitObserved(hook: ReceiverHook[F])(using Raise[F, NetworkError]): F[Unit]
    def tryCollect(onAwait: F[Unit])(using Raise[F, NetworkError]): F[Option[Update[P]]]
    def collect(onAwait: F[Unit])(using Raise[F, NetworkError]): F[Update[P]]
    def serviceAwait(onAwait: F[Unit])(using Raise[F, NetworkError]): F[Unit]
    def outstanding: F[Int]
    def drain(onAwait: F[Unit])(onUpdate: Update[P] => F[Unit])(using
        Raise[F, NetworkError]
    ): F[Unit]

  def resource[F[_]: Async, P](
      channel: MuxChannel[F],
      codec: ChainSync.PayloadCodec[P],
      abort: NetworkError => F[Unit],
      settings: Settings = Settings(),
      maxOutstanding: Int = 300
  )(using Raise[F, NetworkError]): Resource[F, Pipeline[F, P]] =
    ChainSyncRuntime
      .resource(
        channel,
        ChainSync.enginePointCodec,
        ChainSync.engineTipCodec,
        ChainSync.enginePayloadCodec(codec),
        abort,
        ChainSyncRuntime.ClientSettings(
          settings.limits,
          settings.intersectTimeout,
          settings.canAwaitTimeout,
          settings.mustReply,
          settings.timeouts
        ),
        maxOutstanding
      )
      .map { runtime =>
        def translated(update: ChainSyncRuntime.Update[Point, Tip, P]): Update[P] = update match
          case ChainSyncRuntime.Update.RollForward(value, tip)  => Update.RollForward(value, tip)
          case ChainSyncRuntime.Update.RollBackward(point, tip) => Update.RollBackward(point, tip)
        new Pipeline[F, P]:
          def submit(using Raise[F, NetworkError]): F[Unit] = runtime.submit
          def submitObserved(hook: ReceiverHook[F])(using Raise[F, NetworkError]): F[Unit] =
            runtime.submitObserved(hook)
          def outstanding: F[Int] = runtime.outstanding
          def idle: F[Boolean] = runtime.idle
          def idling: F[Boolean] = runtime.idling
          def serviceAwait(onAwait: F[Unit])(using Raise[F, NetworkError]): F[Unit] =
            runtime.serviceAwait(onAwait)
          def tryCollect(onAwait: F[Unit])(using Raise[F, NetworkError]): F[Option[Update[P]]] =
            runtime.tryCollect(onAwait).map(_.map(translated))
          def collect(onAwait: F[Unit])(using Raise[F, NetworkError]): F[Update[P]] =
            runtime.collect(onAwait).map(translated)
          def drain(onAwait: F[Unit])(onUpdate: Update[P] => F[Unit])(using
              Raise[F, NetworkError]
          ): F[Unit] =
            runtime.drain(onAwait)(u => onUpdate(translated(u)))
          def findIntersectResult(points: Vector[Point])(using
              Raise[F, NetworkError]
          ): F[Intersection] =
            runtime.findIntersectResult(points)
          def findIntersect(points: Vector[Point])(using
              Raise[F, NetworkError]
          ): F[Option[(Point, Tip)]] =
            runtime.findIntersect(points)
          def done(using Raise[F, NetworkError]): F[Unit] = runtime.done
          def requestNext(using Raise[F, NetworkError]): F[Update[P]] =
            runtime.requestNext.map(translated)
          def requestNextBatch(n: Int)(using Raise[F, NetworkError]): F[Vector[Update[P]]] =
            runtime.requestNextBatch(n).map(_.map(translated))
          override def requestNextBatchObserved(n: Int)(onUpdate: Update[P] => F[Unit])(using
              Raise[F, NetworkError],
              cats.Monad[F]
          ): F[Vector[Update[P]]] =
            runtime.requestNextBatchObserved(n)(u => onUpdate(translated(u))).map(_.map(translated))
          override def observe(observer: Observer[F])(using Applicative[F]): Resource[F, Unit] =
            runtime.observe(observer)
      }

  trait Observer[F[_]]:
    /** Freeze request-specific context before submission; callbacks still run in the collector. */
    def capture(using F: Applicative[F]): F[Observer[F]] = F.pure(this)
    def awaitReply: F[Unit]
    def reply: F[Unit]

  enum Update[+P]:
    case RollForward(payload: P, tip: Tip)
    case RollBackward(point: Point, tip: Tip)

  final case class Settings(
      limits: ChainSync.Limits = ChainSync.Limits(),
      intersectTimeout: FiniteDuration = 10.seconds,
      canAwaitTimeout: FiniteDuration = 10.seconds,
      mustReply: ChainSyncTimeouts.Policy =
        ChainSyncTimeouts.Policy.Reference(ChainSyncTimeouts.PeerTrust.Untrustable),
      timeouts: Option[ChainSyncRuntime.Timeouts] = None,
      pipelineCapacity: Int = 300
  ):
    def withPeerTrust(peer: ChainSyncTimeouts.PeerTrust): Settings =
      copy(mustReply = mustReply.withPeerTrust(peer))

  def apply[F[_]: Async, P](
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
          settings.mustReply,
          settings.timeouts
        )
      )
      .map { runtime =>
        new ChainSyncClient[F, P]:
          def findIntersectResult(points: Vector[Point])(using
              Raise[F, NetworkError]
          ): F[Intersection] =
            runtime.findIntersectResult(points)
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
