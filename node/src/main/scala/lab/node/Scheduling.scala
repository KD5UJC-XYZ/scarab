// SPDX-License-Identifier: Apache-2.0
package lab.node

import cats.Monad
import cats.effect.{Async, Clock, Ref, Resource, Sync, Temporal}
import cats.effect.syntax.all.*
import cats.syntax.all.*
import java.util.concurrent.{ExecutorService, Executors, ThreadFactory}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.ExecutionContext
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

/** Where CPU-bound pure steps run (D8). The cats-effect compute pool has one thread per CPU; a pure
  * step that takes hundreds of milliseconds (a block with Plutus scripts, an epoch boundary over
  * all accounts) held on it delays every timer of the node: KeepAlive, protocol timeouts, the
  * forger's slot clock. Steps run through this algebra execute on a separate bounded pool, so the
  * compute pool keeps serving timers and the operating system, not the runtime, shares the CPUs
  * between the two. The step is a by-name thunk of a pure function; its duration is recorded under
  * the label `label` computes from the result (a block that crossed an epoch boundary says so).
  */
trait Compute[F[_]]:
  def run[A](label: A => String)(step: => A): F[A]

object Compute:
  /** On the calling thread, unmeasured: tests and pure interpreters. */
  def inline[F[_]: Monad]: Compute[F] = new Compute[F]:
    def run[A](label: A => String)(step: => A): F[A] = Monad[F].unit.map(_ => step)

  /** A fixed pool of `threads` daemon threads, shut down with the resource. */
  def pool[F[_]: Async](threads: Int, stats: SchedulingStats[F]): Resource[F, Compute[F]] =
    val F = Async[F]
    Resource
      .make(F.delay(newPool(threads)))(ex => F.delay(ex.shutdown()))
      .map { executor =>
        val ec = ExecutionContext.fromExecutor(executor)
        new Compute[F]:
          def run[A](label: A => String)(step: => A): F[A] =
            F.evalOn(
              F.delay {
                val began = System.nanoTime()
                val result = step
                (result, System.nanoTime() - began)
              },
              ec
            ).flatMap((result, nanos) => stats.step(label(result), nanos).as(result))
      }

  private def newPool(threads: Int): ExecutorService =
    val count = new AtomicInteger(0)
    Executors.newFixedThreadPool(
      threads,
      new ThreadFactory:
        def newThread(r: Runnable): Thread =
          val t = new Thread(r, s"csl-cpu-${count.incrementAndGet()}")
          t.setDaemon(true)
          t
    )

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

  /** The CPU pool (`cpuThreads` threads), the timer monitor and the statistics, for the life of the
    * resource.
    */
  def resource[F[_]: Async](cpuThreads: Int): Resource[F, Scheduling[F]] =
    for
      stats <- Resource.eval(SchedulingStats.create[F])
      compute <- Compute.pool[F](cpuThreads, stats)
      _ <- monitor[F](stats, 20.millis)
    yield Scheduling(stats, compute)

  /** Same facilities without a pool or a monitor (tests). */
  def inline[F[_]: Sync]: F[Scheduling[F]] =
    SchedulingStats.create[F].map(s => Scheduling(s, Compute.inline[F](using Sync[F])))
