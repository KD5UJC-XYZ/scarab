// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.tools

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import java.lang.management.ManagementFactory
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.node.{NodeSetup, VerificationCache}

/** Development tool (issue 125): the throughput of [[VerificationCache.warm]] on captured blocks.
  *
  * `WarmBenchMain <blocks.hex> <batch size> <blocks> [<concurrent batches>]` warms the first
  * `<blocks>` blocks of the file in batches of `<batch size>` (a range a peer fetched), with the
  * given number of batches in flight at once (the follower prepares each fetched range as it
  * arrives), against a fresh cache, after one warm-up pass. Prints blocks per second and the
  * process CPU time over the wall time (cores used). The VRF checks are not warmed (they need the
  * epoch nonce of the rules); witnesses and operational certificates are.
  */
object WarmBenchMain:
  def main(args: Array[String]): Unit =
    val file = Path.of(args(0))
    val batch = args(1).toInt
    val total = args(2).toInt
    val inFlight = args.lift(3).fold(1)(_.toInt)
    val setup = NodeSetup
      .fromDirectory(Path.of("fixtures/eras/genesis"))
      .fold(e => sys.error(e.message), identity)
    val praos = setup.praos.copy(slotsPerKesPeriod = BigInt(129600), maxKesEvolutions = BigInt(62))
    val raws = Files
      .readAllLines(file)
      .asScala
      .iterator
      .filter(_.nonEmpty)
      .take(total)
      .map(l => Bytes.fromHex(l).fold(e => sys.error(e), identity))
      .toVector
    val batches = raws.grouped(batch).toVector
    val cpus = Runtime.getRuntime.availableProcessors
    val os = ManagementFactory.getOperatingSystemMXBean
      .asInstanceOf[com.sun.management.OperatingSystemMXBean]
    def run(): (Double, Double) =
      val cache = VerificationCache.unsafe(cpus)
      val cpu0 = os.getProcessCpuTime
      val t0 = System.nanoTime
      fs2.Stream
        .emits(batches)
        .covary[IO]
        .parEvalMapUnordered(inFlight)(b => cache.warm[IO](b, praos, cpus))
        .compile
        .drain
        .unsafeRunSync()
      val wall = (System.nanoTime - t0) / 1e9
      val cpu = (os.getProcessCpuTime - cpu0) / 1e9
      cache.shutdown()
      (raws.size / wall, cpu / wall)
    run(): Unit // warm-up: JIT, tables
    val (rate, cores) = run()
    println(
      f"cpus=$cpus batch=$batch inFlight=$inFlight blocks=${raws.size} blocks/s=$rate%.1f cores=$cores%.2f"
    )
