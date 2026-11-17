// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Clock, Ref}
import cats.effect.std.Mutex
import cats.effect.kernel.Async
import cats.mtl.Tell
import cats.syntax.all.*
import scala.concurrent.duration.FiniteDuration
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.consensus.VolatileChain
import xyz.kd5ujc.scarab.ledger.{LedgerRelay, PoolRelayError, PoolRelays, StorageError}
import xyz.kd5ujc.scarab.ledger.state.{
  EraTag,
  KeyHash,
  MirrorError,
  PoolDistr,
  ShelleyBasedEra,
  StakePool
}
import xyz.kd5ujc.scarab.network.selection.*
import xyz.kd5ujc.scarab.node.read.{ChainRead, EraRead, EraViews, InEra, Read}

/** A pool left out of the ledger peers because its registration's relays do not decode. */
final case class SkippedPool(pool: Bytes, error: PoolRelayError)

/** The pools of a ledger state that have a usable relay, largest stake first, and the pools that
  * were left out for the relays they hold.
  */
final case class LedgerPools(pools: Vector[LedgerPool[LedgerRelay]], skipped: Vector[SkippedPool])

object LedgerPools:
  val empty: LedgerPools = LedgerPools(Vector.empty, Vector.empty)

/** What the ledger-peers worker tells besides its answers. */
enum LedgerPeersTrace:
  /** The peer snapshot file was read and is in use while it beats the ledger. */
  case SnapshotLoaded(slot: WithOrigin[BigInt], pools: Int)

  /** The peer snapshot file could not be read (the reference's Praos mode carries on without it:
    * `updateLedgerPeerSnapshot`, `Run.hs` 727-735).
    */
  case SnapshotIgnored(error: PeerSnapshotError)

  /** Whether a Genesis peer snapshot's exact immutable slot and hash were found. */
  case SnapshotVerified(matches: Boolean)

  /** The storage failed while the snapshot was verified: the verification answers no match, as the
    * reference's does for any failure, and this line names the cause, which the reference does not
    * trace (an owner-approved deviation, 2026-10-28, issue 586).
    */
  case SnapshotVerificationFailed(error: StorageError)

  /** The stake maps were rebuilt from `source`, with `ordinary` pools and `big` big ledger pools.
    */
  case Refreshed(source: StakeSource, ordinary: Int, big: Int, slot: WithOrigin[BigInt])

  /** A pool's relays did not decode: the pool is no ledger peer. */
  case PoolSkipped(pool: Bytes, error: PoolRelayError)

  /** The ledger state could not be read: no ledger peers from it this time. */
  case Unreadable(error: MirrorError)

/** The ledger peers of the volatile chain's ledger state, as the reference's `getPeers` of
  * `LedgerSupportsPeerSelection` builds them for a Shelley-based block
  * (`Ouroboros.Consensus.Shelley.Ledger.PeerSelection`, ouroboros-consensus 4.2.1.0, with
  * `queryStakePoolRelays`, cardano-ledger-api `State/Query.hs` 720-736): for every pool of the pool
  * distribution of the epoch (`nesPd`, the *set* snapshot), its share of the stake and its relays,
  * the registered pool's followed by those of its pending re-registration; a pool without a usable
  * relay is no ledger peer; the pools are ordered by stake, largest first, and pools of equal stake
  * keep the order of their ids (`sortOn (Down . fst . snd) . Map.toList`).
  *
  * Only reads. The state is read through [[ChainRead]] / [[EraRead]], the interfaces the other
  * readers use, from a snapshot of the volatile chain; nothing here touches the follower.
  */
trait LedgerPeersSource[F[_]]:
  /** The slot of the tip of the chain (`lpGetLatestSlot`): the origin before the first block. */
  def latestSlot: F[WithOrigin[BigInt]]

  /** The pools with usable relays of the ledger state of the tip. A Byron chain has none. */
  def pools: F[Read[LedgerPools]]

