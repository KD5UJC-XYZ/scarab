// SPDX-License-Identifier: Apache-2.0
package lab.node.ledger

import lab.cbor.Bytes

/** Network constants the epoch rules depend on. */
final case class LedgerGlobals(
    maxSupply: BigInt,
    epochLength: BigInt,
    activeSlotsCoeff: Rational,
    network: Int
)

/** NEWEPOCH/EPOCH: reward application, SNAP, pool parameter adoption, POOLREAP and the Conway
  * dormant-epoch counter, followed by the reward update for the epoch just entered.
  */
object Epoch:
  def boundary(state: LedgerState, epoch: BigInt, globals: LedgerGlobals): LedgerState =
    val rewarded = state.rewardUpdate.fold(state)(applyRewards(state, _))
    val snapshotted = rewarded.copy(
      snapshots = Snapshots(
        mark = Stake.snapshot(rewarded),
        set = rewarded.snapshots.mark,
        go = rewarded.snapshots.set,
        fee = rewarded.fees
      )
    )
    val adopted = snapshotted.copy(
      pools = snapshotted.futurePools.foldLeft(snapshotted.pools) { case (ps, (id, p)) =>
        ps.updatedWith(id)(_.map(_.copy(params = p)))
      },
      futurePools = Map.empty
    )
    val reaped = poolReap(adopted, epoch)
    // Conway EPOCH: `deposited` becomes the total obligation; without governance proposals every
    // epoch is dormant.
    val next = reaped.copy(
      epoch = epoch,
      deposited = obligation(reaped),
      dormantEpochs = reaped.dormantEpochs + 1,
      blocksPrevious = reaped.blocksCurrent,
      blocksCurrent = Map.empty,
      rewardUpdate = None,
      poolDistribution = reaped.snapshots.set
    )
    next.copy(rewardUpdate = Some(Rewards.update(next, globals)))

  /** Sum of all outstanding deposits: stake credentials, pools and DReps. */
  def obligation(state: LedgerState): BigInt =
    state.accounts.valuesIterator.map(_.deposit).sum + state.pools.valuesIterator
      .map(_.deposit)
      .sum +
      state.dreps.valuesIterator.map(_.deposit).sum

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

  private def poolReap(state: LedgerState, epoch: BigInt): LedgerState =
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

/** The Shelley reward calculation (createRUpd) with Babbage/Conway semantics: d = 0, no
  * registration prefilter, rewards for unregistered accounts go to the treasury on application.
  */
object Rewards:
  def update(state: LedgerState, globals: LedgerGlobals): RewardUpdate =
    val pp = state.params
    val go = state.snapshots.go
    val blocks = state.blocksPrevious
    val blocksTotal = blocks.values.sum
    val expected = Rational(globals.epochLength) * globals.activeSlotsCoeff
    val eta = if expected.isZero then Rational.zero else Rational(blocksTotal) / expected
    val deltaR1 = (Rational.one.min(eta) * pp.rho * Rational(state.reserves)).floor
    val rewardPot = state.snapshots.fee + deltaR1
    val deltaT1 = (pp.tau * Rational(rewardPot)).floor
    val r = rewardPot - deltaT1
    val totalStake = globals.maxSupply - state.reserves
    val activeStake = go.activeStake

    val rewards: Vector[(Credential, BigInt)] =
      if totalStake <= 0 then Vector.empty
      else
        go.pools.toVector.flatMap { case (poolId, params) =>
          val members = go.delegations.collect {
            case (c, p) if p == poolId => c -> go.stake.getOrElse(c, BigInt(0))
          }
          val poolStake = members.values.sum
          val blocksN = blocks.getOrElse(poolId, BigInt(0))
          if poolStake == 0 || blocksN == 0 then Vector.empty
          else
            val sigma = Rational(poolStake, totalStake)
            val sigmaA =
              if activeStake == 0 then Rational.zero else Rational(poolStake, activeStake)
            val ownerStake = members.collect {
              case (Credential.Key(h), s) if params.owners(h) => s
            }.sum
            val pledgeRatio = Rational(params.pledge, totalStake)
            val maxP =
              if params.pledge <= ownerStake then maxPool(pp.a0, pp.nOpt, r, sigma, pledgeRatio)
              else BigInt(0)
            val beta = Rational(blocksN, blocksTotal.max(1))
            val performance = if sigmaA.isZero then Rational.zero else beta / sigmaA
            val poolR = (performance * Rational(maxP)).floor
            val s = Rational(ownerStake, totalStake)
            val leader = leaderReward(poolR, params, s, sigma)
            val memberRewards = members.toVector.collect {
              case (c, stake) if !(c match
                    case Credential.Key(h) => params.owners(h)
                    case _                 => false
                  ) =>
                c -> memberReward(poolR, params, Rational(stake, totalStake), sigma)
            }
            (params.rewardAccount.credential -> leader) +: memberRewards.filter(_._2 > 0)
        }

    // Zero rewards have no effect when applied; the ledger omits them.
    val aggregated = rewards.groupMapReduce(_._1)(_._2)(_ + _).filter(_._2 != 0)
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
