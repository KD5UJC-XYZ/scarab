// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import cats.syntax.all.*
import scala.collection.immutable.SortedSet
import xyz.kd5ujc.scarab.ledger.{
  EnactState as StoredEnactState,
  GovActionState as StoredGovActionState,
  ProtocolParams,
  Proposals as StoredProposals,
  RatifyState as StoredRatifyState
}
import xyz.kd5ujc.scarab.ledger.era.conway.{ConwayPParams, ConwayProposals}
import xyz.kd5ujc.scarab.ledger.era.dijkstra.{DijkstraPParams, DijkstraProposals}
import xyz.kd5ujc.scarab.ledger.rules.Convert
import xyz.kd5ujc.scarab.ledger.state.{
  Coin,
  Conway,
  ConwayGovState as StoredConwayGovState,
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

/** How era `E` reads the node's flat record of the governance state (the flat
  * [[xyz.kd5ujc.scarab.ledger.state.ConwayGovState]]) into its [[ConwayGovState]]: its parameters,
  * its proposals and each action state the record holds. Glue: Conway's ledger state holds the flat
  * record, whose proposals Conway's rules read and write (`ProposalsRecord`); Dijkstra's holds the
  * era's record, read from Conway's flat one where the chain enters Dijkstra ([[fromStored]]).
  * Nothing writes the era's governance state back whole. One instance per era, in the companion.
  */
sealed trait GovStateRecord[E <: EraTag]:
  /** The era's fields of the node's parameters. */
  def pparams(p: ProtocolParams): Either[PParamsError, PParams[E]]

  def proposals(stored: StoredProposals): Either[ProposalsReadError, Proposals[E]]
  def action(stored: StoredGovActionState): Either[ProposalsReadError, GovActionState[E]]

object GovStateRecord:
  /** Dijkstra's parameters `pp` as the node's flat record, every field of the other eras at zero
    * ([[ProtocolParams.empty]]), as the Haskell state codec read them before the ledger state held
    * the era's record: what the node's rules that still read the flat record read of a Dijkstra
    * state (`EraLedger.curPParams`).
    */
  def view(pp: PParams[Dijkstra]): ProtocolParams = viewOver(pp, ProtocolParams.empty)

  /** Dijkstra's parameters `pp` laid over the flat record `base`: every field of the era's from
    * `pp`, the others `base` 's, as a restored snapshot's sidecar is written again.
    */
  def viewOver(pp: PParams[Dijkstra], base: ProtocolParams): ProtocolParams =
    DijkstraPParams.toStored(pp.hkd, base)

  /** Dijkstra's future parameters as the node's flat record ([[view]]). */
  def futureView(f: FuturePParams[Dijkstra]): StoredFuturePParams = f match
    case FuturePParams.NoPParamsUpdate() => StoredFuturePParams.NoPParamsUpdate
    case FuturePParams.DefinitePParamsUpdate(pp) =>
      StoredFuturePParams.DefinitePParamsUpdate(view(pp))
    case FuturePParams.PotentialPParamsUpdate(pp) =>
      StoredFuturePParams.PotentialPParamsUpdate(pp.map(view))

  /** Conway's: its parameters ([[ConwayPParams.fromStored]]) and proposals ([[ConwayProposals]]),
    * whose every action the record holds.
    */
  given conway: GovStateRecord[Conway] with
    def pparams(p: ProtocolParams): Either[PParamsError, PParams[Conway]] =
      ConwayPParams.fromStored(p).map(PParams[Conway](_))
    def proposals(stored: StoredProposals): Either[ProposalsReadError, Proposals[Conway]] =
      ConwayProposals.fromStored(stored)
    def action(stored: StoredGovActionState): Either[ProposalsReadError, GovActionState[Conway]] =
      ConwayProposals.action(stored)

  /** Dijkstra's: its parameters ([[DijkstraPParams.fromStored]]) and proposals
    * ([[DijkstraProposals]]).
    */
  given dijkstra: GovStateRecord[Dijkstra] with
    def pparams(p: ProtocolParams): Either[PParamsError, PParams[Dijkstra]] =
      DijkstraPParams.fromStored(p).map(PParams[Dijkstra](_))
    def proposals(stored: StoredProposals): Either[ProposalsReadError, Proposals[Dijkstra]] =
      DijkstraProposals.fromStored(stored)
    def action(
        stored: StoredGovActionState
    ): Either[ProposalsReadError, GovActionState[Dijkstra]] =
      DijkstraProposals.action(stored)

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
