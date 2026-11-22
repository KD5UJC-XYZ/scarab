// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.dijkstra

import cats.data.NonEmptySet
import cats.syntax.all.*
import scala.collection.immutable.SortedSet
import xyz.kd5ujc.scarab.codec.Node
import xyz.kd5ujc.scarab.ledger.{
  Anchor,
  CborRead,
  ConwayParamUpdate,
  DecodeField,
  DecoderVersion,
  GovAction as FlatGovAction,
  GovActionId,
  GovActionState as StoredGovActionState,
  Proposal,
  Proposals as StoredProposals,
  RewardAccount,
  Unsigned
}
import xyz.kd5ujc.scarab.ledger.dijkstra.{
  DijkstraAnchor,
  DijkstraGovAction,
  DijkstraGovActionId,
  DijkstraParamUpdate,
  DijkstraProposal
}
import xyz.kd5ujc.scarab.ledger.era.{
  EraPParams,
  EraTxError,
  GovAction,
  GovActionPurpose,
  GovActionState,
  GovPurposeId,
  PParamsError,
  PParamsUpdate,
  ProposalProcedure,
  Proposals,
  ProposalsReadError,
  ProposalsWriteError
}
import xyz.kd5ujc.scarab.ledger.era.conway.{ConwayPParams, ConwayProposals}
import xyz.kd5ujc.scarab.ledger.rules.Convert
import xyz.kd5ujc.scarab.ledger.state.{Conway, Dijkstra}

/** Dijkstra's proposals read from the node's flat record of them and written back, as Conway's are
  * ([[ConwayProposals]]): the flat record holds Conway's flat proposal ([[Proposal]]), whose update
  * holds Conway's keys only. The ledger state holds Dijkstra's own record, whose proposals the
  * state's codec reads as Dijkstra's decoder reads a proposal at version 12 ([[proposal]]:
  * [[DijkstraProposal.decode]], then [[DijkstraProcedures.proposal]]); a flat proposal is read
  * ([[fromFlat]]) where the chain enters Dijkstra, its update read through its typed value and the
  * era's encoder. Written into the flat record, an update that sets a parameter Dijkstra adds (keys
  * 34 to 56) is refused ([[ProposalsWriteError.UnstorableUpdate]]): the flat record cannot hold it.
  * At cardano-ledger 9df9f98c.
  */