object LedgerPeersSource:

  /** The pools of one era's ledger state. */
  def of[E <: EraTag](read: EraRead[E]): Read[LedgerPools] =
    given ShelleyBasedEra[E] = read.era
    for
      pd <- read.pd
      cert <- read.cert
    yield
      val pstate = EraViews.pState(cert)
      val ordered = byStake(pd)
      val decoded = ordered.map { (id, stake) =>
        val current = pstate.stakePools.get(id).map(_.relays)
        val pending = pstate.future.get(id).map(_.relays)
        val relays = (current.toList ++ pending.toList).traverse(PoolRelays.decode).map(_.flatten)
        (id, stake, relays.map(_.toVector))
      }
      val pools = decoded.flatMap { (_, stake, relays) =>
        relays.toOption.flatMap(PoolRelays.peerRelays).map(LedgerPool(PoolStake(stake), _))
      }
      val skipped = decoded.collect { case (id, _, Left(error)) =>
        SkippedPool(id.bytes, error)
      }
      LedgerPools(pools, skipped)

  /** The pools of the distribution with their share, largest first; equal shares keep the order of
    * the ids (a stable sort of the ascending map).
    */
  private def byStake(pd: PoolDistr): Vector[(KeyHash[StakePool], Rational)] =
    pd.distribution.iterator.map((id, p) => id -> p.stake).toVector.sortBy(_._2)(using larger)

  private val larger: Ordering[Rational] = Ordering.fromLessThan[Rational](_ > _)

  /** The pools of a state read through [[ChainRead]]; none while the chain is in Byron. */
  def of[S: ChainRead](
      state: S,
      tip: Option[xyz.kd5ujc.scarab.consensus.BlockRef]
  ): Read[LedgerPools] =
    state.ledger(tip).flatMap {
      case None                 => Right(LedgerPools.empty)
      case Some(InEra.Of(_, r)) => of(r)
    }

  /** A ledger with no pools: the source of a test and of a node without a chain to read. */
  def none[F[_]: cats.Applicative]: LedgerPeersSource[F] = new LedgerPeersSource[F]:
    def latestSlot: F[WithOrigin[BigInt]] = cats.Applicative[F].pure(WithOrigin.Origin)
    def pools: F[Read[LedgerPools]] = cats.Applicative[F].pure(Right(LedgerPools.empty))

  /** The source of a node: the volatile chain's current state. */
  def fromChain[F[_]: cats.Functor, S: ChainRead](
      chain: F[VolatileChain[S]]
  ): LedgerPeersSource[F] = new LedgerPeersSource[F]:
    def latestSlot: F[WithOrigin[BigInt]] =
      chain.map(c =>
        c.tipRef.fold[WithOrigin[BigInt]](WithOrigin.Origin)(t => WithOrigin.At(t.slot))
      )
    def pools: F[Read[LedgerPools]] = chain.map(c => of(c.state, c.tipRef))

/** A value and the version it was written at: a new write, even of an equal value, has a new one.
  */
final case class Versioned[A](version: BigInt, value: A):
  def next(written: A): Versioned[A] = Versioned(version + 1, written)

/** The ledger-peers worker (`ledgerPeersThread`, `Ouroboros.Network.PeerSelection.LedgerPeers`
  * 225-357): it keeps the stake maps of the ledger peers and of the big ledger peers, rebuilt when
  * their lifetime has passed ([[LedgerPeerStake.refresh]]: from the peer snapshot when that beats
  * the ledger, else from the ledger once it is at the `useLedgerAfterSlot` slot), and answers a
  * request for `count` peers of a kind by the draw of [[LedgerPeerStake.answer]].
  *
  * One request at a time (the reference's worker is a thread answering one at a time). The random
  * generator is the worker's own, split from the governor's seed. The ledger is read only when the
  * cache has expired and the source of the maps could be the ledger, so a request in between costs
  * a draw. The peer snapshot is read from `snapshot` when the maps are rebuilt, as the reference
  * reads `wlpGetLedgerPeerSnapshot` only once its cache has aged out (ouroboros-network c45735a5
  * `ouroboros-network/lib/Ouroboros/Network/PeerSelection/LedgerPeers.hs` 277-287): a new snapshot
  * is taken at the next rebuild, and changes nothing in the cache before it. The reference reads
  * the slot, the ledger's peers and the snapshot in one transaction (284-287); here the snapshot is
  * read before and after the ledger, and the ledger read again until no snapshot was written in
  * between ([[observed]]), so the snapshot used was in force for the whole of the ledger reads.
  * Without ledger peers the snapshot is not read, as in the reference's `DontUseLedgerPeers` branch
  * (280-282). Capabilities: the clock (`Clock`), the trace (`Tell`). The pure parts are
  * [[LedgerPeerStake]]'s.
  */
