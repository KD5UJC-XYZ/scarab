// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.rules

import cats.data.{NonEmptyList, NonEmptyMap, NonEmptySet}
import xyz.kd5ujc.scarab.ledger.{
  Credential as FlatCredential,
  GovAction as FlatGovAction,
  GovActionId,
  GovFailure,
  Mismatch,
  Proposal,
  Voter
}
import xyz.kd5ujc.scarab.ledger.era.*
import xyz.kd5ujc.scarab.ledger.rules.{Convert, DijkstraConway}
import xyz.kd5ujc.scarab.ledger.state.{
  AccountAddress,
  ColdCommittee,
  Coin,
  Credential,
  Dijkstra,
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
  * and a mismatch the node's `Mismatch`, without the reference's relation (`RelEQ`, `RelGT`).
  */
enum DijkstraGovPredFailure[E <: EraTag]:
  case GovActionsDoNotExist(ids: NonEmptyList[GovActionId])
  case MalformedProposal(action: GovAction[E])
  case ProposalProcedureNetworkIdMismatch(account: AccountAddress, network: Network)
  case TreasuryWithdrawalsNetworkIdMismatch(accounts: NonEmptySet[AccountAddress], network: Network)

  /** The deposit (supplied) and the parameters' (expected). */
  case ProposalDepositIncorrect(mismatch: Mismatch[Coin])

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
      mismatch: Mismatch[ProtVer]
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

  /** The flat Conway action of each of `proposals` ' actions, as the flat Dijkstra rule reads it
    * (`DijkstraConway.action`): none for a parameter change, which the flat action cannot hold.
    */
  def readOf(
      proposals: Iterable[ProposalProcedure[Dijkstra]]
  ): GovAction[Dijkstra] => Option[FlatGovAction] =
    action =>
      proposals
        .find(_.pProcGovAction == action)
        .flatMap(p => DijkstraConway.action(p.read.action).toOption)

  /** A failure in the flat form the flat Dijkstra GOV gives, Conway's ([[GovFailure]]), glue the
    * comparisons read, one-way. An action is put in its flat form as `read` gives it ([[readOf]]
    * the transaction's proposals), and a proposal as the flat rule reads the one it was read as; an
    * action neither reaches, a parameter change among them, is [[GovActionNotRead]].
    */
  def toStored(
      failure: DijkstraGovPredFailure[Dijkstra],
      read: GovAction[Dijkstra] => Option[FlatGovAction]
  ): Either[GovActionNotRead[Dijkstra], GovFailure] =
    def action(a: GovAction[Dijkstra]): Either[GovActionNotRead[Dijkstra], FlatGovAction] =
      read(a).toRight(GovActionNotRead(a))
    def proposal(p: ProposalProcedure[Dijkstra]): Either[GovActionNotRead[Dijkstra], Proposal] =
      DijkstraConway.proposal(p.read).left.map(_ => GovActionNotRead(p.pProcGovAction))
    def version(v: ProtVer): (BigInt, BigInt) = (BigInt(v.pvMajor.value), v.pvMinor.value)
    failure match
      case GovActionsDoNotExist(ids) => Right(GovFailure.GovActionsDoNotExist(ids.toList.toVector))
      case MalformedProposal(a)      => action(a).map(GovFailure.MalformedProposal(_))
      case ProposalProcedureNetworkIdMismatch(account, network) =>
        Right(
          GovFailure.ProposalProcedureNetworkIdMismatch(
            Convert.rewardAccountBack(account),
            network.wire
          )
        )
      case TreasuryWithdrawalsNetworkIdMismatch(accounts, network) =>
        Right(
          GovFailure.TreasuryWithdrawalsNetworkIdMismatch(
            accounts.toSortedSet.map(Convert.rewardAccountBack),
            network.wire
          )
        )
      case ProposalDepositIncorrect(m) =>
        Right(GovFailure.ProposalDepositIncorrect(Mismatch(m.supplied.value, m.expected.value)))
      case DisallowedVoters(votes) => Right(GovFailure.DisallowedVoters(votes.toList.toVector))
      case ConflictingCommitteeUpdate(members) =>
        Right(GovFailure.ConflictingCommitteeUpdate(members.toSortedSet.map(Convert.back(_))))
      case ExpirationEpochTooSmall(members) =>
        Right(
          GovFailure.ExpirationEpochTooSmall(
            members.toSortedMap.map((c, e) => Convert.back(c) -> e.toBigInt).toMap
          )
        )
      case InvalidPrevGovActionId(p) => proposal(p).map(GovFailure.InvalidPrevGovActionId(_))
      case VotingOnExpiredGovAction(votes) =>
        Right(GovFailure.VotingOnExpiredGovAction(votes.toList.toVector))
      case ProposalCantFollow(parent, m) =>
        Right(
          GovFailure.ProposalCantFollow(
            parent.map(_.unGovPurposeId),
            Mismatch(version(m.supplied), version(m.expected))
          )
        )
      case InvalidGuardrailsScriptHash(proposed, constitution) =>
        Right(
          GovFailure.InvalidGuardrailsScriptHash(proposed.map(_.bytes), constitution.map(_.bytes))
        )
      case DisallowedProposalDuringBootstrap(p) =>
        proposal(p).map(GovFailure.DisallowedProposalDuringBootstrap(_))
      case DisallowedVotesDuringBootstrap(votes) =>
        Right(GovFailure.DisallowedVotesDuringBootstrap(votes.toList.toVector))
      case VotersDoNotExist(voters)   => Right(GovFailure.VotersDoNotExist(voters.toList.toVector))
      case ZeroTreasuryWithdrawals(a) => action(a).map(GovFailure.ZeroTreasuryWithdrawals(_))
      case ProposalReturnAccountDoesNotExist(account) =>
        Right(GovFailure.ProposalReturnAccountDoesNotExist(Convert.rewardAccountBack(account)))
      case TreasuryWithdrawalReturnAccountsDoNotExist(accounts) =>
        Right(
          GovFailure.TreasuryWithdrawalReturnAccountsDoNotExist(
            accounts.toList.toVector.map(Convert.rewardAccountBack)
          )
        )
      case UnelectedCommitteeVoters(credentials) =>
        Right(GovFailure.UnelectedCommitteeVoters(credentials.toList.toVector))
