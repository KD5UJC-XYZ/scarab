// SPDX-License-Identifier: Apache-2.0
package lab

import lab.cbor.Bytes
import java.nio.file.{Files, Path}

class Sum6CommandSuite extends munit.FunSuite:
  test("bounded pinned supplied-message demo accepts only its exact four originals") {
    val data = Bytes.fromArray(Files.readAllBytes(Path.of("fixtures/sum6/originals.tsv")))
    val checks = Sum6Command.check(data).toOption.get
    assertEquals(checks.size, 4)
    assert(checks.forall(_.matched))
    Vector(
      null,
      Bytes(null),
      Bytes.empty,
      Bytes(data.value :+ 0.toByte),
      Bytes(Vector.fill(Sum6Command.MaxBytes + 1)(0.toByte))
    ).foreach(x => assert(Sum6Command.check(x).isLeft))
  }
  test("dependency seam is unavailable outside its implementation package") {
    assert(compileErrors("lab.kes.Sum6Kes.verifyWith(null,null,null,null,null,null)").nonEmpty)
  }
