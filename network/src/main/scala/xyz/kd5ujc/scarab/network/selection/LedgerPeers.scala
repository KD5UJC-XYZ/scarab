// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network.selection

import cats.data.NonEmptyVector
import java.math.{BigDecimal as JBigDecimal, MathContext, RoundingMode}
import scala.annotation.tailrec
import scala.collection.immutable.TreeMap
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Rational

/** A slot, or the origin of the chain before the first block (`WithOrigin SlotNo`,
  * `Cardano.Slotting.Slot`).
  */
enum WithOrigin[+A]:
  case Origin
  case At(value: A)

/** Exact rationals in the order the stake maps are keyed by. */
private val rationalOrdering: Ordering[Rational] = Ordering.fromLessThan[Rational](_ < _)

/** A pool's share of the stake: a value from 0 to 1 by convention, as the reference's `PoolStake`
  * (`Ouroboros.Network.PeerSelection.LedgerPeers.Type`, ouroboros-network 1.2.0.0, `Type.hs:490`).
  */
opaque type PoolStake = Rational

object PoolStake:
  def apply(share: Rational): PoolStake = share
  extension (s: PoolStake) def share: Rational = s

/** The accumulated share of a pool and all the pools before it (`AccPoolStake`, `Type.hs:499`). */
opaque type AccPoolStake = Rational

object AccPoolStake:
  def apply(share: Rational): AccPoolStake = share
  extension (s: AccPoolStake) def share: Rational = s

/** The two populations a draw can be from: all ledger peers, or the big ledger peers. A phantom
  * type, so that a stake map of one kind is not drawn with the weighting of the other
  * (`LedgerPeersKind`, `Type.hs:89`).
  */
sealed trait LedgerKind

object LedgerKind:
  sealed trait All extends LedgerKind
  sealed trait Big extends LedgerKind

/** A pool that has relays, with its share of the stake. `R` is the relay (the reference's
  * `RelayAccessPoint`): the pure code is the same for any, as the peer address of the governor is.
  */
final case class LedgerPool[R](stake: PoolStake, relays: NonEmptyVector[R])

/** The pools of one kind keyed by their accumulated stake, so that a uniform number picks a pool in
  * proportion to its stake: the entry with the smallest key at or above the number (`Map.lookupGE`,
  * `LedgerPeers.hs:115-121`). As `Map.fromList`, a key given twice (pools without stake follow a
  * pool that holds the last of it) keeps the last pool.
  */
final class StakeMap[K <: LedgerKind, R] private (
    private val byAccumulated: TreeMap[Rational, LedgerPool[R]]
):
  def isEmpty: Boolean = byAccumulated.isEmpty
  def size: Int = byAccumulated.size

  /** The entries by ascending accumulated stake. */
  def entries: Vector[(AccPoolStake, LedgerPool[R])] =
    byAccumulated.iterator.map((k, v) => AccPoolStake(k) -> v).toVector

  /** The entry with the smallest accumulated stake at or above `x`. */
  def lookupGE(x: Rational): Option[(AccPoolStake, LedgerPool[R])] =
    byAccumulated.minAfter(x).map((k, v) => AccPoolStake(k) -> v)

  override def equals(that: Any): Boolean = that match
    case o: StakeMap[?, ?] => byAccumulated == o.byAccumulated
    case _                 => false
  override def hashCode: Int = byAccumulated.hashCode
  override def toString: String = s"StakeMap(${byAccumulated.size} pools)"

object StakeMap:
  def empty[K <: LedgerKind, R]: StakeMap[K, R] =
    new StakeMap(TreeMap.empty[Rational, LedgerPool[R]](using rationalOrdering))

  /** From entries in the order they are given; the last of equal keys wins. */
  def fromEntries[K <: LedgerKind, R](
      entries: Iterable[(AccPoolStake, LedgerPool[R])]
  ): StakeMap[K, R] =
    val start = TreeMap.empty[Rational, LedgerPool[R]](using rationalOrdering)
    new StakeMap(entries.foldLeft(start)((m, e) => m.updated(e._1.share, e._2)))

