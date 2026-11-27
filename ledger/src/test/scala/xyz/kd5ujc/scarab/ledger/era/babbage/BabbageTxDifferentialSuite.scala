// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.babbage

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import io.circe.{Json, parser}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.ledger.{
  AuxiliaryScripts,
  Block,
  ByteStrings,
  DecodeField,
  DecoderVersion,
  LedgerDecodeError,
  ReferenceCorpus,
  Tx as FlatTx,
  TxBody as FlatTxBody,
  Witnesses as FlatWitnesses
}
import xyz.kd5ujc.scarab.ledger.ReferenceCorpus.{Corpus, Kind, Refusal}
import xyz.kd5ujc.scarab.ledger.era.{EraCorpus, EraTxError, PParamsDecodeError}
import xyz.kd5ujc.scarab.protocol.{Era, StateLayout}

/** Babbage's era decoders ([[BabbageTxBody.decode]], [[BabbageTxWits.decode]],
  * [[BabbageTxAuxData.decode]], [[BabbageTx.decode]] and [[BabbageTx.fromEnvelope]]) against the
  * flat decoder at Babbage (`Tx.fromBlock`, `TxBody.decode`, `Witnesses.decode`,
  * `AuxiliaryScripts.read`, `Tx.fromEnvelope`), the path the node runs for Babbage, at every
  * layout: on every Babbage transaction the repository holds (the recorded blocks, the reference's
  * refusal, v1-context and Plutus admission corpora) and byte mutants of them, on the Babbage rows
  * of the reference's decoder corpora (those recorded at a version of Babbage's, read at it) and on
  * the reference's verdicts on Babbage auxiliary data, the two accept and refuse alike. An accepted
  * transaction is compared whole ([[BabbageTestKit.txDifferences]]): its body, witness set, flag,
  * fee size and auxiliary data, against the flat readers' reading of the same bytes; a refused
  * mutant by its verdict alone. A refused row is refused with the case the reference's recording
  * names, where it names one, and the refusals that differ from the flat decoder's, compared whole,
  * are listed, each with the reference's own failure.
  */
