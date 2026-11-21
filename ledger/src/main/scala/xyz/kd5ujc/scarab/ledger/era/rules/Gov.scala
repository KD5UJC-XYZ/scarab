// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.rules

import cats.Monad
import cats.data.{Chain, NonEmptyList, NonEmptyMap, NonEmptySet}
import cats.mtl.{Raise, Stateful, Tell}
import cats.syntax.all.*
import scala.collection.immutable.{SortedMap, SortedSet}
import xyz.kd5ujc.scarab.ledger.{
  Committee,
  Credential as FlatCredential,
  GovActionId,
  Mismatch,
  StakePoolGroup,
  Unsigned,
  Voter
}
import xyz.kd5ujc.scarab.ledger.era.*
import xyz.kd5ujc.scarab.ledger.rules.{Convert, Judge, Sts, Test}
import xyz.kd5ujc.scarab.ledger.state.{
  AccountAddress,
  CertState,
  Coin,
  CommitteeAuthorization,
  CommitteeState,
  Credential,
  DRepRole,
  EpochInterval,
  EpochNo,
  EraTag,
  Hash32,
  HotCommittee,
  KeyHash,
  ScriptHash,
  StakePool,
  VocabError,
  addInterval
}

// Conway's GOV rule (cardano-ledger f649f975 `eras/conway/impl/src/Cardano/Ledger/Conway/Rules/Gov.hs`,
// the same at 29a7d656 but for the failure type's derived `Ord`) and the functions it calls, each
// generic in its era over the reference's classes, with the reference's name. A predicate reports
// Conway's failure type (`GovFailures.scala`), and the transition injects it into the era's.

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

/** Who may vote on an action (`isCommitteeVotingAllowed`, `isDRepVotingAllowed` and
  * `isStakePoolVotingAllowed`, `eras/conway/impl/src/Cardano/Ledger/Conway/Governance/Internal.hs:
  * 350-526`, cardano-ledger f649f975): `isVotingAllowed` of the voter's threshold, which is
  * `NoVotingAllowed` only for the actions below. The thresholds' values are ratification's and
  * never reach `isVotingAllowed`, and with no committee (`committee = SNothing`) the committee's
  * threshold of the other actions is `NoVotingThreshold`, so the current epoch and the committee
  * state, which the reference passes, decide nothing here.
  */
object ConwayVoting:
  /** `isCommitteeVotingAllowed` (`Internal.hs:408-424, 444-479`, cardano-ledger f649f975): not on a
    * motion of no confidence or a committee update.
    */
  def isCommitteeVotingAllowed[E <: EraTag](action: GovAction[E]): Boolean = action match
    case GovAction.NoConfidence(_) | GovAction.UpdateCommittee(_, _, _, _) => false
    case GovAction.NewConstitution(_, _) | GovAction.HardForkInitiation(_, _) |
        GovAction.ParameterChange(_, _, _) | GovAction.TreasuryWithdrawals(_, _) |
        GovAction.InfoAction() =>
      true

  /** `isDRepVotingAllowed` (`Internal.hs:481-490, 504-532`, cardano-ledger f649f975): on every
    * action.
    */
  def isDRepVotingAllowed[E <: EraTag](action: GovAction[E]): Boolean = action match
    case GovAction.NoConfidence(_) | GovAction.UpdateCommittee(_, _, _, _) |
        GovAction.NewConstitution(_, _) | GovAction.HardForkInitiation(_, _) |
        GovAction.ParameterChange(_, _, _) | GovAction.TreasuryWithdrawals(_, _) |
        GovAction.InfoAction() =>
      true

  /** `isStakePoolVotingAllowed` (`Internal.hs:350-406`, cardano-ledger f649f975): not on a new
    * constitution or a treasury withdrawal, and on a parameter change only if it changes a
    * parameter of the security group.
    */
  def isStakePoolVotingAllowed[E <: EraTag](action: GovAction[E])(using
      p: ConwayEraPParams[E]
  ): Boolean = action match
    case GovAction.NewConstitution(_, _) | GovAction.TreasuryWithdrawals(_, _) => false
    case GovAction.ParameterChange(_, ppu, _) =>
      p.modifiedPPGroups(ppu).exists(_.pool == StakePoolGroup.Security)
    case GovAction.NoConfidence(_) | GovAction.UpdateCommittee(_, _, _, _) |
        GovAction.HardForkInitiation(_, _) | GovAction.InfoAction() =>
      true

