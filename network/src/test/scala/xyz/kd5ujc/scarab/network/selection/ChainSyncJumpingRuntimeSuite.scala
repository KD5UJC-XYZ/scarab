// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network.selection

import cats.effect.IO
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.network.FailsOnUncaught
import xyz.kd5ujc.scarab.protocol.ChainSync.{Point, UInt64}
import xyz.kd5ujc.scarab.codec.Bytes

class ChainSyncJumpingRuntimeSuite extends FailsOnUncaught:
  import ChainSyncJumping.*
  private def info(n: Int): JumpInfo[Unit] = JumpInfo(
    Point.Origin,
    (1 to n).toVector.map(i => Point.Block(UInt64.saturating(i), Bytes.fromArray(Array(i.toByte)))),
    ()
  )

  test("jumper waits without holding a registry lock; dynamo publication wakes it") {
    for
      runtime <- ChainSyncJumpingRuntime.create[IO, Int, Unit](4)
      result <- runtime.session(1, Point.Origin, false).use { dynamo =>
        runtime.session(2, Point.Origin, false).use { jumper =>
          for
            waiting <- jumper.next.start
            _ <- IO.cede
            _ <- dynamo.update(info(4))
            _ <- dynamo.onRollForward(info(5).tip)
            jump <- waiting.joinWithNever.timeout(2.seconds)
          yield jump
        }
      }
      _ <- IO(assertEquals(result, Instruction.Jump(info(4), false)))
      empty <- runtime.snapshot
      _ <- IO(assertEquals(empty.peers.size, 0))
    yield ()
  }
  test("cancelling a blocked jumper permits resource cleanup and later registration") {
    for
      runtime <- ChainSyncJumpingRuntime.create[IO, Int, Unit]()
      _ <- runtime.session(1, Point.Origin, false).use { _ =>
        runtime.session(2, Point.Origin, false).use { jumper =>
          jumper.next.start.flatMap(fiber => IO.cede *> fiber.cancel)
        }
      }
      result <- runtime.session(3, Point.Origin, true).use(_.next)
      _ <- IO(assertEquals(result, Instruction.RunNormally))
    yield ()
  }
  test("removing the dynamo wakes a waiting jumper as the next dynamo") {
    for
      runtime <- ChainSyncJumpingRuntime.create[IO, Int, Unit]()
      dynamo <- runtime.session(1, Point.Origin, false).allocated
      result <- runtime
        .session(2, Point.Origin, false)
        .use { jumper =>
          for
            waiting <- jumper.next.start
            _ <- IO.cede *> dynamo._2
            result <- waiting.joinWithNever.timeout(2.seconds)
          yield result
        }
        .guarantee(dynamo._2)
      _ <- IO(assertEquals(result, Instruction.RunNormally))
    yield ()
  }
