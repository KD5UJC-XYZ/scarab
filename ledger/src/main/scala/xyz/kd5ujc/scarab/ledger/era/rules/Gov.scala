// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.rules

import scala.collection.immutable.SortedSet
import xyz.kd5ujc.scarab.ledger.{
  Committee,
  Credential as FlatCredential,
  GovAction as FlatGovAction,
  GovFailure,
  PredicateFailure,
  Proposal,
  Voter
}
import xyz.kd5ujc.scarab.ledger.era.*
import xyz.kd5ujc.scarab.ledger.rules.{Convert, Test}
import xyz.kd5ujc.scarab.ledger.state.{
  CommitteeAuthorization,
  CommitteeState,
  Credential,
  EraTag,
  HotCommittee,
  ScriptHash
}

// The named functions of Conway's GOV rule (cardano-ledger f649f975
// `eras/conway/impl/src/Cardano/Ledger/Conway/Rules/Gov.hs`) and the helpers they call, each
// generic in its era over the reference's classes. A predicate reports the failures of the node's
// `GovFailure`, so its verdict is the one the flat rule gives. The checks the reference writes inline
// in `conwayGovTransition` come with the transition.

private def govFailure(f: GovFailure): PredicateFailure = PredicateFailure.gov(f)

/** Conway's protocol-version gates (`eras/conway/impl/src/Cardano/Ledger/Conway/Era.hs`,
  * cardano-ledger f649f975).
  */
object ConwayEra:
  /** `hardforkConwayBootstrapPhase` (`Conway/Era.hs:257-258`, cardano-ledger f649f975): version 9.
    */
  def hardforkConwayBootstrapPhase(pv: ProtVer): Boolean = pv.pvMajor.value == 9

  /** `hardforkConwayDisallowUnelectedCommitteeFromVoting` (`Conway/Era.hs:262-263`, cardano-ledger
    * f649f975): from version 11.
    */
  def hardforkConwayDisallowUnelectedCommitteeFromVoting(pv: ProtVer): Boolean =
    pv.pvMajor.value > 10

/** The committee's hot credentials (cardano-ledger f649f975
  * `libs/cardano-ledger-core/src/Cardano/Ledger/State/CertState.hs`,
  * `eras/conway/impl/src/Cardano/Ledger/Conway/Governance.hs`).
  */
object ConwayCommittee:
  /** `authorizedHotCommitteeCredentials` (`State/CertState.hs:338-343`, cardano-ledger f649f975):
    * the hot credentials the committee state has authorised, a resigned member's none.
    */
  def authorizedHotCommitteeCredentials(
      committeeState: CommitteeState
  ): Set[Credential[HotCommittee]] =
    committeeState.creds.valuesIterator.collect {
      case CommitteeAuthorization.CommitteeHotCredential(hot) => hot
    }.toSet

  /** `authorizedElectedHotCommitteeCredentials` (`Conway/Governance.hs:581-591`, cardano-ledger
    * f649f975): the hot credentials authorised by the members of the elected committee, none
    * without one.
    */
  def authorizedElectedHotCommitteeCredentials(
      committee: Option[Committee],
      committeeState: CommitteeState
  ): Set[Credential[HotCommittee]] =
    committee.fold(Set.empty[Credential[HotCommittee]])(elected =>
      authorizedHotCommitteeCredentials(
        CommitteeState(
          committeeState.creds.filter((cold, _) => elected.members.contains(Convert.back(cold)))
        )
      )
    )

/** Conway's GOV rule's named functions (`Conway/Rules/Gov.hs`, cardano-ledger f649f975). */
object ConwayGov:
  /** `isBootstrapAction` (`Conway/Rules/Gov.hs:633-639`, cardano-ledger f649f975): a parameter
    * change, a hard fork or an info action.
    */
  def isBootstrapAction[E <: EraTag](ga: GovAction[E]): Boolean = ga match
    case GovAction.ParameterChange(_, _, _) | GovAction.HardForkInitiation(_, _) |
        GovAction.InfoAction() =>
      true
    case GovAction.TreasuryWithdrawals(_, _) | GovAction.NoConfidence(_) |
        GovAction.UpdateCommittee(_, _, _, _) | GovAction.NewConstitution(_, _) =>
      false

  /** `checkBootstrapProposal` (`Conway/Rules/Gov.hs:435-443`, cardano-ledger f649f975): in the
    * bootstrap phase, only a bootstrap action may be proposed. The failure carries the proposal as
    * read.
    */
  def checkBootstrapProposal[E <: EraTag](pp: PParams[E], proposal: ProposalProcedure[E])(using
      p: EraPParams[E],
      read: ProposalRead[E] =:= Proposal
  ): Test[PredicateFailure] =
    if ConwayEra.hardforkConwayBootstrapPhase(p.ppProtocolVersionL.get(pp)) then
      Test.failureUnless(
        isBootstrapAction(proposal.pProcGovAction),
        govFailure(GovFailure.DisallowedProposalDuringBootstrap(read(proposal.read)))
      )
    else Test.pass

  /** `actionWellFormed` (`Conway/Rules/Gov.hs:393-399`, cardano-ledger f649f975): a parameter
    * change's update is well formed at `pv`. The node's failure carries the action as read (`read`,
    * the flat action of the proposal `ga` is of), until each era's failures are named.
    */
  def actionWellFormed[E <: EraTag](pv: ProtVer, ga: GovAction[E])(read: => FlatGovAction)(using
      p: ConwayEraPParams[E]
  ): Test[PredicateFailure] =
    val isWellFormed = ga match
      case GovAction.ParameterChange(_, ppd, _) => p.ppuWellFormed(pv, ppd)
      case GovAction.HardForkInitiation(_, _) | GovAction.TreasuryWithdrawals(_, _) |
          GovAction.NoConfidence(_) | GovAction.UpdateCommittee(_, _, _, _) |
          GovAction.NewConstitution(_, _) | GovAction.InfoAction() =>
        true
    Test.failureUnless(isWellFormed, govFailure(GovFailure.MalformedProposal(read)))

  /** `checkGuardrailsScriptHash` (`Conway/Rules/Gov.hs:420-426`, cardano-ledger f649f975): the
    * guardrails script a proposal names is the constitution's.
    */
  def checkGuardrailsScriptHash(
      expectedHash: Option[ScriptHash],
      actualHash: Option[ScriptHash]
  ): Test[PredicateFailure] =
    Test.failureUnless(
      actualHash == expectedHash,
      govFailure(
        GovFailure.InvalidGuardrailsScriptHash(actualHash.map(_.bytes), expectedHash.map(_.bytes))
      )
    )

  /** `unelectedCommitteeVoters` (`Conway/Rules/Gov.hs:652-665`, cardano-ledger f649f975): the hot
    * credentials among the voters that no member of the elected committee has authorised, in the
    * reference's `Ord (Credential HotCommitteeRole)`. A voter's credential is the node's flat one
    * until the votes carry the reference's typed voter (tracked on #85), so it is compared with the
    * authorised credentials as flat ones.
    */
  def unelectedCommitteeVoters[E <: EraTag](
      committee: Option[Committee],
      committeeState: CommitteeState,
      votes: VotingProcedures[E]
  ): SortedSet[FlatCredential] =
    val authorizedElectedCommittee: Set[FlatCredential] =
      ConwayCommittee
        .authorizedElectedHotCommitteeCredentials(committee, committeeState)
        .map(Convert.back(_))
    SortedSet.from(votes.unVotingProcedures.keysIterator.collect {
      case Voter.CommitteeHot(hotCred) if !authorizedElectedCommittee.contains(hotCred) => hotCred
    })
