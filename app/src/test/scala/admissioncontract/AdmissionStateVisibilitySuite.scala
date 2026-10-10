// SPDX-License-Identifier: Apache-2.0
package admissioncontract

class AdmissionStateVisibilitySuite extends munit.FunSuite:
  test("external callers may read a view but cannot acquire admission commit authority") {
    assertEquals(
      compileErrors(
        "val state: lab.submission.AdmissionState[cats.effect.IO] = null; state.current"
      ),
      ""
    )
    val denied = compileErrors(
      "val state: lab.submission.AdmissionState[cats.effect.IO] = null; state.withCurrent(null)(cats.effect.IO.unit)"
    )
    assert(denied.nonEmpty)
    assert(denied.contains("withCurrent"))
  }
