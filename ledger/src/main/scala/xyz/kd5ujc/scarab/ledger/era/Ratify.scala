// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import cats.Order
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.ledger.{DRepGroup, StakePoolGroup, Unsigned}
import xyz.kd5ujc.scarab.ledger.Unsigned.Width.W16
import xyz.kd5ujc.scarab.ledger.era.conway.{DRepVotingThresholds, PoolVotingThresholds}
import xyz.kd5ujc.scarab.ledger.era.rules.ConwayEra
import xyz.kd5ujc.scarab.ledger.state.{
  Accounts,
  Coin,
  ColdCommittee,
  CommitteeAuthorization,
  CommitteeState,
  CompactCoin,
  Credential,
  DRep,
  DRepRole,
  DRepState,
  EpochNo,
  EraTag,
  InstantStake,
  KeyHash,
  NonZero,
  StakePool,
  StakePoolState,
  VocabError
}

// What ratification reads, by era as the reference's is (cardano-ledger f649f975
// `eras/conway/impl/src/Cardano/Ledger/Conway/Governance/Internal.hs:311-606` and
// `Conway/Governance.hs:526-577`; Dijkstra's the same at 9df9f98c, but for the pools' voting
// stake, [[VotingStakePoolDistr]]): the thresholds of each body for each action, the environment
// and the signal of RATIFY, and a pool's default vote. The rule is
// [[xyz.kd5ujc.scarab.ledger.era.rules.ConwayRatify]].

/** `data VotingThreshold` (`Internal.hs:329-336`, cardano-ledger f649f975): the share of a body's
  * votes an action needs, that it has none, so that it cannot be ratified, or that the body may not
  * vote on it, the reference's constructor `VotingThreshold` being [[VotingThreshold.Threshold]].
  * The reference's threshold is lazy, a value a check of whether a body may vote never reads; here
  * it is the value.
  */
enum VotingThreshold:
  case Threshold(threshold: UnitInterval)
  case NoVotingThreshold
  case NoVotingAllowed

