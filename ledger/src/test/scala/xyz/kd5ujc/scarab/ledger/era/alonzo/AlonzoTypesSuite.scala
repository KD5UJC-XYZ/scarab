// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.alonzo

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import io.circe.{Json, parser}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value as CborValue}
import xyz.kd5ujc.scarab.ledger.{
  Address,
  Block,
  ByteStrings,
  Datum as FlatDatum,
  DecoderVersion,
  LedgerDecodeError,
  ScriptKind,
  Script as FlatScript,
  TxBody as FlatTxBody,
  TxOut as FlatTxOut,
  Value as FlatValue,
  Witnesses as FlatWitnesses
}
import xyz.kd5ujc.scarab.ledger.era.{
  AlonzoScriptRep,
  AlonzoTxOuts,
  Datum,
  EncCBOR,
  EraCorpus,
  EraScript,
  EraTxError,
  EraTxOut,
  MultiAssetValue
}
import xyz.kd5ujc.scarab.ledger.era.mary.{MaryTimelock, MaryTxOut}
import xyz.kd5ujc.scarab.protocol.Era

/** Alonzo's output and scripts against the flat path at Alonzo and against the reference's own
  * verdicts and bytes. The outputs and witness scripts are those of the Alonzo transactions of the
  * recorded blocks (the era walk, the chain-dependent capture, the Preview chunks of
  * `fixtures/haskell-immutable` and the Preview Alonzo blocks) and of the reference's refusal,
  * v1-context, context-matrix and Plutus admission corpora, with the outputs those corpora's
  * transactions spend and the refusal corpora's UTxO sets. Each output reads as the flat decoder
  * reads it (address, value, datum hash and the stored form, byte for byte) and is written back by
  * the reference's encoder to the bytes it was read from; byte mutants of each are accepted and
  * refused alike. The reference's UTxO answers in Alonzo are written byte for byte, its decoder
  * corpora's output items read as the flat decoder reads them, and its verdicts on an output's form
  * are the era decoder's.
  */
