// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.mempool

import xyz.kd5ujc.scarab.ledger.{LedgerDecodeError, Tx}
import xyz.kd5ujc.scarab.ledger.era.{EraTxError, IsPhase2Valid}
import xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraCrossingCases
import xyz.kd5ujc.scarab.protocol.{Era, StateLayout}

/** The hard-fork mempool's re-decode of a Conway transaction into Dijkstra
  * ([[EraInjection.Redecode]], `rematchValidatedTxs` and `matchTx`, `InjectTxs.hs:75, 164, 177,
  * 215`, ouroboros-consensus 808e4005 and d91b3557, the same file at 2416493b), compared with the
  * Leios testnet build on the cases of `fixtures/reference/dijkstra-crossing` (cardano-ledger
  * 9df9f98c, ouroboros-consensus 2416493b): whether it carries each and, of a carried transaction,
  * each part's bytes, the flag, the id and the mempool form ([[DijkstraRead.raw]]).
  */
class DijkstraRematchSuite extends munit.FunSuite:
  import DijkstraCrossingCases.*

  private def held(c: Case): InEra =
    val read = for
      flat <- Tx.fromBlock(c.conway, Era.Conway).left.map(EraTxError.Decode(_))
      read <- ConwayRead.of(EraTx(c.conway, flat), StateLayout.Leios)
    yield read
    InEra.InConway(read.fold(e => fail(s"${c.name}: not a Conway transaction: $e"), identity))

  test("the hard-fork mempool's re-decode as the reference, case by case") {
    all.foreach { c =>
      val expected = field(c.reference, "rematch")
      EraInjection.Redecode.into(held(c), Era.Dijkstra) match
        case Right(InEra.InDijkstra(read)) =>
          assert(bool(expected, "accepted"), s"${c.name}: carried, the reference refuses it")
          same(c.name, expected, read.tx, read.isValid == IsPhase2Valid.Phase2Valid, read.raw)
        case Right(other) => fail(s"${c.name}: carried into ${other.era}")
        case Left(era) =>
          assert(!bool(expected, "accepted"), s"${c.name}: refused")
          assertEquals(era, Era.Conway, s"${c.name}: the mismatch names the transaction's era")
    }
  }

  test("a transaction marked invalid is refused, since Dijkstra's mempool form cannot say it") {
    val c = all.find(_.name == "flag/false").getOrElse(fail("no flag/false case"))
    val conway = held(c)
    assertEquals(EraInjection.Redecode.into(conway, Era.Dijkstra).map(_.era), Left(Era.Conway))
    assertEquals(
      DijkstraRead.submitted(conway.raw).map(_.id),
      Left(EraTxError.Decode(LedgerDecodeError.InvalidFlagSubmitted))
    )
  }
