// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.Id
import cats.effect.{IO, IOApp}
import cats.effect.syntax.all.*
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import java.nio.file.{Files, Path, StandardOpenOption}
import java.util.concurrent.atomic.AtomicReference
import munit.Assertions
import xyz.kd5ujc.scarab.ledger.Phase2
import xyz.kd5ujc.scarab.ledger.era.PlutusLoop
import xyz.kd5ujc.scarab.ledger.era.conway.ConwayScriptedBlocks
import xyz.kd5ujc.scarab.ledger.era.dijkstra.{DijkstraRecordedBlocks, DijkstraScriptedBlocks}
import xyz.kd5ujc.scarab.plutus.{EvalRequest, EvalResult, ExUnits, ScarabEvaluator, ScriptEvaluator}

/** Where preparing a block's scripts for the node's bounded workers (ADR 0018) starts to pay (#85):
  * each input's body program timed serially (`ParallelPlutus` at one worker, the node's serial
  * path) and prepared (`ParallelPlutus` at four, its preparation taken wherever the body may be
  * prepared, below the node's threshold too, [[AlwaysPrepared]]), the two alternated in every
  * round, the inputs interleaved round by round, after warm-up rounds that are not kept. The inputs
  * are the recorded Leios testnet blocks of two or more scripted batches (`DijkstraRecordedBlocks`)
  * and script-heavy blocks of Dijkstra and Conway made of [[PlutusLoop]]'s script
  * (`DijkstraScriptedBlocks`, `ConwayScriptedBlocks`): honest ones, each redeemer declaring what
  * its script spends, and inflated ones, cheap scripts declaring more. Each input's row has its
  * measures (scripted jobs, dependency batches, declared and spent units, script bytes), the
  * medians and spreads of both ways, and, once, the prepared way split into preparation, execution
  * and resolution. Every prepared result is checked against the serial one.
  *
  * Run by hand in the Docker JDK image: `java -cp <tools' runtime classpath>
  * xyz.kd5ujc.scarab.node.PlutusThresholdBench <output.jsonl> [rounds] [warm-up rounds]` from the
  * checkout's root. Not a gate.
  */
