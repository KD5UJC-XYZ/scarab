// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.Id
import cats.effect.IO
import cats.syntax.all.*
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{PreparedBlockFixture, TestLedger, Verdicts}
import xyz.kd5ujc.scarab.network.FailsOnUncaught
import xyz.kd5ujc.scarab.plutus.ScriptEvaluator

class ParallelPlutusSuite extends FailsOnUncaught:
  /** How long a test latch may wait. Generous on purpose: the suite failed once on CI with the old
    * 10 s budget, where both the waiter and a worker timed out in a row (20.1 s of lateness with no
    * scheduling defect to point at). A real stall still fails the suite — with a message that says
    * which side timed out — while a loaded runner cannot fail it for being late.
    */
  private val WaitSeconds = 60

  override def munitIOTimeout: Duration = 2.minutes
  private def workers(n: Int) = ParallelPlutus.Workers.from(n).fold(e => fail(e.toString), identity)

  test("independent machines overlap within one node-wide bound across block callers") {
    val active = new AtomicInteger()
    val maximum = new AtomicInteger()
    val entered = new CountDownLatch(2)
    val release = new CountDownLatch(1)
    val evaluator: ScriptEvaluator[Id] = request =>
      val count = active.incrementAndGet()
      maximum.accumulateAndGet(count, math.max)
      entered.countDown()
      assert(
        release.await(WaitSeconds, TimeUnit.SECONDS),
        "the release latch was not counted down: the test's own waiter timed out first"
      )
      try TestLedger.scripted(true).evaluate(request)
      finally { val _ = active.decrementAndGet() }
    val body = PreparedBlockFixture.body(evaluator)
    for
      service <- ParallelPlutus.create[IO](workers(2))
      result <- (service(body), service(body), service(body)).parTupled.start
      _ <- IO.blocking(
        assert(
          entered.await(WaitSeconds, TimeUnit.SECONDS),
          s"${entered.getCount.toInt} of 2 workers did not enter the evaluator within $WaitSeconds s"
        )
      )
      // the entered latch already implies both workers incremented `active` before counting down
      _ <- IO(assertEquals(active.get(), 2))
      _ <- IO(release.countDown())
      values <- result.joinWithNever
      _ <- IO {
        assertEquals(values._1, PreparedBlockFixture.body(TestLedger.scripted(true)).serial)
        assertEquals(values._1, values._2)
        assertEquals(values._1, values._3)
        assertEquals(maximum.get(), 2)
        assertEquals(active.get(), 0)
      }
    yield ()
  }

  test("serial fast paths also obey the same bound across block callers") {
    val active = new AtomicInteger()
    val maximum = new AtomicInteger()
    val entered = new CountDownLatch(2)
    val release = new CountDownLatch(1)
    val evaluator: ScriptEvaluator[Id] = request =>
      val count = active.incrementAndGet()
      maximum.accumulateAndGet(count, math.max)
      entered.countDown()
      assert(
        release.await(WaitSeconds, TimeUnit.SECONDS),
        "the release latch was not counted down: the test's own waiter timed out first"
      )
      try TestLedger.scripted(true).evaluate(request)
      finally { val _ = active.decrementAndGet() }
    val body = PreparedBlockFixture.body(evaluator, PreparedBlockFixture.txs.take(1))
    for
      service <- ParallelPlutus.create[IO](workers(2))
      result <- (service(body), service(body), service(body)).parTupled.start
      _ <- IO.blocking(
        assert(
          entered.await(WaitSeconds, TimeUnit.SECONDS),
          s"${entered.getCount.toInt} of 2 workers did not enter the evaluator within $WaitSeconds s"
        )
      )
      // the entered latch already implies both workers incremented `active` before counting down
      _ <- IO(assertEquals(active.get(), 2))
      _ <- IO(release.countDown())
      values <- result.joinWithNever
      _ <- IO {
        assertEquals(
          values._1,
          PreparedBlockFixture
            .body(TestLedger.scripted(true), PreparedBlockFixture.txs.take(1))
            .serial
        )
        assertEquals(values._1, values._2)
        assertEquals(values._1, values._3)
        assertEquals(maximum.get(), 2)
        assertEquals(active.get(), 0)
      }
    yield ()
  }

  test("cancellation joins running machines before releasing their permits") {
    val active = new AtomicInteger()
    val maximum = new AtomicInteger()
    val entered = new CountDownLatch(2)
    val release = new CountDownLatch(1)
    val evaluator: ScriptEvaluator[Id] = request =>
      val count = active.incrementAndGet()
      maximum.accumulateAndGet(count, math.max)
      entered.countDown()
      try
        assert(
          release.await(WaitSeconds, TimeUnit.SECONDS),
          "the release latch was not counted down: the test's own waiter timed out first"
        )
        TestLedger.scripted(true).evaluate(request)
      finally { val _ = active.decrementAndGet() }
    for
      service <- ParallelPlutus.create[IO](workers(2))
      first <- service(PreparedBlockFixture.body(evaluator)).start
      _ <- IO.blocking(
        assert(
          entered.await(WaitSeconds, TimeUnit.SECONDS),
          s"${entered.getCount.toInt} of 2 workers did not enter the evaluator within $WaitSeconds s"
        )
      )
      cancelled <- first.cancel.start
      second <- service(PreparedBlockFixture.body(evaluator)).start
      // both permits are held and both workers incremented `active`, as the entered latch proves
      _ <- IO { assertEquals(active.get(), 2); assertEquals(maximum.get(), 2) }
      _ <- IO(release.countDown())
      _ <- cancelled.joinWithNever
      _ <- second.joinWithNever
      _ <- IO { assertEquals(active.get(), 0); assertEquals(maximum.get(), 2) }
    yield ()
  }

  test("one worker follows the exact serial path and backend defects reach the owner") {
    val defect = new IllegalArgumentException("raw machine defect")
    val evaluator: ScriptEvaluator[Id] = _ => throw defect
    for
      serial <- ParallelPlutus.create[IO](workers(1))
      parallel <- ParallelPlutus.create[IO](workers(2))
      a <- serial(PreparedBlockFixture.body(evaluator)).attempt
      b <- parallel(PreparedBlockFixture.body(evaluator)).attempt
      _ <- IO { assert(a.left.exists(_ eq defect)); assert(b.left.exists(_ eq defect)) }
    yield ()
  }

  test("an earlier script refusal precedes a later captured preparation defect") {
    val defect = new IllegalStateException("later phase-1 defect")
    val verdicts = new Verdicts:
      export Verdicts.Direct.{opCert, praosVrf, tpraosVrf, keyHash, takePrepared}
      def witness(key: Bytes, signature: Bytes, message: Bytes): Boolean =
        if message == PreparedBlockFixture.txs(1).body.id then throw defect
        else Verdicts.Direct.witness(key, signature, message)
    val failing: ScriptEvaluator[Id] = _ =>
      xyz.kd5ujc.scarab.plutus.EvalResult.Failure(
        xyz.kd5ujc.scarab.plutus.EvalFailure.OutOfBudget,
        xyz.kd5ujc.scarab.plutus.ExUnits.zero,
        Vector.empty
      )
    val refused = PreparedBlockFixture.body(failing, verdicts = verdicts)
    val defective = PreparedBlockFixture.body(TestLedger.scripted(true), verdicts = verdicts)
    for
      service <- ParallelPlutus.create[IO](workers(2))
      actual <- service(refused)
      reached <- service(defective).attempt
      _ <- IO {
        assertEquals(actual, refused.serial)
        assert(reached.left.exists(_ eq defect))
      }
    yield ()
  }

  test("a transient preparation defect is retained even when a serial retry would succeed") {
    val defect = new IllegalStateException("transient phase-1 defect")
    val first = new java.util.concurrent.atomic.AtomicBoolean(true)
    val verdicts = new Verdicts:
      export Verdicts.Direct.{opCert, praosVrf, tpraosVrf, keyHash, takePrepared}
      def witness(key: Bytes, signature: Bytes, message: Bytes): Boolean =
        if message == PreparedBlockFixture.txs(1).body.id && first.compareAndSet(true, false) then
          throw defect
        else Verdicts.Direct.witness(key, signature, message)
    val body = PreparedBlockFixture.body(TestLedger.scripted(true), verdicts = verdicts)
    for
      service <- ParallelPlutus.create[IO](workers(2))
      result <- service(body).attempt
      _ <- IO {
        assert(result.left.exists(_ eq defect))
        assert(body.serial.isInstanceOf[xyz.kd5ujc.scarab.ledger.rules.EraBlock.Outcome.Applied])
      }
    yield ()
  }
