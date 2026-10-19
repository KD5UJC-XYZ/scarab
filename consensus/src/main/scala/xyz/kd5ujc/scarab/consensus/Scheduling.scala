// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus

import cats.Monad
import cats.effect.{Async, Clock, Outcome, Ref, Resource, Sync, Temporal}
import cats.effect.syntax.all.*
import cats.syntax.all.*
import cats.effect.unsafe.IORuntime
import scala.concurrent.duration.*

/** What the scheduling monitor and the CPU-bound steps have seen (D8): how late timers fired and
  * how long the longest CPU-bound step held a thread.
  */
final case class SchedulingReport(
    /** Number of timer samples. */
    samples: Long,
    /** The latest a timer fired, beyond its due time. */
    maxTimerLatenessMs: Long,
    timersLateOver50: Long,
    timersLateOver100: Long,
    /** CPU-bound steps run on the pool, by label: count, longest and total duration. */
    steps: Map[String, SchedulingReport.Step]
):
  def stepCount: Long = steps.valuesIterator.map(_.count).sum

  /** The longest step of any label, with its label. */
  def longest: Option[(String, SchedulingReport.Step)] =
    steps.maxByOption(_._2.maxMs)

  def merge(other: SchedulingReport): SchedulingReport =
    SchedulingReport(
      samples + other.samples,
      maxTimerLatenessMs.max(other.maxTimerLatenessMs),
      timersLateOver50 + other.timersLateOver50,
      timersLateOver100 + other.timersLateOver100,
      other.steps.foldLeft(steps) { case (acc, (label, b)) =>
        acc.updated(
          label,
          acc.get(label).fold(b) { a =>
            SchedulingReport.Step(a.count + b.count, a.maxMs.max(b.maxMs), a.totalMs + b.totalMs)
          }
        )
      }
    )

object SchedulingReport:
  final case class Step(count: Long, maxMs: Long, totalMs: Long)
  val empty: SchedulingReport = SchedulingReport(0, 0, 0, 0, Map.empty)

  /** Merging windows: counts add, maxima take the larger. */
  given cats.kernel.CommutativeMonoid[SchedulingReport] with
    def empty: SchedulingReport = SchedulingReport.empty
    def combine(a: SchedulingReport, b: SchedulingReport): SchedulingReport = a.merge(b)

/** Scheduling measurements, kept per window (read and reset by [[window]], one per epoch in the
  * log) and for the whole run ([[total]]).
  */
final class SchedulingStats[F[_]: Sync] private (
    current: Ref[F, SchedulingReport],
    whole: Ref[F, SchedulingReport]
):
  private def add(delta: SchedulingReport): F[Unit] =
    current.update(_.merge(delta)) *> whole.update(_.merge(delta))

  /** A timer that was due `lateMs` ago has just fired. */
  def timer(lateMs: Long): F[Unit] =
    add(
      SchedulingReport(
        1,
        lateMs,
        if lateMs > 50 then 1 else 0,
        if lateMs > 100 then 1 else 0,
        Map.empty
      )
    )

  /** A CPU-bound step labelled `label` ran for `nanos`. */
  def step(label: String, nanos: Long): F[Unit] =
    val ms = nanos / 1000000
    add(SchedulingReport(0, 0, 0, 0, Map(label -> SchedulingReport.Step(1, ms, ms))))

  /** The measurements since the previous call; starts a new window. */
  def window: F[SchedulingReport] = current.getAndSet(SchedulingReport.empty)

  /** The measurements since the start. */
  def total: F[SchedulingReport] = whole.get

object SchedulingStats:
  def create[F[_]: Sync]: F[SchedulingStats[F]] =
    (
      Ref.of[F, SchedulingReport](SchedulingReport.empty),
      Ref.of[F, SchedulingReport](SchedulingReport.empty)
    )
      .mapN(new SchedulingStats(_, _))

/** Where the CPU-bound part of the node runs (D8). The cats-effect compute pool has one thread per
  * CPU; a pure step that takes hundreds of milliseconds (a block with Plutus scripts, an epoch
  * boundary over all accounts) held on it delays every timer of the node: KeepAlive, protocol
  * timeouts, the forger's slot clock. The part of the program that applies blocks runs through
  * [[within]] on a separate bounded pool instead, so the compute pool keeps serving timers and the
  * network fibers, and the operating system, not the runtime, shares the CPUs between the two.
  *
  * Moving an effect between pools costs a thread hand-off in and out, which on a busy host is a
  * scheduler wake-up each: it is paid once per batch of blocks, not once per block. The pure steps
  * inside are only measured ([[timed]]), on the thread they run on.
  */
trait Compute[F[_]]:
  /** Run `fa` on the CPU pool: its non-blocking steps execute there, and `blocking` calls leave it
    * and come back.
    */
  def within[A](fa: F[A]): F[A]

  /** Run a pure step on the current thread and record its duration under the label `label` computes
    * from the result (a block that crossed an epoch boundary says so).
    */
  def timed[A](label: A => String)(step: => A): F[A]

  /** Measure a step that joins bounded workers, including preparation and ordered landing. */
  def timedF[A](label: A => String)(step: => F[A]): F[A]

