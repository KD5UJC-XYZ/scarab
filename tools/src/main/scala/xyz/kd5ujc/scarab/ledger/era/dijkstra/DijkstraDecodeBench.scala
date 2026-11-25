// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.dijkstra

import java.io.ByteArrayInputStream
import java.lang.management.ManagementFactory
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.zip.GZIPInputStream
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor}
import xyz.kd5ujc.scarab.ledger.CborRead

/** Development tool: what reading an endorser block's closure into Dijkstra's types costs
  * (`deserializeTxForLeios`, [[xyz.kd5ujc.scarab.ledger.era.rules.DijkstraClosure]]), the era
  * decoder in one pass ([[DijkstraTx.inBlock]]) against the two it replaced, the flat block
  * decoder's transaction and its projection ([[DijkstraTx.inBlockByFlat]]), #85. The closure is the
  * recorded one of the public Leios testnet's endorser block at slot 1,455,869
  * (`fixtures/leios/testnet/eb-1455869.tsv.gz`, 323 transactions), repeated to `copies` times its
  * size (1,292 transactions at the default 4), and each transaction is read whole from its bytes,
  * the CBOR decode included, as the closure reads it. After `warm-up` rounds that are not kept,
  * each round times one closure with each decoder, the two alternated in their order round by
  * round, with the thread's allocation counted; the medians and ranges are printed. Every
  * transaction is first checked to read to the same value both ways.
  *
  * `tools/runMain xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraDecodeBench [rounds] [warm-up]
  * [copies]` from the checkout's root (40 rounds, 20 warm-up rounds and 4 copies when not given).
  * Not a gate.
  */
@SuppressWarnings(
  Array("org.wartremover.warts.Var", "org.wartremover.warts.While", "org.wartremover.warts.Null")
)
object DijkstraDecodeBench:
  private val bean = ManagementFactory.getThreadMXBean.asInstanceOf[com.sun.management.ThreadMXBean]
  @volatile private var sink: AnyRef = null

  private final case class Sample(ns: Long, bytes: Long)

  /** The transactions of the recorded closure, in the endorser block's order. */
  private def closure: Vector[Bytes] =
    val gz = Files.readAllBytes(Path.of("fixtures/leios/testnet/eb-1455869.tsv.gz"))
    val text = new String(
      new GZIPInputStream(new ByteArrayInputStream(gz)).readAllBytes(),
      StandardCharsets.UTF_8
    )
    text.linesIterator
      .map(_.split('\t').toVector)
      .collect { case Vector("tx", _, hex) => Bytes.fromHex(hex) }
      .collect { case Right(b) => b }
      .toVector

  private def read(decoder: xyz.kd5ujc.scarab.codec.Node => AnyRef)(raw: Bytes): AnyRef =
    CborRead.decode(raw, Cbor.Limits.Unbounded) match
      case Right(n) => decoder(n)
      case Left(e)  => e

  private val onePass: Bytes => AnyRef = read(DijkstraTx.inBlock)
  private val twoPass: Bytes => AnyRef = read(DijkstraTx.inBlockByFlat)

  private def run(inputs: Vector[Bytes])(f: Bytes => AnyRef): Sample =
    val before = bean.getCurrentThreadAllocatedBytes
    val t0 = System.nanoTime()
    var j = 0
    while j < inputs.size do
      sink = f(inputs(j))
      j += 1
    Sample(System.nanoTime() - t0, bean.getCurrentThreadAllocatedBytes - before)

  private def median(xs: Seq[Long]): Double =
    val s = xs.sorted
    if s.size % 2 == 1 then s(s.size / 2).toDouble
    else (s(s.size / 2 - 1) + s(s.size / 2)).toDouble / 2

  private def report(name: String, samples: Seq[Sample]): Unit =
    val ms = samples.map(_.ns)
    println(
      f"RESULT $name%-10s median ${median(ms) / 1e6}%8.2f ms  range ${ms.min / 1e6}%8.2f to ${ms.max / 1e6}%8.2f ms  allocated ${median(samples.map(_.bytes)) / 1e6}%8.1f MB"
    )

  def main(args: Array[String]): Unit =
    val rounds = args.lift(0).flatMap(_.toIntOption).getOrElse(40)
    val warmUp = args.lift(1).flatMap(_.toIntOption).getOrElse(20)
    val copies = args.lift(2).flatMap(_.toIntOption).getOrElse(4)
    val one = closure
    val inputs = Vector.fill(copies)(one).flatten
    val differ = one.count(raw => onePass(raw) != twoPass(raw))
    val refused = one.count(raw =>
      twoPass(raw) match
        case Right(_) => false
        case _        => true
    )
    println(
      s"closure: ${one.size} transactions, ${one.map(_.size.toLong).sum} bytes, timed ${inputs.size} a round; $refused refused, $differ read differently"
    )
    println(
      s"jvm ${System.getProperty("java.vm.version")}, processors ${Runtime.getRuntime.availableProcessors}, rounds $rounds, warm-up $warmUp"
    )
    (0 until warmUp).foreach(_ => (run(inputs)(onePass), run(inputs)(twoPass)))
    val samples = (0 until rounds).map { r =>
      if r % 2 == 0 then
        val a = run(inputs)(onePass)
        (a, run(inputs)(twoPass))
      else
        val b = run(inputs)(twoPass)
        (run(inputs)(onePass), b)
    }
    report("one pass", samples.map(_._1))
    report("two passes", samples.map(_._2))
    val ratios = samples.map((a, b) => b.ns.toDouble / a.ns.toDouble).sorted
    println(
      f"RESULT two passes / one pass: median ${ratios(ratios.size / 2)}%.2f, range ${ratios.head}%.2f to ${ratios.last}%.2f"
    )
    if sink == null then println("no result")
