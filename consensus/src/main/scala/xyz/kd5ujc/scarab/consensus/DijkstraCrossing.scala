// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus

import cats.syntax.all.*
import xyz.kd5ujc.scarab.consensus.state.PraosState
import xyz.kd5ujc.scarab.ledger.{CrossingForecastFailure, ProtocolParams}
import xyz.kd5ujc.scarab.ledger.era.GovStateRecord
import xyz.kd5ujc.scarab.ledger.rules.{EraLedger, Globals, TickFailure}
import xyz.kd5ujc.scarab.ledger.state.{
  Conway,
  Dijkstra,
  EpochNo,
  FuturePParams,
  NewEpochState,
  PoolDistr,
  ProtVer,
  SlotNo,
  SnapShots,
  WithOrigin
}
import xyz.kd5ujc.scarab.protocol.LeiosWord.{Word16, Word32}

/** The parts of the hard-fork combinator's crossing from Conway (Praos) into Dijkstra (Praos2) that
  * concern the protocol, at ouroboros-consensus 2416493b with cardano-ledger 9df9f98c: the
  * chain-dependent state and the ledger view forecast across the boundary, and Dijkstra's own
  * forecast ([[forecastInEra]]). All are pure; the node's header rules use them under the Leios
  * build (`NodeHeaderRules`), and its ledger does not enter Dijkstra (#54).
  */
