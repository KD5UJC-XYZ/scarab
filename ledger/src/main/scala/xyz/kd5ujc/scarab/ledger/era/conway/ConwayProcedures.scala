// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.conway

import cats.syntax.all.*
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.ledger.{
  Constitution,
  DecodeField,
  GovAction,
  GovActionId,
  Proposal,
  Unsigned,
  Voter,
  VotingProcedure
}
import xyz.kd5ujc.scarab.ledger.rules.Convert
import xyz.kd5ujc.scarab.ledger.era.{
  EraTxError,
  GovActionRep,
  GovPurposeId,
  PParamsError,
  PParamsUpdate,
  ProposalProcedureRep,
  ProtVer,
  UnitInterval,
  Version
}
import xyz.kd5ujc.scarab.ledger.state.{ColdCommittee, Coin, Conway, EpochNo, ScriptHash}

// Conway's governance procedures: the opaque types are transparent in this file only, so only
// this file makes a value of them, from what Conway's decoder read.

/** Conway's governance action: `GovAction ConwayEra` (cardano-ledger f649f975
  * `eras/conway/impl/src/Cardano/Ledger/Conway/Governance/Procedures.hs:815-868`), whose update is
  * Conway's (`PParamsUpdate ConwayEra`).
  */
opaque type ConwayGovAction = GovActionRep[PParamsUpdate[Conway]]

object ConwayGovAction:
  /** The action the flat decoder read, at the reference's types. The decoder bounded each field as
    * the reference's type does, so none of these refusals is reached from bytes it accepted: an
    * update outside its fields' types ([[ConwayPParams.fromUpdate]]), a version outside `Version`
    * and `Word32`, a script hash or committee credential of the wrong length, an epoch outside
    * `Word64` and a threshold outside `[0, 1]`.
    */
  private[era] def fromFlat(a: GovAction): Either[EraTxError, ConwayGovAction] = a match
    case GovAction.ParameterChange(previous, update, policy) =>
      (
        ConwayPParams.fromUpdate(update).leftMap(EraTxError.PParams(_)),
        guardrails(policy)
      ).mapN((u, g) =>
        GovActionRep.ParameterChange(previous.map(GovPurposeId(_)), PParamsUpdate[Conway](u), g)
      )
    case GovAction.HardFork(previous, major, minor) =>
      (
        Version
          .mkVersion(major)
          .toRight(PParamsError.OutOfRange(DecodeField.Major, major)),
        Unsigned
          .from[Unsigned.Width.W32](minor)
          .toRight(PParamsError.OutOfRange(DecodeField.Minor, minor))
      ).mapN(ProtVer(_, _))
        .leftMap(EraTxError.PParams(_))
        .map(v => GovActionRep.HardForkInitiation(previous.map(GovPurposeId(_)), v))
    case GovAction.TreasuryWithdrawals(withdrawals, policy) =>
      // the decoder refused a repeated account (`decodeMap` from version 9), so the map holds
      // every withdrawal read
      guardrails(policy).map(g =>
        GovActionRep.TreasuryWithdrawals(withdrawals.map((a, c) => a -> Coin(c)).toMap, g)
      )
    case GovAction.NoConfidence(previous) =>
      Right(GovActionRep.NoConfidence(previous.map(GovPurposeId(_))))
    case GovAction.UpdateCommittee(previous, removed, added, quorum) =>
      (
        removed.toVector
          .traverse(Convert.credential[ColdCommittee](_))
          .map(_.toSet)
          .leftMap(EraTxError.Vocab(_)),
        added.toVector
          .traverse((c, e) => (Convert.credential[ColdCommittee](c), EpochNo.fromBigInt(e)).tupled)
          .map(_.toMap)
          .leftMap(EraTxError.Vocab(_)),
        UnitInterval
          .boundRational(quorum)
          .toRight(EraTxError.PParams(PParamsError.RatioOutOfBounds(DecodeField.Quorum, quorum)))
      ).mapN((r, m, q) => GovActionRep.UpdateCommittee(previous.map(GovPurposeId(_)), r, m, q))
    case GovAction.NewConstitution(previous, script, anchor) =>
      Right(
        GovActionRep.NewConstitution(previous.map(GovPurposeId(_)), Constitution(anchor, script))
      )
    case GovAction.Info => Right(GovActionRep.InfoAction)

  private def guardrails(
      policy: Option[xyz.kd5ujc.scarab.codec.Bytes]
  ): Either[EraTxError, Option[ScriptHash]] =
    policy.traverse(ScriptHash.fromBytes).leftMap(EraTxError.Vocab(_))

  extension (a: ConwayGovAction)
    /** The shared representation, read only. */
    def rep: GovActionRep[PParamsUpdate[Conway]] = a

  given CanEqual[ConwayGovAction, ConwayGovAction] = CanEqual.derived

/** Conway's governance proposal: `ProposalProcedure ConwayEra` (`Procedures.hs:498-504`,
  * cardano-ledger f649f975), whose action is Conway's.
  */
opaque type ConwayProposalProcedure = ProposalProcedureRep[ConwayGovAction]

object ConwayProposalProcedure:
  /** The proposal the flat decoder read, its action at the reference's types
    * ([[ConwayGovAction.fromFlat]]).
    */
  private[era] def fromFlat(p: Proposal): Either[EraTxError, ConwayProposalProcedure] =
    ConwayGovAction
      .fromFlat(p.action)
      .map(a => ProposalProcedureRep(Coin(p.deposit), p.returnAccount, a, p.anchor, p))

  extension (p: ConwayProposalProcedure)
    /** The shared representation, read only. */
    def rep: ProposalProcedureRep[ConwayGovAction] = p

  given CanEqual[ConwayProposalProcedure, ConwayProposalProcedure] = CanEqual.derived

/** Conway's votes: `VotingProcedures ConwayEra` (`newtype VotingProcedures era`,
  * `Procedures.hs:400-404`, cardano-ledger f649f975), each voter's votes by the action voted on,
  * both in the reference's order (`Ord Voter`, `Ord GovActionId`).
  */
opaque type ConwayVotingProcedures = SortedMap[Voter, SortedMap[GovActionId, VotingProcedure]]

object ConwayVotingProcedures:
  /** The votes the flat decoder read, which refused a repeated voter or action (`decodeMapByKey`)
    * and a voter without votes (`DecCBOR (VotingProcedures era)`, `Procedures.hs:408-416`,
    * cardano-ledger f649f975).
    */
  private[era] def decoded(
      votes: Vector[(Voter, Vector[(GovActionId, VotingProcedure)])]
  ): ConwayVotingProcedures =
    SortedMap.from(votes.map((voter, vs) => voter -> SortedMap.from(vs)))

  extension (v: ConwayVotingProcedures)
    def unVotingProcedures: SortedMap[Voter, SortedMap[GovActionId, VotingProcedure]] = v

  given CanEqual[ConwayVotingProcedures, ConwayVotingProcedures] = CanEqual.derived
