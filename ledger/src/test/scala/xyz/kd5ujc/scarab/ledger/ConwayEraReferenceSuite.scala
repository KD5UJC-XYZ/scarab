// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import cats.syntax.all.*
import io.circe.parser
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger.era.{EraTxError, IsPhase2Valid}
import xyz.kd5ujc.scarab.ledger.era.conway.{ConwayTx, ConwayTxAuxData, ConwayTxBody, ConwayTxWits}
import xyz.kd5ujc.scarab.protocol.{Era, StateLayout}

/** Conway's era decoders, which read a transaction's parts in one pass ([[ConwayTxBody.decode]],
  * [[ConwayTxWits.decode]], [[ConwayTxAuxData.decode]], [[ConwayTx.decode]] and
  * [[ConwayTx.fromEnvelope]], #85), held to the reference's own answers: every Conway case of the
  * recorded reference corpora, each encoding with what the unchanged released decoders of
  * cardano-ledger f649f975 did with it. The era decoders must give the reference's verdict and, for
  * a refusal, the case the corpus names ([[ReferenceCorpus.Refusal]]), the rule the era-decoders
  * corpus names, or a refusal of the data the Plutus places corpus names; for an accepted encoding,
  * the bytes they keep are the original ones, the id hashes the body's, and the id, the body hash
  * and the relayed bytes are those the reference gave where the corpus records them. A whole
  * transaction is read both as a client submits it ([[ConwayTx.fromEnvelope]]) and as the node's
  * pool reads it, its parts split from its bytes and then read in their block form
  * ([[ConwayTx.decode]]), which must agree. A refusal that is no decoder's (an [[EraTxError]] that
  * is not a decoding error) fails the case: the corpora hold no encoding the projection alone
  * refused.
  */
