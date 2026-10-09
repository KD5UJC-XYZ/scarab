// SPDX-License-Identifier: Apache-2.0
package lab

import lab.cbor.{Bytes, Value as V}
import lab.header.PraosCertificateState.Point
import ReferenceJson.Json as J

/** Generated originals only; no native acquisition, consensus or admission claim. */
class NativeLedgerV2Suite extends NativeLedgerSeedFixtures:
  private final case class Packet(originals: Map[String, Bytes], anchor: Point):
    def pins = originals.map((name, raw) => name -> sha(raw))
    def decoded = NativeLedgerV2.decode(originals, pins, anchor)
    def acquisition = get(
      NativeProtocolBootstrap.checkAcquisition(
        originals.filter((name, _) => NativeProtocolBootstrap.InputNames(name)),
        pins.filter((name, _) => NativeProtocolBootstrap.InputNames(name)),
        anchor
      )
    )
  private def fields(raw: Bytes): Map[String, J] =
    ReferenceJson.parse(raw).asInstanceOf[J.Obj].fields
  private def fixture(
      base: Bundle = bundle(),
      counter: Int = 4,
      epochNonce: Int = 9,
      receiptHelper: Int = 4
  ): Packet =
    val previous = base.epoch.originals
    val anchor = base.epoch.point
    def nonce(i: Int): V = a(V.UInt(1), b(32, i))
    val protocol = raw(
      a(
        V.UInt(0),
        a(
          a(V.UInt(1), V.UInt(anchor.slot)),
          m(b(28, 4) -> V.UInt(counter)),
          nonce(6),
          nonce(7),
          nonce(epochNonce),
          nonce(10),
          nonce(11),
          nonce(12)
        )
      )
    )
    val request = json(
      J.Obj(
        fields(previous("request.json")) ++ Map(
          "schema" -> J.Num("2"),
          "socket" -> J.Str("/synthetic/v2.sock"),
          "byronEpochSlots" -> J.Num("50"),
          "producerBinarySHA256" -> J.Str("02" * 32),
          "producerImage" -> J.Str("sha256:" + "03" * 32)
        )
      )
    )
    val capture = json(
      J.Obj(
        fields(previous("capture.json")) ++ Map(
          "schema" -> J.Num("2"),
          "kind" -> J.Str("single-acquire-native-protocol-payloads"),
          "protocolHex" -> J.Str(protocol.hex),
          "release" -> J.Str("sent-no-ack"),
          "queryEncoding" -> J.Str("GetCBOR-server-maxBound")
        )
      )
    )
    def pin(name: String): J = J.Str(sha(previous(name)).hex)
    val verifier = json(
      obj(
        "schema" -> J.Num("1"),
        "kind" -> J.Str("derived-native-full-epoch-seed"),
        "epochInputSHA256" -> pin("original-debug-epoch.cbor"),
        "utxoInputSHA256" -> pin("original-whole-utxo.cbor"),
        "epochFullConsumption" -> J.Lit("true"),
        "utxoFullConsumption" -> J.Lit("true"),
        "epochRoundTripEqual" -> J.Lit("true"),
        "utxoRoundTripEqual" -> J.Lit("true"),
        "derivedRoundTripEqual" -> J.Lit("true"),
        "onlyUtxoReplaced" -> J.Lit("true"),
        "derivedSeedHex" -> J.Str(previous("derived-full-epoch-seed.cbor").hex),
        "wholeUTxOEntries" -> J.Num("1"),
        "runtimeImport" -> J.Lit("false"),
        "monetaryParity" -> J.Lit("false"),
        "rewardSeedAdmission" -> J.Lit("false"),
        "admissionChecks" -> J.Str("not-performed")
      )
    )
    val receipt = json(
      obj(
        "schema" -> J.Num("2"),
        "kind" -> J.Str("reviewable-single-acquire-native-protocol-seed"),
        "queryHelperSHA256" -> J.Str(bytes(32, receiptHelper).hex),
        "verifierSHA256" -> J.Str("05" * 32),
        "producerIdentitySource" -> J.Str("caller-supplied-review-pin-not-peer-attestation"),
        "requestSHA256" -> J.Str(sha(request).hex),
        "captureSHA256" -> J.Str(sha(capture).hex),
        "epochSHA256" -> pin("original-debug-epoch.cbor"),
        "utxoSHA256" -> pin("original-whole-utxo.cbor"),
        "protocolSHA256" -> J.Str(sha(protocol).hex),
        "derivedSeedSHA256" -> pin("derived-full-epoch-seed.cbor"),
        "runtimeImport" -> J.Lit("false"),
        "monetaryParity" -> J.Lit("false"),
        "rewardSeedAdmission" -> J.Lit("false"),
        "protocolSemanticsVerified" -> J.Lit("false"),
        "admissionChecks" -> J.Str("not-performed"),
        "wholeUTxOEntries" -> J.Num("1"),
        "nativeVerificationScope" -> J.Str(
          "epoch-and-utxo-only;protocol-not-submitted-to-verifier"
        ),
        "status" -> J.Str("epoch-utxo-native-structural-checks-only-protocol-opaque")
      )
    )
    Packet(
      previous ++ Map(
        "request.json" -> request,
        "capture.json" -> capture,
        "native-verification.json" -> verifier,
        "receipt.json" -> receipt,
        "original-debug-protocol.cbor" -> protocol
      ),
      anchor
    )
  private def rebind(
      ledger: NativeLedgerSeed.Checked,
      acq: NativeProtocolBootstrap.Acquisition,
      proto: NativeProtocolBootstrap.Prepared
  ) =
    NativeLedgerV2.bind(ledger, acq, proto, ledger.point, ledger.id, acq.id, proto.id)

  test(
    "generated exact v2 packet joins ledger and protocol without admission or runtime capability"
  ) {
    val packet = fixture()
    val joined = get(packet.decoded)
    assertEquals(joined.point, packet.anchor)
    assertEquals(joined.ledger.epochComponents.protocolAcquisitionId, Some(joined.acquisition.id))
    assertEquals(joined.protocol.originals, joined.acquisition.originals)
    assertEquals(joined.protocol.sourcePins, joined.acquisition.sourcePins)
    assertEquals(
      joined.protocol.leadershipBytes,
      joined.ledger.epochComponents.components("leadership")
    )
    assertEquals(joined.protocol.certificateSeed.tip, joined.point)
    assert(joined.protocol.nonces.seed.fields.previousEpoch.nonEmpty)
    assertEquals(joined.crossingBlockers, Set.empty[NativeLedgerSeed.Blocker])
    assert(NativeLedgerV2.checkCrossingGeometry(joined).isRight)
    assert(joined.protocolSourceJoined && joined.crossingGeometryCompatible)
    assert(
      !joined.runtimeImport && !joined.rewardSeedAdmission && !joined.authenticatedSnapshot && !joined.actualAcquisitionVerified
    )
    assert(!joined.fullLedgerValidated && !joined.nativeConformance && !joined.runtimeReady)
  }
  test("v1 and v2 original contracts cannot be relabelled or mixed") {
    val base = bundle()
    val packet = fixture(base)
    val v2Originals = packet.originals.filter((name, _) => NativeEpochComponents.Names(name))
    val v2Pins = packet.pins.filter((name, _) => NativeEpochComponents.Names(name))
    assert(NativeEpochComponents.decode(v2Originals, v2Pins, packet.anchor).isLeft)
    assert(
      NativeEpochComponents
        .decodeV2(base.epoch.originals, base.epoch.pins, packet.anchor, packet.acquisition)
        .isLeft
    )
    val joined = get(packet.decoded)
    assert(rebind(get(base.join), joined.acquisition, joined.protocol).isLeft)
  }
  test("protocol or receipt-only changes affect acquisition epoch and final join identities") {
    val base = bundle()
    val original = get(fixture(base).decoded)
    for packet <- Vector(
        fixture(base, counter = 5),
        fixture(base, epochNonce = 22),
        fixture(base, receiptHelper = 8)
      )
    do
      val changed = get(packet.decoded)
      assertEquals(
        changed.ledger.epochComponents.originals("derived-full-epoch-seed.cbor"),
        original.ledger.epochComponents.originals("derived-full-epoch-seed.cbor")
      )
      assertNotEquals(changed.acquisition.id, original.acquisition.id)
      assertNotEquals(changed.ledger.epochComponents.id, original.ledger.epochComponents.id)
      assertNotEquals(changed.id, original.id)
      assert(rebind(original.ledger, changed.acquisition, changed.protocol).isLeft)
      assert(rebind(original.ledger, original.acquisition, changed.protocol).isLeft)
  }
  test("exact ten input names independent pins and aggregate bounds are mandatory") {
    val packet = fixture()
    packet.originals.keys.foreach { name =>
      assert(
        NativeLedgerV2.decode(packet.originals - name, packet.pins - name, packet.anchor).isLeft,
        name
      )
      assert(
        NativeLedgerV2
          .decode(packet.originals, packet.pins.updated(name, bytes(32, 0)), packet.anchor)
          .isLeft,
        name
      )
    }
    assert(
      NativeLedgerV2
        .decode(
          packet.originals.updated("extra", text("unreviewed")),
          packet.pins.updated("extra", sha(text("unreviewed"))),
          packet.anchor
        )
        .isLeft
    )
    val large = Bytes(Vector.fill(850000)(0.toByte))
    val oversized = packet.originals.keys.map(_ -> large).toMap
    val bounded = NativeLedgerV2.decode(
      oversized,
      oversized.map((name, raw) => name -> sha(raw)),
      packet.anchor
    )
    assert(bounded.left.exists(_.contains("aggregate original bound")))
  }
  test("valid foreign full points UTxO leadership and genesis bundles cannot be spliced") {
    val first = get(fixture().decoded)
    for base <- Vector(
        bundle(pointHash = 9),
        bundle(coin = 101),
        bundle(poolVrf = 19),
        bundle(epochLength = 500)
      )
    do
      val other = get(fixture(base).decoded)
      assert(rebind(first.ledger, other.acquisition, other.protocol).isLeft)
      assert(rebind(first.ledger, first.acquisition, other.protocol).isLeft)
    val otherGenesis = bundle(epochLength = 500).epoch.originals("effective-shelley-genesis.json")
    val otherProtocol =
      get(NativeProtocolBootstrap.bind(first.acquisition, otherGenesis, sha(otherGenesis)))
    assertEquals(otherProtocol.originals, first.protocol.originals)
    assert(rebind(first.ledger, first.acquisition, otherProtocol).isLeft)
  }
  test("a checked v2 join retains 500-slot crossing blocker and fails geometry predicate") {
    val joined = get(fixture(bundle(epochLength = 500)).decoded)
    assertEquals(
      joined.crossingBlockers,
      Set(NativeLedgerSeed.Blocker.IncompatibleCrossingGeometry)
    )
    assertEquals(joined.protocol.nonces.context.window, BigInt(400))
    assert(!joined.crossingGeometryCompatible && !joined.runtimeReady)
    assert(NativeLedgerV2.checkCrossingGeometry(joined).isLeft)
  }
  test("expected ledger acquisition protocol IDs and full point are checked independently") {
    val joined = get(fixture().decoded)
    val zero = bytes(32, 0)
    for (ledgerId, acqId, protoId) <- Vector(
        (zero, joined.acquisition.id, joined.protocol.id),
        (joined.ledger.id, zero, joined.protocol.id),
        (joined.ledger.id, joined.acquisition.id, zero)
      )
    do
      assert(
        NativeLedgerV2
          .bind(
            joined.ledger,
            joined.acquisition,
            joined.protocol,
            joined.point,
            ledgerId,
            acqId,
            protoId
          )
          .isLeft
      )
    assert(
      NativeLedgerV2
        .bind(
          joined.ledger,
          joined.acquisition,
          joined.protocol,
          joined.point.copy(blockNo = 2),
          joined.ledger.id,
          joined.acquisition.id,
          joined.protocol.id
        )
        .isLeft
    )
    assert(NativeLedgerV2.decode(null, null, null).isLeft)
  }
