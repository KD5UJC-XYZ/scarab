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

  test("native diagnostic context preserves exact ten sources and current ledger parameters") {
    val packet = fixture(bundle(slot = 1))
    val joined = get(packet.decoded)
    val context = get(SequenceInput.fromNativeDiagnostic(joined, joined.id))
    val epoch = joined.ledger.epochComponents
    assertEquals(context.originals, packet.originals)
    assertEquals(context.sourcePins, packet.pins)
    assertEquals(context.originals.keySet, NativeLedgerV2.InputNames)
    assertNotEquals(context.id, joined.id)
    assertNotEquals(context.id, epoch.id)
    assertEquals(context.wholeUTxO, packet.originals("original-whole-utxo.cbor"))
    assertEquals(context.stakeSourceId, epoch.stake.sourceId)
    assertEquals(context.ledger.fees, epoch.pots.fees)
    assertEquals(context.ledger.slot, packet.anchor.slot)
    assertEquals(context.ledger.environment.parameterDigest, epoch.parameters.current.sha256)
    assertEquals(context.ledger.environment.genesisDigest, epoch.parameters.genesisSHA256)
    assertEquals(context.certificates, joined.protocol.certificates)
    assertEquals(context.certificateSeed, joined.protocol.certificateSeed)
    assertEquals(context.certificateSeed.tip, packet.anchor)
    assertEquals(context.nonces, joined.protocol.nonces)
    assertEquals(context.eligibility, joined.protocol.eligibility)
    assertEquals(context.protocolAttributionDigest, packet.pins("original-debug-protocol.cbor"))
    assert(context.diagnosticOnly && context.suppliedCheckpoint)
    assert(
      !context.validatedTip && !context.authenticatedSnapshot && !context.referenceSnapshotAtomic
    )
  }
  test("native diagnostic context rejects a wrong join identity or incompatible geometry") {
    val joined = get(fixture(bundle(slot = 1)).decoded)
    assert(SequenceInput.fromNativeDiagnostic(joined, bytes(32, 0)).isLeft)
    val short = get(fixture(bundle(slot = 1, epochLength = 500)).decoded)
    assert(SequenceInput.fromNativeDiagnostic(short, short.id).isLeft)
    assert(SequenceInput.fromNativeDiagnostic(null, joined.id).isLeft)
  }
  test("ordinary runtime constructors refuse a native diagnostic context") {
    import cats.effect.IO
    import cats.effect.unsafe.implicits.global
    val joined = get(fixture(bundle(slot = 1)).decoded)
    val context = get(SequenceInput.fromNativeDiagnostic(joined, joined.id))
    val epoch = joined.ledger.epochComponents
    val legacy = get(
      CoherentSequence.syntheticRewardProfile(
        epoch.parameters.previous.rewards,
        epoch.parameters.globals,
        epoch.parameters.randomnessWindow,
        Some(false),
        Some(BigInt(0)),
        Some(false),
        Some(false),
        Some(BigInt(0)),
        Some(false),
        Some(false)
      )
    )
    (for
      ordinary <- CoherentSequence.create[IO](context)
      stake <- CoherentSequence.createWithStake[IO](context, epoch.stake)
      rewards <- CoherentSequence.createWithSyntheticRewards[IO](
        context,
        epoch.stake,
        legacy,
        epoch.pots,
        Map.empty,
        Map.empty,
        epoch.reward.componentSHA256
      )
    yield
      assert(ordinary.left.exists(_.toString.contains("diagnostic")))
      assert(stake.left.exists(_.toString.contains("diagnostic")))
      assert(rewards.left.exists(_.toString.contains("diagnostic")))
    ).unsafeToFuture()
  }
  test("internal boundary diagnostic initializes checked components but refuses persistence") {
    import cats.effect.IO
    import cats.effect.unsafe.implicits.global
    val joined = get(fixture(bundle(slot = 1)).decoded)
    val context = get(SequenceInput.fromNativeDiagnostic(joined, joined.id))
    val ledger = joined.ledger
    val epoch = ledger.epochComponents
    val profile = get(
      CoherentSequence.syntheticBoundaryProfile(
        ledger.parameterRoles,
        ledger.pools,
        ledger.globals,
        epoch.parameters.randomnessWindow
      )
    )
    (for
      created <- CoherentSequence.createWithSyntheticBoundary[IO](
        context,
        epoch.stake,
        profile,
        ledger.governanceInput,
        epoch.nonMyopic,
        epoch.pots,
        Map.empty,
        Map.empty,
        epoch.reward.componentSHA256
      )
      runtime = get(created)
      snapshot <- runtime.snapshot
      checkpoint <- runtime.exportLocalCheckpoint(bytes(32, 8), bytes(32, 9), 0L)
      recovery <- runtime.exportSyntheticRecovery(bytes(32, 10))
    yield
      val state = snapshot.state
      assertEquals(state.contextId, context.id)
      assertEquals(state.certificates.state.tip, joined.point)
      assertEquals(state.ledger.id, context.ledger.id)
      assertEquals(state.nonces.id, context.nonces.seed.id)
      assert(state.stake.nonEmpty && state.syntheticRewards.nonEmpty)
      val boundary = state.syntheticBoundary.getOrElse(fail("checked boundary component missing"))
      assertEquals(boundary.roles.id, ledger.parameterRoles.id)
      assertEquals(boundary.globals.id, ledger.globals.id)
      assertEquals(boundary.poolPayloads, ledger.pools)
      assertEquals(boundary.nonMyopic.id, epoch.nonMyopic.id)
      assertEquals(boundary.governanceInput, ledger.governanceInput)
      assert(!boundary.boundaryApplied && boundary.syntheticOnly)
      assert(!boundary.nativeConformance && !boundary.durableImport)
      assert(!state.fullLedgerValidated && !state.consensusValidated)
      assert(checkpoint.left.exists(_.contains("no checkpoint codec")))
      assert(recovery.left.exists(_.contains("no recovery model")))
    ).unsafeToFuture()
  }

  test("diagnostic durable creation refuses before acquiring a store or invoking recorder") {
    import cats.effect.IO
    import cats.effect.unsafe.implicits.global
    import java.nio.file.Files
    import scala.concurrent.duration.*
    val joined = get(fixture(bundle(slot = 1)).decoded)
    val context = get(SequenceInput.fromNativeDiagnostic(joined, joined.id))
    (for
      parent <- IO.blocking(Files.createTempDirectory("native-diagnostic-no-store-"))
      _ <- (for
        result <- CoherentSequence
          .durableCreate[IO](
            parent.resolve("store"),
            context,
            8,
            1.second,
            _ => IO.raiseError(new AssertionError("recorder must not run"))
          )
          .use(_ => IO.raiseError(new AssertionError("store must not be acquired")))
          .attempt
        _ <- IO {
          assert(result.left.exists(_.getMessage.contains("diagnostic context")))
          assert(!Files.exists(parent.resolve("store")))
        }
      yield ()).guarantee(IO.blocking(Files.deleteIfExists(parent)).void)
    yield ()).unsafeToFuture()
  }
