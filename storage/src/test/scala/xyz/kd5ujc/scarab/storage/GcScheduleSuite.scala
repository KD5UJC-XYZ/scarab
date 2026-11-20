// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.{Deferred, IO, Ref}
import cats.effect.std.Queue
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.network.FailsOnUncaught

/** The received log's collection schedule, as the reference's ChainDB schedules the collection of
  * its VolatileDB (`scheduleGC`, `computeTimeForGC`, `gcScheduleRunner`,
  * `Storage/ChainDB/Impl/Background.hs` 466-652, ouroboros-consensus 82ecba329). Time is
  * TestControl's: each test offers its schedules to a program under control and moves the clock.
  */
class GcScheduleSuite extends FailsOnUncaught with ScalaCheckSuite:
  private val reference = GcSchedule.Params.Reference

  /** A program under TestControl that runs, in order, the actions offered to it, beside the runner
    * of `schedule`; each collection the runner makes is recorded with its time.
    */
  private final class Driven(
      control: TestControl[?],
      actions: Queue[IO, IO[Unit]],
      val schedule: GcSchedule[IO],
      val collected: Ref[IO, Vector[(FiniteDuration, BigInt)]]
  ):
    /** `slot` scheduled now, in the program. */
    def copy(slot: Int): IO[Unit] = actions.offer(schedule.schedule(BigInt(slot))) *> control.tick
    def advance(by: FiniteDuration): IO[Unit] = forward(control, by)
    def ended: IO[Boolean] = control.results.map(_.isDefined)

  /** The clock moved on by `by`, each timer due on the way run at its own time. */
  private def forward(control: TestControl[?], by: FiniteDuration): IO[Unit] =
    control.tick *> control.nextInterval.flatMap { next =>
      if next > Duration.Zero && next < by then
        control.advanceAndTick(next) *> forward(control, by - next)
      else control.advanceAndTick(by)
    }

  private def driven[A](body: Driven => IO[A]): IO[A] =
    for
      actions <- Queue.unbounded[IO, IO[Unit]]
      schedule <- GcSchedule[IO](reference)
      collected <- Ref.of[IO, Vector[(FiniteDuration, BigInt)]](Vector.empty)
      program = schedule
        .run[Nothing](slot =>
          IO.monotonic.flatMap(now => collected.update(_ :+ (now -> slot))).as(Right(()))
        )
        .background
        .use(_ => actions.take.flatMap(identity).foreverM)
      control <- TestControl.execute(program)
      _ <- control.tick
      result <- body(Driven(control, actions, schedule, collected))
    yield result

  private def at(seconds: Int, slot: Int) = GcSchedule.Scheduled(seconds.seconds, BigInt(slot))

  /** A collection refused, for the runner's failure. */
  private enum Refused:
    case At(slot: BigInt)

  test("a collection runs 60 s after the copy that scheduled it, at the 10 s mark, not before") {
    driven { d =>
      for
        _ <- d.copy(5)
        first <- d.schedule.pending
        _ <- d.advance(60.seconds - 1.nanosecond)
        early <- d.collected.get
        _ <- d.advance(1.nanosecond)
        due <- d.collected.get
        // at 61 s: 121 s, rounded up to the next 10 s mark
        _ <- d.advance(1.second)
        _ <- d.copy(7)
        second <- d.schedule.pending
        _ <- d.advance(69.seconds - 1.nanosecond)
        before <- d.collected.get
        _ <- d.advance(1.nanosecond)
        after <- d.collected.get
        ended <- d.ended
      yield
        assertEquals(first, Vector(at(60, 5)), "scheduled at the copy plus 60 s")
        assertEquals(early, Vector.empty, "nothing is collected before the time")
        assertEquals(due, Vector(60.seconds -> BigInt(5)), "the slot is collected at its time")
        assertEquals(second, Vector(at(130, 7)), "121 s rounds up to 130 s")
        assertEquals(before, due, "nothing more before 130 s")
        assertEquals(after, due :+ (130.seconds -> BigInt(7)), "and the second at 130 s")
        assert(!ended, "the runner goes on")
    }
  }

  test("copies in one 10 s interval are one collection, of the newest slot") {
    driven { d =>
      for
        _ <- d.copy(1)
        _ <- d.advance(1.second)
        // 61 s, 65 s and 70 s all round up to 70 s: each replaces the entry before it
        _ <- d.copy(2)
        _ <- d.advance(4.seconds)
        _ <- d.copy(3)
        _ <- d.advance(5.seconds)
        _ <- d.copy(4)
        queued <- d.schedule.pending
        _ <- d.advance(60.seconds)
        collected <- d.collected.get
        left <- d.schedule.pending
      yield
        assertEquals(queued, Vector(at(60, 1), at(70, 4)), "the queue after the four copies")
        assertEquals(
          collected,
          Vector(60.seconds -> BigInt(1), 70.seconds -> BigInt(4)),
          "one collection per interval, of the newest slot scheduled for it"
        )
        assertEquals(left, Vector.empty, "the queue is emptied")
    }
  }

  test("copies in different intervals are queued, and each is collected in order at its time") {
    driven { d =>
      for
        _ <- d.copy(1)
        _ <- d.advance(15.seconds)
        _ <- d.copy(2)
        _ <- d.advance(10.seconds)
        _ <- d.copy(3)
        _ <- d.advance(33.seconds)
        _ <- d.copy(4)
        queued <- d.schedule.pending
        _ <- d.advance(100.seconds)
        collected <- d.collected.get
      yield
        assertEquals(queued, Vector(at(60, 1), at(80, 2), at(90, 3), at(120, 4)), "the queue")
        assertEquals(
          collected,
          Vector(
            60.seconds -> BigInt(1),
            80.seconds -> BigInt(2),
            90.seconds -> BigInt(3),
            120.seconds -> BigInt(4)
          ),
          "each at its time, oldest first"
        )
    }
  }

  test("the runner ends with the first collection that fails, and runs no other") {
    for
      actions <- Queue.unbounded[IO, IO[Unit]]
      schedule <- GcSchedule[IO](reference)
      tried <- Ref.of[IO, Vector[BigInt]](Vector.empty)
      ended <- Deferred[IO, Refused]
      program = schedule
        .run[Refused](slot =>
          tried.update(_ :+ slot).as(Either.cond(slot != BigInt(2), (), Refused.At(slot)))
        )
        .flatMap(ended.complete)
        .background
        .use(_ => actions.take.flatMap(identity).foreverM)
      control <- TestControl.execute(program)
      _ <- control.tick
      _ <- actions.offer(schedule.schedule(BigInt(1))) *> control.tick
      _ <- forward(control, 20.seconds)
      _ <- actions.offer(schedule.schedule(BigInt(2))) *> control.tick
      _ <- forward(control, 20.seconds)
      _ <- actions.offer(schedule.schedule(BigInt(3))) *> control.tick
      _ <- forward(control, 200.seconds)
      result <- ended.tryGet
      attempts <- tried.get
      left <- schedule.pending
    yield
      assertEquals(result, Some(Refused.At(BigInt(2))), "the failure is the runner's result")
      assertEquals(attempts, Vector(BigInt(1), BigInt(2)), "no collection after the failure")
      assertEquals(left, Vector(at(100, 3)), "what was scheduled after it stays queued")
  }

  // ---- the pure parts: the time of a collection and the queue ----

  private val nanos: Gen[Long] = Gen.choose(0L, 1_000_000L * 1_000_000_000L)

  property("a collection's time is a 10 s mark, at least 60 s and less than 70 s after its copy") {
    Prop.forAll(nanos) { now =>
      val scheduled = GcSchedule.timeFor(reference, now.nanos)
      val earliest = now.nanos + 60.seconds
      Prop(scheduled >= earliest) :| s"$scheduled before $earliest" &&
      Prop(scheduled < earliest + 10.seconds) :| s"$scheduled past $earliest + 10 s" &&
      Prop(scheduled.toNanos % 10.seconds.toNanos == 0) :| s"$scheduled is not a 10 s mark"
    }
  }

  property("the queue is in time order and never longer than ⌈60 s / 10 s⌉ + 1 entries") {
    // copies 0 to 30 s apart, the runner taking off each entry whose time has come before a copy
    val gaps = Gen.listOf(Gen.choose(0L, 30_000_000_000L))
    Prop.forAll(gaps) { steps =>
      val (_, _, worst, ordered) =
        steps.zipWithIndex.foldLeft((0L, Vector.empty[GcSchedule.Scheduled], 0, true)) {
          case ((now, queue, longest, inOrder), (gap, slot)) =>
            val time = now + gap
            val due = queue.dropWhile(_.at <= time.nanos)
            val next = GcSchedule.scheduled(
              due,
              GcSchedule.Scheduled(GcSchedule.timeFor(reference, time.nanos), BigInt(slot))
            )
            val sorted = next.map(_.at).zip(next.map(_.at).drop(1)).forall(_ < _)
            (time, next, longest.max(next.size), inOrder && sorted)
        }
      Prop(worst <= 7) :| s"a queue of $worst entries" &&
      Prop(ordered) :| "the times are not increasing"
    }
  }

  property("a copy replaces the last entry exactly when it is for the same time") {
    Prop.forAll(Gen.listOfN(3, nanos), nanos) { (earlier, now) =>
      val queue = earlier.sorted.zipWithIndex.foldLeft(Vector.empty[GcSchedule.Scheduled]) {
        case (q, (t, slot)) =>
          GcSchedule.scheduled(
            q,
            GcSchedule.Scheduled(GcSchedule.timeFor(reference, t.nanos), BigInt(slot))
          )
      }
      val entry = GcSchedule.Scheduled(GcSchedule.timeFor(reference, now.nanos), BigInt(99))
      val next = GcSchedule.scheduled(queue, entry)
      val same = queue.lastOption.exists(_.at == entry.at)
      Prop(next.lastOption.contains(entry)) :| "the copy is the last entry" &&
      Prop(next.size == queue.size + (if same then 0 else 1)) :| s"$queue then $next" &&
      Prop(next.dropRight(1) == queue.dropRight(if same then 1 else 0)) :| "the others stay"
    }
  }

  test("the parameters refuse an interval that is not positive and a negative delay") {
    assertEquals(GcSchedule.Params.of(60.seconds, Duration.Zero), None)
    assertEquals(GcSchedule.Params.of(Duration.Zero - 1.second, 10.seconds), None)
    assertEquals(GcSchedule.Params.of(60.seconds, 10.seconds), Some(reference))
  }