object VotingThreshold:
  given CanEqual[VotingThreshold, VotingThreshold] = CanEqual.derived

  private def ge(a: UnitInterval, b: UnitInterval): Boolean = a.rational >= b.rational

  /** `toRatifyVotingThreshold` (`Internal.hs:338-342`, cardano-ledger f649f975): the threshold
    * ratification compares the body's ratio with; none prevents ratification, and a body that may
    * not vote has a threshold of zero.
    */
  def toRatifyVotingThreshold(t: VotingThreshold): Option[UnitInterval] = t match
    case Threshold(threshold) => Some(threshold)
    case NoVotingThreshold    => None
    case NoVotingAllowed      => Some(UnitInterval.zero)

  /** `isVotingAllowed` (`Internal.hs:344-348`, cardano-ledger f649f975). */
  def isVotingAllowed(t: VotingThreshold): Boolean = t match
    case Threshold(_) | NoVotingThreshold => true
    case NoVotingAllowed                  => false

  /** `pparamsUpdateThreshold` (`Internal.hs:311-327`, cardano-ledger f649f975): the greatest of the
    * DReps' thresholds of the groups the update changes, zero if it changes none. The groups are
    * the era's (`modifiedPPGroups`), so Dijkstra's keys 34 to 56 take the groups its record gives
    * them (`Dijkstra/PParams.hs:233-299`, cardano-ledger 9df9f98c).
    */
  def pparamsUpdateThreshold[E <: EraTag](thresholds: DRepVotingThresholds, ppu: PParamsUpdate[E])(
      using p: ConwayEraPParams[E]
  ): UnitInterval =
    def lookupGroupThreshold(g: PPGroups): UnitInterval = g.drep match
      case DRepGroup.Network   => thresholds.dvtPPNetworkGroup
      case DRepGroup.Gov       => thresholds.dvtPPGovGroup
      case DRepGroup.Technical => thresholds.dvtPPTechnicalGroup
      case DRepGroup.Economic  => thresholds.dvtPPEconomicGroup
    p.modifiedPPGroups(ppu)
      .iterator
      .map(lookupGroupThreshold)
      .foldLeft(UnitInterval.zero)((a, b) => if ge(a, b) then a else b)

  /** `isStakePoolVotingAllowed` (`Internal.hs:350-360`, cardano-ledger f649f975): over the empty
    * parameters and no elected committee, which decide nothing here.
    */
  def isStakePoolVotingAllowed[E <: EraTag](action: GovAction[E])(using
      p: ConwayEraPParams[E]
  ): Boolean =
    isVotingAllowed(votingStakePoolThresholdInternal(p.emptyPParams, false, action))

  /** `votingStakePoolThreshold` (`Internal.hs:362-371`, cardano-ledger f649f975): over the enact
    * state's current parameters and whether it has a committee.
    */
  def votingStakePoolThreshold[E <: EraTag](ratifyState: RatifyState[E], action: GovAction[E])(using
      ConwayEraPParams[E]
  ): Option[UnitInterval] =
    val enact = ratifyState.rsEnactState
    toRatifyVotingThreshold(
      votingStakePoolThresholdInternal(enact.ensCurPParams, enact.ensCommittee.isDefined, action)
    )

  /** `votingStakePoolThresholdInternal` (`Internal.hs:373-406`, cardano-ledger f649f975): the pools
    * vote on a parameter change only if it changes a parameter of the security group.
    */
  def votingStakePoolThresholdInternal[E <: EraTag](
      pp: PParams[E],
      isElectedCommittee: Boolean,
      action: GovAction[E]
  )(using p: ConwayEraPParams[E]): VotingThreshold =
    val t: PoolVotingThresholds = ConwayEraPParams.ppPoolVotingThresholdsL[E].get(pp)
    def isSecurityRelevant(g: PPGroups): Boolean = g.pool match
      case StakePoolGroup.Security    => true
      case StakePoolGroup.NoStakePool => false
    def paramChangeThreshold(ppu: PParamsUpdate[E]): VotingThreshold =
      if p.modifiedPPGroups(ppu).exists(isSecurityRelevant) then Threshold(t.pvtPPSecurityGroup)
      else NoVotingAllowed
    action match
      case GovAction.NoConfidence(_) => Threshold(t.pvtMotionNoConfidence)
      case GovAction.UpdateCommittee(_, _, _, _) =>
        Threshold(
          if isElectedCommittee then t.pvtCommitteeNormal else t.pvtCommitteeNoConfidence
        )
      case GovAction.NewConstitution(_, _)      => NoVotingAllowed
      case GovAction.HardForkInitiation(_, _)   => Threshold(t.pvtHardForkInitiation)
      case GovAction.ParameterChange(_, ppu, _) => paramChangeThreshold(ppu)
      case GovAction.TreasuryWithdrawals(_, _)  => NoVotingAllowed
      case GovAction.InfoAction()               => NoVotingThreshold

  /** `isCommitteeVotingAllowed` (`Internal.hs:408-424`, cardano-ledger f649f975): over the empty
    * parameters and no committee, which decide nothing here.
    */
  def isCommitteeVotingAllowed[E <: EraTag](
      currentEpoch: EpochNo,
      committeeState: CommitteeState,
      action: GovAction[E]
  )(using p: ConwayEraPParams[E]): Boolean =
    isVotingAllowed(
      votingCommitteeThresholdInternal(currentEpoch, p.emptyPParams, None, committeeState, action)
    )

  /** `votingCommitteeThreshold` (`Internal.hs:426-442`, cardano-ledger f649f975): over the enact
    * state's current parameters and committee, the node's record of it read at the reference's
    * types ([[Committee.fromStored]]).
    */
  def votingCommitteeThreshold[E <: EraTag](
      currentEpoch: EpochNo,
      ratifyState: RatifyState[E],
      committeeState: CommitteeState,
      action: GovAction[E]
  )(using ConwayEraPParams[E]): Either[VocabError, Option[UnitInterval]] =
    val enact = ratifyState.rsEnactState
    enact.ensCommittee
      .fold[Either[VocabError, Option[Committee[E]]]](Right(None))(c =>
        Committee.fromStored[E](c).map(Some(_))
      )
      .map(committee =>
        toRatifyVotingThreshold(
          votingCommitteeThresholdInternal(
            currentEpoch,
            enact.ensCurPParams,
            committee,
            committeeState,
            action
          )
        )
      )

  /** `votingCommitteeThresholdInternal` (`Internal.hs:444-479`, cardano-ledger f649f975): out of
    * the bootstrap phase, a committee of fewer active members than the parameters' minimum is
    * treated as none. The active members are counted as the reference counts them, `fromIntegral`
    * of the map's size at the minimum's type, a `Word16`, which wraps.
    */
  def votingCommitteeThresholdInternal[E <: EraTag](
      currentEpoch: EpochNo,
      pp: PParams[E],
      committee: Option[Committee[E]],
      committeeState: CommitteeState,
      action: GovAction[E]
  )(using p: ConwayEraPParams[E]): VotingThreshold =
    def isActive(coldKey: Credential[ColdCommittee], validUntil: EpochNo): Boolean =
      committeeState.creds.get(coldKey) match
        case Some(CommitteeAuthorization.CommitteeMemberResigned(_)) => false
        case Some(CommitteeAuthorization.CommitteeHotCredential(_)) =>
          Order[EpochNo].lteqv(currentEpoch, validUntil)
        case None => false
    def threshold: VotingThreshold = committee match
      case Some(c) =>
        val minSize = ConwayEraPParams.ppCommitteeMinSizeL[E].get(pp)
        val activeCommitteeSize = Unsigned.wrapping[W16](
          BigInt(c.committeeMembers.count((cold, until) => isActive(cold, until)))
        )
        if ConwayEra.hardforkConwayBootstrapPhase(p.ppProtocolVersionL.get(pp)) ||
          activeCommitteeSize.value >= minSize.value
        then Threshold(c.committeeThreshold)
        else NoVotingThreshold
      case None => NoVotingThreshold
    action match
      case GovAction.NoConfidence(_)             => NoVotingAllowed
      case GovAction.UpdateCommittee(_, _, _, _) => NoVotingAllowed
      case GovAction.NewConstitution(_, _)       => threshold
      case GovAction.HardForkInitiation(_, _)    => threshold
      case GovAction.ParameterChange(_, _, _)    => threshold
      case GovAction.TreasuryWithdrawals(_, _)   => threshold
      case GovAction.InfoAction()                => NoVotingThreshold

  /** `isDRepVotingAllowed` (`Internal.hs:481-491`, cardano-ledger f649f975): over the empty
    * parameters and no elected committee, which decide nothing here.
    */
  def isDRepVotingAllowed[E <: EraTag](action: GovAction[E])(using
      p: ConwayEraPParams[E]
  ): Boolean =
    isVotingAllowed(votingDRepThresholdInternal(p.emptyPParams, false, action))

  /** `votingDRepThreshold` (`Internal.hs:493-502`, cardano-ledger f649f975). */
  def votingDRepThreshold[E <: EraTag](ratifyState: RatifyState[E], action: GovAction[E])(using
      ConwayEraPParams[E]
  ): Option[UnitInterval] =
    val enact = ratifyState.rsEnactState
    toRatifyVotingThreshold(
      votingDRepThresholdInternal(enact.ensCurPParams, enact.ensCommittee.isDefined, action)
    )

  /** `votingDRepThresholdInternal` (`Internal.hs:504-532`, cardano-ledger f649f975): in the
    * bootstrap phase every threshold is the default, zero.
    */
  def votingDRepThresholdInternal[E <: EraTag](
      pp: PParams[E],
      isElectedCommittee: Boolean,
      action: GovAction[E]
  )(using p: ConwayEraPParams[E]): VotingThreshold =
    val thresholds =
      if ConwayEra.hardforkConwayBootstrapPhase(p.ppProtocolVersionL.get(pp)) then
        DRepVotingThresholds.zero
      else ConwayEraPParams.ppDRepVotingThresholdsL[E].get(pp)
    action match
      case GovAction.NoConfidence(_) => Threshold(thresholds.dvtMotionNoConfidence)
      case GovAction.UpdateCommittee(_, _, _, _) =>
        Threshold(
          if isElectedCommittee then thresholds.dvtCommitteeNormal
          else thresholds.dvtCommitteeNoConfidence
        )
      case GovAction.NewConstitution(_, _)    => Threshold(thresholds.dvtUpdateToConstitution)
      case GovAction.HardForkInitiation(_, _) => Threshold(thresholds.dvtHardForkInitiation)
      case GovAction.ParameterChange(_, ppu, _) =>
        Threshold(pparamsUpdateThreshold(thresholds, ppu))
      case GovAction.TreasuryWithdrawals(_, _) => Threshold(thresholds.dvtTreasuryWithdrawal)
      case GovAction.InfoAction()              => NoVotingThreshold

