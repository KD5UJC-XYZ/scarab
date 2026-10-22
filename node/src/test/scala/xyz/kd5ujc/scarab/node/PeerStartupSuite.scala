// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.std.Supervisor
import cats.effect.testkit.TestControl
import xyz.kd5ujc.scarab.network.FailsOnUncaught
import scala.concurrent.duration.*

class PeerStartupSuite extends FailsOnUncaught:
  test("canceling startup cannot leave a started pipeline without its producer") {
    TestControl.executeEmbed {
      Supervisor[IO](await = false).use { supervisor =>
        for
          started <- Ref.of[IO, Boolean](false)
          observerRegistered <- Deferred[IO, Unit]
          continueRegistration <- Deferred[IO, Unit]
          producerRegistered <- Ref.of[IO, Int](0)
          drained <- Deferred[IO, Unit]
          register =
            supervisor.supervise(IO.never[Unit]).void *>
              observerRegistered.complete(()).void *> continueRegistration.get *>
              supervisor.supervise(drained.complete(()).void).void *>
              producerRegistered.update(_ + 1)
          _ <- Resource
            .make(Peer.startOnce(started)(register).start)(fiber =>
              continueRegistration.complete(()).void *> fiber.cancel
            )
            .use { fiber =>
              for
                _ <- observerRegistered.get
                _ <- Resource.make(fiber.cancel.start)(_.cancel).use { cancellation =>
                  // Exhaust runnable cancellation work in the controlled scheduler before
                  // allowing the remaining registration. No wall-clock timing is involved.
                  IO.sleep(1.second) *> continueRegistration.complete(()).void *> cancellation.join
                }
                _ <- Peer.startOnce(started)(register)
                count <- producerRegistered.get
                _ <- IO(assertEquals(count, 1))
                _ <- drained.get
              yield ()
            }
        yield ()
      }
    }
  }
