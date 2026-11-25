// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.rules

import cats.data.{NonEmptyList, NonEmptyMap, NonEmptySet}
import xyz.kd5ujc.scarab.ledger.{Credential as FlatCredential, GovActionId, Voter}
import xyz.kd5ujc.scarab.ledger.era.*
import xyz.kd5ujc.scarab.ledger.era.Relation.*
import xyz.kd5ujc.scarab.ledger.state.{
  AccountAddress,
  ColdCommittee,
  Coin,
  Credential,
  EpochNo,
  EraTag,
  ScriptHash
}

// The failures of Dijkstra's GOV as the reference declares them (cardano-ledger 9df9f98c
// `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Rules/Gov.hs`), with the injection of Conway's.
// Dijkstra's GOV is Conway's transition (`transitionRules = [Conway.conwayGovTransition]`,
// `Gov.hs:220`) and tells Conway's events (`Gov.hs:123`); their cases at Dijkstra are in
// `GovFailures.scala`.

/** `data DijkstraGovPredFailure era` (`Dijkstra/Rules/Gov.hs:73-114`, cardano-ledger 9df9f98c), its
  * constructors in the order of their tags (0 to 18, `:157-197`): Conway's, of the same names and
  * fields, which Dijkstra's encoder writes with a `Mismatch` as one item (`To mm`, `:168-169,
  * 180-181`). The fields are those of [[ConwayGovPredFailure]]: a voter and a voter's credential
  * the era's voter as the node reads it, until the votes carry the reference's typed voter (#85),
  * and a mismatch with the reference's relation ([[Mismatch]]: `RelEQ`, `RelGT`).
  */
enum DijkstraGovPredFailure[E <: EraTag]:
  case GovActionsDoNotExist(ids: NonEmptyList[GovActionId])
  case MalformedProposal(action: GovAction[E])
  case ProposalProcedureNetworkIdMismatch(account: AccountAddress, network: Network)
  case TreasuryWithdrawalsNetworkIdMismatch(accounts: NonEmptySet[AccountAddress], network: Network)

  /** The deposit (supplied) and the parameters' (expected). */
  case ProposalDepositIncorrect(mismatch: Mismatch[RelEQ, Coin])

  /** The votes on actions their voters may not vote on. */
  case DisallowedVoters(votes: NonEmptyList[(Voter, GovActionId)])

  /** The members both removed and added. */
  case ConflictingCommitteeUpdate(members: NonEmptySet[Credential[ColdCommittee]])

  /** The members added whose term ends no later than the current epoch. */
  case ExpirationEpochTooSmall(members: NonEmptyMap[Credential[ColdCommittee], EpochNo])
  case InvalidPrevGovActionId(proposal: ProposalProcedure[E])
  case VotingOnExpiredGovAction(votes: NonEmptyList[(Voter, GovActionId)])

  /** The parent of the hard fork that cannot follow, and its version (supplied) with the version it
    * must follow (expected).
    */
  case ProposalCantFollow(
      parent: Option[GovPurposeId[GovActionPurpose.HardFork]],
      mismatch: Mismatch[RelGT, ProtVer]
  )

  /** The guardrails script hash the proposal names, and the constitution's. */
  case InvalidGuardrailsScriptHash(proposed: Option[ScriptHash], constitution: Option[ScriptHash])
  case DisallowedProposalDuringBootstrap(proposal: ProposalProcedure[E])
  case DisallowedVotesDuringBootstrap(votes: NonEmptyList[(Voter, GovActionId)])
  case VotersDoNotExist(voters: NonEmptyList[Voter])
  case ZeroTreasuryWithdrawals(action: GovAction[E])
  case ProposalReturnAccountDoesNotExist(account: AccountAddress)
  case TreasuryWithdrawalReturnAccountsDoNotExist(accounts: NonEmptyList[AccountAddress])
  case UnelectedCommitteeVoters(credentials: NonEmptyList[FlatCredential])

object DijkstraGovPredFailure:
  given [E <: EraTag]: CanEqual[DijkstraGovPredFailure[E], DijkstraGovPredFailure[E]] =
    CanEqual.derived

  /** `conwayToDijkstraGovPredFailure` (`Dijkstra/Rules/Gov.hs:222-243`, cardano-ledger 9df9f98c):
    * each constructor as Dijkstra's of the same name and fields. Total, as the reference's is.
    */
  def conwayToDijkstraGovPredFailure[E <: EraTag](
      failure: ConwayGovPredFailure[E]
  ): DijkstraGovPredFailure[E] = failure match
    case ConwayGovPredFailure.GovActionsDoNotExist(ids) => GovActionsDoNotExist(ids)
    case ConwayGovPredFailure.MalformedProposal(a)      => MalformedProposal(a)
    case ConwayGovPredFailure.ProposalProcedureNetworkIdMismatch(a, n) =>
      ProposalProcedureNetworkIdMismatch(a, n)
    case ConwayGovPredFailure.TreasuryWithdrawalsNetworkIdMismatch(as, n) =>
      TreasuryWithdrawalsNetworkIdMismatch(as, n)
    case ConwayGovPredFailure.ProposalDepositIncorrect(m)   => ProposalDepositIncorrect(m)
    case ConwayGovPredFailure.DisallowedVoters(vs)          => DisallowedVoters(vs)
    case ConwayGovPredFailure.ConflictingCommitteeUpdate(c) => ConflictingCommitteeUpdate(c)
    case ConwayGovPredFailure.ExpirationEpochTooSmall(c)    => ExpirationEpochTooSmall(c)
    case ConwayGovPredFailure.InvalidPrevGovActionId(p)     => InvalidPrevGovActionId(p)
    case ConwayGovPredFailure.VotingOnExpiredGovAction(vs)  => VotingOnExpiredGovAction(vs)
    case ConwayGovPredFailure.ProposalCantFollow(p, m)      => ProposalCantFollow(p, m)
    case ConwayGovPredFailure.InvalidGuardrailsScriptHash(a, b) =>
      InvalidGuardrailsScriptHash(a, b)
    case ConwayGovPredFailure.DisallowedProposalDuringBootstrap(p) =>
      DisallowedProposalDuringBootstrap(p)
    case ConwayGovPredFailure.DisallowedVotesDuringBootstrap(vs) =>
      DisallowedVotesDuringBootstrap(vs)
    case ConwayGovPredFailure.VotersDoNotExist(vs)       => VotersDoNotExist(vs)
    case ConwayGovPredFailure.ZeroTreasuryWithdrawals(a) => ZeroTreasuryWithdrawals(a)
    case ConwayGovPredFailure.ProposalReturnAccountDoesNotExist(a) =>
      ProposalReturnAccountDoesNotExist(a)
    case ConwayGovPredFailure.TreasuryWithdrawalReturnAccountsDoNotExist(as) =>
      TreasuryWithdrawalReturnAccountsDoNotExist(as)
    case ConwayGovPredFailure.UnelectedCommitteeVoters(vs) => UnelectedCommitteeVoters(vs)