/** `data VotingStakePoolDistr` (cardano-ledger 9df9f98c `Governance/Internal.hs:706-716`): each
  * pool's stake, with the deposits of the proposals of its delegators, and their total. The Leios
  * build's RATIFY reads this one; f649f975's reads the same two from the pool distribution
  * (`reStakePoolDistr`, `individualTotalPoolStake` and `pdTotalActiveStake`, `Rules/Ratify.hs:
  * 195-226`), with the same ratio.
  */
final case class VotingStakePoolDistr(
    vspdIndividualStake: SortedMap[KeyHash[StakePool], CompactCoin],
    vspdTotalVotingStake: NonZero[Coin]
)

/** `data RatifyEnv era` (`Internal.hs:554-564`, cardano-ledger f649f975; its pools' stake
  * [[VotingStakePoolDistr]] at 9df9f98c, `:560-570`): what RATIFY reads, the distributions and the
  * DReps' states the DRep pulser took at the epoch's start, the current epoch, the committee's
  * state, the accounts and the pools.
  */
final case class RatifyEnv[E <: EraTag](
    reInstantStake: InstantStake[E],
    reVotingStakePoolDistr: VotingStakePoolDistr,
    reDRepDistr: Map[DRep, CompactCoin],
    reDRepState: Map[Credential[DRepRole], DRepState],
    reCurrentEpoch: EpochNo,
    reCommitteeState: CommitteeState,
    reAccounts: Accounts[E],
    reStakePools: SortedMap[KeyHash[StakePool], StakePoolState]
)

