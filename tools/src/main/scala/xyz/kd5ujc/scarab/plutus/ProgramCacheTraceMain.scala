// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import cats.effect.{ExitCode, IO, IOApp}
import cats.syntax.all.*
import java.lang.management.ManagementFactory
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.uplc.ProtocolMajor
import xyz.kd5ujc.scarab.uplc.ledger.LedgerEval

/** The program cache of the own evaluator on a recorded run of script evaluations: what it keeps,
  * finds, evicts and refuses, and the time spent in the evaluator for each block (#1499). Run by
  * hand in the JDK container:
  *
  * {{{
  * java -Xmx4g -XX:+UseG1GC -XX:+UnlockDiagnosticVMOptions -XX:-ReduceAllocationMerges \
  *   -cp <tools classpath> xyz.kd5ujc.scarab.plutus.ProgramCacheTraceMain \
  *   scripts <scripts.tsv>
  * java ... ProgramCacheTraceMain trace <scripts.tsv> <trace.tsv> <costmodels.tsv> <protocol> [rounds [max heap MiB]]
  * java ... ProgramCacheTraceMain anatomy <scripts.tsv> <hash>
  * }}}
  *
  * The inputs are tab-separated: `scripts.tsv` holds each script as its hash, its language's number
  * (1 for V1) and its bytes as the ledger holds them, in hex; `trace.tsv` each evaluation in the
  * chain's order as its block number, its transaction's index in the block and its script's hash;
  * `costmodels.tsv` a language's number and its cost model, comma-separated.
  *
  * `scripts` decodes every script (twice, the second round timed) and prints the distribution of
  * the script sizes, of their decoding times and of their [[ProgramWeight]], and the heap the
  * programs hold after full collections.
  *
  * `trace` gives every evaluation of the trace to one interpreter, in order, each with a budget too
  * small to run the program, so that what is timed is what the program cache decides and its
  * decodings, and prints per round the cache's hits, misses, programs kept and evicted, programs
  * not kept, the weight kept at the end, and the time in the evaluator per block. The first round
  * starts with an empty cache, and each later one with what the round before kept. The cache is
  * bounded as an interpreter not sized for a heap is ([[ScarabEvaluator.Programs]]), or, given a
  * maximum heap, as the node's is for it ([[ScarabEvaluator.programs]]).
  *
  * `anatomy` prints the terms of one script's program by kind and its constants by type, with their
  * weight.
  */
