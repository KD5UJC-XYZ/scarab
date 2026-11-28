// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.alonzo

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

/** Alonzo's era decoders ([[AlonzoTxBody.decode]], [[AlonzoTxWits.decode]],
  * [[AlonzoTxAuxData.decode]], [[AlonzoTx.decode]] and [[AlonzoTx.fromEnvelope]]) against the flat
  * decoder at Alonzo (`Tx.fromBlock`, `TxBody.decode`, `Witnesses.decode`, `AuxiliaryScripts.read`,
  * `Tx.fromEnvelope`), the path the node runs for Alonzo, at every layout: on every Alonzo
  * transaction the repository holds (the era walk, the chain-dependent capture, the Preview Alonzo
  * blocks and ImmutableDB chunks, and the reference's refusal, v1-context, context-matrix and
  * Plutus admission corpora) and byte mutants of their distinct bodies, witness sets and whole
  * transactions, on the Alonzo rows of the reference's decoder corpora (those recorded at a version
  * of Alonzo's, read at it) and on the reference's verdicts on Alonzo auxiliary data, the two
  * accept and refuse alike. An accepted transaction is compared whole
  * ([[AlonzoTestKit.txDifferences]]): its body, witness set, flag, fee size and auxiliary data,
  * against the flat readers' reading of the same bytes; a refused mutant by its verdict alone. A
  * refused row is refused with the case the reference's recording names, where it names one, and
  * the refusals that differ from the flat decoder's, compared whole, are listed.
  */
