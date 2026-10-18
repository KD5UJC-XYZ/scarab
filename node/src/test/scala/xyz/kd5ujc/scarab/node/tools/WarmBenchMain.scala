// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.tools

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import java.lang.management.ManagementFactory
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.Verdicts
import xyz.kd5ujc.scarab.node.{NodeRules, NodeSetup, VerificationCache}

/** Development tool (issue 125): the throughput of [[VerificationCache.warm]] on captured blocks.
  *
  * `WarmBenchMain <blocks.hex> <batch size> <blocks> [<concurrent batches>]` warms the first
  * `<blocks>` blocks of the file in batches of `<batch size>` (a range a peer fetched), with the
  * given number of batches in flight at once (the follower prepares each fetched range as it
  * arrives), against a fresh cache, after one warm-up pass. Prints blocks per second and the
  * process CPU time over the wall time (cores used). The VRF checks are not warmed (they need the
  * epoch nonce of the rules); witnesses and operational certificates are.
  *
  * It also prints what the blocks hold (transactions, distinct witnesses, Plutus scripts, bytes per
  * block), how many witness verifications `warm` computed (and skipped) against the number the
  * rules look up, and the serial cost of one verification (issue 147).
  */
object WarmBenchMain:
  /** The witness check as it was before issue 147 (canonical encodings by compressing, the result
    * compared by compressing), to time the present one against in the same JVM.
    */
  private def before(key: Bytes, signature: Bytes, message: Bytes): Boolean =
    import com.weavechain.curve25519.{CompressedEdwardsY, EdwardsPoint, Scalar}
    def point(bytes: Array[Byte]) =
      try
        val q = new CompressedEdwardsY(bytes.clone()).decompress()
        Option(q).filter(r =>
          java.util.Arrays.equals(r.compress().toByteArray, bytes) && !r.isSmallOrder
        )
      catch case _: com.weavechain.curve25519.InvalidEncodingException => None
    val k = key.toArray
    val sig = signature.toArray
    val r = sig.take(32)
    (for
      a <- point(k)
      _ <- point(r)
      sc <- scala.util.Try(Scalar.fromCanonicalBytes(sig.drop(32))).toOption
    yield
      val digest = java.security.MessageDigest.getInstance("SHA-512")
      digest.update(r)
      digest.update(k)
      digest.update(message.toArray)
      val h = Scalar.fromBytesModOrderWide(digest.digest())
      val check = EdwardsPoint.vartimeDoubleScalarMultiplyBasepoint(h, a.negate(), sc)
      java.util.Arrays.equals(check.compress().toByteArray, r)
    ).contains(true)

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
      .map(l => Bytes.fromHex(l).fold(e => sys.error(e.message), identity))
      .toVector
    val batches = raws.grouped(batch).toVector
    val decoded = raws.flatMap(r => NodeRules.decodeShelley(r))
    val txs = decoded.flatMap(_.txs.flatMap(_.toOption))
    val triples = decoded.map(p =>
      p.txs
        .flatMap(_.toOption)
        .flatMap { tx =>
          (tx.witnesses.vkeys ++ tx.witnesses.bootstrap.map(w => w.vkey -> w.signature))
            .map((vk, sig) => (vk, sig, tx.body.id))
        }
        .distinct
    )
    val witnessCount = triples.map(_.size).sum
    val distinctKeys = triples.flatten.map(_._1).distinct.size
    println(
      f"blocks=${raws.size} bytes/block=${raws.map(_.size).sum / raws.size.max(1)} txs/block=${txs.size.toDouble / raws.size.max(1)}%.1f " +
        f"witnesses/block=${witnessCount.toDouble / raws.size.max(1)}%.1f distinctVkeys=$distinctKeys " +
        f"redeemers/block=${txs.map(_.witnesses.redeemers.size).sum.toDouble / raws.size.max(1)}%.1f " +
        f"plutusTxs/block=${txs.count(_.witnesses.redeemers.nonEmpty).toDouble / raws.size.max(1)}%.1f " +
        f"exUnitsSteps/block=${txs.map(_.witnesses.totalExUnits.steps.toDouble).sum / raws.size.max(1) / 1e9}%.2fG " +
        f"exUnitsMem/block=${txs.map(_.witnesses.totalExUnits.mem.toDouble).sum / raws.size.max(1) / 1e6}%.2fM " +
        f"maxWitnesses/block=${triples.map(_.size).maxOption.getOrElse(0)}"
    )
    locally {
      val sample = triples.flatten.take(2000)
      sample.foreach((a, b, c) => Verdicts.Direct.witness(a, b, c): Unit) // JIT
      val t = System.nanoTime
      sample.foreach((a, b, c) => Verdicts.Direct.witness(a, b, c): Unit)
      println(
        f"serial verification: ${(System.nanoTime - t) / 1e3 / sample.size.max(1)}%.1f us each"
      )
      // the previous verifier against this one, alternating, same JVM, same inputs
      sample.foreach((a, b, c) => before(a, b, c): Unit) // JIT
      val (oldNs, newNs) = (0 until 4).foldLeft((0L, 0L)) { case ((o, n), _) =>
        val t1 = System.nanoTime
        sample.foreach((a, b, c) => before(a, b, c): Unit)
        val t2 = System.nanoTime
        sample.foreach((a, b, c) => Verdicts.Direct.witness(a, b, c): Unit)
        val t3 = System.nanoTime
        (o + t2 - t1, n + t3 - t2)
      }
      val verdictsAgree =
        sample.forall((a, b, c) => before(a, b, c) == Verdicts.Direct.witness(a, b, c))
      println(
        f"alternating: before ${oldNs / 4e3 / sample.size.max(1)}%.1f us, now ${newNs / 4e3 / sample.size.max(1)}%.1f us each, verdicts agree: $verdictsAgree"
      )
      val mx = ManagementFactory.getThreadMXBean.asInstanceOf[com.sun.management.ThreadMXBean]
      val a0 = mx.getThreadAllocatedBytes(Thread.currentThread.threadId)
      sample.foreach((a, b, c) => Verdicts.Direct.witness(a, b, c): Unit)
      println(
        s"allocation: ${(mx.getThreadAllocatedBytes(Thread.currentThread.threadId) - a0) / sample.size.max(1)} bytes per verification"
      )
      // what the header side costs per block: the operational certificate with its KES signature,
      // and the two VRF proofs of a TPraos header (a wrong alpha costs the same as a right one)
      val headers = decoded.map(_.block.header).take(200)
      def perHeader(f: => Unit): Double =
        val t0 = System.nanoTime
        f
        (System.nanoTime - t0) / 1e3 / headers.size.max(1)
      def opCerts(): Unit =
        headers.foreach(h => Verdicts.Direct.opCert(h, BigInt(129600), BigInt(62)): Unit)
      opCerts() // JIT
      println(f"operational certificate + KES: ${perHeader(opCerts())}%.1f us per header")
      val alpha = Bytes.fromArray(Array.fill[Byte](32)(7))
      def vrfs(): Unit =
        headers.foreach { h =>
          val certs = h.vrf match
            case xyz.kd5ujc.scarab.ledger.HeaderVrf.TPraos(n, l) => Vector(n, l)
            case xyz.kd5ujc.scarab.ledger.HeaderVrf.Praos(r)     => Vector(r)
          certs.foreach(c => Verdicts.Direct.tpraosVrf(alpha, h.vrfVkey, c.proof, c.output): Unit)
        }
      vrfs() // JIT
      println(f"VRF proofs of a header: ${perHeader(vrfs())}%.1f us per header")
    }
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
      // the rules look up every witness once
      triples.flatten.foreach((a, b, c) => cache.witness(a, b, c): Unit)
      println(
        s"  warm computed ${cache.warmedStats} (skipped/total), rules lookups hit ${cache.witnessStats}"
      )
      cache.shutdown()
      (raws.size / wall, cpu / wall)
    run(): Unit // warm-up: JIT, tables
    val (rate, cores) = run()
    println(
      f"cpus=$cpus batch=$batch inFlight=$inFlight blocks=${raws.size} blocks/s=$rate%.1f cores=$cores%.2f"
    )
