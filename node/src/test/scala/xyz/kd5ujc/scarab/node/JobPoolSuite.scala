// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Deferred, IO, Ref}
import cats.effect.std.{Queue, Supervisor}
import xyz.kd5ujc.scarab.network.FailsOnUncaught

/** A job of the pool ends by handing over exactly one value: its action's, or its handler's when
  * the action raises, or its fallback's when the handler raises too; a cancelled job hands over
  * nothing and its handler does not run, as the reference's `forkJob'` gives no asynchronous
  * exception to its handler (network-mux c45735a5 `Control/Concurrent/JobPool.hs` 98-113).
  */
class JobPoolSuite extends FailsOnUncaught:
  private final class Raised extends RuntimeException("the job raised")

  private enum Completion:
    case Returned
    case Handled(cause: Throwable)
    case Fallen(cause: Throwable)

  /** Run `job` in a pool that completes to a queue, and give whether its fiber was cancelled,
    * everything handed over, and the exceptions its handler was given.
    */
  private def fork(
      action: IO[Completion],
      handler: Throwable => IO[Completion] = cause => IO.pure(Completion.Handled(cause))
  ): IO[(Boolean, List[Completion], List[Throwable])] =
    Supervisor[IO](await = false).use { supervisor =>
      for
        completions <- Queue.unbounded[IO, Completion]
        handled <- Ref.of[IO, List[Throwable]](Nil)
        pool = JobPool[IO, Completion](supervisor, completions.offer)
        fiber <- pool.forkJob(
          JobPool.Job(
            action,
            cause => handled.update(cause :: _) *> handler(cause),
            cause => Completion.Fallen(cause)
          )
        )
        outcome <- fiber.join
        handed <- completions.tryTakeN(None)
        seen <- handled.get
      yield (outcome.isCanceled, handed, seen)
    }

  test("a job that returns hands over its action's value, once") {
    fork(IO.pure(Completion.Returned)).map { (cancelled, handed, seen) =>
      assertEquals(cancelled, false)
      assertEquals(handed, List(Completion.Returned))
      assertEquals(seen, Nil)
    }
  }

  test("a job that raises hands over its handler's value for that exception, once") {
    val raised = new Raised
    fork(IO.raiseError(raised)).map { (cancelled, handed, seen) =>
      assertEquals(cancelled, false)
      assertEquals(handed, List(Completion.Handled(raised)))
      assertEquals(seen, List(raised))
    }
  }

  test("a job whose handler raises too hands over its fallback for the job's exception, once") {
    val raised = new Raised
    fork(IO.raiseError(raised), _ => IO.raiseError(new Raised)).map { (cancelled, handed, seen) =>
      assertEquals(cancelled, false)
      assertEquals(handed, List(Completion.Fallen(raised)))
      assertEquals(seen, List(raised))
    }
  }

  test("a job cancelled while it runs hands over nothing, and its handler does not run") {
    for
      entered <- Deferred[IO, Unit]
      result <- Supervisor[IO](await = false).use { supervisor =>
        for
          completions <- Queue.unbounded[IO, Completion]
          handled <- Ref.of[IO, List[Throwable]](Nil)
          fiber <- JobPool[IO, Completion](supervisor, completions.offer).forkJob(
            JobPool.Job(
              entered.complete(()) *> IO.never[Completion],
              cause => handled.update(cause :: _).as(Completion.Handled(cause)),
              cause => Completion.Fallen(cause)
            )
          )
          _ <- entered.get
          _ <- fiber.cancel
          outcome <- fiber.join
          handed <- completions.tryTakeN(None)
          seen <- handled.get
        yield (outcome.isCanceled, handed, seen)
      }
    yield
      val (cancelled, handed, seen) = result
      assertEquals(cancelled, true)
      assertEquals(handed, Nil)
      assertEquals(seen, Nil)
  }

  test("a job that cancels itself hands over nothing, and its handler does not run") {
    fork(IO.canceled *> IO.pure(Completion.Returned)).map { (cancelled, handed, seen) =>
      assertEquals(cancelled, true)
      assertEquals(handed, Nil)
      assertEquals(seen, Nil)
    }
  }

  // the handoff after the action returns is masked, as `forkJob'` writes the completion queue
  test("a job cancelled once its action returned still hands over its value") {
    for
      returned <- Deferred[IO, Unit]
      release <- Deferred[IO, Unit]
      result <- Supervisor[IO](await = false).use { supervisor =>
        for
          completions <- Queue.unbounded[IO, Completion]
          handing = (value: Completion) =>
            returned.complete(()) *> release.get *> completions.offer(value)
          fiber <- JobPool[IO, Completion](supervisor, handing).forkJob(
            JobPool.Job.pure(IO.pure(Completion.Returned), cause => Completion.Handled(cause))
          )
          _ <- returned.get
          cancelled <- fiber.cancel.start
          _ <- release.complete(())
          _ <- cancelled.join
          handed <- completions.tryTakeN(None)
        yield handed
      }
    yield assertEquals(result, List(Completion.Returned))
  }

  test("a pool completing elsewhere hands its jobs' values there, its handler's included") {
    val raised = new Raised
    Supervisor[IO](await = false).use { supervisor =>
      for
        first <- Queue.unbounded[IO, Completion]
        other <- Queue.unbounded[IO, Int]
        pool = JobPool[IO, Completion](supervisor, first.offer)
        fiber <- pool
          .completingTo[Int](other.offer)
          .forkJob(JobPool.Job.pure(IO.raiseError[Int](raised), _ => 7))
        _ <- fiber.join
        values <- other.tryTakeN(None)
        handed <- first.tryTakeN(None)
      yield
        assertEquals(values, List(7))
        assertEquals(handed, Nil)
    }
  }
