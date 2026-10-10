// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import lab.TransactionId
import lab.submission.SignedTransaction
import lab.cbor.{Bytes, Cbor, Node, Value}
import java.nio.file.{Files, Path}

/** Test-only fixture reader; expected contexts are used only after independent construction. */
class PlutusBoundaryDifferentialSuite extends munit.FunSuite:
  private def hex(s: String) = Bytes.fromHex(s).fold(fail(_), identity)
  private def decode(b: Bytes) = Cbor.decode(b).fold(fail(_), identity)
  private def arr(n: Node) = n.value.asInstanceOf[Value.Arr].value
  private def fields(n: Node) = n.value
    .asInstanceOf[Value.Map]
    .value
    .map((k, v) => k.value.asInstanceOf[Value.UInt].value.toInt -> v)
    .toMap
  private def uint(n: Node) = n.value.asInstanceOf[Value.UInt].value
  private def bytes(n: Node) = n.value.asInstanceOf[Value.ByteString].value
  private def untag(n: Node) = n.value.asInstanceOf[Value.Tag].value
  private val rows = ujson
    .read(Files.readString(Path.of("fixtures/plutus-pv9-translator/vectors.json")))("vectors")
    .arr
    .toVector
  private val successes =
    rows.filter(_("reference")("evaluation")("status").str == "v3-validation-success")

  private def sourceInput(packet: ujson.Value): PlutusContextInput =
    val tx = hex(packet("transactionCborHex").str)
    val envelope = arr(decode(tx))
    val witnessFields = envelope(1).value.asInstanceOf[Value.Map].value
    def node(v: Value) = Node(v, Bytes.empty)
    // Shape-only witness: these unsigned reference packets are not phase-one/admission fixtures.
    val dummyKey = node(
      Value.Tag(
        258,
        node(
          Value.Arr(
            Vector(
              node(
                Value.Arr(
                  Vector(
                    node(Value.ByteString(Bytes(Vector.fill(32)(0.toByte)))),
                    node(Value.ByteString(Bytes(Vector.fill(64)(0.toByte))))
                  )
                )
              )
            )
          )
        )
      )
    )
    val witnesses = Cbor
      .encode(Value.Map(witnessFields :+ (node(Value.UInt(0)) -> dummyKey)))
      .fold(fail(_), identity)
    val wrapped = Bytes(
      Vector(0x84.toByte) ++ envelope.head.original.value ++ witnesses.value ++
        Vector(0xf5.toByte, 0xf6.toByte)
    )
    val signed = SignedTransaction.checked(wrapped).fold(e => fail(e.toString), identity)
    assertEquals(signed.originalBody, envelope.head.original)
    val resolvedInputs = packet("utxo").arr.toVector.map { x =>
      val ref = arr(decode(hex(x("inputCborHex").str)))
      PlutusContextInput.ResolvedInput(bytes(ref(0)), uint(ref(1)), hex(x("outputCborHex").str))
    }
    val references = arr(untag(fields(envelope.head)(0))).map { n =>
      val pair = arr(n); bytes(pair(0)) -> uint(pair(1))
    }.toSet
    val (ordinaryInputs, collateralInputs) =
      resolvedInputs.partition(x => references.contains(x.transactionId -> x.index))
    val contextInput = PlutusContextInput(
      signed,
      ordinaryInputs,
      collateralInputs,
      PlutusContextInput.SlotTime(
        BigInt("1577836800000"),
        1000,
        1,
        Bytes(Vector.fill(32)(1.toByte))
      )
    )
    contextInput

  test("all eleven retained successful contexts fit the one-input/output profile") {
    assertEquals(successes.size, 11)
  }
  successes.foreach { row =>
    test(s"${row("name").str}: construct all V3 fields before comparing reference bytes") {
      val packet = row("packet")
      val tx = hex(packet("transactionCborHex").str)
      val body = fields(arr(decode(tx)).head)
      val own = arr(untag(body(0)))
      assertEquals(own.size, 1)
      val ref = arr(own.head)
      val resolved =
        packet("utxo").arr.find(x => decode(hex(x("inputCborHex").str)).value == own.head.value).get
      val input = fields(decode(hex(resolved("outputCborHex").str)))
      val datum = arr(untag(decode(bytes(untag(arr(input(2))(1))))))
      val outputs = arr(body(1)); assertEquals(outputs.size, 1)
      val payout = outputs.head.value match
        case Value.Arr(xs) => xs
        case Value.Map(_)  => val fs = fields(outputs.head); Vector(fs(0), fs(1))
        case _             => fail("fixture payout shape")
      // Explicit fixture schedule only. Production assembly receives authenticated POSIX endpoints.
      def millis(key: Int) = body.get(key).map(x => BigInt("1577836800000") + uint(x) * 1000)
      val built = PlutusContext
        .spendData(
          TransactionId.fromEnvelope(tx).fold(fail(_), identity),
          bytes(ref(0)),
          uint(ref(1)),
          Bytes(bytes(input(0)).value.tail),
          uint(input(1)),
          bytes(datum(0)),
          uint(datum(1)),
          Bytes(bytes(payout(0)).value.tail),
          uint(payout(1)),
          uint(body(2)),
          millis(8),
          millis(3)
        )
        .fold(fail(_), identity)
      assertEquals(built, hex(row("reference")("contextDataCborHex").str))
      val contextInput = sourceInput(packet)
      val signed = contextInput.transaction
      val ordinaryInputs = contextInput.ordinaryInputs
      val derived = PlutusContext.derive(contextInput, 0).fold(e => fail(e.toString), identity)
      assertEquals(derived.contextCbor, built)
      assertEquals(derived.transactionId, signed.transactionId)
      val model = Bytes.fromArray(
        Files.readAllBytes(Path.of("vm/src/main/resources/plutus-pv9/cost-model.json"))
      )
      assertEquals(
        PlutusIntegrity.check(derived.originalRedeemers, model, derived.suppliedIntegrity),
        Right(derived.suppliedIntegrity)
      )
      assert(PlutusContext.derive(contextInput.copy(ordinaryInputs = Vector.empty), 0).isLeft)
      assert(PlutusContext.derive(contextInput.copy(collateralInputs = ordinaryInputs), 0).isLeft)
      assert(PlutusContext.derive(contextInput, 1).isLeft)
      assert(
        PlutusContext
          .derive(contextInput.copy(time = contextInput.time.copy(slotLengthDenominator = 0)), 0)
          .isLeft
      )
      if row("name").str == "interval-both" then
        val shifted = PlutusContext
          .derive(
            contextInput.copy(time =
              contextInput.time
                .copy(systemStartMillis = BigInt("1700000000000"), slotLengthNumeratorMillis = 100)
            ),
            0
          )
          .fold(e => fail(e.toString), identity)
        val contextFields = arr(untag(decode(shifted.contextCbor)))
        val txInfo = arr(untag(contextFields.head))
        val interval = arr(untag(txInfo(7)))
        def endpoint(bound: Node): BigInt = uint(arr(untag(arr(untag(bound)).head)).head)
        assertEquals(endpoint(interval(0)), BigInt("1700000001000"))
        assertEquals(endpoint(interval(1)), BigInt("1700000002000"))
        assert(
          PlutusContext
            .derive(
              contextInput.copy(time =
                contextInput.time.copy(slotLengthNumeratorMillis = 1, slotLengthDenominator = 3)
              ),
              0
            )
            .isLeft
        )

    }
  }

  test("all reference integrity domains preserve original redeemer container bytes") {
    val integrity = ujson
      .read(Files.readString(Path.of("fixtures/plutus-pv9-integrity/vectors.json")))("vectors")
      .arr
      .toVector
    val model = Bytes.fromArray(
      Files.readAllBytes(Path.of("vm/src/main/resources/plutus-pv9/cost-model.json"))
    )
    val view = PlutusIntegrity.languageView(model).fold(fail(_), identity)
    assertEquals(view.size, 690)
    assertEquals(integrity.size, 28)
    integrity.foreach { row =>
      val original = hex(row("packet")("transactionCborHex").str)
      val envelope = arr(decode(original))
      val redeemers = fields(envelope(1))(5).original
      val reference = row("reference")
      assertEquals(redeemers, hex(reference("redeemerBytesHex").str))
      assertEquals(view, hex(reference("languageViewHex").str))
      assertEquals(reference("datumBytesHex").str, "")
      assertEquals(Bytes(redeemers.value ++ view.value), hex(reference("preimageHex").str))
      val digest = PlutusIntegrity.commitment(redeemers, view).fold(fail(_), identity)
      assertEquals(digest, hex(reference("computedHashHex").str))
      val supplied = fields(envelope.head).get(11).map(bytes)
      assertEquals(supplied.contains(digest), reference("matches").bool)
    }
  }

  test("all seven wider-profile packets reject at public context derivation") {
    val excluded = rows.filterNot(successes.contains)
    assertEquals(excluded.size, 7)
    excluded.foreach { row =>
      assert(PlutusContext.derive(sourceInput(row("packet")), 0).isLeft, row("name").str)
    }
  }

  test(
    "public context boundary rejects missing, oversized, duplicate and misbound source material"
  ) {
    val base = sourceInput(rows.find(_("name").str == "base").get("packet"))
    def rejected(input: PlutusContextInput) = assert(PlutusContext.derive(input, 0).isLeft)
    rejected(base.copy(ordinaryInputs = base.ordinaryInputs ++ base.ordinaryInputs))
    rejected(base.copy(collateralInputs = Vector.empty))
    rejected(base.copy(ordinaryInputs = base.ordinaryInputs.map(_.copy(index = 1))))
    rejected(
      base.copy(ordinaryInputs =
        base.ordinaryInputs.map(_.copy(originalOutput = Bytes(Vector.fill(4097)(0.toByte))))
      )
    )
    rejected(base.copy(time = base.time.copy(genesisDigest = Bytes.empty)))
    rejected(base.copy(time = base.time.copy(slotLengthNumeratorMillis = -1)))
    rejected(null)
    val envelope = arr(decode(base.transaction.original))
    def withBody(entries: Vector[(Node, Node)]) =
      val body = Cbor.encode(Value.Map(entries)).fold(fail(_), identity)
      val raw = Bytes(
        Vector(0x84.toByte) ++ body.value ++ base.transaction.originalWitnesses.value ++
          Vector(0xf5.toByte, 0xf6.toByte)
      )
      base.copy(transaction = SignedTransaction.checked(raw).fold(e => fail(e.toString), identity))
    val entries = envelope.head.value.asInstanceOf[Value.Map].value
    rejected(withBody(entries.filterNot(_._1.value == Value.UInt(11))))
    rejected(
      withBody(
        entries :+ (Node(Value.UInt(14), Bytes.empty) -> Node(Value.Arr(Vector.empty), Bytes.empty))
      )
    )
  }
