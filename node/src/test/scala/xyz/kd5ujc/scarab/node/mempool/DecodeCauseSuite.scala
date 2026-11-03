// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.mempool

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.ledger.TransactionDecodeProblem

class DecodeCauseSuite extends FunSuite:
  private def encoded(value: Value): Bytes = Cbor.encode(value).fold(e => fail(e.message), identity)

  test("mempool parsing retains the CBOR failure") {
    assertEquals(
      MempoolState.parse(Bytes.empty),
      Left(Rejection.Malformed(TransactionDecodeProblem.Syntax(Cbor.Error.UnexpectedEnd)))
    )
  }

  test("local submission retains the unexpected envelope value") {
    assertEquals(
      LocalSubmission.unwrap(encoded(Value.Null)),
      Left(Rejection.Malformed(TransactionDecodeProblem.EraEnvelope(Value.Null)))
    )
  }

  test("the validity flag is refused before transaction fields are decoded") {
    val nil = Node(Value.Null, Bytes.empty)
    val value = Value.Arr(Vector(nil, nil, nil, nil))
    assertEquals(
      MempoolState.parse(encoded(value)),
      Left(Rejection.Malformed(TransactionDecodeProblem.ValidityFlag(Value.Null)))
    )
  }

  test("a transaction holding detached records is read as the reference reads it (#687)") {
    // a Conway body whose output value is `9f ff coin multiasset`, and auxiliary data
    // `9f ff metadata scripts`: the reference reads each as a record of the items after the break
    val body = "a3" + "00818258201111111111111111111111111111111111111111111111111111111111111111" +
      "00" + "0181" + "82581d61" + "22" * 28 + "9fff1a001e8480a1581c" + "88" * 28 + "a1416101" +
      "021a00030d40"
    val aux = "9fffa11902a2617880"
    val raw = Bytes.fromHex("84" + body + "a0f5" + aux).fold(e => fail(e.toString), identity)
    val parsed = MempoolState.parseParts(raw).fold(e => fail(e.toString), identity)
    val bodyBytes = Bytes.fromHex(body).fold(e => fail(e.toString), identity)
    assertEquals(parsed.parts.body, bodyBytes)
    assertEquals(parsed.parts.auxiliaryData.map(_.hex), Some(aux))
    assertEquals(parsed.id, xyz.kd5ujc.scarab.crypto.Blake2b.hash256.hash(bodyBytes))
    // and the forger reads its parts again as the mempool read them
    val entry = Entry(parsed.id, raw, parsed.tx, 0L)
    assertEquals(
      xyz.kd5ujc.scarab.node.forge.BlockBuilder.piece(entry).map(p => (p.body, p.aux.map(_.hex))),
      Right((bodyBytes, Some(aux)))
    )
  }
