// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.conway

import java.lang.management.ManagementFactory
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor}
import xyz.kd5ujc.scarab.ledger.{Block, DetachedRecords, LedgerDecodeError, Tx}
import xyz.kd5ujc.scarab.protocol.{Era, StateLayout}

/** Development tool: what reading Conway transactions into the era's types costs, in one pass
  * ([[ConwayTx.decode]], [[ConwayTx.fromEnvelope]], #85) and in the two passes they replaced (the
  * flat decoder at Conway, then the projection of what it read: [[ConwayTx.decodeByFlat]],
  * [[ConwayTx.fromEnvelopeByFlat]]). The transactions are the recorded Conway ones: those of the
  * recorded blocks (`fixtures/eras/blocks.hex`, `fixtures/preview/conway.hex`,
  * `fixtures/preview/conway-governance.hex` and `fixtures/preview/duplicate-redeemer-conway.hex`)
  * and the preprod transactions (`fixtures/preprod/tx-*.hex`), repeated to `copies` times their
  * number. Each is read in its block form, its parts apart as a block body holds them; in its
  * mempool form, its whole bytes ([[ConwayTx.fromEnvelope]]); and on the node's pool's route, its
  * bytes split and read by the flat decoder before its parts are read by Conway's decoder in their
  * block form (`MempoolState.parseIn`, then `ConwayRead.of`). After `warm-up` rounds that are not
  * kept, each round times the six workloads once, the one pass and the two passes alternated in
  * their order round by round, with the thread's allocation counted; the medians and ranges are
  * printed, and the ratio of the two passes to the one in each round.
  *
  * `tools/runMain xyz.kd5ujc.scarab.ledger.era.conway.ConwayDecodeBench [rounds] [warm-up]
  * [copies]` from the checkout's root (40 rounds, 20 warm-up rounds and 8 copies when not given).
  * Not a gate.
  */
