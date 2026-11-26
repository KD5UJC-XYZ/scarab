// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.conway

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.annotation.tailrec
import scala.jdk.CollectionConverters.*
import scala.util.Random
import io.circe.{Json, parser}
import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.ledger.{
  Block,
  DetachedRecords,
  LedgerDecodeError,
  PreprodDeepTx,
  Tx as FlatTx,
  TxBody as FlatTxBody,
  Witnesses as FlatWitnesses
}
import xyz.kd5ujc.scarab.ledger.era.{Datum, EraCorpus, EraTxError, Timelock}
import xyz.kd5ujc.scarab.protocol.{Era, StateLayout}

/** Conway's era decoders, which read a transaction's parts in one pass ([[ConwayTxBody.decode]],
  * [[ConwayTxWits.decode]], [[ConwayTxAuxData.decode]], [[ConwayTx.decode]] and
  * [[ConwayTx.fromEnvelope]]), against the two passes they replaced, the flat decoder at Conway and
  * the projection of what it read (the `…ByFlat` decoders, #85): on every input and at every
  * layout, the same value, with the bytes, ids, sizes, stored outputs, native-script levels and
  * arrival orders it keeps, or the same refusal. The inputs are every Conway transaction and part
  * the repository holds (the recorded blocks, the preprod transactions, the decoding, refusal,
  * envelope and detached-record corpora), each transaction in its block form and its mempool form,
  * the Babbage transactions of the recorded blocks read as Conway's, and mutants of them: bytes
  * cut, flipped or deleted, and items duplicated, dropped, emptied, retagged, widened, given an
  * unknown key, written with an indefinite length or as a detached record (`9f ff` and its fields
  * after it). Written cases fix what random mutants reach by chance: competing refusals, every form
  * of Plutus data, and a fault at each key of a body, an output, a witness set and auxiliary data.
  * The refusals the two passes gave on the fixed mutants and on the fault table are recorded too
  * ([[ConwayOnePassExpected]]), so they keep their teeth when the two passes go.
  */
