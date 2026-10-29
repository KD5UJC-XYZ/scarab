// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.read

import xyz.kd5ujc.scarab.consensus.HardForkConfig
import xyz.kd5ujc.scarab.consensus.state.{EraHistory, Placement}
import xyz.kd5ujc.scarab.ledger as flat
import xyz.kd5ujc.scarab.ledger.EpochGeometry
import xyz.kd5ujc.scarab.protocol.Era

/** The slot to time translation of the transactions of a block of `era` at `slot` applied on a
  * chain state: the era history at the state's tip with its forecast horizon ([[ChainRead.clock]]).
  * It is placed by the node's era history parameters, fixed at start-up (the genesis geometry, the
  * Byron era's parameters from the Byron genesis and the hard-fork triggers), so the forger and the
  * mempool translate time as the block rules do without knowing those parameters.
  */
trait TxClock[S]:
  def at(state: S, era: Era, slot: BigInt): flat.PosixClock

  /** Whether the era history at the state's tip translates `slot` (`wallclockToSlot` of the summary
    * of the current ledger, `hfbtGetLedgerState`).
    */
  def translates(state: S, slot: BigInt): Boolean = at(state, Era.Conway, slot).posix(slot).isRight

object TxClock:
  /** The clock of a chain read by `ChainRead`, placed by `geometry` as the state resolves it
    * ([[ChainRead.geometry]]), the Byron era's parameters and the hard-fork triggers.
    */
  def of[S](geometry: EpochGeometry, byron: EraHistory.ByronParams, forks: HardForkConfig)(using
      read: ChainRead[S]
  ): TxClock[S] = new TxClock[S]:
    def at(state: S, era: Era, slot: BigInt): flat.PosixClock =
      state.clock(Placement(state.geometry(geometry), byron, forks), era, slot)
