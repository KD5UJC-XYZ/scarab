// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.rules

import cats.data.{NonEmptyList, NonEmptyMap, NonEmptySet}
import scala.collection.immutable.SortedSet
import xyz.kd5ujc.scarab.ledger.{
  Credential as FlatCredential,
  GovAction as FlatGovAction,
  GovActionId,
  GovFailure,
  Mismatch,
  PredicateFailure,
  RewardAccount,
  Voter
}
import xyz.kd5ujc.scarab.ledger.era.*
import xyz.kd5ujc.scarab.ledger.rules.Convert
import xyz.kd5ujc.scarab.ledger.state.{
  AccountAddress,
  ColdCommittee,
  Coin,
  Conway,
  Credential,
  DRepRole,
  EpochNo,
  EraTag,
  Hash32,
  ScriptHash
}

// The failures and events of Conway's GOV as the reference declares them (cardano-ledger f649f975
// `eras/conway/impl/src/Cardano/Ledger/Conway/Rules/Gov.hs`; at 29a7d656 the failure type also
// derives `Ord`, nothing else differs), with their injection into the rule that runs GOV.

/** `data ConwayGovPredFailure era` (`Conway/Rules/Gov.hs:177-218`, cardano-ledger f649f975), its
  * constructors in the order of their tags (0 to 18, `:237-255`). A voter and a voter's credential
  * are the era's voter ([[Voter]]) as the node reads it, until the votes carry the reference's
  * typed voter (#85). A mismatch is the node's `Mismatch`, without the reference's relation
  * (`RelEQ`, `RelGT`), which only names the comparison in the reference's rendering.
  */
enum ConwayGovPredFailure[E <: EraTag]:
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

/** An action the failure carries that is not one of the proposals it was given: the flat form holds
  * the action as read, which only the proposal keeps.
  */
final case class GovActionNotRead[E <: EraTag](action: GovAction[E])

object ConwayGovPredFailure:
  /** The flat action of each of `proposals`' actions, as read. */
  def readOf(
      proposals: Iterable[ProposalProcedure[Conway]]
  ): GovAction[Conway] => Option[FlatGovAction] =
    action => proposals.find(_.pProcGovAction == action).map(_.read.action)

  /** A failure in the node's flat form, glue the comparisons with the flat rule read, one-way. An
    * action is put in its flat form as `read` gives it: the action as read, which the flat failure
    * holds ([[readOf]] the transaction's proposals).
    */
  def toStored(
      failure: ConwayGovPredFailure[Conway],
      read: GovAction[Conway] => Option[FlatGovAction]
  ): Either[GovActionNotRead[Conway], PredicateFailure] =
    def gov(f: GovFailure): Either[GovActionNotRead[Conway], PredicateFailure] =
      Right(PredicateFailure.gov(f))
    def action(a: GovAction[Conway]): Either[GovActionNotRead[Conway], FlatGovAction] =
      read(a).toRight(GovActionNotRead(a))
    def version(v: ProtVer): (BigInt, BigInt) = (BigInt(v.pvMajor.value), v.pvMinor.value)
    failure match
      case GovActionsDoNotExist(ids) => gov(GovFailure.GovActionsDoNotExist(ids.toList.toVector))
      case MalformedProposal(a) =>
        action(a).map(f => PredicateFailure.gov(GovFailure.MalformedProposal(f)))
      case ProposalProcedureNetworkIdMismatch(account, network) =>
        gov(
          GovFailure.ProposalProcedureNetworkIdMismatch(
            Convert.rewardAccountBack(account),
            network.wire
          )
        )
      case TreasuryWithdrawalsNetworkIdMismatch(accounts, network) =>
        gov(
          GovFailure.TreasuryWithdrawalsNetworkIdMismatch(
            accounts.toSortedSet.map(Convert.rewardAccountBack),
            network.wire
          )
        )
      case ProposalDepositIncorrect(m) =>
        gov(GovFailure.ProposalDepositIncorrect(Mismatch(m.supplied.value, m.expected.value)))
      case DisallowedVoters(votes) => gov(GovFailure.DisallowedVoters(votes.toList.toVector))
      case ConflictingCommitteeUpdate(members) =>
        gov(GovFailure.ConflictingCommitteeUpdate(members.toSortedSet.map(Convert.back(_))))
      case ExpirationEpochTooSmall(members) =>
        gov(
          GovFailure.ExpirationEpochTooSmall(
            members.toSortedMap.map((c, e) => Convert.back(c) -> e.toBigInt).toMap
          )
        )
      case InvalidPrevGovActionId(p) => gov(GovFailure.InvalidPrevGovActionId(p.read))
      case VotingOnExpiredGovAction(votes) =>
        gov(GovFailure.VotingOnExpiredGovAction(votes.toList.toVector))
      case ProposalCantFollow(parent, m) =>
        gov(
          GovFailure.ProposalCantFollow(
            parent.map(_.unGovPurposeId),
            Mismatch(version(m.supplied), version(m.expected))
          )
        )
      case InvalidGuardrailsScriptHash(proposed, constitution) =>
        gov(
          GovFailure.InvalidGuardrailsScriptHash(proposed.map(_.bytes), constitution.map(_.bytes))
        )
      case DisallowedProposalDuringBootstrap(p) =>
        gov(GovFailure.DisallowedProposalDuringBootstrap(p.read))
      case DisallowedVotesDuringBootstrap(votes) =>
        gov(GovFailure.DisallowedVotesDuringBootstrap(votes.toList.toVector))
      case VotersDoNotExist(voters) => gov(GovFailure.VotersDoNotExist(voters.toList.toVector))
      case ZeroTreasuryWithdrawals(a) =>
        action(a).map(f => PredicateFailure.gov(GovFailure.ZeroTreasuryWithdrawals(f)))
      case ProposalReturnAccountDoesNotExist(account) =>
        gov(GovFailure.ProposalReturnAccountDoesNotExist(Convert.rewardAccountBack(account)))
      case TreasuryWithdrawalReturnAccountsDoNotExist(accounts) =>
        gov(
          GovFailure.TreasuryWithdrawalReturnAccountsDoNotExist(
            accounts.toList.toVector.map(Convert.rewardAccountBack)
          )
        )
      case UnelectedCommitteeVoters(credentials) =>
        gov(GovFailure.UnelectedCommitteeVoters(credentials.toList.toVector))

