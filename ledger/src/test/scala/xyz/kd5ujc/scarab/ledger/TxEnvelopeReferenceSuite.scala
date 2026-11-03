// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.syntax.all.*
import io.circe.{Decoder, parser}
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor}
import xyz.kd5ujc.scarab.protocol.Era

/** A whole transaction as a client or a peer submits it: before Alonzo the `RecD` record
  * `ShelleyTx` (`eras/shelley/impl/src/Cardano/Ledger/Shelley/Tx.hs:184-192`, cardano-ledger
  * f649f975), a definite list of three or a detached record, never a non-empty indefinite list
  * (#688); from Alonzo `decodeRecordNamed "AlonzoTx"`
  * (`eras/alonzo/impl/src/Cardano/Ledger/Alonzo/Tx.hs:532-553`), whose validity flag is read only
  * when the third item is a boolean, a transaction without it being valid (#699).
  *
  * `fixtures/reference/tx-envelope/cases.jsonl` holds the forms of each era with the verdict of the
  * unchanged released decoders and, for an accepted one, the transaction id they hash and the bytes
  * the node relays for it (its `encCBOR`). [[Tx.fromEnvelope]] must give the same verdict and id,
  * and from Alonzo [[Tx.envelope]] of the parts it read must be the relayed bytes.
  */
class TxEnvelopeReferenceSuite extends FunSuite:
  import TxEnvelopeReferenceSuite.*

  private val rows: Vector[Row] = load

  private def fromAlonzo(row: Row): Boolean = row.era.ordinal >= Era.Alonzo.ordinal

  test("the corpus covers every era, refused and accepted, with and without the flag") {
    assertEquals(rows.map(_.era).toSet, Era.values.toSet - Era.Byron)
    assertEquals(rows.map(_.name).distinct.size, rows.size)
    for e <- rows.map(_.era).distinct do
      assert(rows.exists(r => r.era == e && r.accepted), s"no accepted $e case")
      assert(rows.exists(r => r.era == e && !r.accepted), s"no refused $e case")
    // #688: a non-empty indefinite envelope before Alonzo is refused, and taken from it
    assert(rows.exists(r => !fromAlonzo(r) && !r.accepted && r.name.endsWith(" indefinite")))
    assert(rows.exists(r => fromAlonzo(r) && r.accepted && r.name.contains(" indefinite ")))
    // #699: from Alonzo three items are a valid transaction
    for e <- List(Era.Alonzo, Era.Babbage, Era.Conway) do
      assert(rows.exists(r => r.era == e && r.accepted && r.name.endsWith(" without its flag")))
  }

  for row <- rows do
    test(row.name) {
      Tx.fromEnvelope(row.raw, row.era) match
        case Right(tx) =>
          assert(row.accepted, s"the reference refuses this encoding: ${row.failure}")
          row.txid.foreach(id => assertEquals(tx.body.id.hex, id, "the transaction id"))
          if fromAlonzo(row) then
            val parts = DetachedRecords
              .read(row.raw, Cbor.Limits(), DetachedRecords.Shapes.transaction(row.era))(
                LedgerDecodeError.Syntax(_)
              )(Tx.envelopeParts(_, row.era))
              .fold(e => fail(e.message), identity)
            assertEquals(parts.isValid, tx.isValid)
            row.wire.foreach(w => assertEquals(Tx.envelope(parts).hex, w, "the relayed bytes"))
        case Left(e) => assert(!row.accepted, s"the reference accepts this encoding: $e")
    }

  test("a transaction without its flag is valid, and the flag it carries is kept") {
    val by = rows.map(r => r.name -> r).toMap
    def read(name: String): Either[LedgerDecodeError, Tx] =
      val row = by.getOrElse(name, fail(s"no case $name"))
      Tx.fromEnvelope(row.raw, row.era)
    assertEquals(read("conway transaction without its flag").map(_.isValid), Right(true))
    assertEquals(read("conway transaction with its flag false").map(_.isValid), Right(false))
    assertEquals(
      read("shelley transaction indefinite"),
      Left(LedgerDecodeError.IndefiniteLength(DecodeField.Transaction))
    )
  }

object TxEnvelopeReferenceSuite:

  /** A line of the corpus. */
  final case class Row(
      name: String,
      era: Era,
      raw: Bytes,
      accepted: Boolean,
      failure: Option[String],
      txid: Option[String],
      wire: Option[String]
  )

  private val eras: Map[String, Era] = Era.values.map(e => e.toString.toLowerCase -> e).toMap

  private given Decoder[Row] = Decoder.instance { c =>
    def unknown(what: String) = io.circe.DecodingFailure(s"unknown $what", c.history)
    for
      name <- c.get[String]("name")
      era <- c.get[String]("era").flatMap(e => eras.get(e).toRight(unknown(s"era $e")))
      raw <- c
        .get[String]("bytes")
        .flatMap(h => Bytes.fromHex(h).leftMap(e => unknown(e.toString)))
      accepted <- c.get[Boolean]("accepted")
      fail <- c.get[Option[String]]("failure")
      txid <- c.get[Option[String]]("txid")
      wire <- c.get[Option[String]]("wire")
    yield Row(name, era, raw, accepted, fail, txid, wire)
  }

  def load: Vector[Row] =
    val path = Path.of("fixtures/reference/tx-envelope/cases.jsonl")
    Files
      .readAllLines(path)
      .asScala
      .toVector
      .filter(_.nonEmpty)
      .map(line => parser.decode[Row](line).fold(e => sys.error(s"$path: $e"), identity))
