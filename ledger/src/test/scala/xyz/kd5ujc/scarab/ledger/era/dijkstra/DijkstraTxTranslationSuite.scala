// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.dijkstra

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.Tx
import xyz.kd5ujc.scarab.ledger.era.{EraTxError, IsPhase2Valid, TxLevel}
import xyz.kd5ujc.scarab.ledger.era.conway.ConwayTx
import xyz.kd5ujc.scarab.protocol.StateLayout

/** `TranslateEra DijkstraEra (Tx TopTx)` on the era types ([[DijkstraTx.translate]]), compared with
  * the Leios testnet build on the cases of `fixtures/reference/dijkstra-crossing` (cardano-ledger
  * 9df9f98c, ouroboros-consensus 2416493b; [[DijkstraCrossingCases]]): whether it carries each, the
  * part a refusal names, and of a carried transaction each part's bytes, the flag, the id and the
  * mempool form. The hard-fork mempool's re-decode of the same cases is the node's
  * (`DijkstraRematchSuite`).
  */
class DijkstraTxTranslationSuite extends munit.FunSuite:
  import DijkstraCrossingCases.*

  private def conway(c: Case): ConwayTx =
    ConwayTx
      .decode(c.conway, StateLayout.Leios)
      .fold(e => fail(s"${c.name}: Conway's era decoder refuses it: $e"), identity)

  private def isValid(tx: DijkstraTx[TxLevel.Top]): Boolean =
    DijkstraTx.isValid(tx) == IsPhase2Valid.Phase2Valid

  // `toCBORForMempoolSubmission` (`Tx.hs:500-514`, cardano-ledger 9df9f98c): `[body, witnesses,
  // auxiliary data / null]`, each part as the bytes it memoizes
  private def mempoolForm(tx: DijkstraTx[TxLevel.Top]): Bytes =
    Bytes.fromArray(Array(0x83.toByte)) ++ tx.body.raw ++ tx.wits.raw ++
      tx.auxData.fold(Bytes.fromArray(Array(0xf6.toByte)))(_.raw)

  test("the fixture holds the cases the oracle recorded, Conway's era decoder reading every one") {
    assertEquals(all.size, 188)
    all.foreach { c =>
      assert(bool(c.reference, "conway", "accepted"), c.name)
      conway(c)
    }
  }

  test("Conway writes each transaction as the reference does") {
    all.foreach(c =>
      assertEquals(Tx.envelope(c.conway).hex, string(c.reference, "conway", "toCbor"), c.name)
    )
  }

  test("TranslateEra DijkstraEra (Tx TopTx) as the reference, case by case") {
    all.foreach { c =>
      val expected = field(c.reference, "translateEra")
      DijkstraTx.translate(conway(c)) match
        case Right(tx) =>
          assert(bool(expected, "accepted"), s"${c.name}: carried, the reference refuses it")
          same(c.name, expected, tx, isValid(tx), mempoolForm(tx))
          assertEquals(tx.sizeTxF, mempoolForm(tx).size.toLong, s"${c.name}: size")
        case Left(DijkstraTxTranslationError.Undecodable(part, cause)) =>
          assert(!bool(expected, "accepted"), s"${c.name}: refused ($part: $cause)")
          assertEquals(Some(part), refusedPart(c.reference), c.name)
          assert(
            cause match
              case EraTxError.Decode(_) => true
              case _                    => false
            ,
            s"${c.name}: refused by a reading of the decoder's value, not by the decoder: $cause"
          )
    }
  }

  test("a transaction marked invalid is carried with its flag") {
    val c = all.find(_.name == "flag/false").getOrElse(fail("no flag/false case"))
    assert(!c.conway.isValid)
    assertEquals(DijkstraTx.translate(conway(c)).map(isValid), Right(false))
  }