object PlutusThresholdBench extends IOApp.Simple with Assertions:
  enum Era:
    case Dijkstra, Conway

  /** A body program of one block, made afresh with an evaluator for each run. */
  trait Input:
    type A
    def name: String
    def era: Era
    def kind: String
    def make(evaluator: ScriptEvaluator[Id]): Phase2.Program[A]

  private def input[R](
      label: String,
      of: Era,
      what: String,
      program: ScriptEvaluator[Id] => Phase2.Program[R]
  ): Input =
    new Input:
      type A = R
      def name: String = label
      def era: Era = of
      def kind: String = what
      def make(evaluator: ScriptEvaluator[Id]): Phase2.Program[R] = program(evaluator)

  private val workers = 4

  /** What a body's scripts ask and spend, from one serial run that records every request. */
  private def measures(in: Input): Json =
    val seen = AtomicReference(Vector.empty[(EvalRequest, EvalResult)])
    val recording: ScriptEvaluator[Id] = request =>
      val result = ScarabEvaluator.default.evaluate(request)
      val _ = seen.updateAndGet(_ :+ (request -> result))
      result
    val _ = in.make(recording).serial
    val asked = seen.get
    val plan = in.make(ScarabEvaluator.default).prepare
    val used = asked.map(_._2 match
      case EvalResult.Success(u, _)    => u
      case EvalResult.Failure(_, u, _) => u)
    Json.obj(
      "scripts" -> asked.size.asJson,
      "batchWidths" -> plan.batches.map(_.size).asJson,
      "jobs" -> plan.batches.map(_.size).sum.asJson,
      "preparable" -> in.make(ScarabEvaluator.default).preparable.asJson,
      "worthwhile" -> in.make(ScarabEvaluator.default).worthwhile.asJson,
      "declaredSteps" -> asked.map(_._1.budget.steps).sum.asJson,
      "declaredMem" -> asked.map(_._1.budget.mem).sum.asJson,
      "spentSteps" -> used.map(_.steps).sum.asJson,
      "spentMem" -> used.map(_.mem).sum.asJson,
      "scriptBytes" -> asked.map(_._1.script.size).sum.asJson
    )

  private def timed[R](fa: IO[R]): IO[(R, Long)] =
    for
      began <- IO.monotonic
      r <- fa
      ended <- IO.monotonic
    yield (r, (ended - began).toNanos)

  /** The prepared way split: preparation, the jobs' execution in their batches, resolution. */
  private def phases(in: Input): IO[Vector[Long]] =
    IO(in.make(ScarabEvaluator.default)).flatMap { body =>
      for
        prepared <- timed(IO(body.prepare))
        (plan, prepareNs) = prepared
        executed <- timed(
          plan.batches.foldLeftM(Vector.empty[plan.Completed])((done, batch) =>
            batch.parTraverseN(workers)(job => IO.blocking(plan.execute(job))).map(done ++ _)
          )
        )
        (completed, executeNs) = executed
        resolved <- timed(IO(plan.resolve(completed)))
      yield Vector(prepareNs, executeNs, resolved._2)
    }

  private def summary(ns: Vector[Long]): Json =
    val s = ns.sorted
    def at(q: Double): Long = s(((s.size - 1) * q).round.toInt)
    Json.obj(
      "median" -> at(0.5).asJson,
      "p25" -> at(0.25).asJson,
      "p75" -> at(0.75).asJson,
      "min" -> s.head.asJson,
      "max" -> s.last.asJson
    )

  private def dijkstraRecorded: Vector[Input] =
    DijkstraRecordedBlocks.samples
      // the blocks the node would prepare whose preparation defers two or more jobs: those the
      // recorded state admits as far as their scripts
      .filter { s =>
        val body = DijkstraRecordedBlocks.program(s, ScarabEvaluator.default)
        body.preparable && body.prepare.batches.map(_.size).sum > 1
      }
      .map(s =>
        input(
          s"dijkstra-recorded-${s.block}",
          Era.Dijkstra,
          "recorded",
          DijkstraRecordedBlocks.program(s, _)
        )
      )

  /** The loop counts of the honest blocks, from a script that ends at once to a heavy one. */
  private val iterations = Vector(0, 30, 300, 3000)
  private val counts = Vector(2, 4, 8, 16, 32)

  /** Dijkstra's block units (the testnet's genesis: 72,000,000 memory, 20,000,000,000 steps). */
  private def withinDijkstra(n: Int, u: ExUnits): Boolean =
    u.mem * n <= BigInt(72000000) && u.steps * n <= BigInt(20000000000L)

  private def dijkstraSynthetic: Vector[Input] =
    val honest = for
      i <- iterations
      script = PlutusLoop.script(i)
      units = DijkstraScriptedBlocks.spent(script)
      n <- counts
      if withinDijkstra(n, units)
    yield input(
      s"dijkstra-loop$i-x$n",
      Era.Dijkstra,
      "honest",
      DijkstraRecordedBlocks.program(DijkstraScriptedBlocks.declaring(n, script, units), _)
    )
    val cheap = PlutusLoop.script(0)
    val inflated = for
      n <- Vector(2, 8, 16)
      declared = ExUnits(BigInt(1000000), BigInt(1000000000L))
    yield input(
      s"dijkstra-inflated-x$n",
      Era.Dijkstra,
      "inflated",
      DijkstraRecordedBlocks.program(DijkstraScriptedBlocks.declaring(n, cheap, declared), _)
    )
    honest ++ inflated

  private def conwaySynthetic: Vector[Input] =
    val honest = for
      i <- iterations
      script = PlutusLoop.script(i)
      units = ConwayScriptedBlocks.spent(script)
      n <- counts
      if withinDijkstra(n, units)
    yield
      val block = ConwayScriptedBlocks.declaring(n, script, units)
      input(s"conway-loop$i-x$n", Era.Conway, "honest", block.program)
    val cheap = PlutusLoop.script(0)
    val inflated = Vector(2, 8, 16).map { n =>
      val block =
        ConwayScriptedBlocks.declaring(n, cheap, ExUnits(BigInt(1000000), BigInt(1000000000L)))
      input(s"conway-inflated-x$n", Era.Conway, "inflated", block.program)
    }
    honest ++ inflated

  private def append(out: Path, row: Json): IO[Unit] =
    IO.blocking {
      val _ = Files.writeString(
        out,
        row.noSpaces + "\n",
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND
      )
    }

  def run: IO[Unit] =
    IO.defer {
      val args = sys.env.get("SCARAB_BENCH_ARGS").map(_.split(' ').toVector).getOrElse(Vector.empty)
      val out = Path.of(args.headOption.getOrElse("plutus-threshold.jsonl"))
      val rounds = args.lift(1).flatMap(_.toIntOption).getOrElse(15)
      val warmup = args.lift(2).flatMap(_.toIntOption).getOrElse(3)
      val only = args.lift(3)
      for
        serial <- ParallelPlutus.create[IO](ParallelPlutus.Workers.sequential)
        parallel <- ParallelPlutus
          .create[IO](ParallelPlutus.Workers.from(workers).fold(e => fail(s"$e"), identity))
        inputs <- IO(
          (dijkstraRecorded ++ dijkstraSynthetic ++ conwaySynthetic)
            .filter(in => only.forall(in.name.startsWith))
        )
        described <- inputs.traverse(in => IO(in -> measures(in)))
        _ <- described.traverse_ { (in, m) =>
          IO(
            assert(
              m.hcursor.get[Int]("jobs").exists(_ > 1),
              s"${in.name}: not prepared into more than one job: $m"
            )
          ) *> IO.println(s"INPUT ${in.name} ${m.noSpaces}")
        }
        // one round: each input serial and prepared, in an order alternated by the round
        oneRound = (round: Int) =>
          inputs.traverse[IO, (Long, Long)] { in =>
            def serialWay = IO(in.make(ScarabEvaluator.default)).flatMap(b => timed(serial(b)))
            def preparedWay =
              IO(AlwaysPrepared(in.make(ScarabEvaluator.default))).flatMap(b => timed(parallel(b)))
            (if round % 2 == 0 then (serialWay, preparedWay).tupled
             else preparedWay.product(serialWay).map(_.swap)).map { case ((s, sNs), (p, pNs)) =>
              assertEquals(p, s, s"${in.name}: the prepared result")
              (sNs, pNs): (Long, Long)
            }
          }
        _ <- (0 until warmup).toVector.traverse_(oneRound)
        measured <- (0 until rounds).toVector.traverse(oneRound)
        split <- inputs.traverse(in => (0 until 5).toVector.traverse(_ => phases(in)))
        _ <- described.zipWithIndex.traverse_ { case ((in, m), k) =>
          val s = measured.map(_(k)._1)
          val p = measured.map(_(k)._2)
          val ratios = measured.map(r => r(k)._2.toDouble / r(k)._1.toDouble)
          val parts = split(k)
          val row = Json.obj(
            "input" -> in.name.asJson,
            "era" -> in.era.toString.asJson,
            "kind" -> in.kind.asJson,
            "measures" -> m,
            "rounds" -> rounds.asJson,
            "serialNs" -> summary(s),
            "preparedNs" -> summary(p),
            "preparedOverSerial" -> Json.obj(
              "median" -> ratios.sorted.apply(ratios.size / 2).asJson,
              "min" -> ratios.min.asJson,
              "max" -> ratios.max.asJson
            ),
            "phasesMedianNs" -> Json.obj(
              "prepare" -> parts.map(_(0)).sorted.apply(2).asJson,
              "execute" -> parts.map(_(1)).sorted.apply(2).asJson,
              "resolve" -> parts.map(_(2)).sorted.apply(2).asJson
            ),
            "serialSamplesNs" -> s.asJson,
            "preparedSamplesNs" -> p.asJson
          )
          append(out, row) *> IO.println(
            f"ROW ${in.name}%-28s serial ${summary(s).hcursor.get[Long]("median").getOrElse(0L) / 1e6}%9.3f ms prepared ${summary(p).hcursor.get[Long]("median").getOrElse(0L) / 1e6}%9.3f ms ratio ${ratios.sorted.apply(ratios.size / 2)}%.3f"
          )
        }
      yield ()
    }