final class LedgerPeersWorker[F[_]: Async] private (
    use: UseLedgerPeers,
    source: LedgerPeersSource[F],
    snapshot: F[Versioned[Option[LedgerPeerSnapshot[LedgerRelay]]]],
    cache: Ref[F, LedgerPeersCache[LedgerRelay]],
    rng: Ref[F, Rng],
    one: Mutex[F]
)(using trace: Tell[F, LedgerPeersTrace]):
  private val F = Async[F]

  /** The relays drawn, or the fall back to the public roots. */
  def request[K <: LedgerKind](count: Int)(using Kind[K]): F[LedgerAnswer[LedgerRelay]] =
    one.lock.surround {
      for
        now <- Clock[F].monotonic
        fresh <- refreshed(now)
        generator <- rng.get
        (next, answer) = LedgerPeerStake.answer[K, LedgerRelay](fresh, count, generator)
        _ <- rng.set(next)
      yield answer
    }

  private def refreshed(now: FiniteDuration): F[LedgerPeersCache[LedgerRelay]] =
    cache.get.flatMap { current =>
      if !LedgerPeerStake.expired(current, use, now) then F.pure(current)
      else
        for
          (slot, read, snapshotNow) <- observed
          pools <- poolsTraced(read)
          (next, from) = LedgerPeerStake.rebuild(current, use, now, slot, pools, snapshotNow)
          _ <- cache.set(next)
          _ <- trace.tell(
            LedgerPeersTrace.Refreshed(from, next.maps.all.size, next.maps.big.size, slot)
          )
        yield next
    }

  /** The slot, the ledger's pools as read and the snapshot, one observation: the snapshot read
    * before and after the ledger, and the ledger read again while a snapshot was written between
    * the two. Without ledger peers neither the pools nor the snapshot are read.
    */
  private def observed: F[
    (WithOrigin[BigInt], Option[Read[LedgerPools]], Option[LedgerPeerSnapshot[LedgerRelay]])
  ] =
    use match
      case UseLedgerPeers.Disabled =>
        source.latestSlot.map(slot => (slot, None, None))
      case UseLedgerPeers.Use(after) =>
        F.tailRecM(()) { _ =>
          for
            before <- snapshot
            slot <- source.latestSlot
            read <- ledgerPools(after, slot)
            now <- snapshot
          yield Either.cond(now.version == before.version, (slot, read, before.value), ())
        }

  /** The pools of the ledger, read only when the ledger may be the source of the maps. */
  private def ledgerPools(
      after: AfterSlot,
      slot: WithOrigin[BigInt]
  ): F[Option[Read[LedgerPools]]] =
    LedgerPeerStake.supply[LedgerRelay](after, slot, Vector.empty) match
      case LedgerSupply.BeforeSlot() => F.pure(None)
      case LedgerSupply.Pools(_)     => source.pools.map(Some(_))

  /** The pools of a read, with what it could not read traced once. */
  private def poolsTraced(read: Option[Read[LedgerPools]]): F[Vector[LedgerPool[LedgerRelay]]] =
    read match
      case None              => F.pure(Vector.empty)
      case Some(Left(error)) => trace.tell(LedgerPeersTrace.Unreadable(error)).as(Vector.empty)
      case Some(Right(pools)) =>
        pools.skipped.traverse_(s => trace.tell(LedgerPeersTrace.PoolSkipped(s.pool, s.error))) *>
          F.pure(pools.pools)

object LedgerPeersWorker:
  def create[F[_]: Async](
      use: UseLedgerPeers,
      source: LedgerPeersSource[F],
      snapshot: F[Versioned[Option[LedgerPeerSnapshot[LedgerRelay]]]],
      seed: Rng
  )(using Tell[F, LedgerPeersTrace]): F[LedgerPeersWorker[F]] =
    for
      cache <- Ref.of[F, LedgerPeersCache[LedgerRelay]](LedgerPeersCache.empty)
      rng <- Ref.of[F, Rng](seed)
      one <- Mutex[F]
    yield new LedgerPeersWorker[F](use, source, snapshot, cache, rng, one)
