// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*
import scala.concurrent.duration.*

/** What [[Settled]] promises the suites that synchronize on it (issue 870). */
class SettledSuite extends FailsOnUncaught:

  private def failure(run: IO[?]): IO[String] =
    run.attempt.map {
      case Left(e: AssertionError) => e.getMessage
      case other                   => fail(s"expected an assertion failure, got $other")
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
    failure(Settled(_ => IO.raiseError[Unit](new IllegalStateException("lost")).start.void))
      .map(message => assert(message.contains("uncaught fiber failure"), message))
  }

  test("a body that waits for ever fails as stuck instead of hanging") {
    failure(Settled(_ => IO.never[Unit]))
      .map(message => assert(message.contains("neither ended nor settled"), message))
  }

  test("without timed, a timer does not fire: the body is stuck") {
    failure(Settled(_ => IO.never[Int].timeoutTo(1.hour, IO.pure(1))))
      .map(message => assert(message.contains("neither ended nor settled"), message))
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
