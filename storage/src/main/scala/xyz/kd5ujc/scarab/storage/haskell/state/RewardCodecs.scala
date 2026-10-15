// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import xyz.kd5ujc.scarab.codec.Value
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.models.runtime.{Codec, DecodeError, Decoded, Node, Writer, at}

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
      case Value.Float(_, _) => Left(DecodeError.at(n, Kind.Unsupported("a double where a Float is expected")))
      case _                 => Left(Codec.mismatch("float", n))
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

  /** `PulsingRewUpdate` (`RewardUpdate.hs:346-348`): `[0, snapshot, pulser]` or `[1, update]`.
    *
    * Only the completed form is mirrored. The pulser of the other (`RSLP`: the free variables, the
    * stake still to visit and the answer so far) is not, so reading `[0, ...]` is refused and a
    * pulsing update is one the codec cannot write: [[Codec.check]] says so, and the codecs of the
    * enclosing records pass it up. (`encode` writes the snapshot and a `null` for the pulser,
    * which is no valid pulser; the callers check first.)
    */
  given pulsingRewUpdate(using ProtVer): Codec[PulsingRewUpdate] =
    new Codec[PulsingRewUpdate]:
      def decode(n: Node): Decoded[PulsingRewUpdate] =
        variant(n).flatMap {
          case (t, Vector(u)) if t == 1 =>
            rewardUpdate.decode(u).map(PulsingRewUpdate.Complete(_)).at("update")
          case (t, Vector(_, _)) if t == 0 =>
            Left(DecodeError.at(n, Kind.Unsupported("a pulsing reward update (the pulser is not mirrored)")))
          case (t, xs) => unknownVariant(n, "PulsingRewUpdate", t, xs.size)
        }
      def encode(a: PulsingRewUpdate, out: Writer): Unit = a match
        case PulsingRewUpdate.Complete(u) =>
          out.arrayHead(2); out.uint(1L); rewardUpdate.encode(u, out)
        case PulsingRewUpdate.Pulsing(s) =>
          out.arrayHead(3); out.uint(0L); rewardSnapShot.encode(s, out); out.nul()
      override def check(a: PulsingRewUpdate): Option[String] = a match
        case PulsingRewUpdate.Complete(u) => rewardUpdate.check(u)
        case PulsingRewUpdate.Pulsing(_) =>
          Some("a pulsing reward update: the pulser is not mirrored")
