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
      assert(release.await(10, TimeUnit.SECONDS))
      try TestLedger.scripted(true).evaluate(request)
      finally { val _ = active.decrementAndGet() }
    val body = PreparedBlockFixture.body(evaluator)
    for
      service <- ParallelPlutus.create[IO](workers(2))
      result <- (service(body), service(body), service(body)).parTupled.start
      _ <- IO.blocking(assert(entered.await(10, TimeUnit.SECONDS)))
      _ <- IO.sleep(100.millis)
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
      assert(release.await(10, TimeUnit.SECONDS))
      try TestLedger.scripted(true).evaluate(request)
      finally { val _ = active.decrementAndGet() }
    val body = PreparedBlockFixture.body(evaluator, PreparedBlockFixture.txs.take(1))
    for
      service <- ParallelPlutus.create[IO](workers(2))
      result <- (service(body), service(body), service(body)).parTupled.start
      _ <- IO.blocking(assert(entered.await(10, TimeUnit.SECONDS)))
      _ <- IO.sleep(100.millis)
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
        assert(release.await(10, TimeUnit.SECONDS))
        TestLedger.scripted(true).evaluate(request)
      finally { val _ = active.decrementAndGet() }
    for
      service <- ParallelPlutus.create[IO](workers(2))
      first <- service(PreparedBlockFixture.body(evaluator)).start
      _ <- IO.blocking(assert(entered.await(10, TimeUnit.SECONDS)))
      cancelled <- first.cancel.start
      second <- service(PreparedBlockFixture.body(evaluator)).start
      _ <- IO.sleep(100.millis)
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

  test("a later speculative preparation defect cannot overtake the serial script refusal") {
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
