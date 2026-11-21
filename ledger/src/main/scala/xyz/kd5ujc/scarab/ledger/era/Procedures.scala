// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.ledger.{
  Anchor,
  Constitution,
  GovActionId,
  Proposal,
  RewardAccount,
  Voter,
  VotingProcedure
}
import xyz.kd5ujc.scarab.ledger.state.{
  ColdCommittee,
  Coin,
  Conway,
  Credential,
  Dijkstra,
  EpochNo,
  EraTag,
  ScriptHash
}

// The governance procedures (cardano-ledger f649f975
// `eras/conway/impl/src/Cardano/Ledger/Conway/Governance/Procedures.hs`): data types parameterised
// by the era, as the reference's are (`data GovAction era`, `data ProposalProcedure era`,
// `newtype VotingProcedures era`), not type families. What differs between the eras is the update
// of the protocol parameters (`PParamsUpdate[E]`), so `GovAction[Conway]` and `GovAction[Dijkstra]`
// are distinct types. Each era's values are made only from what its decoder read.

/** What a governance action with a parent is about (`data GovActionPurpose`, cardano-ledger
  * f649f975 `eras/conway/impl/src/Cardano/Ledger/Conway/Governance/Procedures.hs:618-623`): the
  * index of [[GovPurposeId]].
  */
enum GovActionPurpose:
  case PParamUpdatePurpose, HardForkPurpose, CommitteePurpose, ConstitutionPurpose

object GovActionPurpose:
  type PParamUpdate = PParamUpdatePurpose.type
  type HardFork = HardForkPurpose.type
  type Committee = CommitteePurpose.type
  type Constitution = ConstitutionPurpose.type

/** The id of a governance action of purpose `P` (`newtype GovPurposeId (p :: GovActionPurpose)`,
  * `Procedures.hs:651-656`, cardano-ledger f649f975, nominal in `p`): the parent an action names is
  * of the action's own purpose.
  */
opaque type GovPurposeId[P <: GovActionPurpose] = GovActionId

object GovPurposeId:
  def apply[P <: GovActionPurpose](id: GovActionId): GovPurposeId[P] = id

  extension [P <: GovActionPurpose](p: GovPurposeId[P]) def unGovPurposeId: GovActionId = p

  given [P <: GovActionPurpose]: CanEqual[GovPurposeId[P], GovPurposeId[P]] = CanEqual.derived

  /** The derived `Ord (GovPurposeId p)` (`Procedures.hs:651-654`, cardano-ledger f649f975): its
    * action id's ([[GovActionId.ordering]]).
    */
  given ordering[P <: GovActionPurpose]: Ordering[GovPurposeId[P]] = GovActionId.ordering

/** A governance action of era `E` (`data GovAction era`, `Procedures.hs:815-868`, cardano-ledger
  * f649f975), its update the era's (`PParamsUpdate era`) and each parent of the action's purpose.
  * Equal as the reference derives `Eq`: treasury withdrawals as the `Map AccountAddress Coin` they
  * are, an update by the typed value of each field. The constitution is the node's
  * [[Constitution]], the one the governance state holds, whose guardrails script is its hash's
  * bytes (tracked on #85).
  */
enum GovAction[E <: EraTag]:
  case ParameterChange(
      parent: Option[GovPurposeId[GovActionPurpose.PParamUpdate]],
      update: PParamsUpdate[E],
      guardrails: Option[ScriptHash]
  )
  case HardForkInitiation(parent: Option[GovPurposeId[GovActionPurpose.HardFork]], version: ProtVer)
  case TreasuryWithdrawals(withdrawals: Map[RewardAccount, Coin], guardrails: Option[ScriptHash])
  case NoConfidence(parent: Option[GovPurposeId[GovActionPurpose.Committee]])
  case UpdateCommittee(
      parent: Option[GovPurposeId[GovActionPurpose.Committee]],
      removed: Set[Credential[ColdCommittee]],
      added: Map[Credential[ColdCommittee], EpochNo],
      threshold: UnitInterval
  )
  case NewConstitution(
      parent: Option[GovPurposeId[GovActionPurpose.Constitution]],
      constitution: Constitution
  )
  case InfoAction()

object GovAction:
  given [E <: EraTag]: CanEqual[GovAction[E], GovAction[E]] = CanEqual.derived

/** The proposal an era's flat decoder reads, which the flat rules and their failures carry:
  * Conway's [[Proposal]], and Dijkstra's [[xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraProposal]],
  * whose update is Dijkstra's. A step toward #85's item 25, the proposals of the era types, which
  * drops it with the flat failures.
  */
type ProposalRead[E <: EraTag] = E match
  case Conway   => Proposal
  case Dijkstra => xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraProposal

/** A governance proposal of era `E` (`data ProposalProcedure era`, `Procedures.hs:498-504`,
  * cardano-ledger f649f975), with the flat proposal it was read as ([[ProposalRead]]), which the
  * flat rules and their failures carry. Two proposals are equal when they mean the same, as the
  * reference's derived `Eq` compares them: by the deposit, the return account, the action and the
  * anchor, not the proposal read. Made only by an era's decoder, and by the read of the node's
  * record of the proposals, from the proposal it stored (`ConwayProposals.fromStored`).
  */
final class ProposalProcedure[E <: EraTag] private[era] (
    val pProcDeposit: Coin,
    val pProcReturnAddr: RewardAccount,
    val pProcGovAction: GovAction[E],
    val pProcAnchor: Anchor,
    val read: ProposalRead[E]
):
  private def fields: (Coin, RewardAccount, GovAction[E], Anchor) =
    (pProcDeposit, pProcReturnAddr, pProcGovAction, pProcAnchor)

  override def equals(that: Any): Boolean = that match
    case o: ProposalProcedure[?] => fields == o.fields
    case _                       => false
  override def hashCode(): Int = fields.hashCode
  override def toString: String = s"ProposalProcedure$fields"

object ProposalProcedure:
  given [E <: EraTag]: CanEqual[ProposalProcedure[E], ProposalProcedure[E]] = CanEqual.derived

/** The votes of era `E` (`newtype VotingProcedures era`, `Procedures.hs:400-404`, cardano-ledger
  * f649f975): each voter's votes by the action voted on, both in the reference's order (`Ord
  * Voter`, `Ord GovActionId`). Made only by an era's decoder.
  */
final class VotingProcedures[E <: EraTag] private[era] (
    val unVotingProcedures: SortedMap[Voter, SortedMap[GovActionId, VotingProcedure]]
):
  override def equals(that: Any): Boolean = that match
    case o: VotingProcedures[?] => unVotingProcedures == o.unVotingProcedures
    case _                      => false
  override def hashCode(): Int = unVotingProcedures.hashCode
  override def toString: String = s"VotingProcedures($unVotingProcedures)"

object VotingProcedures:
  given [E <: EraTag]: CanEqual[VotingProcedures[E], VotingProcedures[E]] = CanEqual.derived
