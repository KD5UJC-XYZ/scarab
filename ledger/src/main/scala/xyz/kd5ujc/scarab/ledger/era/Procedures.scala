// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import xyz.kd5ujc.scarab.ledger.{Anchor, Constitution, GovActionId, Proposal, RewardAccount}
import xyz.kd5ujc.scarab.ledger.state.{ColdCommittee, Coin, Credential, EpochNo, ScriptHash}

// The governance procedures the reference shares between Conway and Dijkstra, with the era as a
// parameter (cardano-ledger f649f975
// `eras/conway/impl/src/Cardano/Ledger/Conway/Governance/Procedures.hs`): each is a representation
// parameterised by what differs between the eras, the update of the protocol parameters, and each
// era's own type is an opaque type over it in that era's package (`ConwayGovAction`,
// `ConwayProposalProcedure`).

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

/** A governance action (`data GovAction era`, `Procedures.hs:815-868`, cardano-ledger f649f975)
  * over `U`, the era's update of the protocol parameters (`PParamsUpdate era`), the one part that
  * differs between the eras. Each parent is of its action's purpose. Equal as the reference derives
  * `Eq`: treasury withdrawals as the `Map AccountAddress Coin` they are, an update by the typed
  * value of each field. The constitution is the node's [[Constitution]], the one the governance
  * state holds, whose guardrails script is its hash's bytes.
  */
enum GovActionRep[+U]:
  case ParameterChange(
      parent: Option[GovPurposeId[GovActionPurpose.PParamUpdate]],
      update: U,
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
  case InfoAction

/** A governance proposal (`data ProposalProcedure era`, `Procedures.hs:498-504`, cardano-ledger
  * f649f975) over `A`, the era's governance action, with the flat [[Proposal]] it was read as,
  * which the flat rules and their failures carry. Two proposals are equal when they mean the same,
  * as the reference's derived `Eq` compares them: by the deposit, the return account, the action
  * and the anchor, not the proposal read.
  */
final class ProposalProcedureRep[+A] private[era] (
    val pProcDeposit: Coin,
    val pProcReturnAddr: RewardAccount,
    val pProcGovAction: A,
    val pProcAnchor: Anchor,
    val read: Proposal
):
  private def fields: (Coin, RewardAccount, A, Anchor) =
    (pProcDeposit, pProcReturnAddr, pProcGovAction, pProcAnchor)

  override def equals(that: Any): Boolean = that match
    case o: ProposalProcedureRep[?] => fields == o.fields
    case _                          => false
  override def hashCode(): Int = fields.hashCode
  override def toString: String = s"ProposalProcedure$fields"
