// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.std.Supervisor
import cats.effect.testkit.TestControl
import cats.syntax.all.*

/** The release order of [[OrderedSupervisor]]: youngest child first, each awaited before the next,
  * observed through a log and with no sleeps.
  */
class OrderedSupervisorSuite extends FailsOnUncaught:
  private enum Event:
    case Started(child: Int)
    case Cancelling(child: Int)
    case Cancelled(child: Int)
    case Finished(child: Int)

  /** A child that logs its start, then waits for ever; on cancellation it logs the start of its
    * finalizer, yields so that a concurrent canceller would interleave, and logs the end.
    */
  private def child(log: Ref[IO, Vector[Event]], n: Int, started: Deferred[IO, Unit]): IO[Unit] =
    (log.update(_ :+ Event.Started(n)) *> started.complete(()) *> IO.never[Unit])
      .onCancel(
        log.update(_ :+ Event.Cancelling(n)) *> IO.cede *> log.update(_ :+ Event.Cancelled(n))
      )

  test("children are cancelled youngest first, one after the other") {
    TestControl.executeEmbed {
      for
        log <- Ref.of[IO, Vector[Event]](Vector.empty)
        started <- List.fill(5)(Deferred[IO, Unit]).sequence
        _ <- OrderedSupervisor[IO].use { supervisor =>
          started.zipWithIndex.traverse_((ready, n) =>
            supervisor.supervise(child(log, n, ready))
          ) *>
            started.traverse_(_.get)
        }
        events <- log.get
      yield assertEquals(
        events.drop(5),
        Vector.tabulate(5)(i => 4 - i).flatMap(n => Vector(Event.Cancelling(n), Event.Cancelled(n)))
      )
    }
  }

  test("a child that ended is not cancelled and later children keep their order") {
    TestControl.executeEmbed {
      for
        log <- Ref.of[IO, Vector[Event]](Vector.empty)
        started <- List.fill(3)(Deferred[IO, Unit]).sequence
        _ <- OrderedSupervisor[IO].use { supervisor =>
          for
            _ <- supervisor.supervise(child(log, 0, started(0)))
            _ <- started(0).get
            quick <- supervisor.supervise(
              log.update(_ :+ Event.Finished(1)).onCancel(log.update(_ :+ Event.Cancelling(1)))
            )
            _ <- quick.join
            _ <- supervisor.supervise(child(log, 2, started(2)))
            _ <- started(2).get
          yield ()
        }
        events <- log.get
      yield assertEquals(
        events,
        Vector(
          Event.Started(0),
          Event.Finished(1),
          Event.Started(2),
          Event.Cancelling(2),
          Event.Cancelled(2),
          Event.Cancelling(0),
          Event.Cancelled(0)
        )
      )
    }
  }

  /** The registry size once it has reached `expected`; a registry that never does is a test that
    * does not terminate, which `TestControl` reports.
    */
  private def settled(size: IO[Int], expected: Int): IO[Int] =
    (IO.cede *> size).iterateUntil(_ == expected)

  test("a child cancelled before its first step leaves the registry while the supervisor is open") {
    TestControl.executeEmbed {
      OrderedSupervisor.observed[IO].use { (supervisor, size) =>
        for
          ran <- Ref.of[IO, Boolean](false)
          fiber <- supervisor.supervise(ran.set(true))
          // Nothing has been scheduled since `supervise` returned: the child has not taken a step.
          _ <- fiber.cancel
          outcome <- fiber.join
          held <- settled(size, 0)
          didRun <- ran.get
        yield
          assert(outcome.isCanceled)
          assertEquals((held, didRun), (0, false))
      }
    }
  }

  test("a child that ends or is cancelled leaves the registry") {
    TestControl.executeEmbed {
      OrderedSupervisor.observed[IO].use { (supervisor, size) =>
        for
          ready <- Deferred[IO, Unit]
          done <- supervisor.supervise(IO.unit)
          failed <- supervisor.supervise(IO.raiseError[Unit](new IllegalStateException("child")))
          waiting <- supervisor.supervise(ready.complete(()) *> IO.never[Unit])
          _ <- ready.get
          _ <- done.join *> failed.join
          before <- settled(size, 1)
          _ <- waiting.cancel
          after <- settled(size, 0)
        yield assertEquals((before, after), (1, 0))
      }
    }
  }

  test("supervising concurrently with the release leaves no running child") {
    TestControl.executeEmbed {
      for
        allocated <- OrderedSupervisor[IO].allocated
        (supervisor, release) = allocated
        attempts <- List
          .tabulate(20)(_ => supervisor.supervise(IO.never[Unit]).attempt.start)
          .sequence
        closing <- release.start
        results <- attempts.traverse(_.joinWithNever)
        _ <- closing.joinWithNever
        outcomes <- results.traverse {
          case Right(fiber) => fiber.join.map(_.isCanceled.asRight[String])
          case Left(error)  => IO.pure(error.getMessage.asLeft[Boolean])
        }
      yield assert(
        outcomes.forall {
          case Right(canceled) => canceled
          case Left(message)   => message == "supervisor already shutdown"
        },
        clue(outcomes)
      )
    }
  }

  /** The contract that `supervise` shares with the supervisor of cats-effect, run on both. */
  private val implementations: List[(String, Resource[IO, Supervisor[IO]])] =
    List(
      "OrderedSupervisor" -> OrderedSupervisor[IO],
      "Supervisor" -> Supervisor[IO](await = false)
    )

  for (name, make) <- implementations do
    test(s"$name: a failing child leaves its error in its fiber and stops no sibling") {
      TestControl.executeEmbed {
        for
          siblingCancelled <- Ref.of[IO, Boolean](false)
          ready <- Deferred[IO, Unit]
          outcome <- make.use { supervisor =>
            for
              _ <- supervisor.supervise(
                (ready.complete(()) *> IO.never[Unit]).onCancel(siblingCancelled.set(true))
              )
              failing <- supervisor.supervise(
                IO.raiseError[Unit](new IllegalStateException("child"))
              )
              outcome <- failing.join
              _ <- ready.get
              before <- siblingCancelled.get
            yield (outcome.isError, before)
          }
          atRelease <- siblingCancelled.get
        yield assertEquals((outcome, atRelease), ((true, false), true))
      }
    }

    test(s"$name: supervising after the release fails and starts no work") {
      TestControl.executeEmbed {
        for
          ran <- Ref.of[IO, Boolean](false)
          late <- make.use(IO.pure)
          result <- late.supervise(ran.set(true)).attempt
          didRun <- ran.get
        yield
          assertEquals(result.left.map(_.getMessage), Left("supervisor already shutdown"))
          assert(!didRun)
      }
    }
