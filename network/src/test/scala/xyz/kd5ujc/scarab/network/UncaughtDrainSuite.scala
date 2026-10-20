// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.IO
import scala.concurrent.duration.*
import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicBoolean

class UncaughtDrainSuite extends FailsOnUncaught:
  override def munitIOTimeout: Duration = 2.minutes

  test("draining preserves FIFO order and removes each reported failure once") {
    val first = new IllegalStateException("first")
    val second = new IllegalStateException("second")
    val queue = new ConcurrentLinkedQueue[Throwable]
    queue.add(first)
    queue.add(second)
    assertEquals(FailsOnUncaught.drain(queue), List(first, second))
    assertEquals(FailsOnUncaught.drain(queue), Nil)
  }

  test("a producer racing an empty observation is returned or remains queued") {
    IO.blocking {
      val empty = new CountDownLatch(1)
      val inserted = new CountDownLatch(1)
      val firstEmpty = new AtomicBoolean(true)
      val produced = new AtomicBoolean(false)
      val failure = new IllegalStateException("concurrent report")
      def afterEmpty(): Unit =
        if firstEmpty.compareAndSet(true, false) then
          empty.countDown()
          val insertedReady = inserted.await(30, TimeUnit.SECONDS)
          assert(insertedReady, "producer did not report after empty read")
      val queue = new ConcurrentLinkedQueue[Throwable]:
        override def poll(): Throwable =
          val value = super.poll()
          if value == null then afterEmpty()
          value
        // The old snapshot/clear drain takes this route: its empty snapshot is already
        // captured, and the producer inserts immediately before clear deletes the report.
        override def clear(): Unit =
          afterEmpty()
          super.clear()
      val producer = new Thread(() =>
        try
          if empty.await(30, TimeUnit.SECONDS) then
            queue.add(failure)
            produced.set(true)
        finally inserted.countDown()
      )
      producer.setDaemon(true)
      producer.start()
      try
        val seen = FailsOnUncaught.drain(queue)
        producer.join(30000)
        assert(!producer.isAlive, "producer did not finish")
        assert(produced.get(), "producer did not add the concurrent report")
        val remaining = FailsOnUncaught.drain(queue)
        assertEquals(seen ++ remaining, List(failure))
        assertEquals(FailsOnUncaught.drain(queue), Nil)
      finally
        empty.countDown()
        inserted.countDown()
        producer.join(30000)
    }
  }
