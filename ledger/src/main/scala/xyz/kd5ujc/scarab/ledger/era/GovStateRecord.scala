// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import cats.syntax.all.*
import scala.collection.immutable.SortedSet
import xyz.kd5ujc.scarab.ledger.{
  EnactState as StoredEnactState,
  GovActionState as StoredGovActionState,
  ProtocolParams,
  Proposals as StoredProposals,
  PulsingSnapshot as StoredPulsingSnapshot,
  RatifyState as StoredRatifyState
}
import xyz.kd5ujc.scarab.ledger.era.conway.{ConwayPParams, ConwayProposals}
import xyz.kd5ujc.scarab.ledger.era.dijkstra.{DijkstraPParams, DijkstraProposals}
import xyz.kd5ujc.scarab.ledger.rules.Convert
import xyz.kd5ujc.scarab.ledger.state.{
  Coin,
  Conway,
  ConwayGovState as StoredConwayGovState,
  DRComplete as StoredDRComplete,
  Dijkstra,
  EraTag,
  FuturePParams as StoredFuturePParams,
  Sorted,
  Staking,
  VocabError
}

/** A parameter record of the governance state, which a refusal to read it names. */
enum GovStateParams:
  /** `cgsCurPParams`. */
  case Current

  /** `cgsPrevPParams`. */
  case Previous

  /** The parameters `cgsFuturePParams` holds. */
  case Future

  /** The ratification's enact state's `ensCurPParams`. */
  case EnactCurrent

  /** The ratification's enact state's `ensPrevPParams`. */
  case EnactPrevious

/** The node's parameter record each [[GovStateParams]] position of the governance state is written
  * over ([[GovStateRecord.storedPParams]]): the other eras' fields each position keeps. A restored
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

/** Why the node's record of the governance state cannot be read into an era's [[ConwayGovState]]:
  * what only a damaged record gives, as [[ProposalsReadError]] is. None is a verdict of the rules.
  */
enum GovStateReadError:
  /** The proposals ([[ProposalsReadError]]). */
  case Proposals(error: ProposalsReadError)

  /** An action the ratification or the pulser's snapshot holds, outside the era's types. */
  case Action(error: ProposalsReadError)

  /** A parameter record outside the era's types ([[PParamsError]]). */
  case Parameters(params: GovStateParams, error: PParamsError)

  /** A credential of the enact state's withdrawals of another length than a stake credential's. */
  case Withdrawal(error: VocabError)

/** How era `E` reads the node's record of the governance state (the flat
  * [[xyz.kd5ujc.scarab.ledger.state.ConwayGovState]]) into its [[ConwayGovState]] and writes it
  * back: its parameters, its proposals and each action state the record holds. Glue, until the
  * ledger state holds the era's record itself. One instance per era, in the companion.
  */
sealed trait GovStateRecord[E <: EraTag]:
  /** The era's fields of the node's parameters. */
  def pparams(p: ProtocolParams): Either[PParamsError, PParams[E]]

  /** `base` with the era's fields `pp` 's: the inverse of [[pparams]] over the node's record, whose
    * other eras' fields `base` keeps.
    */
  def storedPParams(pp: PParams[E], base: ProtocolParams): ProtocolParams

  def proposals(stored: StoredProposals): Either[ProposalsReadError, Proposals[E]]
  def storedProposals(ps: Proposals[E]): Either[ProposalsWriteError, StoredProposals]
  def action(stored: StoredGovActionState): Either[ProposalsReadError, GovActionState[E]]
  def storedAction(gas: GovActionState[E]): Either[ProposalsWriteError, StoredGovActionState]