/** `data ConwayGovEvent era` (`Conway/Rules/Gov.hs:300-308`, cardano-ledger f649f975): the
  * proposals after the transaction, and the votes it replaced with the DReps whose votes it
  * removed, each by the transaction's id, the hash of its body.
  */
enum ConwayGovEvent[E <: EraTag]:
  case GovNewProposals(txId: Hash32, proposals: Proposals[E])
  case GovRemovedVotes(
      txId: Hash32,
      replacedVotes: SortedSet[(Voter, GovActionId)],
      unregisteredDReps: SortedSet[Credential[DRepRole]]
  )

/** `EraRuleFailure "GOV" era`: Conway's is its own (`Conway/Rules/Gov.hs:225`, cardano-ledger
  * f649f975). Each era adds its case with its rule.
  */
type GovPredFailure[E <: EraTag] = E match
  case Conway => ConwayGovPredFailure[Conway]

/** `EraRuleEvent "GOV" era`: Conway's is its own (`Conway/Rules/Gov.hs:227`, cardano-ledger
  * f649f975).
  */
type GovEvent[E <: EraTag] = E match
  case Conway => ConwayGovEvent[Conway]

/** `InjectRuleFailure "GOV" ConwayGovPredFailure era` and `InjectRuleEvent "GOV" ConwayGovEvent
  * era` (`libs/cardano-ledger-core/src/Cardano/Ledger/Core/Era.hs:120-134`, cardano-ledger
  * f649f975): how era `E` 's GOV reports the failures and events of Conway's transition. Only this
  * package makes one.
  */
sealed trait InjectGov[E <: EraTag]:
  def injectFailure(failure: ConwayGovPredFailure[E]): GovPredFailure[E]
  def injectEvent(event: ConwayGovEvent[E]): GovEvent[E]

object InjectGov:
  /** `Conway/Rules/Gov.hs:229-231`, cardano-ledger f649f975: the identities. */
  given conway: InjectGov[Conway] with
    def injectFailure(failure: ConwayGovPredFailure[Conway]): GovPredFailure[Conway] = failure
    def injectEvent(event: ConwayGovEvent[Conway]): GovEvent[Conway] = event

/** The order of the sets and maps GOV's failures and events hold: the reference's derived ones. */
private[rules] given voterVoteOrdering: Ordering[(Voter, GovActionId)] =
  Ordering.Tuple2(using Voter.ordering, GovActionId.ordering)
private[rules] given coldCommitteeOrdering: Ordering[Credential[ColdCommittee]] =
  cats.Order[Credential[ColdCommittee]].toOrdering
given drepCredentialOrdering: Ordering[Credential[DRepRole]] =
  cats.Order[Credential[DRepRole]].toOrdering

/** The derived `Ord AccountAddress` (`Address.hs:183-187`, cardano-ledger f649f975): the network,
  * then the credential, the order of the node's reward account it is.
  */
private[rules] given accountAddressOrdering: Ordering[AccountAddress] =
  Ordering.by[AccountAddress, RewardAccount](Convert.rewardAccountBack)
private[rules] given accountAddressOrder: cats.Order[AccountAddress] =
  cats.Order.fromOrdering(using accountAddressOrdering)
