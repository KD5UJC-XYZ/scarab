// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import io.circe.Json
import io.circe.parser.parse
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.Era

/** Transaction bodies and witness sets are decoded at the decoder version of their era
  * (`eraProtVerLow`: Conway 9, Babbage 7). `fixtures/reference/conway-decoding` holds original
  * encodings, each of which breaks one rule of the reference decoders (or keeps a rule it
  * tolerates), with what the unchanged released decoders of cardano-ledger f649f975 did with it.
  * Every verdict must be the same here, and a refusal must be for the rule the case breaks.
  */
class ConwayDecodingReferenceSuite extends FunSuite:
  private final case class Row(
      name: String,
      era: Era,
      kind: String,
      expect: Option[String],
      rule: Option[String],
      raw: Bytes,
      accepted: Boolean,
      failure: Option[String]
  )

  private def load(file: String): Vector[Json] =
    parse(Files.readString(Path.of(s"fixtures/reference/conway-decoding/$file")))
      .fold(e => fail(e.message), identity)
      .asArray
      .getOrElse(fail(s"$file is not an array"))
      .toVector

  private def field[A: io.circe.Decoder](json: Json, name: String): A =
    json.hcursor.get[A](name).fold(e => fail(e.message), identity)

  private val rows: Vector[Row] =
    val reference = load("reference.json").map(r => field[String](r, "name") -> r).toMap
    load("cases.json").map { c =>
      val name = field[String](c, "name")
      val result = reference.getOrElse(name, fail(s"no reference verdict for $name"))
      val verdict = result.hcursor.downField("result")
      Row(
        name,
        field[String](c, "era") match
          case "conway"  => Era.Conway
          case "babbage" => Era.Babbage
          case other     => fail(s"unknown era $other"),
        field[String](c, "kind"),
        field[Option[String]](c, "expect"),
        field[Option[String]](c, "rule"),
        Bytes.fromHex(field[String](c, "bytes")).fold(e => fail(e.toString), identity),
        verdict.get[Boolean]("accepted").fold(e => fail(e.message), identity),
        verdict.get[Option[String]]("failure").fold(e => fail(e.message), identity)
      )
    }

  private def scarab(row: Row): Either[LedgerDecodeError, Unit] = row.kind match
    case "body"      => TxBody.decode(row.raw, row.era).map(_ => ())
    case "witnesses" => Witnesses.decode(row.raw, row.era).map(_ => ())
    case "transaction" =>
      Tx.fromEnvelope(row.raw, row.era).map(_ => ())
    case other => fail(s"unknown kind $other")

  test("the corpus has refused and accepted cases for bodies, witness sets and transactions") {
    assertEquals(rows.size, load("cases.json").size)
    for kind <- Seq("body", "witnesses", "transaction") do
      assert(rows.exists(r => r.kind == kind && r.accepted), s"no accepted $kind case")
      assert(rows.exists(r => r.kind == kind && !r.accepted), s"no refused $kind case")
    assertEquals(rows.map(_.name).distinct.size, rows.size)
  }

  test(
    "every case agrees with its recorded reference verdict, and was recorded for the rule it breaks"
  ) {
    for row <- rows do
      assertEquals(
        row.accepted,
        row.expect.isEmpty,
        s"${row.name}: case metadata and reference verdict"
      )
      row.rule.foreach(rule =>
        assert(
          row.failure.exists(_.contains(rule)),
          s"${row.name}: reference failure ${row.failure}"
        )
      )
  }

  for row <- rows do
    test(s"${row.era} ${row.kind}: ${row.name}") {
      val result = scarab(row)
      row.expect match
        case None =>
          assertEquals(result, Right(()), s"the reference accepts this encoding")
        case Some(error) =>
          val message = result.fold(
            _.toString,
            _ => fail(s"the reference refuses this encoding: ${row.failure}")
          )
          assert(message.contains(error), s"expected $error, got $message")
    }

  test(
    "the decoder version comes from the era: a repeated input is a refusal in Conway and one input in Babbage"
  ) {
    val repeated = rows.find(_.name == "inputs: duplicate input").getOrElse(fail("case"))
    assert(TxBody.decode(repeated.raw, Era.Conway).isLeft)
    val babbage = TxBody.decode(repeated.raw, Era.Babbage).fold(e => fail(e.toString), identity)
    assertEquals(babbage.inputs.size, 1)
  }

  test("an accepted body keeps its original bytes and the elements the reference keeps") {
    val twoInputs = rows.find(_.name == "inputs: tag 258 is accepted").getOrElse(fail("case"))
    val body = TxBody.decode(twoInputs.raw, Era.Conway).fold(e => fail(e.toString), identity)
    assertEquals(body.raw, twoInputs.raw)
    assertEquals(body.inputs.size, 2)
    val witnesses =
      rows.find(_.name == "vkey witnesses: duplicate is tolerated").getOrElse(fail("case"))
    assertEquals(
      Witnesses.decode(witnesses.raw, Era.Conway).map(_.vkeys.size),
      Right(1)
    )
    val sameBytes = rows
      .find(_.name == "Plutus scripts: the same bytes under V1 and V2 are two scripts")
      .getOrElse(fail("case"))
    assertEquals(
      Witnesses.decode(sameBytes.raw, Era.Conway).map(_.scripts.size),
      Right(2)
    )
  }
