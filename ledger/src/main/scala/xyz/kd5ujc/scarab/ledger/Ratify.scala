// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.codec.Bytes

/** RATIFY and ENACT: which proposals the DReps, pools and committee accept, and what enacting them
  * changes. A pure function of the snapshot the DRep pulser took at the epoch boundary.
  */
object Ratify:
  /** A threshold as the ledger reports it. */
  enum Threshold:
    case Required(ratio: Rational)

    /** The action can never be ratified by this body (e.g. no committee). */
    case Unavailable

    /** This body may not vote on the action; its votes do not count. */
    case NotAllowed

  import Threshold.*

  /** Parameter-change groups: DRep group index and whether the parameter is in the security group.
    * 0 network, 1 economic, 2 technical, 3 governance.
    */
  private val groups: Map[Int, (Int, Boolean)] =
    val network = Set(2, 3, 4, 20, 21, 22, 24)
    val economic = Set(0, 1, 5, 6, 10, 11, 16, 17, 19, 33)
    val technical = Set(7, 8, 9, 18, 23)
    val governance = Set(25, 26, 27, 28, 29, 30, 31, 32)
    val security = Set(0, 1, 2, 3, 4, 17, 21, 22, 30, 33)
    (network.map(_ -> 0) ++ economic.map(_ -> 1) ++ technical.map(_ -> 2) ++ governance.map(
      _ -> 3
    )).map { case (k, g) => k -> (g, security(k)) }.toMap

  /** The parameter groups an update modifies, and whether any is in the security group. */
  def modifiedGroups(u: ParamUpdate): (Set[Int], Boolean) =
    val entries = u.fields.keys.flatMap(groups.get).toVector
    (entries.map(_._1).toSet, entries.exists(_._2))

  def isBootstrap(pp: ProtocolParams): Boolean = pp.protocolMajor == 9

  def drepThreshold(pp: ProtocolParams, committeeElected: Boolean, a: GovAction): Threshold =
    // All DRep thresholds except InfoAction's are zero during the bootstrap phase.
    val t = if isBootstrap(pp) then DRepVotingThresholds.zero else pp.drepVotingThresholds
    a match
      case _: GovAction.NoConfidence => Required(t.motionNoConfidence)
      case _: GovAction.UpdateCommittee =>
        Required(if committeeElected then t.committeeNormal else t.committeeNoConfidence)
      case _: GovAction.NewConstitution => Required(t.updateToConstitution)
      case _: GovAction.HardFork        => Required(t.hardForkInitiation)
      case GovAction.ParameterChange(_, u, _) =>
        val ratios = modifiedGroups(u)._1.map {
          case 0 => t.ppNetworkGroup
          case 1 => t.ppEconomicGroup
          case 2 => t.ppTechnicalGroup
          case _ => t.ppGovGroup
        }
        Required(ratios.foldLeft(Rational.zero)(_ max _))
      case _: GovAction.TreasuryWithdrawals => Required(t.treasuryWithdrawal)
      case GovAction.Info                   => Unavailable

  def spoThreshold(pp: ProtocolParams, committeeElected: Boolean, a: GovAction): Threshold =
    val t = pp.poolVotingThresholds
    a match
      case _: GovAction.NoConfidence => Required(t.motionNoConfidence)
      case _: GovAction.UpdateCommittee =>
        Required(if committeeElected then t.committeeNormal else t.committeeNoConfidence)
      case _: GovAction.NewConstitution => NotAllowed
      case _: GovAction.HardFork        => Required(t.hardForkInitiation)
      case GovAction.ParameterChange(_, u, _) =>
        if modifiedGroups(u)._2 then Required(t.securityGroup) else NotAllowed
      case _: GovAction.TreasuryWithdrawals => NotAllowed
      case GovAction.Info                   => Unavailable

  /** The committee threshold for the action, given the committee and who has authorised a hot key.
    */
  def committeeThreshold(
      epoch: BigInt,
      pp: ProtocolParams,
      committee: Option[Committee],
      committeeState: Map[Credential, CommitteeAuth],
      a: GovAction
  ): Threshold =
    def threshold: Threshold =
      committee match
        case Some(c) =>
          val active = c.members.count { case (cold, validUntil) =>
            committeeState.get(cold) match
              case Some(CommitteeAuth.Resigned(_)) => false
              case Some(_)                         => epoch <= validUntil
              case None                            => false
          }
          if isBootstrap(pp) || BigInt(active) >= pp.committeeMinSize then Required(c.threshold)
          else Unavailable
        case None => Unavailable
    a match
      case _: GovAction.NoConfidence | _: GovAction.UpdateCommittee => NotAllowed
      case GovAction.Info                                           => Unavailable
      case _                                                        => threshold

  def isCommitteeVotingAllowed(a: GovAction): Boolean = a match
    case _: GovAction.NoConfidence | _: GovAction.UpdateCommittee => false
    case _                                                        => true

  def isDRepVotingAllowed(a: GovAction): Boolean = true

  def isPoolVotingAllowed(a: GovAction): Boolean = a match
    case _: GovAction.NewConstitution | _: GovAction.TreasuryWithdrawals => false
    case GovAction.ParameterChange(_, u, _)                              => modifiedGroups(u)._2
    case _                                                               => true

  /** What ratification looks at: the pulser's snapshot of the DRep and pool distributions. */
  final case class Env(
      drepDistr: Map[DRep, BigInt],
      drepState: Map[Credential, DRepState],
      poolDistr: Map[Bytes, BigInt],
      totalPoolStake: BigInt,
      epoch: BigInt,
      committeeState: Map[Credential, CommitteeAuth],
      accounts: Map[Credential, Account],
      pools: Map[Bytes, PoolState]
  )

  private def ratio(n: BigInt, d: BigInt): Rational =
    if d <= 0 then Rational.zero else Rational(n, d)

  private def committeeAccepted(env: Env, st: EnactState, gas: GovActionState): Boolean =
    committeeThreshold(env.epoch, st.params, st.committee, env.committeeState, gas.action) match
      case Required(r) =>
        r.isZero || {
          val members = st.committee.map(_.members).getOrElse(Map.empty)
          val (yes, total) = members.foldLeft((BigInt(0), BigInt(0))) {
            case (acc @ (y, t), (member, expiry)) =>
              if env.epoch > expiry then acc
              else
                env.committeeState.get(member) match
                  case None | Some(CommitteeAuth.Resigned(_)) => acc
                  case Some(CommitteeAuth.Hot(hot)) =>
                    gas.committeeVotes.get(hot) match
                      case None               => (y, t + 1)
                      case Some(Vote.Abstain) => acc
                      case Some(Vote.No)      => (y, t + 1)
                      case Some(Vote.Yes)     => (y + 1, t + 1)
          }
          ratio(yes, total) >= r
        }
      case NotAllowed  => true
      case Unavailable => false

  /** How an unvoting pool votes: from the DRep its reward account delegates to. */
  private def defaultPoolVote(env: Env, pool: Bytes): Option[DRep] =
    env.pools
      .get(pool)
      .flatMap(p => env.accounts.get(p.params.rewardAccount.credential))
      .flatMap(_.drep)

  private def spoAccepted(env: Env, st: EnactState, gas: GovActionState): Boolean =
    spoThreshold(st.params, st.committee.isDefined, gas.action) match
      case Required(r) =>
        r.isZero || {
          val isHardFork = gas.action.isInstanceOf[GovAction.HardFork]
          val isNoConfidence = gas.action.isInstanceOf[GovAction.NoConfidence]
          val (yes, abstain) = env.poolDistr.foldLeft((BigInt(0), BigInt(0))) {
            case ((y, ab), (pool, stake)) =>
              gas.poolVotes.get(pool) match
                case None if isHardFork             => (y, ab)
                case None if isBootstrap(st.params) => (y, ab + stake)
                case None =>
                  defaultPoolVote(env, pool) match
                    case Some(DRep.AlwaysNoConfidence) if isNoConfidence => (y + stake, ab)
                    case Some(DRep.AlwaysAbstain)                        => (y, ab + stake)
                    case _                                               => (y, ab)
                case Some(Vote.Abstain) => (y, ab + stake)
                case Some(Vote.No)      => (y, ab)
                case Some(Vote.Yes)     => (y + stake, ab)
          }
          ratio(yes, env.totalPoolStake - abstain) >= r
        }
      case NotAllowed  => true
      case Unavailable => false

  private def drepAccepted(env: Env, st: EnactState, gas: GovActionState): Boolean =
    drepThreshold(st.params, st.committee.isDefined, gas.action) match
      case Required(r) =>
        r.isZero || {
          val (yes, total) = env.drepDistr.foldLeft((BigInt(0), BigInt(0))) {
            case (acc @ (y, t), (drep, stake)) =>
              drep match
                case DRep.Registered(cred) =>
                  env.drepState.get(cred) match
                    case None                            => acc
                    case Some(s) if env.epoch > s.expiry => acc
                    case Some(_) =>
                      gas.drepVotes.get(cred) match
                        case None               => (y, t + stake)
                        case Some(Vote.Yes)     => (y + stake, t + stake)
                        case Some(Vote.Abstain) => acc
                        case Some(Vote.No)      => (y, t + stake)
                case DRep.AlwaysNoConfidence =>
                  if gas.action.isInstanceOf[GovAction.NoConfidence] then (y + stake, t + stake)
                  else (y, t + stake)
                case DRep.AlwaysAbstain => acc
          }
          ratio(yes, total) >= r
        }
      case NotAllowed  => true
      case Unavailable => false

  private def delaying(a: GovAction): Boolean = a match
    case _: GovAction.NoConfidence | _: GovAction.HardFork | _: GovAction.UpdateCommittee |
        _: GovAction.NewConstitution =>
      true
    case _ => false

  private def priority(a: GovAction): Int = a match
    case _: GovAction.NoConfidence        => 0
    case _: GovAction.UpdateCommittee     => 1
    case _: GovAction.NewConstitution     => 2
    case _: GovAction.HardFork            => 3
    case _: GovAction.ParameterChange     => 4
    case _: GovAction.TreasuryWithdrawals => 5
    case GovAction.Info                   => 6

  private def prevActionAsExpected(gas: GovActionState, ids: PrevIds): Boolean =
    GovActions.lineage(gas.action).forall { case (purpose, parent) => parent == ids.get(purpose) }

  private def validCommitteeTerm(a: GovAction, pp: ProtocolParams, epoch: BigInt): Boolean = a match
    case GovAction.UpdateCommittee(_, _, added, _) =>
      added.values.forall(_ <= epoch + pp.committeeMaxTermLength)
    case _ => true

  private def canWithdraw(a: GovAction, treasury: BigInt): Boolean = a match
    case GovAction.TreasuryWithdrawals(ws, _) => ws.map(_._2).sum <= treasury
    case _                                    => true

  /** RATIFY over the proposals, in the ledger's priority order. */
  def ratify(env: Env, start: EnactState, proposals: Vector[GovActionState]): RatifyState =
    val ordered = proposals.sortBy(g => priority(g.action))
    val end = ordered.foldLeft(RatifyState(start, Vector.empty, Set.empty, delayed = false)) {
      (rs, gas) =>
        val st = rs.enact
        val action = gas.action
        if prevActionAsExpected(gas, st.prevIds) &&
          validCommitteeTerm(action, st.params, env.epoch) && !rs.delayed &&
          canWithdraw(action, st.treasury) &&
          committeeAccepted(env, st, gas) && spoAccepted(env, st, gas) &&
          drepAccepted(env, st, gas)
        then
          RatifyState(
            enact(st, gas.id, action),
            rs.enacted :+ gas,
            rs.expired,
            delaying(action)
          )
        else if gas.expiresAfter < env.epoch then rs.copy(expired = rs.expired + gas.id)
        else rs
    }
    end.copy(enact = end.enact.copy(treasury = 0))

  /** ENACT: the effect of one ratified action. */
  def enact(st: EnactState, id: GovActionId, a: GovAction): EnactState = a match
    case GovAction.ParameterChange(_, u, _) =>
      st.copy(
        params = ProtocolParams.update(st.params, u).getOrElse(st.params),
        prevIds = st.prevIds.copy(paramUpdate = Some(id))
      )
    case GovAction.HardFork(_, major, minor) =>
      st.copy(
        params = st.params.copy(protocolMajor = major, protocolMinor = minor),
        prevIds = st.prevIds.copy(hardFork = Some(id))
      )
    case GovAction.TreasuryWithdrawals(ws, _) =>
      val total = ws.map(_._2).sum
      st.copy(
        withdrawals = ws.foldLeft(st.withdrawals) { case (m, (acct, amount)) =>
          m.updated(acct.credential, m.getOrElse(acct.credential, BigInt(0)) + amount)
        },
        treasury = st.treasury - total
      )
    case GovAction.NoConfidence(_) =>
      st.copy(committee = None, prevIds = st.prevIds.copy(committee = Some(id)))
    case GovAction.UpdateCommittee(_, removed, added, threshold) =>
      val members = st.committee.fold(added)(c => (c.members -- removed) ++ added)
      st.copy(
        committee = Some(Committee(members, threshold)),
        prevIds = st.prevIds.copy(committee = Some(id))
      )
    case GovAction.NewConstitution(_, script, anchor) =>
      st.copy(
        constitution = Constitution(anchor, script),
        prevIds = st.prevIds.copy(constitution = Some(id))
      )
    case GovAction.Info => st
