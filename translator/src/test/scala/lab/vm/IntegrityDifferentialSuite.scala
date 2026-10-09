// SPDX-License-Identifier: Apache-2.0
package lab.vm

import lab.cbor.{Bytes, Cbor}
import lab.{Blake2b, TransactionId}
import java.nio.file.{Files, Path}

class IntegrityDifferentialSuite extends munit.FunSuite:
  private val root = Path.of("").toAbsolutePath.normalize()
  private val bundle =
    ujson.read(Files.readString(root.resolve("fixtures/plutus-pv9-integrity/vectors.json")))
  private val rows = bundle("vectors").arr.toVector
  private val model =
    Files.readString(root.resolve("vm/src/main/resources/plutus-pv9/cost-model.json"))
  private def hex(s: String) = Bytes.fromHex(s).fold(fail(_), identity)
  private def input(row: ujson.Value) =
    val p = row("packet")
    (
      hex(p("transactionCborHex").str),
      hex(p("parametersCborHex").str),
      p("utxo").arr.toVector.map(x => hex(x("inputCborHex").str) -> hex(x("outputCborHex").str))
    )
  private def check(row: ujson.Value) =
    val (tx, pp, es) = input(row)
    ProfileIntegrity.check(tx, pp, es, model)
  private def result(name: String) =
    check(rows.find(_("name").str == name).get).fold(fail(_), identity)
  private val baseNames = ujson
    .read(Files.readString(root.resolve("fixtures/plutus-pv9-translator/vectors.json")))("vectors")
    .arr
    .map(_("name").str)
    .toSet
  private val addedNames = Set(
    "commitment-missing",
    "commitment-zero",
    "commitment-flip",
    "redeemer-eight-stale",
    "redeemer-indefinite-stale",
    "outer-witness-map-reversed",
    "body-fee-plus-one",
    "inline-minimum-only",
    "redeemer-eight-repaired",
    "redeemer-indefinite-repaired"
  )
  test("exact reference inventory and match counts, independent of generator expectations") {
    assertEquals(rows.map(_("name").str).toSet, baseNames ++ addedNames)
    assertEquals(rows.size, 28)
    assertEquals(rows.count(_("reference")("matches").bool), 23)
    assertEquals(
      bundle("rejections").arr.map(_("name").str).toSet,
      Set("commitment-width31", "commitment-null")
    )
  }
  rows.foreach { row =>
    test(s"${row("name").str}: independent exact integrity domain/equality evidence") {
      val actual = check(row).fold(fail(_), identity)
      val ref = row("reference"); val e = actual.evidence
      assertEquals(ref("usedLanguages"), ujson.Arr("PlutusV3"))
      assertEquals(ref("languageResolution").str, "mkAlonzoStAnnTx/asatPlutusLanguagesUsed")
      assertEquals(ref("decodedOriginalTransaction"), ujson.Bool(true))
      assertEquals(ref("datumCount").num, 0.0)
      assertEquals(e.redeemers, hex(ref("redeemerBytesHex").str))
      assertEquals(e.datums, hex(ref("datumBytesHex").str))
      assertEquals(e.languageView, hex(ref("languageViewHex").str))
      assertEquals(e.preimage, hex(ref("preimageHex").str))
      assertEquals(e.digest, hex(ref("computedHashHex").str))
      val supplied =
        if ref("suppliedHashHex") == ujson.Null then None else Some(hex(ref("suppliedHashHex").str))
      assertEquals(actual.supplied, supplied)
      assertEquals(actual.matches, ref("matches").bool)
      if actual.matches then assertEquals(ref("referenceCheckDiagnostic").str, "Success ()")
      else assert(ref("referenceCheckDiagnostic").str.contains("PPViewHashesDontMatch"))
      assertEquals(ref("scriptEvaluationPerformed"), ujson.Bool(false))
      assertEquals(ref("fullLedgerValidation"), ujson.Bool(false))
      val (tx, pp, es) = input(row)
      assertEquals(ProfileIntegrity.check(tx, pp, es.reverse, model), Right(actual))
      if supplied.isEmpty then assert(ProfileTranslator.translate(tx, pp, es).isLeft)
    }
  }
  test("reference-observed indefinite map bytes survive stale and repaired checks") {
    val base = result("base").evidence
    val stale = result("redeemer-indefinite-stale")
    val repaired = result("redeemer-indefinite-repaired")
    assert(!stale.matches); assert(repaired.matches)
    assertEquals(stale.evidence, repaired.evidence)
    assertEquals(stale.supplied, Some(base.digest))
    assertNotEquals(stale.evidence.redeemers, base.redeemers)
    assertEquals(stale.evidence.redeemers.value.head, 0xbf.toByte)
    assertEquals(stale.evidence.redeemers.value.last, 0xff.toByte)
    val normalized =
      Cbor.encode(Cbor.decode(stale.evidence.redeemers).toOption.get.value).toOption.get
    assertEquals(normalized, base.redeemers)
    assertNotEquals(
      Blake2b.hash256.hash(Bytes(normalized.value ++ base.languageView.value)),
      stale.evidence.digest
    )
    assertNotEquals(stale.evidence.digest, base.digest)
    assertEquals(
      result("redeemer-eight-stale").evidence,
      result("redeemer-eight-repaired").evidence
    )
    assert(!result("redeemer-eight-stale").matches)
    assert(result("redeemer-eight-repaired").matches)
  }
  test("outer witness, body fee and inline datum changes are excluded from preimage") {
    val base = result("base").evidence
    for name <- Vector("outer-witness-map-reversed", "body-fee-plus-one", "inline-minimum-only") do
      assertEquals(result(name).evidence, base)
    val baseTx = input(rows.find(_("name").str == "base").get)._1
    val changedTx = input(rows.find(_("name").str == "body-fee-plus-one").get)._1
    assertNotEquals(TransactionId.fromEnvelope(baseTx), TransactionId.fromEnvelope(changedTx))
  }
  test("reference malformed commitments also fail Scala grammar, never report mismatch") {
    bundle("rejections").arr.foreach { row =>
      assertEquals(row("reference")("status").str, "rejected")
      assert(check(row).isLeft)
    }
  }