class BabbageTxDifferentialSuite extends FunSuite:
  import BabbageTestKit.{sameAux, sameBody, sameTx, sameWits, txDifferences}

  private val layouts: Vector[StateLayout] = StateLayout.values.toVector

  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.toString), identity)

  /** A transaction of a block read both ways, at every layout. */
  private def sameParts(t: Block.Transaction, what: String): Unit =
    val flat = FlatTx.fromBlock(t, Era.Babbage).fold(e => fail(s"$what: flat $e"), identity)
    layouts.foreach { l =>
      val era = BabbageTx.decode(t, l).fold(e => fail(s"$what at $l: $e"), identity)
      sameTx(era, flat, s"$what at $l")
      // the same transaction submitted whole, as the mempool holds it
      val whole = FlatTx.envelope(t)
      assert(FlatTx.fromEnvelope(whole, Era.Babbage).isRight, what)
      assertEquals(BabbageTx.fromEnvelope(whole, l), Right(era), what)
    }

  // ---- the recorded transactions

  private def babbageTxs(file: String): Vector[Block.Transaction] =
    EraCorpus
      .shelleyBlocks(Vector(file))
      .filter(_.era == Era.Babbage)
      .flatMap(_.transactions)

  test(
    "every recorded Babbage transaction reads alike both ways, and projects to the flat values"
  ) {
    val counts = Vector(
      "fixtures/eras/blocks.hex",
      "fixtures/chain-dep/blocks.hex",
      "fixtures/preview/babbage-mint.hex",
      "fixtures/preview/babbage-transition.hex",
      "fixtures/preview/empty-redeemers.hex",
      "fixtures/preview/deeply-nested-datum.hex"
    ).map { f =>
      val txs = babbageTxs(f)
      txs.zipWithIndex.foreach((t, i) => sameParts(t, s"$f transaction $i"))
      f -> txs.size
    }.toMap
    // every Babbage transaction the repository's blocks hold, thirteen distinct ones (the Preview
    // chunks of `fixtures/haskell-immutable` hold Alonzo blocks only, and the Babbage blocks of the
    // era transition none)
    assertEquals(
      counts,
      Map(
        "fixtures/eras/blocks.hex" -> 2,
        "fixtures/chain-dep/blocks.hex" -> 2,
        "fixtures/preview/babbage-mint.hex" -> 1,
        "fixtures/preview/babbage-transition.hex" -> 0,
        "fixtures/preview/empty-redeemers.hex" -> 6,
        "fixtures/preview/deeply-nested-datum.hex" -> 2
      )
    )
    assertEquals(counts.keys.toVector.flatMap(babbageTxs).map(_.id).distinct.size, 13)
  }

  /** The transactions of the reference's Babbage refusal corpora, `[era, #6.24(bytes .cbor tx)]`:
    * refused by the rules, so each decodes.
    */
  private lazy val refusalTxs: Vector[(String, Bytes)] =
    val corpora = Files
      .list(Path.of("fixtures/reference/refusals"))
      .iterator()
      .asScala
      .map(_.getFileName.toString)
      .filter(_.startsWith("babbage"))
      .toVector
      .sorted
    val txs = corpora.flatMap(d =>
      lines(s"fixtures/reference/refusals/$d/cases/cases.jsonl").map(j =>
        Cbor.decode(hex(str(j, "item"))).toOption.map(_.value) match
          case Some(Value.Arr(Vector(_, tagged))) =>
            tagged.value match
              case Value.Tag(_, inner) =>
                inner.value match
                  case Value.ByteString(b) => (d, b)
                  case other               => fail(s"not a byte string: $other")
              case other => fail(s"not tagged: $other")
          case other => fail(s"not a submitted transaction: $other")
      )
    )
    txs

  /** A whole transaction read both ways, at every layout. */
  private def sameWhole(raw: Bytes, what: String): Unit =
    val flat = FlatTx.fromEnvelope(raw, Era.Babbage).fold(e => fail(s"$what: $e"), identity)
    layouts.foreach { l =>
      val era = BabbageTx.fromEnvelope(raw, l).fold(e => fail(s"$what at $l: $e"), identity)
      sameTx(era, flat, s"$what at $l")
    }

  test("the Babbage transactions of the reference's refusal corpora read alike both ways") {
    val txs = refusalTxs
    txs.foreach((d, raw) => sameWhole(raw, d))
    assertEquals(
      txs.groupMapReduce(_._1)(_ => 1)(_ + _),
      Map(
        "babbage-bootstrap-attributes" -> 1,
        "babbage-certificate-order" -> 3,
        "babbage-collateral" -> 3,
        "babbage-devnet" -> 24,
        "babbage-ppup" -> 1
      )
    )
  }

  /** The Babbage transactions of the v1-context corpus (`fixtures/reference/v1-context`), their
    * parts with their flags, at protocol versions 7 and 8, three of each marked invalid.
    */
  private lazy val contextTxs: Vector[Block.Transaction] =
    Vector("babbage-pv7", "babbage-pv8").flatMap { f =>
      val rows = parser
        .parse(Files.readString(Path.of(s"fixtures/reference/v1-context/$f.json")))
        .fold(e => fail(e.message), identity)
        .asArray
        .getOrElse(fail(s"$f: not an array"))
      rows.map(j =>
        Block.Transaction(
          hex(str(j, "body")),
          hex(str(j, "witnesses")),
          j.hcursor.get[Option[String]]("auxiliary").fold(e => fail(e.message), identity).map(hex),
          j.hcursor.get[Boolean]("isValid").fold(e => fail(e.message), identity)
        )
      )
    }

  /** The whole Babbage transactions the Plutus admission corpora submitted
    * (`fixtures/reference/plutus-admission`), each once.
    */
  private lazy val admissionTxs: Vector[Bytes] =
    Vector("admissions", "rebased-admissions")
      .flatMap(f =>
        parser
          .parse(Files.readString(Path.of(s"fixtures/reference/plutus-admission/$f.json")))
          .fold(e => fail(e.message), identity)
          .hcursor
          .downField("observations")
          .as[Vector[Json]]
          .fold(e => fail(e.message), identity)
          .filter(j => str(j, "era") == "babbage")
          .map(j => hex(str(j, "tx")))
      )
      .distinct

  test(
    "the Babbage transactions of the v1-context and Plutus admission corpora read alike both ways"
  ) {
    contextTxs.zipWithIndex.foreach((t, i) => sameParts(t, s"v1-context transaction $i"))
    admissionTxs.zipWithIndex.foreach((raw, i) => sameWhole(raw, s"admission transaction $i"))
    assertEquals(
      (contextTxs.size, contextTxs.count(!_.isValid), admissionTxs.size),
      (22, 6, 32)
    )
  }

  /** The reference's verdicts on Babbage auxiliary data (`fixtures/reference/auxiliary-scripts`,
    * the `oracle` 's `decoded`), at protocol versions 7 and 8: Babbage's decoder and the flat
    * reader at the row's version agree with them.
    */
  test("every Babbage row of the auxiliary-data corpus is read as the reference reads it") {
    given ByteStrings = ByteStrings.of(DecoderVersion.of(Era.Babbage))
    val rows = parser
      .parse(Files.readString(Path.of("fixtures/reference/auxiliary-scripts/auxiliary.json")))
      .fold(e => fail(e.message), identity)
      .asArray
      .getOrElse(fail("not an array"))
      .filter(j => str(j, "era") == "Babbage")
    val verdicts = rows.map { j =>
      val raw = hex(str(j, "auxiliary"))
      val decoded =
        j.hcursor.downField("oracle").get[Boolean]("decoded").fold(e => fail(e.message), identity)
      val version = j.hcursor.get[Int]("protocolMajor").fold(e => fail(e.message), identity)
      val era = BabbageTxAuxData.decode(raw)
      val what = str(j, "name")
      assertEquals(era.isRight, decoded, s"$what: $era")
      assertEquals(AuxiliaryScripts.read(raw, Era.Babbage, version).isRight, decoded, what)
      era.foreach(sameAux(_, raw, what))
      decoded
    }
    // the rows, and those the reference decodes
    assertEquals((verdicts.size, verdicts.count(identity)), (140, 46))
  }

  // ---- the decoder corpora

  private def lines(path: String): Vector[Json] =
    Files
      .readAllLines(Path.of(path))
      .asScala
      .toVector
      .filter(_.trim.nonEmpty)
      .map(l => parser.parse(l).fold(e => fail(e.message), identity))

  private def str(j: Json, name: String): String =
    j.hcursor.get[String](name).fold(e => fail(s"$name: ${e.message}"), identity)

  /** What a part of a transaction is read as. */
  private enum Part derives CanEqual:
    case Body, Witnesses, Transaction, Auxiliary

  /** A Babbage row of a corpus: what it is read as, its bytes, the reference's verdict, the case a
    * refusal must be where the corpus names one, and the reference's failure.
    */
  private final case class Row(
      corpus: String,
      name: String,
      part: Part,
      raw: Bytes,
      accepted: Boolean,
      expect: Option[Refusal],
      failure: Option[String],
      version: DecoderVersion
  )

  private val typed: Vector[Corpus] = Corpus.values.toVector

  private def typedRows: Vector[Row] =
    typed.flatMap(c =>
      ReferenceCorpus
        .load(c)
        .fold(e => fail(s"${c.directory}: $e"), identity)
        .filter(_.era == Era.Babbage)
        .map(r =>
          val part = r.kind match
            case Kind.Body        => Part.Body
            case Kind.Witnesses   => Part.Witnesses
            case Kind.Transaction => Part.Transaction
          Row(
            c.directory,
            r.name,
            part,
            r.raw,
            r.accepted,
            r.expect,
            r.failure.map(_.toString),
            DecoderVersion.of(Era.Babbage)
          )
        )
    )

  // the corpora the typed loader does not read, each a `cases.jsonl` of rows with a verdict
  private val untyped: Vector[String] = Vector(
    "recd-detached",
    "tx-envelope",
    "bootstrap-duplicates",
    "bootstrap-chain-codes",
    "plutus-data-places",
    "address-headers",
    "era-decoders"
  )

  private def untypedRows: Vector[Row] =
    untyped.flatMap(c =>
      lines(s"fixtures/reference/$c/cases.jsonl")
        .filter(j => j.hcursor.get[String]("era").toOption.contains("babbage"))
        // a row recorded at a decoder version of Babbage's (7 or 8) is read at it; one recorded
        // at a later era's version (9 to 12) is not Babbage's to read
        .flatMap(j => recorded(j).map(j -> _))
        .flatMap((j, version) =>
          j.hcursor
            .get[String]("kind")
            .toOption
            .collect {
              case "body"        => Part.Body
              case "witnesses"   => Part.Witnesses
              case "transaction" => Part.Transaction
              case "auxiliary"   => Part.Auxiliary
            }
            .map(part =>
              Row(
                c,
                str(j, "name"),
                part,
                hex(str(j, "bytes")),
                j.hcursor.get[Boolean]("accepted").fold(e => fail(e.message), identity),
                None,
                j.hcursor.get[String]("failure").toOption,
                version
              )
            )
        )
    )

  /** The decoder version a row was recorded at, Babbage's blocks' when it names none; none when it
    * is a later era's. A version that is not a number fails the test.
    */
  private def recorded(j: Json): Option[DecoderVersion] =
    j.hcursor.downField("version").success match
      case None => Some(DecoderVersion.of(Era.Babbage))
      case Some(c) =>
        val major = c.as[Int].fold(e => fail(s"${str(j, "name")}: version ${e.message}"), identity)
        DecoderVersion.at(Era.Babbage, major) match
          case Right(v) => Some(v)
          case Left(_) =>
            assert(major > 8 && major <= 12, s"${str(j, "name")}: version $major")
            None

  /** The refusal of each way, or none. */
  private def refusals(row: Row): (Option[LedgerDecodeError], Vector[Option[EraTxError]]) =
    given ByteStrings = ByteStrings.of(DecoderVersion.of(Era.Babbage))
    row.part match
      case Part.Body =>
        val flat = FlatTxBody.decode(row.raw, Era.Babbage)
        val era = layouts.map(BabbageTxBody.decode(row.raw, _))
        flat.foreach(f => era.foreach(_.foreach(sameBody(_, f, row.name))))
        (flat.left.toOption, era.map(_.left.toOption))
      case Part.Witnesses =>
        val flat = FlatWitnesses.decode(row.raw, Era.Babbage, row.version)
        val era = BabbageTxWits.decodeAt(row.raw, row.version)
        flat.foreach(f => era.foreach(sameWits(_, f, row.name)))
        (flat.left.toOption, Vector(era.left.toOption))
      case Part.Transaction =>
        val flat = FlatTx.fromEnvelope(row.raw, Era.Babbage)
        val era = layouts.map(BabbageTx.fromEnvelope(row.raw, _))
        flat.foreach(f => era.foreach(_.foreach(sameTx(_, f, row.name))))
        (flat.left.toOption, era.map(_.left.toOption))
      case Part.Auxiliary =>
        val flat = AuxiliaryScripts.read(row.raw, Era.Babbage, 7)
        val era = BabbageTxAuxData.decode(row.raw)
        era.foreach(sameAux(_, row.raw, row.name))
        (flat.left.toOption, Vector(era.left.toOption))

  private def cause(e: EraTxError): Option[LedgerDecodeError] = e match
    case EraTxError.Decode(c) => Some(c)
    case _                    => None

  test("every Babbage row of the reference's decoder corpora is read alike both ways") {
    val rows = typedRows ++ untypedRows
    val results = rows.map(r => r -> refusals(r))
    results.foreach { case (r, (flat, era)) =>
      val what = s"${r.corpus}: ${r.name}"
      // the layouts agree, the two ways agree, and both agree with the reference
      assert(era.forall(_ == era.head), s"$what: $era")
      assertEquals(era.head.isEmpty, flat.isEmpty, s"$what: flat $flat, era ${era.head}")
      assertEquals(flat.isEmpty, r.accepted, s"$what: flat $flat")
      // a refusal of the era's decoder is a decoder's, and the case the reference's recording names
      era.head.foreach(e => assert(cause(e).isDefined, s"$what: $e"))
    }
    // the case the reference's recording names, but for the two below, whose case the recording
    // names by the flat decoder's
    results.foreach { case (r, (_, era)) =>
      if !updateKeys.contains(r.name) then
        r.expect.foreach(x =>
          assertEquals(
            era.head.flatMap(cause).flatMap(Refusal.of),
            Some(x),
            s"${r.corpus}: ${r.name}"
          )
        )
    }
    // the rows whose cause the two ways name differently: the era's is the reference's order
    // the refusals that differ from the flat decoder's, whole
    val differ = results.collect {
      case (r, (Some(f), era)) if era.head.flatMap(cause) != Some(f) =>
        r.name -> (f, era.head.flatMap(cause))
    }.toMap
    // an update proposal's value is read by the typed update's decoder, which names the site of a
    // refused value as the update's or the units', where the flat decoder names the parameter; the
    // reference names neither ("expected word"), and the refusal's case is the same
    val sites = differ.filter((name, _) =>
      !updateKeys.contains(name) && name != countedUnits && !envelopeNulls.contains(name)
    )
    assert(sites.keys.forall(_.startsWith("babbage update value: ")), sites.keys.toString)
    // each the flat decoder's refusal, its site the update's
    sites.foreach { (name, d) =>
      assertEquals(d._2, Some(atUpdate(d._1)), name)
    }
    assertEquals(differ.keySet -- sites.keySet, updateKeys + countedUnits ++ envelopeNulls)
    // four items whose third is not a flag: the reference reads the third as the auxiliary data
    // and then refuses the record's size ("Size mismatch when decoding Record AlonzoTx. Expected
    // 4, but found 3", "Excess terms in array" for the indefinite one), where the flat decoder
    // asks for a flag in its place
    envelopeNulls.foreach(name =>
      assertEquals(
        differ(name)._2,
        Some(LedgerDecodeError.ArrayLength(DecodeField.Transaction, 3, 4)),
        name
      )
    )
    assertEquals(sites.size, 51)
    val differCause = differ.map((name, d) => name -> d._2)
    // the update's own decoder refuses a parameter written twice with the reference's key
    // ("Duplicate key: 0 while decoding type PParamsUpdate BabbageEra", 16 for the wide one), where
    // the flat decoder's refusal names no key
    assertEquals(
      differCause("babbage update: repeated key, same value"),
      Some(
        LedgerDecodeError.PParamsCodec(
          PParamsDecodeError.DuplicateKey(DecodeField.ParameterUpdate, 0)
        )
      )
    )
    assertEquals(
      differCause("babbage update: repeated key, other value, written wide"),
      Some(
        LedgerDecodeError.PParamsCodec(
          PParamsDecodeError.DuplicateKey(DecodeField.ParameterUpdate, 16)
        )
      )
    )
    // `{5: [[0, 0, 0, 9f ff]], 1: 2}`: the reference reads the redeemer's units as a record that
    // takes the next items as its fields, and runs out of input ("end of input" at byte 11); the
    // detached-record walk finds no such record, so both ways refuse the parse as it stands, the era
    // decoder at the redeemer's units, read first as the reference reads them, the flat one at the
    // native scripts' key, which it reads first
    assertEquals(
      differCause(countedUnits),
      Some(LedgerDecodeError.IndefiniteLength(DecodeField.ExUnits))
    )
    // the rows recorded at Babbage's protocol version 8, read at it: three witness sets
    assertEquals(
      rows.filter(_.version != DecoderVersion.of(Era.Babbage)).map(_.part),
      Vector.fill(3)(Part.Witnesses)
    )
    // the rows read, by part and verdict
    assertEquals(
      rows.groupMapReduce(r => (r.part, r.accepted))(_ => 1)(_ + _),
      Map(
        (Part.Body, true) -> 190,
        (Part.Body, false) -> 216,
        (Part.Witnesses, true) -> 63,
        (Part.Witnesses, false) -> 41,
        (Part.Transaction, true) -> 49,
        (Part.Transaction, false) -> 14,
        (Part.Auxiliary, true) -> 2,
        (Part.Auxiliary, false) -> 1
      )
    )
  }

  /** A refusal of an update proposal's value with its site the typed update decoder's: the update
    * (`parameter update`), or the units (`ex units`) of an execution-units value.
    */
  private def atUpdate(e: LedgerDecodeError): LedgerDecodeError =
    val update = DecodeField.ParameterUpdate
    e match
      case LedgerDecodeError.Expected(
            DecodeField.MaxTxExUnits | DecodeField.MaxBlockExUnits,
            shape,
            v
          ) =>
        LedgerDecodeError.Expected(DecodeField.ExUnits, shape, v)
      case LedgerDecodeError.Expected(_, shape, v) => LedgerDecodeError.Expected(update, shape, v)
      case LedgerDecodeError.NumberOutOfRange(
            DecodeField.MaxTxExUnits | DecodeField.MaxBlockExUnits,
            v,
            lo,
            hi
          ) =>
        LedgerDecodeError.NumberOutOfRange(DecodeField.ExUnits, v, lo, hi)
      case LedgerDecodeError.NumberOutOfRange(_, v, lo, hi) =>
        LedgerDecodeError.NumberOutOfRange(update, v, lo, hi)
      case LedgerDecodeError.RationalOutOfBounds(_, b) =>
        LedgerDecodeError.RationalOutOfBounds(update, b)
      case LedgerDecodeError.ExpectedTag(_, t, v) => LedgerDecodeError.ExpectedTag(update, t, v)
      case LedgerDecodeError.ZeroDenominator(_)   => LedgerDecodeError.ZeroDenominator(update)
      case LedgerDecodeError.ArrayLength(
            DecodeField.MaxTxExUnits | DecodeField.MaxBlockExUnits,
            x,
            y
          ) =>
        LedgerDecodeError.ArrayLength(DecodeField.ExUnits, x, y)
      case LedgerDecodeError.ArrayLength(_, x, y) => LedgerDecodeError.ArrayLength(update, x, y)
      case LedgerDecodeError.IndefiniteLength(_) =>
        LedgerDecodeError.IndefiniteLength(DecodeField.ExUnits)
      case other => other

  private val envelopeNulls: Set[String] = Set(
    "babbage transaction four items with null third",
    "babbage transaction four items with auxiliary data third",
    "babbage transaction indefinite four items with null third"
  )

  private val updateKeys: Set[String] = Set(
    "babbage update: repeated key, same value",
    "babbage update: repeated key, other value, written wide"
  )

  private val countedUnits: String =
    "babbage witnesses redeemer units in a witness set that counts their fields"

  // ---- what the comparison sees

  /** Three faults of a decoder that the parts' comparison alone (the body and the witness set)
    * passes, each made by reading bytes that differ from the recorded ones only where the fault is:
    * the whole transaction's comparison ([[BabbageTestKit.txDifferences]]) names each.
    */
  test("the comparison sees a lost auxiliary data, a forced flag and a lost metadata label") {
    import xyz.kd5ujc.scarab.ledger.era.conway.ConwayTestKit.Cb.*
    def envelope(name: String): Bytes =
      lines("fixtures/reference/tx-envelope/cases.jsonl")
        .find(j => str(j, "name") == name)
        .map(j => hex(str(j, "bytes")))
        .getOrElse(fail(s"no row $name"))
    def items(raw: Bytes): Vector[Node] =
      Cbor.decode(raw).toOption.map(_.value) match
        case Some(Value.Arr(xs)) => xs
        case other               => fail(s"not a list: $other")
    // the faulty reading against the flat decoder's of the recorded bytes, at every layout: the
    // differences of the parts, and of the whole transaction
    def differences(recorded: Bytes, faulty: Bytes): Vector[(Vector[String], Vector[String])] =
      val flat = FlatTx.fromEnvelope(recorded, Era.Babbage).fold(e => fail(e.toString), identity)
      layouts.map { l =>
        val era = BabbageTx.fromEnvelope(faulty, l).fold(e => fail(e.toString), identity)
        (
          BabbageTestKit.bodyDifferences(era.body, flat.body) ++
            BabbageTestKit.witsDifferences(era.wits, flat.witnesses),
          txDifferences(era, flat)
        )
      }
    def seen(recorded: Bytes, faulty: Bytes, field: String): Unit =
      assert(differences(recorded, recorded).forall(_ == (Vector.empty, Vector.empty)))
      differences(recorded, faulty).foreach { (parts, whole) =>
        assertEquals(parts, Vector.empty)
        assert(whole.exists(_.startsWith(field)), whole.toString)
      }
    // the tagged auxiliary data of a transaction of three items, dropped
    val tagged = envelope("babbage transaction without its flag with tagged auxiliary data")
    val xs = items(tagged)
    assert(xs(2).value.isInstanceOf[Value.Tag], xs(2).value.toString)
    seen(tagged, arr(xs(0).original, xs(1).original, `null`), "auxiliary data's bytes")
    // the false flag read as true
    val invalid = envelope("babbage transaction with its flag false")
    val ys = items(invalid)
    assertEquals(ys(2).value, Value.Bool(false))
    seen(invalid, arr(ys(0).original, ys(1).original, `true`, ys(3).original), "phase-two flag")
    // the first label of a recorded transaction's metadata, dropped
    val withMetadata = (recordedFiles.flatMap(babbageTxs) ++ contextTxs)
      .find(t =>
        BabbageTx.decode(t, StateLayout.Node11_1).exists(_.auxData.exists(_.metadata.nonEmpty))
      )
      .getOrElse(fail("no recorded transaction with metadata"))
    val aux = Cbor.decode(withMetadata.auxiliaryData.get).toOption.get
    def dropFirst(m: Node): Bytes = m.value match
      case Value.Map(kvs) => map(kvs.tail.map((k, v) => k.original -> v.original)*)
      case other          => fail(s"not a metadata map: $other")
    val lost = aux.value match
      case Value.Map(_)            => dropFirst(aux)
      case Value.Arr(Vector(m, n)) => arr(dropFirst(m), n.original)
      case Value.Tag(t, inner) =>
        inner.value match
          case Value.Map(kvs) =>
            tag(
              t.toInt,
              map(
                kvs.map((k, v) =>
                  k.original -> (if k.value == Value.UInt(0) then dropFirst(v) else v.original)
                )*
              )
            )
          case other => fail(s"not a map: $other")
      case other => fail(s"not auxiliary data: $other")
    val whole = FlatTx.envelope(withMetadata)
    seen(
      whole,
      FlatTx.envelope(withMetadata.copy(auxiliaryData = Some(lost))),
      "auxiliary data metadata"
    )
  }

  // ---- mutants

  private val recordedFiles: Vector[String] = Vector(
    "fixtures/eras/blocks.hex",
    "fixtures/chain-dep/blocks.hex",
    "fixtures/preview/babbage-mint.hex",
    "fixtures/preview/empty-redeemers.hex",
    "fixtures/preview/deeply-nested-datum.hex"
  )

  /** Every whole transaction above, cut short, with a byte flipped, a byte deleted and a byte
    * doubled at each of up to 64 places spread over its bytes: the two ways accept and refuse alike
    * at every layout, a refusal of the era's decoder is a decoder's, and an accepted mutant
    * projects to the flat values.
    */
  test("mutants of every Babbage transaction are accepted and refused alike both ways") {
    val seeds = (recordedFiles.flatMap(babbageTxs) ++ contextTxs).map(FlatTx.envelope) ++
      refusalTxs.map(_._2) ++ admissionTxs
    def mutants(raw: Bytes): Vector[Bytes] =
      val bytes = raw.value
      val step = math.max(1, bytes.size / 64)
      (0 until bytes.size by step).toVector.flatMap { i =>
        Vector(
          bytes.take(i),
          bytes.updated(i, (bytes(i) ^ 0x01).toByte),
          bytes.patch(i, Nil, 1),
          bytes.patch(i, Vector(bytes(i)), 0)
        ).map(Bytes(_))
      }
    val outcomes = seeds.flatMap(mutants).map { raw =>
      val flat = FlatTx.fromEnvelope(raw, Era.Babbage)
      val era = layouts.map(BabbageTx.fromEnvelope(raw, _))
      val what = raw.hex
      assert(era.forall(_.isRight == era.head.isRight), what)
      assertEquals(era.head.isRight, flat.isRight, s"$what: flat $flat, era ${era.head}")
      era.head.left.foreach(e => assert(cause(e).isDefined, s"$what: $e"))
      flat.foreach(f => era.foreach(_.foreach(sameTx(_, f, what))))
      flat.isRight
    }
    assertEquals(seeds.size, 99)
    // the mutants, and those both ways accept
    assertEquals((outcomes.size, outcomes.count(identity)), (27712, 5990))
  }
