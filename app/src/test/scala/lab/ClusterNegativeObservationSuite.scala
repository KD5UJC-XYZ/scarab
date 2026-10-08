// SPDX-License-Identifier: Apache-2.0
package lab

import lab.cbor.{Bytes, Cbor, Node, Value}
import lab.ledger.ClusterTransfer
import java.nio.file.Path

/** Synthetic selected-predicate guards; reference agreement requires opt-in evidence. */
class ClusterNegativeObservationSuite extends munit.FunSuite:
  import ClusterNegativeObservation.*
  private def node(value: Value): Node = Node(value, Bytes.empty)
  private def arr(values: Value*): Value = Value.Arr(values.toVector.map(node))
  private def map(values: (Value, Value)*): Value =
    Value.Map(values.toVector.map { case (key, value) => node(key) -> node(value) })
  private def raw(value: Value): Bytes = Cbor.encode(value).fold(fail(_), identity)
  private val digest = Bytes(Vector.fill(32)(1.toByte))
  private val input = arr(Value.ByteString(digest), Value.UInt(0))
  private val address = Value.ByteString(Bytes(Vector(0x60.toByte) ++ Vector.fill(28)(2.toByte)))
  private val output = arr(address, Value.UInt(10000000))
  private val body =
    map(Value.UInt(0) -> arr(input), Value.UInt(1) -> arr(output), Value.UInt(2) -> Value.UInt(0))
  private val tx = raw(arr(body, map(), Value.Bool(true), Value.Null))
  private val resolved = raw(map(input -> output))
  private val empty = raw(map())
  private val context = ClusterTransfer.Context
    .checked(
      digest,
      digest,
      1082026,
      digest,
      digest,
      1,
      2,
      0,
      0,
      9,
      0,
      1,
      0,
      16384,
      0,
      200000
    )
    .fold(fail(_), identity)

  test("existing pipeline distinguishes missing coverage from unresolved inputs") {
    assertEquals(evaluate(context, resolved, tx), Outcome.Rejected(Rejection.MissingRequiredKeys))
    assertEquals(evaluate(context, empty, tx), Outcome.Rejected(Rejection.UnresolvedInputs))
  }
  test("malformed bytes never count as the expected rejection") {
    assert(evaluate(context, resolved, Bytes.empty).isInstanceOf[Outcome.Unsupported])
    assert(evaluate(context, Bytes.empty, tx).isInstanceOf[Outcome.Unsupported])
  }
  test("classifier recognizes exact errors only and does not overclaim other checks") {
    for error <- Vector(
        "witness signature rejected",
        "value not conserved",
        "fee or size predicate failed",
        "unsupported",
        "missing required witness keys extra",
        "unresolved spending inputs extra"
      )
    do assertEquals(classify(Left(error)), Outcome.Unsupported(error))
    val receipt = ClusterTransfer.Receipt(digest, 0, 0, Set.empty, Set.empty, 0)
    assertEquals(classify(Right(receipt)), Outcome.UnexpectedAcceptance)
  }
  private val scenario = scenarios.head
  private val receipt =
    s"""{"scope":"reference-local-submission-observation","referenceRejectionLayer":"ledger-rule","recognizedReferenceRejection":true,"recognizedLedgerRejection":true,"returncode":1,"passed":true,"stableStateVerified":true,"singleAcquiredSnapshot":false,"transactionId":"${digest.hex}","transactionCborSha256":"${sha256(
        tx
      )}","expectedReason":"MissingVKeyWitnessesUTXOW","stdout":"","stderr":"MissingVKeyWitnessesUTXOW"}"""
  private def parsed(text: String) = ReferenceJson.parse(Bytes.fromArray(text.getBytes("UTF-8")))
  test(
    "reference receipt requires exact transaction identity and recognized unsuccessful submission"
  ) {
    referenceRejection(parsed(receipt), scenario, digest, tx)
    for (from, to) <- Vector(
        "\"returncode\":1" -> "\"returncode\":0",
        "\"returncode\":1" -> "\"returncode\":1e0",
        "\"passed\":true" -> "\"passed\":false",
        "\"stableStateVerified\":true" -> "\"stableStateVerified\":false",
        "\"singleAcquiredSnapshot\":false" -> "\"singleAcquiredSnapshot\":true",
        "\"stderr\":\"MissingVKeyWitnessesUTXOW\"" -> "\"stderr\":\"socket unavailable\"",
        digest.hex -> ("02" * 32)
      )
    do
      intercept[IllegalArgumentException](
        referenceRejection(parsed(receipt.replace(from, to)), scenario, digest, tx)
      )
  }
  test("wrong reference class and duplicate fields fail closed") {
    intercept[IllegalArgumentException](
      referenceRejection(parsed(receipt), scenarios(1), digest, tx)
    )
    intercept[IllegalArgumentException](
      parsed(receipt.replace("\"returncode\":1", "\"returncode\":1,\"returncode\":0"))
    )
  }
  test("mempool reference requires exact reason and cannot claim ledger-rule rejection") {
    val mempool = scenarios(1)
    val escaped = SpentInputsReference.replace("\\", "\\\\").replace("\"", "\\\"")
    val text = receipt
      .replace("MissingVKeyWitnessesUTXOW", escaped)
      .replace("ledger-rule", "mempool")
      .replace("\"recognizedLedgerRejection\":true", "\"recognizedLedgerRejection\":false")
    referenceRejection(parsed(text), mempool, digest, tx)
    for altered <- Vector(
        text.replace("\"recognizedLedgerRejection\":false", "\"recognizedLedgerRejection\":true"),
        text.replace("mempool", "ledger-rule"),
        text.replace("All inputs are spent.", "Other failure."),
        text.replace(
          "\"recognizedReferenceRejection\":true",
          "\"recognizedReferenceRejection\":false"
        )
      )
    do intercept[IllegalArgumentException](referenceRejection(parsed(altered), mempool, digest, tx))
  }
  sys.env.get("CLUSTER_NEGATIVE_EVIDENCE").foreach { directory =>
    test("opt-in original reference negatives agree with selected Scala rejection classes") {
      assertEquals(inspect(Path.of(directory)), scenarios.map(s => s.label -> s.reason))
    }
  }
