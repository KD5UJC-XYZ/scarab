// SPDX-License-Identifier: Apache-2.0
package lab

import lab.cbor.Bytes
import lab.header.PraosCertificateState.Point
import lab.ledger.AdaPool
import lab.submission.StatePin
import ReferenceJson.{Json as J}

class AdaSubmissionClientSuite extends munit.FunSuite:
  private def b(n: Int) = Bytes(Vector.fill(32)(n.toByte))
  private val transactionId = b(9)
  private val pin =
    StatePin.checked(b(1), 2, Point(b(3), 4, 5), b(6), b(7), b(8), 4, StatePin.Profile).toOption.get
  private def actual =
    AdaHttpHandler.statusResponse(transactionId, Some(AdaPool.Status.Included(pin)))
  private def json(response: AdaHttp.Response): J =
    ReferenceJson.parse(Bytes.fromArray(response.json.getBytes("UTF-8")))
  private def fields: Map[String, J] = json(actual) match
    case J.Obj(values) => values
    case _             => fail("handler must emit an object")

  test("external client accepts the actual HTTP handler's Included schema") {
    val response = actual
    AdaSubmissionClientMain.validateIncluded(response.status, json(response), transactionId)
  }

  test("client rejects the obsolete equality field and unsupported equality claims") {
    val current = fields
    val obsolete = J.Obj(
      (current - "submittedEnvelopeByteEqualityVerified") +
        ("submittedEnvelopeByteEquality" -> J.Lit("false"))
    )
    val claimed = J.Obj(current.updated("submittedEnvelopeByteEqualityVerified", J.Lit("true")))
    Vector(obsolete, claimed).foreach { value =>
      intercept[IllegalArgumentException] {
        AdaSubmissionClientMain.validateIncluded(200, value, transactionId)
      }
    }
  }

  test("client binds actual inclusion to the requested body ID and complete pin") {
    intercept[IllegalArgumentException] {
      AdaSubmissionClientMain.validateIncluded(200, json(actual), b(10))
    }
    val incomplete = fields.updated("pin", J.Obj(Map.empty))
    intercept[IllegalArgumentException] {
      AdaSubmissionClientMain.validateIncluded(200, J.Obj(incomplete), transactionId)
    }
    intercept[IllegalArgumentException] {
      AdaSubmissionClientMain.validateIncluded(202, json(actual), transactionId)
    }
  }

  test("long status histories preserve first acceptance and recent observations within64KiB") {
    def observation(index: Int): J =
      val stage =
        if index == 10 then "admission" else if index == 11 then "exactDuplicate" else "status"
      val code =
        if index == 10 then "Accepted" else if index == 11 then "AlreadyPresent" else "Pending"
      J.Obj(
        Map(
          "stage" -> J.Str(stage),
          "index" -> J.Num(index.toString),
          "response" -> J.Obj(Map("code" -> J.Str(code), "padding" -> J.Str("x" * 1400)))
        )
      )
    val retained = (0 until 1000).foldLeft(Vector.empty[J]) { (rows, index) =>
      AdaSubmissionClientMain.retainObservation(rows, observation(index))
    }
    assert(retained.size <= 29)
    assert(retained.contains(observation(0)))
    assert(retained.contains(observation(10)))
    assert(retained.contains(observation(11)))
    assert(retained.contains(observation(999)))
    assert(SyntheticRewardProjection.encode(J.Arr(retained)).size <= 40960)
    val value = J.Obj(Map("passed" -> J.Lit("false"), "observations" -> J.Arr(retained)))
    assert(AdaSubmissionClientMain.encodeEvidence(value).size <= 65536)
  }

  test("oversized observations and result fields produce bounded metadata without success") {
    val huge = J.Obj(
      Map(
        "stage" -> J.Str("admission"),
        "response" ->
          J.Obj(Map("code" -> J.Str("Accepted"), "padding" -> J.Str("x" * 65536)))
      )
    )
    val retained = AdaSubmissionClientMain.retainObservation(Vector.empty, huge)
    assert(SyntheticRewardProjection.encode(J.Arr(retained)).size < 1024)
    val value = J.Obj(
      Map("passed" -> J.Lit("true"), "observations" -> J.Arr(retained), "acceptedResponse" -> huge)
    )
    val encoded = AdaSubmissionClientMain.encodeEvidence(value)
    assert(encoded.size <= 65536)
    val parsed = ReferenceJson.parse(encoded)
    assertEquals(ReferenceJson.field(parsed, "passed"), J.Lit("false"))
    assertEquals(ReferenceJson.field(parsed, "failureType"), J.Str("ClientEvidenceLimit"))
  }
