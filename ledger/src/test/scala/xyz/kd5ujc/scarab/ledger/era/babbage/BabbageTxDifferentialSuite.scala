// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.babbage

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import io.circe.{Json, parser}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Value}
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
  * `AuxiliaryScripts.read`, `Tx.fromEnvelope`), the path the node runs for Babbage: on every
  * recorded Babbage transaction and on every Babbage row of the reference's decoder corpora, at
  * every layout, the two accept and refuse alike; an accepted transaction has the same id, body
  * hash and original bytes, and projects to the flat values field by field (a projection local to
  * this suite); a refused row is refused with the case the reference's recording names, where it
  * names one. The era decoders read a map's keys as the reference does, as written, where the flat
  * decoder reads them in a fixed order; the rows whose cause differs between the two for that
  * reason are listed, each with the reference's own failure.
  */
class BabbageTxDifferentialSuite extends FunSuite:
  import BabbageTestKit.{sameAux, sameBody, sameWits}

  private val layouts: Vector[StateLayout] = StateLayout.values.toVector

  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.toString), identity)

  /** A transaction of a block read both ways, at every layout. */
  private def sameTx(t: Block.Transaction, what: String): Unit =
    val flat = FlatTx.fromBlock(t, Era.Babbage).fold(e => fail(s"$what: flat $e"), identity)
    layouts.foreach { l =>
      val era = BabbageTx.decode(t, l).fold(e => fail(s"$what at $l: $e"), identity)
      sameBody(era.body, flat.body, what)
      sameWits(era.wits, flat.witnesses, what)
      assertEquals(era.auxData.map(_.raw), t.auxiliaryData, what)
      era.auxData.foreach(sameAux(_, t.auxiliaryData.get, what))
      assertEquals(era.isValid, xyz.kd5ujc.scarab.ledger.era.IsPhase2Valid.of(flat.isValid), what)
      assertEquals(era.sizeTxForFeeCalculation, flat.size, what)
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
      txs.zipWithIndex.foreach((t, i) => sameTx(t, s"$f transaction $i"))
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

  test("the Babbage transactions of the reference's refusal corpora read alike both ways") {
    val txs = refusalTxs
    txs.foreach { (d, raw) =>
      val flat = FlatTx.fromEnvelope(raw, Era.Babbage).fold(e => fail(s"$d: $e"), identity)
      layouts.foreach { l =>
        val era = BabbageTx.fromEnvelope(raw, l).fold(e => fail(s"$d at $l: $e"), identity)
        sameBody(era.body, flat.body, d)
        sameWits(era.wits, flat.witnesses, d)
      }
    }
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
      failure: Option[String]
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
          Row(c.directory, r.name, part, r.raw, r.accepted, r.expect, r.failure.map(_.toString))
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
        // a row recorded at another decoder version than Babbage's blocks are read at is not one
        // the era decoders read
        .filter(j =>
          j.hcursor.get[String]("era").toOption.contains("babbage") &&
            j.hcursor.get[Int]("version").toOption.forall(_ == 7)
        )
        .flatMap(j =>
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
                j.hcursor.get[String]("failure").toOption
              )
            )
        )
    )

  /** The refusal of each way, or none. */
  private def refusals(row: Row): (Option[LedgerDecodeError], Vector[Option[EraTxError]]) =
    given ByteStrings = ByteStrings.of(DecoderVersion.of(Era.Babbage))
    row.part match
      case Part.Body =>
        val flat = FlatTxBody.decode(row.raw, Era.Babbage)
        val era = layouts.map(BabbageTxBody.decode(row.raw, _))
        (flat, era) match
          case (Right(f), Right(e) +: _) => sameBody(e, f, row.name)
          case _                         => ()
        (flat.left.toOption, era.map(_.left.toOption))
      case Part.Witnesses =>
        val flat = FlatWitnesses.decode(row.raw, Era.Babbage)
        val era = BabbageTxWits.decode(row.raw)
        (flat, era) match
          case (Right(f), Right(e)) => sameWits(e, f, row.name)
          case _                    => ()
        (flat.left.toOption, Vector(era.left.toOption))
      case Part.Transaction =>
        val flat = FlatTx.fromEnvelope(row.raw, Era.Babbage)
        val era = layouts.map(BabbageTx.fromEnvelope(row.raw, _))
        (flat, era) match
          case (Right(f), Right(e) +: _) =>
            sameBody(e.body, f.body, row.name)
            sameWits(e.wits, f.witnesses, row.name)
          case _ => ()
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
    val differ = results.collect {
      case (r, (Some(f), era)) if era.head.flatMap(cause).flatMap(Refusal.of) != Refusal.of(f) =>
        r.name -> era.head.flatMap(cause)
    }.toMap
    assertEquals(differ.keySet, updateKeys + countedUnits)
    // the update's own decoder refuses a parameter written twice with the reference's key
    // ("Duplicate key: 0 while decoding type PParamsUpdate BabbageEra", 16 for the wide one), where
    // the flat decoder's refusal names no key
    assertEquals(
      differ("babbage update: repeated key, same value"),
      Some(
        LedgerDecodeError.PParamsCodec(
          PParamsDecodeError.DuplicateKey(DecodeField.ParameterUpdate, 0)
        )
      )
    )
    assertEquals(
      differ("babbage update: repeated key, other value, written wide"),
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
      differ(countedUnits),
      Some(LedgerDecodeError.IndefiniteLength(DecodeField.ExUnits))
    )
    // the rows read, by part and verdict
    assertEquals(
      rows.groupMapReduce(r => (r.part, r.accepted))(_ => 1)(_ + _),
      Map(
        (Part.Body, true) -> 190,
        (Part.Body, false) -> 216,
        (Part.Witnesses, true) -> 60,
        (Part.Witnesses, false) -> 41,
        (Part.Transaction, true) -> 49,
        (Part.Transaction, false) -> 14,
        (Part.Auxiliary, true) -> 2,
        (Part.Auxiliary, false) -> 1
      )
    )
  }

  private val updateKeys: Set[String] = Set(
    "babbage update: repeated key, same value",
    "babbage update: repeated key, other value, written wide"
  )

  private val countedUnits: String =
    "babbage witnesses redeemer units in a witness set that counts their fields"

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
    val seeds = recordedFiles.flatMap(babbageTxs).map(FlatTx.envelope) ++ refusalTxs.map(_._2)
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
      (flat, era.head) match
        case (Right(f), Right(e)) =>
          sameBody(e.body, f.body, what)
          sameWits(e.wits, f.witnesses, what)
        case _ => ()
      flat.isRight
    }
    assertEquals(seeds.size, 45)
    // the mutants, and those both ways accept
    assertEquals((outcomes.size, outcomes.count(identity)), (12640, 2798))
  }
