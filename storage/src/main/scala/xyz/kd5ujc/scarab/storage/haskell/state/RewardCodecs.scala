// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import xyz.kd5ujc.scarab.codec.Value
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.models.runtime.{
  Codec,
  ConstraintViolation,
  DecodeError,
  Decoded,
  Node,
  Writer,
  at
}

import DecodeError.Kind
import Wire.*

/** The reward side of the epoch state (`Shelley/PoolRank.hs`, `Shelley/RewardUpdate.hs` and
  * `Rewards.hs`). The weights of a likelihood are single-precision floats, which only a decoder
  * that allows floats reads (`Cbor.Limits(allowFloats = true)`).
  */
trait RewardCodecs extends SnapshotCodecs:
  /** `LogWeight` (`PoolRank.hs:88`, `EncCBOR Float`): a single-precision float, always four bytes
    * (`encodeFloat`, cborg). The decoder reads a half or a single (`decodeFloat`).
    */
  given logWeight: Codec[LogWeight] = new Codec[LogWeight]:
    def decode(n: Node): Decoded[LogWeight] = n.value match
      case Value.Float(x, w) if w != xyz.kd5ujc.scarab.codec.FloatWidth.Double =>
        Right(LogWeight(x.toFloat))
      case Value.Float(_, _) =>
        Left(DecodeError.at(n, Kind.Unsupported("a double where a Float is expected")))
      case _ => Left(Codec.mismatch("float", n))
    def encode(a: LogWeight, out: Writer): Unit = out.float32(a.float)

  /** `Likelihood` (`PoolRank.hs:101-103`, a `StrictSeq LogWeight`). */
  given likelihood: Codec[Likelihood] = seqOf(logWeight).imap(Likelihood(_))(_.weights)

  /** `NonMyopic` (`PoolRank.hs:245-250`): `[likelihoods, reward pot]`. */
  given nonMyopic(using ProtVer): Codec[NonMyopic] =
    Record.of[NonMyopic]((mapOf(keyHash[StakePool], likelihood), coin))

  /** `RewardType` (`Rewards.hs:50-52`): the word 0 (member) or 1 (leader). */
  given rewardType: Codec[RewardType] = new Codec[RewardType]:
    def decode(n: Node): Decoded[RewardType] = n.value match
      case Value.UInt(v) if v == 0 => Right(RewardType.MemberReward)
      case Value.UInt(v) if v == 1 => Right(RewardType.LeaderReward)
      case Value.UInt(v)           => Left(DecodeError.at(n, Kind.OutOfRange("0 or 1", v)))
      case _                       => Left(Codec.mismatch("unsigned integer", n))
    def encode(a: RewardType, out: Writer): Unit = out.uint(a.ordinal.toLong)

  /** `Reward` (`Rewards.hs:89-91`): `[type, pool, amount]`. */
  given reward: Codec[Reward] = Record.auto[Reward]

  /** `Set Reward`, ordered by `Ord Reward` (`Rewards.hs:76-83`), which does not look at the amount:
    * two rewards of one type and pool are one element, so a set that holds both is refused, as the
    * reference's is.
    */
  given rewardSet(using v: ProtVer): Codec[RewardSet] =
    setOf(reward)(using Reward.ordering, v).imap(s => RewardSet(s))(_.rewards)

  /** A delta the reward update writes negated (`invert`, `RewardUpdate.hs:129,131`). */
  private val negated: Codec[DeltaCoin] =
    deltaCoin.imap(d => DeltaCoin(-DeltaCoin.value(d)))(d => DeltaCoin(-DeltaCoin.value(d)))

  /** `RewardUpdate` (`RewardUpdate.hs:125-132`): `[delta T, - delta R, rewards, - delta F,
    * non-myopic]`.
    */
  given rewardUpdate(using ProtVer): Codec[RewardUpdate] =
    Record.of[RewardUpdate](
      (deltaCoin, negated, mapOf(credential[Staking], rewardSet), negated, nonMyopic)
    )

  /** `RewardSnapShot` (`RewardUpdate.hs:177-189`): eight fields. */
  given rewardSnapShot(using ProtVer): Codec[RewardSnapShot] =
    Record.of[RewardSnapShot](
      (
        coin,
        protVer,
        nonMyopic,
        coin,
        coin,
        coin,
        mapOf(keyHash[StakePool], likelihood),
        mapOf(credential[Staking], rewardSet)
      )
    )

  given rewardPulseSize: Codec[RewardPulseSize] =
    Codec.uintBig(BigInt(Long.MaxValue)).validated(RewardPulseSize.fromBigInt)(n => BigInt(n.value))

  given rewardBlockCount: Codec[RewardBlockCount] =
    integer.validated(RewardBlockCount.fromBigInt)(_.value)

  given leaderOnlyReward: Codec[LeaderOnlyReward] = Record.auto[LeaderOnlyReward]

  /** `PoolRewardInfo` (`Shelley/Rewards.hs:260-278`). Pool block counts are unbounded naturals
    * (including tag-2 bignums), as the reference decoder reads them.
    */
  given poolRewardInfo(using ProtVer): Codec[PoolRewardInfo] =
    Record.of[PoolRewardInfo]((ratio, coin, stakePoolSnapShot, rewardBlockCount, leaderOnlyReward))

  given rewardFreeVars(using ProtVer): Codec[RewardFreeVars] =
    Record.of[RewardFreeVars](
      (setOf(credential[Staking]), coin, protVer, mapOf(keyHash[StakePool], poolRewardInfo))
    )

  given singletonReward: Codec[SingletonReward] = reward.imap(SingletonReward(_))(_.reward)

  given rewardAns(using ProtVer): Codec[RewardAns] =
    Record.of[RewardAns](
      (mapOf(credential[Staking], singletonReward), mapOf(credential[Staking], rewardSet))
    )

  /** `RSLP` (`RewardUpdate.hs:332-339`): size, free variables, remaining stake, answer. */
  given rewardPulser(using ProtVer): Codec[RewardPulser] =
    Record.of[RewardPulser](
      (rewardPulseSize, rewardFreeVars, mapOf(credential[Staking], stakeWithDelegation), rewardAns)
    )

  /** `PulsingRewUpdate` (`RewardUpdate.hs:346-348`): `[0, snapshot, pulser]` or `[1, update]`. */
  given pulsingRewUpdate(using ProtVer): Codec[PulsingRewUpdate] =
    new Codec[PulsingRewUpdate]:
      def decode(n: Node): Decoded[PulsingRewUpdate] =
        variant(n).flatMap {
          case (t, Vector(u)) if t == 1 =>
            rewardUpdate.decode(u).map(PulsingRewUpdate.Complete(_)).at("update")
          case (t, Vector(s, p)) if t == 0 =>
            for
              snapshot <- rewardSnapShot.decode(s).at("snapshot")
              pulser <- rewardPulser.decode(p).at("pulser")
            yield PulsingRewUpdate.Pulsing(snapshot, pulser)
          case (t, xs) => unknownVariant(n, "PulsingRewUpdate", t, xs.size)
        }
      def encode(a: PulsingRewUpdate, out: Writer): Unit = a match
        case PulsingRewUpdate.Complete(u) =>
          out.arrayHead(2); out.uint(1L); rewardUpdate.encode(u, out)
        case PulsingRewUpdate.Pulsing(s, p) =>
          out.arrayHead(3); out.uint(0L); rewardSnapShot.encode(s, out); rewardPulser.encode(p, out)
      override def check(a: PulsingRewUpdate): Option[ConstraintViolation] = a match
        case PulsingRewUpdate.Complete(u)   => rewardUpdate.check(u)
        case PulsingRewUpdate.Pulsing(s, p) => rewardSnapShot.check(s).orElse(rewardPulser.check(p))
