// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network.genesis

import cats.effect.{Async, Deferred, Ref, Resource}
import cats.effect.syntax.all.*
import cats.mtl.Raise
import xyz.kd5ujc.scarab.network.{Fibers, NetworkError}
import cats.effect.std.Mutex
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Rational

/** Per-connection LoP controls. Configuration changes refill the bucket but retain the highest
  * validated block number, so replay after rollback or a GSM transition never earns another token.
  * Callbacks return values and never retain a caller's error capability.
  */
trait LimitOnPatience[F[_]]:
  def setPaused(reason: LimitOnPatience.PauseReason, paused: Boolean): F[Unit]
  def validated(blockNo: BigInt): F[Unit]
  def jumpedTo(blockNo: BigInt): F[Unit]
  def configure(config: LeakyBucket.Config): F[Unit]
  def snapshot: F[LeakyBucket.State]

object LimitOnPatience:
  enum PauseReason:
    case AwaitReply, Forecast, HeaderTime, Jumping

  private final case class Timer(generation: BigInt, wakePicos: BigInt)
  private final case class Watch(expiredGeneration: Option[BigInt], timer: Option[Timer])

  private final case class Live[F[_]](
      bucket: LeakyBucket.State,
      bestBlockNo: BigInt,
      changed: Deferred[F, Unit],
      pauses: Set[PauseReason]
  )

  def resource[F[_]: Async](
      initial: LeakyBucket.Config,
      onEmpty: Raise[F, NetworkError] ?=> F[Unit],
      onFailure: NetworkError => F[Unit],
      clock: Option[PicosClock[F]] = None
  ): Resource[F, LimitOnPatience[F]] =
    val F = Async[F]
    val thisTime = clock.getOrElse(PicosClock.system[F])
    def now: F[BigInt] = thisTime.now
    Resource
      .eval((now, Deferred[F, Unit], Mutex[F]).flatMapN { (time, changed, lock) =>
        Ref
          .of[F, Live[F]](Live(LeakyBucket.initial(initial, time), 0, changed, Set.empty))
          .tupleRight(lock)
      })
      .flatMap { (live, lock) =>
        def update(f: (Live[F], BigInt) => Live[F]): F[Unit] =
          lock.lock.surround(F.uncancelable { _ =>
            (now, Deferred[F, Unit]).flatMapN { (time, changed) =>
              live
                .modify { old =>
                  (f(old, time).copy(changed = changed), old.changed)
                }
                .flatMap(_.complete(()).void)
            }
          })

        val controls = new LimitOnPatience[F]:
          def setPaused(reason: PauseReason, paused: Boolean): F[Unit] = update { (s, time) =>
            val pauses = if paused then s.pauses + reason else s.pauses - reason
            s.copy(bucket = s.bucket.setPaused(pauses.nonEmpty, time), pauses = pauses)
          }
          def validated(blockNo: BigInt): F[Unit] = update { (s, time) =>
            if blockNo > s.bestBlockNo then
              s.copy(bucket = s.bucket.fill(Rational.one, time)._1, bestBlockNo = blockNo)
            else s.copy(bucket = s.bucket.snapshot(time))
          }
          def jumpedTo(blockNo: BigInt): F[Unit] = update { (s, time) =>
            s.copy(bucket = s.bucket.snapshot(time), bestBlockNo = s.bestBlockNo.max(blockNo))
          }
          def configure(config: LeakyBucket.Config): F[Unit] = update { (s, time) =>
            s.copy(bucket = s.bucket.reconfigure(config, config.capacity, time))
          }
          def snapshot: F[LeakyBucket.State] = lock.lock.surround(now.flatMap { time =>
            live.modify(s =>
              val next = s.copy(bucket = s.bucket.snapshot(time))
              (next, next.bucket)
            )
          })

        def step(watch: Watch)(using Raise[F, NetworkError]): F[Either[Watch, Unit]] =
          lock.lock
            .surround((now, live.get).mapN { (time, s) =>
              (time, s.bucket.snapshot(time), s.changed)
            })
            .flatMap { (time, bucket, changed) =>
              def waitFor(timer: Timer): F[Either[Watch, Unit]] =
                F.race(changed.get, thisTime.sleep((timer.wakePicos - time).max(0)))
                  .as(Left(Watch(None, Some(timer))))
              watch.timer.filter(_.generation == bucket.generation) match
                case Some(timer) if timer.wakePicos > time => waitFor(timer)
                case _
                    if watch.expiredGeneration.contains(
                      bucket.generation
                    ) || bucket.waitMicros.isEmpty =>
                  changed.get.as(Left(Watch(watch.expiredGeneration, None)))
                case _ if bucket.expired =>
                  onEmpty.as(Left(Watch(Some(bucket.generation), None)))
                case _ =>
                  bucket.waitMicros.fold(
                    changed.get.as[Either[Watch, Unit]](Left(Watch(None, None)))
                  ) { micros =>
                    waitFor(Timer(bucket.generation, time + micros * 1000000))
                  }
            }

        Fibers
          .scoped[F, NetworkError, Unit](NetworkError.Defect(_))(
            F.tailRecM(Watch(None, None))(step)
          )
          .flatMap(_.fold(onFailure, _ => F.unit))
          .background
          .as(controls)
      }
