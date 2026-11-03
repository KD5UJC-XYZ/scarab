// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.syntax.all.*
import io.circe.{Decoder, parser}
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor}
import xyz.kd5ujc.scarab.protocol.Era

/** Below decoder version 12 the reference reads a `RecD` record written as an empty indefinite list
  * `9f ff` with its fields after the break (`decodeCount (RecD cn) n`,
  * `libs/cardano-ledger-binary/src/Cardano/Ledger/Binary/Decoding/Coders.hs:482`;
  * `decodeListLikeT`, `Decoding/Decoder.hs:690-708`, cardano-ledger f649f975), and every enclosing
  * decoder counts the record as one item ([[DetachedRecords]]).
  *
  * `fixtures/reference/recd-detached/cases.jsonl` holds each record a block or a transaction can
  * hold written so, era by era, with the verdict of the unchanged released decoders and, for an
  * accepted body, transaction or block body, the ids they hash from the bytes they read. Scarab
  * must give the same verdict and hash the same bytes. Evidence for #687.
  */
class DetachedRecordsReferenceSuite extends FunSuite:
  import DetachedRecordsReferenceSuite.*

  private val rows: Vector[Row] = load

  /** What a row is read as: the reference's decoder of its `kind`. */
  private def scarab(row: Row): Either[Refused, Verdict] = row.kind match
    case Kind.Body =>
      TxBody
        .decode(row.raw, row.era)
        .bimap(Refused.Decode(_), b => Verdict(Some(b.id), None, Vector.empty))
    case Kind.Witnesses =>
      Witnesses
        .decode(row.raw, row.era)
        .bimap(Refused.Decode(_), _ => Verdict(None, None, Vector.empty))
    case Kind.Auxiliary =>
      AuxiliaryScripts
        .read(row.raw, DecoderVersion.of(row.era).value)(using
          ByteStrings.of(DecoderVersion.of(row.era))
        )
        .bimap(Refused.Decode(_), _ => Verdict(None, None, Vector.empty))
    case Kind.Transaction =>
      Tx.fromEnvelope(row.raw, row.era)
        .bimap(Refused.Decode(_), tx => Verdict(Some(tx.body.id), None, Vector.empty))
    case Kind.BlockBody =>
      Block
        .decodeShelley(block(row.era, row.raw))
        .leftMap(Refused.Chain(_))
        .flatMap {
          case b: Block.Shelley =>
            b.transactions
              .traverse(t => Tx.fromBlock(t, row.era).leftMap(Refused.Decode(_)))
              .as(Verdict(None, Some(b.bodyHash), b.transactions.map(_.id)))
          case _ => Left(Refused.NotShelleyBased)
        }

  test("the corpus covers every era and kind, refused and accepted") {
    assertEquals(rows.map(_.era).toSet, Era.values.toSet - Era.Byron)
    assertEquals(rows.map(_.kind).toSet, Kind.values.toSet)
    assertEquals(rows.map(_.name).distinct.size, rows.size)
    for era <- rows.map(_.era).distinct do
      assert(rows.exists(r => r.era == era && r.accepted), s"no accepted $era case")
      assert(rows.exists(r => r.era == era && !r.accepted), s"no refused $era case")
  }

  test(
    "every accepted case with a detached record has one the walk finds and a tree that shows it"
  ) {
    // The ordinary decode cannot accept these unread: the first detached record is an empty
    // indefinite list in the tree of items, or the tree is refused.
    for row <- rows if row.accepted && !row.name.endsWith(" blockbody plain") do
      val shape = row.kind match
        case Kind.Body        => DetachedRecords.Shapes.body(row.era)
        case Kind.Witnesses   => DetachedRecords.Shapes.witnesses(row.era)
        case Kind.Auxiliary   => DetachedRecords.Shapes.auxiliaryData(row.era)
        case Kind.Transaction => DetachedRecords.Shapes.transaction(row.era)
        case Kind.BlockBody   => DetachedRecords.Shapes.block
      val raw = if row.kind == Kind.BlockBody then block(row.era, row.raw) else row.raw
      assert(
        DetachedRecords.detached(raw, shape).exists(!_.isEmpty),
        s"${row.name}: no record found"
      )
      assert(
        Cbor
          .decode(raw, Cbor.Limits(maxInputBytes = 2500000))
          .forall(_.decodeHasEmptyIndefiniteList),
        s"${row.name}: no empty indefinite list in the tree"
      )
  }

  for row <- rows do
    test(s"${row.name}") {
      scarab(row) match
        case Right(verdict) =>
          assert(row.accepted, s"the reference refuses this encoding: ${row.failure}")
          row.txid.foreach(id =>
            assertEquals(verdict.id.map(_.hex), Some(id), "the transaction id")
          )
          row.bodyHash.foreach(h =>
            assertEquals(verdict.bodyHash.map(_.hex), Some(h), "the body hash")
          )
          row.txids.foreach(ids => assertEquals(verdict.ids.map(_.hex), ids, "the transaction ids"))
        case Left(e) => assert(!row.accepted, s"the reference accepts this encoding: $e")
      // The bootstrap ledger reads the update proposals and certificates of an era with PPUP.
      if row.kind == Kind.Body && row.accepted && ParamUpdate.Rules.of(row.era).isDefined then
        assert(LiteLedger.effects(row.raw, row.era).isRight, "the bootstrap ledger's reading")
    }

