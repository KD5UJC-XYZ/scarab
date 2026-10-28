// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.protocol.Era

import ReferenceCorpus.{Case, Corpus, Kind}

/** Every text field of a block body is read with `decodeString`, which takes a definite-length
  * string in every decoder version (cborg through `instance DecCBOR Text`,
  * `libs/cardano-ledger-binary/src/Cardano/Ledger/Binary/Decoding/DecCBOR.hs:356-358`,
  * cardano-ledger f649f975); an indefinite (chunked) string is not text there. Transaction metadata
  * reads chunked text with its own reader and stays accepted.
  *
  * `fixtures/reference/definite-text/cases.jsonl` holds original bodies of each era with a pool
  * metadata url, and of a Conway body with a DRep registration, proposal or vote anchor url, each
  * written with a definite string and with one chunk, with what the unchanged released decoders did
  * with each. A registration's relay DNS name is refused by [[PoolRelays.decode]] but reaches the
  * body decoder only under #436, so its cases belong to that change.
  */
class DefiniteTextReferenceSuite extends FunSuite:
  private val rows: Vector[Case] =
    ReferenceCorpus.load(Corpus.DefiniteText).fold(e => fail(e.toString), identity)

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
    assert(rows.exists(_.accepted), "no accepted case")
    assert(rows.exists(!_.accepted), "no refused case")
    assertEquals(rows.map(_.name).distinct.size, rows.size)
  }

  test("every case was recorded for the rule it breaks, and the metadata agrees with the reference") {
    for row <- rows do
      assertEquals(row.accepted, row.expect.isEmpty, row.name)
      row.rule.foreach(r => assert(row.failure.exists(r.explains), s"${row.name}: ${row.failure}"))
  }

  test("every chunked string the reference refuses has a definite twin it accepts") {
    val byName = rows.map(r => r.name -> r).toMap
    val chunked = rows.filter(_.name.endsWith(": indefinite"))
    assert(chunked.nonEmpty)
    for row <- chunked do
      val twin = byName.get(row.name.stripSuffix(": indefinite") + ": definite")
      assert(twin.exists(_.accepted), s"${row.name}: no accepted definite twin")
      assert(!row.accepted, row.name)
  }

  test("a chunked metadata text stays accepted, as the reference reads metadata separately") {
    val metadata = rows.filter(_.kind == Kind.Transaction)
    assertEquals(metadata.size, 1, "one whole-transaction contrast case")
    assert(metadata.forall(_.accepted), "chunked metadata text is accepted")
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
    }