class AlonzoTypesSuite extends FunSuite:
  private def right[E, A](what: String)(e: Either[E, A]): A =
    e.fold(x => fail(s"$what: $x"), identity)

  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.toString), identity)

  private def node(b: Bytes): Node = right("cbor")(Cbor.decode(b))

  private val version: DecoderVersion = DecoderVersion.of(Era.Alonzo)
  private given ByteStrings = ByteStrings.of(version)
  private val mode: Address.Mode = Address.Mode.at(version)
  private val values: FlatValue.Decoding = FlatValue.Decoding.of(Era.Alonzo, version)
  private val forms = FlatTxOut.Forms.of(Era.Alonzo)

  private def items(n: Node): Vector[Node] = n.value match
    case CborValue.Arr(xs) => xs
    case other             => fail(s"not an array: $other")

  private def field(n: Node, key: Int): Option[Node] = n.value match
    case CborValue.Map(kvs) =>
      kvs.collectFirst { case (k, v) if k.value == CborValue.UInt(key) => v }
    case other => fail(s"not a map: $other")

  private def lines(path: String): Vector[Json] =
    Files
      .readAllLines(Path.of(path))
      .asScala
      .toVector
      .filter(_.trim.nonEmpty)
      .map(l => parser.parse(l).fold(e => fail(e.message), identity))

  private def str(j: Json, name: String): String =
    j.hcursor.get[String](name).fold(e => fail(s"$name: ${e.message}"), identity)

  // ---- the recorded Alonzo transactions, as (source, body, witness set)

  private final case class Recorded(source: String, body: Bytes, witnesses: Bytes)

  private def blockTxs(file: String): Vector[Recorded] =
    EraCorpus
      .shelleyBlocks(Vector(file))
      .filter(_.era == Era.Alonzo)
      .flatMap(_.transactions)
      .map(t => Recorded(file, t.body, t.witnesses))

  /** The blocks of the Preview ImmutableDB chunks, cut by the lengths `expected-blocks.tsv` lists,
    * in order: each a `[era tag, block]`, all of them Alonzo's.
    */
  private lazy val immutableTxs: Vector[Recorded] =
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
          b.transactions.map(t => Recorded(dir, t.body, t.witnesses))
        case other => fail(s"not a Shelley-based block: $other")
    }

  /** The transactions of the reference's Alonzo refusal corpora, `[era, #6.24(bytes .cbor tx)]`. */
  private lazy val refusalTxs: Vector[Recorded] =
    Files
      .list(Path.of("fixtures/reference/refusals"))
      .iterator()
      .asScala
      .map(_.getFileName.toString)
      .filter(_.startsWith("alonzo"))
      .toVector
      .sorted
      .flatMap(d =>
        lines(s"fixtures/reference/refusals/$d/cases/cases.jsonl").map { j =>
          val whole = items(node(hex(str(j, "item"))))(1).value match
            case CborValue.Tag(_, inner) =>
              inner.value match
                case CborValue.ByteString(b) => b
                case other                   => fail(s"not a byte string: $other")
            case other => fail(s"not tagged: $other")
          val parts = items(node(whole))
          Recorded(d, parts(0).original, parts(1).original)
        }
      )

  private lazy val contextRows: Vector[Json] =
    Vector("alonzo-pv5", "alonzo-pv6").flatMap { f =>
      parser
        .parse(Files.readString(Path.of(s"fixtures/reference/v1-context/$f.json")))
        .fold(e => fail(e.message), identity)
        .asArray
        .getOrElse(fail(s"$f: not an array"))
    }

  private lazy val contextTxs: Vector[Recorded] =
    contextRows.map(j => Recorded("v1-context", hex(str(j, "body")), hex(str(j, "witnesses"))))

  /** The outputs the v1-context transactions spend, reference and put up as collateral, as the
    * reference's UTxO held them.
    */
  private lazy val contextOutputs: Vector[Bytes] =
    contextRows.flatMap(j =>
      Vector("spent", "references", "collateral").flatMap(k =>
        j.hcursor
          .downField(k)
          .as[Option[Vector[Json]]]
          .fold(e => fail(e.message), identity)
          .getOrElse(Vector.empty)
          .map(o => hex(str(o, "out")))
      )
    )

  /** The outputs of the UTxO sets the reference answered `GetUTxOWhole` with at the tips its Alonzo
    * refusal corpora were refused at (the files of `<corpus>/utxo`, each a map of inputs to
    * outputs): outputs the reference itself wrote.
    */
  private lazy val utxoOutputs: Vector[Bytes] =
    Files
      .list(Path.of("fixtures/reference/refusals"))
      .iterator()
      .asScala
      .filter(_.getFileName.toString.startsWith("alonzo"))
      .toVector
      .flatMap(d => Files.list(d.resolve("utxo")).iterator().asScala.toVector)
      .sortBy(_.toString)
      .flatMap(f =>
        node(Bytes.fromArray(Files.readAllBytes(f))).value match
          case CborValue.Map(kvs) => kvs.map(_._2.original)
          case other              => fail(s"$f: not a map: $other")
      )

  /** A whole transaction's body and witness set. */
  private def whole(source: String, raw: Bytes): Recorded =
    val parts = items(node(raw))
    Recorded(source, parts(0).original, parts(1).original)

  private def json(path: String): Json =
    parser.parse(Files.readString(Path.of(path))).fold(e => fail(e.message), identity)

  /** The Alonzo rows of the reference's context matrix (`fixtures/reference/context-matrix`). */
  private lazy val matrixRows: Vector[Json] =
    json("fixtures/reference/context-matrix/matrix.json").asArray
      .getOrElse(fail("matrix: not an array"))
      .filter(j => str(j, "era") == "Alonzo")

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

  /** The outputs of the UTxO sets of the context matrix's Alonzo rows. */
  private lazy val matrixOutputs: Vector[Bytes] =
    matrixRows.flatMap(j =>
      j.hcursor
        .downField("utxo")
        .as[Vector[Json]]
        .fold(e => fail(e.message), identity)
        .map(o => hex(str(o, "output")))
    )

  private lazy val recorded: Vector[Recorded] =
    Vector(
      "fixtures/eras/blocks.hex",
      "fixtures/chain-dep/blocks.hex",
      "fixtures/preview/alonzo-first-20.hex",
      "fixtures/preview/alonzo-txs.hex"
    ).flatMap(blockTxs) ++ immutableTxs ++ refusalTxs ++ contextTxs ++
      matrixRows.map(j => whole("context-matrix", hex(str(j, "transaction")))) ++
      admissionTxs.map(whole("plutus-admission", _))

  // ---- crafted outputs with a datum hash: of the recorded outputs, only four spent ones have one

  private val address = s"581d60${"11" * 28}"
  private val dataHash = s"5820${"33" * 32}"
  private val policy1 = "01" * 28
  private val policy2 = "02" * 28
  private val longName = "5818" + "61" * 24

  /** An ada-only output and a multi-asset one, each with a datum hash, written canonically. */
  private val crafted: Vector[Bytes] = Vector(
    hex(s"83${address}1a000f4240$dataHash"),
    hex(
      s"83${address}821a000f4240a2581c${policy1}a1400a581c${policy2}a2410202${longName}03$dataHash"
    )
  )

  // ---- an output both ways

  /** An output item read by the era and by the flat decoder, compared field by field and whole, and
    * written back; whether the reference's encoder gives back its bytes.
    */
  private def compare(item: Node, flat: FlatTxOut, what: String): Boolean =
    val era = right(s"$what era")(AlonzoTxOut.read(item, mode, values))
    val ops = EraTxOut.alonzo
    assertEquals(ops.address(era), flat.address, what)
    assertEquals(ops.value(era), MultiAssetValue.of(flat.value), what)
    assertEquals(ops.raw(era), flat.raw, what)
    val hash = flat.datum match
      case FlatDatum.NoDatum   => None
      case FlatDatum.Hash(h)   => Some(h)
      case FlatDatum.Inline(_) => fail(s"$what: an inline datum in Alonzo")
    assertEquals(ops.dataHash(era), hash, what)
    assertEquals(ops.datum(era), hash.fold(Datum.NoDatum)(Datum.DatumHash(_)), what)
    assertEquals(AlonzoTxOut.toStored(era), flat, what)
    assertEquals(AlonzoTxOut.fromStored(flat), Right(era), what)
    EncCBOR[AlonzoTxOut].encCBOR(era).bytes == item.original

  /** The outputs of the recorded bodies, each item with the flat decoder's output. */
  private lazy val outputs: Vector[(String, Node, FlatTxOut)] =
    recorded.zipWithIndex.flatMap { (r, i) =>
      val flat = right(s"${r.source} #$i flat")(FlatTxBody.decode(r.body, Era.Alonzo))
      val raw = items(field(node(r.body), 1).getOrElse(fail(s"${r.source} #$i: no outputs")))
      assertEquals(raw.size, flat.outputs.size)
      raw.zip(flat.outputs).zipWithIndex.map { case ((n, f), j) => (s"${r.source} #$i/$j", n, f) }
    }

  test("every recorded Alonzo output reads as the flat decoder reads it, and writes back") {
    val written = outputs.map((what, n, f) => compare(n, f, what))
    // every Alonzo transaction the repository holds, by source
    assertEquals(
      recorded.groupMapReduce(_.source)(_ => 1)(_ + _),
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
    assertEquals((outputs.size, written.count(identity)), (83, 83))
    // of them, those with assets and those with a datum hash
    assertEquals(
      (outputs.count(_._3.value.assets.nonEmpty), outputs.count(_._3.datum != FlatDatum.NoDatum)),
      (41, 0)
    )
  }

  test("the outputs of the reference's recorded UTxO sets read both ways, and write back") {
    def written(raws: Vector[Bytes], what: String): Int =
      raws.zipWithIndex.count { (raw, i) =>
        compare(node(raw), right(s"$what #$i")(FlatTxOut.fromBytes(raw)), s"$what #$i")
      }
    // the outputs the v1-context transactions spend, reference and put up as collateral, and those
    // of the refusal corpora's UTxO sets, which the reference wrote
    assertEquals(
      (contextOutputs.size, written(contextOutputs, "v1-context")),
      (20, 20)
    )
    assertEquals((utxoOutputs.size, written(utxoOutputs, "utxo")), (11, 11))
    assertEquals((matrixOutputs.size, written(matrixOutputs, "context-matrix")), (40, 40))
    // those with assets and those with a datum hash
    val stored = (contextOutputs ++ utxoOutputs ++ matrixOutputs).map(r =>
      right("stored")(FlatTxOut.fromBytes(r))
    )
    assertEquals(
      (stored.count(_.value.assets.nonEmpty), stored.count(_.datum != FlatDatum.NoDatum)),
      (1, 4)
    )
  }

  test("byte mutants of every recorded and crafted Alonzo output are accepted and refused alike") {
    val distinct =
      (outputs.map(
        _._2.original
      ) ++ contextOutputs ++ utxoOutputs ++ matrixOutputs ++ crafted).distinct
    val verdicts = for
      raw <- distinct
      i <- 0 until raw.size
      flip <- Vector(0x01, 0x80)
      mutant = Bytes.fromArray(raw.toArray.updated(i, (raw.toArray(i) ^ flip).toByte))
      n <- Cbor.decode(mutant).toOption
    yield
      val flat = FlatTxOut.decode(n, mode, forms, ScriptKind.newest(Era.Alonzo), values)
      val era = AlonzoTxOut.read(n, mode, values)
      (flat, era) match
        case (Right(f), Right(e)) => assertEquals(AlonzoTxOut.toStored(e), f, mutant.hex); true
        case (Left(f), Left(e))   => assertEquals(e, EraTxError.Decode(f), mutant.hex); false
        case _                    => fail(s"${mutant.hex}: flat $flat, era $era")
    assertEquals((distinct.size, verdicts.size, verdicts.count(identity)), (43, 5730, 5349))
  }

  // ---- the reference's own verdicts and bytes

  test(
    "the reference's UTxO answers in Alonzo are the outputs it reads, written as it writes them"
  ) {
    // `fixtures/reference/utxo-answers`: each entry read by Alonzo's decoder at its lowest version
    // and the map written, or the output refused; the node's stored reader reads each entry (a
    // value written as a detached record among them), and the era reads it from that
    val rows =
      lines("fixtures/reference/utxo-answers/answers.jsonl").filter(j => str(j, "era") == "alonzo")
    def entries(j: Json): Vector[(Bytes, Bytes)] =
      j.hcursor
        .get[Vector[(String, String)]]("entries")
        .fold(e => fail(e.message), identity)
        .map((i, o) => (hex(i), hex(o)))
    val written = rows.filter(_.hcursor.downField("answer").succeeded).flatMap { j =>
      val answer = node(hex(str(j, "answer"))).value match
        case CborValue.Map(kvs) => kvs.map((k, v) => k.original -> v.original).toMap
        case other              => fail(s"${str(j, "name")}: not a map: $other")
      entries(j).map { (i, o) =>
        val what = s"${str(j, "name")} ${i.hex}"
        val stored = right(what)(FlatTxOut.fromBytes(o))
        val era = right(what)(AlonzoTxOut.fromStored(stored))
        // a value written detached is read only by the stored reader
        if !str(j, "name").contains("detached") then
          assertEquals(AlonzoTxOut.read(node(o), mode, values), Right(era), what)
        assertEquals(
          Some(EncCBOR[AlonzoTxOut].encCBOR(era).bytes),
          answer.get(i),
          what
        )
        o
      }
    }
    // an output with a part only a later era's holds: Alonzo's decoder refuses the keyed form
    val refused = rows
      .filter(_.hcursor.downField("refused").succeeded)
      .flatMap(j =>
        entries(j).map { (_, o) =>
          assert(AlonzoTxOut.read(node(o), mode, values).isLeft, str(j, "name"))
          o
        }
      )
    assertEquals((rows.size, written.size, refused.size), (19, 46, 4))
  }

  /** The output items of the reference's Alonzo rows of its decoder corpora, with each row's
    * verdict on the body or transaction holding them, whether it accepted it.
    */
  private lazy val corpusItems: Vector[(String, Boolean, Node)] =
    Vector("era-forms", "era-decoders", "address-headers", "tx-envelope").flatMap { c =>
      lines(s"fixtures/reference/$c/cases.jsonl")
        .filter(j => str(j, "era") == "alonzo")
        .flatMap { j =>
          // a row of a body or a transaction has one verdict; an address's row, which holds no
          // output, has one per header
          val accepted = j.hcursor.get[Boolean]("accepted").toOption.contains(true)
          val bodies = Cbor.decode(hex(str(j, "bytes"))).toOption.toVector.flatMap { n =>
            str(j, "kind") match
              case "body"        => Vector(n)
              case "transaction" => items(n).take(1)
              case "blockbody"   => items(n).headOption.toVector.flatMap(items)
              case _             => Vector.empty
          }
          bodies
            .flatMap(b =>
              b.value match
                case CborValue.Map(_) =>
                  field(b, 1).toVector.flatMap(o =>
                    o.value match
                      case CborValue.Arr(xs) => xs
                      case _                 => Vector.empty
                  )
                case _ => Vector.empty
            )
            .map(o => (s"$c/${str(j, "name")}", accepted, o))
        }
    }

  test("every output item of the reference's Alonzo decoder corpora reads as the flat decoder's") {
    val read = corpusItems.map { (what, accepted, n) =>
      val flat = FlatTxOut.decode(n, mode, forms, ScriptKind.newest(Era.Alonzo), values)
      val era = AlonzoTxOut.read(n, mode, values)
      (flat, era) match
        case (Right(f), Right(_)) => compare(n, f, what)
        case (Left(f), Left(e))   => assertEquals(e, EraTxError.Decode(f), what)
        case _                    => fail(s"$what: flat $flat, era $era")
      // what the reference accepted holds no output it refuses
      if accepted then assert(era.isRight, what)
      era.isRight
    }
    assertEquals((corpusItems.size, read.count(identity)), (78, 70))
    // the outputs of the reference's rows on an output's form, each row's verdict its output's
    val shapes = corpusItems.filter(_._1.startsWith("era-forms/alonzo output"))
    shapes.foreach((what, accepted, n) =>
      assertEquals(AlonzoTxOut.read(n, mode, values).isRight, accepted, what)
    )
    assertEquals((shapes.size, shapes.count(_._2)), (9, 4))
  }

  test("every recorded and crafted Alonzo output's least coin is the flat path's") {
    val p = xyz.kd5ujc.scarab.ledger.TestLedger.params(6)
    val pp = xyz.kd5ujc.scarab.ledger.era.PParams[xyz.kd5ujc.scarab.ledger.state.Alonzo](
      right("parameters")(AlonzoPParams.fromStored(p))
    )
    val stored = (contextOutputs ++ utxoOutputs ++ matrixOutputs ++ crafted)
      .map(raw => (node(raw), right("stored")(FlatTxOut.fromBytes(raw))))
    val read = outputs.map((_, n, f) => (n, f)) ++ stored ++
      corpusItems.flatMap((_, _, n) =>
        FlatTxOut.decode(n, mode, forms, ScriptKind.newest(Era.Alonzo), values).toOption.map(n -> _)
      )
    val sizes = read.map { (n, f) =>
      val era = right(n.original.hex)(AlonzoTxOut.read(n, mode, values))
      assertEquals(
        AlonzoTxOuts.getMinCoinTxOut[xyz.kd5ujc.scarab.ledger.state.Alonzo](pp, era).value,
        xyz.kd5ujc.scarab.ledger.TxChecks
          .minUTxO[xyz.kd5ujc.scarab.ledger.state.Alonzo](p, f)(using
            xyz.kd5ujc.scarab.ledger.state.ShelleyBasedEra.SAlonzo
          ),
        n.original.hex
      )
      AlonzoTxOuts.utxoEntrySize[xyz.kd5ujc.scarab.ledger.state.Alonzo](era)
    }
    // the outputs, and the entry sizes among them
    assertEquals((read.size, sizes.distinct.sorted[BigInt]), (226, Vector[BigInt](29, 39, 58)))
  }

  test("an output of another form is refused as the flat decoder refuses it") {
    // a list of one, of four, and the map form, which Alonzo does not read
    Vector(s"81$address", s"84${address}0a5820${"22" * 32}00", s"a200${address}010a").foreach { h =>
      val n = node(hex(h))
      val flat = FlatTxOut.decode(n, mode, forms, ScriptKind.newest(Era.Alonzo), values)
      assert(flat.isLeft, h)
      assertEquals(
        AlonzoTxOut.read(n, mode, values).left.toOption,
        flat.left.toOption.map(EraTxError.Decode(_)),
        h
      )
    }
    // a datum hash of 31 bytes
    val short = node(hex(s"83${address}0a581f${"22" * 31}"))
    assertEquals(
      AlonzoTxOut.read(short, mode, values).left.toOption,
      FlatTxOut
        .decode(short, mode, forms, ScriptKind.newest(Era.Alonzo), values)
        .left
        .toOption
        .map(EraTxError.Decode(_))
    )
  }

  test("an output with a datum hash is written back, a multi-asset one with its keys ascending") {
    // the multi-asset one's two policies and two names written out of order, one name of 24 bytes
    val unordered = node(
      hex(
        s"83${address}821a000f4240a2581c${policy2}a2${longName}03410202581c${policy1}a1400a$dataHash"
      )
    )
    val era = right("multi-asset")(AlonzoTxOut.read(unordered, mode, values))
    assertEquals(EncCBOR[AlonzoTxOut].encCBOR(era).bytes, crafted(1))
    crafted.foreach { raw =>
      val flat = right("flat")(FlatTxOut.fromBytes(raw))
      assert(compare(node(raw), flat, raw.hex), raw.hex)
      assertEquals(
        EraTxOut.alonzo.dataHash(right("era")(AlonzoTxOut.read(node(raw), mode, values))),
        Some(hex("33" * 32))
      )
    }
  }

  test("an address read with bytes after it is written without them, as the reference crops it") {
    // below version 7 the bytes after a Shelley address are dropped (`fromCborBackwardsBothAddr`)
    val item = node(hex(s"82581f60${"11" * 28}aabb1a000f4240"))
    val era = right("trailing")(AlonzoTxOut.read(item, mode, values))
    assertEquals(EraTxOut.alonzo.raw(era), item.original)
    assertEquals(EncCBOR[AlonzoTxOut].encCBOR(era).bytes, hex(s"82581d60${"11" * 28}1a000f4240"))
  }

  test("Alonzo's versions are 5 to 6") {
    val era = summon[xyz.kd5ujc.scarab.ledger.era.Era[xyz.kd5ujc.scarab.ledger.state.Alonzo]]
    assertEquals((era.eraProtVerLow.value, era.eraProtVerHigh.value), (5, 6))
  }

  test("a Mary output and script carried into Alonzo keep their fields and bytes") {
    val raw = hex(s"82581d60${"11" * 28}1a000f4240")
    val mary = right("mary")(
      MaryTxOut.fromStored(right("flat")(FlatTxOut.fromBytes(raw)))
    )
    val alonzo = AlonzoTxOut.translate(mary)
    assertEquals(EraTxOut.alonzo.raw(alonzo), raw)
    assertEquals(EraTxOut.alonzo.address(alonzo), EraTxOut.mary.address(mary))
    assertEquals(EraTxOut.alonzo.value(alonzo), EraTxOut.mary.value(mary))
    assertEquals(EraTxOut.alonzo.dataHash(alonzo), None)
    val native = node(hex(s"8200581c${"44" * 28}"))
    val script = AlonzoScript.translate(right("mary script")(MaryTimelock.decode(native)))
    assertEquals(
      EraScript.alonzo.hashScript(script),
      EraScript.mary.hashScript(right("mary script")(MaryTimelock.decode(native)))
    )
  }

  // ---- scripts

  test("Alonzo's script decoder reads V1 and native scripts and refuses V2, as the flat one") {
    val v1 = node(hex("8201420102"))
    val alonzo = right("v1")(AlonzoScript.decode(v1))
    assertEquals(
      EraScript.alonzo.hashScript(alonzo),
      right("flat v1")(FlatScript.decode(v1, ScriptKind.PlutusV1)).hash
    )
    assertEquals(
      EraScript.alonzo.toPlutusScript(alonzo).map(_.kind),
      Some(ScriptKind.PlutusV1)
    )
    val v2 = node(hex("8202420102"))
    val refusal = LedgerDecodeError.UnsupportedLanguage(ScriptKind.PlutusV2, ScriptKind.PlutusV1)
    assertEquals(AlonzoScript.decode(v2), Left(EraTxError.Decode(refusal)))
    assertEquals(FlatScript.decode(v2, ScriptKind.PlutusV1).left.toOption, Some(refusal))
    assertEquals(EraScript.alonzo.eraMaxLanguage, xyz.kd5ujc.scarab.plutus.PlutusLanguage.V1)
  }

  test("every recorded Alonzo witness script hashes as the flat decoder's") {
    // the witness set's native scripts (key 1) and Plutus V1 scripts (key 3), each read as an
    // Alonzo script, against the flat witness set's scripts of the same transaction
    val counts = recorded.zipWithIndex.map { (r, i) =>
      val what = s"${r.source} #$i"
      val wits = node(r.witnesses)
      val native = field(wits, 1).toVector
        .flatMap(items)
        .map(n =>
          EraScript.alonzo.fromNativeScript(right(s"$what native")(AlonzoTimelock.decode(n)))
        )
      val plutus = field(wits, 3).toVector.flatMap(items).map { n =>
        val wrapped = node(Bytes.fromArray(Array(0x82.toByte, 0x01.toByte) ++ n.original.toArray))
        right(s"$what v1")(AlonzoScript.decode(wrapped))
      }
      val flat = right(s"$what flat")(FlatWitnesses.decode(r.witnesses, Era.Alonzo))
      val scripts = native ++ plutus
      assertEquals(
        scripts.map(EraScript.alonzo.hashScript).sortBy(_.hex),
        flat.scripts.map(_.hash).sortBy(_.hex),
        what
      )
      scripts.foreach(s =>
        s.rep match
          case AlonzoScriptRep.NativeScript(_) =>
            assert(EraScript.isNativeScript[xyz.kd5ujc.scarab.ledger.state.Alonzo](s))
          case AlonzoScriptRep.PlutusScript(p) => assertEquals(p.kind, ScriptKind.PlutusV1)
      )
      (native.size, plutus.size)
    }
    assertEquals((counts.map(_._1).sum, counts.map(_._2).sum), (0, 41))
  }
