// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Async, Ref, Resource}
import cats.effect.std.Mutex
import cats.effect.syntax.all.*
import cats.mtl.Raise
import cats.syntax.all.*
import fs2.Stream
import fs2.io.file.Path
import scala.annotation.tailrec
import scala.collection.immutable.{HashMap, TreeMap}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{StorageError, TxIn, TxOut}
import xyz.kd5ujc.scarab.storage.{UtxoPinnedReadView, UtxoStore}
import xyz.kd5ujc.scarab.storage.UtxoStore.Coding

/** The UTxO store with a warm tier in front of it (#1484): a copy, bounded in bytes, of some of the
  * store's entries, kept in the store's own encoding, so that a read of one of them costs no read
  * of the disk. The tier holds two populations ([[WarmUtxoStore.Kind]]):
  *
  *   - **young**: the outputs the store's writes create (the changes of the chain's immutable
  *     blocks, `UtxoStore.advance`), from the newest back, the oldest leaving first when the tier
  *     is full. An entry the diff does not decide is at least `k` blocks old, and most of the reads
  *     that reach the store are of outputs a few thousand to a few tens of thousands of blocks old
  *     (#1484's spend-age curve), so the span of blocks the tier covers is what its budget buys;
  *     there is no second bound by age.
  *   - **read**: the entries read since they entered the tier, young ones that were read and
  *     entries read from the store that it did not hold (collateral and reference inputs, which are
  *     read again and again and seldom spent), the least recently read leaving first, within
  *     [[WarmUtxoStore.Budget.readShare]] of the budget.
  *
  * Each entry is charged the bytes of its encoding and [[WarmUtxoStore.EntryBytes]], and the tier
  * never holds more than its budget ([[WarmUtxoStore.Tier]]); a write stages at most a quarter of
  * it on top while the store changes ([[WarmUtxoStore.Budget.staging]]). Its memory is fixed
  * whatever the size of the UTxO set. A budget of 0 holds nothing (the node does not build the tier
  * then).
  *
  * What it serves. [[read]] gives the entries of its keys the tier holds, decoded from the bytes
  * the store would have read (`Coding.encodeValue`, decoded by `Coding.decodeValue`, exactly as
  * `RocksUtxoStore` writes and reads them), and reads the rest from the store. Every read goes
  * through it: the block path (`MirrorRules.backed`, through the read-ahead's `readFor`), the
  * read-ahead's readers (so a key the tier holds is not read from the disk ahead either), the
  * mempool and the forger. The scans (`all`, `select`, `selectAddresses`, `scan`, `entries`), the
  * pinned views of the local state queries (`readView`) and the checkpoints go to the store.
  *
  * Why the tier never serves a value the store does not hold. The invariant is that every entry the
  * tier holds is the store's current value for its key, and it holds by construction, not by what
  * the ledger reads: every change of the store goes through this wrapper ([[write]], [[reset]],
  * [[restore]]; the node holds no other handle on the store), under one lock, and each is a move of
  * the tier ([[WarmUtxoStore.Phase]]):
  *
  *   1. before the store changes, in one step, the tier's epoch advances, it marks itself moving
  *      and removes every key the change touches (a write's deletions and creations alike; a reset
  *      or a restore, everything);
  *   2. the store changes;
  *   3. after it, in one step, the creations of a write that succeeded enter as young, the epoch
  *      advances again and the tier is quiet; a change that failed, or was cancelled, inserts
  *      nothing (a key it may have changed was removed in step 1, and the store's other keys are as
  *      they were).
  *
  * While the store changes, every key the tier holds is one the change does not touch, whose value
  * is the same before and after. An entry read from the store enters the tier (a fill) only with a
  * ticket of the tier as it was before the read was issued (its epoch and phase,
  * [[WarmUtxoStore.Seen]]), and only if the tier was quiet then and its epoch has not moved since:
  * otherwise a change of the store may have come between the read and the fill (a read of K, then a
  * write that deletes K and removes it from the tier, then the fill of the value read), and the
  * fill is refused. A read that started or ended inside a change is refused the same way, as the
  * epoch moves at both ends of a change.
  *
  * The same epoch makes each [[read]] the answer of one read of the store: the entries the tier
  * gave are the store's values from the lookup until the epoch moves, so where it has not moved by
  * the time the rest has been read, all of them are the store's at the moment the rest was read;
  * where it has, the tier's part is dropped and the keys are read from the store again. So the tier
  * is as safe for the mempool and the forger as the store itself.
  *
  * The reference reads a block's tables from its backend when it applies the block, with no tier of
  * entries in front of it (`withValues`, `forkerReadTables fo (getBlockKeySets blk)`,
  * ouroboros-consensus 2416493b
  * `ouroboros-consensus/src/ouroboros-consensus/Ouroboros/Consensus/Storage/LedgerDB/Forker.hs`
  * 695-701); its default backend holds the whole set in memory. Only where an entry is read from
  * changes, never its value: the entries a block is applied with, and so every verdict and state,
  * are those of the store.
  */
