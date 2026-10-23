// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import io.circe.{Decoder, Json}
import io.circe.parser.parse
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.Era

/** Field 18 of a protocol-parameter update, decoded by the rule of the era's decoder version
  * (`CostModels.hs:302-337,426-440`, cardano-ledger `f649f975`). The pre-Conway cases enter through
  * the transaction (Alonzo, decoder version 5, and Babbage, decoder version 7); the Conway cases
  * through the parameter-change proposal (version 9 and above).
  *
  * The pre-Conway ("legacy") cases replay `fixtures/reference/cost-models/legacy-update.json`: 49
  * whole transactions, each measured in the Alonzo and in the Babbage era against the unchanged
  * released ledger decoders at those decoder versions (`scripts/oracle/cost-models`, provenance in
  * the fixture directory). Every row is decoded here through `Tx.fromEnvelope` for both eras and
  * must agree with the retained verdict, with the typed error its `expect` category and `detail`
  * name, and, when accepted, with the cost models the reference kept.
  *
  * The Conway ("open") cases were run against the reference: each Conway transaction body wrapped
  * as `[body, {}, true, null]` in a `Tx ConwayEra` envelope and read by `cardano-cli debug
  * transaction view` of the cardano-reference-11.1.3 image (cardano-cli 11.2.3.0). It accepted a
  * short PlutusV1 list, a 300-entry PlutusV2 list, a short PlutusV3 list, unknown languages 4 and
  * 255 (also an empty one) and the `Int64` bounds. It refused language 256 and -1 ("expected
  * word8"), a value of 2^63 or -2^63-1 in a known and in an unknown language ("expected int"), and
  * a repeated language ("Final number of elements: 1 does not match the total count that was
  * decoded: 2").
  */
