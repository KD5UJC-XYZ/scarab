// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.state

import cats.syntax.all.*
import munit.FunSuite
import xyz.kd5ujc.scarab.ledger.state.LedgerStateGen.params

/** The empty state of each era, built by the witness. */
class EmptySuite extends FunSuite:
  private val epoch = EpochNo.fromUnsigned(7L)

  /** An empty state of the era, read through the era-generic paths. */
  private def check[E <: EraTag](using ShelleyBasedEra[E]): NewEpochState[E] =
    val nes = NewEpochState.empty[E](epoch, params)
    assertEquals(nes.epoch, epoch)
    assertEquals(nes, NewEpochState.empty[E](epoch, params))
    assertEquals(NewEpochState.ledger[E].get(nes), Empty.ledgerState[E](params))
    assert(LedgerState.pState[E].get(NewEpochState.ledger[E].get(nes)) === Empty.pState)
    nes

  test("every era has an empty new-epoch state") {
    check[Shelley]
    check[Allegra]
    check[Mary]
    check[Alonzo]
    check[Babbage]
    check[Conway]
  }

  test("the empty parts are those of the era's side of the split") {
    val conway: ConwayCertState = Empty.certState[Conway]
    val babbage: ShelleyCertState = Empty.certState[Babbage]
    val conwayGov: ConwayGovState = Empty.govState[Conway](params)
    val maryGov: ShelleyGovState = Empty.govState[Mary](params)
    val shelleyAvvm: UtxoTables = Empty.stashedAvvm[Shelley]
    val allegraAvvm: Unit = Empty.stashedAvvm[Allegra]
    assertEquals(conway.vState.dreps.size, 0)
    assertEquals(babbage.dState.accounts.states.size, 0)
    assertEquals(conwayGov.curPParams, params)
    assertEquals(maryGov.prevPParams, params)
    assertEquals(shelleyAvvm, check[Shelley].avvm)
    assertEquals(allegraAvvm, ())
  }

  test("an empty state has no stake, no pools and no treasury") {
    val nes = check[Conway]
    assertEquals(ConwayNewEpochState.treasury.get(nes), Coin.zero)
    assertEquals(ConwayNewEpochState.reserves.get(nes), Coin.zero)
    assertEquals(ConwayNewEpochState.pools.get(nes).size, 0)
    assertEquals(nes.pd, PoolDistr.empty)
    assertEquals(nes.es.snapshots, SnapShots.empty)
  }
