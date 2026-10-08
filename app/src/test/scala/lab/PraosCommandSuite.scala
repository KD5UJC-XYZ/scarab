// SPDX-License-Identifier: Apache-2.0
package lab

import java.nio.file.{Files, Path}
import lab.cbor.Bytes

class PraosCommandSuite extends munit.FunSuite:
  private def data = Bytes.fromArray(Files.readAllBytes(Path.of("fixtures/praos/certificates.tsv")))
  test("bounded pinned demo checks all four exact public outputs and mutation categories") {
    val checks = PraosCommand.check(data).toOption.get
    assertEquals(checks.size, 18)
    assert(checks.forall(_.matched))
    assertEquals(checks.count(_.actual.startsWith("VERIFIED:")), 4)
    assertEquals(checks.count(_.actual == "OUTPUT_MISMATCH"), 2)
    assertEquals(checks.count(_.actual == "PROOF_REJECTED"), 12)
  }
  test("null, empty, altered, appended and oversized inputs fail before parsing") {
    Vector(
      null,
      Bytes.empty,
      Bytes(data.value.updated(0, 0.toByte)),
      Bytes(data.value :+ 10.toByte),
      Bytes(Vector.fill(PraosCommand.MaxBytes + 1)(0.toByte))
    ).foreach(d => assert(PraosCommand.check(d).isLeft))
  }