final class WarmUtxoStore[F[_]: Async] private (
    store: UtxoStore[F],
    budget: WarmUtxoStore.Budget,
    tier: Ref[F, WarmUtxoStore.Tier],
    lock: Mutex[F]
) extends UtxoStore[F]:
  import WarmUtxoStore.*
  private val F = Async[F]

  /** What the tier holds and has done, for the metrics and the suites. */
  def stats: F[Stats] = tier.get.map(_.stats)

  /** The tier itself, for the suites. */
  private[node] def held: F[Tier] = tier.get

  def read(keys: Iterable[TxIn])(using Raise[F, StorageError]): F[Map[TxIn, TxOut]] =
    val keyed = keys.iterator.flatMap(in => Key.of(in).map(in -> _)).toVector
    if keyed.isEmpty then store.read(keys)
    else
      tier.modify(_.lookup(keyed.map(_._2))).flatMap { (hits, seen) =>
        val served = keyed.iterator.flatMap { (in, key) =>
          hits.get(key).flatMap(Tier.decoded).map(in -> _)
        }.toMap
        val rest = keys.filterNot(served.contains)
        val stored = if rest.isEmpty then F.pure(Map.empty[TxIn, TxOut]) else store.read(rest)
        stored.flatMap { got =>
          // encoded before the step (which may run more than once), and no more than the read
          // population can hold
          val fills = Tier.staged(
            got.iterator.flatMap((in, out) => Key.of(in).map(_ -> out)),
            budget.readShare
          )
          tier.modify(_.filled(seen, hits.size, fills)).flatMap {
            case Landing.Current => F.pure(served ++ got)
            // a change of the store began since the lookup: the tier's part may be older than
            // the rest, so all of it is read from the store, at one moment
            case Landing.Moved => store.read(keys)
          }
        }
      }

  def write(changes: Iterable[(TxIn, Option[TxOut])], stamp: Long)(using
      Raise[F, StorageError]
  ): F[Unit] =
    val batch = changes.toVector
    // A batch of more entries than a write may stage (the hand-over from Byron, the upgrade of
    // the stored outputs on entering Dijkstra) empties the tier and adds nothing, so that what
    // the tier stages for a write is bounded as well as what it keeps (`Budget.staging`).
    if batch.size.toLong * EntryBytes > budget.staging then
      moving(Touched.Everything, store.write(batch, stamp))
    else
      val touched = Touched.Keys(batch.flatMap((in, _) => Key.of(in)))
      // what the batch leaves in the store: the last change of each key, as the store applies
      // them in order (a key created and deleted in one batch is not in the store after it)
      val last = batch.foldLeft(Map.empty[Key, Option[TxOut]]) { case (last, (in, out)) =>
        Key.of(in).fold(last)(last.updated(_, out))
      }
      val created = Tier.staged(
        last.iterator.flatMap((key, out) => out.map(key -> _)),
        budget.staging
      )
      moving(touched, store.write(batch, stamp).flatMap(_ => tier.update(_.moved(created))))

  def reset(using Raise[F, StorageError]): F[Unit] =
    moving(Touched.Everything, store.reset)

  def restore(dir: Path)(using Raise[F, StorageError]): F[Boolean] =
    moving(Touched.Everything, store.restore(dir))

  /** `change` of the store as a move of the tier, under the lock: the keys it touches removed
    * first, and the tier quiet again however the move ends, also where it is cancelled before
    * `change` starts (`change` ends it itself where it succeeds and has creations to add).
    */
  private def moving[A](touched: Touched, change: F[A]): F[A] =
    lock.lock.surround(
      (tier.update(_.moving(touched)) *> change).guarantee(tier.update(_.quiet))
    )

  def stamp(using Raise[F, StorageError]): F[Long] = store.stamp
  def all(using Raise[F, StorageError]): F[Map[TxIn, TxOut]] = store.all
  def select(keep: TxOut => Boolean)(using Raise[F, StorageError]): F[Map[TxIn, TxOut]] =
    store.select(keep)
  def selectAddresses(addresses: Set[Bytes])(using
      Raise[F, StorageError]
  ): F[Map[TxIn, TxOut]] = store.selectAddresses(addresses)
  def flushed(using Raise[F, StorageError]): F[Long] = store.flushed
  def readView: Resource[F, Either[StorageError, UtxoPinnedReadView[F]]] = store.readView
  def scan[A](zero: A)(step: (A, TxIn, TxOut) => A)(using Raise[F, StorageError]): F[A] =
    store.scan(zero)(step)
  def entries(using Raise[F, StorageError]): Stream[F, (TxIn, TxOut)] = store.entries
  def size(using Raise[F, StorageError]): F[Long] = store.size
  def checkpoint(dir: Path)(using Raise[F, StorageError]): F[Unit] = store.checkpoint(dir)
  def memory(using Raise[F, StorageError]): F[Map[String, Long]] = store.memory

