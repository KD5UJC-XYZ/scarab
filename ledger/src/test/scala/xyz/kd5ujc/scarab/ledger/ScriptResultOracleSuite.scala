// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.Id
import io.circe.Json
import io.circe.parser.parse
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.plutus.*
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.codec.Bytes

class ScriptResultOracleSuite extends FunSuite:
  import ContextReferenceCases.{field, Recorded}
  private val cases =
    parse(Files.readString(Path.of("fixtures/reference/script-results/aggregation.json")))
      .fold(e => fail(e.message), identity)
      .asArray
      .getOrElse(fail("not an array"))
      .map(Recorded(_))

  private def report(outcome: Phase2.Outcome): Phase2.Report = outcome match
    case Phase2.Outcome.Passed(value)    => value
    case Phase2.Outcome.Failed(value, _) => value
    case other                           => fail(s"no evaluation verdict: $other")

  cases.foreach { recorded =>
    test(s"${recorded.name}: unchanged reference results, every failure, traces and budgets") {
      val requests = recorded.collect.fold(es => fail(es.toString), identity)
      val outcome = Phase2.run(requests, ScalusEvaluator.default)
      val measured = report(outcome)
      val reference = field[Vector[Json]](recorded.oracle, "scripts")
      assertEquals(measured.scripts, reference.size)
      assertEquals(measured.logs, reference.flatMap(field[Vector[String]](_, "verboseLogs")))
      measured.evaluations.zip(reference).foreach { case (evaluation, expected) =>
        assertEquals(evaluation.result.isSuccess, field[Boolean](expected, "success"))
        expected.hcursor.get[Option[Json]]("used").fold(e => fail(e.message), identity).foreach {
          used =>
            assertEquals(
              evaluation.result.spent,
              ExUnits(field[BigInt](used, "mem"), field[BigInt](used, "steps"))
            )
        }
        assert(evaluation.result.spent.fits(evaluation.request.budget))
      }
      val failures = field[Vector[Json]](recorded.oracle, "failures")
      outcome match
        case Phase2.Outcome.Passed(_) => assertEquals(failures.size, 0)
        case Phase2.Outcome.Failed(_, actual) =>
          assertEquals(actual.length, failures.size)
          assert(actual.toVector.forall(_.reason.isInstanceOf[EvalFailure.Script]))
          assertEquals(
            actual.toVector.map { failure =>
              val request = failure.request
              val hash = Blake2b.hash224.hash(
                Bytes(Vector((request.language.id + 1).toByte)) ++ request.script
              )
              s"ScriptHash \"${hash.hex}\""
            },
            failures.map(field[String](_, "scriptHash"))
          )
          assertEquals(
            actual.toVector.map(_.request),
            measured.evaluations.filterNot(_.result.isSuccess).map(_.request)
          )
        case other => fail(s"unexpected outcome: $other")
      // The released ledger's runPlutusScriptWithLogs uses Quiet, independently of verbose API logs.
      assertEquals(field[Vector[String]](recorded.oracle, "logs"), Vector.empty)
      for valid <- Vector(true, false) do
        val checked = TxChecks.checkedPhase2(
          PlutusStats.zero,
          recorded.tx.copy(isValid = valid),
          Right(outcome)
        )
        if valid == outcome.passed then
          val stats = checked.fold(e => fail(e.toString), identity)
          assertEquals(stats.scripts, reference.size.toLong)
          assertEquals(stats.succeeded, if valid then 1L else 0L)
          assertEquals(stats.failed, if valid then 0L else 1L)
        else assert(checked.isLeft)
    }
  }

  private val requests = cases
    .find(_.name == "api-v2-failpassfail")
    .getOrElse(fail("missing mixed case"))
    .collect
    .fold(e => fail(e.toString), identity)

  test("a later backend outage is not hidden by an earlier proven failure, for either tag") {
    val limit = EvalFailure.Unsupported(BackendFailure.NoResult)
    val last = requests.lastOption.getOrElse(fail("no requests"))._2
    val evaluator: ScriptEvaluator[Id] = request =>
      if request == last then EvalResult.Failure(limit, ExUnits.zero, Vector.empty)
      else ScalusEvaluator.default.evaluate(request)
    Phase2.run(requests, evaluator) match
      case Phase2.Outcome.Unavailable(prefix, _, `limit`) =>
        assertEquals(prefix.scripts, 2)
        assert(!prefix.evaluations.headOption.exists(_.result.isSuccess))
      case other => fail(s"outage became a verdict: $other")
  }

  test("a raw later machine defect retains its original identity after a proven failure") {
    val defect = new IllegalStateException("aggregation backend defect")
    val last = requests.lastOption.getOrElse(fail("no requests"))._2
    val evaluator: ScriptEvaluator[Id] =
      request => if request == last then throw defect else ScalusEvaluator.default.evaluate(request)
    val actual = intercept[IllegalStateException](Phase2.run(requests, evaluator))
    assert(actual eq defect)
  }
