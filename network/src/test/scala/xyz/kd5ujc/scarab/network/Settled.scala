// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.{Id, Monad}
import cats.effect.{Deferred, IO, Outcome, Resource}
import cats.effect.kernel.testkit.TestContext
import cats.effect.std.Queue
import cats.effect.unsafe.{IORuntime, IORuntimeConfig, Scheduler}
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.{Duration, FiniteDuration}

/** A test whose steps wait for the other fibers to have done all they can (issue 870).
  *
  * `body` runs on a simulated runtime (the one of `TestControl`) and is given `settle`, which
  * returns once every fiber of the test is blocked: on a queue, a `Deferred`, a lock or a timer
  * that has not fired. That is the state a sleep in a test hopes to have reached; here it is
  * reached, whatever the load of the machine. [[apply]] does not move the simulated clock: a step
  * that needs a timer to fire is not settled, and the test fails with the state it had instead of
  * waiting. [[timed]] moves it to the next timer when every fiber is blocked and none waits in
  * `settle`, for code under test that polls or times out on its own clock.
  *
  * A fiber of `body` that ends with an error nobody observed fails the test, as [[FailsOnUncaught]]
  * does on the real runtime. Each run orders the runnable fibers by a seed of its own, which a
  * failure prints so that [[seeded]] replays it. `body` may do blocking work that completes (file
  * I/O), but must not wait for anything outside the simulation (a socket, another thread): every
  * fiber would be blocked, and the test would fail as stuck.
  */
object Settled:

  def apply[A](body: IO[Unit] => IO[A]): IO[A] = run(None, timed = false)(body)

  /** As [[apply]], with the scheduling `seed` of `TestControl`: each seed orders the runnable
    * fibers differently, and replays that order on every run.
    */
  def seeded[A](seed: String)(body: IO[Unit] => IO[A]): IO[A] =
    run(Some(seed), timed = false)(body)

  /** As [[apply]], and the simulated clock moves to the next timer whenever every fiber is blocked
    * and none waits in `settle`.
    */
  def timed[A](body: IO[Unit] => IO[A]): IO[A] = run(None, timed = true)(body)

  /** `read` once `settle` has returned, when every fiber has done all it can: it must then satisfy
    * `ok`, or the test fails with what it read.
    */
  def observed[A](settle: IO[Unit], what: String)(read: IO[A])(ok: A => Boolean): IO[A] =
    settle *> read.flatMap(a =>
      if ok(a) then IO.pure(a) else IO.raiseError(new AssertionError(s"not $what once settled: $a"))
    )

  /** The runtime that `TestControl.execute` builds, made here to read the failures it is asked to
    * report: the simulation, and where the outcome of `program` will be.
    */
  private def simulated[A](
      seed: Option[String],
      program: IO[A]
  ): IO[(TestContext, IORuntime, AtomicReference[Option[Outcome[Id, Throwable, A]]])] =
    IO {
      val ctx = seed.fold(TestContext())(TestContext(_))
      val scheduler = new Scheduler:
        def sleep(delay: FiniteDuration, task: Runnable): Runnable =
          val cancel = ctx.schedule(delay, task)
          () => cancel()
        def nowMillis(): Long = ctx.now().toMillis
        def monotonicNanos(): Long = ctx.now().toNanos
      val runtime = IORuntime(ctx, ctx.deriveBlocking(), scheduler, () => (), IORuntimeConfig())
      val results = new AtomicReference[Option[Outcome[Id, Throwable, A]]](None)
      program.unsafeRunAsyncOutcome(outcome => results.set(Some(outcome)))(using runtime)
      (ctx, runtime, results)
    }

  private def run[A](seed: Option[String], timed: Boolean)(body: IO[Unit] => IO[A]): IO[A] =
    for
      checkpoints <- Queue.unbounded[IO, Deferred[IO, Unit]]
      stop <- Deferred[IO, Unit]
      settle = Deferred[IO, Unit].flatMap(ack => checkpoints.offer(ack) *> ack.get)
      simulation <- simulated(seed, IO.race(body(settle), stop.get))
      (ctx, runtime, results) = simulation
      result <- Resource
        .make(IO.unit)(_ => stop.complete(()).void *> IO(ctx.tickAll()) *> IO(runtime.shutdown()))
        .use { _ =>
          val uncaught: IO[Unit] = IO(ctx.state.lastReportedFailure).flatMap {
            case Some(cause) =>
              IO.raiseError(
                new AssertionError(s"the runtime reported an uncaught fiber failure: $cause", cause)
              )
            case None => IO.unit
          }
          val ended: IO[Option[A]] = IO(results.get).flatMap {
            case Some(Outcome.Succeeded(Left(a))) => IO.pure(Some(a))
            case Some(Outcome.Errored(cause))     => IO.raiseError(cause)
            case None                             => IO.pure(None)
            case other =>
              IO.raiseError(new AssertionError(s"the test ended without a result: $other"))
          }
          val stuck = new AssertionError(
            "the test neither ended nor settled: every fiber is blocked" +
              (if timed then " and no timer is set" else "")
          )
          Monad[IO].tailRecM(()) { _ =>
            IO(ctx.tick()) *> uncaught *> checkpoints.tryTake.flatMap {
              case Some(ack) => ack.complete(()).as(Left(()))
              case None =>
                ended.flatMap {
                  case Some(a) => IO.pure(Right(a))
                  case None if timed =>
                    IO(ctx.nextInterval()).flatMap(next =>
                      if next > Duration.Zero then IO(ctx.advance(next)).as(Left(()))
                      else IO.raiseError(stuck)
                    )
                  case None => IO.raiseError(stuck)
                }
            }
          }
        }
        .onError(_ => IO(System.err.println(s"Settled: the failed run's seed is ${ctx.seed}")))
    yield result
