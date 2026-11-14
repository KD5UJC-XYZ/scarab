// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.syntax.all.*
import io.circe.{Decoder, parser}
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.Era

/** An explicit decoder version ([[DecoderVersion.at]]): one of the era's range, `eraProtVerLow` to
  * `eraProtVerHigh`
  * (`libs/cardano-ledger-core/internal/Cardano/Ledger/Internal/Definition/Era.hs:87-141`,
  * cardano-ledger f649f975), any other refused; the era's entry points read at it.
  *
  * At f649f975 no decoder branches inside an era's range: the decoders branch at versions 2, 7, 9
  * and 12 (`ifDecoderVersionAtLeast`, `whenDecoderVersionAtLeast`), 3 (`decodeMetadatum`'s `> 2`),
  * 12 (`decodeAnnSet`'s `>= 12`) and 5, 7, 9 and 12 (`guardPlutus`), against ranges of Alonzo 5 to
  * 6, Babbage 7 to 8 and Conway 9 to 11. So every version of an era reads what its lowest reads,
  * and that a version is threaded is shown by reading a part at its neighbouring era's version,
  * across the branch at 9 and the one at 12, with the unchecked readers the entry points share.
  */
class DecoderVersionSuite extends FunSuite:
  import DecoderVersionSuite.*

  private val reference: Map[Era, (Int, Int)] = Map(
    Era.Byron -> (2, 2),
    Era.Shelley -> (2, 2),
    Era.Allegra -> (3, 3),
    Era.Mary -> (4, 4),
    Era.Alonzo -> (5, 6),
    Era.Babbage -> (7, 8),
    Era.Conway -> (9, 11),
    Era.Dijkstra -> (12, 12)
  )

  test("an era's range is eraProtVerLow to eraProtVerHigh, its lowest the version of the era") {
    assertEquals(reference.keySet, Era.values.toSet)
    for era <- Era.values do
      val range = DecoderVersion.range(era)
      assertEquals((range.low, range.high), reference(era), s"$era")
      assertEquals(range.low, DecoderVersion.of(era).value, s"$era: the lowest is the era's")
      assertEquals(DecoderVersion.high(era).value, range.high, s"$era: eraProtVerHigh")
      assertEquals(range.versions.map(_.value), (range.low to range.high).toVector, s"$era")
  }

  test("every version of an era's range is built, and the lowest is the era's own") {
    for
      era <- Era.values
      major <- DecoderVersion.range(era).low to DecoderVersion.range(era).high
    do assertEquals(DecoderVersion.at(era, major).map(_.value), Right(major), s"$era $major")
    for era <- Era.values do
      assertEquals(
        DecoderVersion.at(era, DecoderVersion.of(era).value),
        Right(DecoderVersion.of(era))
      )
  }

  test("a version outside an era's range is refused, with the era and its range") {
    for era <- Era.values do
      val range = DecoderVersion.range(era)
      for major <- Vector(range.low - 1, range.high + 1, 0, DecoderVersion.MaxVersion + 1) do
        assertEquals(
          DecoderVersion.at(era, major),
          Left(DecoderVersion.OutOfRange(era, major, range)),
          s"$era $major"
        )
    assertEquals(
      DecoderVersion.at(Era.Conway, 12).leftMap(_.range),
      Left(DecoderVersion.Range(9, 11))
    )
  }

  test("an entry point refuses a version of another era before it reads") {
    val dijkstra = DecoderVersion.Dijkstra
    val babbage = version(Era.Babbage, 8)
    val outside = LedgerDecodeError.VersionOutsideEra(
      DecoderVersion.OutOfRange(Era.Conway, 12, DecoderVersion.Range(9, 11))
    )
    val below = LedgerDecodeError.VersionOutsideEra(
      DecoderVersion.OutOfRange(Era.Conway, 8, DecoderVersion.Range(9, 11))
    )
    assertEquals(TxBody.decode(duplicateInputs, Era.Conway, dijkstra), Left(outside))
    assertEquals(TxBody.decode(duplicateInputs, Era.Conway, babbage), Left(below))
    assertEquals(Witnesses.decode(chainCode31, Era.Conway, dijkstra), Left(outside))
    assertEquals(Tx.fromEnvelope(transaction, Era.Conway, babbage), Left(below))
    assertEquals(
      Tx.fromBlock(
        Block.Transaction(duplicateInputs, Bytes(Vector(0xa0.toByte)), None, true),
        Era.Conway,
        babbage
      ),
      Left(below)
    )
    assertEquals(
      Certificate.decode(node(hex("82008200581c" + "11" * 28)), Era.Conway, dijkstra),
      Left(outside)
    )
    assertEquals(
      Header.fromRaw(Era.Conway, Bytes.empty, dijkstra),
      Left(Header.fail(outside))
    )
    val block = DetachedRecordsReferenceSuite.block(Era.Conway, hex("80" + "80" + "a0" + "80"))
    assert(Block.decodeShelley(block).isRight, "the block is read at the era's version")
    assertEquals(
      Block.decodeShelley(block, _ => babbage),
      Left(ChainError.Decode(DecodeField.Block, below))
    )
    assertEquals(
      Block.decodeShelley(block, DecoderVersion.high).map(_.header),
      Block.decodeShelley(block).map(_.header)
    )
  }

  test("a version is threaded: the branch at 9 is taken from 9, through a body and a transaction") {
    val at8 = version(Era.Babbage, 8)
    val at9 = DecoderVersion.of(Era.Conway)
    val duplicate = LedgerDecodeError.DuplicateElement(DecodeField.Inputs)
    // `decodeSetEnforceNoDuplicates` from 9 (`Decoder.hs`, f649f975); `Set.fromList` below it
    assertEquals(TxBody.decodeAt(duplicateInputs, Era.Conway, at8).map(_.inputs.size), Right(1))
    assertEquals(TxBody.decodeAt(duplicateInputs, Era.Conway, at9), Left(duplicate))
    assertEquals(TxBody.decode(duplicateInputs, Era.Conway), Left(duplicate))
    for v <- DecoderVersion.range(Era.Conway).versions do
      assertEquals(TxBody.decode(duplicateInputs, Era.Conway, v), Left(duplicate), s"${v.value}")
    assertEquals(Tx.envelopeAt(transaction, Era.Conway, at8).map(_.body.inputs.size), Right(1))
    assertEquals(Tx.envelopeAt(transaction, Era.Conway, at9), Left(duplicate))
  }

  test(
    "a version is threaded: the branch at 9 is taken from 9, through a certificate and a block"
  ) {
    val at8 = version(Era.Babbage, 8)
    val at9 = DecoderVersion.of(Era.Conway)
    // a pool registration whose two owners are one key: `decodeSet` refuses it from 9
    val owner = "581c" + "11" * 28
    val registration = node(
      hex(
        "8a03581c" + "00" * 28 + "5820" + "00" * 32 + "0000d81e820001581de0" + "00" * 28 + "82" +
          owner + owner + "80" + "f6"
      )
    )
    assertEquals(
      Certificate.decodeAt(registration, Era.Conway, at8).map {
        case Certificate.RegisterPool(params) => params.owners.size
        case _                                => 0
      },
      Right(1)
    )
    assertEquals(
      Certificate.decode(registration, Era.Conway),
      Left(LedgerDecodeError.DuplicateElement(DecodeField.PoolOwners))
    )
    // a block body whose auxiliary data names the one transaction twice: `decodeIntMap` refuses it
    // from 9 and keeps the last entry below it
    val parts = node(
      hex("85" + "80" + "81a0" + "81a0" + "a2" + "00a1" + "0001" + "00a1" + "0002" + "80")
    ).value match
      case xyz.kd5ujc.scarab.codec.Value.Arr(xs) => xs
      case other                                 => fail(s"not a list: $other")
    assertEquals(
      Block.segregated(parts, Era.Conway, at8).map(_.transactions.map(_.auxiliaryData)),
      Right(Vector(Some(hex("a10002"))))
    )
    assertEquals(
      Block.segregated(parts, Era.Conway, at9).map(_.transactions.size),
      Left(
        ChainError.Decode(
          DecodeField.Block,
          LedgerDecodeError.DuplicateKey(DecodeField.AuxiliaryData)
        )
      )
    )
  }

  test("a version is threaded: the branch at 12 is taken from 12, through a witness set") {
    // `DecCBOR ChainCode` checks 32 bytes from 12 (`Keys/Bootstrap.hs:72-78`, f649f975)
    for v <- DecoderVersion.range(Era.Conway).versions do
      assertEquals(
        Witnesses.decode(chainCode31, Era.Conway, v).map(_.bootstrap.map(_.chainCode.size)),
        Right(Vector(31)),
        s"${v.value}"
      )
    assertEquals(
      Witnesses.decodeAt(chainCode31, Era.Conway, DecoderVersion.Dijkstra),
      Left(LedgerDecodeError.ByteLength(DecodeField.ChainCode, 32, 31))
    )
  }

  private val rows: Vector[Row] = load.fold(e => fail(e.toString), identity)

  test("the corpora hold each Shelley-based era's bodies, witness sets, transactions and headers") {
    for era <- Shelley do
      for kind <- Vector(Kind.Body, Kind.Transaction) do
        assert(rows.exists(r => r.era == era && r.kind == kind), s"no $era $kind")
      assert(
        rows.exists(r => r.era == era && Set(Kind.Witnesses, Kind.Transaction).contains(r.kind)),
        s"no $era witness set"
      )
    for era <- Vector(Era.Shelley, Era.Alonzo, Era.Babbage, Era.Conway) do
      for kind <- Vector(Kind.Header, Kind.Block) do
        assert(rows.exists(r => r.era == era && r.kind == kind), s"no $era $kind")
    for era <- Vector(Era.Alonzo, Era.Babbage, Era.Conway) do
      assert(rows.exists(r => r.era == era && r.kind == Kind.BlockBody), s"no $era block body")
  }

  for era <- Shelley do
    test(s"every version of $era's range reads the corpora as its own version does") {
      val own = rows.filter(_.era == era)
      for
        row <- own
        v <- DecoderVersion.range(era).versions
      do
        assertEquals(
          read(row, Some(v)),
          read(row, None),
          s"${row.source}: ${row.name} at ${v.value}"
        )
    }

