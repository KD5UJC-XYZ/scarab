// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.{Async, Ref, Resource}
import cats.effect.std.Mutex
import cats.mtl.Raise
import cats.syntax.all.*
import fs2.io.file.Path
import scala.collection.immutable.Queue
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.ledger.StorageError

/** Original received bodies, including forks and blocks whose selection LoE postpones.
  *
  * ChainDB writes to VolatileDB before selection (the pinned consensus ChainSel.hs `addBlockSync`).
  * The selected-chain log cannot serve this purpose: appending a postponed fork there would change
  * the chain recovered on restart. This separate log uses the existing VolatileStore record format
  * unchanged and never writes rollback records. Collection is driven by a durable immutable slot,
  * rather than the adoption of an individual receipt. As in native VolatileDB, an LoE stall can
  * retain a growing fork forest on disk until immutable progress permits collection.
  *
  * In memory, as in the reference's VolatileDB, there is an index of every block (its reference and
  * where its record is, [[VolatileStore.Location]]) and a body is read back from its log file when
  * asked for. In front of the log is a cache of the most recently received bodies, bounded in bytes
  * ([[ReceivedBlocks.DefaultCacheBytes]]): the oldest bodies leave it first, and its residency is
  * reported ([[residency]]). The bound covers this index's own bodies; the block pool keeps its own
  * references to bodies it has processed.
  */
trait ReceivedBlocks[F[_]]:
  def put(ref: BlockRef, raw: Bytes)(using Raise[F, StorageError]): F[Unit]

  /** Repair this cache from originals already validated by selected-chain resume. Unlike put, this
    * may replace an inconsistent record; network receipts must continue to use put.
    */
  def restoreSelected(ref: BlockRef, raw: Bytes)(using Raise[F, StorageError]): F[Unit]

  /** Exclude a structurally malformed recovered record before live admissions begin. Removing its
    * lookup entry permits a subsequent genuine receipt to append a replacement. On-disk bytes
    * remain subject to validation at reopen until replaced or collected at the durable boundary.
    */
  def discardRecovered(hash: Bytes): F[Unit]

  /** The block, from the cache or read back from the log. A record that no longer passes its
    * checksum is dropped from the index (it is fetched again, as anything the log loses is) and
    * counted in [[ReceivedBlocks.Residency.unreadable]]; a failure of the file system is raised.
    */
  def get(hash: Bytes)(using Raise[F, StorageError]): F[Option[(BlockRef, Bytes)]]

  /** The blocks held, oldest slot first. */
  def refs: F[Vector[BlockRef]]
  def hashes: F[Set[Bytes]]

  /** A validation refusal suppresses this body's reuse until the next full validation on open. Like
    * the reference's in-memory invalid-block set, this is not a peer identity persisted across node
    * lifetimes.
    */
  def invalidate(hash: Bytes): F[Unit]

  /** Only call after the immutable store has made this boundary durable. Keep the cutoff slot:
    * Byron EBBs and regular blocks may share it (native VolatileDB/Impl/FileInfo.hs:94–97).
    */
  def collect(durableSlot: BigInt)(using Raise[F, StorageError]): F[Unit]

  /** What the index holds in memory now, and what its cache has done since it was opened. */
  def residency: F[ReceivedBlocks.Residency]

