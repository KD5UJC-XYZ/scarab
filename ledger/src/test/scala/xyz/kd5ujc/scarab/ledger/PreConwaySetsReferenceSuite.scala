// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.Blake2b

import ReferenceCorpus.{Case, Corpus, Kind, Refusal}

/** Below decoder version 9 (Shelley to Babbage) the reference reads a set with `decodeSet`: tag 258
  * is not read, and elements that are equal once decoded are one element, whatever their encodings
  * (`Decoder.hs` in cardano-ledger f649f975). `fixtures/reference/pre-conway-sets` holds original
  * encodings with what the unchanged released decoders of each era did with them.
  */
class PreConwaySetsReferenceSuite extends FunSuite:
  private val rows: Vector[Case] =
    ReferenceCorpus.load(Corpus.PreConwaySets).fold(e => fail(e.toString), identity)

  /** The error or, for an accepted encoding, how many elements Scarab kept and the retained bytes.
    */
  private def scarab(row: Case): Either[LedgerDecodeError, (Int, Bytes)] = row.kind match
    case Kind.Body => TxBody.decode(row.raw, row.era).map(b => (b.inputs.size, b.raw))
    case Kind.Witnesses =>
      Witnesses.decode(row.raw, row.era).map(w => (w.vkeys.size, w.raw.getOrElse(Bytes.empty)))
    case Kind.Transaction => fail(s"the corpus holds no transaction: ${row.id}")

  test("the corpus covers every era from Shelley to Babbage, refused and accepted") {
    assertEquals(rows.map(_.era).distinct.size, 5)
    assert(rows.exists(_.accepted) && rows.exists(!_.accepted))
    assertEquals(rows.map(_.name).distinct.size, rows.size)
  }

  test(
    "every case was recorded for the rule it breaks, and the metadata agrees with the reference"
  ) {
    for row <- rows do
      assertEquals(row.accepted, row.expect.isEmpty, row.name)
      row.rule.foreach(r => assert(row.failure.exists(r.explains), s"${row.name}: ${row.failure}"))
  }

  for row <- rows do
    test(s"${row.era} ${row.kind}: ${row.name}") {
      (row.expect, scarab(row)) match
        case (None, Right((kept, retained))) =>
          assertEquals(Option(kept), row.size, "elements kept after collapsing by decoded value")
          if row.kind == Kind.Body then
            assertEquals(
              Blake2b.hash256.hash(retained),
              Blake2b.hash256.hash(row.raw),
              "the id hashes the original bytes"
            )
          assertEquals(retained, row.raw, "the original bytes are retained")
        case (None, Left(e))     => fail(s"the reference accepts this encoding: $e")
        case (Some(_), Right(_)) => fail(s"the reference refuses this encoding: ${row.failure}")
        case (Some(refusal), Left(e)) =>
          assertEquals(Refusal.of(e), Some(refusal), s"Scarab gave $e")
    }
