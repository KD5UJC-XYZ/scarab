// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import cats.effect.{ExitCode, IO, IOApp}
import cats.syntax.all.*
import java.lang.management.ManagementFactory
import java.nio.file.Path
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.uplc.{Program, ProtocolMajor, Schemes, TermF}
import xyz.kd5ujc.scarab.uplc.cost.ExBudget
import xyz.kd5ujc.scarab.uplc.ledger.LedgerEval
import xyz.kd5ujc.scarab.uplc.oracle.GeneratedReplay
import xyz.kd5ujc.scarab.uplc.oracle.GeneratedReplay.{field, hex}

/** The heap a decoded program holds, and what the evaluator's cache of decoded programs keeps. Run
  * by hand in the JDK container, with the heap of the shipped launchers:
  *
  * {{{
  * java -Xmx768m -XX:+UseG1GC -cp <tools classpath> xyz.kd5ujc.scarab.plutus.ProgramCacheHeapMain programs [count]
  * java -Xmx768m -XX:+UseG1GC -cp <tools classpath> xyz.kd5ujc.scarab.plutus.ProgramCacheHeapMain cache [count]
  * }}}
  *
  * `programs` decodes `count` (default 40) distinct variants of each shape of [[AmplifyingScripts]]
  * at 16,000 bytes, and the distinct scripts of the recorded Leios testnet chain
  * (`fixtures/reference/leios-testnet-chain-scripts`), keeps the programs, and prints for each
  * shape the heap in use after full collections, less the heap in use before, divided by the
  * programs held, with the script bytes, and its [[ProgramWeight]] against that heap; and the
  * median times to decode and to weigh a program, each measured on a second round over the scripts.
  *
  * `cache` gives `count` (default 512) distinct variants of the pair-tree shape to one
  * interpreter's [[ScriptDecoder.validate]], as phase-1 well-formedness does, and then to another
  * interpreter's [[ScriptEvaluator.evaluate]], and prints how many were admitted or had a verdict,
  * what the cache kept and its weight, and the heap in use after full collections with the
  * interpreter reachable.
  *
  * `fold` prints the median time to count a program's terms with `Schemes.cata` and to weigh it
  * with [[ProgramWeight]], for each shape.
  *
  * `leios` prints the median and total time to decode the scripts of the recorded Leios testnet
  * chain's requests, and to decode and evaluate them.
  */