/** What a kind of draw needs: how a pool's stake is weighted, how far up the accumulated stake a
  * draw reaches, and which of the maps it draws from.
  */
trait Kind[K <: LedgerKind]:
  /** The weight of a pool before the weights are made to sum to one (`recomputeRelativeStake`). */
  def weight(stake: Rational): Rational

  /** A draw is a uniform number in `[0, scale]` of the accumulated stake. */
  def scale: Rational

  def select[R](maps: StakeMaps[R]): StakeMap[K, R]

object Kind:
  /** Ordinary ledger peers: weighted by the square root of the stake, to flatten the distribution
    * (`recomputeRelativeStake` for `SingAllLedgerPeers`, `API/LedgerPeers/Utils.hs:80-89`); the
    * root is taken as the reference takes it, as a `Double` (`Utils.hs:84-87`: "we care about
    * precision in the order of 1 block per year and for that a Double is good enough").
    */
  given Kind[LedgerKind.All] with
    def weight(stake: Rational): Rational = LedgerPeerStake.sqrtThroughDouble(stake)
    val scale: Rational = Rational.one
    def select[R](maps: StakeMaps[R]): StakeMap[LedgerKind.All, R] = maps.all

  /** Big ledger peers: weighted by the stake itself, and drawn from the first 90% of it. */
  given Kind[LedgerKind.Big] with
    def weight(stake: Rational): Rational = stake
    val scale: Rational = LedgerPeerStake.BigQuota.share
    def select[R](maps: StakeMaps[R]): StakeMap[LedgerKind.Big, R] = maps.big

/** The two maps the ledger peers worker keeps (`peerMap` and `bigPeerMap`,
  * `LedgerPeers.hs:259-260`).
  */
final case class StakeMaps[R](all: StakeMap[LedgerKind.All, R], big: StakeMap[LedgerKind.Big, R]):
  def isEmpty: Boolean = all.isEmpty && big.isEmpty

  /** Pick `count` relays of the kind `K`, with replacement, by the stake (`pickPeers`). */
  def draw[K <: LedgerKind](count: Int, rng: Rng)(using k: Kind[K]): (Rng, Vector[R]) =
    LedgerPeerStake.draw[K, R](this, count, rng)

object StakeMaps:
  def empty[R]: StakeMaps[R] = StakeMaps(StakeMap.empty, StakeMap.empty)

/** Where the ledger peers may be taken from (`AfterSlot`, `Type.hs:477`). */
enum AfterSlot:
  case Always
  case After(slot: BigInt)

  /** The slot as a number: 0 for `Always` (`stakeMapWithSlotOverSource`, `LedgerPeers.hs:436-438`).
    */
  def slotNo: BigInt = this match
    case Always   => BigInt(0)
    case After(s) => s

/** `UseLedgerPeers` (`Type.hs:471`): the setting of `useLedgerAfterSlot` in the topology. */
enum UseLedgerPeers:
  case Disabled
  case Use(after: AfterSlot)

  def isEnabled: Boolean = this match
    case Disabled => false
    case Use(_)   => true

/** The ledger's answer to a request for its peers (`LedgerPeers`, `LedgerPeers.hs:77-84`). */
enum LedgerSupply[R]:
  /** The node is before the configured slot: no peers from the ledger yet. */
  case BeforeSlot()

  /** The pools with usable relays and their shares of the stake. */
  case Pools(pools: Vector[LedgerPool[R]])

/** A snapshot of the big ledger peers (`LedgerPeerSnapshot BigLedgerPeers`, `Type.hs:113-126`): the
  * file named by `peerSnapshotFile`. Version 1 and 2 of the file give the slot; version 23 gives
  * the point of the block the snapshot was taken at and the network magic.
  */
final case class SnapshotPool[R](
    accumulated: AccPoolStake,
    stake: PoolStake,
    relays: NonEmptyVector[R]
)

/** The block a snapshot was taken at (`Point RawBlockHash`): the origin, or a slot and a header
  * hash.
  */
