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
  * The pure start/pulse/complete machine mirrors the reference: captured inputs are immutable, and
  * each normal pulse consumes a bounded prefix in credential order. The result is attributed: each
  * credential's rewards are a set of [[Reward]] s that name the pool they came from and whether as
  * leader or as member. The native update retains this per-pool attribution through application.
  *
  * Pool-performance likelihoods use the reference Double computation rounded to Float; completion
  * combines their captured values with previous history, including imported updates.
  */
object Rewards:

  /** `x %? y` (`BaseTypes.hs`): the ratio, zero where the denominator is. */
  private def ratio(n: BigInt, d: BigInt): Rational =
    if d.signum == 0 then Rational.zero else Rational(n, d)

  private val Eighty = Rational(4, 5)

  // The reference converts each rational directly to Double, rather than rounding its numerator
  // and denominator separately. Eighty decimal digits distinguish the Double rounding boundaries
  // for the bounded ledger stake/parameter fractions (Word64 numerator and denominator).
  private val doublePrecision = new java.math.MathContext(80)
  private def asDouble(r: Rational): Double =
    new java.math.BigDecimal(r.numerator.bigInteger)
      .divide(new java.math.BigDecimal(r.denominator.bigInteger), doublePrecision)
      .doubleValue

  /** `leaderProbability` and `likelihood` (`PoolRank.hs:125-178`, shelley 1.19.0.1). Every snapshot
    * pool contributes, including pools that produced no blocks. Keep the reference's operation
    * order, Double logarithms and final Float rounding; this is ranking data, not monetary maths.
    */
  private def likelihood(
      made: BigInt,
      stake: Rational,
      active: Double,
      d: Double,
      slots: BigInt
  ): Likelihood =
    val t = (1.0 - math.pow(1.0 - active, asDouble(stake))) * (1.0 - d)
    val n = made.toDouble
    // `unEpochSize` subtraction is Word64 subtraction in the reference.
    val m = ((slots - made) & ((BigInt(1) << 64) - 1)).toDouble
    Likelihood(Vector.tabulate(100) { i =>
      val x = (i.toDouble + 0.5) / 100.0
      LogWeight((n * math.log(x) + m * math.log(1.0 - t * x)).toFloat)
    })

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

  /** `aggregateRewards` (`Rewards.hs:186`): sum Coin amounts for each credential in its order. */
  def aggregate(
      major: BigInt,
      rs: Iterable[(Credential[Staking], RewardSet)]
  ): Vector[(Credential[Staking], Coin)] =
    rs.iterator.map { (credential, rewards) =>
      credential -> Coin(delivered(major, rewards).foldLeft(BigInt(0))(_ + _.rewardAmount.value))
    }.toVector

  /** `sumRewards` (`Rewards.hs:159`). */
  def total(major: BigInt, rs: Iterable[(Credential[Staking], RewardSet)]): BigInt =
    rs.iterator.foldLeft(BigInt(0))((sum, entry) =>
      sum + delivered(major, entry._2).foldLeft(BigInt(0))(_ + _.rewardAmount.value)
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
  def start[E <: EraTag](es: EpochState[E], blocks: BlocksMade, globals: Globals, epoch: BigInt)(
      using ShelleyBasedEra[E]
  ): Either[VocabError, PulsingRewUpdate] =
    val pr = EraLedger.prevPParams(es.ls.utxo.gov)
    val major = pr.protocolMajor
    val go = es.snapshots.go
    val reserves = es.chainAccount.reserves.value
    val fee = es.snapshots.fee.value
    val d = decentralisation[E](pr)
    val activeProbability = asDouble(globals.activeSlotCoeff)
    val decentralisationProbability = asDouble(d)
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
    val registered = EraLedger.registered(es.ls)

    // `mkPoolRewardInfo`: only block-producing pools participate in monetary rewards.
    val infos = go.pools.iterator.toVector.flatTraverse { (id, spss) =>
      blocks.blocks.get(id).toVector.traverse { made =>
        val sigma = ratio(spss.stake.toBigInt, totalStake)
        val sigmaA = ratio(spss.stake.toBigInt, totalActive)
        val ownersStake = spss.selfDelegatedOwnersStake.value
        val maxP =
          if spss.pledge.value <= ownersStake then
            maxPool(pr.a0, pr.nOpt, pot, sigma, ratio(spss.pledge.value, totalStake))
          else BigInt(0)
        val performance =
          if sigmaA.isZero then Rational.zero
          else if d < Eighty then ratio(made.toBigInt, blocksMade.max(1)) / sigmaA
          else Rational.one
        val poolPot = (performance * Rational(maxP)).floor
        for
          blockCount <- RewardBlockCount.fromBigInt(made.toBigInt)
          rewardPot = Coin(poolPot)
          leader = Coin(
            operatorReward(
              poolPot,
              spss.cost.value,
              spss.margin,
              ratio(ownersStake, totalStake),
              sigma
            )
          )
        yield id -> PoolRewardInfo(sigma, rewardPot, spss, blockCount, LeaderOnlyReward(id, leader))
      }
    }
    for
      version <- ProtVer.fromBigInt(pr.protocolMajor, pr.protocolMinor)
      pools <- infos.map(Sorted.map(_))
      leaders = pools.valuesIterator.foldLeft(Sorted.empty[Credential[Staking], RewardSet]) {
        (rs, info) =>
          val account = info.snapshot.accountId
          if forgoPrefilter(major) || registered.contains(account) then
            val reward = Reward(RewardType.LeaderReward, info.leader.pool, info.leader.amount)
            rs.updated(account, RewardSet(rs.getOrElse(account, RewardSet.empty).rewards + reward))
          else rs
      }
      security <- RewardPulseSize.fromBigInt(globals.securityParameter)
      size <- RewardPulseSize.fromBigInt(
        ((BigInt(go.activeStake.entries.size) + 4 * BigInt(security.value) - 1) /
          (4 * BigInt(security.value))).max(1)
      )
      stake = Coin(totalStake)
      deltaR = Coin(deltaR1)
      r = Coin(pot)
      deltaT = Coin(deltaT1)
    yield PulsingRewUpdate.Pulsing(
      RewardSnapShot(
        es.snapshots.fee,
        version,
        es.nonMyopic,
        deltaR,
        r,
        deltaT,
        go.pools.transform { (id, pool) =>
          likelihood(
            blocks.blocks.get(id).fold(BigInt(0))(_.toBigInt),
            ratio(pool.stake.toBigInt, totalStake),
            activeProbability,
            decentralisationProbability,
            slotsPerEpoch
          )
        },
        leaders
      ),
      RewardPulser(
        size,
        RewardFreeVars(registered, stake, version, pools),
        go.activeStake.entries,
        RewardAns.empty
      )
    )

  private def member(
      free: RewardFreeVars,
      credential: Credential[Staking],
      swd: StakeWithDelegation
  ): Either[VocabError, Option[Reward]] =
    free.pools.get(swd.delegation) match
      case None => Right(None)
      case Some(info) =>
        val owner = credential match
          case Credential.KeyHashObj(h)    => info.snapshot.selfDelegatedOwners.contains(h)
          case Credential.ScriptHashObj(_) => false
        if free.totalStake.value == 0 || owner ||
          (!forgoPrefilter(free.protocolVersion.major.toBigInt) && !free.registered.contains(
            credential
          ))
        then Right(None)
        else if info.pot.value > info.snapshot.cost.value && info.relativeStake.numerator.signum <= 0
        then Left(VocabError.InvalidRewardRelativeStake(info.relativeStake))
        else
          val amount = memberReward(
            info.pot.value,
            info.snapshot.cost.value,
            info.snapshot.margin,
            ratio(swd.stake.value.toBigInt, free.totalStake.value),
            info.relativeStake
          )
          if amount == 0 then Right(None)
          else Right(Some(Reward(RewardType.MemberReward, swd.delegation, Coin(amount))))

  private def members(
      pulser: RewardPulser,
      source: Iterable[(Credential[Staking], StakeWithDelegation)]
  ): Either[VocabError, RewardAns] =
    source.iterator.toVector.foldLeftM(pulser.answer.copy(recent = Sorted.empty)) {
      case (answer, (credential, swd)) =>
        member(pulser.free, credential, swd).map {
          case None => answer
          case Some(reward) =>
            val single = SingletonReward(reward)
            RewardAns(
              answer.accumulated.updated(credential, single),
              answer.recent.updated(credential, single.asSet)
            )
        }
    }

  /** `pulseStep`: the call after the last member pulse completes the update. */
  def pulse(update: PulsingRewUpdate): Either[VocabError, PulsingRewUpdate] = update match
    case c: PulsingRewUpdate.Complete => Right(c)
    case p @ PulsingRewUpdate.Pulsing(_, pulser) if pulser.remaining.isEmpty =>
      complete(p).map(PulsingRewUpdate.Complete(_))
    case PulsingRewUpdate.Pulsing(snapshot, pulser) =>
      val (prefix, suffix) =
        pulser.remaining.splitAt(pulser.size.value.min(Int.MaxValue.toLong).toInt)
      members(pulser, prefix).map(answer =>
        PulsingRewUpdate.Pulsing(snapshot, pulser.copy(remaining = suffix, answer = answer))
      )

  /** `completeRupd`: finish any remaining prefix, merge leaders and balance the pots. */
  def complete(update: PulsingRewUpdate): Either[VocabError, RewardUpdate] = update match
    case PulsingRewUpdate.Complete(result) => Right(result)
    case PulsingRewUpdate.Pulsing(snapshot, pulser) =>
      members(pulser, pulser.remaining).map { answer =>
        val memberRewards = SingletonReward.asSets(answer.accumulated)
        val rs = snapshot.leaders.foldLeft(memberRewards) { case (out, (c, rewards)) =>
          out.updated(c, RewardSet(out.getOrElse(c, RewardSet.empty).rewards ++ rewards.rewards))
        }
        val deltaR2 = snapshot.r.value - total(snapshot.protocolVersion.major.toBigInt, rs)
        // `updateNonMyopic` maps the new keys only, including the empty-map case.
        val nonMyopic = NonMyopic(
          snapshot.likelihoods.transform { (pool, current) =>
            val old = snapshot.nonMyopic.likelihoods.get(pool)
            val decayed = old
              .fold(Vector.fill(100)(LogWeight(0f)))(_.weights.map(w => LogWeight(0.9f * w.float)))
            Likelihood(
              LogWeight.normalise(
                decayed.zip(current.weights).map((a, b) => LogWeight(a.float + b.float))
              )
            )
          },
          snapshot.r
        )
        RewardUpdate(
          DeltaCoin(snapshot.deltaT1.value),
          DeltaCoin(-snapshot.deltaR1.value + deltaR2),
          rs,
          DeltaCoin(-snapshot.fees.value),
          nonMyopic
        )
      }

  /** Eager reference for tools: the same machine completed without intermediate ticks. */
  def update[E <: EraTag](es: EpochState[E], blocks: BlocksMade, globals: Globals, epoch: BigInt)(
      using ShelleyBasedEra[E]
  ): Either[VocabError, RewardUpdate] = start(es, blocks, globals, epoch).flatMap(complete)
