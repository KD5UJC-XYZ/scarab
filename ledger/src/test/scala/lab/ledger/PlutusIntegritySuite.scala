// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import lab.cbor.{Bytes, Cbor, Value}
import java.nio.file.{Files, Path}

class PlutusIntegritySuite extends munit.FunSuite:
  private def read(p: String) = Bytes.fromArray(Files.readAllBytes(Path.of(p)))
  private val model = read("vm/src/main/resources/plutus-pv9/cost-model.json")
  private val tx = Cbor
    .decode(read("fixtures/plutus-pv9-reference/inputs/transaction.cbor"))
    .fold(fail(_), identity)
    .value
    .asInstanceOf[Value.Arr]
    .value
  private def field(index: Int, key: Int) = tx(index).value
    .asInstanceOf[Value.Map]
    .value
    .find(_._1.value == Value.UInt(key))
    .get
    ._2
  private val original = field(1, 5).original
  private val supplied = field(0, 11).value.asInstanceOf[Value.ByteString].value
  test("original reference commitment verifies; stale, missing and malformed commitments reject") {
    assertEquals(PlutusIntegrity.check(original, model, supplied), Right(supplied))
    for hash <- Vector(Bytes.empty, Bytes(Vector.fill(32)(0.toByte)), null) do
      assert(PlutusIntegrity.check(original, model, hash).isLeft)
    assert(PlutusIntegrity.check(Bytes(original.value :+ 0.toByte), model, supplied).isLeft)
    assert(
      PlutusIntegrity.check(original, Bytes(model.value.updated(0, 0.toByte)), supplied).isLeft
    )
  }
  test("indefinite redeemer container retains its distinct reference commitment") {
    val indefinite = Bytes(Vector(0xbf.toByte) ++ original.value.tail :+ 0xff.toByte)
    val expected = Bytes
      .fromHex("3eed06f0b5676bcbcdd5d4b3d9705b362e23311c351de50066b761228bb16a79")
      .fold(fail(_), identity)
    assert(PlutusIntegrity.check(indefinite, model, supplied).isLeft)
    assertEquals(PlutusIntegrity.check(indefinite, model, expected), Right(expected))
  }
