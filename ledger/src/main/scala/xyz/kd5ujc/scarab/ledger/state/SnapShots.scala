// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.state

import cats.{Eq, Show}
import cats.derived.*
import monocle.{Focus, Lens}
import scala.collection.immutable.{SortedMap, SortedSet}
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.protocol.{LayoutPart, StateLayout}

// The stake snapshots of the epoch state (mark, set, go) and the pool distribution derived from
// one: `State/SnapShots.hs`, `State/Stake.hs`, `State/PoolDistr.hs` of the pinned ledger
// (cardano-ledger-core 1.21.0.0, rev f649f97; the Preview chain is Conway, so this is the shape
// 11.1.3 reads and writes). The mirror keeps the fields Haskell encodes; the two it derives
// (`ssTotalActiveStake`, `ssStakeMarkPoolDistr`) are methods, so a mirror cannot disagree with
// itself about them.

/** `StakeWithDelegation` (`State/Stake.hs:104-108`, encoding `116-117`: `array(2)[stake, pool]`):
  * the non-zero stake of one credential, and the pool it is delegated to.
  */
final case class StakeWithDelegation(stake: NonZero[CompactCoin], delegation: KeyHash[StakePool])
    derives Eq,
      Show,
      Diff

object StakeWithDelegation:
  val stake: Lens[StakeWithDelegation, NonZero[CompactCoin]] = Focus[StakeWithDelegation](_.stake)
  val delegation: Lens[StakeWithDelegation, KeyHash[StakePool]] =
    Focus[StakeWithDelegation](_.delegation)

/** `ActiveStake` (`State/Stake.hs:146-148`, a newtype over a map): the stake of the credentials
  * that are registered, delegated and have some stake.
  */
final case class ActiveStake(entries: SortedMap[Credential[Staking], StakeWithDelegation])
    derives Eq,
      Show,
      Diff

object ActiveStake:
  val empty: ActiveStake = ActiveStake(Sorted.empty)

  val entries: Lens[ActiveStake, SortedMap[Credential[Staking], StakeWithDelegation]] =
    Focus[ActiveStake](_.entries)

  extension (a: ActiveStake)
    /** `sumAllActiveStake` (`State/Stake.hs:157-160`): the total, which is one lovelace when there
      * is none, because the total is a denominator. Each compact amount is converted to Coin before
      * the fold, so this total has arbitrary precision.
      */
    def total: NonZero[Coin] =
      val sum = a.entries.valuesIterator.foldLeft(BigInt(0))(_ + _.stake.value.toBigInt)
      NonZero.from(Coin(sum)).getOrElse(Coin.oneNonZero)

/** `BlsKey` (cardano-ledger 9df9f98c `State/StakePool.hs:490-494`, encoding `:532-542`): a pool's
  * Leios voting key, a BLS12-381 MinSig verification key and its proof of possession, as their
  * compressed encodings (96 and 48 bytes).
  */
final case class BlsKey(key: Bytes, proofOfPossession: Bytes) derives Eq, Show, Diff

/** `BlsKeyState` (9df9f98c `State/StakePool.hs:548-570`): a registered voting key and the epoch its
  * registration took effect in.
  */
final case class BlsKeyState(key: BlsKey, registeredIn: EpochNo) derives Eq, Show, Diff

/** The voting key of a pool, in its state and in a stake snapshot, by layout: the 11.1 layout has
  * no such field; the Leios layout has `spsBlsKey` /`spssBlsKey`, a `StrictMaybe BlsKeyState`,
  * after the VRF key hash (9df9f98c `State/StakePool.hs:165`, `State/SnapShots.hs:197`).
  */
enum PoolBlsKey extends LayoutPart derives Eq, Show, Diff:
  case Node11_1
  case Leios(key: Option[BlsKeyState])

  def stateLayout: StateLayout = this match
    case Node11_1 => StateLayout.Node11_1
    case Leios(_) => StateLayout.Leios

/** `StakePoolSnapShot` (`State/SnapShots.hs:156-183`, encoding `239-252`: `array(10)` in this
  * order): what the reward calculation needs of one pool, resolved when the snapshot is taken. The
  * Leios layout has the voting key after the VRF key hash, `array(11)` ([[PoolBlsKey]]).
  *
  * `numDelegators` is an `Int` in Haskell, only ever a count; the word is its non-negative range.
  * Its only use is the pool-distribution filter (`SnapShots.hs:453`).
  */