class AlonzoTxDifferentialSuite extends FunSuite:
  import AlonzoTestKit.{sameAux, sameBody, sameTx, sameWits, txDifferences}

  private val layouts: Vector[StateLayout] = StateLayout.values.toVector

  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.toString), identity)

  private def lines(path: String): Vector[Json] =
    Files
      .readAllLines(Path.of(path))
      .asScala
      .toVector
      .filter(_.trim.nonEmpty)
      .map(l => parser.parse(l).fold(e => fail(e.message), identity))

  private def json(path: String): Json =
    parser.parse(Files.readString(Path.of(path))).fold(e => fail(e.message), identity)

  private def str(j: Json, name: String): String =
    j.hcursor.get[String](name).fold(e => fail(s"$name: ${e.message}"), identity)

  private def items(raw: Bytes): Vector[Node] =
    Cbor.decode(raw).toOption.map(_.value) match
      case Some(Value.Arr(xs)) => xs
      case other               => fail(s"not a list: $other")

  // ---- the recorded transactions: those of blocks as their parts, the others whole

  private val blockFiles: Vector[String] = Vector(
    "fixtures/eras/blocks.hex",
    "fixtures/chain-dep/blocks.hex",
    "fixtures/preview/alonzo-first-20.hex",
    "fixtures/preview/alonzo-txs.hex"
  )

  private def alonzoTxs(file: String): Vector[Block.Transaction] =
    EraCorpus
      .shelleyBlocks(Vector(file))
      .filter(_.era == Era.Alonzo)
      .flatMap(_.transactions)

  /** The transactions of the Preview ImmutableDB chunks, cut by the lengths `expected-blocks.tsv`
    * lists, in order: each block a `[era tag, block]`, all of them Alonzo's.
    */
  private lazy val immutableTxs: Vector[Block.Transaction] =
    val dir = "fixtures/haskell-immutable/preview"
    val lengths = Files
      .readAllLines(Path.of(s"$dir/expected-blocks.tsv"))
      .asScala
      .toVector
      .filter(_.trim.nonEmpty)
      .map(_.split("\t").last.toInt)
    val chunks = Bytes.fromArray(
      Vector("00000", "00001", "00002")
        .flatMap(c => Files.readAllBytes(Path.of(s"$dir/immutable/$c.chunk")).toVector)
        .toArray
    )
    val (blocks, rest) = lengths.foldLeft((Vector.empty[Bytes], chunks)) { case ((acc, r), n) =>
      (acc :+ r.take(n), r.drop(n))
    }
    assertEquals(rest.size, 0)
    blocks.flatMap { raw =>
      Block.decodeShelley(raw) match
        case Right(b: Block.Shelley) =>
          assertEquals(b.era, Era.Alonzo)
          b.transactions
        case other => fail(s"not a Shelley-based block: $other")
    }

  /** The Alonzo transactions of the v1-context corpus, their parts with their flags, at protocol
    * versions 5 and 6.
    */
  private lazy val contextTxs: Vector[Block.Transaction] =
    Vector("alonzo-pv5", "alonzo-pv6").flatMap { f =>
      json(s"fixtures/reference/v1-context/$f.json").asArray
        .getOrElse(fail(s"$f: not an array"))
        .map(j =>
          Block.Transaction(
            hex(str(j, "body")),
            hex(str(j, "witnesses")),
            j.hcursor
              .get[Option[String]]("auxiliary")
              .fold(e => fail(e.message), identity)
              .map(hex),
            j.hcursor.get[Boolean]("isValid").fold(e => fail(e.message), identity)
          )
        )
    }

  /** The transactions of the reference's Alonzo refusal corpora, `[era, #6.24(bytes .cbor tx)]`:
    * refused by the rules, so each decodes.
    */
  private lazy val refusalTxs: Vector[(String, Bytes)] =
    Files
      .list(Path.of("fixtures/reference/refusals"))
      .iterator()
      .asScala
      .map(_.getFileName.toString)
      .filter(_.startsWith("alonzo"))
      .toVector
      .sorted
      .flatMap(d =>
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

  /** The whole transactions of the Alonzo rows of the reference's context matrix. */
  private lazy val matrixTxs: Vector[Bytes] =
    json("fixtures/reference/context-matrix/matrix.json").asArray
      .getOrElse(fail("matrix: not an array"))
      .filter(j => str(j, "era") == "Alonzo")
      .map(j => hex(str(j, "transaction")))

  /** The whole Alonzo transactions the Plutus admission corpora submitted, each once. */
  private lazy val admissionTxs: Vector[Bytes] =
    Vector("admissions", "rebased-admissions")
      .flatMap(f =>
        json(s"fixtures/reference/plutus-admission/$f.json").hcursor
          .downField("observations")
          .as[Vector[Json]]
          .fold(e => fail(e.message), identity)
          .filter(j => str(j, "era") == "alonzo")
          .map(j => hex(str(j, "tx")))
      )
      .distinct

  /** Every recorded Alonzo transaction as its parts (a block's), or whole (a submission), by
    * source.
    */
  private lazy val partTxs: Vector[(String, Block.Transaction)] =
    blockFiles.flatMap(f => alonzoTxs(f).map(f -> _)) ++
      immutableTxs.map("fixtures/haskell-immutable/preview" -> _) ++
      contextTxs.map("v1-context" -> _)

  private lazy val wholeTxs: Vector[(String, Bytes)] =
    refusalTxs ++ matrixTxs.map("context-matrix" -> _) ++ admissionTxs.map("plutus-admission" -> _)

  /** A transaction of a block read both ways, at every layout, and the same transaction submitted
    * whole.
    */
  private def sameParts(t: Block.Transaction, what: String): Unit =
    val flat = FlatTx.fromBlock(t, Era.Alonzo).fold(e => fail(s"$what: flat $e"), identity)
    layouts.foreach { l =>
      val era = AlonzoTx.decode(t, l).fold(e => fail(s"$what at $l: $e"), identity)
      sameTx(era, flat, s"$what at $l", l)
      val whole = FlatTx.envelope(t)
      assert(FlatTx.fromEnvelope(whole, Era.Alonzo).isRight, what)
      assertEquals(AlonzoTx.fromEnvelope(whole, l), Right(era), what)
    }

  /** A whole transaction read both ways, at every layout. */
  private def sameWhole(raw: Bytes, what: String): Unit =
    val flat = FlatTx.fromEnvelope(raw, Era.Alonzo).fold(e => fail(s"$what: $e"), identity)
    layouts.foreach { l =>
      val era = AlonzoTx.fromEnvelope(raw, l).fold(e => fail(s"$what at $l: $e"), identity)
      sameTx(era, flat, s"$what at $l", l)
      // its parts, read alone as a block's are
      val xs = items(raw)
      assertEquals(AlonzoTxBody.decode(xs(0).original, l), Right(era.body), what)
    }

  test("every recorded Alonzo transaction reads alike both ways, and projects to the flat values") {
    partTxs.zipWithIndex.foreach { case ((source, t), i) => sameParts(t, s"$source #$i") }
    wholeTxs.zipWithIndex.foreach { case ((source, raw), i) => sameWhole(raw, s"$source #$i") }
    // A6's sixty-nine rows, by source (`fixtures/preprod` and `fixtures/mainnet` hold no Alonzo
    // block)
    assertEquals(
      (partTxs.map(_._1) ++ wholeTxs.map(_._1)).groupMapReduce(identity)(_ => 1)(_ + _),
      Map(
        "fixtures/eras/blocks.hex" -> 2,
        "fixtures/chain-dep/blocks.hex" -> 2,
        "fixtures/preview/alonzo-first-20.hex" -> 2,
        "fixtures/preview/alonzo-txs.hex" -> 2,
        "fixtures/haskell-immutable/preview" -> 2,
        "alonzo-bootstrap-attributes" -> 1,
        "alonzo-collateral" -> 4,
        "alonzo-families" -> 8,
        "v1-context" -> 10,
        "context-matrix" -> 20,
        "plutus-admission" -> 16
      )
    )
    // of them, the distinct transactions, those marked invalid and those with auxiliary data
    val all = wholeEnvelopes
    assertEquals(
      (
        all.distinct.size,
        all.count(raw => items(raw).lift(2).exists(_.value == Value.Bool(false))),
        all.count(raw => items(raw).lastOption.exists(_.value != Value.Null))
      ),
      (57, 15, 8)
    )
  }

  /** Every recorded transaction, whole. */
  private lazy val wholeEnvelopes: Vector[Bytes] =
    partTxs.map((_, t) => FlatTx.envelope(t)) ++ wholeTxs.map(_._2)

  // ---- what the comparison sees

  /** Three faults of a decoder that the parts' comparison alone (the body and the witness set)
    * passes, each made by reading bytes that differ from the recorded ones only where the fault is:
    * the whole transaction's comparison ([[AlonzoTestKit.txDifferences]]) names each.
    */
  test("the comparison sees a lost auxiliary data, a forced flag and a lost metadata label") {
    import xyz.kd5ujc.scarab.ledger.era.conway.ConwayTestKit.Cb.*
    def envelope(name: String): Bytes =
      lines("fixtures/reference/tx-envelope/cases.jsonl")
        .find(j => str(j, "name") == name)
        .map(j => hex(str(j, "bytes")))
        .getOrElse(fail(s"no row $name"))
    // the faulty reading against the flat decoder's of the recorded bytes, at every layout: the
    // differences of the parts, and of the whole transaction
    def differences(recorded: Bytes, faulty: Bytes): Vector[(Vector[String], Vector[String])] =
      val flat = FlatTx.fromEnvelope(recorded, Era.Alonzo).fold(e => fail(e.toString), identity)
      layouts.map { l =>
        val era = AlonzoTx.fromEnvelope(faulty, l).fold(e => fail(e.toString), identity)
        (
          AlonzoTestKit.bodyDifferences(era.body, flat.body, l) ++
            AlonzoTestKit.witsDifferences(era.wits, flat.witnesses),
          txDifferences(era, flat, l)
        )
      }
    def seen(recorded: Bytes, faulty: Bytes, field: String): Unit =
      assert(differences(recorded, recorded).forall(_ == (Vector.empty, Vector.empty)))
      differences(recorded, faulty).foreach { (parts, whole) =>
        assertEquals(parts, Vector.empty)
        assert(whole.exists(_.startsWith(field)), whole.toString)
      }
    // the tagged auxiliary data of a transaction of three items, dropped
    val tagged = envelope("alonzo transaction without its flag with tagged auxiliary data")
    val xs = items(tagged)
    assert(xs(2).value.isInstanceOf[Value.Tag], xs(2).value.toString)
    seen(tagged, arr(xs(0).original, xs(1).original, `null`), "auxiliary data's bytes")
    // the false flag read as true
    val invalid = envelope("alonzo transaction with its flag false")
    val ys = items(invalid)
    assertEquals(ys(2).value, Value.Bool(false))
    seen(invalid, arr(ys(0).original, ys(1).original, `true`, ys(3).original), "phase-two flag")
    // the first label of a transaction's metadata, dropped: no recorded Alonzo transaction holds
    // metadata, so it is the envelope corpus's
    def hasMetadata(raw: Bytes) =
      AlonzoTx
        .fromEnvelope(raw, StateLayout.Node11_1)
        .exists(_.auxData.exists(_.metadata.nonEmpty))
    assert(!wholeEnvelopes.exists(hasMetadata))
    val withMetadata = envelope("alonzo transaction with its flag and auxiliary data")
    assert(hasMetadata(withMetadata))
    val parts = items(withMetadata)
    val aux = parts.last
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
    seen(withMetadata, arr((parts.init.map(_.original) :+ lost)*), "auxiliary data metadata")
  }

  /** Four faults of a reading that the comparison by bytes, by hash and by set alone passes, each
    * put into the era's values of a recorded transaction: the comparison of the values
    * (`BabbageTestKit.witsViewDifferences`, `BabbageTestKit.auxViewDifferences`,
    * [[AlonzoTestKit.redeemerDataDifferences]]) names each.
    */
  test(
    "the comparison sees a wrong script tree, a wrong datum or redeemer data and a lost Plutus script"
  ) {
    import AlonzoTestKit.{auxView, flatAuxView, witsView}
    import xyz.kd5ujc.scarab.ledger.era.babbage.BabbageTestKit.{auxViewDifferences, flatWitsView}
    import xyz.kd5ujc.scarab.ledger.NativeScript
    import xyz.kd5ujc.scarab.plutus.PlutusData
    def named(differences: Vector[String], field: String): Unit =
      assertEquals(differences.map(_.takeWhile(_ != ':')), Vector(field), differences.toString)
    val read = wholeEnvelopes.map(raw =>
      (
        AlonzoTx.fromEnvelope(raw, StateLayout.Node11_1).fold(e => fail(e.toString), identity),
        FlatTx.fromEnvelope(raw, Era.Alonzo).fold(e => fail(e.toString), identity)
      )
    )
    // a native script whose tree is not its bytes' tree: no recorded Alonzo witness set holds a
    // native script, so one is built, a signature of a key and a time lock
    import xyz.kd5ujc.scarab.ledger.era.conway.ConwayTestKit.Cb
    val built = AlonzoTestKit.encode(
      read.head._2.body,
      xyz.kd5ujc.scarab.ledger.era.babbage.BabbageTestKit.witnessSet(
        Vector.empty,
        native = Vector(
          Cb.arr(
            Cb.uint(1),
            Cb.arr(
              Cb.arr(
                Cb.uint(0),
                Cb.bytes(hash(7) ++ hash(7) ++ hash(7) ++ hash(7) ++ hash(7) ++ hash(7) ++ hash(7))
              ),
              Cb.arr(Cb.uint(4), Cb.uint(5))
            )
          )
        )
      )
    )
    assert(read.forall((era, _) => witsView(era.wits).scripts.forall(_._3.isEmpty)))
    val (native, nativeFlat) = (built.tx, built.flat)
    assert(witsView(native.wits).scripts.exists(_._3.isDefined))
    val view = witsView(native.wits)
    named(
      xyz.kd5ujc.scarab.ledger.era.babbage.BabbageTestKit.witsViewDifferences(
        view.copy(scripts =
          view.scripts.map((h, b, t) => (h, b, t.map(_ => NativeScript.Sig(Bytes.empty))))
        ),
        flatWitsView(nativeFlat.witnesses)
      ),
      "native script trees"
    )
    // a datum whose value is not its bytes' value, under the same hash
    val (datum, datumFlat) = read
      .find((era, _) => era.wits.datums.datums.nonEmpty)
      .getOrElse(fail("no recorded datum"))
    val withDatums = witsView(datum.wits)
    named(
      xyz.kd5ujc.scarab.ledger.era.babbage.BabbageTestKit.witsViewDifferences(
        withDatums.copy(datums =
          withDatums.datums.map((h, d) => h -> (d._1, PlutusData.I(BigInt(-1))))
        ),
        flatWitsView(datumFlat.witnesses)
      ),
      "datums"
    )
    // a redeemer whose data is not its bytes' data, its bytes and units the same
    val (redeemed, redeemedFlat) = read
      .find((era, _) => era.wits.redeemers.entries.nonEmpty)
      .getOrElse(fail("no recorded redeemer"))
    val data = AlonzoTestKit.redeemerData(redeemed.wits)
    assertEquals(AlonzoTestKit.redeemerDataDifferences(data, redeemedFlat.witnesses), Vector.empty)
    named(
      AlonzoTestKit.redeemerDataDifferences(
        data.map((k, _) => k -> PlutusData.I(BigInt(-1))),
        redeemedFlat.witnesses
      ),
      "redeemer data"
    )
    // a repeated Plutus script of auxiliary data kept once, its arrival as read
    import xyz.kd5ujc.scarab.ledger.era.conway.ConwayTestKit.Cb.*
    val repeated = tag(259, map(uint(2) -> arr(bytes(hash(1)), bytes(hash(1)))))
    val aux = AlonzoTxAuxData.decode(repeated).fold(e => fail(e.toString), identity)
    val flat = flatAuxView(repeated).fold(e => fail(e.toString), identity)
    assertEquals(auxViewDifferences(auxView(aux), flat), Vector.empty)
    named(
      auxViewDifferences(
        auxView(aux).copy(plutus = auxView(aux).plutus.map((l, ss) => l -> ss.distinct)),
        flat
      ),
      "Plutus scripts"
    )
  }

  private def hash(n: Int): Bytes = Bytes(Vector.fill(4)(n.toByte))

  // ---- mutants

  /** The bytes cut short, with a byte flipped, a byte deleted and a byte doubled at each of up to
    * 64 places spread over them.
    */
  private def mutants(raw: Bytes): Vector[Bytes] =
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

  private def cause(e: EraTxError): Option[LedgerDecodeError] = e match
    case EraTxError.Decode(c) => Some(c)
    case _                    => None

  /** A mutant's verdicts both ways, at every layout: alike, a refusal of the era's decoder a
    * decoder's, and an accepted one compared whole. Whether it was accepted.
    */
  private def alike[F, A](raw: Bytes)(
      flat: Bytes => Either[LedgerDecodeError, F],
      era: (Bytes, StateLayout) => Either[EraTxError, A]
  )(same: (A, F, String, StateLayout) => Unit): Boolean =
    val f = flat(raw)
    val e = layouts.map(era(raw, _))
    val what = raw.hex
    assert(e.forall(_.isRight == e.head.isRight), what)
    assertEquals(e.head.isRight, f.isRight, s"$what: flat $f, era ${e.head}")
    e.head.left.foreach(x => assert(cause(x).isDefined, s"$what: $x"))
    f.foreach(fv => layouts.zip(e).foreach((l, x) => x.foreach(same(_, fv, what, l))))
    f.isRight

  test("mutants of every distinct Alonzo body, witness set and transaction are judged alike") {
    val parts = wholeEnvelopes.map(items)
    val bodies = parts.map(_(0).original).distinct
    val wits = parts.map(_(1).original).distinct
    val txs = wholeEnvelopes.distinct
    val bodyOutcomes = bodies
      .flatMap(mutants)
      .map(alike(_)(FlatTxBody.decode(_, Era.Alonzo), AlonzoTxBody.decode)(sameBody))
    val witsOutcomes = wits
      .flatMap(mutants)
      .map(
        alike(_)(FlatWitnesses.decode(_, Era.Alonzo), (raw, _) => AlonzoTxWits.decode(raw))(
          (w, f, what, _) => sameWits(w, f, what)
        )
      )
    val txOutcomes = txs
      .flatMap(mutants)
      .map(alike(_)(FlatTx.fromEnvelope(_, Era.Alonzo), AlonzoTx.fromEnvelope)(sameTx))
    // the distinct parts, the mutants of each, and those both ways accept
    assertEquals((bodies.size, wits.size, txs.size), (50, 51, 57))
    assertEquals(
      Vector(bodyOutcomes, witsOutcomes, txOutcomes).map(o => (o.size, o.count(identity))),
      Vector((15296, 3309), (16156, 3649), (15848, 3384))
    )
  }

  // ---- the auxiliary data corpus

  /** The reference's verdicts on Alonzo auxiliary data (`fixtures/reference/auxiliary-scripts`, the
    * `oracle` 's `decoded`), 70 at each of Alonzo's protocol versions, 5 and 6: Alonzo's decoder
    * and the flat reader, each at the row's version, agree with them, and an accepted one is the
    * flat readers' reading of its bytes at that version.
    */
  test("every Alonzo row of the auxiliary-data corpus is read as the reference reads it") {
    given ByteStrings = ByteStrings.of(DecoderVersion.of(Era.Alonzo))
    val rows = json("fixtures/reference/auxiliary-scripts/auxiliary.json").asArray
      .getOrElse(fail("not an array"))
      .filter(j => str(j, "era") == "Alonzo")
    val verdicts = rows.map { j =>
      val raw = hex(str(j, "auxiliary"))
      val decoded =
        j.hcursor.downField("oracle").get[Boolean]("decoded").fold(e => fail(e.message), identity)
      val major = j.hcursor.get[Int]("protocolMajor").fold(e => fail(e.message), identity)
      val version =
        DecoderVersion.at(Era.Alonzo, major).fold(e => fail(s"version $major: $e"), identity)
      val era = AlonzoTxAuxData.decodeAt(raw, version)
      val what = str(j, "name")
      assertEquals(era.isRight, decoded, s"$what: $era")
      assertEquals(AuxiliaryScripts.read(raw, Era.Alonzo, major).isRight, decoded, what)
      era.foreach(a =>
        assertEquals(
          AlonzoTestKit
            .flatAuxView(raw, version)
            .map(
              xyz.kd5ujc.scarab.ledger.era.babbage.BabbageTestKit
                .auxViewDifferences(AlonzoTestKit.auxView(a), _)
            ),
          Right(Vector.empty),
          what
        )
      )
      version -> decoded
    }
    assertEquals(
      verdicts.groupMapReduce(_._1)(_ => 1)(_ + _),
      Map(DecoderVersion.of(Era.Alonzo) -> 70, DecoderVersion.high(Era.Alonzo) -> 70)
    )
    assertEquals(verdicts.count(_._2), 36)
  }

  // ---- the decoder corpora

  /** What a part of a transaction is read as. */
  private enum Part derives CanEqual:
    case Body, Witnesses, Transaction, Auxiliary

  /** An Alonzo row of a corpus: what it is read as, its bytes, the reference's verdict, the case a
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

  private def typedRows: Vector[Row] =
    Corpus.values.toVector.flatMap(c =>
      ReferenceCorpus
        .load(c)
        .fold(e => fail(s"${c.directory}: $e"), identity)
        .filter(_.era == Era.Alonzo)
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
            DecoderVersion.of(Era.Alonzo)
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
        .filter(j => j.hcursor.get[String]("era").toOption.contains("alonzo"))
        // a row recorded at a decoder version of Alonzo's (5 or 6) is read at it; one recorded at
        // a later era's version (7 to 12) is not Alonzo's to read
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

  /** The decoder version a row was recorded at, Alonzo's blocks' when it names none; none when it
    * is a later era's. A version that is not a number fails the test.
    */
  private def recorded(j: Json): Option[DecoderVersion] =
    j.hcursor.downField("version").success match
      case None => Some(DecoderVersion.of(Era.Alonzo))
      case Some(c) =>
        val major = c.as[Int].fold(e => fail(s"${str(j, "name")}: version ${e.message}"), identity)
        DecoderVersion.at(Era.Alonzo, major) match
          case Right(v) => Some(v)
          case Left(_) =>
            assert(major > 6 && major <= 12, s"${str(j, "name")}: version $major")
            None

  /** The refusal of each way, or none. */
  private def refusals(row: Row): (Option[LedgerDecodeError], Vector[Option[EraTxError]]) =
    given ByteStrings = ByteStrings.of(DecoderVersion.of(Era.Alonzo))
    row.part match
      case Part.Body =>
        val flat = FlatTxBody.decode(row.raw, Era.Alonzo, row.version)
        val era = layouts.map(AlonzoTxBody.decode(row.raw, _))
        flat.foreach(f =>
          layouts.zip(era).foreach((l, x) => x.foreach(sameBody(_, f, row.name, l)))
        )
        (flat.left.toOption, era.map(_.left.toOption))
      case Part.Witnesses =>
        val flat = FlatWitnesses.decode(row.raw, Era.Alonzo, row.version)
        val era = AlonzoTxWits.decodeAt(row.raw, row.version)
        flat.foreach(f => era.foreach(sameWits(_, f, row.name)))
        (flat.left.toOption, Vector(era.left.toOption))
      case Part.Transaction =>
        val flat = FlatTx.fromEnvelope(row.raw, Era.Alonzo)
        val era = layouts.map(AlonzoTx.fromEnvelope(row.raw, _))
        flat.foreach(f => layouts.zip(era).foreach((l, x) => x.foreach(sameTx(_, f, row.name, l))))
        (flat.left.toOption, era.map(_.left.toOption))
      case Part.Auxiliary =>
        val flat = AuxiliaryScripts.read(row.raw, Era.Alonzo, row.version.value)
        val era = AlonzoTxAuxData.decodeAt(row.raw, row.version)
        era.foreach(sameAux(_, row.raw, row.name))
        (flat.left.toOption, Vector(era.left.toOption))

  test("every Alonzo row of the reference's decoder corpora is read alike both ways") {
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
      !updateKeys.contains(name) && name != countedUnits && !envelopes.contains(name)
    )
    assert(sites.keys.forall(_.startsWith("alonzo update value: ")), sites.keys.toString)
    // each the flat decoder's refusal, its site the update's
    sites.foreach { (name, d) =>
      assertEquals(d._2, Some(atUpdate(d._1)), name)
    }
    // every refused envelope with its own refusal ([[envelopes]]), seven of them not the flat
    // decoder's
    results.foreach { case (r, (_, era)) =>
      if r.corpus == "tx-envelope" && !r.accepted then
        assertEquals(era.head.flatMap(cause), Some(envelopes(r.name)), r.name)
    }
    val envelopeDiffer = differ.keySet.intersect(envelopes.keySet)
    assertEquals(envelopeDiffer.size, 7)
    assertEquals(differ.keySet -- sites.keySet, updateKeys + countedUnits ++ envelopeDiffer)
    assertEquals(sites.size, 54)
    // the refusals that differ from the flat decoder's, whole
    assertEquals(differ.size, 64)
    val differCause = differ.map((name, d) => name -> d._2)
    // the update's own decoder refuses a parameter written twice with the reference's key
    // ("Duplicate key: 0 while decoding type PParamsUpdate AlonzoEra", 16 for the wide one), where
    // the flat decoder's refusal names no key
    assertEquals(
      differCause("alonzo update: repeated key, same value"),
      Some(
        LedgerDecodeError.PParamsCodec(
          PParamsDecodeError.DuplicateKey(DecodeField.ParameterUpdate, 0)
        )
      )
    )
    assertEquals(
      differCause("alonzo update: repeated key, other value, written wide"),
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
    // the rows recorded at Alonzo's protocol version 6, read at it: three witness sets
    assertEquals(
      rows.filter(_.version != DecoderVersion.of(Era.Alonzo)).map(_.part),
      Vector.fill(3)(Part.Witnesses)
    )
    // the rows read, by part and verdict
    assertEquals(
      rows.groupMapReduce(r => (r.part, r.accepted))(_ => 1)(_ + _),
      Map(
        (Part.Body, true) -> 137,
        (Part.Body, false) -> 174,
        (Part.Witnesses, true) -> 61,
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

  /** The refusal of each refused Alonzo envelope of `tx-envelope`, read as the reference's
    * `AlonzoTx` record is (`Alonzo/Tx.hs:538-553`, and `decodeListLikeT`,
    * `libs/cardano-ledger-binary/src/Cardano/Ledger/Binary/Decoding/Decoder.hs:691-709`,
    * cardano-ledger f649f975), against the cause the reference recorded for it:
    *   - "end of input" where an item is read past a definite list's end: the auxiliary data after
    *     the flag, the third item of two, the body of none;
    *   - "Failed to decode AlonzoTxAuxData" where the auxiliary data's reader meets an indefinite
    *     list's break, which the refusal names as the token it is (`Simple(31)`);
    *   - "Size mismatch when decoding Record AlonzoTx" where a definite list is longer than the
    *     record ("Expected 4, but found 3": the third item read as the auxiliary data; "Expected 5,
    *     but found 4");
    *   - "Excess terms in array" where the break is looked for after an indefinite list's third
    *     item;
    *   - the last three refused as the flat decoder refuses them, by the parse of the whole item
    *     before the record is read, where the reference reads the record from the stream: a head of
    *     four over three items ("Size mismatch ... Expected 4, but found 3") ends the input inside
    *     the list, and a head of three over four items ("Expected 3, but found 4") and a detached
    *     record ("expected map len or indef") leave bytes after it.
    */
  private val envelopes: Map[String, LedgerDecodeError] =
    import LedgerDecodeError.*
    val end = Syntax(xyz.kd5ujc.scarab.codec.Cbor.Error.UnexpectedEnd)
    val trailing = Syntax(xyz.kd5ujc.scarab.codec.Cbor.Error.TrailingBytes)
    val noFlag = ArrayLength(DecodeField.Transaction, 3, 4)
    val break = Expected(DecodeField.AuxiliaryData, Shape.ArrayOrMap, Value.Simple(31))
    Map(
      "alonzo transaction a flag and no auxiliary data" -> end,
      "alonzo transaction indefinite with a flag and no auxiliary data" -> break,
      "alonzo transaction four items with null third" -> noFlag,
      "alonzo transaction four items with auxiliary data third" -> noFlag,
      "alonzo transaction indefinite four items with null third" ->
        IndefiniteLength(DecodeField.Transaction),
      "alonzo transaction two items" -> end,
      "alonzo transaction five items" -> ArrayLength(DecodeField.Transaction, 4, 5),
      "alonzo transaction empty" -> end,
      "alonzo transaction head of four over three items" -> end,
      "alonzo transaction head of three over four items" -> trailing,
      "alonzo transaction detached" -> trailing
    )

  private val updateKeys: Set[String] = Set(
    "alonzo update: repeated key, same value",
    "alonzo update: repeated key, other value, written wide"
  )

  private val countedUnits: String =
    "alonzo witnesses redeemer units in a witness set that counts their fields"
