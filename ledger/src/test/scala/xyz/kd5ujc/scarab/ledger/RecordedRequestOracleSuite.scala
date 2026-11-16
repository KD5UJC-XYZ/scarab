// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.syntax.all.*
import io.circe.{Decoder, Json, parser}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.plutus.*
import xyz.kd5ujc.scarab.uplc.Word64
import xyz.kd5ujc.scarab.uplc.cek.CekError
import xyz.kd5ujc.scarab.uplc.cost.ExBudget
import xyz.kd5ujc.scarab.uplc.ledger.{FreeVariableError, ScriptResult}
import xyz.kd5ujc.scarab.uplc.oracle.{GeneratedReplay, Outcome, Verdict}

/** The script requests the ledger collects for the recorded Preview and preprod transactions
  * ([[RecordedRequests]]), judged by the pinned Haskell evaluator of plutus 1.70.0.0
  * (`fixtures/reference/plutus-recorded-requests`, `scripts/oracle/plutus/recorded/run.py`). The
  * ledger collects them again, and each must be the request the oracle judged: the same script,
  * arguments, cost-model list, protocol and budget, so a change in the collection shows here too.
  * Each is evaluated by the node's evaluator ([[ScarabEvaluator]]), whose result is read in the
  * oracle's terms ([[Verdict]]), and must have the oracle's admission and stage, the class and
  * constructor of its error, the budget spent on success and on failure, and the logs, as
  * [[xyz.kd5ujc.scarab.uplc.oracle.GeneratedOracleSuite]] requires of generated scripts. No request
  * is excluded; one this evaluator gives no verdict on is a disagreement too.
  */
