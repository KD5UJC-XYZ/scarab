// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.{Clock, Temporal}
import cats.syntax.all.*
import fs2.concurrent.SignallingRef
import scala.concurrent.duration.*
import scala.util.control.NonFatal

/** When the received log is collected, as the reference's ChainDB schedules the collection of its
  * VolatileDB (`GcSchedule`, `scheduleGC`, `computeTimeForGC`, `roundUpToInterval` and
  * `gcScheduleRunner`, `Storage/ChainDB/Impl/Background.hs` 466-652, ouroboros-consensus 82ecba329;
  * 476-662 at 808e40054). Each copy of blocks to the immutable store schedules a collection for the
  * slot of the immutable tip ([[schedule]]), at the monotonic time now plus the delay, rounded up
  * to the interval. A schedule for the same time as the last one queued replaces it: the entries
  * are coalesced by their time, one to an interval, of the newest slot scheduled for it. The runner
  * ([[run]]) waits for the time of the oldest entry, takes it off the queue and collects its slot,
  * one collection at a time. At most one collection an interval is a goal, "when possible" in the
  * reference's words (`Background.hs` 486-492), not a bound: while the runner takes each entry off
  * when it falls due, the queue holds at most ⌈delay / interval⌉ + 1 entries, and a collection that
  * runs past the next entry's time, or a runner that is late, leaves the entries due meanwhile
  * queued, one to an interval, to run back to back. Nothing of the schedule is kept on disk: after
  * a restart, the collection the next copy schedules covers the earlier ones, since a collection
  * takes every file below its slot (`copyToImmutableDBRunner`'s comment, `Background.hs` 253-256;
  * 254-257 at 808e40054).
  */
trait GcSchedule[F[_]]:
  /** Schedule a collection for `slot`, the immutable tip's after a copy. */
  def schedule(slot: BigInt): F[Unit]

  /** The collections scheduled and not yet run, oldest first (the reference's `dumpGcSchedule`). */
  def pending: F[Vector[GcSchedule.Scheduled]]

  /** Run each scheduled collection at its time, one at a time and in order, until one fails: its
    * failure is the result. An exception anywhere in the runner, while it waits for an entry's time
    * or while it collects, ends it too, with `defect` of the exception as the result, so the runner
    * always ends with a value. Only this runner takes entries off the queue.
    */
  def run[E](collect: BigInt => F[Either[E, Unit]], defect: Throwable => E): F[E]

object GcSchedule:
  /** A collection of the blocks below `slot`, due at monotonic time `at`. */
  final case class Scheduled(at: FiniteDuration, slot: BigInt)

  /** How long after a copy its collection runs (`delay`), and the interval its time is rounded up
    * to (`interval`, never zero): the reference's `GcParams`, from `cdbsGcDelay` and
    * `cdbsGcInterval`.
    */
  final case class Params private (delay: FiniteDuration, interval: FiniteDuration)

  object Params:
    /** The parameters, none when the delay is negative or the interval not positive. */
    def of(delay: FiniteDuration, interval: FiniteDuration): Option[Params] =
      Option.when(delay >= Duration.Zero && interval > Duration.Zero)(Params(delay, interval))

    /** The reference's: a delay of 60 s and an interval of 10 s (`defaultSpecificArgs`,
      * `Storage/ChainDB/Impl/Args.hs` 119-124, ouroboros-consensus 82ecba329; 126-131 at
      * 808e40054).
      */
    val Reference: Params = Params(60.seconds, 10.seconds)

  /** The time of a collection scheduled at `now`: `now` plus the delay, rounded up to a multiple of
    * the interval (`computeTimeForGC` and `roundUpToInterval`). A time past the monotonic clock's
    * range is its largest value.
    */
  def timeFor(params: Params, now: FiniteDuration): FiniteDuration =
    val x = BigInt(now.toNanos) + params.delay.toNanos
    val interval = BigInt(params.interval.toNanos)
    val rest = x.mod(interval)
    val at = if rest == 0 then x else x - rest + interval
    FiniteDuration(at.min(BigInt(Long.MaxValue)).toLong, NANOSECONDS)

  /** `queue` with `entry` scheduled: in place of the last entry when that one is for the same time,
    * else after it (`scheduleGC`).
    */
  def scheduled(queue: Vector[Scheduled], entry: Scheduled): Vector[Scheduled] =
    queue.lastOption match
      case Some(last) if last.at == entry.at => queue.dropRight(1) :+ entry
      case _                                 => queue :+ entry

  /** How long from `now` until `at`, none when it has passed. */
  private def until(at: FiniteDuration, now: FiniteDuration): FiniteDuration =
    val left = (BigInt(at.toNanos) - now.toNanos).max(0).min(BigInt(Long.MaxValue))
    FiniteDuration(left.toLong, NANOSECONDS)

  def apply[F[_]](params: Params)(using F: Temporal[F]): F[GcSchedule[F]] =
    SignallingRef[F, Vector[Scheduled]](Vector.empty).map { queue =>
      new GcSchedule[F]:
        def schedule(slot: BigInt): F[Unit] =
          Clock[F].monotonic.flatMap(now =>
            queue.update(scheduled(_, Scheduled(timeFor(params, now), slot)))
          )

        def pending: F[Vector[Scheduled]] = queue.get

        /** The slot of the oldest entry, taken off the queue once its time has come. The slot is
          * read after the wait: a schedule for the same time may have replaced it meanwhile.
          */
        private def due: F[BigInt] =
          F.tailRecM(())(_ =>
            queue.waitUntil(_.nonEmpty) *> queue.get
              .flatMap(_.headOption.traverse { first =>
                Clock[F].monotonic.flatMap(now => F.sleep(until(first.at, now))) *>
                  queue.modify(q => (q.drop(1), q.headOption.map(_.slot)))
              })
              .map(_.flatten.toRight(()))
          )

        def run[E](collect: BigInt => F[Either[E, Unit]], defect: Throwable => E): F[E] =
          F.tailRecM(())(_ => due.flatMap(collect).map(_.swap)).handleErrorWith {
            case NonFatal(cause) => F.pure(defect(cause))
            case fatal           => F.raiseError(fatal)
          }
    }
