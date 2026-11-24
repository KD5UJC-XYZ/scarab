// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import xyz.kd5ujc.scarab.ledger.{
  EnactState as StoredEnactState,
  ProtocolParams,
  PulsingSnapshot as StoredPulsingSnapshot,
  RatifyState as StoredRatifyState
}
import xyz.kd5ujc.scarab.ledger.era.{
  ConwayGovState,
  DRepPulsingState,
  FuturePParams,
  GovStateParams,
  PParams,
  RatifyState
}
import xyz.kd5ujc.scarab.ledger.era.conway.{ConwayPParams, ConwayProposals}
import xyz.kd5ujc.scarab.ledger.rules.Convert
import xyz.kd5ujc.scarab.ledger.state.{
  Conway,
  ConwayGovState as StoredConwayGovState,
  DRComplete as StoredDRComplete,
  FuturePParams as StoredFuturePParams
}

/** The node's parameter record each [[GovStateParams]] position of the governance state is written
  * over ([[ConwayGovWrite.storedPParams]]): the other eras' fields each position keeps. A restored
  * state can hold different such fields at each position (the current and previous parameters the
  * sidecar's, the enact state's as the Haskell section holds them), so each position keeps its own.
  */
final case class GovStateBases(
    current: ProtocolParams,
    previous: ProtocolParams,
    future: ProtocolParams,
    enactCurrent: ProtocolParams,
    enactPrevious: ProtocolParams
):
  def at(params: GovStateParams): ProtocolParams = params match
    case GovStateParams.Current       => current
    case GovStateParams.Previous      => previous
    case GovStateParams.Future        => future
    case GovStateParams.EnactCurrent  => enactCurrent
    case GovStateParams.EnactPrevious => enactPrevious

object GovStateBases:
  /** One record for every position: a state with no record of its own, whose other eras' fields are
    * all `base` 's.
    */
  def uniform(base: ProtocolParams): GovStateBases =
    GovStateBases(base, base, base, base, base)

  /** The positions of `g`, the record a state was read from: each position its own record, and the
    * future parameters', where `g` holds none, the current ones' (the future parameters are the
    * current ones with an update, as the restoration lays them over).
    */
  def of(g: StoredConwayGovState): GovStateBases =
    val enact = g.drepPulsing.ratify.enact
    val future = g.futurePParams match
      case StoredFuturePParams.DefinitePParamsUpdate(p)        => p
      case StoredFuturePParams.PotentialPParamsUpdate(Some(p)) => p
      case StoredFuturePParams.NoPParamsUpdate | StoredFuturePParams.PotentialPParamsUpdate(None) =>
        g.curPParams
    GovStateBases(g.curPParams, g.prevPParams, future, enact.params, enact.prevParams)

/** Conway's governance state written as the node's flat record, the inverse of
  * `GovStateRecord.fromStored` and `ratifyStateFromStored`, for the tests that read a recorded
  * state's record into Conway's and back: nothing in the node writes the era's governance state
  * back whole (Conway's rules write the proposals alone, `ProposalsRecord`).
  */
object ConwayGovWrite:
  /** `base` with Conway's fields `pp` 's: the inverse of `GovStateRecord.conway.pparams` over the
    * node's record, whose other eras' fields `base` keeps.
    */
  def storedPParams(pp: PParams[Conway], base: ProtocolParams): ProtocolParams =
    ConwayPParams.toStored(pp.hkd, base)

  /** Conway's ratification as the node records it: its enact state's parameter records written over
    * their positions' records of `bases`.
    */
  def ratifyStateToStored(ratify: RatifyState[Conway], bases: GovStateBases): StoredRatifyState =
    val enact = ratify.rsEnactState
    StoredRatifyState(
      StoredEnactState(
        enact.ensCommittee,
        enact.ensConstitution,
        storedPParams(enact.ensCurPParams, bases.at(GovStateParams.EnactCurrent)),
        storedPParams(enact.ensPrevPParams, bases.at(GovStateParams.EnactPrevious)),
        enact.ensTreasury.value,
        enact.ensWithdrawals.iterator.map((c, v) => Convert.back(c) -> v.value).toMap,
        ConwayProposals.prevIds(enact.ensPrevGovActionIds)
      ),
      ratify.rsEnacted.map(ConwayProposals.storedAction),
      ratify.rsExpired,
      ratify.rsDelayed
    )

  /** Conway's governance state as the node records it: each parameter record written over its
    * position's record of `bases` ([[storedPParams]]), which gives the fields of the other eras the
    * node's record holds. The record a state was read from gives them ([[GovStateBases.of]]); a
    * state the Haskell state codecs read holds those fields at the value the codec leaves them at.
    */
  def toStored(g: ConwayGovState[Conway], bases: GovStateBases): StoredConwayGovState =
    def stored(at: GovStateParams)(pp: PParams[Conway]): ProtocolParams =
      storedPParams(pp, bases.at(at))
    val future = g.cgsFuturePParams match
      case FuturePParams.NoPParamsUpdate() => StoredFuturePParams.NoPParamsUpdate
      case FuturePParams.DefinitePParamsUpdate(pp) =>
        StoredFuturePParams.DefinitePParamsUpdate(stored(GovStateParams.Future)(pp))
      case FuturePParams.PotentialPParamsUpdate(pp) =>
        StoredFuturePParams.PotentialPParamsUpdate(pp.map(stored(GovStateParams.Future)))
    g.cgsDRepPulsingState match
      case DRepPulsingState.DRComplete(snapshot, ratify) =>
        StoredConwayGovState(
          ConwayProposals.toStored(g.cgsProposals),
          g.cgsCommittee,
          g.cgsConstitution,
          stored(GovStateParams.Current)(g.cgsCurPParams),
          stored(GovStateParams.Previous)(g.cgsPrevPParams),
          future,
          StoredDRComplete(
            StoredPulsingSnapshot(
              snapshot.psProposals.map(ConwayProposals.storedAction),
              snapshot.psDRepDistr,
              snapshot.psDRepState,
              snapshot.psPoolDistr
            ),
            ratifyStateToStored(ratify, bases)
          )
        )
