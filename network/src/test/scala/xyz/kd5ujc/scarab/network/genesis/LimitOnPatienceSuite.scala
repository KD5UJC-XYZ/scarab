// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network.genesis

import cats.effect.{IO, Ref}
import cats.effect.testkit.TestControl
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.network.FailsOnUncaught

class LimitOnPatienceSuite extends FailsOnUncaught:
  private val config = LeakyBucket.Config(Rational(10), Rational.one)

  test("only new record-high validated blocks replenish; rollback replay does not"):
    TestControl.executeEmbed {
      LimitOnPatience.resource[IO](config, IO.unit).use { patience =>
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
      LimitOnPatience.resource[IO](config, IO.unit).use { patience =>
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
        LimitOnPatience.resource[IO](LeakyBucket.Config.disabled, expired.update(_ + 1)).use {
          patience =>
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
        LimitOnPatience.resource[IO](config, expired.update(_ + 1)).use(_ => IO.sleep(1.second)) *>
          IO.sleep(100.seconds) *> expired.get.map(n => assertEquals(n, 0))
      }
    }

  test("GSM reset retains record high across disabled and enabled phases"):
    TestControl.executeEmbed {
      LimitOnPatience.resource[IO](config, IO.unit).use { patience =>
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
