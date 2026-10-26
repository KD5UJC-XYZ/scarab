// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.protocol.Era

import ReferenceCorpus.{Case, Corpus, Refusal}

/** The forms of a transaction output and the keys of a body differ by era in the decoders of
  * cardano-ledger f649f975 (`Shelley/TxOut.hs`, `Alonzo/TxOut.hs`, `Babbage/TxOut.hs`; the
  * `TxBody.hs` of each era). `fixtures/reference/era-forms/cases.jsonl` holds original encodings
  * with what the unchanged released decoders did with each, and Scarab must do the same.
  */
class EraFormsReferenceSuite extends FunSuite:
  private val rows: Vector[Case] =
    ReferenceCorpus.load(Corpus.EraForms).fold(e => fail(e.toString), identity)

  test("the corpus covers every era from Shelley to Conway, refused and accepted") {
    assertEquals(
      rows.map(_.era).toSet,
      Set(Era.Shelley, Era.Allegra, Era.Mary, Era.Alonzo, Era.Babbage, Era.Conway)
    )
    for era <- rows.map(_.era).toSet do
      assert(rows.exists(r => r.era == era && r.accepted), s"no accepted $era case")
      assert(rows.exists(r => r.era == era && !r.accepted), s"no refused $era case")
    assertEquals(rows.map(_.name).distinct.size, rows.size)
  }

  test("every case was recorded for the rule it breaks") {
    for row <- rows do
      assertEquals(row.accepted, row.expect.isEmpty, row.name)
      assertEquals(row.accepted, row.rule.isEmpty, row.name)
      row.rule.foreach(r => assert(row.failure.exists(r.explains), s"${row.name}: ${row.failure}"))
  }

  for row <- rows do
    test(s"${row.era}: ${row.name}") {
      (row.expect, TxBody.decode(row.raw, row.era)) match
        case (None, Right(body)) =>
          assertEquals(body.raw, row.raw, "the original bytes are retained")
          assertEquals(body.id, Blake2b.hash256.hash(row.raw), "the id hashes the original bytes")
        case (None, Left(e))     => fail(s"the reference accepts this encoding: $e")
        case (Some(_), Right(_)) => fail(s"the reference refuses this encoding: ${row.failure}")
        case (Some(refusal), Left(e)) =>
          assertEquals(Refusal.of(e), Some(refusal), s"Scarab gave $e")
    }

  /** The stores kept an output in every form an earlier build accepted: [[TxOut.Forms.Stored]]. */
  test("an output stored in a form its era refuses in a block still reads, and keeps its bytes") {
    val refused = rows.filter(r =>
      r.expect.contains(Refusal.OutputForm) &&
        (r.name.endsWith("output: three elements") || r.name.endsWith("output: map"))
    )
    assert(refused.exists(_.era == Era.Shelley) && refused.exists(_.era == Era.Alonzo))
    for row <- refused do
      val raw = outputOf(row)
      assert(TxBody.decode(row.raw, row.era).isLeft, row.name)
      assertEquals(TxOut.fromBytes(raw).map(_.raw), Right(raw), row.name)
  }

  private def outputOf(row: Case): Bytes =
    val outputs = for
      root <- Cbor.decode(row.raw).toOption
      body <- CborRead.fields(root, DecodeField.TransactionBody).toOption
      field <- body.get(1)
      xs <- CborRead.array(field, DecodeField.Outputs).toOption
      out <- xs.headOption
    yield out.original
    outputs.getOrElse(fail(s"${row.name}: no output"))
