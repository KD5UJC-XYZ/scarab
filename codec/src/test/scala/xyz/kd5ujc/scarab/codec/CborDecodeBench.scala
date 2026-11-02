// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.codec

import java.lang.management.ManagementFactory

/** Development tool: [[Cbor]]'s decoder (production's, which writes the index of the items) against
  * [[CborDecodeFunctional]] (the same checks and the same index without mutation), timed and with
  * the thread's allocation counted (issue 650; the method of `docs/performance.md` §40, as
  * `WireSkipBench` for the protocol reader).
  *
  * `codec/Test/runMain xyz.kd5ujc.scarab.codec.CborDecodeBench [imperative|functional] [rounds]`
  *
  * The first argument names the variant each workload starts with; the rounds then alternate ABBA.
  * Every input is read by both outside the timed region and the results compared; a difference ends
  * the run with status 1.
  */
object CborDecodeBench:
  private val bean = ManagementFactory.getThreadMXBean.asInstanceOf[com.sun.management.ThreadMXBean]
  @volatile private var sink: AnyRef = null

  private enum Variant:
    case Imperative, Functional

    /** The decode of `input`: production's document, or the functional index. */
    def decode(input: Bytes, limits: Cbor.Limits): Either[Cbor.Error, AnyRef] = this match
      case Imperative => Cbor.decode(input, limits)
      case Functional => CborDecodeFunctional.decode(input, limits)

  private final case class Workload(
      name: String,
      inputs: Vector[Bytes],
      limits: Cbor.Limits
  ):
    val bytes: Long = inputs.map(_.size.toLong).sum

  private final case class Sample(nsPerOp: Double, bytesPerOp: Double)

  private def run(variant: Variant, workload: Workload, ops: Int): Sample =
    val before = bean.getCurrentThreadAllocatedBytes
    val t0 = System.nanoTime()
    var i = 0
    while i < ops do
      var j = 0
      while j < workload.inputs.size do
        sink = variant.decode(workload.inputs(j), workload.limits)
        j += 1
      i += 1
    val elapsed = System.nanoTime() - t0
    val allocated = bean.getCurrentThreadAllocatedBytes - before
    Sample(elapsed.toDouble / ops, allocated.toDouble / ops)

  private def median(xs: Vector[Double]): Double =
    val s = xs.sorted
    if s.size % 2 == 1 then s(s.size / 2) else (s(s.size / 2 - 1) + s(s.size / 2)) / 2

  /** How many passes over the workload take about `millis` milliseconds. */
  private def calibrate(variant: Variant, workload: Workload, millis: Long): Int =
    val one = run(variant, workload, 1).nsPerOp.max(1.0)
    ((millis * 1e6) / one).toInt.max(1)

  private def measure(workload: Workload, first: Variant, rounds: Int): Unit =
    val second = if first == Variant.Imperative then Variant.Functional else Variant.Imperative
    // Warm both up to a steady state before any timed round.
    (0 until 8).foreach { _ =>
      run(first, workload, calibrate(first, workload, 50))
      run(second, workload, calibrate(second, workload, 50))
    }
    val ops = calibrate(Variant.Imperative, workload, 250)
    val order = (0 until rounds).flatMap(r =>
      if r % 2 == 0 then Vector(first, second) else Vector(second, first)
    )
    val samples = order.map(v => v -> run(v, workload, ops)).groupMap(_._1)(_._2)
    def line(v: Variant): (Double, Double) =
      val s = samples(v)
      val ns = median(s.map(_.nsPerOp).toVector)
      val alloc = median(s.map(_.bytesPerOp).toVector)
      val mbs = workload.bytes / ns * 1e9 / 1e6
      println(
        f"RESULT ${workload.name}%-40s $v%-10s ns/op ${ns}%12.0f (min ${s.map(_.nsPerOp).min}%12.0f max ${s.map(_.nsPerOp).max}%12.0f)  MB/s $mbs%8.1f  B/op $alloc%14.0f"
      )
      (ns, alloc)
    val (ni, ai) = line(Variant.Imperative)
    val (nf, af) = line(Variant.Functional)
    println(
      f"RATIO  ${workload.name}%-40s functional/imperative time ${nf / ni}%.3f allocation ${af / ai}%.3f"
    )

  /** Every block of the corpus as the elements of one indefinite array: one large message. */
  private def oneMessage(blocks: Vector[Bytes]): Bytes =
    val out = new java.io.ByteArrayOutputStream()
    out.write(0x9f)
    blocks.foreach(b => out.write(b.toArray))
    out.write(0xff)
    Bytes.wrap(out.toByteArray)

  def main(args: Array[String]): Unit =
    val first = args.headOption match
      case Some("functional") => Variant.Functional
      case _                  => Variant.Imperative
    val rounds = args.lift(1).flatMap(_.toIntOption).getOrElse(10)
    val blocks = CborCorpus.Blocks
    val large = oneMessage(CborCorpus.all)
    val workloads = Vector(
      Workload(s"preview blocks (${CborCorpus.preview.size})", CborCorpus.preview, blocks),
      Workload(s"preprod blocks (${CborCorpus.preprod.size})", CborCorpus.preprod, blocks),
      Workload(s"mainnet blocks (${CborCorpus.mainnet.size})", CborCorpus.mainnet, blocks),
      Workload(s"all blocks in one message (${large.size} B)", Vector(large), blocks)
    )
    // the same index from both, read outside the timed region
    def index(input: Bytes, limits: Cbor.Limits) =
      Cbor.decode(input, limits).map(root => (root.offset, root.original.size))
    val compared = workloads.flatMap(w =>
      w.inputs.map(i =>
        (
          index(i, w.limits),
          CborDecodeFunctional.decode(i, w.limits).map(d => (d.starts.head, d.end - d.starts.head)),
          CborDecodeFunctional.decode(i, w.limits).map(_.starts.size)
        )
      )
    )
    if compared.exists((a, b, n) => a.isLeft || a != b || n.isLeft) then
      println(s"inputs not decoded or not equal: ${compared.count(_._1.isLeft)} refused, ${compared
          .count((a, b, _) => a != b)} differ")
      sys.exit(1)
    println(
      s"EQUAL ${compared.size} inputs: both decode them, to items of the same extent (${compared.flatMap(_._3.toOption).sum} items)"
    )
    workloads.foreach(w =>
      println(s"workload ${w.name}: ${w.inputs.size} inputs, ${w.bytes} bytes")
    )
    println(
      s"jvm ${System.getProperty("java.vm.version")}, processors ${Runtime.getRuntime.availableProcessors}, max heap ${Runtime.getRuntime.maxMemory >> 20} MiB, first $first, rounds $rounds"
    )
    workloads.foreach(measure(_, first, rounds))
    if sink == null then println("no result")
