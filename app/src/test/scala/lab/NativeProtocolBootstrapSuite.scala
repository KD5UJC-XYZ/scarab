// SPDX-License-Identifier: Apache-2.0
package lab

import lab.cbor.{Bytes, Cbor, Node, Value as V}
import lab.header.PraosCertificateState as Certificate
import ReferenceJson.Json as J
import SyntheticRewardProjection.encode

/** Hand-built CBOR and JSON transport fixtures only; never actual acquisition or native parity. */
class NativeProtocolBootstrapSuite extends munit.FunSuite:
  private def get[A](e: Either[String, A]): A = e.fold(e => fail(e), identity)
  private def raw(s: String): Bytes = Bytes.fromArray(s.getBytes("UTF-8"))
  private def sha(b: Bytes): Bytes = ClusterHeaderObservation.sha256(b)
  private def s(v: String): J = J.Str(v)
  private def jn(v: BigInt): J = J.Num(v.toString)
  private val yes = J.Lit("true")
  private val no = J.Lit("false")
  private def obj(xs: (String, J)*): J.Obj = J.Obj(xs.toMap)
  private def pin(b: Bytes): J = s(sha(b).hex)
  private def n(v: V): Node = Node(v, Bytes.empty)
  private def a(vs: V*): V = V.Arr(vs.toVector.map(n))
  private def cbor(v: V): Bytes = get(Cbor.encode(v))
  private def fill(length: Int, byte: Int): Bytes = Bytes(Vector.fill(length)(byte.toByte))
  private val blockHash = fill(32, 1)
  private val pool = fill(28, 2)
  private val vrf = fill(32, 3)
  private val anchor = Certificate.Point(blockHash, 36, 1)
  private def nonce(value: Int): V = a(V.UInt(1), V.ByteString(fill(32, value)))
  private def protocolFields(counter: BigInt = 4): Vector[V] = Vector(
    a(V.UInt(1), V.UInt(36)),
    V.Map(Vector(n(V.ByteString(pool)) -> n(V.UInt(counter)))),
    nonce(4),
    nonce(5),
    nonce(6),
    nonce(7),
    nonce(8),
    nonce(9)
  )
  private def protocol(fields: Vector[V] = protocolFields()): Bytes = cbor(
    a(V.UInt(0), V.Arr(fields.map(n)))
  )
  private def distribution(
      key: Bytes = pool,
      vrfKey: Bytes = vrf,
      numerator: BigInt = 1,
      denominator: BigInt = 1
  ): V =
    val fraction = V.Tag(30, n(a(V.UInt(numerator), V.UInt(denominator))))
    val entry = a(fraction, V.UInt(100), V.ByteString(vrfKey))
    a(V.Map(Vector(n(V.ByteString(key)) -> n(entry))), V.UInt(100))
  private def seed(leadership: V = distribution()): Bytes =
    cbor(a(V.UInt(0), V.Map(Vector.empty), V.Map(Vector.empty), V.Null, V.Null, leadership, V.Null))
  // Minimal synthetic effective-genesis fields consumed by this diagnostic bootstrap.
  private val genesis = raw(
    """{"networkMagic":1082026,"networkId":"Testnet","activeSlotsCoeff":0.05,
      |"securityParam":5,"epochLength":1000,"slotsPerKESPeriod":100,"maxKESEvolutions":62}""".stripMargin
  )

  private def packet(
      proto: Bytes = protocol(),
      epoch: Bytes = seed(),
      hash: Bytes = blockHash,
      slot: BigInt = 36,
      block: BigInt = 1
  ): Map[String, Bytes] =
    val utxo = cbor(V.Map(Vector.empty))
    val point = obj("slot" -> jn(slot), "hash" -> s(hash.hex))
    val request = encode(
      obj(
        "schema" -> jn(2),
        "socket" -> s("/synthetic/node.sock"),
        "point" -> point,
        "networkMagic" -> jn(1082026),
        "byronEpochSlots" -> jn(50),
        "ntcVersion" -> jn(16),
        "producerBinarySHA256" -> s("02" * 32),
        "producerImage" -> s("sha256:" + "03" * 32)
      )
    )
    val capture = encode(
      obj(
        "schema" -> jn(2),
        "kind" -> s("single-acquire-native-protocol-payloads"),
        "requestedPoint" -> point,
        "acquiredPoint" -> point,
        "finalPoint" -> point,
        "blockNo" -> jn(block),
        "finalBlockNo" -> jn(block),
        "ntcVersion" -> jn(16),
        "acquireCount" -> jn(1),
        "reacquireCount" -> jn(0),
        "release" -> s("sent-no-ack"),
        "queryEncoding" -> s("GetCBOR-server-maxBound"),
        "epochHex" -> s(epoch.hex),
        "utxoHex" -> s(utxo.hex),
        "protocolHex" -> s(proto.hex)
      )
    )
    val verifier = encode(
      obj(
        "schema" -> jn(1),
        "kind" -> s("derived-native-full-epoch-seed"),
        "epochInputSHA256" -> pin(epoch),
        "utxoInputSHA256" -> pin(utxo),
        "epochFullConsumption" -> yes,
        "utxoFullConsumption" -> yes,
        "epochRoundTripEqual" -> yes,
        "utxoRoundTripEqual" -> yes,
        "derivedRoundTripEqual" -> yes,
        "onlyUtxoReplaced" -> yes,
        "derivedSeedHex" -> s(epoch.hex),
        "wholeUTxOEntries" -> jn(0),
        "runtimeImport" -> no,
        "monetaryParity" -> no,
        "rewardSeedAdmission" -> no,
        "admissionChecks" -> s("not-performed")
      )
    )
    val receipt = encode(
      obj(
        "schema" -> jn(2),
        "kind" -> s("reviewable-single-acquire-native-protocol-seed"),
        "queryHelperSHA256" -> s("04" * 32),
        "verifierSHA256" -> s("05" * 32),
        "producerIdentitySource" -> s("caller-supplied-review-pin-not-peer-attestation"),
        "requestSHA256" -> pin(request),
        "captureSHA256" -> pin(capture),
        "epochSHA256" -> pin(epoch),
        "utxoSHA256" -> pin(utxo),
        "protocolSHA256" -> pin(proto),
        "derivedSeedSHA256" -> pin(epoch),
        "runtimeImport" -> no,
        "monetaryParity" -> no,
        "rewardSeedAdmission" -> no,
        "protocolSemanticsVerified" -> no,
        "admissionChecks" -> s("not-performed"),
        "wholeUTxOEntries" -> jn(0),
        "nativeVerificationScope" -> s("epoch-and-utxo-only;protocol-not-submitted-to-verifier"),
        "status" -> s("epoch-utxo-native-structural-checks-only-protocol-opaque")
      )
    )
    Map(
      "request.json" -> request,
      "capture.json" -> capture,
      "native-verification.json" -> verifier,
      "receipt.json" -> receipt,
      "original-debug-epoch.cbor" -> epoch,
      "original-whole-utxo.cbor" -> utxo,
      "derived-full-epoch-seed.cbor" -> epoch,
      "original-debug-protocol.cbor" -> proto
    )
  private def pins(inputs: Map[String, Bytes]): Map[String, Bytes] =
    inputs.map((k, v) => k -> sha(v))
  private def acquire(inputs: Map[String, Bytes] = packet(), at: Certificate.Point = anchor) =
    NativeProtocolBootstrap.checkAcquisition(inputs, pins(inputs), at)
  private def bind(inputs: Map[String, Bytes] = packet(), at: Certificate.Point = anchor) =
    acquire(inputs, at).flatMap(NativeProtocolBootstrap.bind(_, genesis, sha(genesis)))
  private def mutate(
      inputs: Map[String, Bytes],
      file: String,
      key: String,
      value: Option[J]
  ): Map[String, Bytes] =
    val fs = ReferenceJson.parse(inputs(file)).asInstanceOf[J.Obj].fields
    inputs.updated(file, encode(J.Obj(value.fold(fs - key)(v => fs.updated(key, v)))))

  test(
    "synthetic native-shaped bootstrap retains exact originals and explicit previous epoch nonce"
  ) {
    val inputs = packet()
    val prepared = get(bind(inputs))
    assertEquals(prepared.anchor, anchor)
    assertEquals(prepared.originals, inputs)
    assertEquals(prepared.sourcePins, pins(inputs))
    assertEquals(prepared.genesisDigest, sha(genesis))
    assertEquals(prepared.certificateSeed.counters, Map(pool -> BigInt(4)))
    assert(prepared.nonces.seed.fields.previousEpoch.nonEmpty)
    assert(
      !prepared.runtimeImport && !prepared.rewardSeedAdmission && !prepared.authenticatedSnapshot && !prepared.actualAcquisitionVerified
    )
  }
  test("all external original pins and exact eight input names are mandatory") {
    val inputs = packet()
    inputs.foreach { (name, bytes) =>
      assert(
        NativeProtocolBootstrap
          .checkAcquisition(
            inputs.updated(name, Bytes(bytes.value :+ 0.toByte)),
            pins(inputs),
            anchor
          )
          .isLeft,
        name
      )
      assert(acquire(inputs - name).isLeft, name)
      assert(
        NativeProtocolBootstrap.checkAcquisition(inputs, pins(inputs) - name, anchor).isLeft,
        name
      )
    }
    assert(acquire(inputs.updated("extra", raw("unreviewed"))).isLeft)
    assert(
      NativeProtocolBootstrap
        .checkAcquisition(inputs, pins(inputs).updated("extra", sha(raw("extra"))), anchor)
        .isLeft
    )
  }
  test("full anchor binds block hash slot and block number even for coherently repinned packets") {
    assert(acquire(packet(hash = fill(32, 9))).isLeft)
    assert(acquire(packet(slot = 37)).isLeft)
    assert(acquire(packet(block = 2)).isLeft)
    val first = get(bind())
    val otherPoint = anchor.copy(hash = fill(32, 9))
    val other = get(bind(packet(hash = otherPoint.hash), otherPoint))
    assertNotEquals(first.id, other.id)
    assertEquals(first.certificateSeed.counters, other.certificateSeed.counters)
  }
  test("acquisition bracket lifecycle versions and verifier attribution cannot be substituted") {
    val inputs = packet()
    for (key, value) <- Vector(
        "schema" -> jn(1),
        "finalBlockNo" -> jn(2),
        "acquireCount" -> jn(2),
        "reacquireCount" -> jn(1),
        "ntcVersion" -> jn(15),
        "protocolHex" -> s("00")
      )
    do assert(acquire(mutate(inputs, "capture.json", key, Some(value))).isLeft)
    for (key, value) <- Vector(
        "protocolSemanticsVerified" -> yes,
        "nativeVerificationScope" -> s("all-protocol-state"),
        "queryHelperSHA256" -> s("invalid"),
        "runtimeImport" -> jn(0)
      )
    do assert(acquire(mutate(inputs, "receipt.json", key, Some(value))).isLeft)
    for key <- Vector("epochFullConsumption", "derivedRoundTripEqual", "onlyUtxoReplaced") do
      assert(acquire(mutate(inputs, "native-verification.json", key, Some(no))).isLeft)
  }
  test("missing extra duplicate JSON fields and substituted original CBOR fail closed") {
    val inputs = packet()
    for name <- Vector("request.json", "capture.json", "native-verification.json", "receipt.json")
    do
      assert(acquire(mutate(inputs, name, "schema", None)).isLeft)
      assert(acquire(mutate(inputs, name, "unreviewed", Some(no))).isLeft)
      val text = new String(inputs(name).toArray, "UTF-8")
      assert(acquire(inputs.updated(name, raw("{\"schema\":2," + text.drop(1)))).isLeft)
    for name <- Vector(
        "original-debug-epoch.cbor",
        "original-whole-utxo.cbor",
        "derived-full-epoch-seed.cbor",
        "original-debug-protocol.cbor"
      )
    do assert(acquire(inputs.updated(name, cbor(V.UInt(42)))).isLeft)
  }
  test("coherent protocol counter changes are explicit new bindings not evidence of forgery") {
    val firstInputs = packet()
    val nextInputs = packet(proto = protocol(protocolFields(5)))
    assert(NativeProtocolBootstrap.checkAcquisition(nextInputs, pins(firstInputs), anchor).isLeft)
    val first = get(bind(firstInputs)); val next = get(bind(nextInputs))
    assertNotEquals(first.id, next.id)
    assertEquals(next.certificateSeed.counters, Map(pool -> BigInt(5)))
  }
  test("coherently repinned nonce and leadership VRF changes produce new bootstrap identities") {
    val inputs = packet()
    val original = get(bind(inputs))
    val nonceInputs = packet(proto = protocol(protocolFields().updated(4, nonce(12))))
    val vrfInputs = packet(epoch = seed(distribution(vrfKey = fill(32, 13))))
    for changed <- Vector(nonceInputs, vrfInputs) do
      assert(NativeProtocolBootstrap.checkAcquisition(changed, pins(inputs), anchor).isLeft)
      val rebound = get(bind(changed))
      assertNotEquals(original.id, rebound.id)
      assertEquals(original.certificateSeed.counters, rebound.certificateSeed.counters)
      assert(
        !rebound.runtimeImport && !rebound.authenticatedSnapshot && !rebound.actualAcquisitionVerified
      )
    val changedNonce = get(bind(nonceInputs))
    assertNotEquals(original.nonces.seed.fields.epoch, changedNonce.nonces.seed.fields.epoch)
  }
  test("valid repinned 500-slot genesis remains diagnostic geometry with a distinct identity") {
    val acquired = get(acquire())
    val original = get(bind())
    val changed = raw(
      new String(genesis.toArray, "UTF-8").replace("\"epochLength\":1000", "\"epochLength\":500")
    )
    val rebound = get(NativeProtocolBootstrap.bind(acquired, changed, sha(changed)))
    assertNotEquals(original.id, rebound.id)
    assertEquals(original.anchor, rebound.anchor)
    assert(!rebound.runtimeImport && !rebound.rewardSeedAdmission)
  }
  test(
    "native protocol version widths missing previous nonce and malformed nonce constructors reject"
  ) {
    val fields = protocolFields()
    def rejects(bytes: Bytes): Unit = assert(bind(packet(proto = bytes)).isLeft)
    rejects(cbor(a(V.UInt(1), V.Arr(fields.map(n)))))
    rejects(protocol(fields.patch(5, Vector.empty, 1)))
    for index <- 2 to 7 do
      rejects(protocol(fields.updated(index, a(V.UInt(1), V.ByteString(fill(31, 4))))))
      rejects(protocol(fields.updated(index, a(V.UInt(2)))))
    rejects(protocol(fields.updated(0, a(V.UInt(1), V.UInt(37)))))
    val wrongKey = V.Map(Vector(n(V.ByteString(fill(27, 2))) -> n(V.UInt(4))))
    rejects(protocol(fields.updated(1, wrongKey)))
    val oversized = V.Tag(2, n(V.ByteString(Bytes(Vector(1.toByte) ++ Vector.fill(8)(0.toByte)))))
    for invalid <- Vector(V.NInt(-1), oversized) do
      val counterMap = V.Map(Vector(n(V.ByteString(pool)) -> n(invalid)))
      rejects(protocol(fields.updated(1, counterMap)))
  }
  test("trailing CBOR and duplicate counter keys reject with newly consistent outer pins") {
    val original = protocol()
    assert(bind(packet(proto = Bytes(original.value :+ 0.toByte))).isLeft)
    val duplicate = V.Map(Vector.fill(2)(n(V.ByteString(pool)) -> n(V.UInt(4))))
    assert(bind(packet(proto = protocol(protocolFields().updated(1, duplicate)))).isLeft)
    val epoch = seed()
    assert(bind(packet(epoch = Bytes(epoch.value :+ 0.toByte))).isLeft)
  }
  test("leadership shape hash lengths and stake coherence are required") {
    assert(bind(packet(epoch = seed(V.Null))).isLeft)
    assert(bind(packet(epoch = seed(distribution(vrfKey = fill(31, 3))))).isLeft)
    assert(bind(packet(epoch = seed(distribution(denominator = 2)))).isLeft)
  }
  test("genesis pin network timing and protocol epoch bindings are mandatory") {
    val acquired = get(acquire())
    assert(NativeProtocolBootstrap.bind(acquired, genesis, sha(raw("different"))).isLeft)
    for (old, replacement) <- Vector(
        "1082026" -> "1082027",
        "\"epochLength\":1000" -> "\"epochLength\":10",
        "\"securityParam\":5" -> "\"securityParam\":0"
      )
    do
      val changed = raw(new String(genesis.toArray, "UTF-8").replace(old, replacement))
      assert(NativeProtocolBootstrap.bind(acquired, changed, sha(changed)).isLeft)
  }
  test("bounded original and JSON inputs reject before bootstrap") {
    val inputs = packet()
    assert(
      acquire(
        inputs.updated("original-debug-protocol.cbor", Bytes(Vector.fill(524289)(0.toByte)))
      ).isLeft
    )
    assert(acquire(inputs.updated("capture.json", Bytes(Vector.fill(4194305)(32.toByte)))).isLeft)
  }
