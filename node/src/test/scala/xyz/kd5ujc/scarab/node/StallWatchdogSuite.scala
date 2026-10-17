// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{IO, Ref}
import cats.effect.testkit.TestControl
import munit.CatsEffectSuite
import scala.concurrent.duration.*

import StallWatchdog.Change

class StallWatchdogSuite extends CatsEffectSuite:
  private val threshold = 10.minutes

  /** A watchdog whose reports are collected. */
  private def watching(ours: Int = 100): IO[(StallWatchdog[IO], IO[Vector[Change]])] =
    Ref.of[IO, Vector[Change]](Vector.empty).flatMap { seen =>
      StallWatchdog.create[IO](BigInt(ours), threshold, c => seen.update(_ :+ c)).map(_ -> seen.get)
    }

  test(
    "no block for the threshold while a peer is ahead is reported once, and its end when a block comes"
  ) {
    TestControl.executeEmbed(
      for
        pair <- watching()
        (dog, seen) = pair
        _ <- dog.announced(BigInt(5000))
        _ <- IO.sleep(threshold - 1.second) *> dog.check
        early <- seen
        _ <- IO.sleep(2.seconds) *> dog.check
        _ <- IO.sleep(1.minute) *> dog.check
        stalled <- seen
        _ <- dog.extended(BigInt(101))
        ended <- seen
      yield
        assertEquals(early, Vector.empty)
        assertEquals(stalled.size, 1)
        stalled.head match
          case Change.Began(idle, ours, peers) =>
            assert(idle >= threshold)
            assertEquals((ours, peers), (BigInt(100), BigInt(5000)))
          case other => fail(s"unexpected $other")
        assertEquals(ended.last, Change.Ended)
    )
  }

  test("a node that is at the tip of every peer is not stalled however long no block comes") {
    TestControl.executeEmbed(
      for
        pair <- watching()
        (dog, seen) = pair
        _ <- dog.announced(BigInt(100))
        _ <- IO.sleep(3.hours) *> dog.check
        reports <- seen
      yield assertEquals(reports, Vector.empty)
    )
  }

  test("a node that keeps extending is not stalled while peers are ahead") {
    TestControl.executeEmbed(
      for
        pair <- watching()
        (dog, seen) = pair
        _ <- dog.announced(BigInt(1000000))
        _ <- (IO.sleep(1.minute) *> dog.extended(BigInt(101)) *> dog.check).replicateA_(60)
        reports <- seen
      yield assertEquals(reports, Vector.empty)
    )
  }

  test("the second stall is reported again after the first ended") {
    TestControl.executeEmbed(
      for
        pair <- watching()
        (dog, seen) = pair
        _ <- dog.announced(BigInt(5000))
        _ <- IO.sleep(threshold) *> dog.check
        _ <- dog.extended(BigInt(101))
        _ <- IO.sleep(threshold) *> dog.check
        reports <- seen
      yield reports match
        case Vector(Change.Began(_, _, _), Change.Ended, Change.Began(_, _, _)) => ()
        case other => fail(s"unexpected $other")
    )
  }

  test("run checks on its period") {
    TestControl.executeEmbed(
      for
        pair <- watching()
        (dog, seen) = pair
        _ <- dog.announced(BigInt(5000))
        fiber <- dog.run(30.seconds).start
        _ <- IO.sleep(threshold + 1.minute)
        _ <- fiber.cancel
        reports <- seen
      yield assertEquals(reports.size, 1)
    )
  }
