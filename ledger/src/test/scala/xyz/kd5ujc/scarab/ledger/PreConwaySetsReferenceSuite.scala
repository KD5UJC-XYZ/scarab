// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import io.circe.Json
import io.circe.parser.parse
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.protocol.Era

/** Below decoder version 9 (Shelley to Babbage) the reference reads a set with `decodeSet`: tag 258
  * is not read, and elements that are equal once decoded are one element, whatever their encodings
  * (`Decoder.hs` in cardano-ledger f649f975). `fixtures/reference/pre-conway-sets` holds original
  * encodings with what the unchanged released decoders of each era did with them.
  */
class PreConwaySetsReferenceSuite extends FunSuite:
  private final case class Row(
      name: String,
      era: Era,
      kind: String,
      expect: Option[String],
      rule: Option[String],
      size: Option[Int],
      raw: Bytes,
      accepted: Boolean,
      failure: Option[String]
  )

  private def load(file: String): Vector[Json] =
    parse(Files.readString(Path.of(s"fixtures/reference/pre-conway-sets/$file")))
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
      val verdict = reference
        .getOrElse(name, fail(s"no reference verdict for $name"))
        .hcursor
        .downField("result")
      val era = field[String](c, "era") match
        case "shelley" => Era.Shelley
        case "allegra" => Era.Allegra
        case "mary"    => Era.Mary
        case "alonzo"  => Era.Alonzo
        case "babbage" => Era.Babbage
        case other     => fail(s"unknown era $other")
      Row(
        name,
        era,
        field[String](c, "kind"),
        field[Option[String]](c, "expect"),
        field[Option[String]](c, "rule"),
        field[Option[Int]](c, "size"),
        Bytes.fromHex(field[String](c, "bytes")).fold(e => fail(e.toString), identity),
        verdict.get[Boolean]("accepted").fold(e => fail(e.message), identity),
        verdict.get[Option[String]]("failure").fold(e => fail(e.message), identity)
      )
    }

  /** The error or, for an accepted encoding, how many elements Scarab kept and the retained bytes.
    */
  private def scarab(row: Row): Either[LedgerDecodeError, (Int, Bytes)] = row.kind match
    case "body" => TxBody.decode(row.raw, row.era).map(b => (b.inputs.size, b.raw))
    case "witnesses" =>
      Witnesses.decode(row.raw, row.era).map(w => (w.vkeys.size, w.raw.getOrElse(Bytes.empty)))
    case other => fail(s"unknown kind $other")

  test("the corpus covers every era from Shelley to Babbage, refused and accepted") {
    assertEquals(rows.map(_.era).distinct.size, 5)
    assert(rows.exists(_.accepted) && rows.exists(!_.accepted))
    assertEquals(rows.map(_.name).distinct.size, rows.size)
  }

  test(
    "every case was recorded for the rule it breaks, and the metadata agrees with the reference"
  ) {
    for row <- rows do
      assertEquals(row.accepted, row.expect.isEmpty, row.name)
      row.rule.foreach(r =>
        assert(row.failure.exists(_.contains(r)), s"${row.name}: ${row.failure}")
      )
  }

  for row <- rows do
    test(s"${row.era} ${row.kind}: ${row.name}") {
      (row.expect, scarab(row)) match
        case (None, Right((kept, retained))) =>
          assertEquals(Option(kept), row.size, "elements kept after collapsing by decoded value")
          if row.kind == "body" then
            assertEquals(
              Blake2b.hash256.hash(retained),
              Blake2b.hash256.hash(row.raw),
              "the id hashes the original bytes"
            )
          assertEquals(retained, row.raw, "the original bytes are retained")
        case (None, Left(e))     => fail(s"the reference accepts this encoding: $e")
        case (Some(_), Right(_)) => fail(s"the reference refuses this encoding: ${row.failure}")
        case (Some(error), Left(e)) =>
          assert(e.toString.contains(error), s"expected $error, got $e")
    }