enum SnapshotPoint:
  case Origin
  case Block(slot: BigInt, hash: xyz.kd5ujc.scarab.codec.Bytes)

  def slotNo: WithOrigin[BigInt] = this match
    case Origin      => WithOrigin.Origin
    case Block(s, _) => WithOrigin.At(s)

enum LedgerPeerSnapshot[R]:
  /** Versions 1 and 2 of the file (the reference reads both as `LedgerPeerSnapshotV2`). */
  case V2(atSlot: WithOrigin[BigInt], bigPools: Vector[SnapshotPool[R]])

  /** Version 23 (`LedgerBigPeerSnapshotV23`). */
  case V23(point: SnapshotPoint, magic: Long, bigPools: Vector[SnapshotPool[R]])

  def slot: WithOrigin[BigInt] = this match
    case V2(s, _)     => s
    case V23(p, _, _) => p.slotNo

  def pools: Vector[SnapshotPool[R]] = this match
    case V2(_, ps)     => ps
    case V23(_, _, ps) => ps

/** Where the maps of a refresh came from. */
enum StakeSource:
  case Snapshot, Ledger, Nothing

/** The result of choosing the source of the stake maps (`stakeMapWithSlotOverSource`). */
final case class ChosenStake[R](maps: StakeMaps[R], cachedSlot: Option[BigInt], source: StakeSource)

/** What the worker holds between requests: the maps, the slot of the snapshot they were made from,
  * and when they were made (`go rng oldTs peerMap bigPeerMap cachedSlot`,
  * `LedgerPeers.hs:255-263`). `stamp` is none before the first refresh, which the reference spells
  * `Time 0`.
  */
final case class LedgerPeersCache[R](
    maps: StakeMaps[R],
    cachedSlot: Option[BigInt],
    stamp: Option[FiniteDuration]
)

object LedgerPeersCache:
  def empty[R]: LedgerPeersCache[R] = LedgerPeersCache(StakeMaps.empty, None, None)

/** An answer to a request for peers (`ledgerPeersThread`, `LedgerPeers.hs:308-357`). */
enum LedgerAnswer[R]:
  /** Both maps are empty: the request falls back to the public roots (`putResp Nothing`). */
  case FallBackToPublicRoots()

  /** The relays picked, and whether fewer pools than requested exist (`NotEnoughLedgerPeers`). */
  case Picked(relays: Vector[R], notEnough: Boolean)

/** Ledger peers and big ledger peers: the stake maps, the draws, the source of the stake and the
  * cache lifetimes, as `Ouroboros.Network.PeerSelection.LedgerPeers` and `.LedgerPeers.Utils` of
  * ouroboros-network 1.2.0.0 define them. Everything here is a pure function; the randomness is the
  * explicit [[Rng]] of the governor, so a draw is a function of the generator it is given. (The
  * streams are not the reference's `StdGen`: the Word64 of a draw is the same kind of value, the
  * relay index is drawn as `Rng.between` draws it, not as `randomR`.)
  *
  * The input of every function is the list of pools with usable relays, in the order the ledger
  * gives them (largest stake first, `PeerSelection.hs:33-35` of ouroboros-consensus 4.2.1.0) with
  * their shares of the stake of the registered pools; building that list from the ledger state is
  * the node's job.
  */
