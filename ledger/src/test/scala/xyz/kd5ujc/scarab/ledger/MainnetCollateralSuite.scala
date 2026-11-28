// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import munit.FunSuite
import xyz.kd5ujc.scarab.ledger.state.Babbage

/** The collateral the phase-2-invalid mainnet transactions of #1521 pay is the fee the chain
  * charged them (`collAdaBalance`, `Babbage/Collateral.hs:30-41`, cardano-ledger f649f975): the
  * coins of their collateral outputs, less the collateral return's.
  */
class MainnetCollateralSuite extends FunSuite:
  test("the three transactions fail phase 2, and two of them have no collateral return") {
    val txs = MainnetCollateral.cases.map(_.tx)
    assertEquals(
      txs.map(t => (t.isValid, t.body.collateralReturn.isDefined)),
      Vector(
        (false, false),
        (false, false),
        (false, true)
      )
    )
  }

  for c <- MainnetCollateral.cases do
    test(s"${c.name}: the collateral pays the fee the chain charged") {
      val collateral = TxChecks.collateral[Babbage](c.utxo, c.tx)
      assertEquals(collateral.outputs, c.utxo.resident.values.toVector)
      assertEquals(collateral.fee, c.fee)
    }
