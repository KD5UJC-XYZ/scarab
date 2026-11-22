// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus.state

import cats.data.NonEmptyVector
import xyz.kd5ujc.scarab.ledger.{Horizon, PosixClock, SystemStart}
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
    * transaction is a block of the next slot in the era of the tip), from the ledger's system start
    * `start`: the era history is the hard-fork combinator's, its times relative, and
    * `slotToPOSIXTime` adds them to the `systemStart` of the ledger's `Globals`, the Shelley
    * genesis's (cardano-ledger 9df9f98c `Plutus/TxInfo.hs:165-172`, `Shelley/Genesis.hs:768-789`).
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
      start: SystemStart,
      state: MirrorState,
      placement: Placement,
      era: Era,
      slot: BigInt
  ): PosixClock =
    val (history, transition) = historyAt(state, placement, era, slot)
    PosixClock.forecast(
      start,
      history,
      transition,
      Option.when(era == Era.Alonzo)(slot)
    )

  /** The era history [[of]] reads, at its transition: the `EpochInfo` of the ledger's `Globals` a
    * rule of `era` asks for at `slot` (`epochInfo`, as the clock's horizon).
    */
  def horizon(state: MirrorState, placement: Placement, era: Era, slot: BigInt): Horizon =
    val (history, transition) = historyAt(state, placement, era, slot)
    history.at(transition)

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
    * bounds and parameters (`Summary xs`, at least one era): the eras of [[summary]] with the
    * bounds of the state's layout, built as `reconstructSummary` builds them (ouroboros-consensus
    * 2416493b `HardFork/Combinator/State/Infra.hs:194-304`). The past eras' bounds and the current
    * era's start are the telescope's, as the state holds them. The current era ends at the next
    * era's start where the transition is known, the next era (where the code has one) then ending
    * at its safe zone from its start; otherwise the current era ends at its safe zone from the slot
    * after the tip. Each end is the `mkUpperBound` of its era from its start, with the reference's
    * `Word64` arithmetic ([[EraHistory.upperBound]]; `applySafeZone` at `:291-300`,
    * [[EraHistory.safeZoneEnd]]), and every era has the Peras round length of its build
    * ([[EraHistory.perasRoundLength]]).
    */
  def interpreter(state: MirrorState, placement: Placement): NonEmptyVector[EraSummary] =
    val telescope = state.ext.ledgerState
    val era = telescope.era
    val layout = telescope.current.start.layout.stateLayout
    val (history, transition) = historyAt(state, placement, era, telescope.current.start.slot)
    def summaryOf(params: Horizon.Params, of: Era, start: Bound, end: Bound) =
      EraSummary(
        start,
        Some(end),
        params.epochSize,
        params.slotLengthMillis,
        params.safeZone,
        params.genesisWindow,
        EraHistory.perasRoundLength(layout, of)
      )
    // `applySafeZone` of a standard safe zone from the slot `from`
    def safeEnd(of: Era, params: Horizon.Params, start: Bound, from: BigInt) =
      EraHistory.safeZoneEnd(
        start,
        of,
        params.epochSize,
        params.slotLengthMillis,
        params.safeZone,
        from
      )
    val past = history.past.zip(telescope.past).zip(Era.values).map { case ((e, p), of) =>
      summaryOf(e.params, of, p.start, p.end)
    }
    val start = telescope.current.start
    val (current, next) = transition match
      case Horizon.Transition.Known(epoch) =>
        val end = EraHistory.upperBound(
          start,
          era,
          history.current.epochSize,
          history.current.slotLengthMillis,
          epoch
        )
        (
          summaryOf(history.current, era, start, end),
          history.next.zip(Era.values.lift(era.index + 1)).map { (params, following) =>
            summaryOf(params, following, end, safeEnd(following, params, end, end.slot))
          }
        )
      case Horizon.Transition.Unknown(tip) =>
        // even a safe zone of zero starts at the slot after the tip
        val after = tip.fold(BigInt(0))(_ + 1)
        (summaryOf(history.current, era, start, safeEnd(era, history.current, start, after)), None)
    NonEmptyVector.one(current).prependVector(past).appendVector(next.toVector)

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