class ConwayOnePassSuite extends ScalaCheckSuite:
  override def scalaCheckTestParameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(2000)

  import ConwayOnePassCodes.code

  private val layouts: Vector[StateLayout] = StateLayout.values.toVector

  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.toString), identity)
  private def node(raw: Bytes): Option[Node] = Cbor.decode(raw, Cbor.Limits.Unbounded).toOption
  private def json(path: String): Json =
    parser.parse(Files.readString(Path.of(path))).fold(e => fail(e.message), identity)
  private def jsonLines(path: String): Vector[Json] =
    Files
      .readAllLines(Path.of(path))
      .asScala
      .toVector
      .filter(_.trim.nonEmpty)
      .map(l => parser.parse(l).fold(e => fail(e.message), identity))
  private def str(j: Json, name: String): String =
    j.hcursor.get[String](name).fold(e => fail(s"$name: ${e.message}"), identity)

  /** What the two ways gave, for the tallies. */
  private enum Outcome derives CanEqual:
    case Accepted, Refused, ProjectionRefused, NotCbor

  private def outcome(one: Either[EraTxError, ?], flatAccepted: => Boolean): Outcome =
    if one.isRight then Outcome.Accepted
    else if flatAccepted then Outcome.ProjectionRefused
    else Outcome.Refused

  // ---- what equality leaves out

  /** A native script's bytes, and each of its levels, walked on the heap: where its bytes lie in
    * the script's and how many there are, and its constructor with its own fields and the number of
    * its sub-scripts, so that two scripts with the same bytes and levels have the same tree and the
    * same bytes at each level, in time linear in the script's size.
    */
  private def levels(t: Timelock): (Bytes, Vector[(Long, Int, Any)]) =
    import xyz.kd5ujc.scarab.ledger.NativeScript as N
    def shallow(n: N): Any = n match
      case N.Sig(h)              => ("sig", h)
      case N.AllOf(xs)           => ("all", xs.size)
      case N.AnyOf(xs)           => ("any", xs.size)
      case N.NOfK(k, xs)         => ("n of k", k, xs.size)
      case N.InvalidBefore(s)    => ("before", s)
      case N.InvalidHereafter(s) => ("hereafter", s)
    val root = t.raw
    def at(b: Bytes): Long =
      if b.unsafeArray eq root.unsafeArray then (b.unsafeOffset - root.unsafeOffset).toLong else -1L
    @tailrec def go(
        todo: List[Timelock],
        out: Vector[(Long, Int, Any)]
    ): Vector[(Long, Int, Any)] =
      todo match
        case Nil => out
        case s :: rest =>
          go(s.children.toList ::: rest, out :+ (at(s.raw), s.raw.size, shallow(s.script)))
    (root, go(List(t), Vector.empty))

  private def sameBodyKept(a: ConwayTxBody, b: ConwayTxBody, what: String): Unit =
    // an output's equality leaves out its bytes and its value's size; the stored form has them
    def outs(x: ConwayTxBody) =
      (x.outputs ++ x.collateralReturn.toVector).map(o =>
        (ConwayTxOut.toStored(o.value), o.size, o.value.rep.raw, o.value.rep.valueSize)
      )
    assertEquals(a.raw, b.raw, what)
    assertEquals(a.id, b.id, what)
    assertEquals(outs(a), outs(b), what)
    def scripts(x: ConwayTxBody) = x.outputs.flatMap(_.value.rep.scriptRef).flatMap(nativeLevels)
    assertEquals(scripts(a), scripts(b), what)

  private def nativeLevels(s: ConwayScript): Vector[(Bytes, Vector[(Long, Int, Any)])] =
    ConwayScript.instance.getNativeScript(s).toVector.map(t => levels(t.rep))

  private def sameWitsKept(a: ConwayTxWits, b: ConwayTxWits, what: String): Unit =
    assertEquals(a.raw, b.raw, what)
    assertEquals(a.scriptArrival, b.scriptArrival, what)
    assertEquals(a.datums.raw, b.datums.raw, what)
    assertEquals(a.redeemers.raw, b.redeemers.raw, what)
    assertEquals(
      a.datums.datums.values.toVector.map(_.originalBytes),
      b.datums.datums.values.toVector.map(_.originalBytes),
      what
    )
    assertEquals(
      a.redeemers.entries.values.toVector.map((d, u) => (d.originalBytes, u)),
      b.redeemers.entries.values.toVector.map((d, u) => (d.originalBytes, u)),
      what
    )
    assertEquals(
      a.scripts.values.toVector.flatMap(nativeLevels),
      b.scripts.values.toVector.flatMap(nativeLevels),
      what
    )

  private def sameAuxKept(a: ConwayTxAuxData, b: ConwayTxAuxData, what: String): Unit =
    assertEquals(a.raw, b.raw, what)
    assertEquals(a.plutusArrival, b.plutusArrival, what)
    assertEquals(
      a.nativeScripts.map(t => levels(t.rep)),
      b.nativeScripts.map(t => levels(t.rep)),
      what
    )

  private def sameTxKept(a: ConwayTx, b: ConwayTx, what: String): Unit =
    sameBodyKept(a.body, b.body, what)
    sameWitsKept(a.wits, b.wits, what)
    a.auxData.zip(b.auxData).foreach(sameAuxKept(_, _, what))
    assertEquals(a.sizeTxForFeeCalculation, b.sizeTxForFeeCalculation, what)

  // ---- the two ways

  private def sameBody(raw: Bytes, what: String): Outcome =
    layouts.map { l =>
      val one = ConwayTxBody.decode(raw, l)
      val two = ConwayTxBody.decodeByFlat(raw, l)
      assertEquals(one, two, s"$what: body at $l")
      one.foreach(a => two.foreach(sameBodyKept(a, _, s"$what: body at $l")))
      outcome(one, FlatTxBody.decode(raw, Era.Conway).isRight)
    }.head

  private def sameWits(raw: Bytes, what: String): Outcome =
    val one = ConwayTxWits.decode(raw)
    val two = ConwayTxWits.decodeByFlat(raw)
    assertEquals(one, two, s"$what: witnesses")
    one.foreach(a => two.foreach(sameWitsKept(a, _, s"$what: witnesses")))
    outcome(one, FlatWitnesses.decode(raw, Era.Conway).isRight)

  private def sameAux(raw: Bytes, what: String): Outcome =
    val one = ConwayTxAuxData.decode(raw)
    val two = ConwayTxAuxData.decodeByFlat(raw)
    assertEquals(one, two, s"$what: auxiliary data")
    one.foreach(a => two.foreach(sameAuxKept(a, _, s"$what: auxiliary data")))
    outcome(
      one,
      xyz.kd5ujc.scarab.ledger.AuxiliaryScripts
        .read(raw, Era.Conway, 9)(using
          xyz.kd5ujc.scarab.ledger.ByteStrings
            .of(xyz.kd5ujc.scarab.ledger.DecoderVersion.of(Era.Conway))
        )
        .isRight
    )

  /** A transaction in its block form, its parts apart, and each part alone. */
  private def sameParts(t: Block.Transaction, what: String): Outcome =
    sameBody(t.body, what)
    sameWits(t.witnesses, what)
    t.auxiliaryData.foreach(sameAux(_, what))
    layouts.map { l =>
      val one = ConwayTx.decode(t, l)
      val two = ConwayTx.decodeByFlat(t, l)
      assertEquals(one, two, s"$what at $l")
      one.foreach(a => two.foreach(sameTxKept(a, _, s"$what at $l")))
      outcome(one, FlatTx.fromBlock(t, Era.Conway).isRight)
    }.head

  /** A whole transaction's bytes, as a client or a peer submits them. */
  private def sameEnvelope(raw: Bytes, what: String): Outcome =
    layouts.map { l =>
      val one = ConwayTx.fromEnvelope(raw, l)
      val two = ConwayTx.fromEnvelopeByFlat(raw, l)
      assertEquals(one, two, s"$what: mempool form at $l")
      one.foreach(a => two.foreach(sameTxKept(a, _, s"$what: mempool form at $l")))
      outcome(one, FlatTx.fromEnvelope(raw, Era.Conway).isRight)
    }.head

  /** The parts of a whole transaction, as the flat decoder splits them. */
  private def partsOf(raw: Bytes): Option[Block.Transaction] =
    DetachedRecords
      .read(raw, Cbor.Limits.Unbounded, DetachedRecords.Shapes.transaction(Era.Conway))(
        LedgerDecodeError.Syntax(_)
      )(FlatTx.envelopeParts(_, Era.Conway))
      .toOption

  /** A whole transaction both ways, and its parts in their block form when it splits. */
  private def sameWhole(raw: Bytes, what: String): Outcome =
    val whole = sameEnvelope(raw, what)
    partsOf(raw).foreach(sameParts(_, s"$what, parts"))
    if node(raw).isEmpty then Outcome.NotCbor else whole

  private def tally(outcomes: Vector[Outcome]): Map[Outcome, Int] =
    outcomes.groupBy(identity).view.mapValues(_.size).toMap

  // ---- the inputs

  private lazy val blocks: Vector[Block.Shelley] = EraCorpus.shelleyBlocks(
    Vector(
      "fixtures/eras/blocks.hex",
      "fixtures/preview/conway.hex",
      "fixtures/preview/conway-governance.hex",
      "fixtures/preview/duplicate-redeemer-conway.hex",
      "fixtures/preview/empty-redeemers.hex",
      "fixtures/preview/deeply-nested-datum.hex"
    )
  )

  private lazy val conwayTxs: Vector[Block.Transaction] =
    blocks.filter(_.era == Era.Conway).flatMap(_.transactions)

  private lazy val babbageTxs: Vector[Block.Transaction] =
    blocks.filter(_.era == Era.Babbage).flatMap(_.transactions)

  private lazy val preprodTxs: Vector[Bytes] =
    Files
      .list(Path.of("fixtures/preprod"))
      .iterator()
      .asScala
      .map(_.toString)
      .filter(p => p.contains("/tx-") && p.endsWith(".hex"))
      .toVector
      .sorted
      .map(p => hex(Files.readString(Path.of(p)).trim))

  /** The Conway corpora of `fixtures/reference/refusals`, every one there now. */
  private lazy val refusalCorpora: Vector[String] =
    Files
      .list(Path.of("fixtures/reference/refusals"))
      .iterator()
      .asScala
      .map(_.getFileName.toString)
      .filter(_.startsWith("conway"))
      .toVector
      .sorted

  /** The Conway refusal corpora there were when the fixed mutants' refusals were recorded
    * (`ConwayOnePassExpected`): the mutants are drawn from their transactions, so a corpus added
    * later, which the differential above reads, does not move them.
    */
  private val seedCorpora: Vector[String] = Vector(
    "conway-bootstrap-attributes",
    "conway-bootstrap-attributes-leios-b004fe0c",
    "conway-deleg-collateral",
    "conway-devnet",
    "conway-gov-proposals",
    "conway-governance",
    "conway-three-certificates",
    "conway-v11-coverage"
  )

  /** The transactions of the reference's refusal corpora: `[era, #6.24(bytes .cbor tx)]`. */
  private lazy val refusalTxs: Vector[Bytes] = refusalTxsOf(refusalCorpora)

  private def refusalTxsOf(corpora: Vector[String]): Vector[Bytes] =
    corpora
      .flatMap(d => jsonLines(s"fixtures/reference/refusals/$d/cases/cases.jsonl"))
      .map(j =>
        node(hex(str(j, "item"))).map(_.value) match
          case Some(Value.Arr(Vector(_, tagged))) =>
            tagged.value match
              case Value.Tag(_, inner) =>
                inner.value match
                  case Value.ByteString(b) => b
                  case other               => fail(s"not a byte string: $other")
              case other => fail(s"not tagged: $other")
          case other => fail(s"not a submitted transaction: $other")
      )

  /** The rows of a reference corpus of `kind` at Conway (or of every era, for `anyEra`). */
  private def rows(path: String, kind: String, anyEra: Boolean = false): Vector[Bytes] =
    val all =
      if path.endsWith(".jsonl") then jsonLines(path)
      else json(path).asArray.getOrElse(fail(s"$path: not an array"))
    all
      .filter(j => str(j, "kind") == kind && (anyEra || str(j, "era") == "conway"))
      .map(j => hex(str(j, "bytes")))

  private lazy val wholeSources: Vector[(String, Vector[Bytes])] = Vector(
    "recorded blocks" -> conwayTxs.map(FlatTx.envelope),
    "Babbage blocks read as Conway's" -> babbageTxs.map(FlatTx.envelope),
    "preprod transactions" -> preprodTxs,
    "refusal corpora" -> refusalTxs,
    "conway-decoding transactions" ->
      rows("fixtures/reference/conway-decoding/cases.json", "transaction"),
    "tx-envelope" -> rows("fixtures/reference/tx-envelope/cases.jsonl", "transaction"),
    "recd-detached transactions" ->
      rows("fixtures/reference/recd-detached/cases.jsonl", "transaction"),
    "recd-records transactions" ->
      rows("fixtures/reference/recd-records/cases.jsonl", "transaction")
  )

  private lazy val bodySources: Vector[(String, Vector[Bytes])] = Vector(
    "conway-decoding bodies" ->
      rows("fixtures/reference/conway-decoding/cases.json", "body", anyEra = true),
    "era-decoders bodies" -> rows("fixtures/reference/era-decoders/cases.jsonl", "body"),
    "recd-detached bodies" -> rows("fixtures/reference/recd-detached/cases.jsonl", "body"),
    "recd-records bodies" -> rows("fixtures/reference/recd-records/cases.jsonl", "body")
  )

  private lazy val witsSources: Vector[(String, Vector[Bytes])] = Vector(
    "conway-decoding witness sets" ->
      rows("fixtures/reference/conway-decoding/cases.json", "witnesses"),
    "recd-detached witness sets" ->
      rows("fixtures/reference/recd-detached/cases.jsonl", "witnesses"),
    "recd-records witness sets" -> rows("fixtures/reference/recd-records/cases.jsonl", "witnesses")
  )

  private lazy val auxSources: Vector[(String, Vector[Bytes])] = Vector(
    "recd-detached auxiliary data" ->
      rows("fixtures/reference/recd-detached/cases.jsonl", "auxiliary")
  )

  test("every recorded Conway transaction and part reads the same in one pass as in two") {
    val parts = conwayTxs.zipWithIndex.map((t, i) => sameParts(t, s"recorded block transaction $i"))
    val whole = wholeSources.map((name, txs) =>
      name -> tally(txs.zipWithIndex.map((raw, i) => sameWhole(raw, s"$name $i")))
    )
    val bodies = bodySources.map((name, xs) =>
      name -> tally(xs.zipWithIndex.map((raw, i) => sameBody(raw, s"$name $i")))
    )
    val wits = witsSources.map((name, xs) =>
      name -> tally(xs.zipWithIndex.map((raw, i) => sameWits(raw, s"$name $i")))
    )
    val aux = auxSources.map((name, xs) =>
      name -> tally(xs.zipWithIndex.map((raw, i) => sameAux(raw, s"$name $i")))
    )
    val counts = (whole ++ bodies ++ wits ++ aux).toMap
    import Outcome.*
    // every recorded block's transaction is read, in both forms
    assertEquals(tally(parts), Map(Accepted -> conwayTxs.size))
    assertEquals(counts("recorded blocks"), Map(Accepted -> conwayTxs.size))
    assertEquals(counts("preprod transactions"), Map(Accepted -> preprodTxs.size))
    assertEquals(counts("refusal corpora"), Map(Accepted -> refusalTxs.size))
    assert(conwayTxs.sizeIs > 50, conwayTxs.size.toString)
    // the corpora have both outcomes, and no refusal of the projection alone
    Vector(
      "conway-decoding bodies",
      "conway-decoding witness sets",
      "recd-detached bodies",
      "recd-records bodies",
      "tx-envelope"
    ).foreach(name =>
      assert(counts(name).contains(Accepted) && counts(name).contains(Refused), s"$name: $counts")
    )
    assert(!counts.values.exists(_.contains(ProjectionRefused)), counts.toString)
    clue(counts)
  }

  test("the deep preprod transaction reads the same both ways on a small stack") {
    val one = PreprodDeepTx.onSmallStack(
      layouts.map(l => (ConwayTx.fromEnvelope(PreprodDeepTx.bytes, l), l))
    )
    one.foreach((r, l) =>
      val two = ConwayTx.fromEnvelopeByFlat(PreprodDeepTx.bytes, l)
      assertEquals(r, two, l.toString)
      assert(r.isRight, r.toString)
      r.foreach(a => two.foreach(sameTxKept(a, _, l.toString)))
    )
  }

  // ---- mutants

  /** The items of a decoded transaction with where each begins in its bytes and the part it is in:
    * the transaction's item and the key of its body's, witness set's or auxiliary data's map under
    * which it lies. Walked on a list of the items still to visit, so the depth is the heap's.
    */
  private def items(root: Node): Vector[(Node, Int, String)] =
    val base = root.original.unsafeOffset
    def children(n: Node): Vector[Node] = n.value match
      case Value.Arr(xs)    => xs
      case Value.Map(kvs)   => kvs.flatMap((k, v) => Vector(k, v))
      case Value.Tag(_, in) => Vector(in)
      case _                => Vector.empty
    def label(path: List[String]): String = path.reverse.take(2).mkString(":")
    @tailrec def go(
        todo: List[(Node, List[String])],
        out: Vector[(Node, Int, String)]
    ): Vector[(Node, Int, String)] =
      todo match
        case Nil => out
        case (n, path) :: rest =>
          val here = (n, n.original.unsafeOffset - base, label(path))
          val kids = n.value match
            case Value.Map(kvs) if path.sizeIs == 1 =>
              kvs.toList.flatMap((k, v) =>
                val key = k.value match
                  case Value.UInt(u) => u.toString
                  case _             => "?"
                List((k, key :: path), (v, key :: path))
              )
            case Value.Arr(xs) if path.isEmpty =>
              xs.zipWithIndex.toList.map((x, i) => (x, s"part$i" :: path))
            case _ => children(n).toList.map((_, path))
          go(kids ++ rest, out :+ here)
    go(List((root, Nil)), Vector.empty)

  private def head(major: Int, count: Long): Bytes =
    val m = major << 5
    def be(n: Int): Array[Byte] = Array.tabulate(n)(i => (count >>> (8 * (n - 1 - i))).toByte)
    Bytes.wrap(
      if count < 24 then Array((m | count.toInt).toByte)
      else if count < 0x100 then Array((m | 24).toByte) ++ be(1)
      else if count < 0x10000 then Array((m | 25).toByte) ++ be(2)
      else if count < 0x100000000L then Array((m | 26).toByte) ++ be(4)
      else Array((m | 27).toByte) ++ be(8)
    )

  private def concat(xs: Vector[Bytes]): Bytes = xs.foldLeft(Bytes.empty)(_ ++ _)

  private val emptyIndefinite = Bytes.wrap(Array(0x9f.toByte, 0xff.toByte))

  /** The item `n` written again by one structural mutation, none when it does not apply. */
  private def rewrite(n: Node, kind: Int, rnd: Random): Option[Bytes] =
    def pick[A](xs: Vector[A]): A = xs(rnd.nextInt(xs.size))
    n.value match
      case Value.Map(kvs) if kvs.nonEmpty =>
        val entries = kvs.map((k, v) => k.original ++ v.original)
        kind % 7 match
          case 0 => // a key given twice, with its own value or another entry's
            val e =
              if rnd.nextBoolean() then pick(entries)
              else kvs(rnd.nextInt(kvs.size))._1.original ++ kvs(rnd.nextInt(kvs.size))._2.original
            Some(head(5, entries.size + 1L) ++ concat(entries) ++ e)
          case 1 => // an entry dropped
            val i = rnd.nextInt(entries.size)
            Some(head(5, entries.size - 1L) ++ concat(entries.patch(i, Nil, 1)))
          case 2 => // an unknown key
            val k = pick(Vector(6L, 8L, 10L, 12L, 23L, 99L, 1L << 33))
            Some(head(5, entries.size + 1L) ++ concat(entries) ++ head(0, k) ++ hex("00"))
          case 3 => // written with an indefinite length
            Some(
              Bytes.wrap(Array(0xbf.toByte)) ++ concat(entries) ++ Bytes.wrap(Array(0xff.toByte))
            )
          case 4 => Some(Bytes.wrap(Array(0xa0.toByte))) // emptied
          case 5 => // an entry's value replaced by an empty indefinite list
            val (k, _) = kvs(rnd.nextInt(kvs.size))
            Some(
              head(5, entries.size.toLong) ++ concat(
                kvs.map((a, b) =>
                  if a eq k then a.original ++ emptyIndefinite else a.original ++ b.original
                )
              )
            )
          case _ => // its length written in eight bytes
            Some(
              Bytes.wrap(Array(0xbb.toByte)) ++ Bytes.wrap(Array.fill(7)(0.toByte)) ++
                Bytes.wrap(Array(entries.size.toByte)) ++ concat(entries)
            )
      case Value.Arr(xs) if xs.nonEmpty =>
        val els = xs.map(_.original)
        kind % 7 match
          case 0 => Some(head(4, els.size + 1L) ++ concat(els) ++ pick(els)) // an element twice
          case 1 =>
            val i = rnd.nextInt(els.size)
            Some(head(4, els.size - 1L) ++ concat(els.patch(i, Nil, 1)))
          case 2 => // a set tag over it, or a tag that is not one
            Some(
              Bytes.wrap(Array(0xd9.toByte, 0x01.toByte, pick(Vector(0x02, 0x03)).toByte)) ++
                n.original
            )
          case 3 =>
            Some(Bytes.wrap(Array(0x9f.toByte)) ++ concat(els) ++ Bytes.wrap(Array(0xff.toByte)))
          case 4 => Some(Bytes.wrap(Array(0x80.toByte)))
          case 5 => // a detached record: an empty indefinite list, its fields after it
            Some(emptyIndefinite ++ concat(els))
          case _ =>
            Some(head(4, els.size + 1L) ++ concat(els) ++ concat(els.take(1))) // first twice
      case Value.Tag(t, inner) =>
        kind % 3 match
          case 0 => Some(inner.original) // untagged
          case 1 => Some(head(6, (t + 1).toLong) ++ inner.original)
          case _ => Some(head(6, 258) ++ head(4, 2) ++ inner.original ++ inner.original)
      case Value.UInt(v) =>
        kind % 5 match
          case 0 => Some(head(0, 0))
          case 1 => Some(Bytes.wrap(Array(0x1b.toByte) ++ Array.fill(8)(0xff.toByte)))
          case 2 => // in a head of eight bytes
            Some(
              Bytes.wrap(0x1b.toByte +: Array.tabulate(8)(i => (v.toLong >>> (8 * (7 - i))).toByte))
            )
          case 3 => Some(emptyIndefinite)
          case _ => Some(head(1, 0)) // negative
      case Value.Text(t) =>
        val utf8 = Bytes.wrap(t.getBytes(StandardCharsets.UTF_8))
        kind % 3 match
          case 0 => // chunked, in two chunks
            val cut = utf8.size / 2
            Some(
              Bytes.wrap(Array(0x7f.toByte)) ++ head(3, cut.toLong) ++ utf8.take(cut) ++
                head(3, (utf8.size - cut).toLong) ++ utf8.drop(cut) ++ Bytes.wrap(
                  Array(0xff.toByte)
                )
            )
          case 1 => // past 64 bytes
            val long = Bytes.wrap(Array.fill(65)(0x61.toByte))
            Some(head(3, long.size.toLong) ++ long)
          case _ => Some(head(3, 0)) // empty
      case Value.ByteString(b) =>
        kind % 4 match
          case 0 => Some(head(2, b.size + 1L) ++ b ++ Bytes.wrap(Array(0.toByte))) // one byte more
          case 1 => Some(head(2, (b.size - 1).max(0).toLong) ++ b.take(b.size - 1))
          case 2 => Some(Bytes.wrap(Array(0x40.toByte))) // empty
          case _ => // chunked
            Some(
              Bytes.wrap(Array(0x5f.toByte)) ++ head(2, b.size.toLong) ++ b ++ Bytes.wrap(
                Array(0xff.toByte)
              )
            )
      case _ =>
        Some(hex(pick(Vector("f6", "f5", "f4", "00", "80", "a0", "40", "60", "9fff"))))

  /** One mutant of `raw` by `seed`: a structural one of an item of its tree, or a byte-level one.
    */
  private def mutant(raw: Bytes, seed: Long): (Bytes, String) =
    val rnd = new Random(seed)
    def bytes(f: Array[Byte] => Array[Byte]) = Bytes.wrap(f(raw.toArray))
    rnd.nextInt(10) match
      case 0 => (raw.take(rnd.nextInt(raw.size + 1)), "truncated")
      case 1 =>
        (
          bytes { a =>
            val i = rnd.nextInt(a.length); a.updated(i, (a(i) ^ (1 << rnd.nextInt(8))).toByte)
          },
          "bit flipped"
        )
      case 2 =>
        val from = rnd.nextInt(raw.size); val len = 1 + rnd.nextInt(8.min(raw.size - from))
        (raw.take(from) ++ raw.drop(from + len), "range deleted")
      case _ =>
        node(raw).fold((raw, "not cbor")) { root =>
          val all = items(root).drop(1)
          if all.isEmpty then (raw, "unchanged")
          else
            val (n, at, part) = all(rnd.nextInt(all.size))
            rewrite(n, rnd.nextInt(1 << 20), rnd).fold((raw, "unchanged"))(item =>
              (raw.take(at) ++ item ++ raw.drop(at + n.original.size), part)
            )
        }

  // the seeds: every whole transaction above, the larger ones but a few, so that a run stays short
  private lazy val seeds: Vector[Bytes] =
    assert(seedCorpora.forall(refusalCorpora.contains), s"seed corpora missing: $seedCorpora")
    val all = wholeSources
      .map((name, txs) => if name == "refusal corpora" then refusalTxsOf(seedCorpora) else txs)
      .flatMap(identity)
      .distinct
    val (small, large) = all.partition(_.size <= 4096)
    small ++ large.take(8)

  private lazy val propertySeeds: Vector[Bytes] = seeds ++ scriptedOutputs.map(FlatTx.envelope)

  private val mutants: Gen[(Bytes, String)] =
    for
      i <- Gen.choose(0, Int.MaxValue)
      seed <- Gen.long
    yield mutant(propertySeeds(i % propertySeeds.size), seed)

  property("a mutated transaction reads the same in one pass as in two, or is refused the same") {
    // and the projection never refuses what the flat decoder accepts
    Prop.forAll(mutants) { (raw, what) =>
      val o = sameWhole(raw, what)
      Prop(o != Outcome.ProjectionRefused) :| s"$what: the projection refused"
    }
  }

  private def fixedMutant(i: Int): (Bytes, String) =
    mutant(seeds(i % seeds.size), i.toLong * 7919L)

  test(
    "the fixed mutants reach every part of a transaction, both outcomes and the recorded refusals"
  ) {
    val read = (0 until ConwayOnePassExpected.fixedMutants.size).map { i =>
      val (raw, part) = fixedMutant(i)
      (part, sameWhole(raw, s"mutant $i ($part)"))
    }
    val outcomes = tally(read.map(_._2).toVector)
    val parts = read.map(_._1).toSet
    assert(outcomes.getOrElse(Outcome.Accepted, 0) > 100, outcomes.toString)
    assert(outcomes.getOrElse(Outcome.Refused, 0) > 1000, outcomes.toString)
    assert(!outcomes.contains(Outcome.ProjectionRefused), outcomes.toString)
    Vector("part0", "part1", "part2", "part3").foreach(p =>
      assert(parts.exists(_.startsWith(p)), s"no mutant of $p: $parts")
    )
    Vector("part0:0", "part0:1", "part0:2", "part0:4", "part1:0", "part1:5", "part3:0")
      .foreach(k => assert(parts.contains(k), s"no mutant under $k: $parts"))
    // as the two passes gave them, recorded: each mutant's code in its mempool form
    read.indices.foreach { i =>
      val (raw, part) = fixedMutant(i)
      val got = code(node(raw).map(_ => ConwayTx.fromEnvelope(raw, StateLayout.Node11_1)))
      assertEquals(got, ConwayOnePassExpected.fixedMutants(i), s"mutant $i ($part)")
    }
  }

  // ---- deterministic cases, written by hand

  private def u(n: Long): Bytes = head(0, n)
  private def arr(xs: Bytes*): Bytes = head(4, xs.size.toLong) ++ concat(xs.toVector)
  private def mp(kvs: (Bytes, Bytes)*): Bytes =
    head(5, kvs.size.toLong) ++ concat(kvs.toVector.flatMap((k, v) => Vector(k, v)))
  private def tag(t: Long, x: Bytes): Bytes = head(6, t) ++ x
  private def bytes(b: Bytes): Bytes = head(2, b.size.toLong) ++ b
  private val nul = hex("f6")
  private val text = hex("60")

  /** An address with a key credential, of the test network, and an output of it in the map form. */
  private val address = hex("60" + "22" * 28)
  private def output(extra: (Int, Bytes)*): Bytes =
    val fields = (Map(0 -> bytes(address), 1 -> u(2000000)) ++ extra).toVector.sortBy(_._1)
    mp(fields.map((k, v) => u(k.toLong) -> v)*)

  /** A body with the keys a body requires, its inputs and outputs empty, and `extra`. */
  private def body(extra: (Int, Bytes)*): Bytes =
    val fields = (Map(0 -> arr(), 1 -> arr(), 2 -> u(0)) ++ extra).toVector.sortBy(_._1)
    mp(fields.map((k, v) => u(k.toLong) -> v)*)

  /** Both forms of a transaction, its parts and its mempool form, read both ways: the same result
    * each way in each form, the two forms' the same; that result.
    */
  private def bothForms(
      b: Bytes,
      w: Bytes,
      aux: Bytes,
      what: String
  ): Either[EraTxError, ConwayTx] =
    val auxiliary = if aux == nul then None else Some(aux)
    val t = Block.Transaction(b, w, auxiliary, true)
    sameParts(t, what)
    sameEnvelope(FlatTx.envelope(t), s"$what, mempool form")
    sameEnvelope(arr(b, w, aux), s"$what, mempool form of three")
    val read = ConwayTx.decode(t, StateLayout.Node11_1)
    assertEquals(ConwayTx.fromEnvelope(FlatTx.envelope(t), StateLayout.Node11_1), read, what)
    read

  test("of competing refusals in a transaction, the first is the two passes' first") {
    // `[body, witnesses, auxiliary data]`, the two passes' refusal recorded for each; the flat
    // decoder reads a body's keys in its own order (unknown keys first, then 0, 1, 2, 3, 8, 4, 5,
    // 7, 9, 11, 13 to 22) and the witness set's (0, 1, 2, 3, 6, 7, 4, 5), where the reference
    // reads them as written, and the tagged auxiliary data's values as written
    val cases = Vector(
      ("body key 0 and witness key 0", body(0 -> u(0)), mp(u(0) -> u(0)), nul),
      ("a body and auxiliary data", body(0 -> u(0)), mp(), u(0)),
      ("a witness set and auxiliary data", body(), mp(u(0) -> u(0)), u(0)),
      ("a body's keys 0 and 5", body(0 -> u(0), 5 -> u(0)), mp(), nul),
      ("a body's key 0 and an unknown key 6", body(0 -> u(0), 6 -> u(0)), mp(), nul),
      ("a body's keys 4 and 8, 4 written first", body(4 -> u(0), 8 -> text), mp(), nul),
      ("a body's keys 9 (no policy) and 11", body(9 -> mp(), 11 -> u(0)), mp(), nul),
      ("a body's keys 20 and 22 (zero)", body(20 -> u(0), 22 -> u(0)), mp(), nul),
      ("a body's key 22 (zero) and an output", body(1 -> arr(u(0)), 22 -> u(0)), mp(), nul),
      ("a witness set's keys 5 and 1, 1 written last", body(), mp(u(5) -> u(0), u(1) -> u(0)), nul),
      ("a witness set's keys 4 and 3", body(), mp(u(3) -> u(0), u(4) -> u(0)), nul),
      (
        "auxiliary data's keys 1 and 0, 1 written first",
        body(),
        mp(),
        tag(259, mp(u(1) -> u(0), u(0) -> u(0)))
      ),
      ("auxiliary data's key 0 twice", body(), mp(), tag(259, mp(u(0) -> u(0), u(0) -> mp()))),
      ("auxiliary data under another tag", body(), mp(), tag(260, mp())),
      (
        "an output's inline datum not data and its reference script",
        body(
          1 -> arr(
            output(2 -> arr(u(1), tag(24, bytes(hex("ff")))), 3 -> tag(24, bytes(hex("00"))))
          )
        ),
        mp(),
        nul
      ),
      ("an output of four items", body(1 -> arr(arr(bytes(address), u(1), u(0), u(0)))), mp(), nul)
    )
    import xyz.kd5ujc.scarab.ledger.DecodeField as F
    import LedgerDecodeError.{Expected, Shape}
    val zero = Value.UInt(BigInt(0))
    def item(b: Bytes) = node(b).getOrElse(fail(b.hex)).value
    // the fifth, sixth and tenth are not the reference's first: it reads the keys as written
    // (`SparseKeyed`), and refuses key 0, key 4 and key 5 first
    val expected: Vector[LedgerDecodeError] = Vector(
      Expected(F.Inputs, Shape.Array, zero),
      Expected(F.Inputs, Shape.Array, zero),
      Expected(F.VkeyWitnesses, Shape.Array, zero),
      Expected(F.Inputs, Shape.Array, zero),
      LedgerDecodeError.UnknownFields(F.TransactionBody, Set(6)),
      Expected(F.ValidityStart, Shape.Unsigned, Value.Text("")),
      LedgerDecodeError.Empty(F.Mint),
      Expected(F.ProposalProcedures, Shape.Array, zero),
      LedgerDecodeError.OutputForm(xyz.kd5ujc.scarab.ledger.TxOut.Forms.PairDatumHashOrMap, zero),
      Expected(F.NativeScripts, Shape.Array, zero),
      Expected(F.PlutusScripts, Shape.Array, zero),
      Expected(F.AuxiliaryNativeScripts, Shape.Array, zero),
      LedgerDecodeError.DuplicateKey(F.AuxiliaryData),
      Expected(F.AuxiliaryData, Shape.ArrayOrMap, item(tag(260, mp()))),
      LedgerDecodeError.PlutusData(
        F.InlineDatum,
        xyz.kd5ujc.scarab.plutus.PlutusData.DecodeError
          .Syntax(Cbor.Error.UnsupportedSimpleValue(31))
      ),
      LedgerDecodeError.OutputForm(
        xyz.kd5ujc.scarab.ledger.TxOut.Forms.PairDatumHashOrMap,
        item(arr(bytes(address), u(1), u(0), u(0)))
      )
    )
    assertEquals(expected.size, cases.size)
    cases.zip(expected).foreach { case ((what, b, w, aux), e) =>
      val got = bothForms(b, w, aux, what)
      assertEquals(got, Left(EraTxError.Decode(e)), what)
      assertEquals(
        code(Some(got)),
        ConwayOnePassExpected.competing(cases.indexWhere(_._1 == what)),
        what
      )
    }
  }

  test(
    "Plutus data of every form, in datums, redeemers and inline datums, reads the same both ways"
  ) {
    import xyz.kd5ujc.scarab.plutus.PlutusData
    import PlutusData.{B, Constr, I, List as L, Map as M}
    val two64 = BigInt(1) << 64
    val sixtyFour = Bytes.wrap(Array.tabulate(64)(i => i.toByte))
    val accepted: Vector[(String, PlutusData)] = Vector(
      "d86682188080" -> Constr(128, Vector.empty),
      "d866821903e880" -> Constr(1000, Vector.empty),
      "1bffffffffffffffff" -> I(two64 - 1),
      "3bffffffffffffffff" -> I(-two64),
      "c248ffffffffffffffff" -> I(two64 - 1),
      "c249010000000000000000" -> I(two64),
      "c348ffffffffffffffff" -> I(-two64),
      "c349010000000000000000" -> I(-1 - two64),
      s"5840${sixtyFour.hex}" -> B(sixtyFour),
      s"5f5840${sixtyFour.hex}4101ff" -> B(sixtyFour ++ hex("01")),
      "5fff" -> B(Bytes.empty),
      "8201820203" -> L(Vector(I(1), L(Vector(I(2), I(3))))),
      "a1a1010281a0" -> M(Vector(M(Vector(I(1) -> I(2))) -> L(Vector(M(Vector.empty))))),
      "9f01a0ff" -> L(Vector(I(1), M(Vector.empty))),
      "d87982a1010280" -> Constr(0, Vector(M(Vector(I(1) -> I(2))), L(Vector.empty)))
    ) ++ (121 to 127).map(t =>
      tag(t.toLong, arr(u(t.toLong))).hex -> Constr(t - 121, Vector(I(t)))
    ) ++
      Vector(1280, 1281, 1350, 1400).map(t =>
        tag(t.toLong, arr(u(t.toLong))).hex -> Constr(t - 1280 + 7, Vector(I(t)))
      )
    val data = accepted.map((h, d) => hex(h) -> d)
    val units = arr(u(1), u(2))
    def redeemerMap(xs: Vector[Bytes]) =
      mp(xs.zipWithIndex.map((d, i) => arr(u(0), u(i.toLong)) -> arr(d, units))*)
    def redeemerList(xs: Vector[Bytes]) =
      arr(xs.zipWithIndex.map((d, i) => arr(u(0), u(i.toLong), d, units))*)
    val inlines = data.map((d, _) => output(2 -> arr(u(1), tag(24, bytes(d)))))
    for redeemers <- Vector(redeemerMap, redeemerList) do
      val wits = mp(u(4) -> arr(data.map(_._1)*), u(5) -> redeemers(data.map(_._1)))
      val tx = bothForms(body(1 -> arr(inlines*)), wits, nul, "every form of data")
        .fold(e => fail(e.toString), identity)
      val datums = tx.wits.datums.datums.values.toVector
      val read = tx.wits.redeemers.entries.values.toVector
      assertEquals(datums.size, data.size)
      assertEquals(read.size, data.size)
      data.foreach { (raw, expected) =>
        assertEquals(
          datums.filter(_.originalBytes == raw).map(_.getPlutusData),
          Vector(expected),
          raw.hex
        )
        assertEquals(
          read.filter(_._1.originalBytes == raw).map(_._1.getPlutusData),
          Vector(expected),
          raw.hex
        )
      }
    // and the forms the decoder refuses: a chunk of more than 64 bytes, a big number's wide head,
    // a constructor tag out of the two ranges, as a datum, a redeemer's data in either form and an
    // inline datum
    import xyz.kd5ujc.scarab.ledger.DecodeField
    val sixtyFive = sixtyFour ++ hex("01")
    val refused = Vector(
      s"5841${sixtyFive.hex}" -> PlutusData.DecodeError.ByteStringChunkLimit,
      s"5f5841${sixtyFive.hex}ff" -> PlutusData.DecodeError.ByteStringChunkLimit,
      "d80248ffffffffffffffff" -> PlutusData.DecodeError.BigNumberWideHead(BigInt(2)),
      "d87880" -> PlutusData.DecodeError.UnexpectedTag(BigInt(120)),
      "d88080" -> PlutusData.DecodeError.UnexpectedTag(BigInt(128)),
      "d904ff80" -> PlutusData.DecodeError.UnexpectedTag(BigInt(1279)),
      "d9057980" -> PlutusData.DecodeError.UnexpectedTag(BigInt(1401))
    )
    def refusal(site: DecodeField, e: PlutusData.DecodeError) =
      Left(EraTxError.Decode(LedgerDecodeError.PlutusData(site, e)))
    refused.foreach { (h, e) =>
      val d = hex(h)
      assertEquals(
        bothForms(body(), mp(u(4) -> arr(d)), nul, s"datum $h"),
        refusal(DecodeField.Datums, e)
      )
      for redeemers <- Vector(redeemerMap, redeemerList) do
        assertEquals(
          bothForms(body(), mp(u(5) -> redeemers(Vector(d))), nul, s"redeemer $h"),
          refusal(DecodeField.Redeemer, e)
        )
      assertEquals(
        bothForms(
          body(1 -> arr(output(2 -> arr(u(1), tag(24, bytes(d)))))),
          mp(),
          nul,
          s"inline $h"
        ),
        refusal(DecodeField.InlineDatum, e)
      )
    }
  }

  /** Each key given each of these values in turn, alone: one is no field's form (a text), the
    * others some fields' forms and not others'.
    */
  private val faults: Vector[Bytes] =
    Vector("60", "00", "80", "a0", "f6", "1bffffffffffffffff", "d9010280", "4100", "8100", "8140")
      .map(hex)

  /** The fault table: each part's keys, each given each fault, read both ways in both forms. */
  private lazy val table: Vector[(String, Vector[(Int, Bytes, Either[EraTxError, ConwayTx])])] =
    def rows(what: String, keys: Range)(make: (Int, Bytes) => (Bytes, Bytes, Bytes)) =
      for
        k <- keys.toVector
        v <- faults
      yield
        val (b, w, aux) = make(k, v)
        (k, v, bothForms(b, w, aux, s"$what key $k = ${v.hex}"))
    Vector(
      "body" -> rows("body", 0 to 23)((k, v) => (body(k -> v), mp(), nul)),
      "output" -> rows("output", 0 to 4)((k, v) => (body(1 -> arr(output(k -> v))), mp(), nul)),
      "witness set" -> rows("witness set", 0 to 8)((k, v) => (body(), mp(u(k.toLong) -> v), nul)),
      "auxiliary data" ->
        rows("auxiliary data", 0 to 6)((k, v) => (body(), mp(), tag(259, mp(u(k.toLong) -> v))))
    )

  test("a fault at each key of a body, an output, a witness set and auxiliary data") {
    // a text is no field's form: refused at every key
    table.foreach { (what, rows) =>
      assert(rows.filter(_._2 == text).forall(_._3.isLeft), s"$what: a text accepted")
      val counts = tally(rows.map(r => if r._3.isRight then Outcome.Accepted else Outcome.Refused))
      assert(counts.size == 2, s"$what: $counts")
    }
    // as the two passes gave them, recorded: every row's code, in order
    assertEquals(
      table.map((what, rows) => what -> rows.map(r => code(Some(r._3))).mkString(",")),
      ConwayOnePassExpected.faultTable
    )
  }

  /** Transactions whose outputs hold an inline datum and a reference script, a native one of
    * several levels or a Plutus one, in the map form, the collateral return among them.
    */
  private lazy val scriptedOutputs: Vector[Block.Transaction] =
    val key = bytes(hex("11" * 28))
    val native = arr(
      u(1),
      arr(arr(u(0), key), arr(u(2), arr(arr(u(0), key), arr(u(4), u(100)))), arr(u(5), u(9)))
    )
    val plutus = arr(u(3), bytes(hex("4e4d01000033222220051200120011")))
    val datum = arr(u(1), tag(24, bytes(hex("d8799f0102ff"))))
    def out(script: Bytes) = output(2 -> datum, 3 -> tag(24, bytes(script)))
    val reference = arr(u(0), native)
    Vector(
      Block.Transaction(body(1 -> arr(out(reference), out(plutus))), mp(), None, true),
      Block.Transaction(body(1 -> arr(out(plutus)), 16 -> out(reference)), mp(), None, false),
      Block.Transaction(
        body(1 -> arr(output(2 -> arr(u(0), bytes(hex("33" * 32)))), out(reference))),
        mp(u(1) -> arr(native), u(7) -> arr(bytes(hex("4e4d01000033222220051200120011")))),
        Some(tag(259, mp(u(1) -> arr(native), u(4) -> arr(bytes(hex("01"))), u(2) -> arr()))),
        true
      )
    )

  test("outputs with an inline datum and a reference script read the same both ways") {
    scriptedOutputs.zipWithIndex.foreach { (t, i) =>
      assertEquals(
        sameParts(t, s"scripted outputs $i"),
        Outcome.Accepted,
        ConwayTx.decode(t, StateLayout.Node11_1).toString
      )
      assertEquals(sameEnvelope(FlatTx.envelope(t), s"scripted outputs $i"), Outcome.Accepted)
      val tx = ConwayTx.decode(t, StateLayout.Node11_1).fold(e => fail(e.toString), identity)
      val outs = tx.body.outputs ++ tx.body.collateralReturn.toVector
      assert(
        outs.forall(o => o.value.rep.scriptRef.isDefined || o.value.rep.datum != Datum.NoDatum)
      )
    }
  }

  test(
    "deep data in a datum, a redeemer and an inline datum reads the same both ways on a small stack"
  ) {
    // a list nested 20,000 deep, far deeper than a recursion over it fits on the small stack; the
    // results are compared by their bytes, ids and hashes, since equality of the data would recurse
    val depth = 20000
    val deep = Bytes.wrap(Array.fill(depth)(0x81.toByte) :+ 0x00.toByte)
    val t = Block.Transaction(
      body(1 -> arr(output(2 -> arr(u(1), tag(24, bytes(deep)))))),
      mp(
        u(4) -> arr(deep),
        u(5) -> mp(arr(u(0), u(0)) -> arr(deep, arr(u(1), u(2))))
      ),
      None,
      true
    )
    val whole = FlatTx.envelope(t)
    def summary(r: Either[EraTxError, ConwayTx]) = r.map(tx =>
      (
        tx.body.id,
        tx.body.outputs.map(o => (ConwayTxOut.toStored(o.value), o.size)),
        tx.wits.raw,
        tx.wits.datums.datums.toVector.map((h, d) => (h, d.originalBytes)),
        tx.wits.redeemers.entries.toVector.map((p, v) => (p, v._1.originalBytes, v._2)),
        tx.sizeTxForFeeCalculation
      )
    )
    val read = PreprodDeepTx.onSmallStack(
      layouts.map(l =>
        (
          summary(ConwayTx.decode(t, l)),
          summary(ConwayTx.decodeByFlat(t, l)),
          summary(ConwayTx.fromEnvelope(whole, l)),
          summary(ConwayTx.fromEnvelopeByFlat(whole, l))
        )
      )
    )
    read.zip(layouts).foreach { case ((one, two, oneWhole, twoWhole), l) =>
      assert(one.isRight, s"$l: $one")
      assertEquals(one, two, l.toString)
      assertEquals(oneWhole, one, l.toString)
      assertEquals(twoWhole, one, l.toString)
      one.foreach(x => assertEquals(x._4.map(_._2), Vector(deep)))
    }
  }
