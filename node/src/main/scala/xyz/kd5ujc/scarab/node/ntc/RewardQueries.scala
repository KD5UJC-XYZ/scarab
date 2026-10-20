// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.ledger.{Credential, ProtocolParams}
import xyz.kd5ujc.scarab.ledger.rules.Rewards
import xyz.kd5ujc.scarab.ledger.state.{Likelihood, NonMyopic, SnapShot, StakePoolSnapShot}
import xyz.kd5ujc.scarab.node.read.EraRead

/** Wallet queries follow `Shelley/API/Wallet.hs:227-281,327-370` and
  * `Shelley/PoolRank.hs:180-218,286-345,395-435` at ledger f649f975. Performance estimates
  * deliberately retain the reference Float histogram arithmetic and Double cumulative sum. Monetary
  * operations remain exact until the reference's explicit conversion for estimated performance.
  */
private[ntc] object RewardQueries:
  private val positions = Vector.tabulate(100)(i => (i.toDouble + 0.5) / 100.0)

  /** Round an exact ratio to IEEE binary64, including ties to even and subnormal values. */
  private[ntc] def asDouble(r: Rational): Double =
    val n = r.numerator.abs
    val d = r.denominator
    if n == 0 then 0.0
    else
      val candidate = n.bitLength - d.bitLength
      val below = if candidate >= 0 then n < (d << candidate) else (n << -candidate) < d
      val exponent = candidate - (if below then 1 else 0)
      val magnitude =
        if exponent > 1023 then Double.PositiveInfinity
        else if exponent < -1075 then 0.0
        else
          val scale = 52 - exponent.max(-1022)
          val (numerator, denominator) =
            if scale >= 0 then (n << scale, d) else (n, d << -scale)
          val (whole, remainder) = numerator /% denominator
          val comparison = (remainder * 2).compare(denominator)
          val rounded =
            if comparison > 0 || (comparison == 0 && whole.testBit(0)) then whole + 1 else whole
          math.scalb(rounded.toDouble, -scale)
      if r.numerator.signum < 0 then -magnitude else magnitude

  private def normalise(values: Vector[Float]): Vector[Float] =
    if values.isEmpty then values
    else
      val largest = values.max
      val shifted = values.map(_ - largest)
      val area = shifted.foldLeft(0.0)((sum, x) => sum + 0.01 * math.exp(x.toDouble))
      val logArea = math.log(area).toFloat
      shifted.map(_ - logArea)

  private val prior = normalise(positions.map(x => (39.0 * math.log(x)).toFloat))

  /** `percentile'`: the CDF is the upstream scan with an initial zero and no bin-width factor. */
  def performance(likelihood: Option[Likelihood]): Double =
    val weights = likelihood.fold(Vector.fill(100)(0.0f))(_.weights.map(_.float))
    val posterior = normalise(prior.zip(weights).map(_ + _))
    val cumulative = posterior.scanLeft(0.0)((sum, x) => sum + math.exp(x.toDouble))
    positions.zip(cumulative).find(_._2 > 0.5).fold(1.0)(_._1)

  private def ratio(n: BigInt, d: BigInt): Rational =
    if d == 0 then Rational.zero else Rational(n, d)

  private def fraction(w: CborOut, r: Rational): CborOut =
    w.tag(30).array(2).integer(r.numerator).integer(r.denominator)

  def info(params: ProtocolParams, total: BigInt, snap: SnapShot, history: NonMyopic): Bytes =
    CborOut.build { w =>
      w.array(2).array(4).uint(params.nOpt)
      fraction(w, params.a0)
        .integer(history.rewardPot.value)
        .integer(total)
      w.map(snap.pools.size)
      snap.pools.foreach { (id, p) =>
        w.bytes(id.bytes)
          .array(6)
          .integer(p.stake.toBigInt)
          .integer(p.pledge.value)
          .integer(p.selfDelegatedOwnersStake.value)
          .integer(p.cost.value)
          .tag(30)
          .array(2)
          .integer(p.margin.numerator)
          .integer(p.margin.denominator)
          .double(performance(history.likelihoods.get(id)))
      }
    }

  private def desirability(
      params: ProtocolParams,
      total: BigInt,
      pot: BigInt,
      p: StakePoolSnapShot,
      estimate: Double
  ): Double =
    val z0 = Rational(1, params.nOpt.max(1))
    val pledge = Rational(p.pledge.value, total.max(1))
    val numerator = estimate * asDouble(Rational(pot) * (z0 + pledge.min(z0) * params.a0))
    val maximum = numerator / asDouble(Rational.one + params.a0)
    val cost = p.cost.value.toDouble
    if maximum <= cost then 0.0 else (maximum - cost) * (1.0 - asDouble(p.margin))

  def members(
      params: ProtocolParams,
      total: BigInt,
      snap: SnapShot,
      history: NonMyopic,
      requested: Vector[Either[BigInt, Credential]]
  ): Bytes =
    val prepared = snap.pools.iterator.map { (id, p) =>
      (id, p, performance(history.likelihoods.get(id)))
    }.toVector
    val ranked = prepared
      .collect {
        case (id, p, estimate) if history.likelihoods.contains(id) =>
          id -> desirability(params, total, history.rewardPot.value, p, estimate)
      }
      .sortBy(-_._2)
    val top = ranked.take(params.nOpt.min(Int.MaxValue).toInt).map(_._1).toSet
    val current = snap.activeStake.entries.iterator
      .map((c, s) => EraRead.credential(c) -> s.stake.value.toBigInt)
      .toMap
    import xyz.kd5ujc.scarab.storage.haskell.state.NodeOrders.given
    val credentialOrder = summon[cats.Order[Credential]].toOrdering
    val keys = requested.distinct.sortWith {
      case (Left(a), Left(b))   => a < b
      case (Left(_), Right(_))  => true
      case (Right(_), Left(_))  => false
      case (Right(a), Right(b)) => credentialOrder.lt(a, b)
    }
    CborOut.build { w =>
      w.map(keys.size)
      keys.foreach { key =>
        key match
          case Left(amount) => w.array(2).uint(0).integer(amount)
          case Right(c) =>
            val tag = c match
              case Credential.Key(_)    => 0
              case Credential.Script(_) => 1
            w.array(2).uint(1).array(2).uint(tag).bytes(c.hash)
        val amount = key.fold(identity, current.getOrElse(_, BigInt(0)))
        val t = ratio(amount, total)
        w.map(snap.pools.size)
        prepared.foreach { (id, p, estimate) =>
          val reward =
            if p.pledge.value > p.selfDelegatedOwnersStake.value then BigInt(0)
            else
              val pledge = ratio(p.pledge.value, total)
              val sigma = ratio(p.stake.toBigInt, total)
              val nm =
                if top(id) then (sigma + t).max(Rational(1, params.nOpt.max(1))) else pledge + t
              val maximum =
                Rewards.maxPool(params.a0, params.nOpt.max(1), history.rewardPot.value, nm, pledge)
              val predicted = estimate * maximum.toDouble
              val fHat = BigDecimal(new java.math.BigDecimal(predicted))
                .setScale(0, BigDecimal.RoundingMode.FLOOR)
                .toBigInt
              Rewards.memberReward(fHat, p.cost.value, p.margin, t, nm)
          w.bytes(id.bytes).integer(reward)
        }
      }
    }
