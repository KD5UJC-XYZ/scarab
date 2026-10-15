// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.codec.Bytes

/** Conway-only parameters taken from the Conway genesis when the chain crosses into Conway. */
final case class ConwayParams(
    drepDeposit: BigInt,
    drepActivity: BigInt,
    govActionDeposit: BigInt,
    govActionLifetime: BigInt,
    minFeeRefScriptCostPerByte: Rational,
    /** The PlutusV3 cost model of the Conway genesis (`plutusV3CostModel`), if present. */
    plutusV3CostModel: Vector[BigInt] = Vector.empty,
    poolVotingThresholds: PoolVotingThresholds = PoolVotingThresholds.zero,
    drepVotingThresholds: DRepVotingThresholds = DRepVotingThresholds.zero,
    committeeMinSize: BigInt = 0,
    committeeMaxTermLength: BigInt = 0,
    /** The genesis committee (`None` only if the genesis has no committee section). */
    committee: Option[Committee] = None,
    constitution: Constitution = GovState.empty.constitution
)

/** Network constants the epoch rules depend on. `stabilityWindow` is 3k/f and `startRewards` the
  * number of slots into an epoch after which the reward calculation starts.
  */
final case class LedgerGlobals(
    maxSupply: BigInt,
    epochLength: BigInt,
    activeSlotsCoeff: Rational,
    network: Int,
    updateQuorum: Int = 5,
    stabilityWindow: BigInt = 0,
    startRewards: BigInt = 0,
    firstEpoch: BigInt = 0,
    conway: Option[ConwayParams] = None
)

/** NEWEPOCH/EPOCH: reward application, MIR, SNAP, pool parameter adoption, POOLREAP, parameter
  * update adoption (before Conway) or the dormant-epoch counter (Conway), followed by the reward
  * update for the epoch just entered.
  */
