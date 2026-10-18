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