object DecoderVersionSuite:
  val Shelley: Vector[Era] =
    Vector(Era.Shelley, Era.Allegra, Era.Mary, Era.Alonzo, Era.Babbage, Era.Conway)

  def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => sys.error(e.toString), identity)

  def node(b: Bytes): xyz.kd5ujc.scarab.codec.Node =
    CborRead.decode(b).fold(e => sys.error(e.toString), identity)

  def version(era: Era, major: Int): DecoderVersion =
    DecoderVersion.at(era, major).fold(e => sys.error(e.toString), identity)

  private val input = "825820" + "11" * 32 + "00"

  /** A Conway body of one input written twice, no outputs and no fee. */
  val duplicateInputs: Bytes = hex("a300" + "82" + input + input + "0180" + "0200")

  /** A Conway transaction of that body, an empty witness set and no auxiliary data. */
  val transaction: Bytes = hex("84") ++ duplicateInputs ++ hex("a0f5f6")

  /** A witness set of one bootstrap witness whose chain code is 31 bytes. */
  val chainCode31: Bytes =
    hex("a10281845820" + "22" * 32 + "5840" + "33" * 64 + "581f" + "44" * 31 + "41a0")

  enum Kind(val wire: String):
    case Body extends Kind("body")
    case Witnesses extends Kind("witnesses")
    case Transaction extends Kind("transaction")
    case Header extends Kind("header")
    case Block extends Kind("block")
    case BlockBody extends Kind("blockbody")

  /** A line of a reference corpus, of a part one of the entry points reads. */
  final case class Row(source: String, name: String, era: Era, kind: Kind, raw: Bytes)

  /** What an entry point read from a line at a version (`None`: the era's own), refusals included.
    */
  enum Read:
    case Body(result: Either[LedgerDecodeError, TxBody])
    case Witnesses(result: Either[LedgerDecodeError, xyz.kd5ujc.scarab.ledger.Witnesses])
    case Transaction(result: Either[LedgerDecodeError, Tx])
    case Header(result: Either[ChainError, xyz.kd5ujc.scarab.ledger.Header.Shelley])
    case Block(
        result: Either[ChainError, xyz.kd5ujc.scarab.ledger.Block],
        transactions: Vector[Either[LedgerDecodeError, Tx]]
    )

  def read(row: Row, at: Option[DecoderVersion]): Read =
    val era = row.era
    def blockOf(raw: Bytes) =
      val block = at.fold(xyz.kd5ujc.scarab.ledger.Block.decodeShelley(raw))(v =>
        xyz.kd5ujc.scarab.ledger.Block.decodeShelley(raw, _ => v)
      )
      val transactions = block.toOption.toVector.flatMap {
        case b: xyz.kd5ujc.scarab.ledger.Block.Shelley =>
          b.transactions.map(t => at.fold(Tx.fromBlock(t, era))(Tx.fromBlock(t, era, _)))
        case _ => Vector.empty
      }
      Read.Block(block, transactions)
    row.kind match
      case Kind.Body =>
        Read.Body(at.fold(TxBody.decode(row.raw, era))(TxBody.decode(row.raw, era, _)))
      case Kind.Witnesses =>
        Read.Witnesses(
          at.fold(xyz.kd5ujc.scarab.ledger.Witnesses.decode(row.raw, era))(
            xyz.kd5ujc.scarab.ledger.Witnesses.decode(row.raw, era, _)
          )
        )
      case Kind.Transaction =>
        Read.Transaction(at.fold(Tx.fromEnvelope(row.raw, era))(Tx.fromEnvelope(row.raw, era, _)))
      case Kind.Header =>
        Read.Header(
          at.fold(xyz.kd5ujc.scarab.ledger.Header.fromRaw(era, row.raw))(
            xyz.kd5ujc.scarab.ledger.Header.fromRaw(era, row.raw, _)
          )
        )
      case Kind.Block =>
        blockOf(hex("82" + f"${era.blockTag}%02x") ++ row.raw)
      case Kind.BlockBody =>
        blockOf(DetachedRecordsReferenceSuite.block(era, row.raw))

  /** The corpora of the reference's verdicts on each era's parts, read by the decoders of each. */
  val Sources: Vector[String] = Vector(
    "era-decoders",
    "recd-detached",
    "recd-records",
    "tx-envelope",
    "header-decoding"
  )

  enum LoadError:
    case Unreadable(path: Path, cause: Throwable)
    case Line(path: Path, number: Int, cause: io.circe.Error)

  private val eras: Map[String, Era] = Era.values.map(e => e.toString.toLowerCase -> e).toMap

  /** A line of a part this suite reads, of a Shelley-based era; any other is `None`. */
  private def line(source: String): Decoder[Option[Row]] = Decoder.instance { c =>
    for
      name <- c.get[String]("name")
      era <- c.get[String]("era").map(eras.get)
      kind <- c.get[String]("kind").map(k => Kind.values.find(_.wire == k))
      raw <- c.get[String]("bytes")
    yield (era.filter(Shelley.contains), kind).mapN((e, k) => Row(source, name, e, k, hex(raw)))
  }

  def load: Either[LoadError, Vector[Row]] =
    Sources.flatTraverse { source =>
      val path = Path.of(s"fixtures/reference/$source/cases.jsonl")
      Either
        .catchNonFatal(Files.readAllLines(path).asScala.toVector)
        .leftMap(LoadError.Unreadable(path, _))
        .flatMap(
          _.zipWithIndex
            .filter(_._1.nonEmpty)
            .traverse((text, i) =>
              parser
                .decode[Option[Row]](text)(using line(source))
                .leftMap(LoadError.Line(path, i + 1, _))
            )
            .map(_.flatten)
        )
    }
