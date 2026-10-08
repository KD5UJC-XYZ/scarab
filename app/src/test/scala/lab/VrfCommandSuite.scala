// SPDX-License-Identifier: Apache-2.0
package lab

import java.nio.file.{Files, Path}
import lab.cbor.Bytes

class VrfCommandSuite extends munit.FunSuite:
  private def data: Map[String, Bytes] = VrfCommand.pins.keys
    .map(name => name -> Bytes.fromArray(Files.readAllBytes(Path.of("fixtures/vrf", name))))
    .toMap
  test("pinned CLI corpus returns exact outputs and classifications") {
    val checks = VrfCommand.check(data).toOption.get
    assertEquals(checks.size, 2048)
    assert(checks.forall(_.matched))
    assertEquals(checks.count(_.actual.startsWith("VALID:")), 3)
    assertEquals(checks.count(_.actual == "MALFORMED"), 27)
  }
  test("missing corpus fails before verification") {
    assert(VrfCommand.check(Map.empty).isLeft)
  }
  test("mutation, swapped filename and unknown filename fail immutable admission") {
    val original = data("vectors.tsv")
    assert(VrfCommand.admit("expected.tsv", original).isLeft)
    assert(VrfCommand.admit("unknown.tsv", original).isLeft)
    assert(VrfCommand.admit("vectors.tsv", Bytes(original.value.updated(0, 0.toByte))).isLeft)
  }
  test("oversized fixture is bounded before digest or parse") {
    assertEquals(
      VrfCommand.admit("vectors.tsv", Bytes(Vector.fill(VrfCommand.MaxBytes + 1)(0.toByte))),
      Left("vectors.tsv exceeds 8 MiB")
    )
  }