object LedgerPeerStake:
  /** Big ledger peers hold 90% of the stake (`bigLedgerPeerQuota`, `Utils.hs:27-28`). */
  val BigQuota: AccPoolStake = AccPoolStake(Rational(9, 10))

  /** How often the governor asks again: the `ttl` of an answer (`LedgerPeers.hs:315`). */
  val ReRequestInterval: FiniteDuration = 5.seconds

  /** The lifetime of the cached list while it is empty and ledger peers are on
    * (`short_PEER_LIST_LIFE_TIME`, `LedgerPeers.hs:217-218`).
    */
  val EmptyLifetime: FiniteDuration = 30.seconds

  /** The lifetime otherwise: close to 30 minutes, and prime (`long_PEER_LIST_LIFE_TIME`,
    * `LedgerPeers.hs:222-223`).
    */
  val PopulatedLifetime: FiniteDuration = 1847.seconds

  private val Word64Max: BigInt = (BigInt(1) << 64) - 1

  // ------------------------------------------------------------------------------ weights

  /** `fromRational`, the square root, `toRational`: the weight of an ordinary ledger peer
    * (`Utils.hs:87`). The conversion to a `Double` rounds to nearest from a 40-digit quotient,
    * which differs from GHC's exact `fromRational` only for a stake within 1e-40 of a rounding tie;
    * the way back is exact.
    */
  def sqrtThroughDouble(stake: Rational): Rational =
    if stake.numerator.signum <= 0 then Rational.zero
    else sqrtOfPositive(stake)

  private def sqrtOfPositive(stake: Rational): Rational =
    val quotient =
      new JBigDecimal(stake.numerator.bigInteger).divide(
        new JBigDecimal(stake.denominator.bigInteger),
        new MathContext(40, RoundingMode.HALF_EVEN)
      )
    val root = math.sqrt(quotient.doubleValue)
    Rational.fromDecimal(BigDecimal(new JBigDecimal(root)))

  /** The pools with the weight of the kind `K`, made to sum to one (`recomputeRelativeStake`,
    * `Utils.hs:62-78`). The reference divides by the total and fails when it is zero; no pool has
    * any weight then, and none is returned.
    */
  def recompute[K <: LedgerKind, R](pools: Vector[LedgerPool[R]])(using
      k: Kind[K]
  ): Vector[LedgerPool[R]] =
    val weighted = pools.map(p => p.copy(stake = PoolStake(k.weight(p.stake.share))))
    val total = weighted.foldLeft(Rational.zero)((t, p) => t + p.stake.share)
    if total.isZero then Vector.empty
    else weighted.map(p => p.copy(stake = PoolStake(p.stake.share / total)))

  // ------------------------------------------------------------------------------ the maps

  /** The map of all ledger peers (`accPoolStake`, `LedgerPeers.hs:123-140`): weighted by the square
    * root of the stake, accumulated in the order given.
    */
  def weighted[R](pools: Vector[LedgerPool[R]]): StakeMap[LedgerKind.All, R] =
    val (_, entries) =
      recompute[LedgerKind.All, R](pools).foldLeft(
        (Rational.zero, Vector.empty[(AccPoolStake, LedgerPool[R])])
      ) { case ((acc, out), p) =>
        val next = acc + p.stake.share
        (next, out :+ (AccPoolStake(next) -> p))
      }
    StakeMap.fromEntries(entries)

  /** The pools that hold the first 90% of the stake, largest first, each with the stake accumulated
    * up to and including it (`accumulateBigLedgerStake`, `Utils.hs:34-57`). A pool is kept while
    * the stake before it is at most the quota, so the pool that crosses the quota is kept and, when
    * the stake reaches the quota exactly, so is the next one.
    */
  def accumulated[R](pools: Vector[LedgerPool[R]]): Vector[(AccPoolStake, LedgerPool[R])] =
    // `sortOn (Down . fst)` is stable: pools of equal stake keep their order
    val sorted =
      recompute[LedgerKind.Big, R](pools).sortBy(_.stake.share)(using rationalOrdering.reverse)
    @tailrec def go(
        rest: Vector[LedgerPool[R]],
        acc: Rational,
        out: Vector[(AccPoolStake, LedgerPool[R])]
    ): Vector[(AccPoolStake, LedgerPool[R])] =
      rest.headOption match
        case Some(p) if acc <= BigQuota.share =>
          val next = acc + p.stake.share
          go(rest.tail, next, out :+ (AccPoolStake(next) -> p))
        case _ => out
    go(sorted, Rational.zero, Vector.empty)

  /** The map of big ledger peers (`accBigPoolStakeMap`, `LedgerPeers.hs:144-149`). */
  def accumulate[R](pools: Vector[LedgerPool[R]]): StakeMap[LedgerKind.Big, R] =
    StakeMap.fromEntries(accumulated(pools))

  /** Both maps from the ledger's pools. */
  def fromLedger[R](pools: Vector[LedgerPool[R]]): StakeMaps[R] =
    StakeMaps(weighted(pools), accumulate(pools))

  /** Both maps from a snapshot of the big ledger peers: the big map is the snapshot as it is, and
    * the ordinary map is made from the same pools (`stakeMapWithSlotOverSource`,
    * `LedgerPeers.hs:429-431`).
    */
  def fromSnapshot[R](snapshot: LedgerPeerSnapshot[R]): StakeMaps[R] =
    StakeMaps(
      weighted(snapshot.pools.map(p => LedgerPool(p.stake, p.relays))),
      StakeMap.fromEntries(snapshot.pools.map(p => p.accumulated -> LedgerPool(p.stake, p.relays)))
    )

  // ------------------------------------------------------------------------------ the draw

  /** Pick `count` relays, with replacement, by the stake of their pools (`pickPeers`,
    * `LedgerPeers.hs:158-211`): each draw is a uniform 64-bit number scaled to `[0, scale]` of the
    * accumulated stake, the pool at or above it, and a uniformly chosen relay of that pool. A
    * number that finds no pool still counts as a draw. When the map of all ledger peers is empty
    * nothing is drawn and the generator is returned as it came, whatever the kind
    * (`LedgerPeers.hs:169`).
    */
  def draw[K <: LedgerKind, R](maps: StakeMaps[R], count: Int, rng: Rng)(using
      k: Kind[K]
  ): (Rng, Vector[R]) =
    val from = k.select(maps)
    @tailrec def go(g: Rng, n: Int, picked: Vector[R]): (Rng, Vector[R]) =
      if n <= 0 then (g, picked)
      else
        val (bits, g1) = g.nextLong
        val unsigned = (BigInt(bits) & Word64Max)
        val x = Rational(unsigned, Word64Max) * k.scale
        from.lookupGE(x) match
          case None => go(g1, n - 1, picked)
          case Some((_, pool)) =>
            val relays = pool.relays.toVector
            val (ix, g2) = g1.between(0L, (relays.size - 1).toLong)
            go(g2, n - 1, picked :+ relays(ix.toInt))
    if maps.all.isEmpty then (rng, Vector.empty) else go(rng, count, Vector.empty)

  /** The worker's answer to a request for `count` peers of the kind `K` from the cache. */
  def answer[K <: LedgerKind, R](cache: LedgerPeersCache[R], count: Int, rng: Rng)(using
      k: Kind[K]
  ): (Rng, LedgerAnswer[R]) =
    if cache.maps.isEmpty then (rng, LedgerAnswer.FallBackToPublicRoots())
    else
      val (next, relays) = draw[K, R](cache.maps, count, rng)
      (next, LedgerAnswer.Picked(relays, count > k.select(cache.maps).size))

  // ------------------------------------------------------------------------------ the source

  /** Whether the ledger may be asked for peers at the latest slot it has (`getLedgerPeers`,
    * `LedgerPeers.hs:97-113`): always with `Always`, else when the ledger is at or past the slot.
    */
  def supply[R](
      after: AfterSlot,
      latest: WithOrigin[BigInt],
      pools: => Vector[LedgerPool[R]]
  ): LedgerSupply[R] = (after, latest) match
    case (AfterSlot.Always, _)                                => LedgerSupply.Pools(pools)
    case (AfterSlot.After(s), WithOrigin.At(cur)) if cur >= s => LedgerSupply.Pools(pools)
    case _                                                    => LedgerSupply.BeforeSlot()

  /** Whether a snapshot is used instead of the ledger. The snapshot must be at a block, not at the
    * origin; at or after the ledger's slot (the origin counts as 0); and at or after
    * `useLedgerAfterSlot` (`Always` counts as 0): `LedgerPeers.hs:419-438`.
    */
  def snapshotBeatsLedger[R](
      snapshot: LedgerPeerSnapshot[R],
      ledgerSlot: WithOrigin[BigInt],
      after: AfterSlot
  ): Option[BigInt] = snapshot.slot match
    case WithOrigin.At(s) =>
      val ledgerAt = ledgerSlot match
        case WithOrigin.Origin => BigInt(0)
        case WithOrigin.At(l)  => l
      Option.when(s >= ledgerAt && s >= after.slotNo)(s)
    case WithOrigin.Origin => None

  /** Choose the source of the maps (`stakeMapWithSlotOverSource`, `LedgerPeers.hs:403-445`): the
    * snapshot when it beats the ledger (the maps already built from the same snapshot are kept, by
    * its slot); else the ledger's pools when it supplies them; else nothing. When the snapshot is
    * chosen, both maps are built from it, the map of all ledger peers too.
    */
  def stakeMapWithSlotOverSource[R](
      ledgerSlot: WithOrigin[BigInt],
      ledger: LedgerSupply[R],
      snapshot: Option[LedgerPeerSnapshot[R]],
      cached: LedgerPeersCache[R],
      after: AfterSlot
  ): ChosenStake[R] =
    snapshot.flatMap(s => snapshotBeatsLedger(s, ledgerSlot, after).map(s -> _)) match
      case Some((_, slot)) if cached.cachedSlot.contains(slot) =>
        ChosenStake(cached.maps, cached.cachedSlot, StakeSource.Snapshot)
      case Some((s, slot)) => ChosenStake(fromSnapshot(s), Some(slot), StakeSource.Snapshot)
      case None =>
        ledger match
          case LedgerSupply.Pools(pools) => ChosenStake(fromLedger(pools), None, StakeSource.Ledger)
          case LedgerSupply.BeforeSlot() => ChosenStake(StakeMaps.empty, None, StakeSource.Nothing)

  // ------------------------------------------------------------------------------ the cache

  /** How long the cached list lives: 30 s while the map of ledger peers is empty and ledger peers
    * are on, else 1847 s (`LedgerPeers.hs:270-272`).
    */
  def lifetime[R](cache: LedgerPeersCache[R], use: UseLedgerPeers): FiniteDuration =
    if cache.maps.all.isEmpty && use.isEnabled then EmptyLifetime else PopulatedLifetime

  /** Whether the cache is to be rebuilt at `now` (a monotonic time): it was never built, or its age
    * is more than its lifetime (`age > peerListLifeTime`, `LedgerPeers.hs:276-278`).
    */
  def expired[R](cache: LedgerPeersCache[R], use: UseLedgerPeers, now: FiniteDuration): Boolean =
    cache.stamp.forall(made => now - made > lifetime(cache, use))

  /** The cache after a request at `now` (`LedgerPeers.hs:277-306`): kept while it lives; else
    * emptied when ledger peers are off, else rebuilt from the chosen source and stamped `now` (also
    * when the result is empty, so an empty list is asked for again after 30 s).
    */
  def refresh[R](
      cache: LedgerPeersCache[R],
      use: UseLedgerPeers,
      now: FiniteDuration,
      ledgerSlot: WithOrigin[BigInt],
      ledgerPools: => Vector[LedgerPool[R]],
      snapshot: Option[LedgerPeerSnapshot[R]]
  ): LedgerPeersCache[R] =
    if !expired(cache, use, now) then cache
    else rebuild(cache, use, now, ledgerSlot, ledgerPools, snapshot)._1

  /** The cache as it is rebuilt at `now`, and where its maps came from (a lifetime that has passed
    * is not asked: [[refresh]] asks it).
    */
  def rebuild[R](
      cache: LedgerPeersCache[R],
      use: UseLedgerPeers,
      now: FiniteDuration,
      ledgerSlot: WithOrigin[BigInt],
      ledgerPools: => Vector[LedgerPool[R]],
      snapshot: Option[LedgerPeerSnapshot[R]]
  ): (LedgerPeersCache[R], StakeSource) =
    use match
      case UseLedgerPeers.Disabled =>
        (LedgerPeersCache(StakeMaps.empty, None, Some(now)), StakeSource.Nothing)
      case UseLedgerPeers.Use(after) =>
        val chosen = stakeMapWithSlotOverSource(
          ledgerSlot,
          supply(after, ledgerSlot, ledgerPools),
          snapshot,
          cache,
          after
        )
        (LedgerPeersCache(chosen.maps, chosen.cachedSlot, Some(now)), chosen.source)