@SuppressWarnings(
  Array("org.wartremover.warts.Var", "org.wartremover.warts.While", "org.wartremover.warts.Null")
)
object ConwayDecodeBench:
  private val bean = ManagementFactory.getThreadMXBean.asInstanceOf[com.sun.management.ThreadMXBean]
  @volatile private var sink: AnyRef = null

  private final case class Sample(ns: Long, bytes: Long)

  private val layout = StateLayout.Node11_1

  private def hexLines(path: String): Vector[Bytes] =
    Files
      .readAllLines(Path.of(path))
      .asScala
      .toVector
      .map(_.trim.split("\\s+").last)
      .filter(h => h.nonEmpty && !h.startsWith("8200") && !h.startsWith("8201"))
      .flatMap(Bytes.fromHex(_).toOption)

  /** The transactions of the recorded Conway blocks, and the preprod transactions split into their
    * parts as the flat decoder splits a submitted one.
    */
  private def transactions: Vector[Block.Transaction] =
    val blocks = Vector(
      "fixtures/eras/blocks.hex",
      "fixtures/preview/conway.hex",
      "fixtures/preview/conway-governance.hex",
      "fixtures/preview/duplicate-redeemer-conway.hex"
    ).flatMap(hexLines)
      .flatMap(Block.decodeShelley(_).toOption)
      .collect {
        case b: Block.Shelley if b.era == Era.Conway => b.transactions
      }
      .flatten
    val preprod = Files
      .list(Path.of("fixtures/preprod"))
      .iterator()
      .asScala
      .map(_.toString)
      .filter(p => p.contains("/tx-") && p.endsWith(".hex"))
      .toVector
      .sorted
      .flatMap(p => Bytes.fromHex(Files.readString(Path.of(p)).trim).toOption)
      .flatMap(raw => Tx.fromEnvelope(raw, Era.Conway).toOption.map(_ => raw))
      .flatMap(raw =>
        xyz.kd5ujc.scarab.codec.Cbor
          .decode(raw)
          .toOption
          .flatMap(Tx.envelopeParts(_, Era.Conway).toOption)
      )
    blocks ++ preprod

  private val onePass: Block.Transaction => AnyRef = ConwayTx.decode(_, layout)
  private val twoPasses: Block.Transaction => AnyRef = ConwayTx.decodeByFlat(_, layout)
  private val onePassMempool: Bytes => AnyRef = ConwayTx.fromEnvelope(_, layout)
  private val twoPassesMempool: Bytes => AnyRef = ConwayTx.fromEnvelopeByFlat(_, layout)

  /** A submitted transaction as the node's pool reads it (`MempoolState.parseIn`, then
    * `ConwayRead.of`): its bytes split into parts and read by the flat decoder, then its parts read
    * by Conway's decoder `decode`, one pass or two.
    */
  private def pool(
      decode: (Block.Transaction, StateLayout) => AnyRef
  ): Bytes => AnyRef = raw =>
    DetachedRecords
      .read(raw, Cbor.Limits.Unbounded, DetachedRecords.Shapes.transaction(Era.Conway))(
        LedgerDecodeError.Syntax(_)
      )(root =>
        Tx.envelopeParts(root, Era.Conway).flatMap(t => Tx.fromBlock(t, Era.Conway).map(_ => t))
      )
      .map(decode(_, layout))
  private val onePassPool: Bytes => AnyRef = pool(ConwayTx.decode)
  private val twoPassesPool: Bytes => AnyRef = pool(ConwayTx.decodeByFlat)

  private def run[A](inputs: Vector[A])(f: A => AnyRef): Sample =
    val before = bean.getCurrentThreadAllocatedBytes
    val t0 = System.nanoTime()
    var j = 0
    while j < inputs.size do
      sink = f(inputs(j))
      j += 1
    Sample(System.nanoTime() - t0, bean.getCurrentThreadAllocatedBytes - before)

  private def median(xs: Seq[Double]): Double =
    val s = xs.sorted
    if s.size % 2 == 1 then s(s.size / 2)
    else (s(s.size / 2 - 1) + s(s.size / 2)) / 2

  private def report(name: String, samples: Seq[Sample]): Unit =
    val ms = samples.map(_.ns / 1e6)
    println(
      f"RESULT $name%-22s median ${median(ms)}%8.2f ms  range ${ms.min}%8.2f to ${ms.max}%8.2f ms  allocated ${median(samples.map(_.bytes.toDouble)) / 1e6}%8.1f MB"
    )

  private def ratio(name: String, two: Seq[Sample], one: Seq[Sample]): Unit =
    val rs = two.zip(one).map((t, o) => t.ns.toDouble / o.ns.toDouble)
    println(
      f"RATIO  $name%-22s two passes / one pass: median ${median(rs)}%5.3f  range ${rs.min}%5.3f to ${rs.max}%5.3f"
    )

  def main(args: Array[String]): Unit =
    val rounds = args.lift(0).flatMap(_.toIntOption).getOrElse(40)
    val warmUp = args.lift(1).flatMap(_.toIntOption).getOrElse(20)
    val copies = args.lift(2).flatMap(_.toIntOption).getOrElse(8)
    val txs = transactions
    val parts = Vector.fill(copies)(txs).flatten
    val wholes = parts.map(Tx.envelope)
    val refused = txs.count(t =>
      ConwayTx.decode(t, layout) match
        case Right(_) => false
        case _        => true
    )
    val bytes = txs.map(_.size).sum
    println(
      s"transactions: ${txs.size}, $bytes bytes, timed ${parts.size} a round in each form; $refused refused"
    )
    println(
      s"jvm ${System.getProperty("java.vm.version")}, processors ${Runtime.getRuntime.availableProcessors}, rounds $rounds, warm-up $warmUp"
    )
    // each pair, the one pass and the two passes, in one order on even rounds and the other on odd
    def pair(f: Bytes => AnyRef, g: Bytes => AnyRef)(inputs: Vector[Bytes], r: Int) =
      if r % 2 == 0 then
        val a = run(inputs)(f)
        (a, run(inputs)(g))
      else
        val b = run(inputs)(g)
        (run(inputs)(f), b)
    def round(r: Int) =
      val block =
        if r % 2 == 0 then
          val a = run(parts)(onePass)
          (a, run(parts)(twoPasses))
        else
          val b = run(parts)(twoPasses)
          (run(parts)(onePass), b)
      (
        block,
        pair(onePassMempool, twoPassesMempool)(wholes, r),
        pair(onePassPool, twoPassesPool)(wholes, r)
      )
    (0 until warmUp).foreach(round)
    val samples = (0 until rounds).map(round)
    val forms = Vector(
      "block" -> samples.map(_._1),
      "mempool" -> samples.map(_._2),
      "pool route" -> samples.map(_._3)
    )
    forms.foreach { (name, s) =>
      report(s"$name one pass", s.map(_._1))
      report(s"$name two passes", s.map(_._2))
    }
    forms.foreach((name, s) => ratio(name, s.map(_._2), s.map(_._1)))
    if sink == null then println("no result")