class CostModelDecodeSuite extends FunSuite:
  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.toString), identity)

  private def head(major: Int, n: BigInt): String =
    if n < 24 then f"${(major << 5) | n.toInt}%02x"
    else if n < 256 then f"${(major << 5) | 24}%02x${n.toInt}%02x"
    else if n < 65536 then f"${(major << 5) | 25}%02x${n.toInt}%04x"
    else f"${(major << 5) | 27}%02x${n.toLong}%016x"

  /** CBOR integer (the unsigned 64-bit argument is written in full from 65536 on). */
  private def int(v: BigInt): String = if v >= 0 then head(0, v) else head(1, -1 - v)

  private def array(items: Seq[String]): String = head(4, items.size) + items.mkString
  private def model(size: Int): String = array(Seq.fill(size)(int(1)))
  private def models(entries: (BigInt, String)*): String =
    head(5, entries.size) + entries.map((k, m) => int(k) + m).mkString

  private val account = "581d" + "e0" + "22" * 28
  private val anchor = "82" + "6161" + "5820" + "33" * 32

  /** A Conway proposal for a parameter change that sets only the cost models. */
  private def proposal(costModels: String) =
    val raw = hex(
      "84" + "00" + account + "84" + "00" + "f6" + "a1" + "12" + costModels + "f6" + anchor
    )
    CborRead.decode(raw).flatMap(Proposal.decode(_, DecoderVersion.of(Era.Conway))).map(_.action)

  private val int64 = (BigInt(Long.MinValue), BigInt(Long.MaxValue))

  private def valueOutOfRange(v: BigInt) =
    LedgerDecodeError.NumberOutOfRange("cost", v, int64._1, int64._2)

  private def withValue(language: Int, v: BigInt, size: Int): String =
    models(BigInt(language) -> array(int(v) +: Seq.fill(size - 1)(int(1))))

  /** One retained transaction: the original bytes, the typed refusal Scarab must give (`expect`
    * names the case, `detail` its fields) and the reference verdict, which is either accepted with
    * the cost models it kept or its diagnostic.
    */
  private final case class Row(
      name: String,
      era: Era,
      expect: String,
      detail: Json,
      tx: Bytes,
      reference: Json
  ):
    def accepted: Boolean =
      reference.hcursor.get[Boolean]("accepted").fold(e => fail(e.message), identity)

  private lazy val rows: Vector[Row] =
    val parsed =
      parse(Files.readString(Path.of("fixtures/reference/cost-models/legacy-update.json")))
        .fold(e => fail(e.message), identity)
    parsed.asArray.getOrElse(fail("the fixture is not an array")).map { row =>
      val c = row.hcursor
      def field[A: Decoder](name: String): A = c.get[A](name).fold(e => fail(e.message), identity)
      Row(
        field[String]("name"),
        field[String]("era") match
          case "alonzo"  => Era.Alonzo
          case "babbage" => Era.Babbage
          case other     => fail(s"unknown era $other"),
        field[String]("expect"),
        field[Json]("detail"),
        hex(field[String]("tx")),
        field[Json]("reference")
      )
    }

  private def forRows(expect: String)(check: Row => Unit): Unit =
    val selected = rows.filter(_.expect == expect)
    assert(selected.nonEmpty, s"no $expect rows")
    assertEquals(selected.count(_.era == Era.Alonzo), selected.count(_.era == Era.Babbage), expect)
    selected.foreach(check)

  private def detailInt(row: Row, field: String): Int =
    row.detail.hcursor.get[Int](field).fold(e => fail(s"${row.name}: ${e.message}"), identity)

  private def detailBig(row: Row, field: String): BigInt =
    row.detail.hcursor.get[String](field).fold(e => fail(s"${row.name}: ${e.message}"), BigInt(_))

  private def replay(row: Row): Either[LedgerDecodeError, Tx] = Tx.fromEnvelope(row.tx, row.era)

  private def accepted(row: Row): Tx =
    replay(row).fold(e => fail(s"${row.name}: ${e.message}"), identity)

  /** The cost models the reference kept, by language id (PlutusV1 0, PlutusV2 1). */
  private def referenceModels(row: Row): Map[Int, Vector[BigInt]] =
    val proposals = row.reference.hcursor.downField("proposals").values.toVector.flatten
    assertEquals(proposals.size, 1, row.name)
    val models = proposals.head.hcursor.downField("costModels")
    assert(models.downField("unknown").values.forall(_.isEmpty), s"${row.name}: unknown languages")
    models
      .downField("known")
      .values
      .toVector
      .flatten
      .map { m =>
        val language = m.hcursor.get[String]("language").fold(e => fail(e.message), identity) match
          case "PlutusV1" => 0
          case "PlutusV2" => 1
          case other      => fail(s"${row.name}: $other")
        val values = m.hcursor
          .downField("values")
          .values
          .toVector
          .flatten
          .map(_.asNumber.flatMap(_.toBigInt).getOrElse(fail(s"${row.name}: parameter")))
        language -> values
      }
      .toMap

  /** The field 18 bytes of the only proposal of the decoded transaction. */
  private def field18(tx: Tx, name: String): Bytes =
    val fields = tx.body.update.toVector.flatMap(_.proposals.values).map(_.fields)
    assertEquals(fields.size, 1, name)
    fields.head.getOrElse(18, fail(s"$name: no field 18"))

  /** The cost models of the parameters after the update is applied (`ProtocolParams.update`, the
    * reader of state), for the languages the reference kept; the legacy rule reads the same bytes
    * to the same map.
    */
  private def keptModels(tx: Tx, row: Row): Map[Int, Vector[BigInt]] =
    val bytes = field18(tx, row.name)
    val decoded = CborRead
      .decode(bytes)
      .flatMap(CostModelDecode.decode(_, CostModelDecode.Format.Legacy))
      .fold(e => fail(s"${row.name}: ${e.message}"), identity)
    val major = if row.era == Era.Alonzo then 5 else 7
    val applied = ProtocolParams
      .update(TestLedger.params(major), ParamUpdate(Map(18 -> bytes)))
      .fold(e => fail(s"${row.name}: ${e.message}"), identity)
    val languages = referenceModels(row).keySet
    val inState = applied.costModels.view.filterKeys(languages).toMap
    assertEquals(inState, decoded, s"${row.name}: the decoder and the applied parameters differ")
    inState

  // Before decoder version 9 (Alonzo, Babbage): CostModels.hs:306-337, replayed from the reference.

  test(
    "legacy: the corpus has the same cases in both eras, each with a retained reference verdict"
  ) {
    assertEquals(rows.size, 98)
    val named = rows.groupBy(_.era).view.mapValues(_.map(_.name.split('/').last).toSet).toMap
    assertEquals(named(Era.Alonzo), named(Era.Babbage))
    assertEquals(rows.map(_.expect).toSet.size, 6)
    rows.foreach(row => assertEquals(row.accepted, row.expect == "accepted", row.name))
  }

  test(
    "legacy: exactly 166 PlutusV1 and 175 PlutusV2 parameters are accepted and kept as the reference keeps them"
  ) {
    forRows("accepted") { row =>
      val tx = accepted(row)
      assertEquals(keptModels(tx, row), referenceModels(row), row.name)
      assert(
        row.tx.hex.contains(field18(tx, row.name).hex),
        s"${row.name}: field 18 is not the original"
      )
    }
  }

  test("legacy: a repeated language keeps its last entry, as the reference does before version 9") {
    val repeated =
      rows.filter(r => r.name.contains("duplicate-distinct") || r.name.contains("interleaved"))
    assertEquals(repeated.size, 6)
    repeated.foreach { row =>
      val kept = keptModels(accepted(row), row)
      assertEquals(kept, referenceModels(row), row.name)
      val language = if row.name.contains("v2-duplicate") then 1 else 0
      assertEquals(kept(language).head, BigInt(2), s"${row.name}: the first entry survived")
    }
  }

  test("legacy: a PlutusV1 or PlutusV2 list of another length is refused, in both eras") {
    forRows("length") { row =>
      assertEquals(
        replay(row).map(_ => ()),
        Left(
          LedgerDecodeError.CostModelLength(
            detailInt(row, "language"),
            detailInt(row, "expected"),
            detailInt(row, "actual")
          )
        ),
        row.name
      )
    }
  }

  test("legacy: PlutusV3 and PlutusV4 are not read before version 9, whatever follows the key") {
    forRows("unsupportedLanguage") { row =>
      assertEquals(
        replay(row).map(_ => ()),
        Left(LedgerDecodeError.UnsupportedCostModelLanguage(detailInt(row, "language"))),
        row.name
      )
    }
  }

  test("legacy: a number that is not a Language is refused, an unknown language is not kept") {
    forRows("unknownTag") { row =>
      assertEquals(
        replay(row).map(_ => ()),
        Left(LedgerDecodeError.UnknownTag("cost model language", detailBig(row, "tag"))),
        row.name
      )
    }
  }

  test("legacy: a parameter outside Int64 is refused, before the length is counted") {
    forRows("outOfRange") { row =>
      assertEquals(
        replay(row).map(_ => ()),
        Left(valueOutOfRange(detailBig(row, "value"))),
        row.name
      )
    }
  }

  test("legacy: a text key, an entry that is not an array or a bignum parameter is refused") {
    forRows("malformed")(row => assert(replay(row).isLeft, row.name))
  }

  // From decoder version 9 (Conway): CostModels.hs:302-304,426-440.

  test("open: any length is accepted for a known language") {
    for (l, n) <- Seq((0, 0), (0, 3), (1, 1), (1, 300), (2, 5), (2, 252), (3, 1)) do
      assert(proposal(models(BigInt(l) -> model(n))).isRight, s"language $l with $n")
  }

  test("open: an unknown language up to 255 is kept, and an empty map is accepted") {
    assert(proposal(models(BigInt(4) -> model(2), BigInt(255) -> model(0))).isRight)
    assert(proposal(models()).isRight)
  }

  test("open: a language that is not a Word8 is refused") {
    for l <- Seq(BigInt(256), BigInt(65536), BigInt(-1)) do
      assertEquals(
        proposal(models(l -> model(2))).map(_ => ()),
        Left(LedgerDecodeError.NumberOutOfRange("cost model language", l, 0, 255))
      )
  }

  test("open: a parameter outside Int64 is refused, in a known and an unknown language") {
    for
      language <- Seq(0, 1, 2, 77)
      v <- Seq(BigInt(2).pow(63), -BigInt(2).pow(63) - 1)
    do
      assertEquals(
        proposal(withValue(language, v, 3)).map(_ => ()),
        Left(valueOutOfRange(v)),
        s"language $language value $v"
      )
    for v <- Seq(int64._1, int64._2) do assert(proposal(withValue(2, v, 3)).isRight, s"bound $v")
  }

  test("open: a repeated language is refused (decodeMapByKey enforces no duplicates)") {
    assertEquals(
      proposal(models(BigInt(0) -> model(2), BigInt(0) -> model(3))).map(_ => ()),
      Left(LedgerDecodeError.DuplicateKey("cost models"))
    )
  }

  test("open: a cost-model entry that is not an array of integers is refused") {
    assert(proposal(models(BigInt(0) -> array(Seq("6161")))).isLeft)
  }
end CostModelDecodeSuite
