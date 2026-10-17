// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.state

/** The scalar fields of a [[NewEpochState]] and of the UTxO state under it: the epoch, the blocks
  * made, the pots and the three amounts of the UTxO state.
  */
final case class LedgerScalars(
    epoch: EpochNo,
    bprev: BlocksMade,
    bcur: BlocksMade,
    chainAccount: ChainAccountState,
    deposited: Coin,
    fees: Coin,
    donation: Coin
)
