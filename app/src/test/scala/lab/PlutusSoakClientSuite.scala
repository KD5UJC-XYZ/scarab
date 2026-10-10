// SPDX-License-Identifier: Apache-2.0
package lab

import ReferenceJson.Json as J

class PlutusSoakClientSuite extends munit.FunSuite:
  private def pin(owner: String, slot: Int, generation: Int, environment: String) =
    val hash = J.Str("a" * 64)
    PlutusMultiEndpointHttp
      .stateReply(
        200,
        J.Obj(
          Map(
            "code" -> J.Str("State"),
            "closed" -> J.Lit("false"),
            "volatile" -> J.Lit("true"),
            "profileId" -> J.Str(lab.submission.AdmissionProfile.PlutusV3.id),
            "fullLedgerValidated" -> J.Lit("false"),
            "pin" -> J.Obj(
              Map(
                "ownerId" -> J.Str(owner * 64),
                "generation" -> J.Num(generation.toString),
                "point" -> J.Obj(
                  Map(
                    "hash" -> hash,
                    "slot" -> J.Num(slot.toString),
                    "blockNo" -> J.Num(generation.toString)
                  )
                ),
                "coherentStateId" -> hash,
                "ledgerStateId" -> hash,
                "environmentId" -> J.Str(environment * 64),
                "validationSlot" -> J.Num(slot.toString),
                "profileId" -> J.Str(lab.submission.AdmissionProfile.PlutusV3.id)
              )
            )
          )
        )
      )
      .pin
  private val before = Vector(pin("1", 10, 1, "3"), pin("2", 10, 1, "3"))
  private val after = Vector(pin("1", 1001, 3, "4"), pin("2", 1002, 4, "4"))
  test("both actual owners must cross with changed environments before second admission") {
    assert(!PlutusSoakClientMain.boundaryReached(before, before))
    assert(!PlutusSoakClientMain.boundaryReached(before, Vector(after(0), before(1))))
    assert(PlutusSoakClientMain.boundaryReached(before, after))
    PlutusSoakClientMain.postBoundary(after(1), pin("2", 1003, 5, "4"))
  }
  test("replacement owner or unchanged environment cannot stand in for epoch transition") {
    intercept[IllegalArgumentException](
      PlutusSoakClientMain.boundaryReached(before, Vector(after(0), pin("5", 1002, 4, "4")))
    )
    intercept[IllegalArgumentException](
      PlutusSoakClientMain.boundaryReached(before, Vector(after(0), pin("2", 1002, 4, "3")))
    )
    intercept[IllegalArgumentException](PlutusSoakClientMain.boundaryReached(after, after))
  }
  test("second admission rejects old slot, foreign owner and stale environment") {
    Vector(before(1), pin("5", 1003, 5, "4"), pin("2", 1003, 5, "3"), pin("2", 1001, 3, "4"))
      .foreach { accepted =>
        intercept[IllegalArgumentException](PlutusSoakClientMain.postBoundary(after(1), accepted))
      }
  }