object Compute:
  /** Everything where it is, unmeasured: tests and pure interpreters. */
  def inline[F[_]: Monad]: Compute[F] = new Compute[F]:
    def within[A](fa: F[A]): F[A] = fa
    def timed[A](label: A => String)(step: => A): F[A] = Monad[F].unit.map(_ => step)
    def timedF[A](label: A => String)(step: => F[A]): F[A] = Monad[F].unit.flatMap(_ => step)

  /** No separate pool, but steps measured: what the node did before D8, kept as `--cpu-threads 0`
    * to compare against.
    */
  def measured[F[_]: Sync](stats: SchedulingStats[F]): Compute[F] = new Compute[F]:
    def within[A](fa: F[A]): F[A] = fa
    def timed[A](label: A => String)(step: => A): F[A] = timeStep(stats, label, step)
    def timedF[A](label: A => String)(step: => F[A]): F[A] = timeEffect(stats, label, step)

  private def timeStep[F[_]: Sync, A](
      stats: SchedulingStats[F],
      label: A => String,
      step: => A
  ): F[A] =
    Sync[F]
      .delay {
        val began = System.nanoTime()
        val result = step
        (result, System.nanoTime() - began)
      }
      .flatMap((result, nanos) => stats.step(label(result), nanos).as(result))

  private def timeEffect[F[_]: Sync, A](
      stats: SchedulingStats[F],
      label: A => String,
      step: => F[A]
  ): F[A] =
    for
      began <- Clock[F].monotonic
      result <- Sync[F].defer(step)
      ended <- Clock[F].monotonic
      _ <- stats.step(label(result), (ended - began).toNanos)
    yield result

  /** `fa` on `pool`, as a fiber of its own that the caller joins from its own thread.
    *
    * A cancellation that reaches a fiber inside `evalOn` runs that fiber's finalizers on the pool's
    * thread, and they stay there: the finalizer that shuts the pool down (see [[pool]]) then runs
    * on the pool it shuts down, and the finalizers after it are scheduled onto the dead pool and
    * never run, so the program never ends (issue 89). Here the caller is cancelled where it is; it
    * cancels the pool fiber and waits for its finalizers, which run on the pool, and carries on its
    * own on the thread it came from.
    */
  private def onPool[F[_]: Async, A](fa: F[A], pool: scala.concurrent.ExecutionContext): F[A] =
    Async[F].uncancelable { poll =>
      Async[F].evalOn(fa, pool).start.flatMap { fiber =>
        poll(fiber.join).onCancel(fiber.cancel).flatMap {
          case Outcome.Succeeded(result) => result
          case Outcome.Errored(error)    => Async[F].raiseError[A](error)
          case Outcome.Canceled()        => poll(Async[F].canceled) *> Async[F].never[A]
        }
      }
    }

  /** A second cats-effect work-stealing pool of `threads` workers (`scarab-cpu-N`), shut down with
    * the resource. It is the same scheduler the compute pool is, so a hand-off to it is as cheap as
    * a fiber moving between compute workers (a plain `ThreadPoolExecutor` parks a thread per
    * continuation, which on a busy host costs a scheduler wake-up each and took a third of the
    * throughput), and `blocking` inside it behaves as it does on the compute pool.
    */
  def pool[F[_]: Async](threads: Int, stats: SchedulingStats[F]): Resource[F, Compute[F]] =
    Resource
      .make(Async[F].delay {
        IORuntime.createWorkStealingComputeThreadPool(
          threads = threads,
          threadPrefix = "scarab-cpu",
          blockerThreadPrefix = "scarab-cpu-blocker"
        )
      })(pool => Async[F].delay(pool._3()))
      .map { pool =>
        new Compute[F]:
          def within[A](fa: F[A]): F[A] = onPool(fa, pool._1)
          def timed[A](label: A => String)(step: => A): F[A] = timeStep(stats, label, step)
          def timedF[A](label: A => String)(step: => F[A]): F[A] = timeEffect(stats, label, step)
      }

/** The node's scheduling facilities: the CPU pool and the timer monitor. */
final case class Scheduling[F[_]](stats: SchedulingStats[F], compute: Compute[F])

object Scheduling:
  /** A fiber that sleeps `period` over and over and records how late it wakes: the lateness of
    * every timer on the compute pool, which is what the cats-effect starvation check warns about.
    */
  def monitor[F[_]: Temporal](
      stats: SchedulingStats[F],
      period: FiniteDuration
  ): Resource[F, Unit] =
    val tick = for
      began <- Clock[F].monotonic
      _ <- Temporal[F].sleep(period)
      woke <- Clock[F].monotonic
      _ <- stats.timer(((woke - began) - period).toMillis.max(0))
    yield ()
    tick.foreverM[Unit].background.void

  /** The CPU pool (`cpuThreads` threads; 0: none, the steps run on the compute pool), the timer
    * monitor and the statistics, for the life of the resource.
    */
  def resource[F[_]: Async](cpuThreads: Int): Resource[F, Scheduling[F]] =
    for
      stats <- Resource.eval(SchedulingStats.create[F])
      compute <-
        if cpuThreads <= 0 then Resource.pure[F, Compute[F]](Compute.measured[F](stats))
        else Compute.pool[F](cpuThreads, stats)
      _ <- monitor[F](stats, 20.millis)
    yield Scheduling(stats, compute)

  /** Same facilities without a pool or a monitor (tests). */
  def inline[F[_]: Sync]: F[Scheduling[F]] =
    SchedulingStats.create[F].map(s => Scheduling(s, Compute.inline[F](using Sync[F])))
