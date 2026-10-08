// SPDX-License-Identifier: Apache-2.0
package lab

import java.nio.file.{Files, Path}
import lab.cbor.Bytes

class OpcertCommandSuite extends munit.FunSuite:
  private def data =
    Bytes.fromArray(Files.readAllBytes(Path.of("fixtures/opcert/certificates.tsv")))
  test("28 pinned cases include four positives and 24 rejections") {
    val checks = OpcertCommand.check(data).toOption.get
    assertEquals(checks.size, 28)
    assert(checks.forall(_.matched))
    assertEquals(checks.count(_.actual == "VERIFIED"), 4)
    assertEquals(checks.count(_.actual == "REJECTED"), 24)
  }
  test("null empty altered appended and oversized input is rejected before parsing") {
    Vector(
      null,
      Bytes.empty,
      Bytes(data.value.updated(0, 0.toByte)),
      Bytes(data.value :+ 10.toByte),
      Bytes(Vector.fill(OpcertCommand.MaxBytes + 1)(0.toByte))
    ).foreach(b => assert(OpcertCommand.check(b).isLeft))
  }