object Epoch:
  def boundary(state: LedgerState, epoch: BigInt, globals: LedgerGlobals): LedgerState =
    if state.params.protocolMajor >= 9 then conwayBoundary(state, epoch, globals)
    else preConwayBoundary(state, epoch, globals)

  /** Per-block TICK effects that depend on the slot: genesis delegation adoption, and the moment
    * the reward calculation starts (it freezes which accounts are registered).
    */
  def tick(
      state: LedgerState,
      slot: BigInt,
      epochStart: BigInt,
      globals: LedgerGlobals
  ): LedgerState =
    val adopted =
      if state.futureGenDelegs.isEmpty then state
      else
        val (due, later) = state.futureGenDelegs.partition(_.slot <= slot)
        if due.isEmpty then state
        else
          state.copy(
            genDelegs = due
              .sortBy(f => (f.slot, f.genesisKey.hex))
              .foldLeft(state.genDelegs)((m, f) => m.updated(f.genesisKey, f.pair)),
            futureGenDelegs = later
          )
    val registered =
      if adopted.rewardRegistered.isEmpty && adopted.params.protocolMajor < 9 &&
        slot > epochStart + globals.startRewards
      then adopted.copy(rewardRegistered = Some(adopted.accounts.keySet))
      else adopted
    // The ledger creates the reward update when a block arrives after the start-rewards slot; if
    // none does, the epoch pays nothing.
    if registered.rewardDraft.isDefined && slot > epochStart + globals.startRewards then
      registered.copy(
        rewardUpdate = registered.rewardDraft.map(prefilter(registered, _)),
        rewardDraft = None
      )
    else registered

  /** Before Babbage the calculation (`startStep`) skips rewards of accounts that are not registered
    * when it starts (`rewardRegistered`); what it skips stays in the reserves (`deltaR2`). The
    * reference reward update carries the filtered rewards, so this is done when it is created.
    */
  private def prefilter(state: LedgerState, ru: RewardUpdate): RewardUpdate =
    if state.previousParams.protocolMajor >= 7 || state.params.protocolMajor >= 9 then ru
    else
      val eligible = state.rewardRegistered.getOrElse(state.accounts.keySet)
      val (kept, dropped) = ru.rewards.partition { case (c, _) => eligible(c) }
      ru.copy(rewards = kept, deltaReserves = ru.deltaReserves + dropped.values.sum)

  private[ledger] def rotate(state: LedgerState, pointers: Boolean = true): LedgerState =
    state.copy(
      snapshots = Snapshots(
        mark = Stake.snapshot(state, pointers),
        set = state.snapshots.mark,
        go = state.snapshots.set,
        fee = state.fees
      )
    )

  private[ledger] def adoptPools(state: LedgerState): LedgerState =
    state.copy(
      pools = state.futurePools.foldLeft(state.pools) { case (ps, (id, p)) =>
        ps.updatedWith(id)(_.map(_.copy(params = p)))
      },
      futurePools = Map.empty
    )

  private def preConwayBoundary(
      state: LedgerState,
      epoch: BigInt,
      globals: LedgerGlobals
  ): LedgerState =
    val rewarded = state.rewardUpdate.fold(state)(applyRewards(state, _))
    val paid = Mir.apply(rewarded)
    // The boundary that enters Conway runs under the Conway rules (the era translation precedes
    // it), and those ignore pointer addresses: the snapshot taken here does not count them
    // (checked against Preview: a pointer address worth 35,553 ada at epoch 646).
    val enteringConway =
      paid.params.protocolMajor < 9 &&
        PpupRules.newEpoch(paid, globals).params.protocolMajor >= 9
    val reaped = poolReap(adoptPools(rotate(paid, pointers = !enteringConway)), epoch)
    val updated = PpupRules.newEpoch(reaped, globals)
    val next = updated.copy(
      epoch = epoch,
      prevParams = Some(reaped.params),
      blocksPrevious = reaped.blocksCurrent,
      blocksCurrent = Map.empty,
      rewardUpdate = None,
      rewardRegistered = None,
      poolDistribution = reaped.snapshots.set
    )
    val entered =
      if next.params.protocolMajor >= 9 then Translate.toConway(next, globals) else next
    entered.copy(rewardUpdate = None, rewardDraft = Some(Rewards.update(entered, globals)))

  private def conwayBoundary(
      state: LedgerState,
      epoch: BigInt,
      globals: LedgerGlobals
  ): LedgerState =
    val rewarded = state.rewardUpdate.fold(state)(applyRewards(state, _))
    val reaped = poolReap(adoptPools(rotate(rewarded)), epoch)
    val entered = ConwayEpoch(reaped, epoch)
    entered.copy(rewardUpdate = None, rewardDraft = Some(Rewards.update(entered, globals)))

  /** Sum of all outstanding deposits: stake credentials, pools and DReps. */
  def obligation(state: LedgerState): BigInt =
    state.accounts.valuesIterator.map(_.deposit).sum + state.pools.valuesIterator
      .map(_.deposit)
      .sum +
      state.dreps.valuesIterator.map(_.deposit).sum + state.gov.proposals.totalDeposits

  /** Apply a reward update: rewards of credentials that are no longer registered go to the treasury
    * (`filterAllRewards'`).
    */
  private def applyRewards(state: LedgerState, ru: RewardUpdate): LedgerState =
    val (registered, unregistered) = ru.rewards.partition { case (c, _) =>
      state.accounts.contains(c)
    }
    state.copy(
      treasury = state.treasury + ru.deltaTreasury + unregistered.values.sum,
      reserves = state.reserves + ru.deltaReserves,
      fees = state.fees + ru.deltaFees,
      accounts = registered.foldLeft(state.accounts) { case (as, (c, r)) =>
        as.updatedWith(c)(_.map(a => a.copy(balance = a.balance + r)))
      }
    )

  private[ledger] def poolReap(state: LedgerState, epoch: BigInt): LedgerState =
    val retired = state.retiring.collect { case (id, e) if e == epoch => id }.toSet
    if retired.isEmpty then state
    else
      val refunds = retired.toVector.flatMap(id =>
        state.pools.get(id).map(p => p.params.rewardAccount.credential -> p.deposit)
      )
      val (paid, unpaid) = refunds.partition { case (c, _) => state.accounts.contains(c) }
      state.copy(
        pools = state.pools -- retired,
        retiring = state.retiring -- retired,
        deposited = state.deposited - refunds.map(_._2).sum,
        treasury = state.treasury + unpaid.map(_._2).sum,
        accounts = paid.foldLeft(state.accounts.map { case (c, a) =>
          c -> (if a.pool.exists(retired) then a.copy(pool = None) else a)
        }) { case (as, (c, d)) => as.updatedWith(c)(_.map(a => a.copy(balance = a.balance + d))) }
      )

/** MIR: instantaneous rewards and pot-to-pot transfers collected by certificates are applied at the
  * start of the next epoch, to registered accounts only and only if both pots can pay.
  */
