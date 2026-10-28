// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus.state

import xyz.kd5ujc.scarab.ledger.PosixClock
import xyz.kd5ujc.scarab.ledger.rules.EraLedger
import xyz.kd5ujc.scarab.ledger.state.WithOrigin
import xyz.kd5ujc.scarab.protocol.Era

/** The slot to time translation of the transactions applied on top of a mirror: the era history at
  * the mirror's tip, as the reference's `EpochInfo` of a ticked ledger state has it.
  *
  * Both the block rules and the mempool use the `EpochInfo` that consensus builds from the ticked
  * state's transition information, which it derives from the state before the tick (extended to the
  * era of the slot): `tickedHardForkLedgerStateTransition` is `mostRecentTransitionInfo` of the
  * extended state (`HardFork/Combinator/Ledger.hs` `applyChainTickLedgerResult`; applied by
  * `applyBlockLedgerResult`, and `applyTx` of `HardFork/Combinator/Mempool.hs`). The horizon
  * therefore starts from the tip the new block or transaction builds on and not from its own slot:
  * ouroboros-consensus `82ecba329d7d054340bf707d44fe6e9ac27cec40`. The Alonzo ledger extends that
  * epoch info linearly from the slot ([[PosixClock.Extended]]); Babbage and Conway use it as it is.
  */
object MirrorClock:

  /** The clock of a transaction of an era-`era` block at `slot` applied on `state` (the mempool's
    * transaction is a block of the next slot in the era of the tip).
    *
    * Where `era` is later than the state's, the tick extends the telescope into it first
    * (`extendToSlot`): the state's era ends where `era` starts ([[MirrorStep.startOf]]) and the
    * eras between are empty. The transition of such a state is read from its configuration alone,
    * for the translated state is not at hand before the tick.
    */
  def of(
      state: MirrorState,
      placement: Placement,
      era: Era,
      slot: BigInt
  ): PosixClock =
    val telescope = state.ext.ledgerState
    val tip = state.ext.headerState.tip match
      case WithOrigin.Origin => None
      case WithOrigin.At(t)  => Some(t.slot.toBigInt)
    val (past, start) =
      if era == telescope.era then (telescope.past, telescope.current.start)
      else
        val begun = MirrorStep.startOf(placement, era, slot, telescope.current.start)
        val skipped = Vector.fill(era.index - telescope.era.index - 1)(Past(begun, begun))
        ((telescope.past :+ Past(telescope.current.start, begun)) ++ skipped, begun)
    val history = EraHistory.horizonHistory(placement.geometry, placement.byron, era, past, start)
    val transition = telescope.state match
      case i: EraLedgerState.InShelleyEra[e] if era == telescope.era =>
        val nes = i.state.nes
        given xyz.kd5ujc.scarab.ledger.state.ShelleyBasedEra[e] = i.era
        EraHistory.transition(
          placement.forks,
          placement.geometry.k,
          era,
          nes.epoch.toBigInt,
          EraLedger.futurePParams(nes.es.ls.utxo.gov).definite.map(_.protocolMajor),
          BigInt(i.state.transition.afterVoting.toLong),
          tip
        )
      case _ =>
        EraHistory.transition(
          placement.forks,
          placement.geometry.k,
          era,
          0,
          None,
          0,
          tip
        )
    PosixClock.forecast(
      placement.geometry,
      history,
      transition,
      Option.when(era == Era.Alonzo)(slot)
    )
