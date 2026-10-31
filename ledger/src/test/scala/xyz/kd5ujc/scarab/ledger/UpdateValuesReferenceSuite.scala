// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.protocol.Era

import ReferenceCorpus.{Case, Corpus}

/** Below decoder version 12 the reference reads each value of a pre-Conway `PParamsUpdate` at the
  * type of its field when it decodes the body (`SparseKeyed`, `mkField`,
  * `libs/cardano-ledger-core/src/Cardano/Ledger/Core/PParams.hs:257-293`, cardano-ledger f649f975),
  * the protocol version by `decodeProtVer` with the era's bound, and so does Scarab
  * ([[ParamUpdate.read]]).
  *
  * `fixtures/reference/update-values/cases.jsonl` holds an original body of each era from Shelley
  * to Babbage whose update sets one value: every key at the bound of its type and one over, and the
  * other forms of each type once; and Conway bodies whose parameter change sets `Prices` or
  * `ExUnits`, `RecD` records in every era, definite and indefinite. Evidence for #470 and the
  * second part of #436.
  */
class UpdateValuesReferenceSuite extends FunSuite:
  private val rows: Vector[Case] =
    ReferenceCorpus.load(Corpus.UpdateValues).fold(e => fail(e.toString), identity)

  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.toString), identity)

  test("the corpus covers every era from Shelley to Conway, accepted and refused") {
    assertEquals(
      rows.map(_.era).toSet,
      Set(Era.Shelley, Era.Allegra, Era.Mary, Era.Alonzo, Era.Babbage, Era.Conway)
    )
    assertEquals(rows.map(_.name).distinct.size, rows.size)
    assert(rows.exists(_.accepted) && rows.exists(!_.accepted))
    test("a Conway update in a stored state may hold an indefinite ExUnits; a body may not") {
      // {20: [_ 1, 2]}: the maximum transaction execution units as an indefinite list
      val raw = hex("a1149f0102ff")
      val node = CborRead.decode(raw).fold(e => fail(e.toString), identity)
      assertEquals(
        ConwayParamUpdate.decode(node),
        Left(LedgerDecodeError.IndefiniteLength(DecodeField.MaxTxExUnits))
      )
      val stored = ConwayParamUpdate.decodeStored(node).fold(e => fail(e.toString), identity)
      val encoded = ConwayParamUpdate
        .fromEncoded(Vector(BigInt(20) -> hex("9f0102ff")))
        .fold(e => fail(e.toString), identity)
      assertEquals(stored, encoded)
      assertEquals(
        stored.params,
        Vector(ConwayParam.MaxTxExUnits(xyz.kd5ujc.scarab.plutus.ExUnits(1, 2)))
      )
      assertEquals(stored.encodings, Vector(20 -> hex("9f0102ff")), "the original bytes are kept")
    }

    for row <- rows do
      assertEquals(row.accepted, row.expect.isEmpty, row.name)
      assertEquals(row.accepted, row.rule.isEmpty, row.name)
      row.rule.foreach(r => assert(row.failure.exists(r.explains), s"${row.name}: ${row.failure}"))
  }

  test("an update applied from a stored state is read as before, whatever the body would refuse") {
    // A Babbage update proposing major 10 and a maximum header size of 70000: the body refuses both
    // (the major is over 9, the size over `Word16`), and a state stored before #470 may hold them.
    val stored = ParamUpdate(Map(14 -> hex("820a00"), 4 -> hex("1a00011170")))
    val applied = ProtocolParams
      .update(TestLedger.params(8), stored)
      .fold(e => fail(e.toString), identity)
    assertEquals(applied.protocolMajor, BigInt(10))
    assertEquals(applied.maxBlockHeaderSize, BigInt(70000))
  }

  for row <- rows do
    test(s"${row.era}: ${row.name}") {
      val body = TxBody.decode(row.raw, row.era)
      val effects = LiteLedger.effects(row.raw, row.era)
      (row.expect, body) match
        case (None, Right(b)) =>
          assertEquals(b.raw, row.raw, "the original bytes are retained")
          assertEquals(b.id, Blake2b.hash256.hash(row.raw), "the id hashes the original bytes")
        case (None, Left(e))     => fail(s"the reference accepts this encoding: $e")
        case (Some(_), Right(_)) => fail(s"the reference refuses this encoding: ${row.failure}")
        case (Some(refusal), Left(e)) =>
          assertEquals(ReferenceCorpus.Refusal.of(e), Some(refusal), s"Scarab gave $e")
      // The bootstrap ledger reads the update of an era with PPUP; Conway's proposals are not its.
      if ParamUpdate.Rules.of(row.era).isDefined then
        assertEquals(effects.isRight, row.accepted, s"the bootstrap ledger's reading: $effects")
    }