object Mir:
  def apply(state: LedgerState): LedgerState =
    val ir = state.mir
    if ir == InstantRewards.empty then state
    else
      val toReserves = ir.reserves.filter { case (c, _) => state.accounts.contains(c) }
      val toTreasury = ir.treasury.filter { case (c, _) => state.accounts.contains(c) }
      val totalR = toReserves.values.sum
      val totalT = toTreasury.values.sum
      val availableR = state.reserves + ir.deltaReserves
      val availableT = state.treasury + ir.deltaTreasury
      val cleared = state.copy(mir = InstantRewards.empty)
      if totalR <= availableR && totalT <= availableT then
        val paid = (toReserves.iterator ++ toTreasury.iterator).foldLeft(state.accounts) {
          case (as, (c, amount)) =>
            as.updatedWith(c)(_.map(a => a.copy(balance = a.balance + amount)))
        }
        cleared.copy(
          accounts = paid,
          reserves = state.reserves - totalR + ir.deltaReserves,
          treasury = state.treasury - totalT + ir.deltaTreasury
        )
      else cleared

/** Era translation into Conway at the epoch boundary where the protocol version reaches 9. The
  * Conway-only parameters come from the Conway genesis; the DRep state starts empty and the
  * pre-Conway update, instantaneous reward and pointer state is dropped. The genesis delegations
  * stay in the delegation state, unused, as in the reference node (found by the db-analyser
  * comparison, roadmap K1, which also checks the rest of this translation).
  */
object Translate:
  def toConway(state: LedgerState, globals: LedgerGlobals): LedgerState =
    val params = globals.conway.fold(state.params)(c =>
      state.params.copy(
        drepDeposit = c.drepDeposit,
        drepActivity = c.drepActivity,
        govActionDeposit = c.govActionDeposit,
        govActionLifetime = c.govActionLifetime,
        minFeeRefScriptCostPerByte = c.minFeeRefScriptCostPerByte,
        poolVotingThresholds = c.poolVotingThresholds,
        drepVotingThresholds = c.drepVotingThresholds,
        committeeMinSize = c.committeeMinSize,
        committeeMaxTermLength = c.committeeMaxTermLength,
        costModels =
          if c.plutusV3CostModel.isEmpty then state.params.costModels
          else state.params.costModels.updated(2, c.plutusV3CostModel)
      )
    )
    val gov = globals.conway.fold(GovState.empty)(c =>
      GovState.empty.copy(committee = c.committee, constitution = c.constitution)
    )
    val translated = state.copy(
      params = params,
      futureGenDelegs = Vector.empty,
      ppup = Ppup.empty,
      mir = InstantRewards.empty,
      ptrs = Map.empty,
      dreps = Map.empty,
      gov = gov,
      committeeState = Map.empty,
      donation = 0,
      // The epoch transition into the first Conway epoch runs under the Conway EPOCH rule (the era
      // translation precedes it), and with no governance proposals that epoch is dormant.
      dormantEpochs = 1,
      rewardRegistered = None
    )
    val counted = translated.copy(deposited = Epoch.obligation(translated))
    counted.copy(gov =
      counted.gov.copy(pulsed = Some(ConwayEpoch.freshPulser(counted, counted.epoch)))
    )

/** The Shelley reward calculation (createRUpd). Parameters are those of the previous epoch
  * (`esPrevPp`); from Babbage `d` is 0; before Allegra only one reward per account is kept.
  */
