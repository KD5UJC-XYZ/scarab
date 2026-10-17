// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.network.selection.PeerMetrics

/** The cost of the observation callbacks that feed the peer metrics (G1.2), on the per-header and
  * per-block path of a hot session. Not part of `check`: run it with
  * `sbt "node/Test/runMain xyz.kd5ujc.scarab.node.PeerMetricsBench"`; it prints the median
  * nanoseconds per header, per block and per decision. The figures are in `docs/performance.md`
  * section 19.
  *
  * Steady state: the boards are full (180 slots), 20 peers have reported, and each call reports
  * slots newer than any on the board, as a session does when it follows the chain.
  */
object PeerMetricsBench:
  private val warmups = 20
  private val runs = 41

  /** Keeps results alive so the JIT cannot discard the work. */
  @volatile private var sink = 0L

  private def median(xs: Vector[Double]): Double = xs.sorted.apply(xs.size / 2)

  /** Median ns per unit over `runs` timed runs after `warmups` untimed ones. */
  private def measure(units: Int)(work: => Long): Double =
    (0 until warmups).foreach(_ => sink += work)
    median(Vector.fill(runs) {
      val t0 = System.nanoTime()
      sink += work
      (System.nanoTime() - t0).toDouble / units
    })

  private def warm: PeerMetrics[Int] =
    (1L to 360L).foldLeft(PeerMetrics.empty[Int]()) { (m, s) =>
      m.reportHeader((s % 20).toInt, s, s.seconds).reportFetch((s % 20).toInt, s, 60000L, s.seconds)
    }

  private def report(label: String, ns: Double): Unit =
    println(f"BENCH $label%-62s $ns%9.0f ns")

  def main(args: Array[String]): Unit =
    // several rounds in one JVM, interleaved, so the JIT state is the same for all
    for _ <- 1 to 3 do
      for batch <- List(1, 16, 128) do
        var m = warm
        var slot = 1000L
        val ns = measure(batch) {
          val slots = Vector.tabulate(batch)(i => slot + i)
          slot += batch
          m = m.reportHeaders(7, slots, slot.seconds)
          m.sizes._1.toLong
        }
        report(s"headers, batches of $batch, per header (pure)", ns)
      for batch <- List(1, 100) do
        var m = warm
        var slot = 1000L
        val ns = measure(batch) {
          val blocks = Vector.tabulate(batch)(i => (slot + i) -> 70000L)
          slot += batch
          m = m.reportFetches(7, blocks, slot.seconds)
          m.sizes._2.toLong
        }
        report(s"blocks, batches of $batch, per block (pure)", ns)
      // as the session does it: one Ref update per batch, in IO (a thousand inside one run of the
      // fiber, so that starting it is not counted)
      for batch <- List(1, 128) do
        val ref = Ref.unsafe[IO, PeerMetrics[Int]](warm)
        var slot = 1000L
        val ns = measure(1000 * batch) {
          val base = slot
          slot += 1000L * batch
          IO.defer {
            (0 until 1000).toList.traverse_ { k =>
              val first = base + k.toLong * batch
              ref.update(
                _.reportHeaders(7, Vector.tabulate(batch)(i => first + i), (first + batch).seconds)
              )
            }
          }.unsafeRunSync()
          0L
        }
        report(s"headers, batches of $batch, per header (Ref.update in IO)", ns)
      val m = warm
      report(
        "hotScores, 20 peers (once per governor pass that demotes)",
        measure(1)(m.hotScores.size.toLong)
      )
      println()
    println(s"checksum $sink")
