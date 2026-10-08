// SPDX-License-Identifier: Apache-2.0
package lab

import java.nio.file.{Files, Path}
import java.nio.charset.StandardCharsets
import lab.cbor.Bytes

class BranchInputSuite extends munit.FunSuite:
  private def raw(s: String): Bytes = Bytes.fromArray(s.getBytes(StandardCharsets.UTF_8))
  private def get[A](e: Either[BranchInput.Failure, A]): A = e.fold(f => fail(f.toString), identity)
  private def ok[A](e: Either[String, A]): A = e.fold(fail(_), identity)
  private def manifest(files: Map[String, Bytes]): Bytes = raw(
    "format\tcoherent-branch-input-v1\n" + BranchInput.sources.toVector
      .sortBy(_._1)
      .map((key, name) => key + "\t" + ClusterHeaderObservation.sha256(files(name)).hex)
      .mkString("\n") + "\n"
  )
  private val dummy = BranchInput.sources.values.map(_ -> raw("{}")).toMap
  test("manifest requires exactly pre-only files and rejects duplicate, unknown and missing pins") {
    val good = new String(manifest(dummy).toArray, StandardCharsets.UTF_8)
    for bad <- Vector(
        good + "format\tcoherent-branch-input-v1\n",
        good + "other\t00\n",
        good.linesIterator.filterNot(_.startsWith("preUtxoSha256")).mkString("\n")
      )
    do assert(BranchInput.bind(raw(bad), dummy).isLeft)
    assert(BranchInput.bind(manifest(dummy), dummy + ("post-utxo.md" -> raw("{}"))).isLeft)
    assert(BranchInput.bind(manifest(dummy), dummy - "pre-utxo-cbor.md").isLeft)
  }
  test("source digest failure is typed and includes original whitespace changes") {
    val changed = dummy.updated("pre-utxo.md", raw("{} "))
    BranchInput.bind(manifest(dummy), changed) match
      case Left(BranchInput.Failure.Rejected("source", reason)) =>
        assert(reason.contains("pre-utxo.md"))
      case other => fail(other.toString)
  }
  test("explicit network exclusion is Unsupported, not a scoped success") {
    val changed =
      dummy.updated("transfer-genesis.md", raw("""{"networkId":"Mainnet","networkMagic":1}"""))
    BranchInput.bind(manifest(changed), changed) match
      case Left(BranchInput.Failure.Unsupported(_)) => ()
      case other                                    => fail(other.toString)
  }
  test("source bounds reject before parsing or acceptance") {
    val changed = dummy.updated("pre-protocol-state.md", Bytes.empty)
    assert(BranchInput.bind(manifest(changed), changed).isLeft)
    assert(BranchInput.bind(raw("x" * 8193), dummy).isLeft)
  }
  test("checked input cannot be copied or publicly manufactured") {
    assert(compileErrors("""val x: lab.BranchInput.Checked = null; x.copy()""").nonEmpty)
  }

  sys.env.get("COHERENT_BRANCH_EVIDENCE").foreach { location =>
    val dir = Path.of(location)
    def originals = BranchInput.sources.values
      .map(n => n -> Bytes.fromArray(Files.readAllBytes(dir.resolve(n))))
      .toMap
    def loaded = get(BranchInput.load(dir))
    test("retained pre-only inputs preserve original spans without ledger or consensus claims") {
      val input = loaded
      assertEquals(input.header.slot, BigInt(1281))
      assertEquals(input.certificates.seed.tip.slot, BigInt(1028))
      assertEquals(input.transaction.size, 246)
      assert(input.body.original.size > 0 && input.witnesses.original.size > 0)
      assert(!input.ledgerValidated && !input.consensusValidated && !input.authenticatedSnapshot)
      assert(!input.originals.keys.exists(_.startsWith("post-")))
      val (certificate, eligible) = ok(PraosEligibilityContext.applyPrepared(input.eligibility))
      assertEquals(certificate.state.tip.slot, input.header.slot)
      assertEquals(eligible.headers.size, 1)
      assert(
        eligible.suppliedContextEligibilityVerified && !eligible.stateDerivedConsensus && !eligible.referenceRuntimeParity
      )
    }
    test("retained missing nonce is rejected and rebound wrong nonce produces no combined result") {
      val files = originals
      val text = new String(files("pre-protocol-state.md").toArray, StandardCharsets.UTF_8)
      val missing = files.updated(
        "pre-protocol-state.md",
        raw(text.replace("\"epochNonce\"", "\"unusedNonce\""))
      )
      assert(BranchInput.bind(manifest(missing), missing).isLeft)
      val nonce = ReferenceJson.string(
        ReferenceJson.field(ReferenceJson.parse(files("pre-protocol-state.md")), "epochNonce")
      )
      val changed = files.updated("pre-protocol-state.md", raw(text.replace(nonce, "00" * 32)))
      val bound = get(BranchInput.bind(manifest(changed), changed))
      assert(PraosEligibilityContext.applyPrepared(bound.eligibility).isLeft)
    }
    test("retained UTxO and transaction original digests cannot be silently substituted") {
      val files = originals; val m = manifest(files)
      for name <- Vector("pre-utxo.md", "pre-utxo-cbor.md", "signed-transaction-cbor.md") do
        assert(BranchInput.bind(m, files.updated(name, raw("00"))).isLeft)
    }
    test(
      "malformed validity and auxiliary fields reject; supported exclusions remain Unsupported"
    ) {
      val files = originals
      val tx = loaded.transaction
      for suffix <- Vector("00f6", "f500") do
        val malformed = Bytes(tx.value.dropRight(2) ++ ok(Bytes.fromHex(suffix)).value)
        val changed = files.updated("signed-transaction-cbor.md", raw(malformed.hex))
        BranchInput.bind(manifest(changed), changed) match
          case Left(BranchInput.Failure.Rejected("original", _)) => ()
          case other                                             => fail(other.toString)
    }
    test("mixed protocol source cannot be paired with an existing certificate seed") {
      val in = loaded
      val changed = raw(new String(in.originals("pre-protocol-state.md").toArray, "UTF-8") + " ")
      assert(
        PraosEligibilityContext
          .fromBoundSources(
            in.certificates,
            in.originals("transfer-genesis.md"),
            in.originals("pre-ledger-state.md"),
            changed,
            ClusterHeaderObservation.sha256(changed)
          )
          .isLeft
      )
    }
    test("unsupported parameter minor fails before any scoped publication") {
      val files = originals
      val p = new String(files("pre-parameters.md").toArray, "UTF-8")
      val changed =
        files.updated("pre-parameters.md", raw(p.replaceAll("""("minor"\s*:\s*)0""", "$1 1")))
      BranchInput.bind(manifest(changed), changed) match
        case Left(BranchInput.Failure.Unsupported("ledger PV9.0 required")) => ()
        case other                                                          => fail(other.toString)
    }
    test("retained second original block is an explicit profile exclusion") {
      val files = originals
      val capture = new String(files("scala-transfer.md").toArray, StandardCharsets.UTF_8)
      val row = capture.linesIterator.find(_.contains("transfer-range-block")).get
      val changed = files.updated("scala-transfer.md", raw(capture + "\n" + row + "\n"))
      BranchInput.bind(manifest(changed), changed) match
        case Left(BranchInput.Failure.Unsupported(_)) => ()
        case other                                    => fail(other.toString)
    }
  }