object DetachedRecordsReferenceSuite:
  enum Kind(val wire: String):
    case Body extends Kind("body")
    case Witnesses extends Kind("witnesses")
    case Auxiliary extends Kind("auxiliary")
    case Transaction extends Kind("transaction")
    case BlockBody extends Kind("blockbody")

  /** A line of the corpus. */
  final case class Row(
      name: String,
      era: Era,
      kind: Kind,
      raw: Bytes,
      accepted: Boolean,
      failure: Option[String],
      txid: Option[String],
      bodyHash: Option[String],
      txids: Option[Vector[String]]
  )

  /** Why Scarab refused a row. */
  enum Refused:
    case Decode(cause: LedgerDecodeError)
    case Chain(cause: ChainError)
    case NotShelleyBased

  /** What Scarab read: a transaction id, a block body's hash and its transactions' ids. */
  final case class Verdict(id: Option[Bytes], bodyHash: Option[Bytes], ids: Vector[Bytes])

  private val eras: Map[String, Era] = Era.values.map(e => e.toString.toLowerCase -> e).toMap

  private given Decoder[Row] = Decoder.instance { c =>
    for
      name <- c.get[String]("name")
      era <- c.get[String]("era").flatMap(e => eras.get(e).toRight(failure(c, s"era $e")))
      kind <- c
        .get[String]("kind")
        .flatMap(k => Kind.values.find(_.wire == k).toRight(failure(c, s"kind $k")))
      raw <- c
        .get[String]("bytes")
        .flatMap(h => Bytes.fromHex(h).leftMap(e => failure(c, e.toString)))
      accepted <- c.get[Boolean]("accepted")
      fail <- c.get[Option[String]]("failure")
      txid <- c.get[Option[String]]("txid")
      bodyHash <- c.get[Option[String]]("bodyHash")
      txids <- c.get[Option[Vector[String]]]("txids")
    yield Row(name, era, kind, raw, accepted, fail, txid, bodyHash, txids)
  }

  private def failure(c: io.circe.HCursor, what: String) =
    io.circe.DecodingFailure(s"unknown $what", c.history)

  /** The lines of the corpus. */
  def load: Vector[Row] =
    val path = Path.of("fixtures/reference/recd-detached/cases.jsonl")
    Files
      .readAllLines(path)
      .asScala
      .toVector
      .filter(_.nonEmpty)
      .map(line => parser.decode[Row](line).fold(e => sys.error(s"$path: $e"), identity))

  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => sys.error(e.toString), identity)
  private def head(major: Int, n: Int): String = f"${(major << 5) | n}%02x"
  private def bytes(size: Int, fill: String): String =
    val length =
      if size < 24 then head(2, size) else if size < 256 then f"58$size%02x" else f"59$size%04x"
    length + fill * size

  /** A block of `era` around `body`, the parts after its header: `[tag, [header, body...]]` with a
    * header of the era's shape (TPraos 15 fields before Babbage, Praos 10 from it) that
    * [[Header.fromRaw]] reads; no rule runs on it.
    */
  def block(era: Era, body: Bytes): Bytes =
    val vrf = "82" + bytes(64, "01") + bytes(80, "02")
    val common = "01" + "02" + "f6" + bytes(32, "03") + bytes(32, "04")
    val fields =
      if era.ordinal < Era.Babbage.ordinal then
        "8f" + common + vrf + vrf + "00" + bytes(32, "05") + bytes(32, "06") + "00" + "00" +
          bytes(64, "07") + "02" + "00"
      else
        "8a" + common + vrf + "00" + bytes(32, "05") +
          ("84" + bytes(32, "06") + "00" + "00" + bytes(64, "07")) + "82" + "09" + "00"
    val header = "82" + fields + bytes(448, "08")
    val parts = if era.ordinal >= Era.Alonzo.ordinal then "85" else "84"
    hex("82" + f"${era.blockTag}%02x" + parts + header + body.hex)
