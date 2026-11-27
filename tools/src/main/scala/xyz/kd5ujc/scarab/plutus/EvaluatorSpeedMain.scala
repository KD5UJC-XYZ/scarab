// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import io.circe.Json
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.RecordedRequests
import xyz.kd5ujc.scarab.plutus.ndc.UplcTurboMain
import xyz.kd5ujc.scarab.uplc.{Program, ProgramVersion, ProtocolMajor, Term}
import xyz.kd5ujc.scarab.uplc.cost.ExBudget
import xyz.kd5ujc.scarab.uplc.flat.FlatEncoder
import xyz.kd5ujc.scarab.uplc.ledger.{
  EvaluationError,
  LedgerContext,
  LedgerEval,
  ScriptResult,
  VerboseMode
}

/** Development tool (#1499): the speed of the node's script evaluation on recorded scripts, and a
  * digest of every result, so that two builds can be compared on speed and on what they answer.
  *
  * `tools/runMain xyz.kd5ujc.scarab.plutus.EvaluatorSpeedMain <corpus> [rounds] [warm-up rounds]
  * [results file]` from the checkout's root, where `<corpus>` is `recorded` (the script requests of
  * the recorded Preview and preprod transactions, [[RecordedRequests]]) or the directory of a
  * `uplc-turbo` corpus (mainnet scripts, each applied to its arguments, in the default context of
  * its language and protocol, [[UplcTurboMain.corpus]], within the scenario's limits).
  *
  * Each request's script is decoded and its evaluation context made before anything is timed, as
  * the node's interpreter holds them once they are in its caches ([[ScarabEvaluator]]); what is
  * timed is the evaluation the interpreter runs on a cache hit,
  * [[LedgerEval.evaluateScriptRestricting]] with the request's budget. A round evaluates every
  * request once, in order; the warm-up rounds (5 when not given) are not kept, and of the timed
  * rounds (15 when not given) the median, the fastest and the slowest are printed, with the mean
  * time per script and the scripts per second of the median round. Every round must give the
  * results of the first; their digest (SHA-256 over each request's result, in order) is printed,
  * and each request's result is written to the results file when one is given. Not a gate.
  */
