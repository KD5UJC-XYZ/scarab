// SPDX-License-Identifier: Apache-2.0
package lab.chain

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import lab.cbor.Bytes
import lab.chain.CardanoBlockEvidence as E

/** Original independent public observations are separate from synthetic local-profile controls. */
class CardanoBlockEvidenceSuite extends munit.FunSuite:
  private def resource(name: String): Bytes =
    val in = getClass.getResourceAsStream(s"/block-evidence/$name")
    require(in != null, s"missing fixture $name")
    try Bytes.fromArray(in.readAllBytes())
    finally in.close()
  private def text(name: String): String =
    new String(resource(name).toArray, StandardCharsets.US_ASCII)
  private def rows(name: String): Vector[Vector[String]] =
    text(name).linesIterator.map(_.split("\t", -1).toVector).toVector
  private def hex(value: String): Bytes = Bytes.fromHex(value).fold(fail(_), identity)
  private val zero = Bytes(Vector.fill(32)(0.toByte))
  private val corpus = rows("expectations.tsv")
  private val edges = rows("edges.tsv")
  private def context(line: String): E.SuppliedContext =
    val f = line.stripSuffix("\n").split("\t", -1)
    assertEquals(f.length, 11)
    val nonce =
      if f(9) == "-" then None
      else
        Some(E.SuppliedNonce.checked(hex(f(9)), hex(f(10))).fold(e => fail(e.toString), identity))
    E.SuppliedContext
      .checked(
        hex(f(1)),
        hex(f(2)),
        hex(f(3)),
        BigInt(f(4)),
        f(5),
        hex(f(6)),
        BigInt(f(7)),
        f(8).toInt,
        nonce
      )
      .fold(e => fail(e.toString), identity)
  private def contextFor(name: String): E.SuppliedContext = context(text(s"contexts/$name.tsv"))
  private def raw(name: String): Bytes = resource(s"blocks/$name")
  private def outcome(result: Either[E.Failure, E.Receipt]): String = result match
    case Right(_)                                => "checked"
    case Left(E.Failure.Malformed(stage, _))     => s"Malformed:$stage"
    case Left(E.Failure.Unsupported(stage, _))   => s"Unsupported:$stage"
    case Left(E.Failure.Rejected(stage, _))      => s"Rejected:$stage"
    case Left(E.Failure.ResourceLimit(stage, _)) => s"ResourceLimit:$stage"
    case Left(E.Failure.InternalFailure(stage, kind)) =>
      fail(s"unexpected internal error at $stage: $kind")
  private val baseName = "original-08.cbor"
  private def base: Bytes = raw(baseName)
  private def baseContext: E.SuppliedContext = contextFor(baseName)
  private def changedContext(
      c: E.SuppliedContext,
      label: String = "preprod-explorer-unauthed",
      source: Bytes = zero,
      spp: BigInt = BigInt(129600),
      maximum: Int = 62,
      nonce: Option[E.SuppliedNonce] = None
  ): E.SuppliedContext =
    E.SuppliedContext
      .checked(
        c.expectedBlockSha256,
        c.expectedHeaderHash,
        c.expectedParentHash,
        c.expectedSlot,
        label,
        source,
        spp,
        maximum,
        nonce
      )
      .fold(e => fail(e.toString), identity)

  for row <- corpus do
    test(s"original same-block partial evidence: ${row(0)}") {
      val context = contextFor(row(0))
      assertEquals(context.identity.hex, row(20))
      assertEquals(context.encoding.hex, row(22))
      val result = E.inspect(raw(row(0)), context)
      assertEquals(outcome(result), row(1))
      result.foreach { r =>
        assertEquals(r.profileHash.hex, text("profile-sha256.txt").trim)
        assertEquals(r.diskEra, row(2))
        assertEquals(r.originalBlockSha256.hex, row(3))
        assertEquals(r.originalHeaderSha256.hex, row(4))
        assertEquals(r.originalHeaderHash.hex, row(5))
        assertEquals(r.originalParentHash.hex, row(6))
        assertEquals(r.slot, BigInt(row(7)))
        assertEquals(r.blockNumber, BigInt(row(8)))
        assertEquals(r.protocolMajor, BigInt(row(9)))
        assertEquals(r.protocolMinor, BigInt(row(10)))
        assertEquals(r.messageDigest.hex, row(11))
        assertEquals(r.currentKesPeriod, BigInt(row(12)))
        assertEquals(r.relativeKesPeriod, row(13).toInt)
        assertEquals(r.body.actualSize, row(14).toLong)
        assertEquals(r.body.actualHash.hex, row(15))
        assertEquals(r.receiptId.hex, row(21))
        assertEquals(r.messageEvidence, E.MessageEvidence.SourceProfileShortestDefiniteCandidate)
        assertEquals(r.vrf, E.VrfEvidence.NotCheckedMissingNonce)
        assert(
          r.bodyCommitmentChecked && r.operationalCertificateSignatureChecked && r.sum6SignatureChecked
        )
        assert(!r.referenceSerializerParity && !r.authorizedIssuer && !r.registeredVrfKeyBinding)
        assert(!r.opcertCounterAdmissibility && !r.nonceDerivedFromState && !r.leaderEligibility)
        assert(!r.protocolVersionAdmissibility && !r.ledgerApplied && !r.selectedChain)
      }
    }

  for row <- edges do
    test(s"synthetic local-profile edge: ${row(0)}") {
      val supplied = context(row(3).replace('|', '\t') + "\n")
      assertEquals(outcome(E.inspect(hex(row(2)), supplied)), row(1))
    }

  test("corpus has genuine full blocks but no invented full-block VRF positive") {
    assertEquals(corpus.size, 15)
    assertEquals(corpus.count(_(1) == "checked"), 14)
    assertEquals(corpus.count(_(16) == "network-unestablished-assumption"), 2)
    assertEquals(
      corpus.filter(_(16) != "network-unestablished-assumption").count(_(1) == "checked"),
      13
    )
    assertEquals(edges.size, 24)
    assertEquals(corpus.find(_(0) == "original-13.cbor").get(1), "Rejected:KesSignature")
  }

  test("same-header differently encoded disk envelope has a new original owner and receipt") {
    val original = E.inspect(base, baseContext).toOption.get
    val edge = edges.find(_(0) == "outer-wide-new-owner").get
    val other = E.inspect(hex(edge(2)), context(edge(3).replace('|', '\t') + "\n")).toOption.get
    assertEquals(original.originalHeaderHash, other.originalHeaderHash)
    assertEquals(original.messageDigest, other.messageDigest)
    assertNotEquals(original.originalBlockSha256, other.originalBlockSha256)
    assertNotEquals(original.contextDigest, other.contextDigest)
    assertNotEquals(original.receiptId, other.receiptId)
    assertEquals(outcome(E.inspect(hex(edge(2)), baseContext)), "Rejected:ContextBinding")
  }

  test("every foreign original context is rejected without invoking a public-key predicate") {
    var calls = 0
    val observer = new E.StageObserver:
      def before(stage: E.Stage): Unit = { calls += 1; () }
    for row <- corpus if row(0) != baseName do
      assertEquals(
        outcome(E.inspectObserved(base, contextFor(row(0)), observer)),
        "Rejected:ContextBinding"
      )
    assertEquals(calls, 0)
  }

  test("malformed, profile and commitment failures short-circuit before cryptography") {
    var calls = 0
    val observer = new E.StageObserver:
      def before(stage: E.Stage): Unit = { calls += 1; () }
    for
      row <- edges if row(1).startsWith("Unsupported:") || row(1).startsWith("Malformed:") || row(
        1
      ) == "Rejected:BodyCommitment"
    do
      assertEquals(
        outcome(
          E.inspectObserved(hex(row(2)), context(row(3).replace('|', '\t') + "\n"), observer)
        ),
        row(1)
      )
    assertEquals(calls, 0)
  }

  test("successful missing-nonce evidence executes both real signature stages and no VRF") {
    var stages = Vector.empty[E.Stage]
    val observer = new E.StageObserver:
      def before(stage: E.Stage): Unit = { stages :+= stage; () }
    assert(E.inspectObserved(base, baseContext, observer).isRight)
    assertEquals(stages, Vector(E.Stage.OperationalCertificateSignature, E.Stage.KesSignature))
  }

  test("observer cannot replace verification; nonfatal and fatal interruption remain distinct") {
    val nonfatal = new E.StageObserver:
      def before(stage: E.Stage): Unit = throw new IllegalStateException("test interruption")
    E.inspectObserved(base, baseContext, nonfatal) match
      case Left(E.Failure.InternalFailure(E.Stage.OperationalCertificateSignature, _)) => ()
      case result => fail(s"unexpected result $result")
    val error = new LinkageError("test fatal")
    val fatal = new E.StageObserver:
      def before(stage: E.Stage): Unit = throw error
    var propagated = false
    try E.inspectObserved(base, baseContext, fatal)
    catch
      case caught: LinkageError =>
        assert(caught eq error)
        propagated = true
    assert(propagated, "fatal error was converted into an ordinary result")
  }

  test("timing uses checked subtraction, exclusive lifetime and capacity without uint overflow") {
    for relative <- Vector(0, 61, 62, 63, 64) do
      val expected = relative < 62
      assertEquals(
        E.derivePeriod(BigInt(1000 + relative), BigInt(1000), BigInt(1), 62).isRight,
        expected
      )
    assertEquals(
      E.derivePeriod(BigInt(1063), BigInt(1000), BigInt(1), 64),
      Right((BigInt(1063), 63))
    )
    assert(E.derivePeriod(BigInt(1064), BigInt(1000), BigInt(1), 64).isLeft)
    assert(E.derivePeriod(BigInt(999), BigInt(1000), BigInt(1), 62).isLeft)
    val max = (BigInt(1) << 64) - 1
    assertEquals(E.derivePeriod(max, max, BigInt(1), 64), Right((max, 0)))
    assertEquals(E.derivePeriod(max, max - 32, BigInt(1), 64), Right((max, 32)))
    assertEquals(E.derivePeriod(max, BigInt(0), max, 64), Right((BigInt(1), 1)))
    assert(E.derivePeriod(max + 1, BigInt(0), BigInt(1), 62).isLeft)
    assert(E.derivePeriod(BigInt(0), BigInt(0), BigInt(0), 62).isLeft)
    assert(E.derivePeriod(BigInt(0), BigInt(0), BigInt(1), 65).isLeft)
  }

  test(
    "attribution/source/config changes produce different evidence IDs even when predicates stay true"
  ) {
    val original = E.inspect(base, baseContext).toOption.get
    val variants = Vector(
      changedContext(
        baseContext,
        label = "conditional-other-label",
        source = baseContext.timingSourceDigest
      ),
      changedContext(baseContext, source = zero),
      changedContext(baseContext, source = baseContext.timingSourceDigest, spp = BigInt(129601)),
      changedContext(baseContext, source = baseContext.timingSourceDigest, maximum = 63)
    )
    variants.foreach { c =>
      val r = E.inspect(base, c).fold(e => fail(e.toString), identity)
      assertNotEquals(c.identity, baseContext.identity)
      assertNotEquals(r.receiptId, original.receiptId)
    }
    val nonce = E.SuppliedNonce.checked(zero, zero).toOption.get
    val c =
      changedContext(baseContext, source = baseContext.timingSourceDigest, nonce = Some(nonce))
    assertNotEquals(c.identity, baseContext.identity)
    assertEquals(outcome(E.inspect(base, c)), "Rejected:VrfCertificate")
  }

  test("nulls, work bounds and unsupported envelope kinds are explicit") {
    assertEquals(outcome(E.inspect(null, baseContext)), "Malformed:Input")
    assertEquals(outcome(E.inspect(base, null)), "Malformed:Input")
    assertEquals(outcome(E.inspect(Bytes(null), baseContext)), "Malformed:Input")
    assertEquals(
      outcome(E.inspect(Bytes(Vector.fill(E.MaxBlockBytes + 1)(0.toByte)), baseContext)),
      "ResourceLimit:Input"
    )
    assertEquals(outcome(E.inspect(Bytes.empty, baseContext)), "Malformed:Index")
    assertEquals(outcome(E.inspect(hex("f9ffff"), baseContext)), "Unsupported:Index")
    assertEquals(outcome(E.inspect(hex("d8184100"), baseContext)), "Unsupported:Index")
    assertEquals(outcome(E.inspect(hex("8204d8184100"), baseContext)), "Unsupported:Index")
    val header = CardanoBlockIndex.inspect(base).toOption.get.headerBytes
    assertEquals(outcome(E.inspect(header, baseContext)), "Unsupported:Index")
  }

  test(
    "context and nonce constructors reject invalid boundaries without arithmetic or digest work"
  ) {
    val c = baseContext
    def make(
        block: Bytes = c.expectedBlockSha256,
        header: Bytes = c.expectedHeaderHash,
        parent: Bytes = c.expectedParentHash,
        slot: BigInt = c.expectedSlot,
        label: String = c.networkLabel,
        source: Bytes = c.timingSourceDigest,
        spp: BigInt = c.slotsPerKesPeriod,
        maximum: Int = c.maxKesEvolutions,
        nonce: Option[E.SuppliedNonce] = None
    ) =
      E.SuppliedContext.checked(block, header, parent, slot, label, source, spp, maximum, nonce)
    val max = (BigInt(1) << 64) - 1
    Vector(BigInt(-1), BigInt(0), max + 1).foreach(n => assert(make(spp = n).isLeft))
    Vector(-1, 0, 65).foreach(n => assert(make(maximum = n).isLeft))
    Vector(BigInt(-1), max + 1).foreach(n => assert(make(slot = n).isLeft))
    assert(make(spp = null).isLeft)
    assert(make(slot = null).isLeft)
    assert(make(nonce = null).isLeft)
    assert(make(nonce = Some(null)).isLeft)
    Vector(null, "", "é", "a" * 161).foreach(label => assert(make(label = label).isLeft))
    Vector(
      null,
      Bytes(null),
      Bytes.empty,
      Bytes(Vector.fill(31)(0.toByte)),
      Bytes(Vector.fill(33)(0.toByte))
    ).foreach { bytes =>
      assert(make(block = bytes).isLeft)
      assert(make(header = bytes).isLeft)
      assert(make(parent = bytes).isLeft)
      assert(make(source = bytes).isLeft)
      assert(E.SuppliedNonce.checked(bytes, zero).isLeft)
      assert(E.SuppliedNonce.checked(zero, bytes).isLeft)
    }
    assert(make(slot = max, spp = max, maximum = 64).isRight)
  }

  test("effective depth and advertised item limits reject before any cryptographic stage") {
    var calls = 0
    val observer = new E.StageObserver:
      def before(stage: E.Stage): Unit = { calls += 1; () }
    val depth = Bytes(Vector.fill(34)(0x81.toByte) :+ 0.toByte)
    val items = hex("9a000186a1")
    assertEquals(outcome(E.inspectObserved(depth, baseContext, observer)), "ResourceLimit:Index")
    assertEquals(outcome(E.inspectObserved(items, baseContext, observer)), "ResourceLimit:Index")
    assertEquals(calls, 0)
  }

  test("immutable inputs, deterministic replay and concurrent calls") {
    val bytes = base.toArray
    val owned = Bytes.fromArray(bytes)
    val before = E.inspect(owned, baseContext).toOption.get.receiptId
    java.util.Arrays.fill(bytes, 0.toByte)
    assertEquals(E.inspect(owned, baseContext).toOption.get.receiptId, before)
    val leaked = baseContext.encoding.toArray
    java.util.Arrays.fill(leaked, 0.toByte)
    assertEquals(E.inspect(owned, baseContext).toOption.get.receiptId, before)
    import scala.concurrent.{ExecutionContext, Future}
    given ExecutionContext = ExecutionContext.global
    Future
      .sequence(Vector.fill(4)(Future(E.inspect(owned, baseContext).toOption.get.receiptId)))
      .map { ids =>
        assert(ids.forall(_ == before))
      }
  }
