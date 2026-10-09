// SPDX-License-Identifier: Apache-2.0
package lab

import lab.cbor.Bytes
import lab.header.{
  PraosCertificateState as Certificate,
  PraosEligibility,
  PraosNonceEvolution as Nonces
}
import lab.vrf.PraosVrfCertificate as Vrf
import ReferenceJson.Json as J
import scala.util.control.NonFatal

/** Private source-bound header bootstrap candidate. Checked transport is not native seed admission.
  * No legacy SequenceInput files are constructed and no runtime/CLI capability is returned.
  */
private[lab] object NativeProtocolBootstrap:
  val ContractSourceCommit = "15ae0462930cb45cf4a07446059ea7823d5e0802"
  val InputNames: Set[String] = Set(
    "request.json",
    "capture.json",
    "native-verification.json",
    "receipt.json",
    "original-debug-epoch.cbor",
    "original-whole-utxo.cbor",
    "derived-full-epoch-seed.cbor",
    "original-debug-protocol.cbor"
  )
  val MaxOriginalBytes = 524288
  private val Max = (BigInt(1) << 64) - 1
  final class Acquisition private[NativeProtocolBootstrap] (
      val id: Bytes,
      val anchor: Certificate.Point,
      val networkMagic: BigInt,
      val originals: Map[String, Bytes],
      val sourcePins: Map[String, Bytes]
  ):
    val actualAcquisitionVerified = false
    val authenticatedSnapshot = false
    val runtimeImport = false
    val rewardSeedAdmission = false

  final class Prepared private[NativeProtocolBootstrap] (
      val id: Bytes,
      val anchor: Certificate.Point,
      val certificates: Certificate.Context,
      val certificateSeed: Certificate.State,
      val nonces: PraosNonceSnapshot.Prepared,
      val eligibility: PraosEligibility.Context,
      val originals: Map[String, Bytes],
      val sourcePins: Map[String, Bytes],
      val genesisDigest: Bytes,
      val originalGenesis: Bytes,
      val leadershipBytes: Bytes
  ):
    val actualAcquisitionVerified = false
    val authenticatedSnapshot = false
    val runtimeImport = false
    val rewardSeedAdmission = false
    val monetaryParity = false
    val fullEpochSemanticsChecked = false
    val nativeConformance = false

  private def checked[A](body: => A): Either[String, A] =
    try Right(body)
    catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.getClass.getName))
  private def get[A](e: Either[String, A]): A =
    e.fold(s => throw new IllegalArgumentException(s), value => value)
  private def bytes(raw: Bytes, max: Int): Unit =
    require(
      raw != null && raw.value != null && raw.size > 0 && raw.size <= max,
      "bounded original required"
    )
  private def sha(raw: Bytes): Bytes = ClusterHeaderObservation.sha256(raw)
  private def hash(j: J): Bytes =
    val s = ReferenceJson.string(j)
    require(s.matches("[0-9a-f]{64}"), "canonical SHA256/hash required")
    get(Bytes.fromHex(s))
  private def uint(j: J, max: BigInt = Max): BigInt =
    val n = ReferenceJson.uint(j); require(n <= max, "bounded unsigned integer required"); n
  private def obj(j: J): Map[String, J] = j match
    case J.Obj(v) => v
    case _        => throw new IllegalArgumentException("object required")
  private def exact(j: J, names: Set[String]): Map[String, J] =
    val fs = obj(j); require(fs.keySet == names, "exact versioned field set required"); fs
  private def text(j: J, expected: String): Unit =
    require(ReferenceJson.string(j) == expected, "contract literal mismatch: " + expected)
  private def flag(j: J, expected: Boolean): Unit =
    require(j == J.Lit(expected.toString), "contract Boolean mismatch")
  private def point(j: J, expected: Certificate.Point): Unit =
    val p = exact(j, Set("slot", "hash"))
    require(
      uint(p("slot")) == expected.slot && hash(p("hash")) == expected.hash,
      "full acquired point mismatch"
    )
  private def hexOriginal(j: J, original: Bytes): Unit =
    val s = ReferenceJson.string(j)
    require(s.length == original.size * 2 && s == original.hex, "original capture bytes mismatch")
  private def identity(domain: String, fields: Vector[(String, Bytes)]): Bytes =
    // Field names are fixed program literals and every value is a fixed-width digest.
    val recipe =
      fields.sortBy(_._1).map((k, v) => k + "=" + v.hex).mkString(domain + "\n", "\n", "\n")
    sha(Bytes.fromArray(recipe.getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
  private def anchorDigest(p: Certificate.Point): Bytes =
    sha(
      Bytes.fromArray(
        s"native-protocol-point-v1\n${p.hash.hex}\n${p.slot}\n${p.blockNo}\n"
          .getBytes(java.nio.charset.StandardCharsets.US_ASCII)
      )
    )

  /** Every packet file has an independently supplied expected digest. A self-consistent rewritten
    * packet with newly authorized pins is a different source identity, not authenticated state.
    */
  def checkAcquisition(
      originals: Map[String, Bytes],
      expectedPins: Map[String, Bytes],
      expectedAnchor: Certificate.Point
  ): Either[String, Acquisition] = checked {
    require(
      originals != null && expectedPins != null && originals.keySet == InputNames && expectedPins.keySet == InputNames,
      "exact v2 original and external pin sets required"
    )
    require(
      expectedAnchor != null && expectedAnchor.hash != null && expectedAnchor.hash.size == 32 &&
        expectedAnchor.slot >= 0 && expectedAnchor.slot <= Max && expectedAnchor.blockNo >= 0 && expectedAnchor.blockNo <= Max,
      "complete non-origin anchor required"
    )
    originals.foreach { (name, raw) =>
      val limit =
        if name.endsWith(".cbor") then MaxOriginalBytes
        else if name == "request.json" then 16384
        else 4194304
      bytes(raw, limit)
      val pin = expectedPins(name)
      require(
        pin != null && pin.value != null && pin.size == 32 && sha(raw) == pin,
        "external original pin mismatch: " + name
      )
    }
    require(originals.values.map(_.size.toLong).sum <= 8388608L, "aggregate packet byte bound")
    def json(name: String) = ReferenceJson.parse(originals(name))
    val request = exact(
      json("request.json"),
      Set(
        "schema",
        "socket",
        "point",
        "networkMagic",
        "byronEpochSlots",
        "ntcVersion",
        "producerBinarySHA256",
        "producerImage"
      )
    )
    require(uint(request("schema")) == 2, "v2 request required")
    point(request("point"), expectedAnchor)
    val socket = ReferenceJson.string(request("socket"))
    require(
      socket.startsWith("/") && !socket
        .contains(0.toChar) && socket.getBytes("UTF-8").length <= 100,
      "bounded Unix socket identity"
    )
    val magic = uint(request("networkMagic"), (BigInt(1) << 32) - 1);
    require(magic > 0, "network magic")
    require(uint(request("byronEpochSlots")) > 0, "Byron codec epoch slots")
    val version = uint(request("ntcVersion"));
    require(version >= 16 && version <= 23, "supported exact wire version")
    hash(request("producerBinarySHA256"))
    val image = ReferenceJson.string(request("producerImage"));
    require(image.startsWith("sha256:"), "producer image digest")
    hash(J.Str(image.stripPrefix("sha256:")))
    val capture = exact(
      json("capture.json"),
      Set(
        "schema",
        "kind",
        "requestedPoint",
        "acquiredPoint",
        "finalPoint",
        "blockNo",
        "finalBlockNo",
        "ntcVersion",
        "acquireCount",
        "reacquireCount",
        "release",
        "queryEncoding",
        "epochHex",
        "utxoHex",
        "protocolHex"
      )
    )
    require(uint(capture("schema")) == 2, "v2 capture required")
    text(capture("kind"), "single-acquire-native-protocol-payloads")
    Vector("requestedPoint", "acquiredPoint", "finalPoint").foreach(k =>
      point(capture(k), expectedAnchor)
    )
    require(
      uint(capture("blockNo")) == expectedAnchor.blockNo && uint(
        capture("finalBlockNo")
      ) == expectedAnchor.blockNo,
      "anchor block number mismatch"
    )
    require(
      uint(capture("ntcVersion")) == version && uint(capture("acquireCount")) == 1 && uint(
        capture("reacquireCount")
      ) == 0,
      "version/acquisition lifecycle"
    )
    text(capture("release"), "sent-no-ack");
    text(capture("queryEncoding"), "GetCBOR-server-maxBound")
    hexOriginal(capture("epochHex"), originals("original-debug-epoch.cbor"))
    hexOriginal(capture("utxoHex"), originals("original-whole-utxo.cbor"))
    hexOriginal(capture("protocolHex"), originals("original-debug-protocol.cbor"))
    val verified = exact(
      json("native-verification.json"),
      Set(
        "schema",
        "kind",
        "epochInputSHA256",
        "utxoInputSHA256",
        "epochFullConsumption",
        "utxoFullConsumption",
        "epochRoundTripEqual",
        "utxoRoundTripEqual",
        "derivedRoundTripEqual",
        "onlyUtxoReplaced",
        "derivedSeedHex",
        "runtimeImport",
        "monetaryParity",
        "wholeUTxOEntries",
        "rewardSeedAdmission",
        "admissionChecks"
      )
    )
    require(uint(verified("schema")) == 1, "existing ledger verifier schema")
    text(verified("kind"), "derived-native-full-epoch-seed")
    require(
      hash(verified("epochInputSHA256")) == expectedPins("original-debug-epoch.cbor") && hash(
        verified("utxoInputSHA256")
      ) == expectedPins("original-whole-utxo.cbor"),
      "ledger verifier original binding"
    )
    Vector(
      "epochFullConsumption",
      "utxoFullConsumption",
      "epochRoundTripEqual",
      "utxoRoundTripEqual",
      "derivedRoundTripEqual",
      "onlyUtxoReplaced"
    ).foreach(k => flag(verified(k), true))
    Vector("runtimeImport", "monetaryParity", "rewardSeedAdmission").foreach(k =>
      flag(verified(k), false)
    )
    text(verified("admissionChecks"), "not-performed")
    val entries = uint(verified("wholeUTxOEntries"), 4096)
    hexOriginal(verified("derivedSeedHex"), originals("derived-full-epoch-seed.cbor"))
    val receipt = exact(
      json("receipt.json"),
      Set(
        "schema",
        "kind",
        "queryHelperSHA256",
        "verifierSHA256",
        "producerIdentitySource",
        "requestSHA256",
        "captureSHA256",
        "epochSHA256",
        "utxoSHA256",
        "protocolSHA256",
        "derivedSeedSHA256",
        "runtimeImport",
        "monetaryParity",
        "rewardSeedAdmission",
        "protocolSemanticsVerified",
        "admissionChecks",
        "wholeUTxOEntries",
        "nativeVerificationScope",
        "status"
      )
    )
    require(uint(receipt("schema")) == 2, "v2 receipt required")
    text(receipt("kind"), "reviewable-single-acquire-native-protocol-seed")
    hash(receipt("queryHelperSHA256")); hash(receipt("verifierSHA256"))
    text(receipt("producerIdentitySource"), "caller-supplied-review-pin-not-peer-attestation")
    Map(
      "requestSHA256" -> "request.json",
      "captureSHA256" -> "capture.json",
      "epochSHA256" -> "original-debug-epoch.cbor",
      "utxoSHA256" -> "original-whole-utxo.cbor",
      "protocolSHA256" -> "original-debug-protocol.cbor",
      "derivedSeedSHA256" -> "derived-full-epoch-seed.cbor"
    ).foreach { (key, name) =>
      require(hash(receipt(key)) == expectedPins(name), "receipt original binding: " + key)
    }
    Vector("runtimeImport", "monetaryParity", "rewardSeedAdmission", "protocolSemanticsVerified")
      .foreach(k => flag(receipt(k), false))
    text(receipt("admissionChecks"), "not-performed")
    require(uint(receipt("wholeUTxOEntries"), 4096) == entries, "verifier/receipt entry count")
    text(
      receipt("nativeVerificationScope"),
      "epoch-and-utxo-only;protocol-not-submitted-to-verifier"
    )
    text(receipt("status"), "epoch-utxo-native-structural-checks-only-protocol-opaque")
    val id = identity(
      "native-protocol-acquisition-v2",
      expectedPins.toVector :+ ("anchor" -> anchorDigest(expectedAnchor))
    )
    new Acquisition(id, expectedAnchor, magic, originals, expectedPins)
  }

  /** Decode only the header bootstrap fields from original protocol/epoch bytes. Other epoch
    * components, reward admission and native execution are deliberately not established here.
    */
  def bind(
      acquisition: Acquisition,
      effectiveGenesis: Bytes,
      genesisPin: Bytes
  ): Either[String, Prepared] = checked {
    require(acquisition != null, "checked v2 acquisition required")
    bytes(effectiveGenesis, 4194304)
    require(
      genesisPin != null && genesisPin.value != null && genesisPin.size == 32 && sha(
        effectiveGenesis
      ) == genesisPin,
      "effective genesis external pin"
    )
    val genesis = ReferenceJson.parse(effectiveGenesis)
    import ReferenceJson.field
    text(field(genesis, "networkId"), "Testnet")
    require(
      uint(field(genesis, "networkMagic"), (BigInt(1) << 32) - 1) == acquisition.networkMagic,
      "genesis/acquisition network mismatch"
    )
    val length = uint(field(genesis, "epochLength")); require(length > 0, "epoch length")
    val kes = uint(field(genesis, "slotsPerKESPeriod")); require(kes > 0, "KES period")
    val lifetime = uint(field(genesis, "maxKESEvolutions"), 64);
    require(lifetime > 0, "KES lifetime")
    val protocol =
      get(NativePraosProtocol.decode(acquisition.originals("original-debug-protocol.cbor")))
    val leadership =
      get(NativeLeadershipSnapshot.decodeEpoch(acquisition.originals("original-debug-epoch.cbor")))
    require(protocol.snapshot.lastSlot == acquisition.anchor.slot, "protocol anchor slot mismatch")
    require(leadership.epoch == acquisition.anchor.slot / length, "epoch/anchor geometry mismatch")
    val bound = identity(
      "native-protocol-genesis-binding-v1",
      Vector("acquisition" -> acquisition.id, "genesis" -> genesisPin)
    )
    val registration = identity(
      "native-protocol-leadership-binding-v1",
      Vector(
        "binding" -> bound,
        "epoch" -> leadership.digest,
        "leadership" -> sha(leadership.leadershipBytes)
      )
    )
    val certificates = get(
      Certificate.Context.checked(
        genesisPin,
        registration,
        leadership.epoch * length,
        (leadership.epoch + 1) * length - 1,
        kes,
        lifetime.toInt,
        leadership.registrations
      )
    )
    val seed =
      get(Certificate.seed(certificates, acquisition.anchor, protocol.counters, protocol.digest))
    val nonces = get(
      PraosNonceSnapshot.bindDecoded(
        certificates,
        seed,
        effectiveGenesis,
        protocol.snapshot,
        protocol.counters,
        protocol.digest
      )
    )
    val epochNonce = nonces.seed.fields.epoch match
      case Nonces.Nonce.Neutral => Vrf.NeutralNonce
      case Nonces.Nonce.Hash(b) => get(Vrf.Hash32.fromBytes(b).left.map(_.toString))
    val active = PraosEligibilityContext.coefficient(field(genesis, "activeSlotsCoeff"))
    val eligibility = get(
      PraosEligibility.Context.checked(
        certificates,
        seed,
        leadership.epoch,
        length,
        epochNonce,
        active,
        leadership.stakes,
        protocol.digest
      )
    )
    val id = identity(
      "native-protocol-bootstrap-v1",
      Vector(
        "binding" -> bound,
        "certificate" -> seed.id,
        "nonce" -> nonces.seed.id,
        "eligibility" -> eligibility.id
      )
    )
    new Prepared(
      id,
      acquisition.anchor,
      certificates,
      seed,
      nonces,
      eligibility,
      acquisition.originals,
      acquisition.sourcePins,
      genesisPin,
      effectiveGenesis,
      leadership.leadershipBytes
    )
  }