object EvaluatorSpeedMain:
  private final case class Corpus(name: String, requests: Vector[(String, EvalRequest)])

  private def recorded: Corpus =
    Corpus(
      "recorded",
      RecordedRequests.sources.flatMap(_.requests).map(r => r.id -> r.request)
    )

  /** The files of a directory, by name, the listing closed once read. */
  private def listed(dir: Path): Vector[Path] =
    val stream = Files.list(dir)
    val all = stream.iterator.asScala.toVector
    stream.close()
    all.sortBy(_.getFileName.toString)

  /** The programs of a `uplc-turbo` corpus, `<epoch>/<name>-v<n>.flat`, as [[UplcTurboMain]] reads
    * them.
    */
  private def benches(root: Path): Vector[UplcTurboMain.Bench] =
    listed(root)
      .filter(Files.isDirectory(_))
      .flatMap(dir => dir.getFileName.toString.toIntOption.map(dir -> _))
      .flatMap((dir, epoch) =>
        listed(dir).filter(_.getFileName.toString.endsWith(".flat")).map { file =>
          val name = file.getFileName.toString
          val language =
            UplcTurboMain.languageOf(name).getOrElse(sys.error(s"$file names no language"))
          val bytes = Bytes.fromArray(Files.readAllBytes(file))
          UplcTurboMain.Bench(name, language, UplcTurboMain.protocolOf(epoch), bytes)
        }
      )
      .sortBy(_.name)

  private def turbo(root: Path): Corpus =
    Corpus(
      root.toString,
      benches(root).map { b =>
        val costModel =
          UplcTurboMain
            .defaultCostModel(b.language, b.protocol)
            .fold(e => sys.error(e.message), identity)
        val limit = UplcTurboMain.Options.Default.limitFor(b.protocol)
        b.name -> EvalRequest(
          b.language,
          b.protocol,
          b.script,
          Vector.empty,
          costModel,
          ExUnits(limit.memory.toBigInt, limit.cpu.toBigInt)
        )
      }
    )

  /** A request ready to evaluate: its program and its context made. */
  private final case class Ready(
      request: EvalRequest,
      context: LedgerContext,
      program: Program,
      budget: ExBudget
  ):
    def evaluate(): ScriptResult =
      LedgerEval.evaluateScriptRestricting(
        request.language,
        ProtocolMajor(request.protocolMajor),
        VerboseMode.Verbose,
        context,
        budget,
        program,
        request.args
      )

  private def ready(id: String, request: EvalRequest): Ready =
    def fail(what: Any) = sys.error(s"$id: $what")
    val protocol = ProtocolMajor(request.protocolMajor)
    val program = LedgerEval
      .deserialiseScript(request.language, protocol, request.script)
      .fold(fail, identity)
    val context = LedgerContext
      .make(request.language.evaluatedAs, request.costModel)
      ._2
      .fold(fail, identity)
    val budget = ExBudget.of(request.budget.steps.toLong, request.budget.mem.toLong)
    Ready(request, context, program, budget)

  /** A result as one line: its verdict, the machine's error, the failure's cause as the hex of its
    * Flat encoding (a `Cause` is compared by identity and shows nothing of its term, and a term's
    * rendering shortens long constants), the budget and the log lines, all of it quoted as one JSON
    * string, so that two results are alike exactly when their lines are and a line never breaks.
    */
  private def line(result: ScriptResult): String =
    def flat(term: Term) =
      FlatEncoder
        .program(Program(ProgramVersion.V1_1_0, term))
        .fold(e => s"not encodable ($e): $term", _.hex)
    def quoted(logs: Vector[String]) = logs.map(Json.fromString(_).noSpaces).mkString("[", ",", "]")
    val raw = result match
      case ScriptResult.Success(spent, logs) => s"success $spent ${quoted(logs)}"
      case ScriptResult.Failure(EvaluationError.Cek(failure), spent, logs) =>
        s"failure ${failure.error} cause ${failure.cause.map(c => flat(c.term))} $spent ${quoted(logs)}"
      case ScriptResult.Failure(error, spent, logs) => s"failure $error $spent ${quoted(logs)}"
      case ScriptResult.Unavailable(reason)         => s"unavailable $reason"
    Json.fromString(raw).noSpaces

  private def digest(lines: Vector[String]): String =
    val md = MessageDigest.getInstance("SHA-256")
    lines.foreach(l => md.update((l + "\n").getBytes(StandardCharsets.UTF_8)))
    md.digest.map(b => f"${b & 0xff}%02x").mkString

  /** One round: every request once, in order, timed as a whole (FUN-2 (e): the clock is read around
    * the evaluations alone).
    */
  private def round(requests: Vector[Ready]): (Long, Vector[ScriptResult]) =
    val start = System.nanoTime()
    val results = requests.map(_.evaluate())
    (System.nanoTime() - start, results)

  private def load(argument: String): Corpus =
    val corpus = if argument == "recorded" then recorded else turbo(Path.of(argument))
    if corpus.requests.isEmpty then sys.error(s"$argument holds no script to evaluate")
    corpus

  /** A corpus made ready, for [[EvaluatorCompareMain]], which loads two builds of the evaluator
    * into one JVM (each in a class loader of its own) and alternates their rounds: the digest of
    * its results, and a round, which must give the same results.
    */
  final class Session(argument: String):
    private val requests = load(argument).requests.map(ready)
    private val first = round(requests)._2.map(line)
    val digest: String = EvaluatorSpeedMain.digest(first)

    /** The nanoseconds of one round. */
    def timed(): Long =
      val (ns, results) = round(requests)
      if results.map(line) != first then sys.error("a round gave other results than the first")
      ns

  def main(args: Array[String]): Unit =
    val corpus = load(args.headOption.getOrElse("recorded"))
    val rounds = args.lift(1).flatMap(_.toIntOption).getOrElse(15)
    val warmUp = args.lift(2).flatMap(_.toIntOption).getOrElse(5)
    val out = args.lift(3).map(Path.of(_))
    val requests = corpus.requests.map(ready)
    val (_, first) = round(requests)
    val lines = first.map(line)
    (1 to warmUp).foreach(_ => round(requests))
    val times = (1 to rounds).map { i =>
      val (ns, results) = round(requests)
      if results.map(line) != lines then sys.error(s"round $i gave other results than the first")
      ns
    }.sorted
    val median = times(times.size / 2)
    val succeeded = first.count {
      case ScriptResult.Success(_, _) => true
      case _                          => false
    }
    val steps = first.map {
      case ScriptResult.Success(spent, _)    => spent.cpu.toBigInt
      case ScriptResult.Failure(_, spent, _) => spent.cpu.toBigInt
      case ScriptResult.Unavailable(_)       => BigInt(0)
    }.sum
    println(
      s"corpus ${corpus.name}: ${requests.size} requests, $succeeded succeeded, steps spent $steps"
    )
    println(s"results digest ${digest(lines)}")
    println(
      f"rounds $rounds (after $warmUp warm-up): median ${median / 1e6}%.2f ms, fastest ${times.head / 1e6}%.2f ms, slowest ${times.last / 1e6}%.2f ms; ${median / 1e3 / requests.size}%.1f us per script, ${requests.size / (median / 1e9)}%.0f scripts/s"
    )
    out.foreach(path =>
      Files.write(
        path,
        corpus.requests
          .map(_._1)
          .zip(lines)
          .map((id, l) => s"$id\t$l")
          .mkString("", "\n", "\n")
          .getBytes(
            StandardCharsets.UTF_8
          )
      )
    )