/** `data GovEnv era` (`Conway/Rules/Gov.hs:149-157`, cardano-ledger f649f975): the transaction's id
  * (the hash of its body), the current epoch, the parameters, the constitution's guardrails script
  * hash, the certificate state after the transaction's certificates and the elected committee, the
  * node's record of it until the governance state's era types hold the reference's `Committee era`
  * (#85).
  */
final case class GovEnv[E <: EraTag](
    geTxId: Hash32,
    geEpoch: EpochNo,
    gePParams: PParams[E],
    geGuardrailsScriptHash: Option[ScriptHash],
    geCertState: CertState[E],
    geCommittee: Option[Committee]
)

/** `data GovSignal era` (`Conway/Rules/Gov.hs:312-317`, cardano-ledger f649f975): the votes, the
  * proposals and the certificates of a transaction.
  */
final case class GovSignal[E <: EraTag](
    gsVotingProcedures: VotingProcedures[E],
    gsProposalProcedures: OSet[ProposalProcedure[E]],
    gsCertificates: Vector[TxCert[E]]
)

/** Why GOV reached no verdict: what the reference has no counterpart for, or meets with an
  * exception. Neither is a verdict of the rule.
  */
enum GovDefect:
  /** A voter's credential or key hash, or an account, outside the state's vocabulary, which only
    * the node's flat voter and reward account can hold.
    */
  case Vocab(error: VocabError)

  /** More proposals than a `GovActionIx` (`Word16`) numbers: `indexedGovProps` ' `succ` of
    * `maxBound` (`Governance/Procedures.hs:490-496`, cardano-ledger f649f975) throws.
    */
  case ProposalIndexOverflow(proposals: Int)

