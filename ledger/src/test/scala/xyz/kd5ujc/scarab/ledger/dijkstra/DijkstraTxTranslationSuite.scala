// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.dijkstra

import io.circe.{Json, parser}
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor}
import xyz.kd5ujc.scarab.ledger.{Block, Tx}
import xyz.kd5ujc.scarab.protocol.Era

/** Conway transactions carried into Dijkstra ([[DijkstraTxTranslation]]), compared with the Leios
  * testnet build on the cases of `fixtures/reference/dijkstra-crossing` (cardano-ledger 9df9f98c,
  * ouroboros-consensus 2416493b; provenance in its README): whether each way accepts, the part a
  * refusal names, and of an accepted transaction each part's bytes, the flag, the id and the
  * mempool form.
  */
class DijkstraTxTranslationSuite extends munit.FunSuite:
  private val fixture = Path.of("fixtures", "reference", "dijkstra-crossing", "cases.jsonl")

  private val cases: Vector[Json] =
    Files
      .readAllLines(fixture)
      .asScala
      .toVector
      .filter(_.nonEmpty)
      .map(l => parser.parse(l).fold(e => fail(e.message), identity))

  private def field(j: Json, path: String*): Json =
    path.foldLeft(j)((at, k) => at.hcursor.downField(k).focus.getOrElse(fail(s"no $k in $at")))
  private def string(j: Json, path: String*): String =
    field(j, path*).asString.getOrElse(fail(s"${path.mkString(".")} is not a string"))
  private def bool(j: Json, path: String*): Boolean =
    field(j, path*).asBoolean.getOrElse(fail(s"${path.mkString(".")} is not a boolean"))
  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.toString), identity)

  private def parts(raw: Bytes): Block.Transaction =
    Cbor
      .decode(raw, Cbor.Limits.Unbounded)
      .left
      .map(_.toString)
      .flatMap(Tx.envelopeParts(_, Era.Conway).left.map(_.message))
      .fold(e => fail(s"not a Conway transaction: $e"), identity)

  /** The first part the reference refuses alone, in the order the translation reads them. */
  private def refusedPart(reference: Json): Option[DijkstraTxPart] =
    val partsOf = field(reference, "parts")
    Vector(
      "body" -> DijkstraTxPart.TxBody,
      "witnesses" -> DijkstraTxPart.TxWits,
      "auxiliaryData" -> DijkstraTxPart.TxAuxData
    ).collectFirst {
      case (key, part)
          if partsOf.hcursor.downField(key).focus.exists(p => !p.isNull) &&
            !bool(partsOf, key, "accepted") =>
        part
    }

  private def same(name: String, reference: Json, tx: DijkstraTx): Unit =
    assertEquals(tx.body.raw.hex, string(reference, "body"), s"$name: body")
    assertEquals(tx.witnesses.raw.hex, string(reference, "witnesses"), s"$name: witnesses")
    assertEquals(
      tx.auxiliary.map(_.raw.hex),
      field(reference, "auxiliaryData").asString,
      s"$name: auxiliary data"
    )
    assertEquals(tx.isValid, bool(reference, "isValid"), s"$name: flag")
    assertEquals(tx.id.hex, string(reference, "id"), s"$name: id")
    assertEquals(tx.raw.hex, string(reference, "mempool"), s"$name: mempool form")

  test("the fixture holds the cases the oracle recorded, Conway accepting every one") {
    assertEquals(cases.size, 188)
    cases.foreach(c => assert(bool(c, "reference", "conway", "accepted"), string(c, "name")))
  }

  test("Conway writes each transaction as the reference does") {
    cases.foreach { c =>
      assertEquals(
        Tx.envelope(parts(hex(string(c, "tx")))).hex,
        string(c, "reference", "conway", "toCbor"),
        string(c, "name")
      )
    }
  }

  test("TranslateEra DijkstraEra (Tx TopTx) as the reference, case by case") {
    cases.foreach { c =>
      val name = string(c, "name")
      val reference = field(c, "reference")
      val expected = field(reference, "translateEra")
      DijkstraTxTranslation.translateEra(parts(hex(string(c, "tx")))) match
        case Right(tx) =>
          assert(bool(expected, "accepted"), s"$name: carried, the reference refuses it")
          same(name, expected, tx)
        case Left(DijkstraTxTranslationError.Undecodable(part, cause)) =>
          assert(!bool(expected, "accepted"), s"$name: refused ($part: ${cause.message})")
          assertEquals(Some(part), refusedPart(reference), name)
        case Left(other) => fail(s"$name: $other")
    }
  }

  test("the hard-fork mempool's rematch as the reference, case by case") {
    cases.foreach { c =>
      val name = string(c, "name")
      val expected = field(c, "reference", "rematch")
      DijkstraTxTranslation.rematch(parts(hex(string(c, "tx")))) match
        case Right(tx) =>
          assert(bool(expected, "accepted"), s"$name: carried, the reference refuses it")
          same(name, expected, tx)
        case Left(DijkstraTxTranslationError.WrongEra(cause)) =>
          assert(!bool(expected, "accepted"), s"$name: refused (${cause.message})")
        case Left(other) => fail(s"$name: $other")
    }
  }

  test("a transaction marked invalid is carried by TranslateEra and refused by the rematch") {
    val c = cases.find(string(_, "name") == "flag/false").getOrElse(fail("no flag/false case"))
    val conway = parts(hex(string(c, "tx")))
    assert(!conway.isValid)
    assertEquals(DijkstraTxTranslation.translateEra(conway).map(_.isValid), Right(false))
    assert(DijkstraTxTranslation.rematch(conway).isLeft)
  }
