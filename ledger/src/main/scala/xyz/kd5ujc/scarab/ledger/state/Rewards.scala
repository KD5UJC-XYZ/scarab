// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.state

import cats.{Eq, Order, Show}
import cats.derived.*
import cats.syntax.show.*
import monocle.{Focus, Lens, Prism}
import scala.collection.immutable.{SortedMap, SortedSet}
import xyz.kd5ujc.scarab.codec.Bytes

// The reward side of the epoch state: the non-myopic pool ranking, the reward update of the epoch
// boundary and the state it is held in while it is computed. Sources: `Shelley/PoolRank.hs`,
// `Shelley/RewardUpdate.hs` (shelley impl 1.19.0.1 of the pinned ledger) and
// `cardano-ledger-core/.../Rewards.hs`.

/** `LogWeight` (`Shelley/PoolRank.hs:88`, `newtype LogWeight = LogWeight Float`): the logarithm of
  * one sample of a pool's likelihood. A 32-bit float, and encoded as one (`deriving EncCBOR` of
  * `Float`; only `PerformanceEstimate`, `PoolRank.hs:222-229`, is a `Double`, and it is not part of
  * the state). `Eq` is the IEEE one of `Float`, as in Haskell: `NaN` is not equal to itself and
  * `0.0 == -0.0`, so the generators of the mirror produce finite values only.
  */
opaque type LogWeight = Float

object LogWeight:
  def apply(value: Float): LogWeight = value

  extension (w: LogWeight) def float: Float = w

  /** `normalizeLikelihood` (`PoolRank.hs:121-123`): every weight less the least; empty is itself
    * (Haskell's `minimum` is partial there).
    */
  def normalise(ws: Vector[LogWeight]): Vector[LogWeight] =
    if ws.isEmpty then ws
    else
      val least = ws.min(using Ordering.Float.IeeeOrdering)
      ws.map(_ - least)

  given Eq[LogWeight] = Eq.instance((a, b) => a == b)
  given Show[LogWeight] = Show.show(_.toString)

/** `Likelihood` (`Shelley/PoolRank.hs:101-103`, a `StrictSeq LogWeight`): the likelihood samples of
  * one pool, 100 of them (`samplePositions`, `PoolRank.hs:134-135`) when the node computes it. `Eq`
  * is the Haskell one (`PoolRank.hs:107-108`): the normalised sequences are compared, so two
  * likelihoods that differ by a constant are equal. (Haskell's `Ord` is the derived one on the raw
  * sequence, which that `Eq` disagrees with; the mirror has no use for it and offers none.)
  */
final case class Likelihood(weights: Vector[LogWeight])

object Likelihood:
  extension (l: Likelihood) def normalised: Likelihood = Likelihood(LogWeight.normalise(l.weights))

  given Eq[Likelihood] =
    Eq.instance((a, b) => Eq[Vector[LogWeight]].eqv(a.normalised.weights, b.normalised.weights))

  given Show[Likelihood] = Show.show(l => s"Likelihood(${l.weights.show})")

/** `NonMyopic` (`Shelley/PoolRank.hs:231-236`, encoding `245-250`: `array(2)[likelihoods, pot]`):
  * the data of the pool ranking (`getNonMyopicMemberRewards`), which is not part of the protocol.
  * The node does not compute it, so a mirror built from our state holds [[NonMyopic.empty]] (a gap,
  * as the plan says).
  */
final case class NonMyopic(
    likelihoods: SortedMap[KeyHash[StakePool], Likelihood],
    rewardPot: Coin
) derives Eq,
      Show

object NonMyopic:
  /** `def` (`PoolRank.hs:238-239`). */
  val empty: NonMyopic = NonMyopic(Sorted.empty, Coin.zero)

  val likelihoods: Lens[NonMyopic, SortedMap[KeyHash[StakePool], Likelihood]] =
    Focus[NonMyopic](_.likelihoods)
  val rewardPot: Lens[NonMyopic, Coin] = Focus[NonMyopic](_.rewardPot)

/** `RewardType` (`Rewards.hs:41-42`, encoded as the word 0 or 1, `50-52`). */
enum RewardType derives Eq, Show:
  case MemberReward, LeaderReward

/** `Reward` (`cardano-ledger-core/.../Rewards.hs:68-72`, encoding `89-91`: `array(3)`): a reward of
  * one stake pool, to a member or to the leader (the owner), in lovelace.
  */
final case class Reward(rewardType: RewardType, rewardPool: KeyHash[StakePool], rewardAmount: Coin)
    derives Eq,
      Show

object Reward:
  /** The Haskell `Ord Reward` (`Rewards.hs:76-83`): member rewards sort above leader rewards (the
    * reverse of the constructors), then by pool, and the amount does not count, so a set holds one
    * reward per type and pool. Not an `Order` given, because it disagrees with the structural `Eq`;
    * a [[RewardSet]] is built with it and nothing else sorts by it.
    */
  val ordering: Order[Reward] = Order.from { (a, b) =>
    (a.rewardType, b.rewardType) match
      case (RewardType.MemberReward, RewardType.LeaderReward) => 1
      case (RewardType.LeaderReward, RewardType.MemberReward) => -1
      case _ => Order[KeyHash[StakePool]].compare(a.rewardPool, b.rewardPool)
  }

  val rewardType: Lens[Reward, RewardType] = Focus[Reward](_.rewardType)
  val rewardPool: Lens[Reward, KeyHash[StakePool]] = Focus[Reward](_.rewardPool)
  val rewardAmount: Lens[Reward, Coin] = Focus[Reward](_.rewardAmount)

