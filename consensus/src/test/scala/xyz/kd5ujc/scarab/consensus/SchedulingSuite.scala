// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus

import cats.Eq
import cats.effect.IO
import cats.kernel.laws.discipline.CommutativeMonoidTests
import cats.syntax.all.*
import munit.{CatsEffectSuite, DisciplineSuite}
import org.scalacheck.{Arbitrary, Gen}
import scala.concurrent.duration.*

/** The measurements form a commutative monoid, so per-epoch windows merge into the run total. */
class SchedulingReportLawsSuite extends DisciplineSuite:
  private val step: Gen[SchedulingReport.Step] =
    for
      count <- Gen.choose(0L, 50L)
      max <- Gen.choose(0L, 900L)
      total <- Gen.choose(0L, 9000L)
    yield SchedulingReport.Step(count, max, total)

  given Arbitrary[SchedulingReport] = Arbitrary(
    for
      samples <- Gen.choose(0L, 1000L)
      late <- Gen.choose(0L, 500L)
      over50 <- Gen.choose(0L, 100L)
      over100 <- Gen.choose(0L, 100L)
      labels <- Gen.listOf(Gen.zip(Gen.oneOf("block", "plutus", "epochBoundary", "decode"), step))
    yield SchedulingReport(samples, late, over50, over100, labels.toMap)
  )
  given Eq[SchedulingReport] = Eq.fromUniversalEquals

  checkAll("SchedulingReport", CommutativeMonoidTests[SchedulingReport].commutativeMonoid)

class SchedulingSuite extends CatsEffectSuite:
  override def munitIOTimeout: Duration = 2.minutes

  private val cpus = math.max(2, Runtime.getRuntime.availableProcessors())

  private def spin(ms: Long): Long =
    val end = System.nanoTime() + ms * 1000000
    var x = 0L
    while System.nanoTime() < end do x += 1
    x

  test("an effect runs on a CPU pool thread; a timed step is recorded under its label") {
    SchedulingStats.create[IO].flatMap { stats =>
      Compute.pool[IO](2, stats).use { compute =>
        for
          thread <- compute.within(IO(Thread.currentThread.getName))
          // a blocking call stays with the pool (the worker blocks in place, as on the compute pool)
          blockingThread <- compute.within(IO.blocking(Thread.currentThread.getName))
          back <- compute.within(IO.blocking(()) *> IO(Thread.currentThread.getName))
          outside <- IO(Thread.currentThread.getName)
          _ <- compute.timed((_: Long) => "spin")(spin(30))
          report <- stats.total
          _ <- IO {
            assert(thread.startsWith("csl-cpu-"), thread)
            assert(blockingThread.startsWith("csl-cpu"), blockingThread)
            assert(back.startsWith("csl-cpu-"), back)
            assert(!outside.startsWith("csl-cpu-"), outside)
            assertEquals(report.steps.keySet, Set("spin"))
            assertEquals(report.steps("spin").count, 1L)
            assert(report.steps("spin").maxMs >= 25, report.toString)
            assertEquals(report.longest.map(_._1), Some("spin"))
          }
        yield ()
      }
    }
  }

  test("windows: reading one starts the next; the total keeps everything") {
    SchedulingStats.create[IO].flatMap { stats =>
      for
        _ <- stats.timer(7) *> stats.step("block", 5000000L)
        first <- stats.window
        _ <- stats.timer(120)
        second <- stats.window
        third <- stats.window
        total <- stats.total
        _ <- IO {
          assertEquals(first.maxTimerLatenessMs, 7L)
          assertEquals(first.steps("block").maxMs, 5L)
          assertEquals((second.samples, second.timersLateOver100), (1L, 1L))
          assertEquals(third, SchedulingReport.empty)
          assertEquals(total, first.merge(second))
        }
      yield ()
    }
  }

  test("results through the pool equal the inline ones") {
    val inline = Compute.inline[IO]
    SchedulingStats.create[IO].flatMap { stats =>
      Compute.pool[IO](3, stats).use { pooled =>
        val work = (1 to 200).toList
        for
          a <- work.traverse(i => inline.timed((_: Int) => "x")(i * i + 1))
          b <- work.parTraverse(i => pooled.within(pooled.timed((_: Int) => "x")(i * i + 1)))
          _ <- IO(assertEquals(b, a))
        yield ()
      }
    }
  }

  /** The largest lateness of a 20 ms timer while `cpus` CPU-bound steps of 600 ms run in parallel.
    */
  private def lateness(run: IO[Long] => IO[Long]): IO[Long] =
    SchedulingStats.create[IO].flatMap { stats =>
      Scheduling.monitor[IO](stats, 20.millis).use { _ =>
        IO.sleep(100.millis) *>
          List.fill(cpus)(()).parTraverse_(_ => run(IO(spin(600)))) *>
          stats.total.map(_.maxTimerLatenessMs)
      }
    }

  test("CPU-bound work on the pool leaves the timers alone; on the compute pool it starves them") {
    for
      unpooled <- lateness(identity)
      pooled <- SchedulingStats.create[IO].flatMap { poolStats =>
        Compute.pool[IO](cpus, poolStats).use(c => lateness(c.within))
      }
      _ <- IO {
        // all compute threads are inside a step for 600 ms: no timer can fire
        assert(unpooled >= 300, s"inline lateness $unpooled ms")
        // on the pool the compute threads stay free (a busy machine delays any thread, so the bound is loose)
        assert(
          pooled < 400 && pooled < unpooled,
          s"pooled lateness $pooled ms, inline $unpooled ms"
        )
      }
    yield ()
  }
