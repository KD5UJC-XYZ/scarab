// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.IO
import scala.concurrent.duration.*

/** Condition waits for tests that must observe a background fiber reaching a state.
  *
  * A fixed `IO.sleep` is a bet on scheduling: it fails on a loaded runner, and it hides a real
  * stall behind the same observable. This polls the state instead, within a deadline, and fails
  * with the last value observed so the failure says what the state was rather than that time
  * passed.
  */
object Wait:

  /** Polls `state` until `holds`, or fails with the last observed value after `timeout`. */
  def until[A](
      state: IO[A],
      timeout: FiniteDuration = 10.seconds,
      interval: FiniteDuration = 20.millis
  )(holds: A => Boolean): IO[A] =
    IO.monotonic.flatMap { started =>
      def loop: IO[A] =
        state.flatMap { a =>
          if holds(a) then IO.pure(a)
          else
            IO.monotonic.flatMap { now =>
              if now - started >= timeout then
                IO.raiseError(
                  new AssertionError(s"condition not met within $timeout; last observed: $a")
                )
              else IO.sleep(interval) *> loop
            }
        }
      loop
    }
