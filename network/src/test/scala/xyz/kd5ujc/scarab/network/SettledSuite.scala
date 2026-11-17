// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*
import scala.concurrent.duration.*

/** What [[Settled]] promises the suites that synchronize on it (issue 870). */
class SettledSuite extends FailsOnUncaught:

  private def failure(run: IO[?]): IO[Settled.Failure] =
    run.attempt.map {
      case Left(e: Settled.Failed) => e.failure
      case other                   => fail(s"expected a failure of Settled, got $other")
    }

  test("settle returns once a started fiber has done all it can and waits") {
    Settled { settle =>
      for
        steps <- Ref.of[IO, Int](0)
        gate <- Deferred[IO, Unit]
        worker <- ((1 to 1000).toList.traverse_(_ => steps.update(_ + 1)) *> gate.get).start
        _ <- settle
        seen <- steps.get
        _ <- gate.complete(())
        _ <- worker.joinWithNever
      yield seen
    }.assertEquals(1000)
  }

  test("a fiber that ends with an error nobody observes fails the test") {
    val lost = new IllegalStateException("lost")
    failure(Settled(_ => IO.raiseError[Unit](lost).start.void)).map {
      case Settled.Failure.UncaughtFiber(cause) => assert(cause eq lost, cause.toString)
      case other                                => fail(s"unexpected $other")
    }
  }

  test("a body that waits for ever fails as stuck instead of hanging") {
    failure(Settled(_ => IO.never[Unit])).assertEquals(Settled.Failure.Stuck(timed = false))
  }

  test("without timed, a timer does not fire: the body is stuck") {
    failure(Settled(_ => IO.never[Int].timeoutTo(1.hour, IO.pure(1))))
      .assertEquals(Settled.Failure.Stuck(timed = false))
  }

  test("timed moves the clock to the next timer once every fiber is blocked") {
    Settled
      .timed(_ =>
        (IO.monotonic, IO.never[Int].timeoutTo(1.hour, IO.pure(1)), IO.monotonic)
          .mapN((before, value, after) => (value, after - before))
      )
      .assertEquals((1, 1.hour))
  }

  test("a settle does not move the clock, even under timed") {
    Settled
      .timed(settle =>
        (IO.monotonic, settle, IO.monotonic).mapN((before, _, after) => after - before)
      )
      .assertEquals(Duration.Zero)
  }

  test("a seed replays the order in which the runnable fibers ran") {
    def order(seed: String): IO[Vector[Int]] =
      Settled.seeded(seed) { settle =>
        Ref.of[IO, Vector[Int]](Vector.empty).flatMap { log =>
          (1 to 8).toList.traverse_(n => log.update(_ :+ n).start) *> settle *> log.get
        }
      }
    (order("NDI="), order("NDI=")).mapN((first, again) =>
      assertEquals(first, again)
      assertEquals(first.sorted, (1 to 8).toVector)
    )
  }

  test("a fiber that outlives the body fails the test once its cleanup reaches the limit") {
    // a ticker on the simulated clock, which only the cleanup moves
    failure(Settled(_ => IO.never[Unit].timeoutTo(1.second, IO.unit).foreverM.start.void))
      .assertEquals(Settled.Failure.OutlivesTest(Settled.StepLimit, Settled.CleanupMoves))
  }

  test("a fiber that never waits fails the test within the step limit, and is cancelled") {
    for
      cancelled <- Deferred[IO, Unit]
      failed <- failure(
        Settled.limited(10000)(_ => IO.unit.foreverM.onCancel(cancelled.complete(()).void))
      )
      stopped <- cancelled.tryGet
    yield
      assertEquals(failed, Settled.Failure.NeverWaits(10000))
      assertEquals(stopped, Some(()))
  }

  test(
    "a fiber that never waits and cannot be cancelled is left once its cleanup reaches the limit"
  ) {
    failure(Settled.limited(10000)(_ => IO.uncancelable(_ => IO.unit.foreverM)))
      .assertEquals(Settled.Failure.NeverWaits(10000))
  }
