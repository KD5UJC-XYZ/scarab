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
  private def watching(
      ours: Int = 100,
      exitAfter: Option[FiniteDuration] = None
  ): IO[(StallWatchdog[IO], IO[Vector[Change]])] =
    Ref.of[IO, Vector[Change]](Vector.empty).flatMap { seen =>
      StallWatchdog
        .create[IO](BigInt(ours), threshold, exitAfter, c => seen.update(_ :+ c))
        .map(_ -> seen.get)
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

  test("the exit is due once, after exitAfter, only while a peer is ahead") {
    TestControl.executeEmbed(
      for
        pair <- watching(exitAfter = Some(30.minutes))
        (dog, seen) = pair
        _ <- dog.announced(BigInt(5000))
        _ <- IO.sleep(29.minutes) *> dog.check
        before <- seen
        _ <- IO.sleep(2.minutes) *> dog.check
        _ <- IO.sleep(5.minutes) *> dog.check
        after <- seen
      yield
        assertEquals(before.size, 1) // the warning only
        assertEquals(after.size, 2)
        assert(after.last.isInstanceOf[Change.ExitDue], after.toString)
    )
  }

  test("no exit while no peer is ahead, and none without exitAfter") {
    TestControl.executeEmbed(
      for
        level <- watching(exitAfter = Some(30.minutes))
        _ <- level._1.announced(BigInt(100))
        _ <- IO.sleep(5.hours) *> level._1.check
        quiet <- level._2
        off <- watching()
        _ <- off._1.announced(BigInt(5000))
        _ <- IO.sleep(5.hours) *> off._1.check
        warned <- off._2
      yield
        assertEquals(quiet, Vector.empty)
        assertEquals(warned.size, 1)
        assert(warned.head.isInstanceOf[Change.Began])
    )
  }

  test("a block before the exit starts the count again") {
    TestControl.executeEmbed(
      for
        pair <- watching(exitAfter = Some(30.minutes))
        (dog, seen) = pair
        _ <- dog.announced(BigInt(5000))
        _ <- IO.sleep(25.minutes) *> dog.check *> dog.extended(BigInt(101))
        _ <- IO.sleep(25.minutes) *> dog.check
        reports <- seen
      yield assert(!reports.exists(_.isInstanceOf[Change.ExitDue]), reports.toString)
    )
  }