object ReceivedBlocks:
  /** The default bound of the body cache: 20,000,000 bytes.
    *
    * The reference keeps a received block in memory only while it waits in the queue of blocks to
    * add, of `cdbsBlocksToAddSize = 10` blocks ("the maximum number of blocks that could be kept in
    * memory at the same time", Ouroboros/Consensus/Storage/ChainDB/Impl/Args.hs:71-75 and 122 at
    * `release-ouroboros-consensus-4.2.1.0`); after that it reads the block from its VolatileDB
    * file. The default holds that many blocks of the largest size any era allows, the Byron
    * `maxBlockSize` of 2,000,000 bytes (the Shelley-based eras' body limit is smaller): 10 ×
    * 2,000,000 = 20,000,000.
    */
  val DefaultCacheBytes: Long = 10L * 2_000_000L

  /** @param blocks
    *   blocks in the index
    * @param cachedBlocks
    *   bodies held in memory
    * @param cachedBytes
    *   their bytes, never more than `limitBytes`
    * @param evictions
    *   bodies that left the cache for the bound
    * @param reads
    *   bodies read back from the log
    * @param unreadable
    *   records that failed their checksum when read back, and were dropped
    */
  final case class Residency(
      blocks: Int,
      cachedBlocks: Int,
      cachedBytes: Long,
      limitBytes: Long,
      evictions: Long,
      reads: Long,
      unreadable: Long
  )

  /** The cache of bodies, oldest received first in `order`. */
  private final case class Cache(
      bodies: Map[Bytes, Bytes],
      order: Queue[Bytes],
      bytes: Long,
      evictions: Long
  ):
    def without(drop: Bytes => Boolean): Cache =
      val gone = bodies.filter((hash, _) => drop(hash))
      Cache(
        bodies -- gone.keys,
        order.filterNot(gone.contains),
        bytes - gone.valuesIterator.map(_.size.toLong).sum,
        evictions
      )

    /** `raw` added as the newest body, then the oldest evicted until the cache fits `limit`. A body
      * larger than `limit` is not kept.
      */
    def added(hash: Bytes, raw: Bytes, limit: Long): Cache =
      val fresh = if bodies.contains(hash) then without(_ == hash) else this
      if raw.size.toLong > limit then fresh
      else
        @annotation.tailrec
        def fit(c: Cache): Cache =
          if c.bytes <= limit then c
          else
            c.order.dequeueOption match
              case None => c
              case Some((oldest, rest)) =>
                val size = c.bodies.get(oldest).fold(0L)(_.size.toLong)
                fit(Cache(c.bodies - oldest, rest, c.bytes - size, c.evictions + 1))
        fit(
          Cache(
            fresh.bodies.updated(hash, raw),
            fresh.order.enqueue(hash),
            fresh.bytes + raw.size,
            fresh.evictions
          )
        )

  private object Cache:
    val empty: Cache = Cache(Map.empty, Queue.empty, 0L, 0L)

  private final case class Counters(reads: Long, unreadable: Long)

  def open[F[_]: Async](
      dir: Path,
      blocksPerFile: Int = 512,
      cacheBytes: Long = DefaultCacheBytes
  )(using Raise[F, StorageError]): Resource[F, ReceivedBlocks[F]] =
    for
      opened <- VolatileStore.open[F](dir, blocksPerFile)
      (log, recovered) = opened
      index <- Resource.eval(
        Ref.of[F, Map[Bytes, (BlockRef, VolatileStore.Location)]](
          recovered.located.map(block => block._1.hash -> block).toMap
        )
      )
      cache <- Resource.eval(
        Ref.of[F, Cache](
          recovered.chain.foldLeft(Cache.empty)((c, block) =>
            c.added(block._1.hash, block._2, cacheBytes)
          )
        )
      )
      counters <- Resource.eval(Ref.of[F, Counters](Counters(0L, 0L)))
      lock <- Resource.eval(Mutex[F])
      invalid <- Resource.eval(Ref.of[F, Set[Bytes]](Set.empty))
    yield new ReceivedBlocks[F]:
      private val F = Async[F]

      private def stored(ref: BlockRef, raw: Bytes)(using Raise[F, StorageError]): F[Unit] =
        log
          .append(ref, raw)
          .flatMap(at =>
            index.update(_.updated(ref.hash, ref -> at)) *>
              cache.update(_.added(ref.hash, raw, cacheBytes))
          )

      /** The body of an indexed block, from the cache or the log; the caller holds `lock`, so the
        * file cannot be collected under the read.
        */
      private def body(hash: Bytes)(using
          Raise[F, StorageError]
      ): F[Option[(BlockRef, Bytes)]] =
        index.get.map(_.get(hash)).flatMap {
          case None => F.pure(None)
          case Some((ref, at)) =>
            cache.get.map(_.bodies.get(hash)).flatMap {
              case Some(raw) => F.pure(Some(ref -> raw))
              case None =>
                log.read(at).flatMap {
                  case Some((found, raw)) if found == ref =>
                    counters.update(c => c.copy(reads = c.reads + 1)).as(Some(ref -> raw))
                  case _ =>
                    F.uncancelable(_ =>
                      index.update(_ - hash) *>
                        counters.update(c => c.copy(unreadable = c.unreadable + 1))
                    ).as(None)
                }
            }
        }

      def put(ref: BlockRef, raw: Bytes)(using Raise[F, StorageError]): F[Unit] =
        lock.lock.surround {
          index.get.flatMap { known =>
            F.unlessA(known.contains(ref.hash))(F.uncancelable(_ => stored(ref, raw)))
          }
        }

      def restoreSelected(ref: BlockRef, raw: Bytes)(using Raise[F, StorageError]): F[Unit] =
        lock.lock.surround {
          body(ref.hash).flatMap { found =>
            F.uncancelable { _ =>
              F.unlessA(found.contains(ref -> raw))(stored(ref, raw)) *>
                invalid.update(_ - ref.hash)
            }
          }
        }

      def discardRecovered(hash: Bytes): F[Unit] = lock.lock.surround {
        F.uncancelable(_ =>
          index.update(_ - hash) *> cache.update(c =>
            if c.bodies.contains(hash) then c.without(_ == hash) else c
          ) *>
            invalid.update(_ - hash)
        )
      }

      def get(hash: Bytes)(using Raise[F, StorageError]): F[Option[(BlockRef, Bytes)]] =
        invalid.get.flatMap(refused =>
          if refused(hash) then F.pure(None) else lock.lock.surround(body(hash))
        )

      def refs: F[Vector[BlockRef]] =
        (index.get, invalid.get).mapN { (known, refused) =>
          known.valuesIterator
            .map(_._1)
            .filterNot(ref => refused(ref.hash))
            .toVector
            .sortBy(ref => (ref.slot, ref.hash.hex))
        }

      def hashes: F[Set[Bytes]] =
        (index.get, invalid.get).mapN((known, refused) => known.keySet -- refused)

      def invalidate(hash: Bytes): F[Unit] = invalid.update(_ + hash)

      def collect(durableSlot: BigInt)(using Raise[F, StorageError]): F[Unit] =
        lock.lock.surround {
          F.uncancelable(_ =>
            log.gc(durableSlot - 1) *> index
              .updateAndGet(_.filter(_._2._1.slot >= durableSlot))
              .flatMap(remaining =>
                cache.update(_.without(hash => !remaining.contains(hash))) *>
                  invalid.update(_ intersect remaining.keySet)
              )
          )
        }

      def residency: F[Residency] =
        (index.get, cache.get, counters.get).mapN { (known, c, n) =>
          Residency(
            known.size,
            c.bodies.size,
            c.bytes,
            cacheBytes,
            c.evictions,
            n.reads,
            n.unreadable
          )
        }
