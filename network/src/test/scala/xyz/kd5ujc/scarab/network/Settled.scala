// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.{Id, Monad}
import cats.effect.{Deferred, IO, Outcome, Resource}
import cats.effect.kernel.testkit.TestContext
import cats.effect.std.Queue
import cats.effect.unsafe.{IORuntime, IORuntimeConfig, Scheduler}
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
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
  * Every fiber runs on the thread that drives the simulation, a task at a time. A fiber that never
  * waits (a loop that only computes) would keep that thread for ever: once [[StepLimit]] tasks (or
  * the limit of [[limited]]) have run without every fiber being blocked, the test fails, prints its
  * seed, and cancels `body`. Its cleanup runs at most as many tasks between two moves of the clock,
  * and the simulation is then left: nothing runs its tasks any more. A cleanup that does not end
  * within those bounds after `body` has ended (a fiber that outlives the test, such as a ticker it
  * started) fails the test too.
  *
  * A fiber of `body` that ends with an error nobody observed fails the test, as [[FailsOnUncaught]]
  * does on the real runtime. Each run orders the runnable fibers by a seed of its own, which a
  * failure prints so that [[seeded]] replays it. `body` may do blocking work that completes (file
  * I/O), but must not wait for anything outside the simulation (a socket, another thread): every
  * fiber would be blocked, and the test would fail as stuck.
  */
object Settled:

  /** The most tasks a step of a test runs before every fiber is blocked: 30 times the largest step
    * of the suites, 167,052 tasks (`FortyBearersSuite`, issue 1110). The count of a seed's run does
    * not depend on the load of the machine.
    */
  val StepLimit: Long = 5000000L

  def apply[A](body: IO[Unit] => IO[A]): IO[A] = run(None, timed = false, StepLimit)(body)

  /** As [[apply]], with a step limit of the test's own: a test whose steps are small fails sooner
    * when a fiber never waits.
    */
  def limited[A](steps: Long)(body: IO[Unit] => IO[A]): IO[A] =
    run(None, timed = false, steps)(body)

  /** As [[apply]], with the scheduling `seed` of `TestControl`: each seed orders the runnable
    * fibers differently, and replays that order on every run.
    */
  def seeded[A](seed: String)(body: IO[Unit] => IO[A]): IO[A] =
    run(Some(seed), timed = false, StepLimit)(body)

  /** As [[apply]], and the simulated clock moves to the next timer whenever every fiber is blocked
    * and none waits in `settle`.
    */
  def timed[A](body: IO[Unit] => IO[A]): IO[A] = run(None, timed = true, StepLimit)(body)

  /** As [[timed]], with the scheduling `seed` of [[seeded]]. */
  def seededTimed[A](seed: String)(body: IO[Unit] => IO[A]): IO[A] =
    run(Some(seed), timed = true, StepLimit)(body)

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

  /** Runs the simulation's runnable tasks until none is left, at most `steps` of them: whether none
    * was left.
    */
  private def quiesce(ctx: TestContext, steps: Long): Boolean =
    @tailrec
    def loop(ran: Long): Boolean =
      if !ctx.tickOne() then true
      else if ran + 1 >= steps then false
      else loop(ran + 1)
    loop(0)

  /** The most times the cleanup of a test moves the clock to its next timer; the suites move it at
    * most once (issue 1110).
    */
  private val CleanupMoves = 10000

  /** As `TestContext.tickAll`, which runs every task and moves the clock to every timer, with at
    * most `steps` tasks between two moves of the clock and [[CleanupMoves]] moves: whether no task
    * was left.
    */
  private def drain(ctx: TestContext, steps: Long): Boolean =
    @tailrec
    def loop(moves: Int): Boolean =
      if !quiesce(ctx, steps) then false
      else
        val next = ctx.nextInterval()
        if next <= Duration.Zero then true
        else if moves >= CleanupMoves then false
        else
          ctx.advance(next)
          loop(moves + 1)
    loop(0)

  private def run[A](seed: Option[String], timed: Boolean, steps: Long)(
      body: IO[Unit] => IO[A]
  ): IO[A] =
    for
      checkpoints <- Queue.unbounded[IO, Deferred[IO, Unit]]
      stop <- Deferred[IO, Unit]
      settle = Deferred[IO, Unit].flatMap(ack => checkpoints.offer(ack) *> ack.get)
      simulation <- simulated(seed, IO.race(body(settle), stop.get))
      (ctx, runtime, results) = simulation
      result <- Resource
        .makeCase(IO.unit) { (_, exit) =>
          (stop.complete(()).void *> IO(drain(ctx, steps)) <* IO(runtime.shutdown())).flatMap {
            case false if exit == Resource.ExitCase.Succeeded =>
              IO.raiseError(
                new AssertionError(
                  s"the test's cleanup did not end within $steps tasks a step and $CleanupMoves " +
                    "moves of the clock: a fiber outlives the test"
                )
              )
            case _ => IO.unit
          }
        }
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
          val ran: IO[Unit] = IO(quiesce(ctx, steps)).flatMap(settled =>
            if settled then IO.unit
            else
              IO.raiseError(
                new AssertionError(
                  s"the test ran $steps tasks without every fiber blocked: a fiber never waits"
                )
              )
          )
          val stuck = new AssertionError(
            "the test neither ended nor settled: every fiber is blocked" +
              (if timed then " and no timer is set" else "")
          )
          Monad[IO].tailRecM(()) { _ =>
            ran *> uncaught *> checkpoints.tryTake.flatMap {
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
