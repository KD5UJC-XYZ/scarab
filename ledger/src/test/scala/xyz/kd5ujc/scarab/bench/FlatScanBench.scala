// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.bench

import java.lang.management.ManagementFactory
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scalus.uplc.{ScarabProgramView, Term}
import xyz.kd5ujc.scarab.ledger.PlutusCase
import xyz.kd5ujc.scarab.plutus.{DeepFlatPrograms, FlatScan, FlatScanFunctional, PlutusScriptBytes}

/** Development tool: the production flat decoder ([[FlatScan.decode]], an imperative walker)
  * against [[FlatScanFunctional.decode]] (the same contract without mutation), timed and with the
  * thread's allocation counted (`docs/performance.md`, "Flat script decoding").
  *
  * `ledger/Test/runMain xyz.kd5ujc.scarab.bench.FlatScanBench [imperative|functional] [rounds]`
  *
  * The first argument names the variant each workload starts with; the rounds then alternate ABBA.
  * Every input is decoded by both outside the timed region and the results compared whole (on a
  * large stack, as equality of a deep term recurses); a difference ends the run with status 1.
  */
object FlatScanBench:
  private val bean = ManagementFactory.getThreadMXBean.asInstanceOf[com.sun.management.ThreadMXBean]
  @volatile private var sink: AnyRef = null

  private enum Variant:
    case Imperative, Functional

    def decode(flat: Array[Byte]): Either[FlatScan.Failure, FlatScan.Decoded] = this match
      case Imperative => FlatScan.decode(flat)
      case Functional => FlatScanFunctional.decode(flat)

  private final case class Workload(name: String, inputs: Vector[Array[Byte]]):
    val bytes: Long = inputs.map(_.length.toLong).sum

  private final case class Sample(nsPerOp: Double, bytesPerOp: Double)

  /** The distinct Plutus scripts of the recorded Preview and preprod transactions: witness scripts
    * and the reference scripts of the outputs they spend, reference or offer as collateral.
    */
  private def realScripts: Vector[Array[Byte]] =
    val cases = Vector("fixtures/preview/plutus", "fixtures/preprod/plutus").flatMap { dir =>
      Files.list(Path.of(dir)).iterator.asScala.filter(_.toString.endsWith(".json")).toVector
    }
    cases
      .map(PlutusCase.load)
      .flatMap { c =>
        val outputs = c.spent.values ++ c.references.values ++ c.collateral.values
        c.tx.witnesses.scripts
          .flatMap(_.plutus) ++ outputs.flatMap(_.referenceScript).flatMap(_.plutus)
      }
      .distinctBy(_.hex)
      .flatMap(script => PlutusScriptBytes.flatOf(script.toArray))
      .sortBy(_.length)

  /** One program of about `target` bytes: the terms of real scripts applied to one another, at the
    * latest version among them (`constr` and `case` need 1.1.0).
    */
  private def composed(scripts: Vector[Array[Byte]], target: Int): Option[Array[Byte]] =
    val terms = scripts.reverse.flatMap(FlatScan.decode(_).toOption).map(_.program)
    terms.headOption.map { first =>
      val version = terms.map(_.version).maxOption.getOrElse(first.version)
      val term = terms.tail
        .scanLeft(first.term)((acc, next) => Term.Apply(acc, next.term))
        .find(t => ScarabProgramView.program(version, t).flatEncoded.length >= target)
        .getOrElse(terms.tail.foldLeft(first.term)((acc, next) => Term.Apply(acc, next.term)))
      ScarabProgramView.program(version, term).flatEncoded
    }

  private def onBigStack[A](body: => A): Either[Throwable, A] =
    val result = new java.util.concurrent.atomic.AtomicReference[Either[Throwable, A]]()
    val worker = new Thread(
      null,
      () =>
        result.set(
          try Right(body)
          catch case error: Throwable => Left(error)
        ),
      "flat-bench-compare",
      1024L * 1024 * 1024
    )
    worker.start()
    worker.join()
    result.get()

  private def run(variant: Variant, workload: Workload, ops: Int): Sample =
    val before = bean.getCurrentThreadAllocatedBytes
    val t0 = System.nanoTime()
    var i = 0
    while i < ops do
      var j = 0
      while j < workload.inputs.size do
        sink = variant.decode(workload.inputs(j))
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
    val perScript = workload.inputs.size.toDouble
    def line(v: Variant): (Double, Double) =
      val s = samples(v)
      val ns = median(s.map(_.nsPerOp).toVector)
      val alloc = median(s.map(_.bytesPerOp).toVector)
      val mbs = workload.bytes / ns * 1e9 / 1e6
      println(
        f"RESULT ${workload.name}%-44s $v%-10s ns/op ${ns}%12.0f (min ${s.map(_.nsPerOp).min}%12.0f max ${s.map(_.nsPerOp).max}%12.0f)  ns/script ${ns / perScript}%10.0f  MB/s $mbs%8.1f  B/op $alloc%14.0f"
      )
      (ns, alloc)
    val (ni, ai) = line(Variant.Imperative)
    val (nf, af) = line(Variant.Functional)
    println(
      f"RATIO  ${workload.name}%-44s functional/imperative time ${nf / ni}%.3f allocation ${af / ai}%.3f"
    )

  def main(args: Array[String]): Unit =
    val first = args.headOption match
      case Some("functional") => Variant.Functional
      case _                  => Variant.Imperative
    val rounds = args.lift(1).flatMap(_.toIntOption).getOrElse(10)
    val real = realScripts
    val sizes = real.map(_.length)
    println(
      s"real scripts: ${real.size}, ${sizes.sum} bytes, min ${sizes.headOption.getOrElse(0)}, median ${sizes.lift(sizes.size / 2).getOrElse(0)}, max ${sizes.lastOption.getOrElse(0)}"
    )
    val large = composed(real, 64000).toVector
    val workloads = Vector(
      Workload(s"real scripts (${real.size}, ${sizes.sum} B)", real),
      Workload(s"largest real script (${sizes.lastOption.getOrElse(0)} B)", real.takeRight(1)),
      Workload(s"real scripts applied to each other (${large.map(_.length).sum} B)", large)
    ) ++ DeepFlatPrograms.all.map((id, flat) => Workload(s"$id (${flat.length} B)", Vector(flat)))
    val inputs = workloads.flatMap(_.inputs)
    val checked = onBigStack(inputs.map { flat =>
      val imperative = FlatScan.decode(flat)
      (imperative.isRight, imperative == FlatScanFunctional.decode(flat))
    })
    checked match
      case Left(error) =>
        println(s"comparison failed: $error")
        sys.exit(1)
      case Right(results) if results.exists(r => !r._1 || !r._2) =>
        println(
          s"inputs not decoded or not equal: ${results.count(!_._1)} refused, ${results.count(!_._2)} differ"
        )
        sys.exit(1)
      case Right(results) =>
        println(s"EQUAL ${results.size} inputs: both decoders give the same result")
    println(
      s"jvm ${System.getProperty("java.vm.version")}, processors ${Runtime.getRuntime.availableProcessors}, max heap ${Runtime.getRuntime.maxMemory >> 20} MiB, first $first, rounds $rounds"
    )
    workloads.foreach(measure(_, first, rounds))
    if sink == null then println("no result")
