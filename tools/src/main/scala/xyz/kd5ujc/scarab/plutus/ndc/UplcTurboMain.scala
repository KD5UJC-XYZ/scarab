// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus.ndc

import cats.effect.{ExitCode, IO, IOApp}
import cats.syntax.all.*
import io.circe.Json
import java.lang.management.ManagementFactory
import java.nio.file.{Files, Path}
import scala.annotation.tailrec
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.plutus.{PlutusLanguage, PlutusVm}
import xyz.kd5ujc.scarab.uplc.{ProtocolMajor, SemanticsVariant}
import xyz.kd5ujc.scarab.uplc.cost.{DefaultCostModels, ExBudget}

/** The `uplc-turbo` scenario of pragma-org's node-diversity-conformance site: the time, the memory
  * and the budget of every program of its corpus on one of the node's Plutus evaluators, written as
  * the scenario's report (`scenarios/uplc-turbo/README.md`, `CHANGELOG.md` and `schema.json` at the
  * site's commit 9dc68a75).
  *
  * `tools/runMain xyz.kd5ujc.scarab.plutus.ndc.UplcTurboMain <scalus|scarab> <corpus-dir>
  * <report.json> [--memory allocated|null] [--warm-up <passes>] [--memory-limit <units>]`
  *
  * Each `<epoch>/<name>-v<n>.flat` of the corpus is a script as the ledger holds it (a CBOR byte
  * string around the Flat program), of language `n`, at the protocol the README gives the epoch
  * ([[protocolOf]]), in the default evaluation context of that language and protocol: the default
  * cost model of the semantics variant of the pair (plutus 1.70.0.0's `builtinCostModel` and
  * `cekMachineCosts` files, through [[DefaultCostModels]]) as the list the ledger would pass. The
  * script is admitted and checked before anything is timed. Every program is then run once,
  * untimed, so that the JVM's compilers have seen the corpus; then fifty times each, timed, within
  * 10,000,000,000 CPU and 10,000,000 memory units, restricting, as the README sets
  * (`--memory-limit` changes the second, for an experiment only). Of the fifty the thirty fastest
  * are kept: `cpu_us` is their mean time and `mem_bytes` the mean of the bytes the evaluating
  * thread allocated in them (`--memory allocated`, the default) or null (`--memory null`). The
  * budget is what the runs spent, which must be the same in each. A program that does not evaluate
  * is left out of the report, which the site counts as a failure, and named on the console.
  */
