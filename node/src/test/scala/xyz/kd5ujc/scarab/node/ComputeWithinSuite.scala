// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.IO
import cats.effect.syntax.all.*
import cats.effect.unsafe.IORuntimeConfig
import cats.mtl.Handle
import cats.mtl.syntax.all.*
import xyz.kd5ujc.scarab.consensus.{Compute, SchedulingStats}
import scala.concurrent.duration.*

/** A typed failure raised inside [[Compute.within]] on the CPU pool reaches the caller's handler
  * and nothing else: the pool fiber ends with a value, so the runtime has no unobserved failure to
  * report even when that fiber ends before its caller joins it (issue 553, where a refused forged
  * block printed a bare `cats.mtl.Handle$Submarine`).
  */
class ComputeWithinSuite extends xyz.kd5ujc.scarab.network.FailsOnUncaught:
  override def munitIOTimeout: FiniteDuration = 2.minutes

  // A fiber yields after every other step, so the caller is often rescheduled between starting the
  // pool fiber and joining it, and the pool fiber ends first: in production a yield at that point
  // is rare (one in `autoYieldThreshold` steps), which is why the report was rare.
  override protected def runtimeConfig: IORuntimeConfig = IORuntimeConfig(1, 2)

  private enum Refused:
    case Block(n: Int)

  private val rounds = 2000

  test("a typed failure inside the pool is the caller's alone, however early the pool fiber ends") {
    SchedulingStats.create[IO].flatMap { stats =>
      Compute.pool[IO](2, stats).use { cpu =>
        (1 to rounds).toList
          .parTraverseN(16) { n =>
            Handle
              .allow[Refused] {
                cpu.within(Refused.Block(n).raise[IO, Int])
              }
              .rescue { case Refused.Block(m) =>
                IO.pure(-m)
              }
          }
          .map(results => assertEquals(results, (1 to rounds).toList.map(-_)))
      }
    }
  }

  test("a defect inside the pool is raised in the caller, unreported by the runtime") {
    val defect = new IllegalStateException("injected pool defect")
    SchedulingStats.create[IO].flatMap { stats =>
      Compute.pool[IO](2, stats).use { cpu =>
        (1 to rounds).toList
          .parTraverseN(16)(_ => cpu.within(IO.raiseError[Int](defect)).attempt)
          .map(results => assertEquals(results, List.fill(rounds)(Left(defect))))
      }
    }
  }
