// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus

import cats.syntax.all.*
import xyz.kd5ujc.scarab.consensus.state.PraosState
import xyz.kd5ujc.scarab.ledger.ProtocolParams
import xyz.kd5ujc.scarab.ledger.rules.{EraLedger, Globals, TickFailure, Translate}
import xyz.kd5ujc.scarab.ledger.state.{
  Conway,
  Dijkstra,
  NewEpochState,
  PoolDistr,
  ProtVer,
  SlotNo,
  VocabError,
  WithOrigin
}
import xyz.kd5ujc.scarab.protocol.LeiosWireError
import xyz.kd5ujc.scarab.protocol.LeiosWord.{Word16, Word32}

/** Why no Dijkstra ledger view is forecast from a Conway ledger state. */
enum CrossingForecastFailure:
  /** `OutsideForecastRange` (`Ouroboros/Consensus/Forecast.hs`, ouroboros-consensus 2416493b): the
    * slot is not before the exclusive bound `maxFor` of a forecast from the tip `at`.
    */
  case OutsideForecastRange(at: WithOrigin[SlotNo], maxFor: SlotNo, forecastFor: SlotNo)

  /** The state does not translate into Dijkstra ([[Translate.conwayToDijkstra]]). */
  case Translation(cause: TickFailure)

  /** A parameter of the view does not fit the word the reference's view holds it in. */
  case Parameter(cause: LeiosWireError)

  case Vocabulary(cause: VocabError)

/** The parts of the hard-fork combinator's crossing from Conway (Praos) into Dijkstra (Praos2) that
  * concern the protocol, at ouroboros-consensus 2416493b with cardano-ledger 9df9f98c: the
  * chain-dependent state and the ledger view forecast across the boundary. Both are pure and
  * neither is wired: the node does not enter Dijkstra (#54).
  */
object DijkstraCrossing:

  /** `translateChainDepState` of `TranslateProto (Praos c) (Praos2 c)`
    * (`Protocol/Praos2.hs:189-200`), which the crossing applies through
    * `translateChainDepStateAcrossShelley` (`Cardano/CanHardFork.hs:166-173`,
    * `Shelley/ShelleyHFC.hs:304-317`): every field carried unchanged and no announcement, since no
    * Praos header could carry one. The epoch transition is the tick's, not the translation's.
    */
  def chainDepState(praos: PraosState): Praos2.State = Praos2.State(praos, None)

  /** `crossEraForecastBound` (`Ouroboros/Consensus/Forecast.hs:174-200`): the exclusive upper bound
    * of a forecast from `tip` across a transition at `transition`, the lesser of what a next block
    * of this era and the first block of the next could change. Slot additions are the reference's
    * unsigned words.
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

  /** `crossEraForecastConwayToDijkstraWrapper` (`Cardano/CanHardFork.hs:767-808`): the Dijkstra
    * ledger view of `forecastFor` from the Conway state `nes` at `tip`, the transition at
    * `transition`. It does not translate the Conway view, which has no Leios parameters; it
    * translates the state ([[Translate.conwayToDijkstra]], as the combinator does when it ticks
    * across the boundary) and forecasts with Dijkstra's own rules (`ledgerViewForecastAtPolyPraos`,
    * `Shelley/Ledger/SupportsProtocol.hs:105-140`).
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
    val innerMax = addSlots(dijkstra.stabilityWindow, successor(tip))
    val atTip = tip == WithOrigin.At(forecastFor)
    // both bounds read only the tip, which the reference's lazy translation leaves for last
    for
      _ <- within(tip, maxFor, forecastFor)
      _ <- if atTip then Right(()) else within(tip, innerMax, forecastFor)
      translated <- Translate
        .conwayToDijkstra(dijkstra, nes)
        .leftMap(CrossingForecastFailure.Translation(_))
      view <-
        if atTip then ledgerView(PoolDistr.of(translated.es.snapshots.set), params(translated))
        else (ledgerView.tupled)(tickf(dijkstra, forecastFor, translated))
    yield view

  /** TICKF of Dijkstra, Conway's (`EraRule "TICKF" DijkstraEra = Conway.TICKF`,
    * `Dijkstra/Era.hs:224`; `Conway/Rules/Tickf.hs:59-85`), as far as the header view reads it: the
    * future parameters made certain from the slot of no return (`solidifyNextEpochPParams`,
    * `Shelley/Rules/Tick.hs:147-159`; `getTheSlotOfNoReturn`, `Slot.hs:106-114`), and only into the
    * epoch after the state's the snapshots rotated by Dijkstra's SNAP, the old mark becoming the
    * set whose pool distribution the forecast reads (`Dijkstra/Rules/Snap.hs:103-143`,
    * `nesStakePoolDistrG`, `Shelley/LedgerState/Types.hs:643-644`), and the parameters of the next
    * epoch in force (`nextEpochPParams`, `State/Governance.hs:121-133`). The new mark and the Leios
    * committee it seats are not in the header view.
    */
  private def tickf(
      globals: Globals,
      slot: SlotNo,
      nes: NewEpochState[Dijkstra]
  ): (PoolDistr, ProtocolParams) =
    val now = slot.toBigInt
    val epoch = globals.epochOf(now)
    val pointOfNoReturn = globals.slotOfNoReturn(epoch)
    val gov = nes.es.ls.utxo.gov
    val future = gov.futurePParams
    val solid = if now < pointOfNoReturn then future else EraLedger.solidify(future)
    if epoch != nes.epoch.toBigInt + 1 then (PoolDistr.of(nes.es.snapshots.set), params(nes))
    else (PoolDistr.of(nes.es.snapshots.mark), solid.definite.getOrElse(params(nes)))

  private def params(nes: NewEpochState[Dijkstra]): ProtocolParams =
    nes.es.ls.utxo.gov.curPParams

  /** `forecastToPolyPraosLedgerView` of Praos2 (`Protocol/Praos2.hs:136-159`) over
    * `mkDijkstraForecast` (`Dijkstra/Forecast.hs:124-152`), the fields header validation reads
    * ([[Praos2.LedgerView]]).
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
      word32(pp.maxEndorserBlockReferencesSize)
    ).mapN(
      Praos2.LedgerView(
        pools.distribution.unsorted.map((id, p) => id -> Praos2.PoolStake(p.stake, p.vrf)),
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