object Rewards:
  private val Eighty = Rational(4, 5)

  private final case class Reward(
      credential: Credential,
      amount: BigInt,
      leader: Boolean,
      pool: Bytes
  )

  def update(state: LedgerState, globals: LedgerGlobals): RewardUpdate =
    val pp = state.previousParams
    val d = pp.effectiveD
    val go = state.snapshots.go
    val blocks = state.blocksPrevious
    val blocksTotal = blocks.values.sum
    val expected = (Rational.one - d) * Rational(globals.epochLength) * globals.activeSlotsCoeff
    val eta =
      if d >= Eighty then Rational.one
      else if expected.isZero then Rational.zero
      else Rational(blocksTotal) / expected
    val deltaR1 = (Rational.one.min(eta) * pp.rho * Rational(state.reserves)).floor
    val rewardPot = state.snapshots.fee + deltaR1
    val deltaT1 = (pp.tau * Rational(rewardPot)).floor
    val r = rewardPot - deltaT1
    val totalStake = globals.maxSupply - state.reserves
    val activeStake = go.activeStake
    val membersOf = go.delegations.groupMap(_._2)(_._1)

    val rewards: Vector[Reward] =
      if totalStake <= 0 then Vector.empty
      else
        go.pools.toVector.flatMap { case (poolId, params) =>
          val members = membersOf.getOrElse(poolId, Iterable.empty).map { c =>
            c -> go.stake.getOrElse(c, BigInt(0))
          }
          val poolStake = members.map(_._2).sum
          val blocksN = blocks.getOrElse(poolId, BigInt(0))
          if poolStake == 0 || blocksN == 0 then Vector.empty
          else
            val sigma = Rational(poolStake, totalStake)
            val sigmaA =
              if activeStake == 0 then Rational.zero else Rational(poolStake, activeStake)
            // The owners' stake counts only where they delegate to this very pool (`poolStake` of
            // the ledger restricts the stake map to the pool's delegators before `rewardOnePool`).
            val ownerStake = members.iterator.collect {
              case (Credential.Key(h), stake) if params.owners(h) => stake
            }.sum
            val pledgeRatio = Rational(params.pledge, totalStake)
            val maxP =
              if params.pledge <= ownerStake then maxPool(pp.a0, pp.nOpt, r, sigma, pledgeRatio)
              else BigInt(0)
            val beta = Rational(blocksN, blocksTotal.max(1))
            val performance =
              if d >= Eighty then Rational.one
              else if sigmaA.isZero then Rational.zero
              else beta / sigmaA
            val poolR = (performance * Rational(maxP)).floor
            val s = Rational(ownerStake, totalStake)
            val leader = leaderReward(poolR, params, s, sigma)
            val memberRewards = members.toVector.collect {
              case (c, stake) if !(c match
                    case Credential.Key(h) => params.owners(h)
                    case _                 => false
                  ) =>
                Reward(
                  c,
                  memberReward(poolR, params, Rational(stake, totalStake), sigma),
                  false,
                  poolId
                )
            }
            Reward(params.rewardAccount.credential, leader, true, poolId) +:
              memberRewards.filter(_.amount > 0)
        }

    // Zero rewards have no effect when applied; the ledger omits them.
    val aggregated: Map[Credential, BigInt] =
      if pp.protocolMajor >= 3 then
        rewards.groupMapReduce(_.credential)(_.amount)(_ + _).filter(_._2 != 0)
      else
        // Before Allegra a credential earning several rewards keeps only the smallest in the
        // ledger's `Ord Reward` (`filterRewards` takes `Set.deleteFindMin`): leader rewards sort
        // before member rewards, then by pool id.
        rewards
          .groupBy(_.credential)
          .view
          .mapValues(rs => rs.minBy(x => (!x.leader, x.pool.hex)).amount)
          .toMap
          .filter(_._2 != 0)
    val distributed = aggregated.values.sum
    val deltaR2 = r - distributed
    RewardUpdate(
      deltaTreasury = deltaT1,
      deltaReserves = -deltaR1 + deltaR2,
      rewards = aggregated,
      deltaFees = -state.snapshots.fee
    )

  /** maxPool': optimal pool reward for relative stake `sigma` and relative pledge `p`. */
  def maxPool(a0: Rational, nOpt: BigInt, r: BigInt, sigma: Rational, p: Rational): BigInt =
    if nOpt <= 0 then BigInt(0)
    else
      val z0 = Rational(1, nOpt)
      val sigma1 = sigma.min(z0)
      val p1 = p.min(z0)
      val factor1 = Rational(r) / (Rational.one + a0)
      val factor4 = (z0 - sigma1) / z0
      val factor3 = (sigma1 - p1 * factor4) / z0
      val factor2 = sigma1 + p1 * a0 * factor3
      (factor1 * factor2).floor

  def leaderReward(poolR: BigInt, p: PoolParams, s: Rational, sigma: Rational): BigInt =
    if poolR <= p.cost then poolR
    else
      p.cost + (Rational(poolR - p.cost) * (p.margin + (Rational.one - p.margin) * s / sigma)).floor

  def memberReward(poolR: BigInt, p: PoolParams, t: Rational, sigma: Rational): BigInt =
    if poolR <= p.cost then BigInt(0)
    else (Rational(poolR - p.cost) * (Rational.one - p.margin) * t / sigma).floor
