// SPDX-License-Identifier: Apache-2.0
package lab

import java.nio.file.{Files, Path}
import lab.cbor.{Bytes, Cbor, Node, Value}
import lab.kes.Sum6Kes
import lab.opcert.OperationalCertificate
import lab.ledger.ClusterTransfer

class ClusterHeaderObservationSuite extends munit.FunSuite:
  import ClusterHeaderObservation.*
  private def bytes(text: String): Bytes = Bytes.fromArray(text.getBytes("UTF-8"))
  private def node(v: Value): Node = Node(v, Bytes.empty)
  private def arr(n: Node): Vector[Node] = n.value match
    case Value.Arr(xs) => xs
    case _             => fail("array expected")
  private def bs(n: Node): Bytes = n.value match
    case Value.ByteString(b) => b
    case _                   => fail("bytes expected")
  private def uint(n: Node): BigInt = n.value match
    case Value.UInt(n) => n
    case _             => fail("integer expected")
  private def encode(v: Value): Bytes = Cbor.encode(v).fold(fail(_), identity)
  private val raw =
    Bytes.fromArray(Files.readAllBytes(Path.of("fixtures/opcert/evidence/preprod_70070331.cbor")))
  assertEquals(sha256(raw).hex, "a0bd5600bb96ff965fbaa72d9c3d2692e7aecc3c3948a4b816263069782ed902")
  private val header = arr(Cbor.decode(raw).toOption.get)
  private val body = arr(header(0))
  private val pool = Blake2b.hash224.hash(bs(body(3))).hex
  private val vrf = Blake2b.hash256.hash(bs(body(4))).hex
  private val genesisText =
    s"""{"networkId":"Testnet","networkMagic":1082026,"slotsPerKESPeriod":129600,"maxKESEvolutions":62,"extraConfig":{"stakePools":{"data":{"$pool":{"poolId":"$pool","vrf":"$vrf"}}}}}"""
  private val genesis = bytes(genesisText)
  private def context(g: Bytes, h: Bytes = raw): ClusterTransfer.Context =
    ClusterTransfer.Context
      .checked(
        sha256(g),
        sha256(g),
        1082026,
        bs(body(2)),
        Blake2b.hash256.hash(h),
        uint(body(1)) - 1,
        uint(body(1)),
        0,
        0,
        9,
        0,
        1,
        0,
        16384,
        0,
        0
      )
      .fold(fail(_), identity)
  private def bound(g: Bytes = genesis, h: Bytes = raw): BoundContext =
    bindGenesis(g, context(g, h))
  private def envelope(h: Bytes): Bytes = encode(
    Value.Arr(Vector(node(Value.UInt(6)), node(Value.Tag(24, node(Value.ByteString(h))))))
  )
  private def observed(g: Bytes = genesis, h: Bytes = raw): HeaderObservation =
    observeHeader(bound(g, h), envelope(h)).fold(fail(_), identity)
  private def mutate(f: Vector[Node] => Vector[Node]): Bytes =
    encode(Value.Arr(header.updated(0, node(Value.Arr(f(body))))))

  test("positive public signature predicates use timing and registration from hashed JSON") {
    val b = bound()
    assertEquals(b.slotsPerKesPeriod, BigInt(129600))
    assertEquals(b.maxKesEvolutions, 62)
    assertEquals(b.genesisSha256, sha256(genesis))
    assert(observed().cryptographicPredicatesSucceeded)
    assertEquals(observed().cryptography.relativePeriod, 35)
    assert(!observed().cryptography.consensusValidated)
  }
  test("changed timing cannot retain the old digest; rebound timing affects KES") {
    val changed = bytes(genesisText.replace("129600", "130600"))
    intercept[IllegalArgumentException](bindGenesis(changed, context(genesis)))
    assertEquals(
      observed(changed).cryptography.opcert,
      OperationalCertificate.Result.OperationalCertificateSignatureVerified
    )
    assertEquals(observed(changed).cryptography.kes, Sum6Kes.Result.SignatureRejected)
    assert(!observed(changed).cryptographicPredicatesSucceeded)
  }
  test("wrong genesis and registration binding fail closed") {
    intercept[IllegalArgumentException](bindGenesis(bytes(genesisText + " "), context(genesis)))
    val badId = bytes(genesisText.replace(s"\"poolId\":\"$pool\"", s"\"poolId\":\"${"00" * 28}\""))
    intercept[IllegalArgumentException](bound(badId))
    val badVrf = bytes(genesisText.replace(vrf, "00" * 32))
    assert(
      observeHeader(bound(badVrf), envelope(raw)).left.toOption.get.contains("VRF key mismatch")
    )
    val absent = bytes(genesisText.replace(pool, "00" * 28))
    assert(observeHeader(bound(absent), envelope(raw)).left.toOption.get.contains("issuer absent"))
  }
  test("strict JSON parser rejects duplicate keys, fractional timing and invalid lifetime") {
    for changed <- Vector(
        genesisText.replace(
          "\"slotsPerKESPeriod\":129600",
          "\"slotsPerKESPeriod\":129600,\"slotsPerKESPeriod\":1"
        ),
        genesisText.replace("129600", "1.296e5"),
        genesisText.replace("129600", "0"),
        genesisText.replace("\"maxKESEvolutions\":62", "\"maxKESEvolutions\":65"),
        genesisText.replace("1082026", "1082027")
      )
    do intercept[IllegalArgumentException](bound(bytes(changed)))
  }
  test("both signature results are required for predicate success") {
    import OperationalCertificate.Result.{
      OperationalCertificateSignatureVerified as OGood,
      SignatureRejected as OBad
    }
    import Sum6Kes.Result.{SuppliedMessageSignatureVerified as KGood, SignatureRejected as KBad}
    assert(predicatesSucceeded(OGood, KGood))
    assert(!predicatesSucceeded(OBad, KGood))
    assert(!predicatesSucceeded(OGood, KBad))
    assert(!predicatesSucceeded(OBad, KBad))
  }
  test("real signed-field and OpCert mutations remain unsuccessful observations") {
    val changed = mutate(b => b.updated(0, node(Value.UInt(uint(b(0)) + 1))))
    assertEquals(observed(h = changed).cryptography.kes, Sum6Kes.Result.SignatureRejected)
    assert(!observed(h = changed).cryptographicPredicatesSucceeded)
    val badCert = mutate { b =>
      val cert = arr(b(8)); val sig = bs(cert(3))
      val corrupt = Bytes(sig.value.updated(0, (sig.value.head ^ 1).toByte))
      b.updated(8, node(Value.Arr(cert.updated(3, node(Value.ByteString(corrupt))))))
    }
    val result = observed(h = badCert)
    assertEquals(result.cryptography.opcert, OperationalCertificate.Result.SignatureRejected)
    assertEquals(result.cryptography.kes, Sum6Kes.Result.SignatureRejected)
    assert(!result.cryptographicPredicatesSucceeded)
  }
  test("alternate body encoding retains the exact-reference unsupported boundary") {
    val alternate = Bytes(raw.value.take(1) ++ Vector(0x98.toByte, 10.toByte) ++ raw.value.drop(2))
    assert(
      observeHeader(bound(h = alternate), envelope(alternate)).left.toOption.get
        .contains("unsupported serialization")
    )
  }
  test("captured block comparison must pass; successful structure is not signature success") {
    val empty = encode(Value.Arr(Vector.empty))
    val emptyMap = encode(Value.Map(Vector.empty))
    val commitment = Blake2b.hash256.hash(
      Bytes(
        Vector(empty, empty, emptyMap, empty)
          .flatMap(Blake2b.hash256.hash(_).value)
      )
    )
    val changed =
      mutate(b => b.updated(6, node(Value.UInt(4))).updated(7, node(Value.ByteString(commitment))))
    val block = Bytes(
      Vector(0x82.toByte, 7.toByte, 0x85.toByte) ++ changed.value ++
        empty.value ++ empty.value ++ emptyMap.value ++ empty.value
    )
    val capture = Capture(envelope(changed), block)
    val report = inspect(bound(h = changed), Vector(capture)).fold(fail(_), identity)
    assert(!report.cryptographicPredicatesSucceeded)
    assert(
      !report.fullHeaderValidated && !report.consensusValidated && !report.trustedLedgerRegistration
    )
    assert(render(report).contains("\"cryptographicPredicatesSucceeded\":false"))
    assert(inspect(bound(), Vector(capture)).isLeft) // independently bound post point differs
    assert(inspect(bound(h = changed), Vector(capture.copy(block = Bytes.empty))).isLeft)
    assert(inspect(bound(), Vector.empty).isLeft)
  }
  sys.env.get("CLUSTER_HEADER_EVIDENCE").foreach { directory =>
    test("opt-in retained v2 capture binds genesis, range, body and both header predicates") {
      val dir = Path.of(directory)
      val context = load(dir).fold(fail(_), identity)
      val found = captures(dir.resolve("scala-transfer.md")).fold(fail(_), identity)
      val report = inspect(context, found).fold(fail(_), identity)
      val capturedHeader =
        ReferenceCaptureCommand.header(found.head.headerEnvelope).fold(fail(_), identity)
      assertEquals((capturedHeader.major, capturedHeader.minor), (BigInt(11), BigInt(2)))
      assert(report.cryptographicPredicatesSucceeded)
      assertEquals(context.slotsPerKesPeriod, BigInt(129600))
      assertEquals(context.maxKesEvolutions, 60)
      assert(report.headers.forall(_.cryptography.relativePeriod == 0))
      assert(!report.consensusValidated && !report.trustedLedgerRegistration)
      assert(render(report).contains("\"genesisTimingSourceBound\":true"))
    }
  }