object UplcTurboMain extends IOApp:

  /** The budget of the README. */
  val Limit: ExBudget = ExBudget.of(10_000_000_000L, 10_000_000L)

  val Runs: Int = 50
  val Kept: Int = 30

  /** The protocol of an epoch, the README's table: 11 from 644, 10 from 537, 9 from 507, else 8. */
  def protocolOf(epoch: Int): Int =
    if epoch >= 644 then 11 else if epoch >= 537 then 10 else if epoch >= 507 then 9 else 8

  private val LanguageSuffix = raw".*-v([123])\.flat".r

  /** The language a corpus file names, `…-v<n>.flat`. */
  def languageOf(name: String): Option[PlutusLanguage] = name match
    case LanguageSuffix(n) => n.toIntOption.flatMap(i => PlutusLanguage.values.find(_.id == i - 1))
    case _                 => None

  /** What `mem_bytes` reports. */
  enum Memory:
    /** The bytes the evaluating thread allocated during the timed evaluation. */
    case Allocated

    /** Nothing: the schema's null. */
    case Unreported

  /** The options after the three paths. */
  final case class Options(memory: Memory, passes: Int, limit: ExBudget)

  object Options:
    val Default: Options = Options(Memory.Allocated, 1, Limit)

  /** `--memory allocated|null`, `--warm-up <passes>`, `--memory-limit <units>`, in any order; none
    * for anything else.
    */
  @tailrec
  def options(rest: List[String], so: Options = Options.Default): Option[Options] = rest match
    case Nil                               => Some(so)
    case "--memory" :: "allocated" :: tail => options(tail, so.copy(memory = Memory.Allocated))
    case "--memory" :: "null" :: tail      => options(tail, so.copy(memory = Memory.Unreported))
    case "--warm-up" :: n :: tail =>
      n.toIntOption.filter(_ >= 0) match
        case Some(passes) => options(tail, so.copy(passes = passes))
        case None         => None
    case "--memory-limit" :: n :: tail =>
      n.toLongOption.filter(_ > 0) match
        case Some(units) => options(tail, so.copy(limit = ExBudget.of(Limit.cpu.toLong, units)))
        case None        => None
    case _ => None

  /** A program of the corpus. */
  final case class Bench(name: String, language: PlutusLanguage, protocol: Int, script: Bytes)

  /** Why the corpus cannot be read. */
  enum CorpusError:
    case Unreadable(path: Path, cause: Throwable)
    case UnnamedLanguage(path: Path)
    case CostModel(language: PlutusLanguage, protocol: Int, cause: Throwable)

    def message: String = this match
      case Unreadable(path, cause) => s"cannot read $path: $cause"
      case UnnamedLanguage(path)   => s"$path does not name its language"
      case CostModel(l, p, cause)  => s"no default cost model of $l at $p: $cause"

  /** Every program of the corpus, in the order of their names. */
  def corpus(root: Path): Either[CorpusError, Vector[Bench]] =
    def list(dir: Path) =
      Either
        .catchNonFatal(Files.list(dir).iterator.asScala.toVector)
        .leftMap(CorpusError.Unreadable(dir, _))
    for
      epochs <- list(root).map(_.flatMap(d => d.getFileName.toString.toIntOption.map(d -> _)))
      benches <- epochs.flatTraverse { (dir, epoch) =>
        list(dir).flatMap(
          _.filter(_.getFileName.toString.endsWith(".flat")).traverse { file =>
            val name = file.getFileName.toString
            for
              language <- languageOf(name).toRight(CorpusError.UnnamedLanguage(file))
              bytes <- Either
                .catchNonFatal(Bytes.fromArray(Files.readAllBytes(file)))
                .leftMap(CorpusError.Unreadable(file, _))
            yield Bench(name, language, protocolOf(epoch), bytes)
          }
        )
      }
    yield benches.sortBy(_.name)

  /** The default cost-model list of a language at a protocol: that of the variant of the pair. */
  def defaultCostModel(language: PlutusLanguage, protocol: Int): Either[CorpusError, Vector[Long]] =
    Either
      .catchNonFatal(
        DefaultCostModels.list(language, SemanticsVariant.of(language, ProtocolMajor(protocol)))
      )
      .leftMap(CorpusError.CostModel(language, protocol, _))

  /** One timed evaluation: its time, the bytes the thread allocated in it, and its run. */
  final case class Sample(nanos: Long, allocated: Long, run: Run)

  /** What the benchmark of one program gives. */
  final case class Measured(spent: ExBudget, cpuMicros: Double, allocated: Double)

  /** Why a program has no measurement. */
  enum Unmeasured:
    case NotPrepared(run: Run)
    case NotEvaluated(run: Run)
    case BudgetsDiffer(budgets: Vector[ExBudget])

    def message: String =
      def show(run: Run) = run match
        case Run.Failed(verdict, detail) => s"${verdict.text}: $detail"
        case Run.NoVerdict(cause)        => s"no verdict: ${cause.message}"
        case Run.Evaluated(spent, _)     => s"evaluated ($spent)"
      this match
        case NotPrepared(run)       => s"not prepared: ${show(run)}"
        case NotEvaluated(run)      => show(run)
        case BudgetsDiffer(budgets) => s"the runs spent different budgets: ${budgets.distinct}"

  private val threads: Option[com.sun.management.ThreadMXBean] =
    ManagementFactory.getThreadMXBean match
      case bean: com.sun.management.ThreadMXBean if bean.isThreadAllocatedMemorySupported =>
        Some(bean)
      case _ => None

  private def allocatedSoFar(): Long = threads.fold(0L)(_.getCurrentThreadAllocatedBytes)

  /** The timed region of the benchmark, a measurement tool's (AGENTS.md: `TimingProbeMain`): the
    * clock and the allocation counter are read around the evaluation alone, with no effect wrapper
    * between them that would add its own allocation and time to what is measured.
    */
  def sample(prepared: Prepared, limit: ExBudget): Sample =
    val before = allocatedSoFar()
    val start = System.nanoTime()
    val run = prepared.evaluate(limit)
    val end = System.nanoTime()
    val after = allocatedSoFar()
    Sample(end - start, after - before, run)

  /** The bytes the allocation counter itself reports for an empty measurement, the least of many.
    */
  def counterOverhead(): Long =
    Vector
      .fill(1000) {
        val before = allocatedSoFar()
        val after = allocatedSoFar()
        after - before
      }
      .min

  /** The README's measurement of one program: fifty runs, the thirty fastest kept. */
  def measure(prepared: Prepared, overhead: Long, limit: ExBudget): Either[Unmeasured, Measured] =
    val samples = Vector.fill(Runs)(sample(prepared, limit))
    val budgets = samples.map(_.run).collect { case Run.Evaluated(spent, _) => spent }
    samples.map(_.run).find {
      case _: Run.Evaluated => false
      case _                => true
    } match
      case Some(run) => Left(Unmeasured.NotEvaluated(run))
      case None =>
        budgets.distinct match
          case Vector(spent) =>
            val kept = samples.sortBy(_.nanos).take(Kept)
            Right(
              Measured(
                spent,
                kept.map(_.nanos).sum.toDouble / kept.size / 1000.0,
                kept.map(s => (s.allocated - overhead).max(0L)).sum.toDouble / kept.size
              )
            )
          case different => Left(Unmeasured.BudgetsDiffer(different))

  /** The report's item of a measured program. */
  def item(bench: Bench, measured: Measured, memory: Memory): Json =
    def rounded(x: Double) = BigDecimal(x).setScale(3, BigDecimal.RoundingMode.HALF_EVEN)
    Json.obj(
      "bench" -> Json.fromString(bench.name),
      "plutus_version" -> Json.fromString(NdcReport.languageName(bench.language)),
      "protocol_version" -> Json.fromString(NdcReport.protocolName(bench.protocol)),
      "budget_cpu" -> Json.fromLong(measured.spent.cpu.toLong),
      "budget_mem" -> Json.fromLong(measured.spent.memory.toLong),
      "cpu_us" -> Json.fromBigDecimal(rounded(measured.cpuMicros)),
      "mem_bytes" -> (memory match
        case Memory.Allocated  => Json.fromBigDecimal(rounded(measured.allocated))
        case Memory.Unreported => Json.Null)
    )

  /** Every program prepared on `backend`, the context made once for each language and protocol. */
  def prepareAll(
      backend: UplcBackend,
      benches: Vector[Bench]
  ): Either[CorpusError, Vector[(Bench, Either[Run, Prepared])]] =
    benches
      .groupBy(b => (b.language, b.protocol))
      .toVector
      .flatTraverse { case ((language, protocol), group) =>
        defaultCostModel(language, protocol).map { model =>
          val context = backend.context(language, protocol, model)
          group.map(b =>
            b -> context.fold(cause => Left(Run.NoVerdict(cause)), backend.script(_, b.script))
          )
        }
      }
      .map(_.sortBy(_._1.name))

  def run(args: List[String]): IO[ExitCode] =
    args match
      case vmName :: corpusDir :: reportFile :: rest =>
        (PlutusVm.parse(vmName), options(rest)) match
          case (Left(unknown), _)    => IO.println(unknown.message).as(ExitCode(2))
          case (Right(vm), Some(so)) => bench(vm, Path.of(corpusDir), Path.of(reportFile), so)
          case (Right(_), None)      => usage
      case _ => usage

  private val usage: IO[ExitCode] =
    IO.println(
      "usage: UplcTurboMain <scalus|scarab> <corpus-dir> <report.json> " +
        "[--memory allocated|null] [--warm-up <passes>] [--memory-limit <units>]"
    ).as(ExitCode(2))

  private def bench(vm: PlutusVm, root: Path, out: Path, so: Options): IO[ExitCode] =
    val backend = UplcBackend.of(vm)
    NdcReport.bigStack.use { stack =>
      for
        read <- IO.blocking(corpus(root)).timed
        prepared <- IO(read._2.flatMap(prepareAll(backend, _))).evalOn(stack).timed
        code <- prepared._2 match
          case Left(error) => IO.println(error.message).as(ExitCode(1))
          case Right(programs) =>
            val ready = programs.collect { case (b, Right(p)) => b -> p }
            val unprepared = programs.collect { case (b, Left(run)) =>
              b -> Unmeasured.NotPrepared(run)
            }
            for
              warm <- IO(Vector.fill(so.passes)(ready.foreach((_, p) => p.evaluate(so.limit))))
                .evalOn(stack)
                .timed
              measured <- IO {
                val overhead = counterOverhead()
                ready.map((b, p) => b -> measure(p, overhead, so.limit))
              }.evalOn(stack).timed
              results = measured._2
              failed = unprepared ++ results.collect { case (b, Left(why)) => b -> why }
              items = results.collect { case (b, Right(m)) => (b, m) }
              generated <- NdcReport.now
              machine <- NdcReport.machine
              report = Json.fromFields(
                NdcReport.header(
                  "uplc-turbo",
                  generated,
                  Json.obj(
                    "name" -> Json.fromString(NdcReport.implementation(vm)),
                    "language" -> Json.fromString("Scala")
                  )
                ) ++ Vector(
                  "machine" -> machine,
                  "benchmarks" -> Json.fromValues(items.map((b, m) => item(b, m, so.memory)))
                )
              )
              written <- NdcReport.write(out, report).timed
              _ <- failed.traverse_((b, why) => IO.println(s"${b.name}: ${why.message}"))
              _ <- IO.println(
                s"${vm.name}: ${items.size} of ${programs.size} programs measured, ${failed.size} not; " +
                  s"sum of cpu_us ${items.map(_._2.cpuMicros).sum.round} us; read ${read._1.toMillis} ms, " +
                  s"prepare ${prepared._1.toMillis} ms, warm-up ${warm._1.toMillis} ms (${so.passes} passes, memory limit ${so.limit.memory.toLong}), " +
                  s"measure ${measured._1.toMillis} ms, write ${written._1.toMillis} ms"
              )
            yield ExitCode.Success
      yield code
    }
