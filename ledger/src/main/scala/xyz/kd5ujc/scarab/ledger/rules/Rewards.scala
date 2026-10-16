// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import cats.syntax.all.*
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.ledger.state.*

/** The reward calculation (`createRUpd`: `startStep` and `completeRupd` of `Shelley/LedgerState/
  * PulsingReward.hs:89,242,284`, `mkPoolRewardInfo` `Rewards.hs:331`, `rewardOnePoolMember` `291`
  * and the reward formulas of `Shelley/Rewards.hs:86-190`), in exact rational arithmetic.
  *
  * Haskell spreads the calculation over the blocks of the epoch (the pulser) and the node computes
  * it in one go when the first block past the start of rewards arrives (design D8: the pulser is a
  * prefix of the credentials in map order, so the result is the same); what differs from Haskell is
  * therefore when, not what. The result is attributed: each credential's rewards are a set of
  * [[Reward]] s that name the pool they came from and whether as leader or as member, so the
  * per-pool accounting is there (the node's own reward update has only the sum per credential).
  *
  * The non-myopic ranking (`updateNonMyopic`, the likelihoods and the pot) is left as the epoch
  * state has it: it is a gap of the node (`Gap.NonMyopic`, J3.6).
  */
object Rewards:

  /** `x %? y` (`BaseTypes.hs`): the ratio, zero where the denominator is. */
  private def ratio(n: BigInt, d: BigInt): Rational =
    if d.signum == 0 then Rational.zero else Rational(n, d)

  private val Eighty = Rational(4, 5)

  /** `hardforkAllegraAggregatedRewards` (`HardForks.hs`): from protocol version 3 a credential gets
    * all its rewards.
    */
  private def aggregated(major: BigInt): Boolean = major >= 3

  /** `hardforkBabbageForgoRewardPrefilter`: from 7 the rewards of accounts that were not registered
    * when the calculation started are not filtered out.
    */
  private def forgoPrefilter(major: BigInt): Boolean = major >= 7

  /** `filterRewards` (`Rewards.hs:170`): the rewards that are delivered. Before Allegra a
    * credential earning from several sources got only the least (`Set.deleteFindMin`; the leader
    * reward sorts before the member rewards, then by pool).
    */
  private def delivered(major: BigInt, rewards: RewardSet): Iterable[Reward] =
    if aggregated(major) then rewards.rewards else rewards.rewards.headOption

  /** `aggregateRewards` (`Rewards.hs:186`): the coin each credential receives, in the order of the
    * credential. Amounts are sums of the non-negative rewards, so they are coins.
    */
  def aggregate(
      major: BigInt,
      rs: SortedMap[Credential[Staking], RewardSet]
  ): Either[VocabError, Vector[(Credential[Staking], Coin)]] =
    rs.iterator
      .map((c, set) => c -> delivered(major, set).foldLeft(BigInt(0))(_ + _.rewardAmount.value))
      .toVector
      .traverse((c, n) => Coin.fromBigInt(n).map(c -> _))

  /** `sumRewards` (`Rewards.hs:159`). */
  def total(major: BigInt, rs: SortedMap[Credential[Staking], RewardSet]): BigInt =
    rs.valuesIterator.foldLeft(BigInt(0))((sum, set) =>
      sum + delivered(major, set).foldLeft(BigInt(0))(_ + _.rewardAmount.value)
    )

  /** The pool-specific values of the calculation (`PoolRewardInfo`, `Rewards.hs:242`). */
  private final case class PoolInfo(
      pool: KeyHash[StakePool],
      snapshot: StakePoolSnapShot,
      sigma: Rational,
      pot: BigInt,
      leader: BigInt
  )

  /** `maxPool'` (`State/SnapShots.hs:119`): the most a pool of relative stake `sigma` and relative
    * pledge `pledge` can earn of the pot `r`.
    */
  def maxPool(a0: Rational, nOpt: BigInt, r: BigInt, sigma: Rational, pledge: Rational): BigInt =
    if nOpt <= 0 then BigInt(0)
    else
      val z0 = Rational(1, nOpt)
      val sigma1 = sigma.min(z0)
      val p1 = pledge.min(z0)
      val factor1 = Rational(r) / (Rational.one + a0)
      val factor4 = (z0 - sigma1) / z0
      val factor3 = (sigma1 - p1 * factor4) / z0
      val factor2 = sigma1 + p1 * a0 * factor3
      (factor1 * factor2).floor

  /** `calcStakePoolOperatorReward` (`Rewards.hs:110`). */
  def operatorReward(
      pot: BigInt,
      cost: BigInt,
      margin: Rational,
      owners: Rational,
      sigma: Rational
  ): BigInt =
    if pot <= cost then pot
    else cost + (Rational(pot - cost) * (margin + (Rational.one - margin) * owners / sigma)).floor

  /** `calcStakePoolMemberReward` (`Rewards.hs:140`). */
  def memberReward(
      pot: BigInt,
      cost: BigInt,
      margin: Rational,
      share: Rational,
      sigma: Rational
  ): BigInt =
    if pot <= cost then BigInt(0)
    else (Rational(pot - cost) * (Rational.one - margin) * share / sigma).floor

  /** The decentralisation parameter of the reward calculation (`ppDG`): Babbage removed `d`. */
  private def decentralisation[E <: EraTag](p: xyz.kd5ujc.scarab.ledger.ProtocolParams)(using
      era: ShelleyBasedEra[E]
  ): Rational = era match
    case ShelleyBasedEra.SBabbage | ShelleyBasedEra.SConway => Rational.zero
    case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary |
        ShelleyBasedEra.SAlonzo =>
      p.d

  /** `createRUpd` (`PulsingReward.hs:284`) for the epoch state `es` and the blocks of the previous
    * epoch `blocks`: the reward update that is applied at the next boundary.
    */
  def update[E <: EraTag](es: EpochState[E], blocks: BlocksMade, globals: Globals, epoch: BigInt)(
      using ShelleyBasedEra[E]
  ): Either[VocabError, RewardUpdate] =
    val pr = EraLedger.prevPParams(es.ls.utxo.gov)
    val major = pr.protocolMajor
    val go = es.snapshots.go
    val reserves = es.chainAccount.reserves.value
    val fee = es.snapshots.fee.value
    val d = decentralisation[E](pr)
    val slotsPerEpoch = globals.geometry.epochSlots(epoch)
    val expectedBlocks =
      ((Rational.one - d) * globals.activeSlotCoeff * Rational(slotsPerEpoch)).floor
    val blocksMade = blocks.blocks.valuesIterator.foldLeft(BigInt(0))(_ + _.toBigInt)
    val eta =
      if d >= Eighty then Rational.one
      else ratio(blocksMade, expectedBlocks)
    val deltaR1 = (Rational.one.min(eta) * pr.rho * Rational(reserves)).floor
    val potTotal = fee + deltaR1
    val deltaT1 = (pr.tau * Rational(potTotal)).floor
    val pot = potTotal - deltaT1
    val totalStake = globals.maxLovelaceSupply - reserves
    val totalActive = go.totalActiveStake.value.value
    val registered: Credential[Staking] => Boolean = EraLedger.isRegistered(es.ls, _)

    // `mkPoolRewardInfo` for the pools that made blocks
    val infos: Map[KeyHash[StakePool], PoolInfo] =
      go.pools.iterator.flatMap { (id, spss) =>
        blocks.blocks.get(id).map { made =>
          val poolStake = spss.stake.toBigInt
          val sigma = ratio(poolStake, totalStake)
          val sigmaA = ratio(poolStake, totalActive)
          val pledge = spss.pledge.value
          val ownersStake = spss.selfDelegatedOwnersStake.value
          val relativePledge = ratio(pledge, totalStake)
          val maxP =
            if pledge <= ownersStake then maxPool(pr.a0, pr.nOpt, pot, sigma, relativePledge)
            else BigInt(0)
          val performance =
            if sigmaA.isZero then Rational.zero
            else if d < Eighty then ratio(made.toBigInt, blocksMade.max(1)) / sigmaA
            else Rational.one
          val poolPot = (performance * Rational(maxP)).floor
          val leader = operatorReward(
            poolPot,
            spss.cost.value,
            spss.margin,
            ratio(ownersStake, totalStake),
            sigma
          )
          id -> PoolInfo(id, spss, sigma, poolPot, leader)
        }
      }.toMap

    // `rewardOnePoolMember` over the active stake, and the leader rewards (`collectLRs`)
    def reward(kind: RewardType, pool: KeyHash[StakePool], amount: BigInt) =
      Coin.fromBigInt(amount).map(Reward(kind, pool, _))
    val members: Iterator[Either[VocabError, (Credential[Staking], Reward)]] =
      go.activeStake.entries.iterator.flatMap { (credential, swd) =>
        infos.get(swd.delegation).flatMap { info =>
          val owner = credential match
            case Credential.KeyHashObj(h)    => info.snapshot.selfDelegatedOwners.contains(h)
            case Credential.ScriptHashObj(_) => false
          val amount = memberReward(
            info.pot,
            info.snapshot.cost.value,
            info.snapshot.margin,
            ratio(swd.stake.value.toBigInt, totalStake),
            info.sigma
          )
          Option.when(
            totalStake.signum > 0 && (forgoPrefilter(major) || registered(credential)) &&
              !owner && amount.signum != 0
          )(reward(RewardType.MemberReward, info.pool, amount).map(credential -> _))
        }
      }
    val leaders: Iterator[Either[VocabError, (Credential[Staking], Reward)]] =
      infos.valuesIterator.flatMap { info =>
        val account = info.snapshot.accountId
        Option.when(forgoPrefilter(major) || registered(account))(
          reward(RewardType.LeaderReward, info.pool, info.leader).map(account -> _)
        )
      }
    (members ++ leaders).toVector.sequence.map { earned =>
      val rs: SortedMap[Credential[Staking], RewardSet] =
        Sorted.map(earned.groupMap(_._1)(_._2).map((c, rewards) => c -> RewardSet(rewards)))
      val deltaR2 = pot - total(major, rs)
      RewardUpdate(
        DeltaCoin(deltaT1),
        DeltaCoin(-deltaR1 + deltaR2),
        rs,
        DeltaCoin(-fee),
        es.nonMyopic
      )
    }