object ProgramCacheTraceMain extends IOApp:
  private val heap = ManagementFactory.getMemoryMXBean

  private val settled: IO[Long] =
    IO.blocking(heap.gc()).replicateA_(3) *> IO(heap.getHeapMemoryUsage.getUsed)

  private final case class Script(language: PlutusLanguage, bytes: Bytes)

  private final case class Evaluation(block: Long, script: Script)

  private def columns(path: Path): Vector[Vector[String]] =
    Files.readAllLines(path).asScala.toVector.filter(_.nonEmpty).map(_.split('\t').toVector)

  private def language(number: String): PlutusLanguage =
    PlutusLanguage.fromOrdinal(number.toInt - 1)

  private def scripts(path: Path): Map[String, Script] =
    columns(path).map { row =>
      row(0) -> Script(
        language(row(1)),
        Bytes.fromHex(row(2)).fold(e => sys.error(e.message), identity)
      )
    }.toMap

  private def costModels(path: Path): Map[PlutusLanguage, Vector[Long]] =
    columns(path).map(row => language(row(0)) -> row(1).split(',').toVector.map(_.toLong)).toMap

  def run(args: List[String]): IO[ExitCode] = args match
    case "scripts" :: file :: Nil => decoded(scripts(Path.of(file))).as(ExitCode.Success)
    case "anatomy" :: file :: hash :: Nil =>
      anatomy(scripts(Path.of(file))(hash)).as(ExitCode.Success)
    case "trace" :: file :: trace :: models :: protocol :: rest =>
      val known = scripts(Path.of(file))
      val evaluations = columns(Path.of(trace)).map(row => Evaluation(row(0).toLong, known(row(2))))
      val rounds = rest.headOption.fold(2)(_.toInt)
      val limits = rest
        .drop(1)
        .headOption
        .fold(ScarabEvaluator.Programs)(mib => ScarabEvaluator.programs(mib.toLong << 20))
      replayed(evaluations, costModels(Path.of(models)), protocol.toInt, rounds, limits)
        .as(ExitCode.Success)
    case _ =>
      IO.println(
        "usage: scripts <scripts.tsv> | trace <scripts.tsv> <trace.tsv> <costmodels.tsv> <protocol> [rounds [max heap MiB]] | anatomy <scripts.tsv> <hash>"
      ).as(ExitCode(2))

  private def percentiles(xs: Vector[Double]): String =
    val sorted = xs.sorted
    def at(q: Double) = sorted(((sorted.size - 1) * q).toInt)
    sorted.lastOption.fold("none")(max =>
      f"median ${at(0.5)}%.2f, p90 ${at(0.9)}%.2f, max $max%.2f, mean ${xs.sum / xs.size}%.2f"
    )

  private def decoded(known: Map[String, Script]): IO[Unit] =
    val all = known.values.toVector
    def decode(s: Script) =
      IO(LedgerEval.deserialiseScript(s.language, ProtocolMajor(8), s.bytes).map(_.withScope)).timed
    for
      _ <- all.traverse_(decode)
      before <- settled
      held <- all.traverse(decode)
      after <- settled
      admitted = all.zip(held).flatMap((s, h) => h._2.toOption.map(s -> _))
      programs = admitted.map(_._2)
      weights = admitted.map((s, p) => ProgramWeight.entry(s.bytes, p, Weight.unbounded).toLong)
      _ <- IO.println(
        s"${all.size} scripts, ${programs.size} admitted; " +
          s"script KiB: ${percentiles(all.map(_.bytes.size / 1024.0))}"
      )
      _ <- IO.println(s"decode ms: ${percentiles(held.map(_._1.toMicros / 1000.0))}")
      _ <- IO.println(s"weight KiB: ${percentiles(weights.map(_ / 1024.0))}")
      _ <- IO.println(
        f"heap of all programs: ${(after - before) / 1048576.0}%.1f MiB, weight of all " +
          f"${weights.sum / 1048576.0}%.1f MiB, heap/weight ${(after - before).toDouble / weights.sum}%.2f, " +
          f"heap per script byte ${(after - before).toDouble / all.map(_.bytes.size.toLong).sum}%.1f"
      )
      _ <- IO(held.size).void
    yield ()

  /** The terms of a script's program by kind, and its constants by type with their weight. */
  private def anatomy(script: Script): IO[Unit] = IO {
    val program = LedgerEval
      .deserialiseScript(script.language, ProtocolMajor(8), script.bytes)
      .fold(e => sys.error(e.toString), identity)
    @scala.annotation.tailrec
    def go(
        pending: List[xyz.kd5ujc.scarab.uplc.Term],
        kinds: Map[String, Int],
        constants: Map[String, (Int, Long)]
    ): (Map[String, Int], Map[String, (Int, Long)]) =
      pending match
        case Nil => (kinds, constants)
        case t :: rest =>
          val layer = t.unfix
          val kind = layer.getClass.getSimpleName
          val more = kinds.updatedWith(kind)(n => Some(n.getOrElse(0) + 1))
          layer match
            case xyz.kd5ujc.scarab.uplc.TermF.Constant(c) =>
              val name = c.typeOf.toString
              val w = ProgramWeight.constant(c).toLong
              go(
                rest,
                more,
                constants.updatedWith(name)(o => Some(o.fold((1, w))((n, x) => (n + 1, x + w))))
              )
            case other =>
              go(cats.Traverse[xyz.kd5ujc.scarab.uplc.TermF].toList(other) ++ rest, more, constants)
    val (kinds, constants) = go(program.term :: Nil, Map.empty, Map.empty)
    println(s"${script.bytes.size} bytes, weight ${ProgramWeight.of(program).toLong}")
    kinds.toVector.sortBy(-_._2).foreach((k, n) => println(s"  $k $n"))
    constants.toVector.sortBy(-_._2._1).foreach { case (k, (n, w)) =>
      println(s"  constant $k $n weighing $w")
    }
  }

  private final case class Tally(
      hits: Long,
      misses: Long,
      kept: Long,
      evicted: Long,
      refused: Long,
      nanos: Map[Long, Long]
  ):
    def line(interpreter: ScarabEvaluator.Interpreter): String =
      val (size, weight) = interpreter.held
      val perBlock = nanos.values.toVector.map(_ / 1e6)
      f"evaluations ${hits + misses}, hits $hits (${100.0 * hits / (hits + misses)}%.1f%%), " +
        f"misses $misses, kept $kept, evicted $evicted, not kept $refused; at the end $size programs " +
        f"weighing ${weight.toLong / 1048576.0}%.1f MiB; evaluator ms per block over ${perBlock.size} " +
        f"blocks: ${percentiles(perBlock)}, total ${perBlock.sum / 1000.0}%.1f s"

  private def replayed(
      evaluations: Vector[Evaluation],
      models: Map[PlutusLanguage, Vector[Long]],
      protocol: Int,
      rounds: Int,
      limits: Limits
  ): IO[Unit] =
    val interpreter = ScarabEvaluator.Interpreter(limits)
    val budget = ExUnits(BigInt(1), BigInt(1))
    def round: IO[Tally] = IO {
      evaluations.foldLeft(Tally(0, 0, 0, 0, 0, Map.empty)) { (t, e) =>
        val request =
          EvalRequest(
            e.script.language,
            protocol,
            e.script.bytes,
            Vector.empty,
            models(e.script.language),
            budget
          )
        val found = interpreter.holds(e.script.language, protocol, e.script.bytes)
        val (before, _) = interpreter.held
        val start = System.nanoTime()
        val _ = interpreter.evaluate(request)
        val spent = System.nanoTime() - start
        val (after, _) = interpreter.held
        val nanos = t.nanos.updatedWith(e.block)(n => Some(n.getOrElse(0L) + spent))
        if found then t.copy(hits = t.hits + 1, nanos = nanos)
        else if interpreter.holds(e.script.language, protocol, e.script.bytes) then
          t.copy(
            misses = t.misses + 1,
            kept = t.kept + 1,
            evicted = t.evicted + (before + 1 - after),
            nanos = nanos
          )
        else t.copy(misses = t.misses + 1, refused = t.refused + 1, nanos = nanos)
      }
    }
    (1 to rounds).toVector.traverse_ { n =>
      round.flatMap(t => IO.println(s"round $n: ${t.line(interpreter)}"))
    }
