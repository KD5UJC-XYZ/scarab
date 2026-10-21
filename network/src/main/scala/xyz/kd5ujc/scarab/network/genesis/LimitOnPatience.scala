// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network.genesis

import cats.effect.{Deferred, Ref, Resource, Temporal}
import cats.effect.syntax.all.*
import cats.effect.std.Mutex
import cats.syntax.all.*
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Rational

/** Per-connection LoP controls. Configuration changes refill the bucket but retain the highest
  * validated block number, so replay after rollback or a GSM transition never earns another token.
  * Callbacks return values and never retain a caller's error capability.
  */
trait LimitOnPatience[F[_]]:
  def setPaused(reason: LimitOnPatience.PauseReason, paused: Boolean): F[Unit]
  def validated(blockNo: BigInt): F[Unit]
  def configure(config: LeakyBucket.Config): F[Unit]
  def snapshot: F[LeakyBucket.State]

object LimitOnPatience:
  enum PauseReason:
    case AwaitReply, Forecast, HeaderTime, Jumping

  private final case class Live[F[_]](
      bucket: LeakyBucket.State,
      bestBlockNo: BigInt,
      changed: Deferred[F, Unit],
      pauses: Set[PauseReason]
  )

  def resource[F[_]: Temporal](
      initial: LeakyBucket.Config,
      onEmpty: F[Unit]
  ): Resource[F, LimitOnPatience[F]] =
    val F = Temporal[F]
    def now: F[BigInt] = F.monotonic.map(t => BigInt(t.toNanos) * 1000)
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
          def configure(config: LeakyBucket.Config): F[Unit] = update { (s, time) =>
            s.copy(bucket = s.bucket.reconfigure(config, config.capacity, time))
          }
          def snapshot: F[LeakyBucket.State] = lock.lock.surround(now.flatMap { time =>
            live.modify(s =>
              val next = s.copy(bucket = s.bucket.snapshot(time))
              (next, next.bucket)
            )
          })

        def step(expiredGeneration: Option[BigInt]): F[Either[Option[BigInt], Unit]] =
          lock.lock
            .surround((now, live.get).mapN { (time, s) =>
              (s.bucket.snapshot(time), s.changed)
            })
            .flatMap { (bucket, changed) =>
              if bucket.expired then
                if expiredGeneration.contains(bucket.generation) then
                  changed.get.as(Left(expiredGeneration))
                else onEmpty.as(Left(Some(bucket.generation)))
              else if bucket.paused || bucket.waitMicros.isEmpty then changed.get.as(Left(None))
              else
                bucket.waitMicros
                  .fold(changed.get) { micros =>
                    val wait = micros.min(BigInt(Long.MaxValue)).max(1).toLong.microseconds
                    F.race(changed.get, F.sleep(wait)).void
                  }
                  .as(Left(None))
            }

        F.tailRecM(Option.empty[BigInt])(step).background.as(controls)
      }