object WarmUtxoStore:
  /** The tier's bound in bytes, of which the read population may take [[readShare]]. */
  final case class Budget private (bytes: Long):
    /** The read population's part: an eighth. Collateral and reference inputs are a small set read
      * again and again, and the entries read that a block spends (the store's reads cannot tell
      * them apart) leave it when it is full or when their spend is written; the rest of the budget
      * is the span of the young.
      */
    def readShare: Long = bytes / 8

    /** What one write may stage for the tier, beyond what it holds: a quarter. A write's creations
      * are encoded before the store changes, while the entries they will push out are still held,
      * so the tier's memory at its peak is its budget and this (a fill stages at most the read
      * share, within what its read already holds decoded); a write of more entries than this holds
      * empties the tier instead.
      */
    def staging: Long = bytes / 4

  object Budget:
    /** A budget of `mib` MiB; none below 0. */
    def mib(mib: Int): Budget = Budget(math.max(0L, mib.toLong) * 1024 * 1024)

    /** A budget of `bytes` (the suites). */
    def bytes(bytes: Long): Budget = Budget(math.max(0L, bytes))

    /** 256 MiB, the largest default. At an encoding of 100 to 150 bytes, an entry costs 356 to 406
      * bytes ([[EntryBytes]] more), so the young population's seven eighths hold 580,000 to 660,000
      * entries: 13,000 to 15,000 blocks of 45 outputs (fewer survive the diff to be written, so more
      * blocks), where #1484's spend-age curve puts about 40 percent of the spending reads that reach
      * the store.
      */
    val Default: Budget = mib(256)

    /** The budget when `--utxo-warm-mb` is not given, for a maximum heap of `maxHeap` bytes: a
      * sixteenth of it, at most [[Default]] (192 MiB at `-Xmx3g`, 256 MiB from 4 GiB up), so that a
      * small heap is not asked for the full default (decided 2026-11-27, #1484).
      */
    def forHeap(maxHeap: Long): Budget = bytes(math.min(Default.bytes, maxHeap / 16))

  /** The heap an entry takes beyond its encoding, as charged: its key (the store's 34-byte key in a
    * [[Bytes]], 88 bytes), its encoding's array header and padding (about 20), its record, its
    * place in the map by key and its node and boxed `Long` in its population's order (119
    * together). Measured on the heap of a tier of 400,000 entries (2026-11-27, JDK 25, compressed
    * references): about 226 bytes, rounded up to 256 so that the variation of the maps' layout with
    * their size stays inside the charge.
    */
  val EntryBytes = 256L

  /** The two populations of the tier: written and not read since, and read. */
  enum Kind:
    case Young, Read

  /** Whether a change of the store is under way. */
  enum Phase:
    case Quiet, Moving

  /** What a change of the store touches. */
  enum Touched:
    case Keys(keys: Vector[Key])
    case Everything

  /** Whether a fill came while the tier was where the read found it. */
  enum Landing:
    case Current, Moved

  /** The tier as a lookup found it: a fill carries it to [[Tier.filled]]. */
  final case class Seen(epoch: Long, phase: Phase)

  /** The store's key of an input (`Coding.encodeKey`, `txid ++ be16 ix`): an input that has none is
    * never stored, and never held.
    */
  opaque type Key = Bytes

  object Key:
    def of(in: TxIn): Option[Key] = Coding.encodeKey(in).map(Bytes.wrap)

  /** What the tier holds and has done: its entries and their bytes, by population; its budget; the
    * entries it served and the keys it did not hold; the entries it dropped for its bound, by
    * population; and the fills it refused because the store changed under the read.
    */
  final case class Stats(
      entries: Int,
      youngBytes: Long,
      readBytes: Long,
      budget: Long,
      served: Long,
      missed: Long,
      evictedYoung: Long,
      evictedRead: Long,
      refused: Long
  ):
    def bytes: Long = youngBytes + readBytes

  /** One entry: the store's encoding of its output (`Coding.encodeValue`, never written to), its
    * population and its place in that population's order.
    */
  private final case class Held(value: Array[Byte], kind: Kind, seq: Long):
    def charge: Long = value.length + EntryBytes

  /** The tier: entries by key, each population ordered oldest first (young by when they were
    * written, read by when they were last read), the bytes each takes, the epoch and phase of the
    * moves ([[WarmUtxoStore]] says why), and the counts. A value made by [[Tier.empty]] and the
    * operations below is always within its budget, and its bytes are always the charges of the
    * entries it holds.
    */
  final case class Tier private (
      budget: Budget,
      private val held: HashMap[Key, Held],
      private val young: TreeMap[Long, Key],
      private val reread: TreeMap[Long, Key],
      private val next: Long,
      youngBytes: Long,
      readBytes: Long,
      epoch: Long,
      phase: Phase,
      private val counts: Stats
  ):
    def size: Int = held.size
    def bytes: Long = youngBytes + readBytes
    def holds(key: Key): Boolean = held.contains(key)
    def kindOf(key: Key): Option[Kind] = held.get(key).map(_.kind)

    /** Every entry held, decoded, with its population (the suites). */
    private[node] def contents: Map[Key, (Option[TxOut], Kind)] =
      held.view.mapValues(h => (Tier.decoded(h.value), h.kind)).toMap

    /** The charges of the entries held, by population, counted again (the suites). */
    private[node] def charged: (Long, Long) =
      held.valuesIterator.foldLeft((0L, 0L)) { case ((y, r), h) =>
        h.kind match
          case Kind.Young => (y + h.charge, r)
          case Kind.Read  => (y, r + h.charge)
      }

    def stats: Stats =
      counts.copy(
        entries = held.size,
        youngBytes = youngBytes,
        readBytes = readBytes,
        budget = budget.bytes
      )

    /** The encodings the tier holds of `keys`, each moved to the newest place of the read
      * population, and the tier as the lookup found it (for a fill of what it did not hold).
      */
    def lookup(keys: Iterable[Key]): (Tier, (Map[Key, Array[Byte]], Seen)) =
      val seen = Seen(epoch, phase)
      val (after, hits) = keys.foldLeft((this, Map.empty[Key, Array[Byte]])) {
        case ((t, found), key) =>
          if found.contains(key) then (t, found)
          else
            t.held.get(key) match
              case Some(h) =>
                val moved = t.removed(key).inserted(key, h.value, Kind.Read)
                (moved, found.updated(key, h.value))
              case None => (t.counted(missed = 1), found)
      }
      (after.within, (hits, seen))

    /** Entries read from the store after a lookup that found the tier as `seen` and gave `hits`
      * entries, added to the read population only if the tier was quiet then and has not moved
      * since; otherwise refused, and [[Landing.Moved]] if it has moved (the hits are then dropped,
      * and not counted as served).
      */
    def filled(seen: Seen, hits: Int, read: Iterable[(Key, Array[Byte])]): (Tier, Landing) =
      if seen.epoch != epoch then (counted(refused = read.size.toLong), Landing.Moved)
      else
        val served = counted(served = hits.toLong)
        seen.phase match
          case Phase.Moving => (served.counted(refused = read.size.toLong), Landing.Current)
          case Phase.Quiet =>
            val added = read.foldLeft(served) { case (t, (key, value)) =>
              if t.held.contains(key) then t else t.inserted(key, value, Kind.Read).within
            }
            (added, Landing.Current)

    /** A change of the store begins: the keys it touches removed, in the next epoch. */
    def moving(touched: Touched): Tier =
      val base = touched match
        case Touched.Keys(keys) => keys.foldLeft(this)(_.removed(_))
        case Touched.Everything =>
          copy(
            held = HashMap.empty,
            young = TreeMap.empty,
            reread = TreeMap.empty,
            youngBytes = 0,
            readBytes = 0
          )
      base.copy(epoch = epoch + 1, phase = Phase.Moving)

    /** A write's creations, made in the store, enter as young, each within the budget as it enters
      * (a large batch never takes more), and the tier is quiet in the next epoch.
      */
    def moved(created: Iterable[(Key, Array[Byte])]): Tier =
      created
        .foldLeft(this) { case (t, (key, value)) =>
          t.removed(key).inserted(key, value, Kind.Young).within
        }
        .copy(epoch = epoch + 1, phase = Phase.Quiet)

    /** Quiet again where a change ended without [[moved]] (it failed, was cancelled, or was a reset
      * or a restore), in the next epoch; as it is where it is quiet already.
      */
    def quiet: Tier = phase match
      case Phase.Moving => copy(epoch = epoch + 1, phase = Phase.Quiet)
      case Phase.Quiet  => this

    private def counted(served: Long = 0, missed: Long = 0, refused: Long = 0): Tier =
      copy(counts =
        counts.copy(
          served = counts.served + served,
          missed = counts.missed + missed,
          refused = counts.refused + refused
        )
      )

    private def inserted(key: Key, value: Array[Byte], kind: Kind): Tier =
      val h = Held(value, kind, next)
      val base = copy(held = held.updated(key, h), next = next + 1)
      kind match
        case Kind.Young =>
          base.copy(young = young.updated(h.seq, key), youngBytes = youngBytes + h.charge)
        case Kind.Read =>
          base.copy(reread = reread.updated(h.seq, key), readBytes = readBytes + h.charge)

    private def removed(key: Key): Tier =
      held.get(key).fold(this) { h =>
        val base = copy(held = held - key)
        h.kind match
          case Kind.Young =>
            base.copy(young = young - h.seq, youngBytes = youngBytes - h.charge)
          case Kind.Read =>
            base.copy(reread = reread - h.seq, readBytes = readBytes - h.charge)
      }

    /** The oldest read entries dropped while their population is over its share, then the oldest
      * young ones (and when none is left, the oldest read ones) while the tier is over its budget.
      */
    @tailrec private def within: Tier =
      if readBytes > budget.readShare then
        reread.headOption match
          case Some((_, key)) => evict(key, Kind.Read).within
          case None           => this
      else if bytes > budget.bytes then
        young.headOption.map(_ -> Kind.Young).orElse(reread.headOption.map(_ -> Kind.Read)) match
          case Some(((_, key), kind)) => evict(key, kind).within
          case None                   => this
      else this

    private def evict(key: Key, kind: Kind): Tier =
      val base = removed(key)
      kind match
        case Kind.Young =>
          base.copy(counts = base.counts.copy(evictedYoung = base.counts.evictedYoung + 1))
        case Kind.Read =>
          base.copy(counts = base.counts.copy(evictedRead = base.counts.evictedRead + 1))

  object Tier:
    def empty(budget: Budget): Tier =
      Tier(
        budget,
        HashMap.empty,
        TreeMap.empty,
        TreeMap.empty,
        0,
        0,
        0,
        0,
        Phase.Quiet,
        Stats(0, 0, 0, budget.bytes, 0, 0, 0, 0, 0)
      )

    /** The store's encoding of `out`, as `RocksUtxoStore` writes it. */
    def encoded(out: TxOut): Array[Byte] = Coding.encodeValue(out)

    /** The encodings of `entries`, in their order, as long as their charges stay within `room`:
      * what a fill or a write stages for the tier is never more than it can hold.
      */
    def staged(entries: Iterator[(Key, TxOut)], room: Long): Vector[(Key, Array[Byte])] =
      entries
        .map((key, out) => key -> encoded(out))
        .scanLeft((0L, Option.empty[(Key, Array[Byte])])) { case ((total, _), entry) =>
          (total + entry._2.length + EntryBytes, Some(entry))
        }
        .drop(1)
        .takeWhile((total, _) => total <= room)
        .flatMap(_._2)
        .toVector

    /** The output of a held encoding, as `RocksUtxoStore` reads it; none if it does not decode,
      * which an encoding the tier made cannot fail to do (the key is then read from the store).
      */
    def decoded(value: Array[Byte]): Option[TxOut] = Coding.decodeValue(value).toOption

  /** `store` with a warm tier of `budget` in front of it. */
  def apply[F[_]: Async](store: UtxoStore[F], budget: Budget): F[WarmUtxoStore[F]] =
    (Ref.of[F, Tier](Tier.empty(budget)), Mutex[F]).mapN(new WarmUtxoStore[F](store, budget, _, _))
