// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network.genesis

import cats.effect.{Deferred, IO, Ref}
import cats.effect.testkit.TestControl
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.network.{FailsOnUncaught, NetworkError}
import cats.mtl.Raise

class LimitOnPatienceSuite extends FailsOnUncaught:
  private val config = LeakyBucket.Config(Rational(10), Rational.one)

  test("only new record-high validated blocks replenish; rollback replay does not"):
    TestControl.executeEmbed {
      LimitOnPatience.resource[IO](config, IO.unit, error => IO(fail(error.message))).use {
        patience =>
          for
            _ <- IO.sleep(2.seconds)
            _ <- patience.validated(5)
            _ <- patience.validated(4)
            _ <- patience.validated(5)
            state <- patience.snapshot
          yield assertEquals(state.level, Rational(9))
      }
    }

  test("independent pause reasons cannot prematurely resume each other"):
    TestControl.executeEmbed {
      LimitOnPatience.resource[IO](config, IO.unit, error => IO(fail(error.message))).use {
        patience =>
          for
            _ <- patience.setPaused(LimitOnPatience.PauseReason.Forecast, true)
            _ <- patience.setPaused(LimitOnPatience.PauseReason.AwaitReply, true)
            _ <- patience.setPaused(LimitOnPatience.PauseReason.Forecast, false)
            _ <- IO.sleep(100.seconds)
            paused <- patience.snapshot
            _ <- patience.setPaused(LimitOnPatience.PauseReason.AwaitReply, false)
            _ <- IO.sleep(2.seconds)
            running <- patience.snapshot
          yield
            assertEquals(paused.level, Rational(10))
            assertEquals(running.level, Rational(8))
      }
    }

  test("reset wakes the timer immediately and the disabled phase does not expire"):
    TestControl.executeEmbed {
      Ref.of[IO, Int](0).flatMap { expired =>
        LimitOnPatience
          .resource[IO](
            LeakyBucket.Config.disabled,
            expired.update(_ + 1),
            error => IO(fail(error.message))
          )
          .use { patience =>
            for
              _ <- IO.sleep(100.seconds)
              before <- expired.get
              _ <- patience.configure(config)
              _ <- IO.sleep(11.seconds)
              after <- expired.get
              _ <- patience.configure(config)
              _ <- IO.sleep(11.seconds)
              again <- expired.get
            yield
              assertEquals(before, 0)
              assertEquals(after, 1)
              assertEquals(again, 2)
          }
      }
    }

  test("resource cancellation cancels the timer without a late empty callback"):
    TestControl.executeEmbed {
      Ref.of[IO, Int](0).flatMap { expired =>
        LimitOnPatience
          .resource[IO](config, expired.update(_ + 1), error => IO(fail(error.message)))
          .use(_ => IO.sleep(1.second)) *>
          IO.sleep(100.seconds) *> expired.get.map(n => assertEquals(n, 0))
      }
    }

  test("GSM reset retains record high across disabled and enabled phases"):
    TestControl.executeEmbed {
      LimitOnPatience.resource[IO](config, IO.unit, error => IO(fail(error.message))).use {
        patience =>
          for
            _ <- patience.validated(10)
            _ <- patience.configure(LeakyBucket.Config.disabled)
            _ <- patience.configure(config)
            _ <- IO.sleep(2.seconds)
            _ <- patience.validated(10)
            state <- patience.snapshot
          yield assertEquals(state.level, Rational(8))
      }
    }

  test("an accepted CSJ jump advances the record without granting a token"):
    TestControl.executeEmbed {
      LimitOnPatience.resource[IO](config, IO.unit, error => IO(fail(error.message))).use {
        patience =>
          for
            _ <- IO.sleep(2.seconds)
            _ <- patience.jumpedTo(10)
            _ <- patience.validated(10)
            replayed <- patience.snapshot
            _ <- patience.validated(11)
            progressed <- patience.snapshot
          yield
            assertEquals(replayed.level, Rational(8))
            assertEquals(progressed.level, Rational(9))
      }
    }

  test("pauses retain the native timer and its positive sub-microsecond expiry"):
    TestControl.executeEmbed {
      Ref.of[IO, Vector[FiniteDuration]](Vector.empty).flatMap { expired =>
        val tiny = LeakyBucket.Config(Rational(3, 2000000), Rational.one)
        val refill = LeakyBucket.Config(Rational(10), Rational(1000000))
        LimitOnPatience
          .resource[IO](
            tiny,
            IO.monotonic.flatMap(t => expired.update(_ :+ t)),
            error => IO(fail(error.message))
          )
          .use { patience =>
            for
              _ <- IO.cede
              // Initial wake is 1us. Pausing at 0.5us leaves 1us of tokens, resuming at
              // 0.6us must retain that wake: its 0.6us remainder expires at 1us, not 1.6us.
              _ <- IO.sleep(500.nanos)
              _ <- patience.setPaused(LimitOnPatience.PauseReason.AwaitReply, true)
              _ <- IO.sleep(100.nanos)
              _ <- patience.setPaused(LimitOnPatience.PauseReason.AwaitReply, false)
              _ <- IO.sleep(1.microsecond)
              times <- expired.get
              _ <- patience.configure(refill)
            yield assertEquals(times, Vector(1.microsecond))
          }
      }
    }

  test("injected picosecond clock retains rational-rate microsecond rounding before sleeping") {
    TestControl.executeEmbed {
      val clock = new PicosClock[IO]:
        def now: IO[BigInt] = IO.monotonic.map(t => BigInt(t.toNanos))
        def sleep(delayPicos: BigInt): IO[Unit] = IO.sleep(delayPicos.toLong.nanos)
      // 47/4 tokens/s, capacity 47/4,000,000 => exactly one microsecond.
      val exact = LeakyBucket.Config(Rational(47, 4000000), Rational(47, 4))
      for
        fired <- Ref.of[IO, Vector[BigInt]](Vector.empty)
        _ <- LimitOnPatience
          .resource[IO](
            exact,
            clock.now.flatMap(now => fired.update(_ :+ now)),
            error => IO(fail(error.message)),
            Some(clock)
          )
          .use { patience =>
            for
              _ <- IO.sleep(499999.nanos)
              before <- patience.snapshot
              _ <- IO(
                assertEquals(
                  before.level,
                  exact.capacity - exact.rate * Rational(499999, BigInt(10).pow(12))
                )
              )
              _ <- IO.sleep(500002.nanos)
              seen <- fired.get
              _ <- IO(assertEquals(seen, Vector(BigInt(1000000))))
            yield ()
          }
      yield ()
    }
  }

  test("a typed empty callback failure reaches its owner from the watcher scope") {
    TestControl.executeEmbed {
      for
        failed <- Deferred[IO, NetworkError]
        cause = NetworkError.Timeout(2, "patience-test")
        _ <- LimitOnPatience
          .resource[IO](
            config,
            summon[Raise[IO, NetworkError]].raise[NetworkError, Unit](cause),
            error => failed.complete(error).void
          )
          .use(_ => failed.get.map(actual => assertEquals(actual, cause)))
      yield ()
    }
  }

  test("a watcher clock defect reaches its owner rather than silently stopping the timer") {
    TestControl.executeEmbed {
      val defect = new IllegalStateException("injected patience clock defect")
      for
        failed <- Deferred[IO, NetworkError]
        reads <- Ref.of[IO, Int](0)
        clock = new PicosClock[IO]:
          def now = reads.getAndUpdate(_ + 1).flatMap { count =>
            if count == 0 then IO.pure(BigInt(0)) else IO.raiseError[BigInt](defect)
          }
          def sleep(delayPicos: BigInt) = IO.never[Unit]
        _ <- LimitOnPatience
          .resource[IO](config, IO.unit, error => failed.complete(error).void, Some(clock))
          .use(_ => failed.get.map(actual => assertEquals(actual, NetworkError.Defect(defect))))
      yield ()
    }
  }