object DijkstraProposals:
  /** A proposal of a Dijkstra state's item, as the state's decoder reads it (`DecCBOR
    * (ProposalProcedure DijkstraEra)` at version 12,
    * `eras/conway/impl/src/Cardano/Ledger/Conway/Governance/Procedures.hs:567-580`, cardano-ledger
    * 9df9f98c): the reader of a stored Dijkstra proposal, for a codec of the era's governance
    * state.
    */
  def proposal(n: Node): Either[EraTxError, ProposalProcedure[Dijkstra]] =
    DijkstraProposal.decode(n).leftMap(EraTxError.Decode(_)).flatMap(DijkstraProcedures.proposal)

  private def outOfRange(field: DecodeField, n: BigInt): EraTxError =
    EraTxError.PParams(PParamsError.OutOfRange(field, n))

  private def actionId(id: GovActionId): Either[EraTxError, DijkstraGovActionId] =
    Unsigned
      .from[Unsigned.Width.W16](id.index)
      .toRight(outOfRange(DecodeField.ActionIndex, id.index))
      .map(ix => DijkstraGovActionId(id.txId, ix.value.toInt))

  private def word32(field: DecodeField, n: BigInt): Either[EraTxError, Long] =
    Unsigned.from[Unsigned.Width.W32](n).toRight(outOfRange(field, n)).map(_.value.toLong)

  /** A stored update, Conway's keys only, as Dijkstra's decoder reads the same map at version 12:
    * its typed value ([[ConwayPParams.fromUpdate]]) written by Conway's encoder of an update
    * (`EncCBOR (PParamsUpdate era)`, `Core/PParams.hs:250-262`, cardano-ledger 9df9f98c), whose
    * keys and values for those keys are Dijkstra's (`Dijkstra/PParams.hs:741-776`).
    */
  private def update(u: ConwayParamUpdate): Either[EraTxError, DijkstraParamUpdate] =
    for
      typed <- ConwayPParams.fromUpdate(u).leftMap(EraTxError.PParams(_))
      bytes = PParamsUpdate
        .encodePParamsUpdate(PParamsUpdate[Conway](typed))(using
          EraPParams.conway
        )
        .bytes
      n <- CborRead.decode(bytes).leftMap(EraTxError.Decode(_))
      read <- DijkstraParamUpdate.decode(n).leftMap(EraTxError.Decode(_))
    yield read

  private def anchor(a: Anchor): DijkstraAnchor = DijkstraAnchor(a.url, a.hash)

  private def flatAction(a: FlatGovAction): Either[EraTxError, DijkstraGovAction] =
    def previous(p: Option[GovActionId]) = p.traverse(actionId)
    a match
      case FlatGovAction.ParameterChange(prev, u, policy) =>
        (previous(prev), update(u)).mapN(DijkstraGovAction.ParameterChange(_, _, policy))
      case FlatGovAction.HardFork(prev, major, minor) =>
        (previous(prev), word32(DecodeField.Major, major), word32(DecodeField.Minor, minor))
          .mapN(DijkstraGovAction.HardForkInitiation(_, _, _))
      case FlatGovAction.TreasuryWithdrawals(ws, policy) =>
        Right(DijkstraGovAction.TreasuryWithdrawals(ws.toMap, policy))
      case FlatGovAction.NoConfidence(prev) =>
        previous(prev).map(DijkstraGovAction.NoConfidence(_))
      case FlatGovAction.UpdateCommittee(prev, removed, added, quorum) =>
        previous(prev).map(DijkstraGovAction.UpdateCommittee(_, removed, added, quorum))
      case FlatGovAction.NewConstitution(prev, script, a) =>
        previous(prev).map(DijkstraGovAction.NewConstitution(_, anchor(a), script))
      case FlatGovAction.Info => Right(DijkstraGovAction.InfoAction)

  /** The flat proposal the node's record holds, as Dijkstra's decoder reads the proposal it was
    * stored from: the flat proposal's values as Dijkstra's decoded proposal, then
    * [[DijkstraProcedures.proposal]]. A value outside Dijkstra's types is refused, which the
    * state's decoder at version 12 already refused.
    */
  def fromFlat(p: Proposal): Either[EraTxError, ProposalProcedure[Dijkstra]] =
    flatAction(p.action)
      .map(a => DijkstraProposal(p.deposit, p.returnAccount, a, anchor(p.anchor)))
      .flatMap(DijkstraProcedures.proposal)

  /** Conway's update keys (`Conway/PParams.hs:862-894`, cardano-ledger f649f975), the ones the flat
    * update has a field for.
    */
  private val conwayKeys: Set[BigInt] =
    EraPParams.conway.eraPParams.flatMap(_.ppUpdate.map(_.ppuTag.value)).toSet

  /** The update keys `u` sets, in the order of the era's descriptions. */
  def updateKeys(u: PParamsUpdate[Dijkstra]): Vector[BigInt] =
    EraPParams.dijkstra.eraPParams.flatMap(
      _.ppUpdate.filter(_.ppuLens.get(u).isDefined).map(_.ppuTag.value)
    )

  /** A Dijkstra update as the flat record holds it: refused if it sets a key Conway's update has no
    * field for, else written by the era's encoder (`EncCBOR (PParamsUpdate DijkstraEra)`) and read
    * by the flat reader of an update at version 12 ([[ConwayParamUpdate.decode]]).
    */
  private def flatUpdate(
      id: GovActionId,
      u: PParamsUpdate[Dijkstra]
  ): Either[ProposalsWriteError, ConwayParamUpdate] =
    NonEmptySet
      .fromSet(SortedSet.from(updateKeys(u).filterNot(conwayKeys)))
      .map(ProposalsWriteError.UnstorableUpdate(id, _))
      .toLeft(())
      .flatMap(_ =>
        CborRead
          .decode(PParamsUpdate.encodePParamsUpdate(u)(using EraPParams.dijkstra).bytes)
          .flatMap(ConwayParamUpdate.decode(_, DecoderVersion.Dijkstra))
          .leftMap(ProposalsWriteError.StoredUpdate(id, _))
      )

  /** Dijkstra's proposal of the action `id` as the flat record holds it, the inverse of
    * [[fromFlat]] where the record holds the proposal's update: each field the flat proposal's, the
    * withdrawals in the reference's order (`Map AccountAddress Coin`).
    */
  def toFlat(
      id: GovActionId,
      p: ProposalProcedure[Dijkstra]
  ): Either[ProposalsWriteError, Proposal] =
    val action: Either[ProposalsWriteError, FlatGovAction] =
      flatOrUpdate(p.pProcGovAction).fold(
        { case GovAction.ParameterChange(parent, u, guardrails) =>
          flatUpdate(id, u).map(
            FlatGovAction.ParameterChange(parent.map(_.unGovPurposeId), _, guardrails.map(_.bytes))
          )
        },
        Right(_)
      )
    action.map(Proposal(p.pProcDeposit.value, p.pProcReturnAddr, _, p.pProcAnchor))

  /** An action of Dijkstra as the flat record holds it, every action but a parameter change, which
    * is given back: its update is Dijkstra's ([[PParamsUpdate]]), which the flat record's update of
    * Conway's keys does not hold. The other actions have the same values in both, which the Haskell
    * state codec writes by the flat record's writer.
    */
  def flatOrUpdate(
      a: GovAction[Dijkstra]
  ): Either[GovAction.ParameterChange[Dijkstra], FlatGovAction] =
    def previous[P <: GovActionPurpose](parent: Option[GovPurposeId[P]]): Option[GovActionId] =
      parent.map(_.unGovPurposeId)
    a match
      case change: GovAction.ParameterChange[Dijkstra] => Left(change)
      case GovAction.HardForkInitiation(parent, v) =>
        Right(FlatGovAction.HardFork(previous(parent), BigInt(v.pvMajor.value), v.pvMinor.value))
      case GovAction.TreasuryWithdrawals(ws, guardrails) =>
        Right(
          FlatGovAction.TreasuryWithdrawals(
            ws.toVector.map((a, c) => a -> c.value).sortBy(_._1)(using Ordering[RewardAccount]),
            guardrails.map(_.bytes)
          )
        )
      case GovAction.NoConfidence(parent) => Right(FlatGovAction.NoConfidence(previous(parent)))
      case GovAction.UpdateCommittee(parent, removed, added, threshold) =>
        Right(
          FlatGovAction.UpdateCommittee(
            previous(parent),
            removed.map(Convert.back(_)),
            added.map((c, e) => Convert.back(c) -> e.toBigInt),
            threshold.rational
          )
        )
      case GovAction.NewConstitution(parent, c) =>
        Right(FlatGovAction.NewConstitution(previous(parent), c.script, c.anchor))
      case GovAction.InfoAction() => Right(FlatGovAction.Info)

  /** A stored action's state as Dijkstra's, its proposal read by [[fromFlat]]. */
  def action(stored: StoredGovActionState): Either[ProposalsReadError, GovActionState[Dijkstra]] =
    ConwayProposals.actionWith[Dijkstra, EraTxError](stored, fromFlat)(
      ProposalsReadError.StoredProposal(_, _)
    )

  /** Dijkstra's action state as the node records it, its proposal written by [[toFlat]]. */
  def storedAction(
      gas: GovActionState[Dijkstra]
  ): Either[ProposalsWriteError, StoredGovActionState] =
    toFlat(gas.gasId, gas.gasProposalProcedure).map(ConwayProposals.storedWith(gas, _))

  /** The node's record of the proposals as Dijkstra's [[Proposals]]: `mkProposals` over its roots
    * and actions in order, as Conway's ([[ConwayProposals.fromStored]]).
    */
  def fromStored(stored: StoredProposals): Either[ProposalsReadError, Proposals[Dijkstra]] =
    stored.actions
      .traverse(action)
      .flatMap(Proposals.mkProposals[Dijkstra](ConwayProposals.roots(stored.roots), _))

  /** Dijkstra's [[Proposals]] as the node records them: the roots and the actions in order, or the
    * first action the record cannot hold.
    */
  def toStored(ps: Proposals[Dijkstra]): Either[ProposalsWriteError, StoredProposals] =
    ps.pProps.toVector
      .traverse(storedAction)
      .map(StoredProposals(_, ConwayProposals.prevIds(Proposals.toPrevGovActionIds(ps.pRoots))))