/** Conway's GOV rule's functions and its transition (`Conway/Rules/Gov.hs`, cardano-ledger
  * f649f975).
  */
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
    * bootstrap phase, only a bootstrap action may be proposed.
    */
  def checkBootstrapProposal[E <: EraTag](pp: PParams[E], proposal: ProposalProcedure[E])(using
      p: EraPParams[E]
  ): Test[ConwayGovPredFailure[E]] =
    if ConwayEra.hardforkConwayBootstrapPhase(p.ppProtocolVersionL.get(pp)) then
      Test.failureUnless(
        isBootstrapAction(proposal.pProcGovAction),
        ConwayGovPredFailure.DisallowedProposalDuringBootstrap(proposal)
      )
    else Test.pass

  /** `actionWellFormed` (`Conway/Rules/Gov.hs:393-399`, cardano-ledger f649f975): a parameter
    * change's update is well formed at `pv`.
    */
  def actionWellFormed[E <: EraTag](pv: ProtVer, ga: GovAction[E])(using
      p: ConwayEraPParams[E]
  ): Test[ConwayGovPredFailure[E]] =
    val isWellFormed = ga match
      case GovAction.ParameterChange(_, ppd, _) => p.ppuWellFormed(pv, ppd)
      case GovAction.HardForkInitiation(_, _) | GovAction.TreasuryWithdrawals(_, _) |
          GovAction.NoConfidence(_) | GovAction.UpdateCommittee(_, _, _, _) |
          GovAction.NewConstitution(_, _) | GovAction.InfoAction() =>
        true
    Test.failureUnless(isWellFormed, ConwayGovPredFailure.MalformedProposal(ga))

  /** `checkGuardrailsScriptHash` (`Conway/Rules/Gov.hs:420-426`, cardano-ledger f649f975): the
    * guardrails script a proposal names is the constitution's.
    */
  def checkGuardrailsScriptHash[E <: EraTag](
      expectedHash: Option[ScriptHash],
      actualHash: Option[ScriptHash]
  ): Test[ConwayGovPredFailure[E]] =
    Test.failureUnless(
      actualHash == expectedHash,
      ConwayGovPredFailure.InvalidGuardrailsScriptHash(actualHash, expectedHash)
    )

  /** `mkGovActionState` (`Conway/Rules/Gov.hs:401-418`, cardano-ledger f649f975): a new action with
    * no votes, which expires `expiryInterval` epochs after `curEpoch`, a `Word64` sum that wraps
    * (`addEpochInterval`).
    */
  def mkGovActionState[E <: EraTag](
      actionId: GovActionId,
      proposal: ProposalProcedure[E],
      expiryInterval: EpochInterval,
      curEpoch: EpochNo
  ): GovActionState[E] =
    GovActionState[E](
      actionId,
      Map.empty,
      Map.empty,
      Map.empty,
      proposal,
      curEpoch,
      curEpoch.addInterval(expiryInterval)
    )

  /** `checkDisallowedVotes` (`Conway/Rules/Gov.hs:641-650`, cardano-ledger f649f975): the votes
    * whose action cannot be voted on by their voter, in the order of `votes`.
    */
  def checkDisallowedVotes[E <: EraTag](
      votes: Vector[(Voter, GovActionState[E])],
      failure: NonEmptyList[(Voter, GovActionId)] => ConwayGovPredFailure[E]
  )(canBeVotedOnBy: (GovActionState[E], Voter) => Boolean): Test[ConwayGovPredFailure[E]] =
    val disallowedVotes = votes.collect {
      case (voter, gas) if !canBeVotedOnBy(gas, voter) => (voter, gas.gasId)
    }
    Test.failureOnJust(NonEmptyList.fromList(disallowedVotes.toList))(failure)

  /** `checkVotesAreNotForExpiredActions` (`Conway/Rules/Gov.hs:356-362`, cardano-ledger f649f975).
    */
  def checkVotesAreNotForExpiredActions[E <: EraTag](
      curEpoch: EpochNo,
      votes: Vector[(Voter, GovActionState[E])]
  ): Test[ConwayGovPredFailure[E]] =
    checkDisallowedVotes(votes, ConwayGovPredFailure.VotingOnExpiredGovAction(_))((gas, _) =>
      cats.Order[EpochNo].lteqv(curEpoch, gas.gasExpiresAfter)
    )

  /** `checkVotersAreValid` (`Conway/Rules/Gov.hs:364-376`, cardano-ledger f649f975), its current
    * epoch and committee state left out ([[ConwayVoting]]).
    */
  def checkVotersAreValid[E <: EraTag](votes: Vector[(Voter, GovActionState[E])])(using
      ConwayEraPParams[E]
  ): Test[ConwayGovPredFailure[E]] =
    checkDisallowedVotes(votes, ConwayGovPredFailure.DisallowedVoters(_)) { (gas, voter) =>
      voter match
        case Voter.CommitteeHot(_) => ConwayVoting.isCommitteeVotingAllowed(gas.gasAction)
        case Voter.DRepVoter(_)    => ConwayVoting.isDRepVotingAllowed(gas.gasAction)
        case Voter.Pool(_)         => ConwayVoting.isStakePoolVotingAllowed(gas.gasAction)
    }

  /** `checkBootstrapVotes` (`Conway/Rules/Gov.hs:378-391`, cardano-ledger f649f975): in the
    * bootstrap phase a DRep votes only on an info action, the others only on a bootstrap action.
    */
  def checkBootstrapVotes[E <: EraTag](pp: PParams[E], votes: Vector[(Voter, GovActionState[E])])(
      using p: EraPParams[E]
  ): Test[ConwayGovPredFailure[E]] =
    if ConwayEra.hardforkConwayBootstrapPhase(p.ppProtocolVersionL.get(pp)) then
      checkDisallowedVotes(votes, ConwayGovPredFailure.DisallowedVotesDuringBootstrap(_)) {
        (gas, voter) =>
          voter match
            case Voter.DRepVoter(_) =>
              gas.gasAction match
                case GovAction.InfoAction() => true
                case GovAction.ParameterChange(_, _, _) | GovAction.HardForkInitiation(_, _) |
                    GovAction.TreasuryWithdrawals(_, _) | GovAction.NoConfidence(_) |
                    GovAction.UpdateCommittee(_, _, _, _) | GovAction.NewConstitution(_, _) =>
                  false
            case Voter.CommitteeHot(_) | Voter.Pool(_) => isBootstrapAction(gas.gasAction)
      }
    else Test.pass

  /** `unelectedCommitteeVoters` (`Conway/Rules/Gov.hs:652-665`, cardano-ledger f649f975): the hot
    * credentials among the voters that no member of the elected committee has authorised, in the
    * reference's `Ord (Credential HotCommitteeRole)`. A voter's credential is the node's flat one
    * until the votes carry the reference's typed voter (#85), so it is compared with the authorised
    * credentials as flat ones.
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

  /** `succVersion` (`libs/cardano-ledger-binary/src/Cardano/Ledger/Binary/Version.hs`,
    * cardano-ledger f649f975): the next version, none past `MaxVersion`.
    */
  private def succVersion(v: Version): Option[Version] = Version.mkVersion(BigInt(v.value) + 1)

  /** `pvCanFollow` (`eras/shelley/impl/src/Cardano/Ledger/Shelley/PParams.hs:297-307`,
    * cardano-ledger f649f975): the next major version at minor 0, or the same major version at the
    * next minor, a `Word32` sum that wraps.
    */
  def pvCanFollow(prev: ProtVer, next: ProtVer): Boolean =
    (succVersion(prev.pvMajor), BigInt(0)) == (Some(next.pvMajor), next.pvMinor.value) ||
      (prev.pvMajor, Unsigned.wrapping[Unsigned.Width.W32](prev.pvMinor.value + 1).value) ==
      (next.pvMajor, next.pvMinor.value)

  /** `preceedingHardFork` (`Conway/Rules/Gov.hs:673-695`, cardano-ledger f649f975): for a hard
    * fork, its parent, its version and the version it must follow: the parameters' when its parent
    * is the root or its major version is above the next (`Just major > succVersion`, true when
    * there is no next), otherwise its parent's, if its parent is a stored hard fork.
    */
  def preceedingHardFork[E <: EraTag](
      pp: PParams[E],
      pgaids: GovRelation[Option],
      ps: Proposals[E],
      action: GovAction[E]
  )(using
      p: EraPParams[E]
  ): Option[(Option[GovPurposeId[GovActionPurpose.HardFork]], ProtVer, ProtVer)] =
    action match
      case GovAction.HardForkInitiation(mPrev, newProtVer) =>
        val current = p.ppProtocolVersionL.get(pp)
        val tooHigh =
          succVersion(current.pvMajor).forall(next => newProtVer.pvMajor.value > next.value)
        if mPrev == pgaids.grHardFork || tooHigh then Some((mPrev, newProtVer, current))
        else
          for
            prev <- mPrev
            gas <- Proposals.proposalsLookupId(prev.unGovPurposeId, ps)
            prevProtVer <- gas.gasAction match
              case GovAction.HardForkInitiation(_, v) => Some(v)
              case GovAction.ParameterChange(_, _, _) | GovAction.TreasuryWithdrawals(_, _) |
                  GovAction.NoConfidence(_) | GovAction.UpdateCommittee(_, _, _, _) |
                  GovAction.NewConstitution(_, _) | GovAction.InfoAction() =>
                None
          yield (mPrev, newProtVer, prevProtVer)
      case GovAction.ParameterChange(_, _, _) | GovAction.TreasuryWithdrawals(_, _) |
          GovAction.NoConfidence(_) | GovAction.UpdateCommittee(_, _, _, _) |
          GovAction.NewConstitution(_, _) | GovAction.InfoAction() =>
        None

  /** `indexedGovProps` (`Governance/Procedures.hs:490-496`, cardano-ledger f649f975): each proposal
    * with its index, a `GovActionIx` (`Word16`) from 0. A 65,537th proposal has no index: the
    * reference's `succ` of `maxBound` throws ([[GovDefect.ProposalIndexOverflow]]), and the
    * sequence is strict in its spine, so it throws before any proposal is processed.
    */
  def indexedGovProps[A](
      props: Vector[A]
  ): Either[GovDefect, Vector[(Unsigned[Unsigned.Width.W16], A)]] =
    props.zipWithIndex
      .traverse((a, i) => Unsigned.from[Unsigned.Width.W16](BigInt(i)).map(_ -> a))
      .toRight(GovDefect.ProposalIndexOverflow(props.size))

  /** `conwayGovTransition` (`Conway/Rules/Gov.hs:445-631`, cardano-ledger f649f975): each proposal
    * checked and added to the proposals in order, then the votes of the voters that exist on the
    * actions that do, checked and cast, the votes of the DReps the transaction unregisters removed,
    * and both events told; the proposals after are the state whatever was recorded. `netId` is the
    * network `Globals` gives the reference (`asks networkId`).
    *
    * A voter is the node's flat voter, and an account the node's reward account, so each is read
    * into the state's vocabulary to be looked up; one outside it is raised ([[GovDefect.Vocab]]),
    * as the flat rule raises it.
    */
  def conwayGovTransition[F[_]: Monad, E <: EraTag](
      env: GovEnv[E],
      signal: GovSignal[E],
      netId: Network
  )(using
      p: ConwayEraPParams[E],
      c: ConwayEraCertState[E],
      t: ConwayEraTxCert[E],
      inject: InjectGov[E],
      state: Stateful[F, Proposals[E]],
      judge: Judge[F, GovPredFailure[E]],
      events: Tell[F, Chain[GovEvent[E]]],
      defect: Raise[F, GovDefect]
  ): F[Unit] =
    val GovEnv(txid, currentEpoch, pp, constitutionPolicy, certState, committee) = env
    val pv = p.ppProtocolVersionL.get(pp)
    val bootstrap = ConwayEra.hardforkConwayBootstrapPhase(pv)
    val certVState = c.certVStateL.get(certState)
    val certPState = c.certPStateL.get(certState)
    val accounts = c.certDStateL.get(certState).accounts
    val committeeState = certVState.committeeState
    val knownDReps = certVState.dreps
    val knownStakePools = certPState.stakePools
    val knownCommitteeMembers = ConwayCommittee.authorizedHotCommitteeCredentials(committeeState)

    def fail(f: ConwayGovPredFailure[E]): F[Unit] = judge.fail(inject.injectFailure(f))
    def check(cond: Boolean, f: => ConwayGovPredFailure[E]): F[Unit] =
      if cond then Monad[F].unit else fail(f)
    def runTest(test: Test[ConwayGovPredFailure[E]]): F[Unit] =
      Sts.runTest[F, GovPredFailure[E]](test.leftMap(_.map(inject.injectFailure)))
    def vocab[A](result: Either[VocabError, A]): F[A] =
      result.fold(e => defect.raise[GovDefect, A](GovDefect.Vocab(e)), Monad[F].pure)
    def accountAddress(account: xyz.kd5ujc.scarab.ledger.RewardAccount): F[AccountAddress] =
      vocab(Convert.accountAddress(account))
    def isAccountRegistered(account: AccountAddress): Boolean =
      EraAccounts.isAccountRegistered[E](account.id.credential, accounts)(using c.accountsOf)
    def network(account: AccountAddress): Boolean = account.networkId == netId

    def processProposal(
        pgaids: GovRelation[Option],
        proposals: Proposals[E],
        indexed: (Unsigned[Unsigned.Width.W16], ProposalProcedure[E])
    ): F[Proposals[E]] =
      val (idx, proposal) = indexed
      val govAction = proposal.pProcGovAction
      val newGaid = GovActionId(txid.bytes, idx.value)
      val badHardFork = preceedingHardFork[E](pp, pgaids, proposals, govAction).flatMap {
        (prevGaid, newProtVer, prevProtVer) =>
          Option.unless(pvCanFollow(prevProtVer, newProtVer))(
            ConwayGovPredFailure.ProposalCantFollow[E](prevGaid, Mismatch(newProtVer, prevProtVer))
          )
      }
      val expectedDeposit = ConwayEraPParams.ppGovActionDepositCompactL[E].get(pp).toCoin
      for
        pProcReturnAddr <- accountAddress(proposal.pProcReturnAddr)
        withdrawalAccounts <- govAction match
          case GovAction.TreasuryWithdrawals(withdrawals, _) =>
            SortedSet.from(withdrawals.keys).toList.traverse(accountAddress)
          case GovAction.ParameterChange(_, _, _) | GovAction.HardForkInitiation(_, _) |
              GovAction.NoConfidence(_) | GovAction.UpdateCommittee(_, _, _, _) |
              GovAction.NewConstitution(_, _) | GovAction.InfoAction() =>
            Monad[F].pure(List.empty[AccountAddress])
        _ <- runTest(checkBootstrapProposal(pp, proposal))
        _ <- badHardFork.traverse_(fail)
        _ <- runTest(actionWellFormed(pv, govAction))
        _ <-
          if bootstrap then Monad[F].unit
          else
            check(
              isAccountRegistered(pProcReturnAddr),
              ConwayGovPredFailure.ProposalReturnAccountDoesNotExist(pProcReturnAddr)
            ) *>
              // the withdrawals' accounts, ascending (`Map.keys`); none for another action
              NonEmptyList
                .fromList(withdrawalAccounts.filterNot(isAccountRegistered))
                .traverse_(ns =>
                  fail(ConwayGovPredFailure.TreasuryWithdrawalReturnAccountsDoNotExist(ns))
                )
        _ <- check(
          proposal.pProcDeposit == expectedDeposit,
          ConwayGovPredFailure.ProposalDepositIncorrect(
            Mismatch(proposal.pProcDeposit, expectedDeposit)
          )
        )
        _ <- check(
          network(pProcReturnAddr),
          ConwayGovPredFailure.ProposalProcedureNetworkIdMismatch(pProcReturnAddr, netId)
        )
        _ <- govAction match
          case GovAction.TreasuryWithdrawals(wdrls, proposalPolicy) =>
            val mismatchedAccounts = SortedSet.from(withdrawalAccounts.filterNot(network))
            for
              _ <- NonEmptySet
                .fromSet(mismatchedAccounts)
                .traverse_(m =>
                  fail(ConwayGovPredFailure.TreasuryWithdrawalsNetworkIdMismatch(m, netId))
                )
              _ <- runTest(checkGuardrailsScriptHash[E](constitutionPolicy, proposalPolicy))
              _ <- check(
                wdrls.values.foldLeft(BigInt(0))(_ + _.value) != 0,
                ConwayGovPredFailure.ZeroTreasuryWithdrawals(govAction)
              )
            yield ()
          case GovAction.UpdateCommittee(_, membersToRemove, membersToAdd, _) =>
            val conflicting = SortedSet.from(membersToAdd.keySet.intersect(membersToRemove))
            val invalidMembers = SortedMap.from(
              membersToAdd.filter((_, e) => cats.Order[EpochNo].lteqv(e, currentEpoch))
            )
            NonEmptySet
              .fromSet(conflicting)
              .traverse_(m => fail(ConwayGovPredFailure.ConflictingCommitteeUpdate(m))) *>
              NonEmptyMap
                .fromMap(invalidMembers)
                .traverse_(m => fail(ConwayGovPredFailure.ExpirationEpochTooSmall(m)))
          case GovAction.ParameterChange(_, _, proposalPolicy) =>
            runTest(checkGuardrailsScriptHash[E](constitutionPolicy, proposalPolicy))
          case GovAction.HardForkInitiation(_, _) | GovAction.NoConfidence(_) |
              GovAction.NewConstitution(_, _) | GovAction.InfoAction() =>
            Monad[F].unit
        actionState = mkGovActionState(
          newGaid,
          proposal,
          ConwayEraPParams.ppGovActionLifetimeL[E].get(pp),
          currentEpoch
        )
        updated <- Proposals.proposalsAddAction(actionState, proposals) match
          case Some(updatedProposals) => Monad[F].pure(updatedProposals)
          case None => fail(ConwayGovPredFailure.InvalidPrevGovActionId(proposal)).as(proposals)
      yield updated

    /** `internVoter`: whether the voter is in the ledger state. */
    def known(voter: Voter): F[Boolean] = voter match
      case Voter.CommitteeHot(hotCred) =>
        vocab(Convert.credential[HotCommittee](hotCred)).map(knownCommitteeMembers.contains)
      case Voter.DRepVoter(cred) =>
        vocab(Convert.credential[DRepRole](cred)).map(knownDReps.contains)
      case Voter.Pool(poolId) =>
        vocab(Convert.keyHash[StakePool](poolId)).map(knownStakePools.contains)

    for
      st <- state.get
      pgaids = Proposals.toPrevGovActionIds(st.pRoots)
      _ <-
        if ConwayEra.hardforkConwayDisallowUnelectedCommitteeFromVoting(pv) then
          NonEmptyList
            .fromList(
              unelectedCommitteeVoters(committee, committeeState, signal.gsVotingProcedures).toList
            )
            .traverse_(u => fail(ConwayGovPredFailure.UnelectedCommitteeVoters(u)))
        else Monad[F].unit
      indexed <- indexedGovProps(signal.gsProposalProcedures.toVector)
        .fold(
          defect.raise[GovDefect, Vector[(Unsigned[Unsigned.Width.W16], ProposalProcedure[E])]],
          Monad[F].pure
        )
      proposals <- indexed.foldM(st)((ps, ix) => processProposal(pgaids, ps, ix))
      voters = signal.gsVotingProcedures.unVotingProcedures.toVector
      knownFlags <- voters.traverse((voter, _) => known(voter))
      (knownVoters, unknownVoters) = voters.zip(knownFlags).partition(_._2) match
        case (k, u) => (k.map(_._1), u.map(_._1._1))
      curGovActionIds = Proposals.proposalsActionsMap(proposals)
      // `foldrVotingProcedures` over the known voters, each list built in front from the right:
      // in the votes' order, voters ascending and each voter's actions ascending
      votesInOrder = knownVoters.flatMap((voter, votes) =>
        votes.toVector.map((gaId, vp) => (voter, gaId, vp))
      )
      unknownGovActionIds = votesInOrder.collect {
        case (_, gaId, _) if !curGovActionIds.contains(gaId) => gaId
      }
      knownVotesWithCast = votesInOrder.flatMap((voter, gaId, vp) =>
        curGovActionIds.get(gaId).map(gas => (voter, vp.vote, gas))
      )
      replacedVotes = SortedSet.from(knownVotesWithCast.collect {
        case (voter, _, gas) if (voter match
              case Voter.CommitteeHot(hotCred) => gas.gasCommitteeVotes.contains(hotCred)
              case Voter.DRepVoter(cred)       => gas.gasDRepVotes.contains(cred)
              case Voter.Pool(poolId)          => gas.gasStakePoolVotes.contains(poolId)
            ) =>
          (voter, gas.gasId)
      })
      knownVotes = knownVotesWithCast.map((voter, _, gas) => (voter, gas))
      _ <- NonEmptyList
        .fromList(unknownVoters.toList)
        .traverse_(v => fail(ConwayGovPredFailure.VotersDoNotExist(v)))
      _ <- NonEmptyList
        .fromList(unknownGovActionIds.toList)
        .traverse_(ids => fail(ConwayGovPredFailure.GovActionsDoNotExist(ids)))
      _ <- runTest(checkBootstrapVotes(pp, knownVotes))
      _ <- runTest(checkVotesAreNotForExpiredActions(currentEpoch, knownVotes))
      _ <- runTest(checkVotersAreValid(knownVotes))
      unregisteredDReps = SortedSet.from(
        signal.gsCertificates.flatMap(cert => t.getUnRegDRepTxCert(cert).map(_._1))
      )
      cleanupProposalVotes = (ps: Proposals[E]) =>
        if unregisteredDReps.isEmpty then ps
        else
          val removed = unregisteredDReps.map(Convert.back(_))
          Proposals.mapProposals[E](gas => gas.copy(gasDRepVotes = gas.gasDRepVotes -- removed))(ps)
      updatedProposalStates = cleanupProposalVotes(
        knownVotesWithCast.foldLeft(proposals)((ps, cast) =>
          Proposals.proposalsAddVote(cast._1, cast._2, cast._3.gasId, ps)
        )
      )
      _ <- events.tell(
        Chain(
          inject.injectEvent(ConwayGovEvent.GovNewProposals(txid, updatedProposalStates)),
          inject.injectEvent(
            ConwayGovEvent.GovRemovedVotes(txid, replacedVotes, unregisteredDReps)
          )
        )
      )
      _ <- state.set(updatedProposalStates)
    yield ()
