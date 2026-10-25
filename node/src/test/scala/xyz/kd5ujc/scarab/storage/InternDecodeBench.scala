// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import java.lang.management.ManagementFactory
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}
import scala.noinline
import munit.Assertions
import xyz.kd5ujc.scarab.consensus.state.ConsensusMirrorEnv
import xyz.kd5ujc.scarab.node.NodeSetup

/** Decode-only comparison. Full equality/digests and externally requested live histograms are
  * outside timing. Memory mode retains exactly one decoded state until the LIVE checkpoint.
  */
object InternDecodeBench extends Assertions:
  private val heap = ManagementFactory.getMemoryMXBean
  private val allocation = ManagementFactory.getThreadMXBean match
    case b: com.sun.management.ThreadMXBean => b
    case _                                  => fail("allocation counter unavailable")
  private def checked[E, A](result: Either[E, A]): A =
    result.fold(e => fail(s"fixture refused: $e"), identity)
  private def sha(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map("%02x".format(_)).mkString
  private def checkpoint(label: String): Unit =
    println(s"READY $label")
    System.out.flush()
    assert(scala.io.StdIn.readLine() == "continue")
    println(s"HEAP $label used=${heap.getHeapMemoryUsage.getUsed}")

  @noinline private def retained(env: ConsensusMirrorEnv, content: Array[Byte]): Unit =
    val running = new AtomicBoolean(true)
    val peak = new AtomicLong(heap.getHeapMemoryUsage.getUsed)
    val sampler = new Thread(
      () => {
        while running.get() do
          peak.accumulateAndGet(heap.getHeapMemoryUsage.getUsed, java.lang.Math.max)
          Thread.sleep(1)
      },
      "heap-sampler"
    )
    sampler.start()
    val state =
      try checked(SnapshotV7.decode(env, content))
      finally
        running.set(false)
        sampler.join()
    println(s"PEAK used=${peak.get()}")
    println(s"DIGEST state ${sha(state.toString.getBytes("UTF-8"))}")
    checkpoint("LIVE")
    java.lang.ref.Reference.reachabilityFence(state)

  def main(args: Array[String]): Unit =
    assert(args.length == 4, "mode, label, snapshot, configs")
    val raw = Files.readAllBytes(Path.of(args(2)))
    val content = checked(
      FileLedgerSnapshots.verified(raw).flatMap(xyz.kd5ujc.scarab.node.NativeSnapshots.current)
    )
    val env = checked(NodeSetup.fromDirectory(Path.of(args(3)))).mirrorEnv()
    println(s"INPUT ${args(1)} bytes=${raw.length} sha256=${sha(raw)}")
    if args(0) == "memory" then
      checkpoint("BASE")
      retained(env, content)
      checkpoint("RELEASED")
    else
      val initial = checked(SnapshotV7.decode(env, content))
      (0 until 10).foreach(_ => assertEquals(checked(SnapshotV7.decode(env, content)), initial))
      val rows = (0 until 9).map { sample =>
        val before = allocation.getCurrentThreadAllocatedBytes
        val start = System.nanoTime()
        val result = SnapshotV7.decode(env, content)
        val elapsed = System.nanoTime() - start
        val allocated = allocation.getCurrentThreadAllocatedBytes - before
        assertEquals(checked(result), initial)
        println(s"SAMPLE sample=$sample ns=$elapsed allocated=$allocated")
        (elapsed, allocated)
      }
      println(
        s"METRIC median_ns=${rows.map(_._1).sorted.apply(4)} min_ns=${rows.map(_._1).min} max_ns=${rows.map(_._1).max} allocated=${rows.map(_._2).sorted.apply(4)}"
      )
      println(s"DIGEST state ${sha(initial.toString.getBytes("UTF-8"))}")
      val refusal = SnapshotV7.decode(env, content.dropRight(1))
      assert(refusal.isLeft)
      println(s"DIGEST refusal ${sha(refusal.toString.getBytes("UTF-8"))}")
    java.lang.ref.Reference.reachabilityFence(raw)
