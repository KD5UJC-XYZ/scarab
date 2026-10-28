// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.ledger.TestGeometry
import cats.effect.{IO, Ref}
import cats.effect.testkit.TestControl
import cats.mtl.{Handle, Raise}
import cats.syntax.all.*
import java.time.Instant
import munit.{CatsEffectSuite, ScalaCheckSuite}
import org.scalacheck.{Gen, Prop}
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.ledger.{ByronSpan, EpochGeometry, Fixtures, Header, HeaderError, PeerError}
import xyz.kd5ujc.scarab.network.selection.FailureClass
import xyz.kd5ujc.scarab.node.HeaderEligibility.*
import xyz.kd5ujc.scarab.protocol.ChainSync

/** The wall-clock eligibility of incoming headers (AUD-01, #153): the judgement of a slot against
  * the arrival time, and the gate that holds a live session to it. Time is `TestControl` 's, with
  * the system start at the epoch and one-second slots, so slot `n` begins `n` seconds in.
  */
class HeaderClockSuite extends CatsEffectSuite with ScalaCheckSuite:
  private val history =
    EpochGeometry(Instant.EPOCH, 1000, 432000, TestGeometry.k(2160), TestGeometry.f("0.05"))
  private val skew = 2.seconds
  private def at(millis: Long): Instant = Instant.ofEpochMilli(millis)
  private def judge(slot: BigInt, arrivalMillis: Long) =
    HeaderEligibility.judge(history, slot, at(arrivalMillis), skew)

  // ---- the judgement ----

  test("a slot that has begun is eligible, also exactly at its onset") {
    assertEquals(judge(10, 10000), Eligible)
    assertEquals(judge(10, 10001), Eligible)
    assertEquals(judge(10, 500000), Eligible)
  }

  test("a slot that begins within the skew waits for its onset, the boundary included") {
    assertEquals(judge(10, 9999), WaitUntil(at(10000)))
    assertEquals(judge(10, 8000), WaitUntil(at(10000)))
  }

  test("a slot that begins more than the skew ahead is far in the future") {
    assertEquals(judge(10, 7999), FarFuture(HeaderError.FarFuture(10, 2001, 2000)))
    assertEquals(judge(10, 0), FarFuture(HeaderError.FarFuture(10, 10000, 2000)))
  }

  test("a slot number chosen by the peer cannot overflow the judgement") {
    assert(judge(BigInt(2).pow(200), 0).isInstanceOf[FarFuture])
    assert(judge(BigInt(Long.MaxValue), 0).isInstanceOf[FarFuture])
  }

  property("the verdict is the reference's: eligible, wait, or far future by the strict skew") {
    Prop.forAll(Gen.choose(0L, 100000L), Gen.choose(0L, 100000000L), Gen.choose(0L, 5000L)) {
      (slot, arrival, skewMillis) =>
        val ahead = slot * 1000 - arrival
        HeaderEligibility.judge(history, slot, at(arrival), skewMillis.millis) match
          case Eligible     => ahead <= 0
          case WaitUntil(o) => ahead > 0 && ahead <= skewMillis && o == at(slot * 1000)
          case FarFuture(e) =>
            ahead > skewMillis && e == HeaderError.FarFuture(slot, ahead, skewMillis)
    }
  }

  test("a Byron span that is still derived never makes a Shelley slot far in the future") {
    // 20 s Byron slots, 1 s Shelley slots: read as Byron, slot 21700 would begin at 434000 s, but
    // after one Byron epoch (21600 slots, 432000 s) it begins at 432100 s
    val derived = EpochGeometry(
      Instant.EPOCH,
      1000,
      432000,
      TestGeometry.k(2160),
      TestGeometry.f("0.05"),
      ByronSpan.derived(21600, 20000)
    )
    val slot = BigInt(21700)
    val arrival = at(432100L * 1000)
    assertEquals(HeaderEligibility.judge(derived.resolved(1), slot, arrival, skew), Eligible)
    assertEquals(HeaderEligibility.judge(derived, slot, arrival, skew), Eligible)
    // a slot that is in the future by any reading is still caught
    assert(
      HeaderEligibility.judge(derived, BigInt(10).pow(12), arrival, skew).isInstanceOf[FarFuture]
    )
  }

  // ---- the gate ----

  private def tip = ChainSync.Tip.Origin
  private def forward(h: Header): SyncEvent = SyncEvent.RollForward(h, tip)
  private def header(blockNo: Int, slot: Int): Header =
    Fixtures.header(blockNo, slot, None, salt = blockNo)

  /** The inner gate records the time (ms) at which each batch reached it. */
  private final class Recorder:
    val reached = Ref.unsafe[IO, Vector[(Long, Vector[SyncEvent])]](Vector.empty)
    val check: HeaderCheck[IO] = new HeaderCheck[IO]:
      def start(intersection: ChainSync.Point) = IO.pure(new HeaderGate[IO]:
        def accept(batch: Vector[SyncEvent])(using Raise[IO, PeerError]) =
          millis.flatMap(t => reached.update(_ :+ (t -> batch)))
        def counts = IO.pure(HeaderGate.Counts(0, 0, 0)))

  /** A clock whose wall time is the test's time plus a settable offset (a clock step). */
  private final class Stepped(val offset: Ref[IO, FiniteDuration]) extends SlotClock[IO]:
    def now = (IO.realTime, offset.get).mapN((t, o) => Instant.EPOCH.plusNanos((t + o).toNanos))
    def geometry = IO.pure(history)

  private def stepped = Ref.of[IO, FiniteDuration](Duration.Zero).map(new Stepped(_))
  private def millis = IO.realTime.map(_.toMillis)

  private def typed[A](program: Handle[IO, PeerError] ?=> IO[A]): IO[Either[PeerError, A]] =
    Handle.allow[PeerError](program.map(_.asRight[PeerError])).rescue(e => IO.pure(e.asLeft[A]))

  private def session(clock: SlotClock[IO], rec: Recorder) =
    HeaderClock.clocked[IO](clock, rec.check, skew).start(ChainSync.Point.Origin)

  private def run[A](program: IO[A]): IO[A] = TestControl.executeEmbed(program)

  test("an eligible header reaches the inner gate at once") {
    run {
      for
        clock <- stepped
        rec = new Recorder
        _ <- IO.sleep(30.seconds)
        gate <- session(clock, rec)
        r <- typed(gate.accept(Vector(header(0, 5), header(1, 6)).map(forward)))
        seen <- rec.reached.get
      yield (r, seen.map(_._1))
    }.assertEquals((Right(()), Vector(30000L)))
  }

  test("a header within the skew is held back until its slot begins, then offered") {
    run {
      for
        clock <- stepped
        rec = new Recorder
        _ <- IO.sleep(8.seconds)
        gate <- session(clock, rec)
        r <- typed(gate.accept(Vector(forward(header(0, 10)))))
        seen <- rec.reached.get
      yield (r, seen.map(_._1), seen.map(_._2))
    }.assertEquals((Right(()), Vector(10000L), Vector(Vector(forward(header(0, 10))))))
  }

  test("a header beyond the skew raises the typed error, is never offered, and nothing waits") {
    run {
      for
        clock <- stepped
        rec = new Recorder
        _ <- IO.sleep(7.seconds)
        gate <- session(clock, rec)
        h = header(0, 10)
        r <- typed(gate.accept(Vector(forward(h))))
        now <- millis
        seen <- rec.reached.get
      yield (r, now, seen, h)
    }.map { case (r, now, seen, h) =>
      assertEquals(
        r,
        Left(PeerError.InvalidHeader(h.point, HeaderError.FarFuture(10, 3000, 2000)))
      )
      assertEquals(now, 7000L)
      assertEquals(seen, Vector.empty)
      assertEquals(r.left.toOption.map(FailurePolicy.classify), Some(FailureClass.Misbehaved))
    }
  }

  test("the headers behind a waiting one wait with it: the batch is released at the latest onset") {
    run {
      for
        clock <- stepped
        rec = new Recorder
        _ <- IO.sleep(8.seconds)
        gate <- session(clock, rec)
        _ <- typed(gate.accept(Vector(header(0, 5), header(1, 9), header(2, 10)).map(forward)))
        seen <- rec.reached.get
      yield seen.map(_._1)
    }.assertEquals(Vector(10000L))
  }

  test("a far-future header after a waiting one is judged after the first delay") {
    run {
      for
        clock <- stepped
        rec = new Recorder
        _ <- IO.sleep(8.seconds)
        gate <- session(clock, rec)
        r <- typed(gate.accept(Vector(header(0, 9), header(1, 100)).map(forward)))
        now <- millis
      yield (r.isLeft, now)
    }.assertEquals((true, 9000L))
  }

  test("each batch is judged at its own arrival, so a header that waited does not shift the next") {
    run {
      for
        clock <- stepped
        rec = new Recorder
        _ <- IO.sleep(8.seconds)
        gate <- session(clock, rec)
        _ <- typed(gate.accept(Vector(forward(header(0, 10)))))
        // at 10 s a slot 12 header is exactly at the skew (it waits to 12 s); then slot 15 is 3 s ahead
        ok <- typed(gate.accept(Vector(forward(header(1, 12)))))
        bad <- typed(gate.accept(Vector(forward(header(2, 15)))))
        seen <- rec.reached.get
      yield (ok, bad.isLeft, seen.map(_._1))
    }.assertEquals((Right(()), true, Vector(10000L, 12000L)))
  }

  test("a clock step back does not extend the native single synthetic delay") {
    run {
      for
        clock <- stepped
        rec = new Recorder
        _ <- IO.sleep(8.seconds)
        gate <- session(clock, rec)
        waiting <- typed(gate.accept(Vector(forward(header(0, 10))))).start
        _ <- IO.sleep(500.millis)
        _ <- clock.offset.set(-1.second)
        _ <- waiting.join
        seen <- rec.reached.get
      yield seen.map(_._1)
    }.assertEquals(Vector(10000L))
  }

  test("a clock that steps forward while a header waits releases it when the sleep ends") {
    run {
      for
        clock <- stepped
        rec = new Recorder
        _ <- IO.sleep(8.seconds)
        gate <- session(clock, rec)
        waiting <- typed(gate.accept(Vector(forward(header(0, 10))))).start
        _ <- IO.sleep(500.millis)
        _ <- clock.offset.set(5.seconds)
        _ <- waiting.join
        seen <- rec.reached.get
      yield seen.map(_._1)
    }.assertEquals(Vector(10000L))
  }

  test("a reconnected session starts clean: one session's refusal does not touch another") {
    run {
      for
        clock <- stepped
        rec = new Recorder
        first <- session(clock, rec)
        bad <- typed(first.accept(Vector(forward(header(0, 50)))))
        second <- session(clock, rec)
        ok <- typed(second.accept(Vector(forward(header(0, 1)))))
        seen <- rec.reached.get
      yield (bad.isLeft, ok, seen.size)
    }.assertEquals((true, Right(()), 1))
  }

  test("old headers, as when a restored node catches up, are never delayed or refused") {
    run {
      for
        clock <- stepped
        rec = new Recorder
        _ <- IO.sleep(1000000.seconds)
        gate <- session(clock, rec)
        r <- typed(gate.accept(Fixtures.chain(200).map(forward)))
        now <- millis
      yield (r, now)
    }.assertEquals((Right(()), 1000000000L))
  }

  test("a batch of rollbacks only goes straight to the inner gate") {
    run {
      for
        clock <- stepped
        rec = new Recorder
        gate <- session(clock, rec)
        r <- typed(
          gate.accept(Vector(SyncEvent.RollBackward(ChainSync.Point.Origin, tip)))
        )
        seen <- rec.reached.get
      yield (r, seen.size)
    }.assertEquals((Right(()), 1))
  }

  test("waiting through very many stale clock reads is stack safe") {
    val reads = 200000
    run {
      for
        calls <- Ref.of[IO, Int](0)
        clock = new SlotClock[IO]:
          // the clock is read many times before it reaches the onset
          def now = calls.updateAndGet(_ + 1).map(n => Instant.EPOCH.plusNanos(n.toLong * 10))
          def geometry = IO.pure(history)
        _ <- HeaderClock.until[IO](clock, Instant.EPOCH.plusNanos(reads.toLong * 10))
        n <- calls.get
      yield n
    }.assertEquals(reads)
  }
