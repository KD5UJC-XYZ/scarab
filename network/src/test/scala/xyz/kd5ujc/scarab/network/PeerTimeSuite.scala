// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{Deferred, IO, Resource}
import cats.effect.testkit.TestControl
import scala.concurrent.duration.*

class PeerTimeSuite extends FailsOnUncaught:
  test("overlapping local callbacks exclude their union rather than their summed durations") {
    TestControl.executeEmbed {
      for
        clock <- PeerTime.create[IO]
        entered <- Deferred[IO, Unit]
        _ <- Resource
          .make(clock.local(entered.complete(()).void *> IO.sleep(2.seconds)).start)(_.cancel)
          .use { first =>
            entered.get *> IO.sleep(1.second) *>
              clock.local(IO.sleep(2.seconds)) *> first.join
          }
        _ <- IO.sleep(1.second)
        elapsed <- clock.now
      yield assertEquals(elapsed, 1.second)
    }
  }

  test("canceling a local callback resumes peer time and leaves no paused timer") {
    TestControl.executeEmbed {
      for
        clock <- PeerTime.create[IO]
        entered <- Deferred[IO, Unit]
        _ <- Resource
          .make(clock.local(entered.complete(()).void *> IO.never[Unit]).start)(_.cancel)
          .use { local =>
            entered.get *> IO.sleep(2.seconds) *> local.cancel
          }
        _ <- IO.sleep(2.seconds)
        elapsed <- clock.now
      yield assertEquals(elapsed, 2.seconds)
    }
  }
