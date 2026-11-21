// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.rules

import cats.Order
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.ledger.Vote
import xyz.kd5ujc.scarab.ledger.era.*
import xyz.kd5ujc.scarab.ledger.era.VotingThreshold.{
  votingCommitteeThreshold,
  votingDRepThreshold,
  votingStakePoolThreshold
}
import xyz.kd5ujc.scarab.ledger.state.{
  Coin,
  ColdCommittee,
  CommitteeAuthorization,
  CommitteeState,
  CompactCoin,
  Credential,
  DRep,
  DRepRole,
  EpochNo,
  EraTag,
  HotCommittee,
  VocabError,
  addInterval
}

/** Conway's RATIFY (cardano-ledger f649f975
  * `eras/conway/impl/src/Cardano/Ledger/Conway/Rules/Ratify.hs`), the RATIFY of both eras from
  * Conway (`Conway/Era.hs`, `Dijkstra/Era.hs:226`, 9df9f98c, where the rule reads the pools' voting
  * stake, [[VotingStakePoolDistr]]), generic in its era over the reference's classes. It has no
  * predicate failure and no event (`Void`, `:98`, and its ENACT's embedding, `:378-380`): it is a
  * function of its environment, state and signal. What remains is ENACT's [[VocabError]], which an
  * imported state's withdrawal to an account of a network the reference does not read reaches
  * ([[ConwayEnact]]), and the committee the enact state holds read at the reference's types
  * ([[Committee.fromStored]]), which no state the node's codec read holds.
  */