/** `Set Reward`: the rewards one credential receives, ordered by [[Reward.ordering]] by
  * construction (so it is also the order of its encoding).
  */
opaque type RewardSet = SortedSet[Reward]

object RewardSet:
  def apply(rewards: IterableOnce[Reward]): RewardSet =
    Sorted.set(rewards)(using Reward.ordering)

  val empty: RewardSet = apply(Nil)

  extension (s: RewardSet) def rewards: SortedSet[Reward] = s

  // Structural equality of the members. Within a set the ordering already collapsed what it
  // does not tell apart, so what is left is compared whole.
  given Eq[RewardSet] = Eq.fromUniversalEquals
  given Show[RewardSet] = Show.show(s => s"RewardSet(${s.toList.map(_.show).mkString(", ")})")

/** `RewardUpdate` (`Shelley/RewardUpdate.hs:111-117`, encoding `125-132`: `array(5)`): the change
  * of the treasury, the reserves and the fees, and the rewards of each credential, computed for the
  * next boundary. The encoding writes `deltaR` and `deltaF` negated (`invert`, `129,131`); the
  * mirror holds them as they are in memory, and the codec is where the sign is flipped.
  */
final case class RewardUpdate(
    deltaT: DeltaCoin,
    deltaR: DeltaCoin,
    rs: SortedMap[Credential[Staking], RewardSet],
    deltaF: DeltaCoin,
    nonMyopic: NonMyopic
) derives Eq,
      Show

object RewardUpdate:
  /** `emptyRewardUpdate` (`RewardUpdate.hs:154-156`). */
  val empty: RewardUpdate =
    RewardUpdate(DeltaCoin(0), DeltaCoin(0), Sorted.empty, DeltaCoin(0), NonMyopic.empty)

  val deltaT: Lens[RewardUpdate, DeltaCoin] = Focus[RewardUpdate](_.deltaT)
  val deltaR: Lens[RewardUpdate, DeltaCoin] = Focus[RewardUpdate](_.deltaR)
  val rs: Lens[RewardUpdate, SortedMap[Credential[Staking], RewardSet]] = Focus[RewardUpdate](_.rs)
  val deltaF: Lens[RewardUpdate, DeltaCoin] = Focus[RewardUpdate](_.deltaF)
  val nonMyopic: Lens[RewardUpdate, NonMyopic] = Focus[RewardUpdate](_.nonMyopic)

/** `ProtVer` (`BaseTypes.hs:211-212`, encoded as the two numbers in sequence, `CBORGroup`): the
  * protocol version. Haskell bounds the major version (`Version`) and the minor is a `Word32`; both
  * are widened to a word here, and the codec is where the range is checked.
  */
final case class ProtVer(major: Word64, minor: Word64) derives Eq, Show

object ProtVer:
  val major: Lens[ProtVer, Word64] = Focus[ProtVer](_.major)
  val minor: Lens[ProtVer, Word64] = Focus[ProtVer](_.minor)

/** `RewardSnapShot` (`RewardUpdate.hs:161-170`, encoding `177-189`: `array(8)`): what the pulsing
  * reward calculation was started from.
  */
final case class RewardSnapShot(
    fees: Coin,
    protocolVersion: ProtVer,
    nonMyopic: NonMyopic,
    deltaR1: Coin,
    r: Coin,
    deltaT1: Coin,
    likelihoods: SortedMap[KeyHash[StakePool], Likelihood],
    leaders: SortedMap[Credential[Staking], RewardSet]
) derives Eq,
      Show

/** The pulser of a reward update that is still being computed (`Pulser` of `RewardUpdate.hs:346`,
  * the `RewardPulser` of `Pulsing.hs`: the free variables, the stake still to visit, the answer so
  * far), as the CBOR item it was read as. The mirror does not model it (the node computes its
  * rewards at the boundary), and what it does not model it keeps as the original bytes, so a state
  * taken in the middle of an epoch is written again byte for byte. `item` is one complete, well
  * formed CBOR item.
  */
final case class RewardPulser(item: Bytes) derives Eq, Show

/** `PulsingRewUpdate` (`RewardUpdate.hs:341-344`, encoding `346-348`: `[0, snapshot, pulser]` or
  * `[1, update]`): a reward update that is either still being computed or complete.
  *
  * The node computes its rewards at the boundary (plan §7.5), so a mirror of our state is always
  * [[PulsingRewUpdate.Complete]]. [[PulsingRewUpdate.Pulsing]] keeps the snapshot a Haskell state
  * was left with and the pulser as the original bytes ([[RewardPulser]]): such a state round trips
  * exactly, but a node cannot be made from it (importing one stays a refusal, `Gap`).
  */
enum PulsingRewUpdate derives Eq, Show:
  case Pulsing(snapshot: RewardSnapShot, pulser: RewardPulser)
  case Complete(update: RewardUpdate)

object PulsingRewUpdate:
  /** The completed update, if that is the form. */
  val complete: Prism[PulsingRewUpdate, RewardUpdate] =
    Prism.partial[PulsingRewUpdate, RewardUpdate] { case Complete(u) => u }(Complete(_))
