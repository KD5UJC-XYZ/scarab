// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.syntax.all.*
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Node}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.protocol.Era

import ReferenceCorpus.{Case, Corpus, Kind}

/** Below decoder version 12 the reference reads a `RecD` record as a definite list only:
  * `decodeCount (RecD cn) n` reads the length around a decoder that reads nothing
  * (`libs/cardano-ledger-binary/src/Cardano/Ledger/Binary/Decoding/Coders.hs:482`, cardano-ledger
  * f649f975), so an indefinite list fails with "Excess terms in array" ([[CborRead.record]]).
  *
  * `fixtures/reference/recd-records/cases.jsonl` holds the `RecD` records a block can hold, each as
  * a definite and an indefinite list: the update proposal of a body (Shelley to Babbage), the
  * execution units of a redeemer (Alonzo to Conway, also at and over their bounds), and Conway's
  * `GovActionId` (also at and over the bound of its index), `VotingProcedure`, `ProposalProcedure`,
  * `Constitution` and `Anchor` in each place a body has one, the `MaryValue` of an output in each
  * of its forms and of a collateral return (Mary to Conway), and the list form of auxiliary data in
  * a transaction (Allegra to Conway). Evidence for #668.
  */
class RecdRecordsReferenceSuite extends FunSuite:
  private val rows: Vector[Case] =
    ReferenceCorpus.load(Corpus.RecdRecords).fold(e => fail(e.toString), identity)

  /** Scarab's verdict and, for an accepted encoding, the original bytes it kept. */
  private def scarab(row: Case): Either[LedgerDecodeError, Bytes] = row.kind match
    case Kind.Body        => TxBody.decode(row.raw, row.era).map(_.raw)
    case Kind.Witnesses   => Witnesses.decode(row.raw, row.era).map(_.raw.getOrElse(Bytes.empty))
    case Kind.Transaction => Tx.fromEnvelope(row.raw, row.era).map(_ => row.raw)

  test("the corpus covers every era from Shelley to Conway, refused and accepted") {
    assertEquals(
      rows.map(_.era).toSet,
      Set(Era.Shelley, Era.Allegra, Era.Mary, Era.Alonzo, Era.Babbage, Era.Conway)
    )
    assertEquals(rows.map(_.name).distinct.size, rows.size)
    for era <- rows.map(_.era).distinct do
      assert(rows.exists(r => r.era == era && r.accepted), s"no accepted $era case")
      assert(rows.exists(r => r.era == era && !r.accepted), s"no refused $era case")
    for row <- rows do
      assertEquals(row.accepted, row.expect.isEmpty, row.name)
      assertEquals(row.accepted, row.rule.isEmpty, row.name)
      row.rule.foreach(r => assert(row.failure.exists(r.explains), s"${row.name}: ${row.failure}"))
  }

  test("every indefinite record the reference refuses has a definite twin it accepts") {
    val byName = rows.map(r => r.name -> r).toMap
    val indefinite = rows.filter(_.name.endsWith(": indefinite"))
    assert(indefinite.nonEmpty)
    for row <- indefinite do
      val twin = byName.get(row.name.stripSuffix(": indefinite") + ": definite")
      assert(twin.exists(_.accepted), s"${row.name}: no accepted definite twin")
      assert(!row.accepted, row.name)
  }

  /** The fields of a body, by key. */
  private def fields(raw: Bytes): Map[Int, Node] =
    CborRead
      .decode(raw)
      .flatMap(CborRead.fields(_, DecodeField.TransactionBody))
      .fold(e => fail(e.toString), identity)

  /** The outputs of a body: those of key 1, then the collateral return of key 16. */
  private def outputs(raw: Bytes): Vector[Node] =
    val f = fields(raw)
    f.get(1)
      .traverse(CborRead.array(_, DecodeField.Outputs))
      .fold(e => fail(e.toString), _.getOrElse(Vector.empty)) ++ f.get(16).toVector

  test("an output whose value a body refuses as an indefinite list still loads as stored") {
    // A build before this rule took such an output from a body, and the stores read it back with
    // `TxOut.fromBytes` in every form they wrote (ADR 0006).
    given ByteStrings = ByteStrings.Chunked
    val byName = rows.map(r => r.name -> r).toMap
    val values = rows.filter(r => r.kind == Kind.Body && r.name.endsWith("value: indefinite"))
    assertEquals(values.map(_.era).toSet, Set(Era.Mary, Era.Alonzo, Era.Babbage, Era.Conway))
    for row <- values do
      val twin = byName.getOrElse(
        row.name.stripSuffix(": indefinite") + ": definite",
        fail(s"${row.name}: no definite twin")
      )
      // The output that holds the record: the one the block's rule refuses.
      val pairs = outputs(row.raw)
        .zip(outputs(twin.raw))
        .filter((o, _) => TxOut.decode(o, Address.Mode.of(row.era), TxOut.Forms.of(row.era)).isLeft)
      assertEquals(pairs.size, 1, row.name)
      for (indefinite, definite) <- pairs do
        assertEquals(
          TxOut.decode(indefinite, Address.Mode.of(row.era), TxOut.Forms.of(row.era)).left.toOption,
          Some(LedgerDecodeError.IndefiniteLength(DecodeField.OutputValue)),
          s"${row.name}: the block's rule"
        )
        val stored = TxOut.fromBytes(indefinite.original)
        assertEquals(stored.map(_.raw), Right(indefinite.original), s"${row.name}: the stored rule")
        assertEquals(
          stored.map(_.value),
          TxOut.fromBytes(definite.original).map(_.value),
          s"${row.name}: the same value as the definite list"
        )
  }

  for row <- rows do
    test(s"${row.era} ${row.kind}: ${row.name}") {
      (row.expect, scarab(row)) match
        case (None, Right(retained)) =>
          assertEquals(retained, row.raw, "the original bytes are retained")
          if row.kind == Kind.Body then
            assertEquals(
              TxBody.decode(row.raw, row.era).map(_.id),
              Right(Blake2b.hash256.hash(row.raw)),
              "the id hashes the original bytes"
            )
        case (None, Left(e))     => fail(s"the reference accepts this encoding: $e")
        case (Some(_), Right(_)) => fail(s"the reference refuses this encoding: ${row.failure}")
        case (Some(refusal), Left(e)) =>
          assertEquals(ReferenceCorpus.Refusal.of(e), Some(refusal), s"Scarab gave $e")
      // The bootstrap ledger reads the update proposal of an era with PPUP, and no output.
      if row.kind == Kind.Body && ParamUpdate.Rules.of(row.era).isDefined && fields(row.raw)
          .contains(6)
      then
        assertEquals(
          LiteLedger.effects(row.raw, row.era).isRight,
          row.accepted,
          "the bootstrap ledger's reading"
        )
    }