class ConwayEraReferenceSuite extends FunSuite:
  private val layouts: Vector[StateLayout] = StateLayout.values.toVector

  /** The decoder's error of a refusal; any other refusal fails the case. */
  private def decoding[A](what: String)(r: Either[EraTxError, A]): Either[LedgerDecodeError, A] =
    r.leftMap {
      case EraTxError.Decode(e) => e
      case other                => fail(s"$what: refused by no decoder: $other")
    }

  /** `read` at every layout, whose verdicts and refusals must agree (a pool's parameters are read
    * in the layout, so the values may not); the first layout's.
    */
  private def atEveryLayout[A](what: String)(
      read: StateLayout => Either[EraTxError, A]
  ): Either[LedgerDecodeError, A] =
    val all = layouts.map(read)
    all.foreach(r => assertEquals(r.void, all.head.void, s"$what: the layouts differ"))
    decoding(what)(all.head)

  private def body(raw: Bytes, what: String): Either[LedgerDecodeError, ConwayTxBody] =
    atEveryLayout(what)(ConwayTxBody.decode(raw, _))

  private def wits(raw: Bytes, what: String): Either[LedgerDecodeError, ConwayTxWits] =
    decoding(what)(ConwayTxWits.decode(raw))

  private def aux(raw: Bytes, what: String): Either[LedgerDecodeError, ConwayTxAuxData] =
    decoding(what)(ConwayTxAuxData.decode(raw))

  /** The parts of a whole transaction, as the node's pool splits them before reading them. */
  private def parts(raw: Bytes): Either[LedgerDecodeError, Block.Transaction] =
    DetachedRecords.read(
      raw,
      Cbor.Limits.Unbounded,
      DetachedRecords.Shapes.transaction(Era.Conway)
    )(LedgerDecodeError.Syntax(_))(Tx.envelopeParts(_, Era.Conway))

  /** A whole transaction read as a client submits it, and as the pool reads it: the same either
    * way, its parts' bytes the parts the pool split when it is accepted.
    */
  private def whole(raw: Bytes, what: String): Either[LedgerDecodeError, ConwayTx] =
    val submitted = atEveryLayout(what)(ConwayTx.fromEnvelope(raw, _))
    val pool = parts(raw).flatMap(t =>
      atEveryLayout(s"$what, its parts")(ConwayTx.decode(t, _)).map(tx => (t, tx))
    )
    assertEquals(pool.map(_._2), submitted, s"$what: the pool's reading")
    pool.foreach((t, tx) =>
      assertEquals(tx.body.raw, t.body, what)
      assertEquals(tx.wits.raw, t.witnesses, what)
      assertEquals(tx.auxData.map(_.raw), t.auxiliaryData, what)
    )
    submitted

  /** The verdict of the era decoders on a corpus case of `kind`, and what an accepted one kept as
    * it came.
    */
  private def read(kind: ReferenceCorpus.Kind, raw: Bytes, what: String) =
    kind match
      case ReferenceCorpus.Kind.Body =>
        body(raw, what).map { b =>
          assertEquals(b.id, Blake2b.hash256.hash(raw), s"$what: the id hashes the original bytes")
          b.raw
        }
      case ReferenceCorpus.Kind.Witnesses   => wits(raw, what).map(_.raw)
      case ReferenceCorpus.Kind.Transaction => whole(raw, what).map(_ => raw)

  private lazy val corpora: Vector[ReferenceCorpus.Case] =
    ReferenceCorpus.Corpus.values.toVector
      .flatMap(c => ReferenceCorpus.load(c).fold(e => fail(e.toString), identity))
      .filter(_.era == Era.Conway)

  test("the reference corpora hold Conway cases of every kind, accepted and refused") {
    for kind <- ReferenceCorpus.Kind.values do
      assert(corpora.exists(r => r.kind == kind && r.accepted), s"no accepted $kind case")
      assert(corpora.exists(r => r.kind == kind && !r.accepted), s"no refused $kind case")
    assert(corpora.sizeIs > 400, corpora.size.toString)
  }

  test("every Conway case of the reference corpora: the reference's verdict and refusal") {
    corpora.foreach { row =>
      val what = s"${row.kind} ${row.name}"
      (row.expect, read(row.kind, row.raw, what)) match
        case (None, Right(kept)) => assertEquals(kept, row.raw, s"$what: the bytes kept")
        case (None, Left(e))     => fail(s"$what: the reference accepts this encoding: $e")
        case (Some(_), Right(_)) => fail(s"$what: the reference refuses it: ${row.failure}")
        case (Some(r), Left(e)) =>
          assertEquals(ReferenceCorpus.Refusal.of(e), Some(r), s"$what: $e")
    }
  }

  test("every Conway envelope: the reference's verdict, transaction id and relayed bytes") {
    val rows = TxEnvelopeReferenceSuite.load.filter(_.era == Era.Conway)
    assert(rows.exists(_.accepted) && rows.exists(!_.accepted))
    rows.foreach { row =>
      whole(row.raw, row.name) match
        case Right(tx) =>
          assert(row.accepted, s"${row.name}: the reference refuses it: ${row.failure}")
          row.txid.foreach(id => assertEquals(tx.body.id.hex, id, s"${row.name}: the id"))
          row.wire.foreach(w =>
            assertEquals(
              parts(row.raw).map(Tx.envelope(_).hex),
              Right(w),
              s"${row.name}: the relayed bytes"
            )
          )
        case Left(e) => assert(!row.accepted, s"${row.name}: the reference accepts it: $e")
    }
    val byName = rows.map(r => r.name -> r).toMap
    def flag(name: String) = byName.get(name).map(r => whole(r.raw, name).map(_.isValid))
    assertEquals(
      flag("conway transaction without its flag"),
      Some(Right(IsPhase2Valid.Phase2Valid))
    )
    assertEquals(
      flag("conway transaction with its flag false"),
      Some(Right(IsPhase2Valid.Phase2Invalid))
    )
  }

  test("every Conway case with a detached record: the reference's verdict, id and parts") {
    import DetachedRecordsReferenceSuite.Kind
    val rows = DetachedRecordsReferenceSuite.load
      .filter(r => r.era == Era.Conway && r.kind != Kind.GovActionIds && r.kind != Kind.BlockBody)
    assert(rows.exists(_.accepted) && rows.exists(!_.accepted))
    rows.foreach { row =>
      val verdict: Either[LedgerDecodeError, Option[Bytes]] = row.kind match
        case Kind.Body        => body(row.raw, row.name).map(b => Some(b.id))
        case Kind.Witnesses   => wits(row.raw, row.name).as(None)
        case Kind.Auxiliary   => aux(row.raw, row.name).as(None)
        case Kind.Transaction => whole(row.raw, row.name).map(t => Some(t.body.id))
        case Kind.GovActionIds | Kind.BlockBody => fail(row.name)
      verdict match
        case Right(id) =>
          assert(row.accepted, s"${row.name}: the reference refuses it: ${row.failure}")
          row.txid.foreach(t => assertEquals(id.map(_.hex), Some(t), s"${row.name}: the id"))
        case Left(e) => assert(!row.accepted, s"${row.name}: the reference accepts it: $e")
    }
  }

  test("every Conway body and witness set of the era-decoders corpus: the reference's rule") {
    import EraDecodersReferenceSuite.{Kind, Refused, cause}
    val rows = EraDecodersReferenceSuite.load
      .fold(e => fail(e.toString), identity)
      .filter(r => r.era == Era.Conway && r.kind != Kind.BlockBody)
    assert(rows.nonEmpty)
    rows.foreach { row =>
      val read = row.kind match
        case Kind.Body      => body(row.raw, row.name).void
        case Kind.Witnesses => wits(row.raw, row.name).void
        case Kind.BlockBody => fail(row.name)
      (row.rule, read) match
        case (None, Right(()))    => ()
        case (None, Left(e))      => fail(s"${row.name}: the reference accepts it: $e")
        case (Some(_), Right(())) => fail(s"${row.name}: the reference refuses it: ${row.failure}")
        case (Some(rule), Left(e)) =>
          assert(cause(row, rule, Refused.Decode(e)), s"${row.name}: refused for another cause: $e")
    }
  }

  test("every Conway place of Plutus data: the reference's verdict, a refusal of the data") {
    val rows = Files
      .readAllLines(Path.of("fixtures/reference/plutus-data-places/cases.jsonl"))
      .asScala
      .toVector
      .filter(_.nonEmpty)
      .map(l => parser.parse(l).fold(e => fail(e.message), identity).hcursor)
      .filter(_.get[String]("era").contains("conway"))
    assert(rows.nonEmpty)
    rows.foreach { c =>
      def field[A: io.circe.Decoder](name: String): A =
        c.get[A](name).fold(e => fail(s"$name: ${e.message}"), identity)
      val name = field[String]("name")
      val raw = Bytes.fromHex(field[String]("bytes")).fold(e => fail(e.toString), identity)
      val read =
        if field[String]("kind") == "witnesses" then wits(raw, name).void else body(raw, name).void
      if field[Boolean]("accepted") then
        assert(read.isRight, s"$name: the reference reads it: $read")
      else
        assert(
          read match {
            case Left(LedgerDecodeError.PlutusData(_, _)) | Left(LedgerDecodeError.Syntax(_)) =>
              true
            case _ => false
          },
          s"$name: the reference refuses it: $read"
        )
    }
  }
