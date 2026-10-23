// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import munit.FunSuite
import xyz.kd5ujc.scarab.protocol.Era

import ReferenceCorpus.{Case, Corpus, Kind, Refusal}

/** Transaction bodies and witness sets are decoded at the decoder version of their era
  * (`eraProtVerLow`: Conway 9, Babbage 7). `fixtures/reference/conway-decoding` holds original
  * encodings, each of which breaks one rule of the reference decoders (or keeps a rule it
  * tolerates), with what the unchanged released decoders of cardano-ledger f649f975 did with it.
  * Every verdict must be the same here, and a refusal must be for the rule the case breaks.
  */
class ConwayDecodingReferenceSuite extends FunSuite:
  private val rows: Vector[Case] =
    ReferenceCorpus.load(Corpus.ConwayDecoding).fold(e => fail(e.toString), identity)

  /** The cases the tests below name, by the stable id the generator gives them. */
  private enum Known(val id: String):
    case DuplicateInput extends Known("inputs-duplicate")
    case TaggedInputs extends Known("inputs-tag-258")
    case ToleratedVkey extends Known("vkeys-duplicate-tolerated")
    case SameBytesTwoLanguages extends Known("plutus-same-bytes-two-languages")

  private def known(k: Known): Case =
    rows.find(_.id == k.id).getOrElse(fail(s"the corpus has no case $k"))

  private def scarab(row: Case): Either[LedgerDecodeError, Unit] = row.kind match
    case Kind.Body        => TxBody.decode(row.raw, row.era).map(_ => ())
    case Kind.Witnesses   => Witnesses.decode(row.raw, row.era).map(_ => ())
    case Kind.Transaction => Tx.fromEnvelope(row.raw, row.era).map(_ => ())

  test("the corpus has refused and accepted cases for bodies, witness sets and transactions") {
    for kind <- Kind.values do
      assert(rows.exists(r => r.kind == kind && r.accepted), s"no accepted $kind case")
      assert(rows.exists(r => r.kind == kind && !r.accepted), s"no refused $kind case")
    assertEquals(rows.map(_.name).distinct.size, rows.size)
  }

  test(
    "every case agrees with its recorded reference verdict, and was recorded for the rule it breaks"
  ) {
    for row <- rows do
      assertEquals(
        row.accepted,
        row.expect.isEmpty,
        s"${row.name}: case metadata and reference verdict"
      )
      row.rule.foreach(rule =>
        assert(
          row.failure.exists(rule.explains),
          s"${row.name}: reference failure ${row.failure}"
        )
      )
  }

  for row <- rows do
    test(s"${row.era} ${row.kind}: ${row.name}") {
      val result = scarab(row)
      row.expect match
        case None =>
          assertEquals(result, Right(()), "the reference accepts this encoding")
        case Some(refusal) =>
          assertEquals(
            result.swap.toOption.flatMap(Refusal.of),
            Some(refusal),
            s"the reference refuses this encoding (${row.failure}); Scarab gave $result"
          )
    }

  test(
    "the decoder version comes from the era: a repeated input is a refusal in Conway and one input in Babbage"
  ) {
    val repeated = known(Known.DuplicateInput)
    assert(TxBody.decode(repeated.raw, Era.Conway).isLeft)
    val babbage = TxBody.decode(repeated.raw, Era.Babbage).fold(e => fail(e.toString), identity)
    assertEquals(babbage.inputs.size, 1)
  }

  test("an accepted body keeps its original bytes and the elements the reference keeps") {
    val twoInputs = known(Known.TaggedInputs)
    val body = TxBody.decode(twoInputs.raw, Era.Conway).fold(e => fail(e.toString), identity)
    assertEquals(body.raw, twoInputs.raw)
    assertEquals(body.inputs.size, 2)
    val witnesses = known(Known.ToleratedVkey)
    assertEquals(
      Witnesses.decode(witnesses.raw, Era.Conway).map(_.vkeys.size),
      Right(1)
    )
    val sameBytes = known(Known.SameBytesTwoLanguages)
    assertEquals(
      Witnesses.decode(sameBytes.raw, Era.Conway).map(_.scripts.size),
      Right(2)
    )
  }
