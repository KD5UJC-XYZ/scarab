// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.syntax.all.*
import io.circe.{Decoder, parser}
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.Era

/** An explicit decoder version. [[DecoderVersion.at]] builds one of an era's range, `ProtVerLow` to
  * `ProtVerHigh`
  * (`libs/cardano-ledger-core/internal/Cardano/Ledger/Internal/Definition/Era.hs:87-141`,
  * cardano-ledger f649f975), and refuses any other; the era's entry points read at whatever version
  * they are given, as `decodeFull'` does
  * (`libs/cardano-ledger-binary/src/Cardano/Ledger/Binary/Decoding.hs:93-100`).
  *
  * At f649f975 no decoder branches inside an era's range: the decoders branch at versions 2, 7, 9
  * and 12 (`ifDecoderVersionAtLeast`, `whenDecoderVersionAtLeast`), 3 (`decodeMetadatum`'s `> 2`),
  * 12 (`decodeAnnSet`'s `>= 12`) and 5, 7, 9 and 12 (`guardPlutus`), against ranges of Alonzo 5 to
  * 6, Babbage 7 to 8 and Conway 9 to 11. So every version of an era gives the verdict and the value
  * its lowest gives; a refusal can still differ in the version it names (an auxiliary Plutus script
  * refused before its language's version, `AuxiliaryScriptProtocol`), which no line of these
  * corpora reaches. That a version reaches each place that branches on it is shown by reading a
  * part across the branch it meets, at 9 or at 12, through each entry point.
  */
