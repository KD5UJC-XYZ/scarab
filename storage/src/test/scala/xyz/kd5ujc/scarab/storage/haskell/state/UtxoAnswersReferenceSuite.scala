// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import io.circe.{Decoder, parser}
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{TxIn, TxOut}
import xyz.kd5ujc.scarab.ledger.state.ShelleyBasedEra

/** The UTxO answer of the node-to-client queries in every Shelley-based era, against the reference
  * (`fixtures/reference/utxo-answers`, written by `scripts/oracle/utxo-answers`): each entry is
  * read with the node's stored-output reader and written with [[UtxoCodecs.utxoIn]].
  *
  *   - An answer the reference writes is written byte for byte.
  *   - An output the era's reference decoder refuses has a part only a later era's `TxOut` holds,
  *     and the era's codec refuses to write it.
  */
class UtxoAnswersReferenceSuite extends FunSuite:
  private final case class Row(
      name: String,
      era: String,
      entries: Vector[(String, String)],
      answer: Option[String],
      refused: Option[String]
  )

  private given Decoder[Row] =
    Decoder.forProduct5("name", "era", "entries", "answer", "refused")(Row.apply)

  private val rows: Vector[Row] =
    Files
      .readString(Path.of("fixtures/reference/utxo-answers/answers.jsonl"))
      .linesIterator
      .filter(_.nonEmpty)
      .map(l => parser.decode[Row](l).fold(e => fail(e.getMessage), identity))
      .toVector

  private val eras: Map[String, ShelleyBasedEra[?]] = Map(
    "shelley" -> ShelleyBasedEra.SShelley,
    "allegra" -> ShelleyBasedEra.SAllegra,
    "mary" -> ShelleyBasedEra.SMary,
    "alonzo" -> ShelleyBasedEra.SAlonzo,
    "babbage" -> ShelleyBasedEra.SBabbage,
    "conway" -> ShelleyBasedEra.SConway
  )

  private def bytes(hex: String): Bytes = Bytes.fromHex(hex).fold(e => fail(e.message), identity)

  private def entries(row: Row): Map[TxIn, TxOut] =
    row.entries.map { (i, o) =>
      val input =
        UtxoCodecs.txIn.decodeBytes(bytes(i)).fold(e => fail(s"${row.name}: $e"), identity)
      val output =
        UtxoCodecs.txOut.decodeBytes(bytes(o)).fold(e => fail(s"${row.name}: $e"), identity)
      input -> output
    }.toMap

  private def era(row: Row): ShelleyBasedEra[?] =
    eras.getOrElse(row.era, fail(s"${row.name}: unknown era ${row.era}"))

  test("the corpus has answers and refusals in every Shelley-based era") {
    assertEquals(rows.size, 100)
    assertEquals(rows.map(_.era).distinct.toSet, eras.keySet)
    eras.keys.foreach { e =>
      assert(rows.exists(r => r.era == e && r.answer.isDefined), e)
    }
    // Babbage's form holds every output, so only the earlier eras refuse
    assertEquals(
      rows.filter(_.refused.isDefined).map(_.era).toSet,
      Set("shelley", "allegra", "mary", "alonzo")
    )
  }

  test("every answer the reference writes is written byte for byte") {
    rows.filter(_.answer.isDefined).foreach { row =>
      assertEquals(
        UtxoCodecs.utxoIn(era(row)).write(entries(row)).map(_.hex),
        Right(row.answer.getOrElse("")),
        row.name
      )
    }
  }

  test("an output the era's reference decoder refuses is not written in that era") {
    rows.filter(_.refused.isDefined).foreach { row =>
      assert(UtxoCodecs.utxoIn(era(row)).write(entries(row)).isLeft, row.name)
    }
  }

  test("the Conway codec is the Conway era's") {
    rows.filter(r => r.era == "conway" && r.answer.isDefined).foreach { row =>
      assertEquals(
        UtxoCodecs.utxo.write(entries(row)).map(_.hex),
        Right(row.answer.getOrElse("")),
        row.name
      )
    }
  }
