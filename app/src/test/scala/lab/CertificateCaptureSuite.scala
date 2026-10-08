// SPDX-License-Identifier: Apache-2.0
package lab

import java.nio.file.{Files, Path}
import java.nio.charset.StandardCharsets
import lab.cbor.Bytes

class CertificateCaptureSuite extends munit.FunSuite:
  private def get[A](e: Either[String, A]): A = e.fold(fail(_), identity)
  private def raw(s: String): Bytes = Bytes.fromArray(s.getBytes(StandardCharsets.UTF_8))
  private val pool = "02" * 28
  private val vrf = "03" * 32
  private def tips(slot: Int, block: Int, hash: String): String =
    val tip = s"""{"hash":"$hash","slot":$slot,"block":$block,"epoch":0,"era":"Conway"}"""
    s"[$tip,$tip]"
  private val ledger =
    s"""{"lastEpoch":0,"stakeDistrib":{"unPoolDistr":{"$pool":{"individualPoolStakeVrf":"$vrf"}}}}"""
  private val fixture = Map(
    "transfer-genesis.md" -> raw(
      """{"networkId":"Testnet","networkMagic":1082026,"epochLength":500,"slotsPerKESPeriod":129600,"maxKESEvolutions":60}"""
    ),
    "pre-tips.md" -> raw(tips(10, 3, "01" * 32)),
    "post-tips.md" -> raw(tips(20, 5, "04" * 32)),
    "pre-ledger-state.md" -> raw(ledger),
    "post-ledger-state.md" -> raw(ledger),
    "pre-protocol-state.md" -> raw(s"""{"lastSlot":10,"oCertCounters":{"$pool":0}}"""),
    "post-protocol-state.md" -> raw(s"""{"lastSlot":20,"oCertCounters":{"$pool":0}}"""),
    "pre-parameters.md" -> raw("""{"protocolVersion":{"major":9,"minor":0}}"""),
    "post-parameters.md" -> raw("""{"protocolVersion":{"major":9,"minor":0}}""")
  )
  private def manifest(files: Map[String, Bytes]): Bytes =
    raw(
      "format\tcertificate-context-v1\n" + CertificateCapture.sources.toVector
        .sortBy(_._1)
        .map((key, file) => key + "\t" + ClusterHeaderObservation.sha256(files(file)).hex)
        .mkString("\n") + "\n"
    )
  private def bind(files: Map[String, Bytes]) = CertificateCapture.bind(manifest(files), files)

  test("source binder preserves supplied full counter map, registration and stable endpoints") {
    val b = get(bind(fixture))
    assertEquals(b.seed.tip.slot, BigInt(10))
    assertEquals(b.post.blockNo, BigInt(5))
    assertEquals(b.seed.counters, Map(get(Bytes.fromHex(pool)) -> BigInt(0)))
    assertEquals(b.context.registrations, Map(get(Bytes.fromHex(pool)) -> get(Bytes.fromHex(vrf))))
  }
  test("manifest rejects duplicate, unknown, missing and malformed fields") {
    val good = new String(manifest(fixture).toArray, StandardCharsets.UTF_8)
    Vector(
      good + "format\tcertificate-context-v1\n",
      good + "other\tvalue\n",
      good.linesIterator.filterNot(_.startsWith("preTips")).mkString("\n"),
      good.replace("format\t", "format  ")
    ).foreach { text =>
      assert(CertificateCapture.bind(raw(text), fixture).isLeft)
    }
  }
  test("digest binding rejects a changed source even if JSON semantics are unchanged") {
    val changed = fixture.updated(
      "pre-tips.md",
      raw(new String(fixture("pre-tips.md").toArray, StandardCharsets.UTF_8) + " ")
    )
    assert(CertificateCapture.bind(manifest(fixture), changed).isLeft)
  }
  test("rehashed source still rejects an unstable bracket and mismatched protocol slot") {
    val tip = tips(10, 3, "01" * 32).replaceFirst("\"slot\":10", "\"slot\":11")
    assert(bind(fixture.updated("pre-tips.md", raw(tip))).isLeft)
    assert(
      bind(
        fixture.updated("pre-protocol-state.md", raw("""{"lastSlot":11,"oCertCounters":{}}"""))
      ).isLeft
    )
  }
  test("epoch crossing, changed registration and parameters cannot reuse fixed context") {
    assert(bind(fixture.updated("post-tips.md", raw(tips(520, 5, "04" * 32)))).isLeft)
    assert(
      bind(fixture.updated("post-ledger-state.md", raw(ledger.replace(vrf, "05" * 32)))).isLeft
    )
    assert(
      bind(
        fixture.updated("post-parameters.md", raw("""{"protocolVersion":{"major":10,"minor":0}}"""))
      ).isLeft
    )
  }
  test("counter overflow and absent counter export fail closed") {
    assert(
      bind(
        fixture.updated(
          "post-protocol-state.md",
          raw(s"""{"lastSlot":20,"oCertCounters":{"$pool":18446744073709551616}}""")
        )
      ).isLeft
    )
    assert(bind(fixture.updated("pre-protocol-state.md", raw("""{"lastSlot":10}"""))).isLeft)
  }
  test("nonlocal magic and empty pool distribution are rejected even when freshly bound") {
    val genesis = new String(fixture("transfer-genesis.md").toArray, StandardCharsets.UTF_8)
    assert(
      bind(fixture.updated("transfer-genesis.md", raw(genesis.replace("1082026", "1")))).isLeft
    )
    val empty = raw("""{"lastEpoch":0,"stakeDistrib":{"unPoolDistr":{}}}""")
    assert(
      bind(
        fixture.updated("pre-ledger-state.md", empty).updated("post-ledger-state.md", empty)
      ).isLeft
    )
  }

  sys.env.get("CERTIFICATE_CAPTURE_EVIDENCE").foreach { location =>
    val directory = Path.of(location)
    def originals = get(CertificateCapture.captures(directory.resolve("scala-certificate.md")))
    test(
      "retained certificate range checks both signatures, registration, full counters and every rollback prefix"
    ) {
      val r = get(CertificateCapture.assess(directory, originals))
      assert(r.branch.steps.size >= 2 && r.branch.steps.size <= 8)
      assertEquals(r.branch.state.counters, r.bound.postCounters)
      assertEquals(r.branch.state.tip, r.bound.post)
      assertEquals(
        get(CertificateCapture.assess(directory, originals)).branch.state.id,
        r.branch.state.id
      )
      val json = ReferenceJson.parse(raw(CertificateCapture.render(r)))
      assertEquals(ReferenceJson.field(json, "consensusValidated"), ReferenceJson.Json.Lit("false"))
      assertEquals(
        ReferenceJson.field(json, "referenceSnapshotAtomic"),
        ReferenceJson.Json.Lit("false")
      )
      assertEquals(
        ReferenceJson.field(json, "opCertSignaturesChecked"),
        ReferenceJson.Json.Lit("true")
      )
    }
    test("retained truncation, duplication and reversed order never produce an accepted branch") {
      val rows = originals
      assert(CertificateCapture.assess(directory, rows.dropRight(1)).isLeft)
      assert(CertificateCapture.assess(directory, rows.reverse).isLeft)
      assert(CertificateCapture.assess(directory, rows.updated(1, rows.head)).isLeft)
    }
    test("retained changed original block and envelope are rejected") {
      val rows = originals
      assert(
        CertificateCapture
          .assess(directory, rows.updated(0, rows.head.copy(block = Bytes.empty)))
          .isLeft
      )
      assert(
        CertificateCapture
          .assess(directory, rows.updated(0, rows.head.copy(envelope = Bytes.empty)))
          .isLeft
      )
    }
    test(
      "retained source binding rejects altered bytes and stale digest without modifying evidence"
    ) {
      val files = CertificateCapture.sources.values
        .map(name => name -> Bytes.fromArray(Files.readAllBytes(directory.resolve(name))))
        .toMap
      val boundManifest =
        Bytes.fromArray(Files.readAllBytes(directory.resolve("certificate-context.md")))
      assert(
        CertificateCapture
          .bind(boundManifest, files.updated("pre-protocol-state.md", Bytes.empty))
          .isLeft
      )
      val stale = new String(boundManifest.toArray, StandardCharsets.UTF_8)
        .replaceFirst("genesisSha256\\t[0-9a-f]{64}", "genesisSha256\t" + "00" * 32)
      assert(CertificateCapture.bind(raw(stale), files).isLeft)
    }
  }