class DecoderVersionSuite extends FunSuite:
  import DecoderVersionSuite.*

  private val reference: Map[Era, (Int, Int)] = Map(
    Era.Byron -> (0, 1),
    Era.Shelley -> (2, 2),
    Era.Allegra -> (3, 3),
    Era.Mary -> (4, 4),
    Era.Alonzo -> (5, 6),
    Era.Babbage -> (7, 8),
    Era.Conway -> (9, 11),
    Era.Dijkstra -> (12, 12)
  )

  test("an era's range is ProtVerLow to ProtVerHigh, from Shelley its lowest the era's version") {
    assertEquals(reference.keySet, Era.values.toSet)
    for era <- Era.values do
      val range = DecoderVersion.range(era)
      assertEquals((range.low, range.high), reference(era), s"$era")
      assertEquals(DecoderVersion.high(era).value, range.high, s"$era: eraProtVerHigh")
      assertEquals(range.versions.map(_.value), (range.low to range.high).toVector, s"$era")
    for era <- Era.values.filterNot(_ == Era.Byron) do
      assertEquals(DecoderVersion.range(era).low, DecoderVersion.of(era).value, s"$era")
    // the readers of Shelley-shaped parts read a Byron era as Shelley, outside Byron's own range
    assertEquals(DecoderVersion.of(Era.Byron), DecoderVersion.of(Era.Shelley))
    assert(!DecoderVersion.range(Era.Byron).contains(DecoderVersion.of(Era.Byron).value))
  }

  test("every version of an era's range is built, and from Shelley the lowest is the era's own") {
    for
      era <- Era.values
      major <- DecoderVersion.range(era).low to DecoderVersion.range(era).high
    do assertEquals(DecoderVersion.at(era, major).map(_.value), Right(major), s"$era $major")
    for era <- Era.values.filterNot(_ == Era.Byron) do
      assertEquals(
        DecoderVersion.at(era, DecoderVersion.of(era).value),
        Right(DecoderVersion.of(era))
      )
  }

  test("a version outside an era's range is refused, with the era and its range") {
    for era <- Era.values do
      val range = DecoderVersion.range(era)
      for major <- Vector(range.low - 1, range.high + 1, DecoderVersion.MaxVersion + 1) do
        assertEquals(
          DecoderVersion.at(era, major),
          Left(DecoderVersion.OutOfRange(era, major, range)),
          s"$era $major"
        )
    assertEquals(
      DecoderVersion.at(Era.Conway, 12).leftMap(_.range),
      Left(DecoderVersion.Range(9, 11))
    )
    assertEquals(
      DecoderVersion.at(Era.Byron, 2).leftMap(_.range),
      Left(DecoderVersion.Range(0, 1))
    )
  }

  private val at8 = version(Era.Babbage, 8)
  private val at9 = DecoderVersion.of(Era.Conway)
  private val at11 = DecoderVersion.high(Era.Conway)
  private val at12 = DecoderVersion.Dijkstra

  test("a body reads its inputs, withdrawals and certificates at the version given (9)") {
    val duplicate = LedgerDecodeError.DuplicateElement(DecodeField.Inputs)
    // `decodeSetEnforceNoDuplicates` from 9 (`Decoder.hs`, f649f975); `Set.fromList` below it
    assertEquals(TxBody.decode(duplicateInputs, Era.Conway, at8).map(_.inputs.size), Right(1))
    assertEquals(TxBody.decode(duplicateInputs, Era.Conway, at9), Left(duplicate))
    assertEquals(TxBody.decode(duplicateInputs, Era.Conway), Left(duplicate))
    for v <- DecoderVersion.range(Era.Conway).versions do
      assertEquals(TxBody.decode(duplicateInputs, Era.Conway, v), Left(duplicate), s"${v.value}")
    // the same account withdrawn twice: refused from 9, the last kept below it
    val withdrawals = hex("a400800180020005a2" + account + "00" + account + "01")
    assertEquals(TxBody.decode(withdrawals, Era.Conway, at8).map(_.withdrawals.size), Right(1))
    assertEquals(
      TxBody.decode(withdrawals, Era.Conway, at9),
      Left(LedgerDecodeError.DuplicateElement(DecodeField.Withdrawals))
    )
    // the body's certificates are read at the body's version
    val certificates = hex("a40080018002000481") ++ registration
    assertEquals(
      TxBody.decode(certificates, Era.Conway, at8).map(_.certificates.map(owners)),
      Right(Vector(1))
    )
    assertEquals(
      TxBody.decode(certificates, Era.Conway, at9),
      Left(LedgerDecodeError.DuplicateElement(DecodeField.PoolOwners))
    )
  }

  test("a certificate reads its pool owners and its anchor's url at the version given (9)") {
    assertEquals(Certificate.decode(node(registration), Era.Conway, at8).map(owners), Right(1))
    assertEquals(
      Certificate.decode(node(registration), Era.Conway, at9),
      Left(LedgerDecodeError.DuplicateElement(DecodeField.PoolOwners))
    )
    // `textDecCBOR 64` below 9 and 128 from it (`BaseTypes.hs:644-697`, f649f975)
    val url = "7864" + "61" * 100
    val drep = node(hex("841082" + "00581c" + "11" * 28 + "0082" + url + "5820" + "22" * 32))
    assertEquals(
      Certificate.decode(drep, Era.Conway, at8),
      Left(LedgerDecodeError.TextTooLong(DecodeField.AnchorUrl, 64, 100))
    )
    assert(Certificate.decode(drep, Era.Conway, at9).isRight)
  }

  test("a witness set reads its lists and chain codes at the version given (9, 12)") {
    // `decodeNonEmptyList` from 9 (`Alonzo/TxWits.hs`, f649f975)
    val empty = hex("a10080")
    assertEquals(Witnesses.decode(empty, Era.Conway, at8).map(_.vkeys), Right(Vector.empty))
    assertEquals(
      Witnesses.decode(empty, Era.Conway, at9),
      Left(LedgerDecodeError.Empty(DecodeField.VkeyWitnesses))
    )
    // `DecCBOR ChainCode` checks 32 bytes from 12 (`Keys/Bootstrap.hs:72-78`, f649f975)
    for v <- DecoderVersion.range(Era.Conway).versions do
      assertEquals(
        Witnesses.decode(chainCode31, Era.Conway, v).map(_.bootstrap.map(_.chainCode.size)),
        Right(Vector(31)),
        s"${v.value}"
      )
    assertEquals(
      Witnesses.decode(chainCode31, Era.Conway, at12),
      Left(LedgerDecodeError.ByteLength(DecodeField.ChainCode, 32, 31))
    )
  }

  test("a transaction reads its body and auxiliary data at the version given (9)") {
    assertEquals(Tx.fromEnvelope(transaction, Era.Conway, at8).map(_.body.inputs.size), Right(1))
    assertEquals(
      Tx.fromEnvelope(transaction, Era.Conway, at9),
      Left(LedgerDecodeError.DuplicateElement(DecodeField.Inputs))
    )
    // a metadata label written twice: refused from 9 (`decodeMetadata`, `Metadata.hs`, f649f975)
    val labels = hex("a200000000")
    val duplicateLabels = LedgerDecodeError.DuplicateKey(DecodeField.MetadataLabels)
    val whole = hex("84a3008001800200a0f5") ++ labels
    assert(Tx.fromEnvelope(whole, Era.Conway, at8).isRight)
    assertEquals(Tx.fromEnvelope(whole, Era.Conway, at9), Left(duplicateLabels))
    val parts = Block.Transaction(hex("a3008001800200"), hex("a0"), Some(labels), true)
    assert(Tx.fromBlock(parts, Era.Conway, at8).isRight)
    assertEquals(Tx.fromBlock(parts, Era.Conway, at9), Left(duplicateLabels))
  }

  test("a header reads its byte strings at the version given (12)") {
    // `decodeBytes` takes a chunked string from 12 only (`Decoder.hs:350-373`, f649f975)
    val definite = hex("82" + headerBody + "5901c0" + "08" * 448)
    val chunked = hex("82" + headerBody + "5f" + ("5900e0" + "08" * 224) * 2 + "ff")
    val read = Header.fromRaw(Era.Conway, definite).map(_.signable)
    assert(read.isRight, s"$read")
    assert(Header.fromRaw(Era.Conway, chunked, at11).isLeft)
    assertEquals(Header.fromRaw(Era.Conway, chunked, at12).map(_.signable), read)
  }

  test("a block reads its records, header and auxiliary data at the version given (9, 12)") {
    // a block body whose auxiliary data names the one transaction twice: `decodeIntMap` refuses it
    // from 9 and keeps the last entry below it
    val aux = DetachedRecordsReferenceSuite.block(
      Era.Conway,
      hex("81a0" + "81a0" + "a2" + "00a10001" + "00a10002" + "80")
    )
    assertEquals(
      Block.decodeShelley(aux, _ => at8).map {
        case b: Block.Shelley => b.transactions.map(_.auxiliaryData)
        case _                => Vector.empty
      },
      Right(Vector(Some(hex("a10002"))))
    )
    assertEquals(
      Block.decodeShelley(aux, _ => at9),
      Left(
        ChainError.Decode(
          DecodeField.Block,
          LedgerDecodeError.DuplicateKey(DecodeField.AuxiliaryData)
        )
      )
    )
    // a block whose header is a detached record: read below 12, where `RecD` reads one, and
    // refused at 12, where no record is detached ([[DetachedRecords.Shapes]])
    val detached = rows
      .find(r => r.source == "header-decoding" && r.name == "babbage block detached header")
      .map(r => hex("8206") ++ r.raw)
      .getOrElse(fail("no detached Babbage block in the header-decoding corpus"))
    for v <- DecoderVersion.range(Era.Babbage).versions do
      assert(Block.decodeShelley(detached, _ => v).isRight, s"${v.value}")
    assert(Block.decodeShelley(detached, _ => at12).isLeft)
  }

  test("a body, a witness set and a transaction read detached records below 12 only") {
    for kind <- Vector(Kind.Body, Kind.Witnesses, Kind.Transaction) do
      val detached = rows.filter(r =>
        r.source == "recd-detached" && r.era == Era.Conway && r.kind == kind &&
          DetachedRecords.mayHold(r.raw) && accepted(read(r, None))
      )
      assert(detached.nonEmpty, s"no accepted detached Conway $kind")
      assert(
        detached.exists(r => !accepted(read(r, Some(at12)))),
        s"every detached Conway $kind read at 12"
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

  /** A testnet key reward account. */
  private val account = "581de0" + "11" * 28

  /** A pool registration whose two owners are one key: `decodeSet` refuses it from 9. */
  val registration: Bytes =
    val owner = "581c" + "11" * 28
    hex(
      "8a03581c" + "00" * 28 + "5820" + "00" * 32 + "0000d81e820001581de0" + "00" * 28 + "82" +
        owner + owner + "80" + "f6"
    )

  /** The owners of a pool registration, 0 for any other certificate. */
  def owners(c: Certificate): Int = c match
    case Certificate.RegisterPool(params) => params.owners.size
    case _                                => 0

  /** A Praos header body of protocol version 9.0 (`Praos/BlockHeader.hs:203-229`, f649f975). */
  val headerBody: String =
    "8a" + "01" + "02" + "f6" + "5820" + "03" * 32 + "5820" + "04" * 32 + "82" + "5840" +
      "01" * 64 + "5850" + "02" * 80 + "00" + "5820" + "05" * 32 + "84" + "5820" + "06" * 32 +
      "00" + "00" + "5840" + "07" * 64 + "82" + "09" + "00"

  /** Whether an entry point read a line. */
  def accepted(r: Read): Boolean = r match
    case Read.Body(result)                => result.isRight
    case Read.Witnesses(result)           => result.isRight
    case Read.Transaction(result)         => result.isRight
    case Read.Header(result)              => result.isRight
    case Read.Block(result, transactions) => result.isRight && transactions.forall(_.isRight)

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
      raw <- c
        .get[String]("bytes")
        .flatMap(h =>
          Bytes.fromHex(h).leftMap(e => io.circe.DecodingFailure(s"bytes: $e", c.history))
        )
    yield (era.filter(Shelley.contains), kind).mapN((e, k) => Row(source, name, e, k, raw))
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