object GovStateRecord:
  /** Conway's: its parameters ([[ConwayPParams.fromStored]]) and proposals ([[ConwayProposals]]),
    * whose every action the record holds.
    */
  given conway: GovStateRecord[Conway] with
    def pparams(p: ProtocolParams): Either[PParamsError, PParams[Conway]] =
      ConwayPParams.fromStored(p).map(PParams[Conway](_))
    def storedPParams(pp: PParams[Conway], base: ProtocolParams): ProtocolParams =
      ConwayPParams.toStored(pp.hkd, base)
    def proposals(stored: StoredProposals): Either[ProposalsReadError, Proposals[Conway]] =
      ConwayProposals.fromStored(stored)
    def storedProposals(ps: Proposals[Conway]): Either[ProposalsWriteError, StoredProposals] =
      Right(ConwayProposals.toStored(ps))
    def action(stored: StoredGovActionState): Either[ProposalsReadError, GovActionState[Conway]] =
      ConwayProposals.action(stored)
    def storedAction(
        gas: GovActionState[Conway]
    ): Either[ProposalsWriteError, StoredGovActionState] =
      Right(ConwayProposals.storedAction(gas))

  /** Dijkstra's: its parameters ([[DijkstraPParams.fromStored]]) and proposals
    * ([[DijkstraProposals]]), an update of the keys Dijkstra adds refused on the way back.
    */
  given dijkstra: GovStateRecord[Dijkstra] with
    def pparams(p: ProtocolParams): Either[PParamsError, PParams[Dijkstra]] =
      DijkstraPParams.fromStored(p).map(PParams[Dijkstra](_))
    def storedPParams(pp: PParams[Dijkstra], base: ProtocolParams): ProtocolParams =
      DijkstraPParams.toStored(pp.hkd, base)
    def proposals(stored: StoredProposals): Either[ProposalsReadError, Proposals[Dijkstra]] =
      DijkstraProposals.fromStored(stored)
    def storedProposals(ps: Proposals[Dijkstra]): Either[ProposalsWriteError, StoredProposals] =
      DijkstraProposals.toStored(ps)
    def action(
        stored: StoredGovActionState
    ): Either[ProposalsReadError, GovActionState[Dijkstra]] =
      DijkstraProposals.action(stored)
    def storedAction(
        gas: GovActionState[Dijkstra]
    ): Either[ProposalsWriteError, StoredGovActionState] =
      DijkstraProposals.storedAction(gas)

  private def params[E <: EraTag](at: GovStateParams, p: ProtocolParams)(using
      r: GovStateRecord[E]
  ): Either[GovStateReadError, PParams[E]] =
    r.pparams(p).leftMap(GovStateReadError.Parameters(at, _))

  private def actions[E <: EraTag](stored: Vector[StoredGovActionState])(using
      r: GovStateRecord[E]
  ): Either[GovStateReadError, Vector[GovActionState[E]]] =
    stored.traverse(r.action).leftMap(GovStateReadError.Action(_))

  private def enactState[E <: EraTag](s: StoredEnactState)(using
      GovStateRecord[E]
  ): Either[GovStateReadError, EnactState[E]] =
    for
      cur <- params[E](GovStateParams.EnactCurrent, s.params)
      prev <- params[E](GovStateParams.EnactPrevious, s.prevParams)
      withdrawals <- s.withdrawals.toVector
        .traverse((c, v) => Convert.credential[Staking](c).map(_ -> Coin(v)))
        .leftMap(GovStateReadError.Withdrawal(_))
    yield EnactState[E](
      s.committee,
      s.constitution,
      cur,
      prev,
      Coin(s.treasury),
      Sorted.map(withdrawals),
      ConwayProposals.roots(s.prevIds)
    )

  private def futurePParams[E <: EraTag](f: StoredFuturePParams)(using
      GovStateRecord[E]
  ): Either[GovStateReadError, FuturePParams[E]] = f match
    case StoredFuturePParams.NoPParamsUpdate => Right(FuturePParams.NoPParamsUpdate())
    case StoredFuturePParams.DefinitePParamsUpdate(p) =>
      params[E](GovStateParams.Future, p).map(FuturePParams.DefinitePParamsUpdate(_))
    case StoredFuturePParams.PotentialPParamsUpdate(p) =>
      p.traverse(params[E](GovStateParams.Future, _)).map(FuturePParams.PotentialPParamsUpdate(_))

  /** The node's record of the governance state as era `E` 's [[ConwayGovState]]: each field the
    * record's at the era's types, the proposals rebuilt as `mkProposals` rebuilds them
    * ([[GovStateRecord.proposals]]) and the actions of the ratification and of the pulser's
    * snapshot each read as one of the proposals is, in their order.
    */
  def fromStored[E <: EraTag](g: StoredConwayGovState)(using
      r: GovStateRecord[E]
  ): Either[GovStateReadError, ConwayGovState[E]] =
    val ratify = g.drepPulsing.ratify
    val snapshot = g.drepPulsing.snapshot
    for
      proposals <- r.proposals(g.proposals).leftMap(GovStateReadError.Proposals(_))
      cur <- params[E](GovStateParams.Current, g.curPParams)
      prev <- params[E](GovStateParams.Previous, g.prevPParams)
      future <- futurePParams[E](g.futurePParams)
      ratifyState <- ratifyStateFromStored[E](ratify)
      snapshotActions <- actions[E](snapshot.proposals)
    yield ConwayGovState[E](
      proposals,
      g.committee,
      g.constitution,
      cur,
      prev,
      future,
      DRepPulsingState.DRComplete(
        PulsingSnapshot[E](
          snapshotActions,
          snapshot.drepDistr,
          snapshot.drepState,
          snapshot.poolDistr
        ),
        ratifyState
      )
    )

  /** The node's record of a ratification as era `E` 's [[RatifyState]]: its enact state's
    * parameters at the era's types and its enacted actions each read as one of the proposals is.
    */
  def ratifyStateFromStored[E <: EraTag](ratify: StoredRatifyState)(using
      GovStateRecord[E]
  ): Either[GovStateReadError, RatifyState[E]] =
    for
      enact <- enactState[E](ratify.enact)
      enacted <- actions[E](ratify.enacted)
    yield RatifyState[E](enact, enacted, SortedSet.from(ratify.expired), ratify.delayed)

  /** Era `E` 's ratification as the node records it, the inverse of [[ratifyStateFromStored]]: its
    * enact state's parameter records written over their positions' records of `bases`, or the first
    * enacted action the record cannot hold.
    */
  def ratifyStateToStored[E <: EraTag](ratify: RatifyState[E], bases: GovStateBases)(using
      r: GovStateRecord[E]
  ): Either[ProposalsWriteError, StoredRatifyState] =
    val enact = ratify.rsEnactState
    ratify.rsEnacted
      .traverse(r.storedAction)
      .map(enacted =>
        StoredRatifyState(
          StoredEnactState(
            enact.ensCommittee,
            enact.ensConstitution,
            r.storedPParams(enact.ensCurPParams, bases.at(GovStateParams.EnactCurrent)),
            r.storedPParams(enact.ensPrevPParams, bases.at(GovStateParams.EnactPrevious)),
            enact.ensTreasury.value,
            enact.ensWithdrawals.iterator.map((c, v) => Convert.back(c) -> v.value).toMap,
            ConwayProposals.prevIds(enact.ensPrevGovActionIds)
          ),
          enacted,
          ratify.rsExpired,
          ratify.rsDelayed
        )
      )

  /** Era `E` 's governance state as the node records it, the inverse of [[fromStored]]: each
    * parameter record written over its position's record of `bases`
    * ([[GovStateRecord.storedPParams]]), which gives the fields of the other eras the node's record
    * holds, or the first action the record cannot hold ([[ProposalsWriteError]]). The record a
    * state was read from gives them ([[GovStateBases.of]]); a state the Haskell state codecs read
    * holds those fields at the value the codec leaves them at.
    */
  def toStored[E <: EraTag](g: ConwayGovState[E], bases: GovStateBases)(using
      r: GovStateRecord[E]
  ): Either[ProposalsWriteError, StoredConwayGovState] =
    def stored(at: GovStateParams)(pp: PParams[E]): ProtocolParams =
      r.storedPParams(pp, bases.at(at))
    val future = g.cgsFuturePParams match
      case FuturePParams.NoPParamsUpdate() => StoredFuturePParams.NoPParamsUpdate
      case FuturePParams.DefinitePParamsUpdate(pp) =>
        StoredFuturePParams.DefinitePParamsUpdate(stored(GovStateParams.Future)(pp))
      case FuturePParams.PotentialPParamsUpdate(pp) =>
        StoredFuturePParams.PotentialPParamsUpdate(pp.map(stored(GovStateParams.Future)))
    g.cgsDRepPulsingState match
      case DRepPulsingState.DRComplete(snapshot, ratify) =>
        for
          proposals <- r.storedProposals(g.cgsProposals)
          storedRatify <- ratifyStateToStored(ratify, bases)
          snapshotActions <- snapshot.psProposals.traverse(r.storedAction)
        yield StoredConwayGovState(
          proposals,
          g.cgsCommittee,
          g.cgsConstitution,
          stored(GovStateParams.Current)(g.cgsCurPParams),
          stored(GovStateParams.Previous)(g.cgsPrevPParams),
          future,
          StoredDRComplete(
            StoredPulsingSnapshot(
              snapshotActions,
              snapshot.psDRepDistr,
              snapshot.psDRepState,
              snapshot.psPoolDistr
            ),
            storedRatify
          )
        )
