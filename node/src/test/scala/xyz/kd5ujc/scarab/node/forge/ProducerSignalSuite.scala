// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import cats.syntax.all.*
import munit.FunSuite

/** CIP-0203's minor of scheme 0 against the CIP's "Test vectors" table (`CIP-0203/README.md` of
  * cardano-foundation/CIPs#1276 at 8bf55a9b), and the two signals the node forges (#1348).
  */
class ProducerSignalSuite extends FunSuite:
  private def minor(payload: Int, id: Int): BigInt =
    (ProducerSignal.Payload.from(payload), ProducerSignal.Id.from(id))
      .mapN(ProducerSignal.minor)
      .getOrElse(fail(s"out of range: payload $payload, id $id"))

  test("the scheme-0 vectors of the CIP encode to their minors") {
    // (minor, payload, id); the scheme-1 vector has no scheme-0 encoding
    val vectors = Vector(
      (BigInt(0), 0, 0),
      (BigInt(7), 0, 7),
      (BigInt(67), 0, 67),
      (BigInt(69), 0, 69),
      (BigInt(315973), 1234, 69),
      (BigInt(1073741637L), 4194303, 69),
      (BigInt(1073741823L), 4194303, 255)
    )
    vectors.foreach((expected, payload, id) =>
      assertEquals(minor(payload, id), expected, s"payload $payload, id $id")
    )
    // every scheme-0 minor stays below the scheme bits
    assert(minor(4194303, 255) < (BigInt(1) << 30))
  }

  test("an identifier above 8 bits or a payload above 22 bits is refused") {
    assertEquals(ProducerSignal.Id.from(256), None)
    assertEquals(ProducerSignal.Id.from(-1), None)
    assertEquals(ProducerSignal.Payload.from(4194304), None)
    assertEquals(ProducerSignal.Payload.from(-1), None)
  }

  test("the node signals 73 with no payload by default, 255 when the operator opts out") {
    assertEquals(ProducerSignal.Default, ProducerSignal.Identified)
    assertEquals(ProducerSignal.Identified.minor, BigInt(73))
    assertEquals(ProducerSignal.OptedOut.minor, BigInt(255))
    assertEquals(
      ProducerSignal.byName,
      Map("scarab" -> ProducerSignal.Identified, "opt-out" -> ProducerSignal.OptedOut)
    )
  }
