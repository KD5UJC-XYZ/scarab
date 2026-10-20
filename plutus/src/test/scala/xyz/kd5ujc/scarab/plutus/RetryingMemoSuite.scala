// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import munit.FunSuite

class RetryingMemoSuite extends FunSuite:
  /** How long a test latch may wait: generous on purpose, so a loaded runner cannot fail the
    * concurrency test for being late, while a real deadlock still fails it with a message.
    */
  private val WaitSeconds = 30
  test("an unavailable VM construction is retried, then its recovered value is retained") {
    val memo = RetryingMemo[String, EvalFailure.Unsupported, Object]()
    val calls = AtomicInteger(0)
    val recovered = new Object
    def build: Either[EvalFailure.Unsupported, Object] =
      if calls.incrementAndGet() == 1 then Left(EvalFailure.Unsupported(BackendFailure.NoResult))
      else Right(recovered)
    assert(memo.getOrElseUpdate("model")(build).isLeft)
    assertEquals(memo.getOrElseUpdate("model")(build), Right(recovered))
    assertEquals(memo.getOrElseUpdate("model")(build), Right(recovered))
    assertEquals(calls.get(), 2)
  }

  test("concurrent callers share one successful VM construction") {
    val memo = RetryingMemo[String, EvalFailure.Unsupported, Object]()
    val calls = AtomicInteger(0)
    val constructing = CountDownLatch(1)
    val finish = CountDownLatch(1)
    val done = CountDownLatch(8)
    val recovered = new Object
    val results = Vector.fill(8)(
      AtomicReference[Either[EvalFailure.Unsupported, Object]](
        Left(EvalFailure.Unsupported(BackendFailure.NoResult))
      )
    )
    val workers = results.map { result =>
      val worker = new Thread(() =>
        result.set(memo.getOrElseUpdate("model") {
          calls.incrementAndGet()
          constructing.countDown()
          if finish.await(WaitSeconds, TimeUnit.SECONDS) then Right(recovered)
          else Left(EvalFailure.Unsupported(BackendFailure.NoResult))
        })
        done.countDown()
      )
      worker.setDaemon(true)
      worker
    }
    workers.foreach(_.start())
    assert(
      constructing.await(WaitSeconds, TimeUnit.SECONDS),
      s"no caller reached the construction within $WaitSeconds s"
    )
    finish.countDown()
    assert(
      done.await(WaitSeconds, TimeUnit.SECONDS),
      s"${done.getCount.toInt} of 8 callers did not finish within $WaitSeconds s"
    )
    workers.foreach(_.join())
    assertEquals(calls.get(), 1)
    results.foreach(result => assertEquals(result.get(), Right(recovered)))
  }

  test("the bounded decoder cache skips unavailable results and retains known malformed input") {
    type Result = Either[ScriptDecodeFailure, Int]
    val cache = ScalusEvaluator.BoundedCache[String, Result](1)
    val calls = AtomicInteger(0)
    def lookup(key: String)(value: Result): Result =
      cache.getOrElseUpdate(key, { calls.incrementAndGet(); value })(ScriptDecodeFailure.cacheable)
    assertEquals(lookup("valid")(Right(1)), Right(1))
    assert(lookup("retry")(Left(ScriptDecodeFailure.Unavailable(BackendFailure.NoResult))).isLeft)
    // An unavailable attempt did not consume the single cache slot or evict the known program.
    assertEquals(lookup("valid")(Right(2)), Right(1))
    val malformed = Left(ScriptDecodeFailure.Malformed(ScriptProblem.CborByteStringExpected))
    assertEquals(lookup("bad")(malformed), malformed)
    assertEquals(lookup("bad")(Right(3)), malformed)
    assertEquals(lookup("retry")(Right(4)), Right(4))
    assertEquals(lookup("retry")(Right(5)), Right(4))
    assertEquals(calls.get(), 4)
  }
