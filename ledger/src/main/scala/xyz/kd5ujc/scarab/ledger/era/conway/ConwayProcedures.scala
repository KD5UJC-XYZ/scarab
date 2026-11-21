// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.conway

import cats.syntax.all.*
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.ledger.{
  Constitution,
  ConwayParamUpdate,
  DecodeField,
  DijkstraKeysUpdate,
  GovAction as FlatGovAction,
  GovActionId,
  Proposal,
  Unsigned,
  Voter,
  VotingProcedure
}
import xyz.kd5ujc.scarab.ledger.rules.Convert
import xyz.kd5ujc.scarab.ledger.era.{
  EraTxError,
  GovAction,
  GovPurposeId,
  PParamsError,
  PParamsUpdate,
  ProposalProcedure,
  ProtVer,
  UnitInterval,
  Version,
  VotingProcedures
}
import xyz.kd5ujc.scarab.ledger.state.{ColdCommittee, Coin, Conway, EpochNo, ScriptHash}

// Conway's governance procedures, read from what Conway's decoder read: only this object makes a
// Conway proposal or Conway's votes.

/** Conway's proposals and votes from the flat decoder's (`GovAction ConwayEra`,
  * `ProposalProcedure ConwayEra`, `VotingProcedures ConwayEra`, cardano-ledger f649f975
  * `eras/conway/impl/src/Cardano/Ledger/Conway/Governance/Procedures.hs:400-404,498-504,815-868`).
  */
object ConwayProcedures:
  /** The action the flat decoder read, at the reference's types. The decoder bounded each field as
    * the reference's type does, so none of these refusals is reached from bytes it accepted: an
    * update outside its fields' types ([[ConwayPParams.fromUpdate]]), a version outside `Version`
    * and `Word32`, a script hash or committee credential of the wrong length, an epoch outside
    * `Word64` and a threshold outside `[0, 1]`. An update that sets a parameter Dijkstra adds,
    * which only a Dijkstra state holds, is not Conway's: refused at its first such key.
    */
  private[era] def govAction(a: FlatGovAction): Either[EraTxError, GovAction[Conway]] = a match
    case FlatGovAction.ParameterChange(_, update: DijkstraKeysUpdate, _) =>
      Left(EraTxError.PParams(PParamsError.UnknownKey(update.firstAdded)))
    case FlatGovAction.ParameterChange(previous, update: ConwayParamUpdate, policy) =>
      (
        ConwayPParams.fromUpdate(update).leftMap(EraTxError.PParams(_)),
        guardrails(policy)
      ).mapN((u, g) =>
        GovAction.ParameterChange(previous.map(GovPurposeId(_)), PParamsUpdate[Conway](u), g)
      )
    case FlatGovAction.HardFork(previous, major, minor) =>
      (
        Version
          .mkVersion(major)
          .toRight(PParamsError.OutOfRange(DecodeField.Major, major)),
        Unsigned
          .from[Unsigned.Width.W32](minor)
          .toRight(PParamsError.OutOfRange(DecodeField.Minor, minor))
      ).mapN(ProtVer(_, _))
        .leftMap(EraTxError.PParams(_))
        .map(v => GovAction.HardForkInitiation(previous.map(GovPurposeId(_)), v))
    case FlatGovAction.TreasuryWithdrawals(withdrawals, policy) =>
      // the decoder refused a repeated account (`decodeMap` from version 9), so the map holds
      // every withdrawal read
      guardrails(policy).map(g =>
        GovAction.TreasuryWithdrawals(withdrawals.map((a, c) => a -> Coin(c)).toMap, g)
      )
    case FlatGovAction.NoConfidence(previous) =>
      Right(GovAction.NoConfidence(previous.map(GovPurposeId(_))))
    case FlatGovAction.UpdateCommittee(previous, removed, added, quorum) =>
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
      ).mapN((r, m, q) => GovAction.UpdateCommittee(previous.map(GovPurposeId(_)), r, m, q))
    case FlatGovAction.NewConstitution(previous, script, anchor) =>
      Right(
        GovAction.NewConstitution(previous.map(GovPurposeId(_)), Constitution(anchor, script))
      )
    case FlatGovAction.Info => Right(GovAction.InfoAction())

  private def guardrails(
      policy: Option[xyz.kd5ujc.scarab.codec.Bytes]
  ): Either[EraTxError, Option[ScriptHash]] =
    policy.traverse(ScriptHash.fromBytes).leftMap(EraTxError.Vocab(_))

  /** The proposal the flat decoder read, its action at the reference's types ([[govAction]]). */
  private[era] def proposal(p: Proposal): Either[EraTxError, ProposalProcedure[Conway]] =
    govAction(p.action).map(a =>
      ProposalProcedure[Conway](Coin(p.deposit), p.returnAccount, a, p.anchor, p)
    )

  /** The votes the flat decoder read, which refused a repeated voter or action (`decodeMapByKey`)
    * and a voter without votes (`DecCBOR (VotingProcedures era)`, `Procedures.hs:408-416`,
    * cardano-ledger f649f975).
    */
  private[era] def votes(
      all: Vector[(Voter, Vector[(GovActionId, VotingProcedure)])]
  ): VotingProcedures[Conway] =
    VotingProcedures[Conway](SortedMap.from(all.map((voter, vs) => voter -> SortedMap.from(vs))))
