// SPDX-License-Identifier: Apache-2.0
package lab

import lab.ledger.{AdaPool, ClusterTransition, PlutusAdmission, PlutusSpending, ScopedAdmission}
import lab.plutus.PlutusExecution
import lab.submission.{AdmissionProfile, AdmissionState, SignedTransaction}

class AdaHttpFailureSuite extends munit.FunSuite:
  private val detail = "secret-key:sample\n\"injected\":true\u263a"
  private val render = new AdaHttpHandler.Representation(AdmissionProfile.PlutusV3)
  private def expected(http: Int, code: String, category: String): AdaHttp.Response =
    AdaHttp.Response(
      http,
      s"""{"code":"$code","profileId":"${AdmissionProfile.PlutusV3.id}","volatile":true,"fullLedgerValidated":false,"status":"$category"}"""
    )
  private def rejected(value: ScopedAdmission.Failure): AdaHttp.Response =
    render.resultResponse(AdaSubmissionService.Result.Rejected(value))

  test("closed error cases retain every existing exact HTTP and JSON representation") {
    import AdaHttpHandler.FailureResponse as F
    val rows = Vector(
      (F.BudgetExhausted, 422, "BudgetExhausted", "rejected"),
      (F.Capacity, 429, "Capacity", "capacity"),
      (F.DecodeRejected, 400, "DecodeRejected", "rejected"),
      (F.EnvelopeConflict, 409, "EnvelopeConflict", "conflict"),
      (F.InputLimit, 413, "InputLimit", "rejected"),
      (F.InputsReserved, 409, "InputsReserved", "conflict"),
      (F.InternalFailure, 503, "InternalFailure", "unavailable"),
      (F.LocalLimit, 413, "LocalLimit", "rejected"),
      (F.MalformedShape, 400, "MalformedShape", "rejected"),
      (F.NonUnitReturn, 422, "NonUnitReturn", "rejected"),
      (F.Rejected, 422, "Rejected", "rejected"),
      (F.ScriptFailure, 422, "ScriptFailure", "rejected"),
      (F.StaleState, 409, "StaleState", "stale"),
      (F.Unavailable, 503, "Unavailable", "unavailable"),
      (F.Unsupported, 422, "Unsupported", "unsupported")
    )
    assertEquals(rows.map(_._1).toSet, F.values.toSet)
    rows.foreach { (value, http, code, category) =>
      assertEquals(render.failure(value), expected(http, code, category))
    }
  }

  test("identity paths and each validation error retain exact bytes without private details") {
    import ScopedAdmission.Failure as S
    import ClusterTransition.Failure as L
    import PlutusAdmission.Failure as P
    import PlutusSpending.Failure as O
    import PlutusExecution.Failure as E
    val identities = Vector(
      (SignedTransaction.Error.InputLimit, 413, "InputLimit"),
      (SignedTransaction.Error.DecodeRejected(detail), 400, "DecodeRejected"),
      (SignedTransaction.Error.MalformedShape(detail), 400, "MalformedShape")
    )
    identities.foreach { (value, status, code) =>
      assertEquals(rejected(S.Identity(value)), expected(status, code, "rejected"))
      assertEquals(rejected(S.Plutus(P.Identity(value))), rejected(S.Identity(value)))
    }
    val rows = Vector(
      (S.Unsupported(detail), 422, "Unsupported", "unsupported"),
      (S.Plutus(P.PhaseOne(O.Malformed(detail))), 400, "MalformedShape", "rejected"),
      (S.Plutus(P.PhaseOne(O.Unsupported(detail))), 422, "Unsupported", "unsupported"),
      (S.Plutus(P.PhaseOne(O.Rejected(detail))), 422, "Rejected", "rejected"),
      (S.Plutus(P.PhaseOne(O.InternalFailure(detail))), 503, "InternalFailure", "unavailable"),
      (S.Plutus(P.BindingMismatch), 503, "InternalFailure", "unavailable"),
      (S.Plutus(P.InternalFailure(detail)), 503, "InternalFailure", "unavailable"),
      (S.Plutus(P.Execution(E.MalformedInput(detail))), 400, "DecodeRejected", "rejected"),
      (S.Plutus(P.Execution(E.Unsupported(detail))), 422, "Unsupported", "unsupported"),
      (S.Plutus(P.Execution(E.ScriptFailure(detail))), 422, "ScriptFailure", "rejected"),
      (S.Plutus(P.Execution(E.NonUnitReturn)), 422, "NonUnitReturn", "rejected"),
      (S.Plutus(P.Execution(E.BudgetExhausted)), 422, "BudgetExhausted", "rejected"),
      (S.Plutus(P.Execution(E.InternalFailure(detail))), 503, "InternalFailure", "unavailable"),
      (S.Ledger(L.Unsupported(detail)), 422, "Unsupported", "unsupported"),
      (S.Ledger(L.DecodeRejected(detail)), 400, "DecodeRejected", "rejected"),
      (S.Ledger(L.Malformed(detail)), 400, "MalformedShape", "rejected"),
      (S.Ledger(L.ResourceLimit(detail)), 413, "LocalLimit", "rejected"),
      (
        S.Ledger(L.Rejected(lab.ledger.NativeSpending.Error.CaptureRejected(detail))),
        422,
        "Rejected",
        "rejected"
      ),
      (S.Ledger(L.StaleState(detail)), 409, "StaleState", "stale"),
      (S.Ledger(L.InternalFailure(detail)), 503, "InternalFailure", "unavailable")
    )
    rows.foreach { (value, http, code, category) =>
      assertEquals(rejected(value), expected(http, code, category))
    }
  }

  test("pool rejections and actual unexpected-error classifier keep stable redacted policy") {
    import AdaSubmissionService.Result as R
    import AdaHttpHandler.FailureResponse as F
    val rows = Vector(
      (R.PoolRejected(AdaPool.Rejection.EnvelopeConflict), 409, "EnvelopeConflict", "conflict"),
      (
        R.PoolRejected(AdaPool.Rejection.InputsReserved(Set.empty)),
        409,
        "InputsReserved",
        "conflict"
      ),
      (R.PoolRejected(AdaPool.Rejection.Capacity), 429, "Capacity", "capacity"),
      (R.Unavailable, 503, "Unavailable", "unavailable")
    )
    rows.foreach { (value, http, code, category) =>
      assertEquals(render.resultResponse(value), expected(http, code, category))
    }
    assertEquals(
      render.failure(F.unexpected(new RuntimeException(detail))),
      expected(503, "InternalFailure", "unavailable")
    )
    AdmissionState.UnavailableReason.values.foreach { reason =>
      assertEquals(
        render.failure(F.unexpected(new AdmissionState.Unavailable(reason))),
        expected(503, "Unavailable", "unavailable")
      )
    }
  }

  test("arbitrary failure triples and string classifications cannot reach the renderer") {
    import scala.compiletime.testing.{typeCheckErrors, typeChecks}
    assert(
      typeChecks(
        "new lab.AdaHttpHandler.Representation(lab.submission.AdmissionProfile.PlutusV3).failure(lab.AdaHttpHandler.FailureResponse.InternalFailure)"
      )
    )
    assert(
      typeCheckErrors(
        "new lab.AdaHttpHandler.Representation(lab.submission.AdmissionProfile.PlutusV3).failure(200, \"InternalFailure\", \"accepted\")"
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        "new lab.AdaHttpHandler.Representation(lab.submission.AdmissionProfile.PlutusV3).failure(\"InternalFailure\")"
      ).nonEmpty
    )
  }