final case class StakePoolSnapShot(
    stake: CompactCoin,
    stakeRatio: Rational,
    selfDelegatedOwners: SortedSet[KeyHash[Staking]],
    selfDelegatedOwnersStake: Coin,
    vrf: VRFVerKeyHash,
    blsKey: PoolBlsKey,
    pledge: Coin,
    cost: Coin,
    margin: Rational,
    numDelegators: Word64,
    accountId: Credential[Staking]
) derives Eq,
      Show,
      Diff

object StakePoolSnapShot:
  val stake: Lens[StakePoolSnapShot, CompactCoin] = Focus[StakePoolSnapShot](_.stake)
  val stakeRatio: Lens[StakePoolSnapShot, Rational] = Focus[StakePoolSnapShot](_.stakeRatio)
  val selfDelegatedOwners: Lens[StakePoolSnapShot, SortedSet[KeyHash[Staking]]] =
    Focus[StakePoolSnapShot](_.selfDelegatedOwners)
  val selfDelegatedOwnersStake: Lens[StakePoolSnapShot, Coin] =
    Focus[StakePoolSnapShot](_.selfDelegatedOwnersStake)
  val vrf: Lens[StakePoolSnapShot, VRFVerKeyHash] = Focus[StakePoolSnapShot](_.vrf)
  val blsKey: Lens[StakePoolSnapShot, PoolBlsKey] = Focus[StakePoolSnapShot](_.blsKey)
  val pledge: Lens[StakePoolSnapShot, Coin] = Focus[StakePoolSnapShot](_.pledge)
  val cost: Lens[StakePoolSnapShot, Coin] = Focus[StakePoolSnapShot](_.cost)
  val margin: Lens[StakePoolSnapShot, Rational] = Focus[StakePoolSnapShot](_.margin)
  val numDelegators: Lens[StakePoolSnapShot, Word64] = Focus[StakePoolSnapShot](_.numDelegators)
  val accountId: Lens[StakePoolSnapShot, Credential[Staking]] =
    Focus[StakePoolSnapShot](_.accountId)

/** `SnapShot` (`State/SnapShots.hs:273-282`, encoding `290-296`: `array(2)[activeStake, pools]`).
  * The pinned shape holds the delegations inside the active stake and the pools as a map of
  * [[StakePoolSnapShot]]; the older `[stake, delegations, pools]` shape is decode-only
  * (`SnapShots.hs:310-325`). `ssTotalActiveStake` is not encoded and is [[ActiveStake.total]] here.
  */
final case class SnapShot(
    activeStake: ActiveStake,
    pools: SortedMap[KeyHash[StakePool], StakePoolSnapShot]
) derives Eq,
      Show,
      Diff

object SnapShot:
  /** `emptySnapShot` (`State/SnapShots.hs:390-391`). */
  val empty: SnapShot = SnapShot(ActiveStake.empty, Sorted.empty)

  val activeStake: Lens[SnapShot, ActiveStake] = Focus[SnapShot](_.activeStake)
  val pools: Lens[SnapShot, SortedMap[KeyHash[StakePool], StakePoolSnapShot]] =
    Focus[SnapShot](_.pools)

  extension (s: SnapShot)
    /** `ssTotalActiveStake`, derived (`mkSnapShot`, `State/SnapShots.hs:397-403`). */
    def totalActiveStake: NonZero[Coin] = s.activeStake.total

/** `IndividualPoolStake` (`State/PoolDistr.hs:54-62`, encoding `70-77`: `array(3)`): the stake of
  * one pool, as a ratio of the total and as an amount, and the hash of its VRF key.
  */
final case class IndividualPoolStake(
    stake: Rational,
    totalPoolStake: CompactCoin,
    vrf: VRFVerKeyHash
) derives Eq,
      Show,
      Diff

object IndividualPoolStake:
  val stake: Lens[IndividualPoolStake, Rational] = Focus[IndividualPoolStake](_.stake)
  val totalPoolStake: Lens[IndividualPoolStake, CompactCoin] =
    Focus[IndividualPoolStake](_.totalPoolStake)
  val vrf: Lens[IndividualPoolStake, VRFVerKeyHash] = Focus[IndividualPoolStake](_.vrf)

/** `PoolDistr` (`State/PoolDistr.hs:98-104`, encoding `117-122`: `array(2)[distribution, total]`).
  */
final case class PoolDistr(
    distribution: SortedMap[KeyHash[StakePool], IndividualPoolStake],
    totalActiveStake: NonZero[Coin]
) derives Eq,
      Show,
      Diff

