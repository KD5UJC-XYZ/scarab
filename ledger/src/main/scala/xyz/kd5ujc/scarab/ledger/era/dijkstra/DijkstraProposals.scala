// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.dijkstra

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Node
import xyz.kd5ujc.scarab.ledger.{
  Anchor,
  CborRead,
  ConwayParamUpdate,
  DecodeField,
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
  ProposalsReadError
}
import xyz.kd5ujc.scarab.ledger.era.conway.{ConwayPParams, ConwayProposals}
import xyz.kd5ujc.scarab.ledger.rules.Convert
import xyz.kd5ujc.scarab.ledger.state.{Conway, Dijkstra}

/** Dijkstra's proposals read from the node's flat record of them, as Conway's are
  * ([[ConwayProposals]]): the flat record holds Conway's flat proposal ([[Proposal]]), whose update
  * holds Conway's keys only. The ledger state holds Dijkstra's own record, whose proposals the
  * state's codec reads as Dijkstra's decoder reads a proposal at version 12 ([[proposal]]:
  * [[DijkstraProposal.decode]], then [[DijkstraProcedures.proposal]]); a flat proposal is read
  * ([[fromFlat]]) where the chain enters Dijkstra, its update read through its typed value and the
  * era's encoder. Nothing writes Dijkstra's proposals into the flat record. At cardano-ledger
  * 9df9f98c.
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

  /** The update keys `u` sets, in the order of the era's descriptions. */
  def updateKeys(u: PParamsUpdate[Dijkstra]): Vector[BigInt] =
    EraPParams.dijkstra.eraPParams.flatMap(
      _.ppUpdate.filter(_.ppuLens.get(u).isDefined).map(_.ppuTag.value)
    )

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

  /** The node's record of the proposals as Dijkstra's [[Proposals]]: `mkProposals` over its roots
    * and actions in order, as Conway's ([[ConwayProposals.fromStored]]).
    */
  def fromStored(stored: StoredProposals): Either[ProposalsReadError, Proposals[Dijkstra]] =
    stored.actions
      .traverse(action)
      .flatMap(Proposals.mkProposals[Dijkstra](ConwayProposals.roots(stored.roots), _))

/** Dijkstra's proposals read by the flat decoder, as the node's flat record of Conway's holds them
  * ([[Proposal]]), for SUBLEDGER's committee proposals (`DijkstraSubLedger.committeeProposals`,
  * beside [[ConwayProposals.storedWith]]): every action but a parameter change, which is given
  * back, since the flat record's update holds Conway's keys only. This stays the Gap of [#85's
  * item](https://github.com/KD5UJC-XYZ/scarab/issues/85#issuecomment-6923349468). At cardano-ledger
  * 9df9f98c.
  */
object DijkstraConway:
  def anchor(a: DijkstraAnchor): Anchor = Anchor(a.url, a.hash)

  def actionId(id: DijkstraGovActionId): GovActionId = GovActionId(id.txId, BigInt(id.index))

  /** The Conway governance action a Dijkstra one is, or a parameter change, which the store of
    * proposals cannot hold yet. The withdrawals of a treasury withdrawal are the reference's `Map`,
    * in its order.
    */
  def action(a: DijkstraGovAction): Either[DijkstraGovAction.ParameterChange, FlatGovAction] =
    a match
      case change: DijkstraGovAction.ParameterChange => Left(change)
      case DijkstraGovAction.HardForkInitiation(prev, major, minor) =>
        Right(FlatGovAction.HardFork(prev.map(actionId), BigInt(major), BigInt(minor)))
      case DijkstraGovAction.TreasuryWithdrawals(ws, policy) =>
        Right(FlatGovAction.TreasuryWithdrawals(ws.toVector.sortBy(_._1), policy))
      case DijkstraGovAction.NoConfidence(prev) =>
        Right(FlatGovAction.NoConfidence(prev.map(actionId)))
      case DijkstraGovAction.UpdateCommittee(prev, removed, added, threshold) =>
        Right(FlatGovAction.UpdateCommittee(prev.map(actionId), removed, added, threshold))
      case DijkstraGovAction.NewConstitution(prev, a, policy) =>
        Right(FlatGovAction.NewConstitution(prev.map(actionId), policy, anchor(a)))
      case DijkstraGovAction.InfoAction => Right(FlatGovAction.Info)

  def proposal(p: DijkstraProposal): Either[DijkstraGovAction.ParameterChange, Proposal] =
    action(p.action).map(Proposal(p.deposit, p.returnAccount, _, anchor(p.anchor)))