object ConwayRatify:
  /** `x %? y` (`BaseTypes/NonZero.hs:150-153`, cardano-ledger f649f975): zero only where the
    * denominator is zero.
    */
  private def ratio(x: BigInt, y: BigInt): Rational =
    if y == 0 then Rational.zero else Rational(x, y)

  private def isZero(r: UnitInterval): Boolean = r.rational.isZero

  /** `committeeAccepted` (`Ratify.hs:118-134`, cardano-ledger f649f975): no threshold (no
    * committee, or too small a one) is a no, a zero threshold a yes.
    */
  def committeeAccepted[E <: EraTag](
      env: RatifyEnv[E],
      rs: RatifyState[E],
      gas: GovActionState[E]
  )(using ConwayEraPParams[E]): Either[VocabError, Boolean] =
    for
      threshold <- votingCommitteeThreshold(
        env.reCurrentEpoch,
        rs,
        env.reCommitteeState,
        gas.gasAction
      )
      committee <- rs.rsEnactState.ensCommittee.fold[Either[VocabError, Option[Committee[E]]]](
        Right(None)
      )(c => Committee.fromStored[E](c).map(Some(_)))
    yield threshold match
      case None => false
      case Some(r) =>
        def members = committee.fold(SortedMap.empty[Credential[ColdCommittee], EpochNo])(
          _.committeeMembers
        )
        isZero(r) || committeeAcceptedRatio(
          members,
          gas.gasCommitteeVotes,
          env.reCommitteeState,
          env.reCurrentEpoch
        ) >= r.rational

  /** `committeeAcceptedRatio` (`Ratify.hs:136-163`, cardano-ledger f649f975): the members voting
    * yes over those not abstaining, an expired, unregistered or resigned member abstaining and one
    * that did not vote voting no.
    */
  def committeeAcceptedRatio(
      members: SortedMap[Credential[ColdCommittee], EpochNo],
      votes: SortedMap[Credential[HotCommittee], Vote],
      committeeState: CommitteeState,
      currentEpoch: EpochNo
  ): Rational =
    val (yesVotes, totalExcludingAbstain) = members.foldLeft((BigInt(0), BigInt(0))) {
      case ((yes, tot), (member, expiry)) =>
        if Order[EpochNo].gt(currentEpoch, expiry) then (yes, tot)
        else
          committeeState.creds.get(member) match
            case None                                                    => (yes, tot)
            case Some(CommitteeAuthorization.CommitteeMemberResigned(_)) => (yes, tot)
            case Some(CommitteeAuthorization.CommitteeHotCredential(hotKey)) =>
              votes.get(hotKey) match
                case None               => (yes, tot + 1)
                case Some(Vote.Abstain) => (yes, tot)
                case Some(Vote.No)      => (yes, tot + 1)
                case Some(Vote.Yes)     => (yes + 1, tot + 1)
    }
    ratio(yesVotes, totalExcludingAbstain)

  /** `spoAccepted` (`Ratify.hs:165-176`, cardano-ledger f649f975). */
  def spoAccepted[E <: EraTag](env: RatifyEnv[E], rs: RatifyState[E], gas: GovActionState[E])(using
      p: ConwayEraPParams[E],
      a: ConwayEraAccounts[E]
  ): Boolean =
    votingStakePoolThreshold(rs, gas.gasAction) match
      case Some(r) =>
        isZero(r) ||
        spoAcceptedRatio(env, gas, p.ppProtocolVersionL.get(rs.rsEnactState.ensCurPParams)) >=
          r.rational
      case None => false

  /** `spoAcceptedRatio` (`Ratify.hs:195-226`, cardano-ledger f649f975; over the pools' voting stake
    * at 9df9f98c, `:199-227`): the stake voting yes over the total less the stake abstaining. A
    * pool that did not vote votes no on a hard fork, abstains in the bootstrap phase, and otherwise
    * votes as its reward account's DRep makes it ([[DefaultVote.defaultStakePoolVote]]). The sums
    * are of compact coins, `Word64` s, as the reference's are.
    */
  def spoAcceptedRatio[E <: EraTag](env: RatifyEnv[E], gas: GovActionState[E], pv: ProtVer)(using
      ConwayEraAccounts[E]
  ): Rational =
    val action = gas.gasAction
    val (yesStake, abstainStake) =
      env.reVotingStakePoolDistr.vspdIndividualStake.foldLeft(
        (CompactCoin.zero, CompactCoin.zero)
      ) { case ((yes, abstain), (poolId, stake)) =>
        gas.gasStakePoolVotes.get(poolId) match
          case None =>
            def isNoConfidence: Boolean = action match
              case GovAction.NoConfidence(_) => true
              case GovAction.ParameterChange(_, _, _) | GovAction.HardForkInitiation(_, _) |
                  GovAction.TreasuryWithdrawals(_, _) | GovAction.UpdateCommittee(_, _, _, _) |
                  GovAction.NewConstitution(_, _) | GovAction.InfoAction() =>
                false
            def notVoted: (CompactCoin, CompactCoin) =
              if ConwayEra.hardforkConwayBootstrapPhase(pv) then (yes, abstain.add(stake))
              else
                DefaultVote.defaultStakePoolVote(poolId, env.reStakePools, env.reAccounts) match
                  case DefaultVote.DefaultNoConfidence if isNoConfidence =>
                    (yes.add(stake), abstain)
                  case DefaultVote.DefaultAbstain => (yes, abstain.add(stake))
                  case DefaultVote.DefaultNo | DefaultVote.DefaultNoConfidence => (yes, abstain)
            action match
              case GovAction.HardForkInitiation(_, _) => (yes, abstain)
              case GovAction.ParameterChange(_, _, _) | GovAction.TreasuryWithdrawals(_, _) |
                  GovAction.NoConfidence(_) | GovAction.UpdateCommittee(_, _, _, _) |
                  GovAction.NewConstitution(_, _) | GovAction.InfoAction() =>
                notVoted
          case Some(Vote.Abstain) => (yes, abstain.add(stake))
          case Some(Vote.No)      => (yes, abstain)
          case Some(Vote.Yes)     => (yes.add(stake), abstain)
      }
    val totalStake = env.reVotingStakePoolDistr.vspdTotalVotingStake.value.value
    ratio(yesStake.toBigInt, totalStake - abstainStake.toBigInt)

  /** `dRepAccepted` (`Ratify.hs:228-239`, cardano-ledger f649f975). */
  def dRepAccepted[E <: EraTag](env: RatifyEnv[E], rs: RatifyState[E], gas: GovActionState[E])(using
      ConwayEraPParams[E]
  ): Boolean =
    votingDRepThreshold(rs, gas.gasAction) match
      case Some(r) =>
        isZero(r) || dRepAcceptedRatio(env, gas.gasDRepVotes, gas.gasAction) >= r.rational
      case None => false

  /** `dRepAcceptedRatio` (`Ratify.hs:252-281`, cardano-ledger f649f975): the stake voting yes over
    * the stake not abstaining, of registered DReps not expired, with the always-no-confidence DRep
    * voting yes on a motion of no confidence and no otherwise. The sums are of compact coins, as
    * the reference's are.
    */
  def dRepAcceptedRatio[E <: EraTag](
      env: RatifyEnv[E],
      gasDRepVotes: SortedMap[Credential[DRepRole], Vote],
      govAction: GovAction[E]
  ): Rational =
    def credential(
        cred: Credential[DRepRole],
        yes: CompactCoin,
        tot: CompactCoin,
        stake: CompactCoin
    ) =
      env.reDRepState.get(cred) match
        case None => (yes, tot)
        case Some(drepState) if Order[EpochNo].gt(env.reCurrentEpoch, drepState.expiry) =>
          (yes, tot)
        case Some(_) =>
          gasDRepVotes.get(cred) match
            case None               => (yes, tot.add(stake))
            case Some(Vote.Yes)     => (yes.add(stake), tot.add(stake))
            case Some(Vote.Abstain) => (yes, tot)
            case Some(Vote.No)      => (yes, tot.add(stake))
    val (yesStake, totalExcludingAbstainStake) =
      env.reDRepDistr.foldLeft((CompactCoin.zero, CompactCoin.zero)) {
        case ((yes, tot), (drep, stake)) =>
          drep match
            case DRep.DRepKeyHash(kh) =>
              credential(Credential.KeyHashObj[DRepRole](kh), yes, tot, stake)
            case DRep.DRepScriptHash(sh) =>
              credential(Credential.ScriptHashObj[DRepRole](sh), yes, tot, stake)
            case DRep.DRepAlwaysNoConfidence =>
              govAction match
                case GovAction.NoConfidence(_) => (yes.add(stake), tot.add(stake))
                case GovAction.ParameterChange(_, _, _) | GovAction.HardForkInitiation(_, _) |
                    GovAction.TreasuryWithdrawals(_, _) | GovAction.UpdateCommittee(_, _, _, _) |
                    GovAction.NewConstitution(_, _) | GovAction.InfoAction() =>
                  (yes, tot.add(stake))
            case DRep.DRepAlwaysAbstain => (yes, tot)
      }
    ratio(yesStake.toBigInt, totalExcludingAbstainStake.toBigInt)

  /** `delayingAction` (`Ratify.hs:283-290`, cardano-ledger f649f975). */
  def delayingAction[E <: EraTag](action: GovAction[E]): Boolean = action match
    case GovAction.NoConfidence(_)             => true
    case GovAction.HardForkInitiation(_, _)    => true
    case GovAction.UpdateCommittee(_, _, _, _) => true
    case GovAction.NewConstitution(_, _)       => true
    case GovAction.TreasuryWithdrawals(_, _)   => false
    case GovAction.ParameterChange(_, _, _)    => false
    case GovAction.InfoAction()                => false

  /** `withdrawalCanWithdraw` (`Ratify.hs:292-295`, cardano-ledger f649f975). */
  def withdrawalCanWithdraw[E <: EraTag](action: GovAction[E], treasury: Coin): Boolean =
    action match
      case GovAction.TreasuryWithdrawals(m, _) =>
        m.valuesIterator.map(_.value).sum <= treasury.value
      case GovAction.ParameterChange(_, _, _) | GovAction.HardForkInitiation(_, _) |
          GovAction.NoConfidence(_) | GovAction.UpdateCommittee(_, _, _, _) |
          GovAction.NewConstitution(_, _) | GovAction.InfoAction() =>
        true

  /** `acceptedByEveryone` (`Ratify.hs:297-306`, cardano-ledger f649f975): the committee, the pools
    * and the DReps, each asked only if those before accepted.
    */
  def acceptedByEveryone[E <: EraTag](
      env: RatifyEnv[E],
      st: RatifyState[E],
      gas: GovActionState[E]
  )(using ConwayEraPParams[E], ConwayEraAccounts[E]): Either[VocabError, Boolean] =
    committeeAccepted(env, st, gas).map(
      _ && spoAccepted(env, st, gas) && dRepAccepted(env, st, gas)
    )

  /** `prevActionAsExpected` (`Ratify.hs:364-367`, cardano-ledger f649f975): the action's parent is
    * the last enacted action of its purpose; an action of no such purpose has none to match.
    */
  def prevActionAsExpected[E <: EraTag](
      gas: GovActionState[E],
      prevGovActionIds: GovRelation[Option]
  ): Boolean =
    GovActionState.withGovActionParent(gas, true)(
      new GovActionParent[Boolean]:
        def apply[P <: GovActionPurpose](
            field: GovRelationL[P],
            parent: Option[GovPurposeId[P]],
            self: GovPurposeId[P]
        ): Boolean = parent == field.get[Option](prevGovActionIds)
    )

  /** `validCommitteeTerm` (`Ratify.hs:369-382`, cardano-ledger f649f975): every member a committee
    * update adds ends its term within the parameters' maximum from the current epoch.
    */
  def validCommitteeTerm[E <: EraTag](
      govAction: GovAction[E],
      pp: PParams[E],
      currentEpoch: EpochNo
  )(using
      ConwayEraPParams[E]
  ): Boolean =
    govAction match
      case GovAction.UpdateCommittee(_, _, newMembers, _) =>
        val limit = currentEpoch.addInterval(ConwayEraPParams.ppCommitteeMaxTermLengthL[E].get(pp))
        newMembers.valuesIterator.forall(Order[EpochNo].lteqv(_, limit))
      case GovAction.ParameterChange(_, _, _) | GovAction.HardForkInitiation(_, _) |
          GovAction.TreasuryWithdrawals(_, _) | GovAction.NoConfidence(_) |
          GovAction.NewConstitution(_, _) | GovAction.InfoAction() =>
        true

  /** `ratifyTransition` (`Ratify.hs:308-362`, cardano-ledger f649f975): each action in the signal's
    * order, enacted by ENACT ([[ConwayEnact.enactmentTransition]]) if its parent is the last
    * enacted of its purpose, its committee's terms are valid, no delaying action was enacted before
    * it, the treasury holds its withdrawals and every body accepts it; otherwise, past its last
    * epoch, it expires. The treasury of the enact state is zero at the end.
    *
    * The reference recurses on the rest of the signal, after the action in the enacted branch and
    * before marking it expired in the other, which is not a tail call; this is a fold over the
    * signal, the same result: the other branch carries the state on unchanged, and nothing the rest
    * of the signal reads or the end changes is the set of expired actions, so adding to it before
    * the rest is adding to it after. Each action is judged on the enact state the actions enacted
    * before it left, as the reference judges it.
    */
  def ratifyTransition[E <: EraTag](
      env: RatifyEnv[E],
      st: RatifyState[E],
      signal: RatifySignal[E]
  )(using
      p: ConwayEraPParams[E],
      a: ConwayEraAccounts[E]
  ): Either[VocabError, RatifyState[E]] =
    def step(rs: RatifyState[E], gas: GovActionState[E]): Either[VocabError, RatifyState[E]] =
      val enact = rs.rsEnactState
      val govAction = gas.gasAction
      val admissible =
        prevActionAsExpected(gas, enact.ensPrevGovActionIds) &&
          validCommitteeTerm(govAction, enact.ensCurPParams, env.reCurrentEpoch) &&
          !rs.rsDelayed &&
          withdrawalCanWithdraw(govAction, enact.ensTreasury)
      (if admissible then acceptedByEveryone(env, rs, gas) else Right(false)).flatMap {
        case true =>
          ConwayEnact
            .enactmentTransition(enact, EnactSignal[E](gas.gasId, govAction))
            .map(newEnactState =>
              rs.copy(
                rsEnactState = newEnactState,
                rsDelayed = delayingAction(govAction),
                rsEnacted = rs.rsEnacted :+ gas
              )
            )
        case false =>
          Right(
            if Order[EpochNo].lt(gas.gasExpiresAfter, env.reCurrentEpoch) then
              rs.copy(rsExpired = rs.rsExpired + gas.gasId)
            else rs
          )
      }
    signal.unRatifySignal
      .foldLeft[Either[VocabError, RatifyState[E]]](Right(st))((acc, gas) =>
        acc.flatMap(step(_, gas))
      )
      .map(end => end.copy(rsEnactState = end.rsEnactState.copy(ensTreasury = Coin.zero)))