class RecordedRequestOracleSuite extends FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(10, "min")

  private val directory = Path.of(RecordedRequests.Directory)

  private def read(name: String): String =
    val path = directory.resolve(name)
    // a committed fixture: a missing file fails rather than leaving no requests
    if !Files.isRegularFile(path) then fail(s"missing committed fixture file $path")
    Files.readString(path)

  private def json(name: String): Json =
    parser.parse(read(name)).fold(e => fail(e.message), identity)

  private def rows(name: String): Vector[Json] =
    read(name).linesIterator.map(parser.parse(_).fold(e => fail(e.message), identity)).toVector

  private def field[A: Decoder](row: Json, key: String): A =
    row.hcursor.get[A](key).fold(e => fail(s"$key: ${e.message}"), identity)

  private lazy val manifest = json("manifest.json")
  private lazy val requests = rows("requests.jsonl")
  private lazy val verdicts = rows("verdicts.jsonl")
  private lazy val collected = RecordedRequests.corpus

  test("the fixture's files are the ones its manifest binds, with a verdict for every request") {
    field[Map[String, String]](manifest, "sha256").foreach((name, hash) =>
      assertEquals(RecordedRequests.sha256(directory.resolve(name)), hash, name)
    )
    assertEquals(
      field[String](manifest, "plutusCommit"),
      "507cab8cf6192e5143f12be5b2d3005a2928dc6d"
    )
    assertEquals(field[String](manifest, "release"), RecordedRequests.Release)
    assertEquals(requests.size, field[Int](manifest, "requests"))
    assert(requests.size >= 100, s"only ${requests.size} requests")
    assertEquals(verdicts.map(field[String](_, "id")), requests.map(field[String](_, "id")))
  }

  test("the ledger collects today the requests the oracle judged, from the same recorded cases") {
    assertEquals(collected.corpus, read("corpus.json"), "the recorded cases or their digests")
    assertEquals(collected.models, read("models.jsonl"), "the cost-model lists")
    val now = collected.requests.linesIterator.toVector
    val judged = read("requests.jsonl").linesIterator.toVector
    val differ = now.zipAll(judged, "", "").filterNot(_ == _).map((n, j) => s"now $n, judged $j")
    assertEquals(differ.take(3), Vector.empty[String], s"${differ.size} requests differ")
    assertEquals(
      RecordedRequests.sha256(collected.oracleRequests.getBytes(StandardCharsets.UTF_8)),
      field[String](manifest, "oracleRequestsSha256"),
      "the requests the oracle read, script and argument bytes included"
    )
  }

  test("the requests hold every language and both verdicts") {
    val languages = collected.recorded.map(_.request.language).toSet
    assertEquals(languages, Set(PlutusLanguage.V1, PlutusLanguage.V2, PlutusLanguage.V3))
    val stages = verdicts.map(field[String](_, "stage")).toSet
    assert(stages.contains("success") && stages.size > 1, stages.toString)
  }

  /** The node's result of a request in the oracle's terms, or why it gives none. The adapter keeps
    * the typed cause of every failure but running out of budget, whose remaining budget is the
    * request's less what was spent.
    */
  private def verdict(request: EvalRequest, result: EvalResult): Either[BackendFailure, Verdict] =
    def budget(units: ExUnits) = ExBudget.of(units.steps.toLong, units.mem.toLong)
    result match
      case EvalResult.Success(used, logs) => Right(Verdict.Ran(Outcome.Success, budget(used), logs))
      case EvalResult.Failure(reason, used, logs) =>
        reason match
          case EvalFailure.Unsupported(why)                  => Left(why)
          case EvalFailure.Decode(ScriptProblem.Uplc(error)) => Right(Verdict.Decode(error))
          case EvalFailure.Decode(ScriptProblem.UplcVersion(refusal)) =>
            Right(Verdict.Setup(refusal))
          case EvalFailure.OpenTerm(index) =>
            Word64
              .fromBigInt(index)
              .map(i => Right(Verdict.Scope(FreeVariableError.FreeIndex(i))))
              .getOrElse(fail(s"${request.script.hex}: free index $index beyond 64 bits"))
          case EvalFailure.OutOfBudget =>
            val remaining = ExBudget.of(
              (request.budget.steps - used.steps).toLong,
              (request.budget.mem - used.mem).toLong
            )
            Right(Verdict.Ran(Outcome.Failed(CekError.OutOfBudget(remaining)), budget(used), logs))
          case EvalFailure.Script(ScriptFailure.Uplc(error, _)) =>
            Verdict
              .of(ScriptResult.Failure(error, budget(used), logs))
              .leftMap(BackendFailure.Uplc(_))

  private lazy val evaluated: Vector[(String, Json, Json, Either[BackendFailure, Verdict])] =
    val byId = requests.zip(verdicts).map((r, v) => field[String](r, "id") -> (r, v)).toMap
    collected.recorded.map { recorded =>
      val (request, expected) = byId.getOrElse(recorded.id, fail(s"${recorded.id}: not judged"))
      val result = ScarabEvaluator.default.evaluate(recorded.request)
      (recorded.id, request, expected, verdict(recorded.request, result))
    }

  private lazy val replayed: Vector[GeneratedReplay.Replayed] =
    evaluated.collect { case (id, request, expected, Right(v)) =>
      GeneratedReplay.Replayed(id, request, expected, Right(v))
    }

  test("every recorded request: the admission, stage, error and success budget of the oracle") {
    val noVerdict = evaluated.collect { case (id, _, expected, Left(why)) =>
      s"$id: no verdict (${why.message}); oracle ${GeneratedReplay.expectedText(expected)}"
    }
    val disagreements = noVerdict ++ GeneratedReplay.required(replayed)
    val stages = verdicts.groupMapReduce(field[String](_, "stage"))(_ => 1)(_ + _)
    println(
      s"recorded-request oracle: ${evaluated.size} requests " +
        s"(${stages.toVector.sorted.mkString(", ")}), ${disagreements.size} disagreements"
    )
    disagreements.take(40).foreach(println)
    assertEquals(disagreements.take(40), Vector.empty[String])
  }

  test("every recorded request that ran: the budget spent on failure and the logs") {
    val disagreements = GeneratedReplay.spentAndLogs(replayed)
    println(s"recorded-request oracle, spent budget and logs: ${disagreements.size} disagreements")
    disagreements.take(40).foreach(println)
    assertEquals(disagreements.take(40), Vector.empty[String])
  }

  test("negative control: every cost parameter doubled, no success spends the recorded budget") {
    val unlimited = ExUnits(BigInt(Long.MaxValue / 4), BigInt(Long.MaxValue / 4))
    val successes = evaluated.collect {
      case (id, _, expected, _) if field[String](expected, "stage") == "success" => id -> expected
    }.toMap
    val same = collected.recorded.filter { r =>
      successes.get(r.id).exists { expected =>
        val doubled = r.request.copy(costModel = r.request.costModel.map(_ * 2), budget = unlimited)
        ScarabEvaluator.default.evaluate(doubled) match
          case EvalResult.Success(used, _) =>
            (used.steps, used.mem) ==
              (field[BigInt](expected, "cpu"), field[BigInt](expected, "memory"))
          case _ => false
      }
    }
    assert(successes.size > evaluated.size / 4, s"only ${successes.size} successes")
    assertEquals(same.map(_.id), Vector.empty[String])
  }