object ProgramCacheHeapMain extends IOApp:
  private val heap = ManagementFactory.getMemoryMXBean

  /** The heap in use once three full collections have run. */
  private val settled: IO[Long] =
    IO.blocking(heap.gc()).replicateA_(3) *> IO(heap.getHeapMemoryUsage.getUsed)

  private val Size = 16_000

  private final case class Script(language: PlutusLanguage, protocol: Int, bytes: Bytes)

  private def shapes(count: Int): Vector[(String, Vector[Script])] =
    def variants(
        make: (Int, Int) => Bytes,
        language: PlutusLanguage = PlutusLanguage.V1,
        protocol: Int = 11
    ) =
      Vector.tabulate(count)(v => Script(language, protocol, make(Size, v)))
    Vector(
      "pair tree" -> variants(AmplifyingScripts.pairTree),
      "lam chain" -> variants(AmplifyingScripts.lams),
      "empty lists" -> variants(AmplifyingScripts.emptyLists),
      "deep type" -> variants(AmplifyingScripts.deepType, protocol = 10),
      "constr chain" -> variants(AmplifyingScripts.constrs, PlutusLanguage.V3),
      "data chain" -> variants(AmplifyingScripts.dataChain),
      "data list" -> variants(AmplifyingScripts.dataList),
      "data pairs" -> variants(AmplifyingScripts.dataPairs),
      "empty values" -> variants(AmplifyingScripts.emptyValues, PlutusLanguage.V3, 12),
      "Leios testnet chain" -> recorded
    )

  private def recorded: Vector[Script] =
    GeneratedReplay
      .rows(Path.of("fixtures/reference/leios-testnet-chain-scripts/requests.jsonl"))
      .map(r =>
        Script(
          PlutusLanguage.fromOrdinal(field[Int](r, "language") - 1),
          field[Int](r, "protocol"),
          hex(field[String](r, "script"))
        )
      )
      .distinctBy(_.bytes)

  private def decoded(script: Script): IO[(Program, FiniteDuration)] =
    IO(
      LedgerEval.deserialiseScript(script.language, ProtocolMajor(script.protocol), script.bytes)
    ).timed
      .flatMap { case (time, result) =>
        result.fold(
          e => IO.raiseError(new IllegalStateException(e.toString)),
          p => IO.pure(p -> time)
        )
      }

  private def programs(count: Int): IO[Unit] =
    shapes(count).traverse_ { case (name, scripts) =>
      for
        _ <- scripts.traverse_(decoded)
        before <- settled
        held <- scripts.traverse(decoded)
        after <- settled
        bytes = scripts.map(_.bytes.size.toLong).sum
        median = held.map(_._2).sorted.apply(held.size / 2)
        perProgram = (after - before) / held.size
        _ <- held.traverse_(p => IO(ProgramWeight.of(p._1)))
        weighed <- held.traverse(p => IO(ProgramWeight.of(p._1)).timed)
        weight = weighed.map(_._2.toLong).sum / held.size
        weighing = weighed.map(_._1).sorted.apply(held.size / 2)
        _ <- IO.println(
          f"$name%-20s ${held.size}%4d programs, ${bytes / held.size}%6d script bytes each, " +
            f"${perProgram / 1024}%8d KiB heap each (${perProgram.toDouble / (bytes / held.size)}%6.1f per byte), " +
            f"median decode ${median.toMicros / 1000.0}%8.1f ms; weight ${weight / 1024}%8d KiB each, " +
            f"heap/weight ${perProgram.toDouble / weight}%5.2f, median weighing ${weighing.toMicros / 1000.0}%6.2f ms"
        )
        _ <- IO(held.size).void
      yield ()
    }

  private def cache(count: Int): IO[Unit] =
    val decoder = ScarabEvaluator.pureDecoder
    for
      before <- settled
      results <- (0 until count).toVector.traverse { v =>
        IO(decoder.validate(PlutusLanguage.V1, 11, AmplifyingScripts.pairTree(Size, v)))
      }
      after <- settled
      admitted = results.count(_.isRight)
      _ <- IO.println(
        s"cache: $count pair-tree scripts given, $admitted admitted, " +
          s"${results.size - admitted} without a verdict or refused; " +
          s"${(after - before) / (1024 * 1024)} MiB held after full collections " +
          s"(max heap ${Runtime.getRuntime.maxMemory / (1024 * 1024)} MiB)"
      )
      _ <- evaluated(count)
      _ <- IO(decoder.hashCode).void
    yield ()

  /** The same scripts evaluated by one interpreter, which keeps their programs. */
  private def evaluated(count: Int): IO[Unit] =
    val interpreter = ScarabEvaluator.Interpreter()
    def request(v: Int) = EvalRequest(
      PlutusLanguage.V1,
      11,
      AmplifyingScripts.pairTree(Size, v),
      Vector.fill(3)(xyz.kd5ujc.scarab.uplc.PlutusData.unit),
      PlutusBoundaryCorpus.costModel(PlutusLanguage.V1, 11),
      PlutusBoundaryCorpus.budget
    )
    for
      before <- settled
      results <- (0 until count).toVector.traverse(v => IO(interpreter.evaluate(request(v))))
      after <- settled
      (kept, weight) = interpreter.held
      unsupported = results.count {
        case EvalResult.Failure(EvalFailure.Unsupported(_), _, _) => true
        case _                                                    => false
      }
      _ <- IO.println(
        s"evaluated: $count pair-tree scripts, $unsupported without a verdict; $kept programs kept, " +
          s"weighing ${weight.toLong / (1024 * 1024)} MiB; " +
          s"${(after - before) / (1024 * 1024)} MiB held after full collections"
      )
    yield ()

  /** For each script request of the recorded Leios testnet chain, the time to decode its script and
    * the time to decode and evaluate it, twice over the requests, the first round to warm up.
    */
  private def leios: IO[Unit] =
    val directory = Path.of("fixtures/reference/leios-testnet-chain-scripts")
    val models = GeneratedReplay.rows(directory.resolve("models.jsonl"))
    val requests = GeneratedReplay.rows(directory.resolve("requests.jsonl"))
    val contexts = GeneratedReplay.contexts(models)
    def round: IO[Vector[(FiniteDuration, FiniteDuration)]] = requests.traverse { r =>
      val script = Script(
        PlutusLanguage.fromOrdinal(field[Int](r, "language") - 1),
        field[Int](r, "protocol"),
        hex(field[String](r, "script"))
      )
      val budget = ExBudget.of(field[Long](r, "cpu"), field[Long](r, "memory"))
      (
        decoded(script).map(_._2),
        IO(GeneratedReplay.evaluate(contexts, r, budget)).timed.map(_._1)
      ).tupled
    }
    def median(xs: Vector[FiniteDuration]) = xs.sorted.apply(xs.size / 2).toMicros / 1000.0
    round *> round.flatMap { times =>
      val decode = times.map(_._1)
      val total = times.map(_._2)
      IO.println(
        f"Leios testnet chain: ${times.size} requests; decode median ${median(decode)}%.2f ms, " +
          f"total ${decode.map(_.toMicros).sum / 1000.0}%.1f ms; decode and evaluate median " +
          f"${median(total)}%.2f ms, total ${total.map(_.toMicros).sum / 1000.0}%.1f ms"
      )
    }

  /** The terms of a program counted by `Schemes.cata`, against the program weighed by
    * [[ProgramWeight]], which walks its terms by hand: the median of five rounds over each shape's
    * programs, after a round to warm up.
    */
  private def fold(count: Int): IO[Unit] =
    def nodes(layer: TermF[Long]): Long = layer match
      case TermF.LamAbs(b)     => 1 + b
      case TermF.Apply(f, a)   => 1 + f + a
      case TermF.Force(b)      => 1 + b
      case TermF.Delay(b)      => 1 + b
      case TermF.Constr(_, fs) => 1 + fs.sum
      case TermF.Case(s, bs)   => 1 + s + bs.sum
      case TermF.Var(_)        => 1
      case TermF.Builtin(_)    => 1
      case TermF.Error         => 1
      case TermF.Constant(_)   => 1
    def median(programs: Vector[Program], run: Program => Long): IO[Double] =
      programs.traverse_(p => IO(run(p))) *>
        Vector.fill(5)(programs).flatten.traverse(p => IO(run(p)).timed.map(_._1)).map { ts =>
          ts.sorted.apply(ts.size / 2).toNanos / 1e6
        }
    shapes(count).traverse_ { case (name, scripts) =>
      for
        held <- scripts.traverse(decoded).map(_.map(_._1))
        folded <- median(held, p => Schemes.cata(p.term)(nodes))
        walked <- median(held, p => ProgramWeight.of(p).toLong)
        _ <- IO.println(
          f"$name%-20s cata (terms only) $folded%8.3f ms; ProgramWeight.of $walked%8.3f ms"
        )
      yield ()
    }

  def run(args: List[String]): IO[ExitCode] = args match
    case "fold" :: rest     => fold(rest.headOption.fold(10)(_.toInt)).as(ExitCode.Success)
    case "leios" :: _       => leios.as(ExitCode.Success)
    case "programs" :: rest => programs(rest.headOption.fold(40)(_.toInt)).as(ExitCode.Success)
    case "cache" :: rest    => cache(rest.headOption.fold(512)(_.toInt)).as(ExitCode.Success)
    case _ => IO.println("usage: ProgramCacheHeapMain programs|cache [count]").as(ExitCode(2))
