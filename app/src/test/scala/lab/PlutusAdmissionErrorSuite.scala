// SPDX-License-Identifier: Apache-2.0
package lab

import lab.cbor.Bytes
import lab.ledger.{PlutusAdmission, PlutusSpending, ScopedAdmission}
import lab.submission.{AdmissionProfile, SignedTransaction}

class PlutusAdmissionErrorSuite extends munit.FunSuite:
  private val render = new AdaHttpHandler.Representation(AdmissionProfile.PlutusV3)
  private def response(error: ScopedAdmission.Failure): AdaHttp.Response =
    render.resultResponse(AdaSubmissionService.Result.Rejected(error))

  test("submission preserves structural identity errors before requiring a ledger view") {
    val original = Bytes(Vector(0x80.toByte))
    val expected = SignedTransaction.checked(original).swap.toOption.get
    assertEquals(
      PlutusAdmission.prepare((), null, original, None).left.toOption,
      Some(ScopedAdmission.Failure.Identity(expected))
    )
    val rejected = response(ScopedAdmission.Failure.Identity(expected))
    assertEquals(rejected.status, 400)
    assert(rejected.json.contains("MalformedShape"))
    assertEquals(
      response(ScopedAdmission.Failure.Identity(SignedTransaction.Error.InputLimit)).status,
      413
    )
  }

  test("phase-one categories retain distinct HTTP codes without exposing private details") {
    import PlutusSpending.Failure as S
    val cases = Vector(
      (S.Malformed("private detail"), 400, "MalformedShape"),
      (S.Unsupported("private detail"), 422, "Unsupported"),
      (S.Rejected("private detail"), 422, "Rejected"),
      (S.InternalFailure("private detail"), 503, "InternalFailure")
    )
    cases.foreach { (error, status, code) =>
      val result = response(ScopedAdmission.Failure.Plutus(PlutusAdmission.Failure.PhaseOne(error)))
      assertEquals(result.status, status)
      assert(result.json.contains(code))
      assert(result.json.contains(AdmissionProfile.PlutusV3.id))
      assert(!result.json.contains("private detail"))
    }
  }

  test("direct-check identity failures retain the same renderer as submission identity failures") {
    val error = SignedTransaction.Error.DecodeRejected("private detail")
    val direct = response(ScopedAdmission.Failure.Plutus(PlutusAdmission.Failure.Identity(error)))
    assertEquals(direct, response(ScopedAdmission.Failure.Identity(error)))
    assertEquals(direct.status, 400)
  }
