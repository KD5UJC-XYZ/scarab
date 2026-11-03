// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import java.lang.management.ManagementFactory
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor}
import xyz.kd5ujc.scarab.protocol.WireCorpus

/** Development tool: the cost of reading detached `RecD` records (#687) on real blocks. It times
  * [[Block.decodeShelley]] over the Shelley-based blocks of the protocol corpus (`WireCorpus`: the
  * Preview immutable chunks, the first preprod blocks, the mainnet fixtures), with the thread's
  * allocation counted, and reports how many of them hold an empty indefinite list (the blocks whose
  * read also walks them, [[DetachedRecords.read]]) and what that walk costs alone, on those and on
  * every block. The method is `docs/performance.md` §40's, as `CborDecodeBench`.
  *
  * `ledger/Test/runMain xyz.kd5ujc.scarab.ledger.DetachedRecordsBench [rounds]`
  */
@SuppressWarnings(
  Array("org.wartremover.warts.Var", "org.wartremover.warts.While", "org.wartremover.warts.Null")
)
object DetachedRecordsBench:
  private val bean = ManagementFactory.getThreadMXBean.asInstanceOf[com.sun.management.ThreadMXBean]
  @volatile private var sink: AnyRef = null

  private final case class Sample(nsPerOp: Double, bytesPerOp: Double)

  private def run(inputs: Vector[Bytes], ops: Int)(f: Bytes => AnyRef): Sample =
    val before = bean.getCurrentThreadAllocatedBytes
    val t0 = System.nanoTime()
    var i = 0
    while i < ops do
      var j = 0
      while j < inputs.size do
        sink = f(inputs(j))
        j += 1
      i += 1
    Sample(
      (System.nanoTime() - t0).toDouble / ops,
      (bean.getCurrentThreadAllocatedBytes - before).toDouble / ops
    )

  private def median(xs: Seq[Double]): Double =
    val s = xs.sorted
    if s.size % 2 == 1 then s(s.size / 2) else (s(s.size / 2 - 1) + s(s.size / 2)) / 2

  private def measure(name: String, inputs: Vector[Bytes], rounds: Int)(f: Bytes => AnyRef): Unit =
    (0 until 8).foreach(_ => run(inputs, 1)(f))
    val one = run(inputs, 1)(f).nsPerOp.max(1.0)
    val ops = (250e6 / one).toInt.max(1)
    val samples = (0 until rounds).map(_ => run(inputs, ops)(f))
    val bytes = inputs.map(_.size.toLong).sum
    val ns = median(samples.map(_.nsPerOp))
    println(
      f"RESULT $name%-48s ns/op ${ns}%12.0f (min ${samples.map(_.nsPerOp).min}%12.0f max ${samples.map(_.nsPerOp).max}%12.0f)  MB/s ${bytes / ns * 1e3}%8.1f  B/op ${median(samples.map(_.bytesPerOp))}%14.0f"
    )

  def main(args: Array[String]): Unit =
    val rounds = args.headOption.flatMap(_.toIntOption).getOrElse(10)
    val blocks = WireCorpus.all.filter(b => Block.decodeShelley(b).isRight)
    val limits =
      Cbor.Limits(maxInputBytes = Block.MaxInputBytes, maxItems = 1000000, maxStringBytes = 2500000)
    val walked = blocks.filter(b => Cbor.decode(b, limits).exists(_.decodeHasEmptyIndefiniteList))
    val found = walked.count(b =>
      DetachedRecords.detached(b, DetachedRecords.Shapes.block).exists(!_.isEmpty)
    )
    println(
      s"corpus: ${blocks.size} Shelley-based blocks, ${blocks.map(_.size.toLong).sum} bytes; ${walked.size} hold an empty indefinite list; the walk finds detached records in $found"
    )
    println(
      s"jvm ${System.getProperty("java.vm.version")}, processors ${Runtime.getRuntime.availableProcessors}, rounds $rounds"
    )
    measure(s"Block.decodeShelley (${blocks.size} blocks)", blocks, rounds)(Block.decodeShelley(_))
    measure(s"Cbor.decode (${blocks.size} blocks)", blocks, rounds)(Cbor.decode(_, limits))
    // what the walk would cost if every block held an empty indefinite list: a peer's worst case
    measure(s"walk alone (${blocks.size} blocks)", blocks, rounds)(
      DetachedRecords.detached(_, DetachedRecords.Shapes.block)
    )
    if walked.nonEmpty then
      measure(s"Block.decodeShelley (${walked.size} walked blocks)", walked, rounds)(
        Block.decodeShelley(_)
      )
      measure(s"walk alone (${walked.size} walked blocks)", walked, rounds)(
        DetachedRecords.detached(_, DetachedRecords.Shapes.block)
      )
    if sink == null then println("no result")