/** `newtype RatifySignal era` (`Internal.hs:546-547`, cardano-ledger f649f975): the actions RATIFY
  * goes through, in order.
  */
final case class RatifySignal[E <: EraTag](unRatifySignal: Vector[GovActionState[E]])

object RatifySignal:
  /** `actionPriority` (`Internal.hs:534-541`, cardano-ledger f649f975). */
  def actionPriority[E <: EraTag](action: GovAction[E]): Int = action match
    case GovAction.NoConfidence(_)             => 0
    case GovAction.UpdateCommittee(_, _, _, _) => 1
    case GovAction.NewConstitution(_, _)       => 2
    case GovAction.HardForkInitiation(_, _)    => 3
    case GovAction.ParameterChange(_, _, _)    => 4
    case GovAction.TreasuryWithdrawals(_, _)   => 5
    case GovAction.InfoAction()                => 6

  /** `reorderActions` (`Internal.hs:543-544`, cardano-ledger f649f975): by priority, a stable sort,
    * as `sortOn` is.
    */
  def reorderActions[E <: EraTag](actions: Vector[GovActionState[E]]): Vector[GovActionState[E]] =
    actions.sortBy(gas => actionPriority(gas.gasAction))

/** `data DefaultVote` (`Conway/Governance.hs:526-536`, cardano-ledger f649f975): how a pool that
  * did not vote is counted, by the DRep its reward account delegates to.
  */
enum DefaultVote:
  case DefaultNo, DefaultAbstain, DefaultNoConfidence

object DefaultVote:
  given CanEqual[DefaultVote, DefaultVote] = CanEqual.derived

  /** `defaultStakePoolVote` (`Conway/Governance.hs:560-577`, cardano-ledger f649f975). */
  def defaultStakePoolVote[E <: EraTag](
      poolId: KeyHash[StakePool],
      stakePools: SortedMap[KeyHash[StakePool], StakePoolState],
      accounts: Accounts[E]
  )(using a: ConwayEraAccounts[E]): DefaultVote =
    stakePools
      .get(poolId)
      .flatMap(spp => a.accountsMapL.get(accounts).get(spp.accountId))
      .flatMap(a.dRepDelegationAccountStateL.get) match
      case Some(DRep.DRepAlwaysAbstain)                                    => DefaultAbstain
      case Some(DRep.DRepAlwaysNoConfidence)                               => DefaultNoConfidence
      case Some(DRep.DRepKeyHash(_)) | Some(DRep.DRepScriptHash(_)) | None => DefaultNo
