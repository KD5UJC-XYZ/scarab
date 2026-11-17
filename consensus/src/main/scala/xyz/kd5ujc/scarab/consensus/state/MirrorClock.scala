// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus.state

import xyz.kd5ujc.scarab.ledger.{Horizon, PosixClock}
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
    * which is what the reference reads from the translated state. Every translation into a Shelley
    * era starts the transition counter at zero (`shelleyLedgerTransition = ShelleyTransitionInfo
    * 0`, consensus 82ecba329 `Shelley/ShelleyHFC.hs:381` and `Cardano/CanHardFork.hs:360-361`), and
    * a version trigger needs `shelleyAfterVoting >= k` (`shelleyTransition`,
    * `ShelleyHFC.hs:220-227`), so the translated state has no transition by version; an epoch
    * trigger is configuration. The counter, the epoch and the future parameters are therefore those
    * of [[EraHistory.transition]]'s `afterVoting = 0` case whatever the translated state holds.
    */
  def of(
      state: MirrorState,
      placement: Placement,
      era: Era,
      slot: BigInt
  ): PosixClock =
    val (history, transition) = historyAt(state, placement, era, slot)
    PosixClock.forecast(
      placement.geometry,
      history,
      transition,
      Option.when(era == Era.Alonzo)(slot)
    )

  /** The era history of `state` at its tip, as `GetInterpreter` answers it
    * (`interpretQueryHardFork` is `mkInterpreter (hardForkSummary cfg st)`,
    * `HardFork/Combinator/Ledger/Query.hs:529`; `hardForkSummary` is `reconstructSummary` of the
    * eras the state has passed and its transition, `State/Infra.hs:194-262`, ouroboros-consensus
    * 82ecba32). While the chain is in Byron the era ends at its safe zone, not where the
    * configuration would place Shelley (#118).
    */
  def summary(state: MirrorState, placement: Placement): Horizon =
    val telescope = state.ext.ledgerState
    val (history, transition) =
      historyAt(state, placement, telescope.era, telescope.current.start.slot)
    history.at(transition)

  /** The era history of `state` at its tip as `GetInterpreter` answers it, every era with its
    * bounds and parameters (`Summary xs`): the summary of [[summary]] with the bounds of the
    * state's layout. As `reconstructSummary` builds it (ouroboros-consensus 2416493b
    * `HardFork/Combinator/State/Infra.hs:194-304`), the past eras' bounds and the current era's
    * start are the telescope's, as the state holds them; each end the summary computes, the current
    * era's and, where the transition is known, the next era's, is the `mkUpperBound` of its era
    * from its start ([[EraHistory.upperBound]]), and the next era starts where the current ends.
    * Every era has the Peras round length of its build ([[EraHistory.perasRoundLength]]).
    */
  def interpreter(state: MirrorState, placement: Placement): Vector[EraSummary] =
    val telescope = state.ext.ledgerState
    val layout = telescope.current.start.layout.stateLayout
    def summaryOf(e: Horizon.Era, era: Era, start: Bound, end: Option[Bound]) =
      EraSummary(
        start,
        end,
        e.params.epochSize,
        e.params.slotLengthMillis,
        e.params.safeZone,
        e.params.genesisWindow,
        EraHistory.perasRoundLength(layout, era)
      )
    val (entered, ahead) =
      summary(state, placement).eras.toVector.zip(Era.values).splitAt(telescope.past.size)
    val past = entered.zip(telescope.past).map { case ((e, era), p) =>
      summaryOf(e, era, p.start, Some(p.end))
    }
    val (_, open) = ahead.foldLeft((telescope.current.start, Vector.empty[EraSummary])) {
      case ((start, eras), (e, era)) =>
        val end = e.end.map(edge =>
          EraHistory.upperBound(start, era, Bound(edge.timePs, edge.slot, edge.epoch, start.layout))
        )
        (end.getOrElse(start), eras :+ summaryOf(e, era, start, end))
    }
    past ++ open

  /** The eras up to `era` and what the state tells of the end of `era` ([[of]]). */
  private def historyAt(
      state: MirrorState,
      placement: Placement,
      era: Era,
      slot: BigInt
  ): (Horizon.History, Horizon.Transition) =
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
      case EraLedgerState.ByronCurrent(byron) if era == telescope.era =>
        EraHistory.byronTransition(placement.geometry, placement.byron, byron.ledger, tip)
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
    (history, transition)