object PoolDistr:
  /** `def` (`State/PoolDistr.hs:108-109`). */
  val empty: PoolDistr = PoolDistr(Sorted.empty, Coin.oneNonZero)

  val distribution: Lens[PoolDistr, SortedMap[KeyHash[StakePool], IndividualPoolStake]] =
    Focus[PoolDistr](_.distribution)
  val totalActiveStake: Lens[PoolDistr, NonZero[Coin]] = Focus[PoolDistr](_.totalActiveStake)

  /** `calculatePoolDistr` (`State/SnapShots.hs:446-465`): the pools of the snapshot that have at
    * least one delegator, with their stake and VRF key, over the snapshot's total.
    */
  def of(snapshot: SnapShot): PoolDistr =
    PoolDistr(
      Sorted.map(snapshot.pools.iterator.collect {
        case (id, p) if !p.numDelegators.isZero =>
          id -> IndividualPoolStake(p.stakeRatio, p.stake, p.vrf)
      }),
      snapshot.totalActiveStake
    )

/** `LeiosSeat` (cardano-base c1e1ed8d `cardano-crypto-leios/src/Cardano/Crypto/Leios.hs:131-135`,
  * encoding cardano-ledger 9df9f98c `Binary/Encoding/EncCBOR.hs:487-491`: `array(2)`): a seat of a
  * Leios voting committee, its weight and, if it has one, its BLS12-381 MinSig verification key (96
  * bytes, compressed).
  */
final case class LeiosSeat(weight: Rational, verificationKey: Option[Bytes]) derives Eq, Show, Diff

/** `LeiosCommittee` (cardano-base c1e1ed8d `Leios.hs:140`, encoding 9df9f98c `EncCBOR.hs:493-496`:
  * the list of the seats): the voting committee of an epoch, seats in order. Empty before Dijkstra.
  */
final case class LeiosCommittee(seats: Vector[LeiosSeat]) derives Eq, Show, Diff

object LeiosCommittee:
  /** `emptyLeiosCommittee` (9df9f98c `State/LeiosCommittee.hs:39-41`). */
  val empty: LeiosCommittee = LeiosCommittee(Vector.empty)

/** `SnapShots` (`State/SnapShots.hs:341-347`, encoding `355-362`: `array(4)[mark, set, go, fee]`).
  * Haskell also holds `ssStakeMarkPoolDistr`, lazily, and does not encode it; here it is
  * [[SnapShots.markPoolDistr]]. What the Leios layout adds is in [[SnapShots.Layout]].
  */
final case class SnapShots(
    mark: SnapShot,
    set: SnapShot,
    go: SnapShot,
    fee: Coin,
    layout: SnapShots.Layout
) derives Eq,
      Show,
      Diff

object SnapShots:
  /** The snapshots by layout. The 11.1 layout writes the three snapshots bare. The Leios layout
    * (cardano-ledger 9df9f98c `State/SnapShots.hs:386-582`) wraps them: the mark snapshot as
    * `MarkSnapShot [snapshot, epoch, committee size, maximum key age]`, the epoch at whose start it
    * was taken and the parameters its committee is selected with (`Word16`, `EpochInterval`, both
    * zero before Dijkstra); the set snapshot as `SetSnapShot [snapshot, committee]`, the Leios
    * committee of the epoch it is the leader-election distribution of; the go snapshot as
    * `GoSnapShot [snapshot]`. The pool distributions and the mark snapshot's committee are derived
    * there and not written (`:414-432`, `:485-505`).
    */
  enum Layout extends LayoutPart derives Eq, Show, Diff:
    case Node11_1
    case Leios(
        markEpoch: EpochNo,
        markCommitteeSize: BigInt,
        markMaxKeyAge: BigInt,
        setCommittee: LeiosCommittee
    )

    def stateLayout: StateLayout = this match
      case Node11_1          => StateLayout.Node11_1
      case Leios(_, _, _, _) => StateLayout.Leios

  /** `emptySnapShots` (`State/SnapShots.hs:393-395`), in the 11.1 layout. */
  val empty: SnapShots =
    SnapShots(SnapShot.empty, SnapShot.empty, SnapShot.empty, Coin.zero, Layout.Node11_1)

  val mark: Lens[SnapShots, SnapShot] = Focus[SnapShots](_.mark)
  val set: Lens[SnapShots, SnapShot] = Focus[SnapShots](_.set)
  val go: Lens[SnapShots, SnapShot] = Focus[SnapShots](_.go)
  val fee: Lens[SnapShots, Coin] = Focus[SnapShots](_.fee)
  val layout: Lens[SnapShots, Layout] = Focus[SnapShots](_.layout)

  extension (s: SnapShots)
    /** `ssStakeMarkPoolDistr`, derived (`decSharePlusCBOR`, `State/SnapShots.hs:374`). */
    def markPoolDistr: PoolDistr = PoolDistr.of(s.mark)
