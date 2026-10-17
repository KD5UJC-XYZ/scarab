// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.legacy

import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.ledger.TestLedger.{Supply, params}

/** The flat ledger state of a rule test (FROZEN with the flat state): the suites of the era-indexed
  * rules that are written over it ([[RuleSet]]) start from one and the mirror turns it into the
  * state the rules run on.
  */
object FlatTestLedger:
  /** A state whose UTxO is `funds` and whose reserves make the total equal [[Supply]]. */
  def state(protocolMajor: Int, funds: Vector[(TxIn, TxOut)]): LedgerState =
    val utxo = funds.toMap
    LedgerState(
      epoch = 5,
      params = params(protocolMajor),
      utxo = Utxo(utxo),
      fees = 0,
      deposited = 0,
      accounts = Map.empty,
      pools = Map.empty,
      futurePools = Map.empty,
      retiring = Map.empty,
      dreps = Map.empty,
      dormantEpochs = 0,
      treasury = 0,
      reserves = Supply - utxo.valuesIterator.map(_.value.coin).sum,
      snapshots = Snapshots(Snapshot.empty, Snapshot.empty, Snapshot.empty, 0),
      blocksCurrent = Map.empty,
      blocksPrevious = Map.empty,
      rewardUpdate = None,
      poolDistribution = Snapshot.empty
    )

  /** Sum of all outstanding deposits of a state: stake credentials, pools, DReps and proposals. */
  def obligation(state: LedgerState): BigInt =
    state.accounts.valuesIterator.map(_.deposit).sum + state.pools.valuesIterator
      .map(_.deposit)
      .sum +
      state.dreps.valuesIterator.map(_.deposit).sum + state.gov.proposals.totalDeposits