object DijkstraCrossing:

  /** `translateChainDepState` of `TranslateProto (Praos c) (Praos2 c)`
    * (`Protocol/Praos2.hs:189-200`, ouroboros-consensus 2416493b), which the crossing applies
    * through `translateChainDepStateAcrossShelley` (`Cardano/CanHardFork.hs:166-173`,
    * `Shelley/ShelleyHFC.hs:304-317`): every field carried unchanged and no announcement, since no
    * Praos header could carry one. The epoch transition is the tick's, not the translation's.
    */
  def chainDepState(praos: PraosState): Praos2.State = Praos2.State(praos, None)

  /** `crossEraForecastBound` (`Ouroboros/Consensus/Forecast.hs:174-200`, ouroboros-consensus
    * 2416493b): the exclusive upper bound of a forecast from `tip` across a transition at
    * `transition`, the lesser of what a next block of this era and the first block of the next
    * could change. Slot additions are the reference's unsigned words.
    */
  def forecastBound(
      tip: WithOrigin[SlotNo],
      transition: SlotNo,
      currentLookahead: BigInt,
      nextLookahead: BigInt
  ): SlotNo =
    val tipSucc = successor(tip)
    val fromNext = addSlots(nextLookahead, transition)
    if tipSucc.toBigInt < transition.toBigInt then
      val fromCurrent = addSlots(currentLookahead, tipSucc)
      if fromCurrent.toBigInt < fromNext.toBigInt then fromCurrent else fromNext
    else fromNext

  /** `crossEraForecastConwayToDijkstraWrapper` (`Cardano/CanHardFork.hs:767-808`,
    * ouroboros-consensus 2416493b): the Dijkstra ledger view of `forecastFor` from the Conway state
    * `nes` at `tip`, the transition at `transition`. It does not translate the Conway view, which
    * has no Leios parameters; it translates the state, as the combinator does when it ticks across
    * the boundary, and forecasts with Dijkstra's own rules (`ledgerViewForecastAtPolyPraos`,
    * `Shelley/Ledger/SupportsProtocol.hs:105-140`). The reference's translation is lazy and its
    * forecast state holds no ledger tables (`forgetLedgerTables`), so only what the view reads is
    * translated here ([[Basis.translated]]): the parameters, upgraded as
    * `Translate.conwayToDijkstra` upgrades them, the snapshots and the epoch, never the UTxO.
    *
    * The slot must be before both bounds: the crossing's ([[forecastBound]], the stability windows
    * of `conway` and `dijkstra`) and then the Dijkstra forecast's own, the stability window past
    * the tip's successor. At the tip the view is the state's (`currentForecast`); after it the
    * state is first ticked by TICKF ([[tickf]]).
    */
  def forecast(
      conway: Globals,
      dijkstra: Globals,
      transition: SlotNo,
      forecastFor: SlotNo,
      tip: WithOrigin[SlotNo],
      nes: NewEpochState[Conway]
  ): Either[CrossingForecastFailure, Praos2.LedgerView] =
    val maxFor =
      forecastBound(tip, transition, conway.stabilityWindow, dijkstra.stabilityWindow)
    // both bounds read only the tip, which the reference's lazy translation leaves for last
    for
      _ <- within(tip, maxFor, forecastFor)
      _ <- inEraBound(dijkstra, forecastFor, tip)
      translated <- Basis.translated(dijkstra, nes)
      view <- viewAt(dijkstra, forecastFor, tip, translated)
    yield view

  /** `ledgerViewForecastAtPolyPraos` of Dijkstra (`Shelley/Ledger/SupportsProtocol.hs:105-140`,
    * ouroboros-consensus 2416493b, the forecast of `ledgerViewForecastAt` for a Praos2 era): the
    * ledger view of `forecastFor` from the Dijkstra state `nes` at `tip`, the state's own at the
    * tip (`currentForecast`) and after it the state ticked by TICKF ([[tickf]]), up to the
    * exclusive bound of the stability window past the tip's successor. Dijkstra is the last era of
    * the build, so no transition bounds it.
    */
  def forecastInEra(
      dijkstra: Globals,
      forecastFor: SlotNo,
      tip: WithOrigin[SlotNo],
      nes: NewEpochState[Dijkstra]
  ): Either[CrossingForecastFailure, Praos2.LedgerView] =
    // the view is made only once the bound holds (`ledgerViewForecastAtPolyPraos` throws first)
    inEraBound(dijkstra, forecastFor, tip).flatMap(_ =>
      viewAt(dijkstra, forecastFor, tip, Basis.of(nes))
    )

  /** The bound of [[forecastInEra]]: none at the tip itself, else the stability window past the
    * tip's successor (`maxFor`, exclusive).
    */
  private def inEraBound(
      dijkstra: Globals,
      forecastFor: SlotNo,
      tip: WithOrigin[SlotNo]
  ): Either[CrossingForecastFailure, Unit] =
    if tip == WithOrigin.At(forecastFor) then Right(())
    else within(tip, addSlots(dijkstra.stabilityWindow, successor(tip)), forecastFor)

  /** What the forecast reads of a Dijkstra state: its epoch, its snapshots and its current and
    * future parameters.
    */
  private final case class Basis(
      epoch: EpochNo,
      snapshots: SnapShots,
      current: ProtocolParams,
      future: FuturePParams
  )

  private object Basis:
    def of(nes: NewEpochState[Dijkstra]): Basis =
      val gov = nes.es.ls.utxo.gov
      Basis(
        nes.epoch,
        nes.es.snapshots,
        GovStateRecord.view(gov.cgsCurPParams),
        GovStateRecord.futureView(gov.cgsFuturePParams)
      )

    /** The basis of the Dijkstra state `Translate.conwayToDijkstra` makes of `nes`: the epoch and
      * the snapshots carried over, the current and future parameters upgraded by the Dijkstra
      * genesis (`TranslationContext DijkstraEra`), refused without one as the translation is
      * ([[TickFailure.NoDijkstraGenesis]]).
      */
    def translated(
        dijkstra: Globals,
        nes: NewEpochState[Conway]
    ): Either[CrossingForecastFailure, Basis] =
      dijkstra.ledger.dijkstra
        .toRight(CrossingForecastFailure.Translation(TickFailure.NoDijkstraGenesis))
        .map { genesis =>
          val gov = nes.es.ls.utxo.gov
          Basis(
            nes.epoch,
            nes.es.snapshots,
            genesis.upgrade(gov.curPParams),
            FuturePParams.map(gov.futurePParams)(genesis.upgrade)
          )
        }

  /** The view of [[forecastInEra]] once its bound holds. */
  private def viewAt(
      dijkstra: Globals,
      forecastFor: SlotNo,
      tip: WithOrigin[SlotNo],
      basis: Basis
  ): Either[CrossingForecastFailure, Praos2.LedgerView] =
    if tip == WithOrigin.At(forecastFor) then
      ledgerView(PoolDistr.of(basis.snapshots.set), basis.current)
    else (ledgerView.tupled)(tickf(dijkstra, forecastFor, basis))

  /** TICKF of Dijkstra, Conway's (`EraRule "TICKF" DijkstraEra = Conway.TICKF`,
    * `Dijkstra/Era.hs:224`; `Conway/Rules/Tickf.hs:59-85`; cardano-ledger 9df9f98c), as far as the
    * header view reads it: the future parameters made certain from the slot of no return
    * (`solidifyNextEpochPParams`, `Shelley/Rules/Tick.hs:147-159`; `getTheSlotOfNoReturn`,
    * `Slot.hs:106-114`), and only into the epoch after the state's the snapshots rotated by
    * Dijkstra's SNAP, the old mark becoming the set whose pool distribution the forecast reads
    * (`Dijkstra/Rules/Snap.hs:103-143`, `nesStakePoolDistrG`,
    * `Shelley/LedgerState/Types.hs:643-644`), and the parameters of the next epoch in force
    * (`nextEpochPParams`, `State/Governance.hs:121-133`). The new mark and the Leios committee it
    * seats are not in the header view.
    */
  private def tickf(
      globals: Globals,
      slot: SlotNo,
      basis: Basis
  ): (PoolDistr, ProtocolParams) =
    val now = slot.toBigInt
    val epoch = globals.epochOf(now)
    val pointOfNoReturn = globals.slotOfNoReturn(epoch)
    val future = basis.future
    val solid = if now < pointOfNoReturn then future else EraLedger.solidify(future)
    if epoch != basis.epoch.toBigInt + 1 then (PoolDistr.of(basis.snapshots.set), basis.current)
    else (PoolDistr.of(basis.snapshots.mark), solid.definite.getOrElse(basis.current))

  /** `forecastToPolyPraosLedgerView` of Praos2 (`Protocol/Praos2.hs:136-159`, ouroboros-consensus
    * 2416493b) over `mkDijkstraForecast` (`Dijkstra/Forecast.hs:124-152`, cardano-ledger 9df9f98c),
    * the fields header validation and the Leios fetch read ([[Praos2.LedgerView]]): the bound on an
    * endorser block's transactions is `maxEndorserBlockTxsSize` (`plvMaxEbTxsSize`,
    * `Protocol/Praos2.hs:155-156`).
    */
  private def ledgerView(
      pools: PoolDistr,
      pp: ProtocolParams
  ): Either[CrossingForecastFailure, Praos2.LedgerView] =
    def word16(n: BigInt) = Word16.from(n).leftMap(CrossingForecastFailure.Parameter(_))
    def word32(n: BigInt) = Word32.from(n).leftMap(CrossingForecastFailure.Parameter(_))
    (
      word16(pp.maxBlockHeaderSize),
      word32(pp.maxBlockBodySize),
      ProtVer
        .fromBigInt(pp.protocolMajor, pp.protocolMinor)
        .leftMap(CrossingForecastFailure.Vocabulary(_)),
      word32(pp.leiosAnnouncementPeriodLength),
      word32(pp.leiosVotePeriodLength),
      word32(pp.leiosDiffusionPeriodLength),
      word32(pp.maxEndorserBlockReferencesSize),
      word32(pp.maxEndorserBlockTxsSize)
    ).mapN(
      Praos2.LedgerView(
        pools.distribution.unsorted.map((id, p) => id -> Praos2.PoolStake(p.stake, p.vrf)),
        _,
        _,
        _,
        _,
        _,
        _,
        _,
        _
      )
    )

  private def within(
      tip: WithOrigin[SlotNo],
      maxFor: SlotNo,
      forecastFor: SlotNo
  ): Either[CrossingForecastFailure, Unit] =
    Either.cond(
      forecastFor.toBigInt < maxFor.toBigInt,
      (),
      CrossingForecastFailure.OutsideForecastRange(tip, maxFor, forecastFor)
    )

  /** `succWithOrigin`: the first slot after the tip. */
  private def successor(tip: WithOrigin[SlotNo]): SlotNo = tip match
    case WithOrigin.Origin => SlotNo.fromUnsigned(0)
    case WithOrigin.At(s)  => addSlots(1, s)

  /** `addSlots`, in the reference's unsigned word. */
  private def addSlots(n: BigInt, slot: SlotNo): SlotNo =
    SlotNo.fromUnsigned((slot.toBigInt + n).toLong)
