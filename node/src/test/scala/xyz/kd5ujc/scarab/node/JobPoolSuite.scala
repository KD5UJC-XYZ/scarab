// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Deferred, IO, Ref}
import cats.effect.std.{Queue, Supervisor}
import xyz.kd5ujc.scarab.network.FailsOnUncaught

/** A job of the pool ends by handing over its action's value, or its handler's when the action
  * raises; a cancelled job hands over nothing and its handler does not run, as the reference's
  * `forkJob'` gives no asynchronous exception to its handler (network-mux c45735a5
  * `Control/Concurrent/JobPool.hs` 98-113).
  */
class JobPoolSuite extends FailsOnUncaught:
  private final class Raised extends RuntimeException("the job raised")

  private enum Completion:
    case Returned
    case Handled(cause: Throwable)

  /** Fork `action` in a pool that completes to a queue, and give whether its fiber was cancelled,
    * what was handed over, and the exceptions its handler was given.
    */
  private def fork(action: IO[Completion]): IO[(Boolean, Option[Completion], List[Throwable])] =
    Supervisor[IO](await = false).use { supervisor =>
      for
        completions <- Queue.unbounded[IO, Completion]
        handled <- Ref.of[IO, List[Throwable]](Nil)
        pool = JobPool[IO, Completion](supervisor, completions.offer)
        fiber <- pool.forkJob(
          JobPool.Job(
            action,
            cause => handled.update(cause :: _).as(Completion.Handled(cause))
          )
        )
        outcome <- fiber.join
        handed <- completions.tryTake
        seen <- handled.get
      yield (outcome.isCanceled, handed, seen)
    }

  test("a job that returns hands over its action's value") {
    fork(IO.pure(Completion.Returned)).map { (cancelled, handed, seen) =>
      assertEquals(cancelled, false)
      assertEquals(handed, Some(Completion.Returned))
      assertEquals(seen, Nil)
    }
  }

  test("a job that raises hands over its handler's value for that exception") {
    val raised = new Raised
    fork(IO.raiseError(raised)).map { (cancelled, handed, seen) =>
      assertEquals(cancelled, false)
      assertEquals(handed, Some(Completion.Handled(raised)))
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
              cause => handled.update(cause :: _).as(Completion.Handled(cause))
            )
          )
          _ <- entered.get
          _ <- fiber.cancel
          outcome <- fiber.join
          handed <- completions.tryTake
          seen <- handled.get
        yield (outcome.isCanceled, handed, seen)
      }
    yield
      val (cancelled, handed, seen) = result
      assertEquals(cancelled, true)
      assertEquals(handed, None)
      assertEquals(seen, Nil)
  }

  test("a job that cancels itself hands over nothing, and its handler does not run") {
    fork(IO.canceled *> IO.pure(Completion.Returned)).map { (cancelled, handed, seen) =>
      assertEquals(cancelled, true)
      assertEquals(handed, None)
      assertEquals(seen, Nil)
    }
  }

  test("a pool completing elsewhere hands its jobs' values there, its handler's included") {
    val raised = new Raised
    Supervisor[IO](await = false).use { supervisor =>
      for
        first <- Queue.unbounded[IO, Completion]
        other <- Deferred[IO, Int]
        pool = JobPool[IO, Completion](supervisor, first.offer)
        fiber <- pool
          .completingTo[Int](other.complete(_).void)
          .forkJob(JobPool.Job(IO.raiseError[Int](raised), _ => IO.pure(7)))
        _ <- fiber.join
        value <- other.get
        handed <- first.tryTake
      yield
        assertEquals(value, 7)
        assertEquals(handed, None)
    }
  }
