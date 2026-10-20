// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import cats.Order
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.ledger.{Credential, ProtocolParams}
import xyz.kd5ujc.scarab.ledger.rules.Rewards
import xyz.kd5ujc.scarab.ledger.state.{Likelihood, NonMyopic, SnapShot, StakePoolSnapShot}
import xyz.kd5ujc.scarab.node.read.EraRead
import xyz.kd5ujc.scarab.storage.haskell.state.Wire
import xyz.kd5ujc.scarab.storage.haskell.state.NodeOrders.given

/** Wallet ranking queries, following `Shelley/API/Wallet.hs:217–361` and `PoolRank.hs:180–432` at
  * cardano-ledger 4c81e909df7555b6a58a000db8f8f4f89c7acdc4. These read the current stake, not the
  * epoch snapshots. Double/Float operations here mirror the reference's ranking estimate; monetary
  * formulas otherwise retain exact rational arithmetic.
  */
private[ntc] object RewardQueries:
  private final case class Facts(
      snapshot: SnapShot,
      history: NonMyopic,
      total: BigInt,
      pp: ProtocolParams
  )

  private def facts(env: QueryEnv, f: ConwayFacts): Either[Unanswered, Facts] =
    (f.currentStake, f.scalars, f.l.nes)
      .mapN { (snapshot, scalars, nes) =>
        Facts(
          snapshot,
          nes.es.nonMyopic,
          env.maxSupply - scalars.chainAccount.reserves.value,
          f.l.params
        )
      }
      .leftMap(Unanswered.Refused(_))
      .flatMap { value =>
        Either.cond(
          value.total >= 0 && value.total.bitLength <= 64 && value.pp.nOpt >= 0 && value.pp.nOpt <= 65535 &&
            Wire.nonNegativeInterval.check(value.pp.a0).isEmpty &&
            value.snapshot.pools.valuesIterator
              .forall(p => Wire.unitInterval.check(p.margin).isEmpty),
          value,
          Unanswered.Unwritable
        )
      }

  private val positions = Vector.tabulate(100)(i => (i.toDouble + 0.5) / 100.0)
  private val precision = new java.math.MathContext(80)
  private def asDouble(r: Rational): Double =
    new java.math.BigDecimal(r.numerator.bigInteger)
      .divide(new java.math.BigDecimal(r.denominator.bigInteger), precision)
      .doubleValue

  private def normalize(values: Vector[Float]): Vector[Float] =
    val maximum = values.max
    val shifted = values.map(_ - maximum)
    val area = shifted.foldLeft(0.0)((sum, x) => sum + 0.01 * math.exp(x.toDouble))
    val logArea = math.log(area).toFloat
    shifted.map(_ - logArea)

  private lazy val prior = normalize(
    positions.map(x => (39.0 * math.log(x) + 0.0 * math.log(1.0 - x)).toFloat)
  )

  /** The reference accumulates the normalized density samples directly (not width-scaled CDF
    * increments), and pairs the initial zero with the first sample position. Keep that order.
    */
  private[ntc] def performance(history: Option[Likelihood]): Either[Unanswered, Double] =
    val weights = history.fold(Vector.fill(100)(0.0f))(_.weights.map(_.float))
    if weights.isEmpty || weights.exists(x => !java.lang.Float.isFinite(x)) then
      Left(Unanswered.Unwritable)
    else
      val posterior = normalize(prior.zip(weights).map((a, b) => a + b))
      val cumulative = posterior.scanLeft(0.0)((sum, x) => sum + math.exp(x.toDouble))
      Right(positions.zip(cumulative).find(_._2 > 0.5).fold(1.0)(_._1))

  private def estimates(f: Facts): Either[Unanswered, Map[Bytes, Double]] =
    f.snapshot.pools.keysIterator.toVector
      .traverse { id =>
        performance(f.history.likelihoods.get(id)).map(id.bytes -> _)
      }
      .map(_.toMap)

  def poolInfo(env: QueryEnv, f: ConwayFacts): Either[Unanswered, Bytes] =
    for
      state <- facts(env, f)
      estimates <- estimates(state)
    yield CborOut.build { w =>
      w.array(2)
        .array(4)
        .uint(state.pp.nOpt)
        .rational(state.pp.a0.numerator, state.pp.a0.denominator)
        .integer(state.history.rewardPot.value)
        .integer(state.total)
        .map(state.snapshot.pools.size)
      state.snapshot.pools.foreach { (id, pool) =>
        w.bytes(id.bytes)
          .array(6)
          .integer(pool.stake.toBigInt)
          .integer(pool.pledge.value)
          .integer(pool.selfDelegatedOwnersStake.value)
          .integer(pool.cost.value)
          .rational(pool.margin.numerator, pool.margin.denominator)
          .double(estimates(id.bytes))
      }
    }

  private def share(amount: BigInt, total: BigInt): Rational =
    if total == 0 then Rational.zero else Rational(amount, total)

  private def desirability(f: Facts, pool: StakePoolSnapShot, performance: Double): Double =
    val z0 = Rational(1, f.pp.nOpt.max(1))
    val pledge = Rational(pool.pledge.value, f.total.max(1)).min(z0)
    val numerator =
      performance * asDouble(Rational(f.history.rewardPot.value) * (z0 + pledge * f.pp.a0))
    val reward = numerator / asDouble(Rational.one + f.pp.a0)
    val cost = pool.cost.value.toDouble
    if reward <= cost then 0.0 else (reward - cost) * (1.0 - asDouble(pool.margin))

  private def topPools(f: Facts, estimates: Map[Bytes, Double]): Set[Bytes] =
    f.snapshot.pools.iterator
      .collect {
        case (id, pool) if f.history.likelihoods.contains(id) =>
          id.bytes -> desirability(f, pool, estimates(id.bytes))
      }
      .toVector
      .sortBy(-_._2)
      .take(f.pp.nOpt.toInt)
      .map(_._1)
      .toSet

  private def reward(
      f: Facts,
      pool: StakePoolSnapShot,
      member: Rational,
      top: Boolean,
      estimate: Double
  ): Either[Unanswered, BigInt] =
    if pool.pledge.value > pool.selfDelegatedOwnersStake.value then Right(BigInt(0))
    else
      val pledge = share(pool.pledge.value, f.total)
      val actual = share(pool.stake.toBigInt, f.total)
      val nonMyopic =
        if top then (actual + member).max(Rational(1, f.pp.nOpt.max(1))) else pledge + member
      val maximum =
        Rewards.maxPool(f.pp.a0, f.pp.nOpt.max(1), f.history.rewardPot.value, nonMyopic, pledge)
      val expected = estimate * maximum.toDouble
      if !java.lang.Double.isFinite(expected) || expected < 0 then Left(Unanswered.Unwritable)
      else
        val pot = BigInt(new java.math.BigDecimal(expected).toBigInteger)
        Right(Rewards.memberReward(pot, pool.cost.value, pool.margin, member, nonMyopic))

  private def inputCompare(a: Either[BigInt, Credential], b: Either[BigInt, Credential]): Int =
    (a, b) match
      case (Left(x), Left(y))   => x.compare(y)
      case (Right(x), Right(y)) => summon[Order[Credential]].compare(x, y)
      case (Left(_), Right(_))  => -1
      case (Right(_), Left(_))  => 1

  def members(
      env: QueryEnv,
      f: ConwayFacts,
      inputs: Vector[Either[BigInt, Credential]]
  ): Either[Unanswered, Bytes] =
    for
      state <- facts(env, f)
      estimates <- estimates(state)
      top = topPools(state, estimates)
      active = state.snapshot.activeStake.entries.iterator.map { (credential, stake) =>
        EraRead.credential(credential) -> stake.stake.value.toBigInt
      }.toMap
      entries <- inputs.distinct.sortWith((a, b) => inputCompare(a, b) < 0).traverse { input =>
        val key = input match
          case Left(coin) =>
            if coin >= 0 && coin.bitLength <= 64 then
              Right(CborOut.build(_.array(2).uint(0).uint(coin)))
            else Left(Unanswered.Unwritable)
          case Right(credential) =>
            xyz.kd5ujc.scarab.storage.haskell.state.HaskellStateCodecs.nodeCredential
              .write(credential)
              .leftMap(_ => Unanswered.Unwritable)
              .map(raw => CborOut.build(_.array(2).uint(1).raw(raw)))
        val amount = input.fold(identity, active.getOrElse(_, BigInt(0)))
        for
          encoded <- key
          rewards <- state.snapshot.pools.toVector.traverse { (id, pool) =>
            reward(state, pool, share(amount, state.total), top(id.bytes), estimates(id.bytes))
              .map(id.bytes -> _)
          }
        yield (encoded, rewards)
      }
    yield CborOut.build { w =>
      w.map(entries.size)
      entries.foreach { (key, rewards) =>
        w.raw(key)
        w.map(rewards.size)
        rewards.foreach((id, amount) => w.bytes(id).integer(amount))
      }
    }
