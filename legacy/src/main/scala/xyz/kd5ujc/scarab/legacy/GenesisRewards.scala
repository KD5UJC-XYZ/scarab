// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.legacy

import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.ledger.*

/** FROZEN: the old rules' Shelley reward calculation (createRUpd), which the genesis state
  * ([[GenesisState]]) puts its first reward update through. Parameters are those of the previous
  * epoch (`esPrevPp`); from Babbage `d` is 0; before Allegra only one reward per account is kept.
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
    // `expectedBlocks` is floored (`PulsingReward.hs:127-129`); it was not, which differs from the
    // reference when (1 - d) * f * epochLength is not an integer. Keep the floor explicit so
    // genesis configurations need not rely on that product being integral.
    val expected = Rational(
      ((Rational.one - d) * Rational(globals.epochLength) * globals.activeSlotsCoeff).floor
    )
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
