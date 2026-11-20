// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.{Async, Ref, Resource}
import cats.effect.std.{Mutex, SecureRandom, Semaphore, UUIDGen}
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import fs2.io.file.Path
import java.nio.channels.{FileChannel, FileLock}
import java.util.concurrent.{ConcurrentHashMap, Semaphore as JSemaphore}
import java.nio.charset.StandardCharsets.US_ASCII
import java.nio.file.{Files, StandardCopyOption, StandardOpenOption}
import java.util.Arrays
import org.rocksdb.{
  ColumnFamilyDescriptor,
  ColumnFamilyHandle,
  ColumnFamilyOptions,
  DBOptions,
  Options,
  ReadOptions,
  RocksDB,
  WriteBatch,
  WriteOptions
}
import scala.annotation.tailrec
import scala.collection.immutable.{SortedMap, SortedSet}
import scala.jdk.CollectionConverters.*
import scala.util.Using
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.{EbHash, EbPoint, LeiosTxHash}
import xyz.kd5ujc.scarab.protocol.LeiosWord.{Word32, Word64}

/** What the store holds of one endorser block: its points with their announced sizes and statuses,
  * its body, the transactions stored for it by offset, and whether its closure is complete. What
  * the import from and the export to the reference's database exchange.
  */
final case class StoredEb(
    hash: EbHash,
    points: Vector[(Word64, Long, EbStatus)],
    body: Option[Bytes],
    txs: SortedMap[Int, Bytes],
    complete: Boolean
)

/** The Leios store in RocksDB, at `leios/` in the node's database directory, beside the chain and
  * UTxO stores and not part of the ledger snapshot. A prototype format of the Leios testnet build,
  * unstable like the build's other layouts: a store of another format or version is refused, never
  * migrated. Its column families:
  *
  *   - `meta`: `format` to the magic `SCRBLEIO` and the version, a 32-bit big-endian integer (1);
  *   - `points`: the slot (64-bit big-endian) and the endorser-block hash (32 bytes) to the
  *     announced size (64-bit big-endian) and the status ([[EbStatus.code]]);
  *   - `bodies`: the hash to the body's encoding ([[LeiosEb.encoded]]);
  *   - `txs`: the hash and the transaction's offset (32-bit big-endian) to its bytes;
  *   - `complete`: the hash to nothing, present once every transaction the body lists is stored.
  *
  * The reference keeps the same records in SQLite: `ebs`, `ebBodies` and `ebClosures`, the closure
  * one blob of every transaction with zeroes for those not written (`Impl/SQLite/Schema.hs:74-92`
  * at 2416493b), and which closures are incomplete in memory (`Impl/SQLite/Completion.hs`). The
  * points of each hash, and which of them are volatile, are held in memory, read once when the
  * store opens, as the reference's `idx_ebs_ebHashBytes` and `idx_ebs_sweepable` indexes find them.
  *
  * Writes are serialised (`writes`), as the reference's one write queue serialises them, and each
  * is one atomic batch with RocksDB's write-ahead log, which makes it durable: the store has no
  * checkpoint to be rebuilt from. A read sees the store at one moment, through a RocksDB snapshot
  * ([[RocksLeiosStore.View]]), as the reference reads inside one SQLite transaction. Every call
  * holds a permit of `gate` while it runs and the release takes all of them, so the database is
  * never closed under a call. A first open holds an exclusive lock of the operating system on
  * `<dir>.lock`, beside the store, while it makes, syncs, checks and opens the store
  * ([[RocksLeiosStore.firstOpen]]); the lock file is not part of the store, as the reference's
  * SQLite files have SQLite's own locking.
  *
  * One store holds what the reference splits between its volatile and immutable files. A hash the
  * reference's collection evicted from its volatile file has its points [[EbStatus.Evicted]]: its
  * body and transactions stay, as the immutable file keeps them, and what the reference reads from
  * its volatile file alone (the point scans, a novel point's completion, a write of transactions)
  * does not see it, until its body is written again, which empties its closure for its transactions
  * to be written again, as the reference's volatile file has it then, and makes its evicted points
  * immutable again, so that a later collection evicts them again and keeps the body and
  * transactions. A hash evicted again before they are holds only what was written again, less than
  * the reference's immutable file holds (an approved Deviation, `LeiosStore.writeEbBody`).
  */
final class RocksLeiosStore[F[_]] private (
    db: RocksDB,
    families: RocksLeiosStore.Families,
    writes: Mutex[F],
    gate: Semaphore[F],
    released: Ref[F, Boolean],
    state: Ref[F, RocksLeiosStore.Points]
)(using F: Async[F])
    extends LeiosStore[F]:
  import RocksLeiosStore.*
  import RocksLeiosStore.given

  private val writeOptions = new WriteOptions()

  // what a write reads: the latest state, since writes are serialised
  private val latest = new ReadOptions()

  private def run[A](body: => Either[LeiosStoreError, A])(using Raise[F, LeiosStoreError]): F[A] =
    F.blocking(body).attempt.flatMap {
      case Right(Right(a)) => F.pure(a)
      case Right(Left(e))  => e.raise[F, A]
      case Left(e)         => LeiosStoreError.Io(e).raise[F, A]
    }

  /** `use` while the store is open, holding a permit of `gate`. */
  private def opened[A](use: F[A])(using Raise[F, LeiosStoreError]): F[A] =
    gate.permit.surround(released.get.flatMap {
      case true  => LeiosStoreError.Closed.raise[F, A]
      case false => use
    })

  /** A write: `body` sees the points of every hash and gives the new ones with its result, which
    * replace them once its batch is written. The batch and the replacement are not cancelable
    * apart, so the points held in memory are always those on disk: collection reads them to tell
    * that no point names a hash any more.
    */
  private def writing[A](body: ReadOptions ?=> Points => Either[LeiosStoreError, (A, Points)])(using
      Raise[F, LeiosStoreError]
  ): F[A] =
    writes.lock.surround(
      F.uncancelable(_ =>
        state.get
          .flatMap(p => opened(run(body(using latest)(p))))
          .flatMap((result, next) => state.set(next).as(result))
      )
    )

  /** `use` with a [[View]]: every read made through it sees the store at the moment it was taken. A
    * view taken by a write's caller does not see that write.
    */
  private[storage] def viewing[A](use: View => F[A])(using Raise[F, LeiosStoreError]): F[A] =
    opened(
      Resource
        .make(run(Right {
          val snapshot = db.getSnapshot()
          snapshot -> new ReadOptions().setSnapshot(snapshot)
        })) { (snapshot, options) =>
          F.blocking {
            options.close()
            db.releaseSnapshot(snapshot)
          }
        }
        .use((_, options) => use(new View(options)))
    )

  private def reading[A](body: ReadOptions ?=> Either[LeiosStoreError, A])(using
      Raise[F, LeiosStoreError]
  ): F[A] =
    viewing(v => in(v)(body))

  private def in[A](view: View)(body: ReadOptions ?=> Either[LeiosStoreError, A])(using
      Raise[F, LeiosStoreError]
  ): F[A] =
    run(body(using view.options))

  // --- records ---

  private def get(family: ColumnFamilyHandle, key: Array[Byte])(using
      read: ReadOptions
  ): Option[Array[Byte]] =
    Option(db.get(family, read, key))

  private def has(family: ColumnFamilyHandle, key: Array[Byte])(using read: ReadOptions): Boolean =
    db.keyExists(family, read, key)

  /** One pass over the records of `family` from `from` while `keep` holds of their keys. */
  private def fold[A](
      family: ColumnFamilyHandle,
      from: Array[Byte],
      keep: Array[Byte] => Boolean,
      zero: A
  )(
      step: (A, Array[Byte], Array[Byte]) => Either[LeiosStoreError, A]
  )(using read: ReadOptions): Either[LeiosStoreError, A] =
    Using.resource(db.newIterator(family, read)) { it =>
      @tailrec def go(acc: A): Either[LeiosStoreError, A] =
        if it.isValid && keep(it.key) then
          step(acc, it.key, it.value) match
            case Right(next) =>
              it.next()
              go(next)
            case stopped => stopped
        else Right(acc)
      it.seek(from)
      val result = go(zero)
      it.status()
      result
    }

  /** Whether a record of `family` has a key that starts with `prefix`. */
  private def holdsPrefix(family: ColumnFamilyHandle, prefix: Array[Byte])(using
      read: ReadOptions
  ): Boolean =
    Using.resource(db.newIterator(family, read)) { it =>
      it.seek(prefix)
      val found = it.isValid && startsWith(it.key, prefix)
      it.status()
      found
    }

  private def commit(ops: Vector[Op]): Unit =
    Using.resource(new WriteBatch()) { batch =>
      ops.foreach {
        case Op.Put(family, key, value)        => batch.put(families(family), key, value)
        case Op.Delete(family, key)            => batch.delete(families(family), key)
        case Op.DeleteRange(family, from, end) => batch.deleteRange(families(family), from, end)
      }
      db.write(writeOptions, batch)
    }

  private def body(hash: EbHash, key: Array[Byte])(using
      ReadOptions
  ): Either[LeiosStoreError, Option[LeiosEb]] =
    get(families.bodies, key).traverse(raw =>
      LeiosEb.decode(Bytes.wrap(raw)).leftMap(LeiosStoreError.CorruptBody(hash, _))
    )

  /** The transactions stored for the hash, by offset. */
  private def transactions(key: Array[Byte])(using
      ReadOptions
  ): Either[LeiosStoreError, SortedMap[Int, Bytes]] =
    fold(families.txs, key, startsWith(_, key), SortedMap.empty[Int, Bytes]) { (held, k, v) =>
      txOffset(k).map(held.updated(_, Bytes.wrap(v)))
    }

  private def heldOffsets(key: Array[Byte])(using ReadOptions): Either[LeiosStoreError, Set[Int]] =
    fold(families.txs, key, startsWith(_, key), Set.empty[Int])((held, k, _) =>
      txOffset(k).map(held + _)
    )

  /** Whether the collection evicted the hash from the reference's volatile partition. */
  private def evicted(p: Points, hash: EbHash, key: Array[Byte])(using
      ReadOptions
  ): Either[LeiosStoreError, Boolean] =
    pointsOf(p, hash, key).map(_.exists(_._4 == EbStatus.Evicted))

  private def storedPoint(key: Array[Byte])(using
      ReadOptions
  ): Either[LeiosStoreError, (Long, EbStatus)] =
    get(families.points, key)
      .toRight(LeiosStoreError.Corrupt(LeiosRecord.PointKey, Bytes.wrap(key)))
      .flatMap(pointRecord(key, _))

  /** The points of the hash, as stored. */
  private def pointsOf(p: Points, hash: EbHash, key: Array[Byte])(using ReadOptions) =
    p.slotsOf(hash).toVector.traverse { slot =>
      val k = pointKey(slotBits(slot), key)
      storedPoint(k).map((size, status) => (slot, k, size, status))
    }

  /** The reference's copier for a hash whose first transaction is stored: its pinned points become
    * copied (`sql_mark_as_copied`, `Impl/SQLite/Queries.hs:125-127` at 2416493b).
    */
  private def copied(p: Points, hash: EbHash, key: Array[Byte])(using
      ReadOptions
  ): Either[LeiosStoreError, (Vector[Op], Points)] =
    pointsOf(p, hash, key).map { rows =>
      val pinned = rows.collect { case (slot, k, size, EbStatus.Certified) => (slot, k, size) }
      (
        pinned.map((_, k, size) => Op.Put(Family.Points, k, pointValue(size, EbStatus.Immutable))),
        pinned.foldLeft(p)((q, r) => q.restated(hash, r._1, EbStatus.Immutable))
      )
    }

  // --- writes ---

  def writeEbPoint(point: EbPoint, announced: Word32)(using
      Raise[F, LeiosStoreError]
  ): F[Vector[EbPoint]] =
    writing { p =>
      hashKey(point.hash).flatMap { key =>
        val k = pointKey(slotBits(point.slot), key)
        if has(families.points, k) then Right((Vector.empty, p))
        else
          evicted(p, point.hash, key).map { gone =>
            commit(
              Vector(Op.Put(Family.Points, k, pointValue(announced.toLong, EbStatus.Volatile)))
            )
            // the reference completes a novel point whose body it holds in its volatile file
            val completed =
              if !gone && has(families.complete, key) then Vector(point) else Vector.empty
            (completed, p.added(point.hash, point.slot, EbStatus.Volatile))
          }
      }
    }

  def writeEbBody(point: EbPoint, eb: LeiosEb, fills: Vector[LocalFill])(using
      Raise[F, LeiosStoreError]
  ): F[BodyWritten] =
    writing { p =>
      for
        key <- hashKey(point.hash)
        _ <- Either.cond(
          has(families.points, pointKey(slotBits(point.slot), key)),
          (),
          LeiosStoreError.PointNotWritten(point)
        )
        gone <- evicted(p, point.hash, key)
        rows <- pointsOf(p, point.hash, key)
        result <-
          if has(families.bodies, key) && !gone then
            Right((BodyWritten(Vector.empty, Vector.empty), p))
          else
            // a body written again after its hash was evicted: the reference's volatile file takes
            // it with an empty closure, and its immutable file still holds the evicted points, which
            // become immutable again (present in the volatile file once more), so that a later
            // collection evicts them again and keeps the body and transactions
            val retired = rows.filter(_._4 == EbStatus.Evicted)
            val kept =
              retired.foldLeft(p)((q, r) => q.restated(point.hash, r._1, EbStatus.Immutable))
            val filled = fills.foldLeft(SortedMap.empty[Int, Bytes]) { (acc, fill) =>
              if acc.contains(fill.offset) then acc
              else filledFrom(p, eb, fill).fold(acc)(acc.updated(fill.offset, _))
            }
            val complete = filled.sizeIs == eb.size
            (if filled.isEmpty then Right((Vector.empty, kept)) else copied(kept, point.hash, key))
              .map { (copy, next) =>
                commit(
                  retired
                    .map(r => Op.Put(Family.Points, r._2, pointValue(r._3, EbStatus.Immutable))) ++
                    Option
                      .when(gone)(
                        Vector(
                          Op.Delete(Family.Complete, key),
                          Op.DeleteRange(Family.Txs, txKey(key, 0), txRangeEnd(key))
                        )
                      )
                      .getOrElse(Vector.empty) ++
                    Vector(Op.Put(Family.Bodies, key, eb.encoded.toArray)) ++
                    filled.toVector.map((o, tx) => Op.Put(Family.Txs, txKey(key, o), tx.toArray)) ++
                    Option.when(complete)(Op.Put(Family.Complete, key, Empty)) ++ copy
                )
                val completed =
                  if complete then
                    kept.pointsOf(point.hash).filterNot(c => retired.exists(_._1 == c.slot))
                  else Vector.empty
                (BodyWritten(completed, filled.keys.toVector), next)
              }
      yield result
    }

  /** The bytes a fill copies, if its source holds a transaction of the declared size whose hash is
    * the one the body lists at the fill's offset (`fillFrom`, `Impl/SQLite/Insert.hs:204-237` at
    * 2416493b).
    */
  private def filledFrom(p: Points, eb: LeiosEb, fill: LocalFill)(using
      ReadOptions
  ): Option[Bytes] =
    for
      (listed, declared) <- eb.transactions.get(fill.offset)
      source = fill.source
      if source.offset >= 0 && source.hash.bytes.size == LeiosEb.HashBytes
      // the reference fills from its volatile file only
      if evicted(p, source.hash, source.hash.bytes.toArray).contains(false)
      raw <- get(families.txs, txKey(source.hash.bytes.toArray, source.offset))
      tx = Bytes.wrap(raw)
      if tx.size.toLong == declared.toLong && LeiosEb.written(tx) &&
        LeiosEb.hashTx(tx).bytes == listed.bytes
    yield tx

  def writeTxs(point: EbPoint, txs: Vector[(Int, Bytes)])(using
      Raise[F, LeiosStoreError]
  ): F[Vector[EbPoint]] =
    writing { p =>
      for
        key <- hashKey(point.hash)
        gone <- evicted(p, point.hash, key)
        // the reference drops what it has no volatile body for
        stored <- if gone then Right(None) else body(point.hash, key)
        result <- stored.fold(Right((Vector.empty[EbPoint], p))) { eb =>
          // the first of each offset, as the reference keeps it (`Insert.hs:287-297` at 2416493b)
          val accepted = txs.foldLeft(SortedMap.empty[Int, Bytes]) { case (acc, (o, tx)) =>
            if acc.contains(o) || !eb.declared(o).contains(tx.size.toLong) || !LeiosEb.written(tx)
            then acc
            else acc.updated(o, tx)
          }
          for
            held <- heldOffsets(key)
            fresh = accepted.view.filterKeys(o => !held.contains(o)).toVector
            completes = !has(families.complete, key) && held.size + fresh.size == eb.size
            copying <-
              if held.isEmpty && fresh.nonEmpty then copied(p, point.hash, key)
              else Right((Vector.empty, p))
            (copy, next) = copying
          yield
            commit(
              fresh.map((o, tx) => Op.Put(Family.Txs, txKey(key, o), tx.toArray)) ++
                Option.when(completes)(Op.Put(Family.Complete, key, Empty)) ++ copy
            )
            (if completes then p.pointsOf(point.hash) else Vector.empty, next)
        }
      yield result
    }

  /** Collection, in writes of at most [[GcBatch]] hashes each, as the reference's sweeper evicts a
    * batch of hashes at a time between the writer's jobs (`sweepEbBatch`,
    * `Impl/SQLite/Maintenance.hs:305-325` at 2416493b). A hash is decided once in the write that
    * takes it, from its points in the reference's volatile file then: unless one is certified or at
    * or after `slot` (`sql_gc_markable`, `Impl/SQLite/Queries.hs:193-200` at 2416493b), all its
    * volatile points go in that write and its immutable ones become evicted, and its body and
    * transactions go with them if no point is left, so no hash is ever left half collected. Only
    * the volatile and immutable points older than `slot` are visited, from the points held in
    * memory (the reference's `idx_ebs_sweepable`), so a collection that removes nothing costs no
    * more however many evicted points the store holds.
    */
  def garbageCollect(slot: Word64)(using Raise[F, LeiosStoreError]): F[Unit] =
    def after(cursor: Option[(Word64, EbHash)]): F[Unit] =
      collectBatch(slot, cursor).flatMap(_.fold(F.unit)(last => after(Some(last))))
    after(None)

  /** One write of a collection: the hashes of the next [[GcBatch]] collectable points older than
    * `slot` after `cursor`, and the last of those points, from which the next write goes on; none
    * when there was none.
    */
  private[storage] def collectBatch(slot: Word64, cursor: Option[(Word64, EbHash)])(using
      Raise[F, LeiosStoreError]
  ): F[Option[(Word64, EbHash)]] =
    val frontier = slot.toBigInt
    writing { p =>
      val pending = cursor
        .fold(p.sweepable.iterator)(c => p.sweepable.iteratorFrom(c).filter(_ != c))
        .takeWhile(_._1.toBigInt < frontier)
        .to(LazyList)
      @tailrec def gather(
          rest: LazyList[(Word64, EbHash)],
          hashes: Vector[EbHash],
          last: Option[(Word64, EbHash)]
      ): (Vector[EbHash], Option[(Word64, EbHash)]) =
        rest match
          case (point @ (_, hash)) #:: more =>
            if hashes.contains(hash) then gather(more, hashes, Some(point))
            else if hashes.sizeIs >= GcBatch then (hashes, last)
            else gather(more, hashes :+ hash, Some(point))
          case _ => (hashes, last)
      val (hashes, last) = gather(pending, Vector.empty, None)
      hashes
        .foldLeftM((Vector.empty[Op], p)) { case ((ops, q), hash) =>
          val key = hash.bytes.toArray
          pointsOf(q, hash, key).map { rows =>
            val live = rows.filter(_._4 != EbStatus.Evicted)
            val vetoed = live.exists(r => r._4 == EbStatus.Certified || r._1.toBigInt >= frontier)
            val gone = if vetoed then Vector.empty else live.filter(_._4 == EbStatus.Volatile)
            val kept = if vetoed then Vector.empty else live.filter(_._4 == EbStatus.Immutable)
            val next = kept.foldLeft(gone.foldLeft(q)((acc, r) => acc.removed(hash, r._1))) {
              (acc, r) => acc.restated(hash, r._1, EbStatus.Evicted)
            }
            val swept =
              if gone.isEmpty || next.slotsOf(hash).nonEmpty then Vector.empty
              else
                Vector(
                  Op.Delete(Family.Bodies, key),
                  Op.Delete(Family.Complete, key),
                  Op.DeleteRange(Family.Txs, txKey(key, 0), txRangeEnd(key))
                )
            val evictions =
              kept.map(r => Op.Put(Family.Points, r._2, pointValue(r._3, EbStatus.Evicted)))
            ((ops ++ gone.map(r => Op.Delete(Family.Points, r._2)) ++ evictions) ++ swept, next)
          }
        }
        .map { (ops, next) =>
          commit(ops)
          (last, next)
        }
    }

  def promoteToImmutable(points: Vector[EbPoint])(using Raise[F, LeiosStoreError]): F[Unit] =
    writing { p =>
      points
        .map(_.hash)
        .distinct
        .flatTraverse { hash =>
          hashKey(hash).flatMap { key =>
            evicted(p, hash, key).flatMap { gone =>
              // copied once the reference's volatile file holds a transaction of it
              val promoted =
                if !gone && holdsPrefix(families.txs, key) then EbStatus.Immutable
                else EbStatus.Certified
              pointsOf(p, hash, key).map(_.collect {
                case (slot, k, size, status)
                    if (status == EbStatus.Volatile || status == EbStatus.Certified) &&
                      status != promoted =>
                  (hash, slot, promoted, Op.Put(Family.Points, k, pointValue(size, promoted)))
              })
            }
          }
        }
        .map { changes =>
          commit(changes.map(_._4))
          val next = changes.foldLeft(p) { case (q, (hash, slot, status, _)) =>
            q.restated(hash, slot, status)
          }
          ((), next)
        }
    }

  // --- reads ---

  def lookupEbBody(hash: EbHash)(using Raise[F, LeiosStoreError]): F[Option[Bytes]] =
    reading(hashKey(hash).map(key => get(families.bodies, key).map(Bytes.wrap)))

  def lookupEbClosure(hash: EbHash)(using
      Raise[F, LeiosStoreError]
  ): F[Option[Vector[(LeiosTxHash, Bytes)]]] =
    viewing(lookupEbClosureIn(_, hash))

  private[storage] def lookupEbClosureIn(view: View, hash: EbHash)(using
      Raise[F, LeiosStoreError]
  ): F[Option[Vector[(LeiosTxHash, Bytes)]]] =
    in(view)(hashKey(hash).flatMap { key =>
      if !has(families.complete, key) then Right(None)
      else
        (body(hash, key), transactions(key)).mapN { (eb, held) =>
          eb.flatMap(_.transactions.toVector.zipWithIndex.traverse { case ((txHash, _), o) =>
            held.get(o).map(txHash -> _)
          })
        }
    })

  def batchRetrieveTxs(hash: EbHash, offsets: Vector[Int])(using
      Raise[F, LeiosStoreError]
  ): F[Vector[(Int, Bytes)]] =
    viewing(batchRetrieveTxsIn(_, hash, offsets))

  private[storage] def batchRetrieveTxsIn(view: View, hash: EbHash, offsets: Vector[Int])(using
      Raise[F, LeiosStoreError]
  ): F[Vector[(Int, Bytes)]] =
    in(view)(hashKey(hash).flatMap { key =>
      body(hash, key).flatMap(_.fold(Right(Vector.empty)) { eb =>
        offsets.distinct.sorted.flatMap(o => eb.declared(o).map(o -> _)).traverse { (o, size) =>
          get(families.txs, txKey(key, o)) match
            case Some(raw) => Right(o -> Bytes.wrap(raw))
            case None =>
              Either.cond(
                size <= Int.MaxValue,
                o -> Bytes.wrap(new Array[Byte](size.toInt)),
                LeiosStoreError.DeclaredSizeTooLarge(hash, o, size)
              )
        }
      })
    })

  def scanEbPoints(using Raise[F, LeiosStoreError]): F[Vector[EbPoint]] =
    reading(
      fold(families.points, Array.emptyByteArray, _ => true, Vector.empty[EbPoint]) { (acc, k, v) =>
        (pointKeyed(k), pointRecord(k, v)).mapN((point, record) =>
          if record._2 == EbStatus.Evicted then acc else acc :+ point
        )
      }
    )

  def scanCompleteEbClosuresNotOlderThanSlot(slot: Word64)(using
      Raise[F, LeiosStoreError]
  ): F[Vector[EbPoint]] =
    viewing(scanCompleteIn(_, slot))

  private[storage] def scanCompleteIn(view: View, slot: Word64)(using
      Raise[F, LeiosStoreError]
  ): F[Vector[EbPoint]] =
    val from = slot.toBigInt
    // every point, for the hashes evicted from the reference's volatile file, wherever their points
    in(view)(
      fold(
        families.points,
        Array.emptyByteArray,
        _ => true,
        (Vector.empty[EbPoint], Set.empty[EbHash])
      ) { case ((listed, gone), k, v) =>
        (pointKeyed(k), pointRecord(k, v)).mapN { (p, record) =>
          if record._2 == EbStatus.Evicted then (listed, gone + p.hash)
          else if p.slot.toBigInt >= from && has(families.complete, p.hash.bytes.toArray) then
            (listed :+ p, gone)
          else (listed, gone)
        }
      }.map((listed, gone) => listed.filterNot(p => gone.contains(p.hash)))
    )

  // --- what the import from and the export to the reference's database exchange ---

  /** Merge points read from the reference's database: a point already stored keeps the later of the
    * two statuses.
    */
  private[storage] def importPoints(points: Vector[(EbPoint, Word32, EbStatus)])(using
      Raise[F, LeiosStoreError]
  ): F[Unit] =
    writing { p =>
      points
        .traverse { (point, size, status) =>
          hashKey(point.hash).flatMap { key =>
            val k = pointKey(slotBits(point.slot), key)
            get(families.points, k)
              .traverse(pointRecord(k, _))
              .map { stored =>
                val kept = stored.fold(status)((_, s) => if s.code > status.code then s else status)
                (Op.Put(Family.Points, k, pointValue(size.toLong, kept)), point, kept)
              }
          }
        }
        .map { written =>
          commit(written.map(_._1))
          ((), written.foldLeft(p)((q, w) => q.added(w._2.hash, w._2.slot, w._3)))
        }
    }

  /** Merge a body and the written transactions of its closure read from the reference's database.
    * The body must be the one stored, if one is.
    */
  private[storage] def importBody(
      hash: EbHash,
      raw: Bytes,
      eb: LeiosEb,
      txs: SortedMap[Int, Bytes]
  )(using
      Raise[F, LeiosStoreError]
  ): F[Unit] =
    writing { p =>
      for
        key <- hashKey(hash)
        _ <- get(families.bodies, key).fold(Right(())) { stored =>
          Either.cond(Bytes.wrap(stored) == raw, (), LeiosStoreError.BodyConflict(hash))
        }
        held <- heldOffsets(key)
      yield
        commit(
          Vector(Op.Put(Family.Bodies, key, raw.toArray)) ++
            txs.toVector.map((o, tx) => Op.Put(Family.Txs, txKey(key, o), tx.toArray)) ++
            Option.when((held ++ txs.keySet).sizeIs == eb.size)(Op.Put(Family.Complete, key, Empty))
        )
        ((), p)
    }

  /** `use` with every hash the store holds a point or a body of, in byte order, and what it holds
    * of each: all read from one view, so that what is enumerated and what is read are the store at
    * one moment, whatever a collection does meanwhile.
    */
  private[storage] def exporting[A](use: (Vector[EbHash], EbHash => F[StoredEb]) => F[A])(using
      Raise[F, LeiosStoreError]
  ): F[A] =
    viewing { v =>
      in(v)(pointsByHash).flatMap { points =>
        in(v)(fold(families.bodies, Array.emptyByteArray, _ => true, points.keySet) { (acc, k, _) =>
          Right(acc + EbHash.fromBytes(Bytes.wrap(k)))
        }).flatMap { hashes =>
          use(
            hashes.toVector.sorted,
            hash => in(v)(storedIn(hash, points.getOrElse(hash, Vector.empty)))
          )
        }
      }
    }

  /** What the store holds of `hash`, read from one view. */
  private[storage] def stored(hash: EbHash)(using Raise[F, LeiosStoreError]): F[StoredEb] =
    exporting((_, read) => read(hash))

  /** Every point, by hash, with its announced size and status. */
  private def pointsByHash(using
      ReadOptions
  ): Either[LeiosStoreError, Map[EbHash, Vector[(Word64, Long, EbStatus)]]] =
    fold(
      families.points,
      Array.emptyByteArray,
      _ => true,
      Map.empty[EbHash, Vector[(Word64, Long, EbStatus)]]
    ) { (acc, k, v) =>
      (pointKeyed(k), pointRecord(k, v)).mapN { (point, record) =>
        acc.updated(
          point.hash,
          acc.getOrElse(point.hash, Vector.empty) :+ (point.slot, record._1, record._2)
        )
      }
    }

  private def storedIn(hash: EbHash, points: Vector[(Word64, Long, EbStatus)])(using
      ReadOptions
  ): Either[LeiosStoreError, StoredEb] =
    hashKey(hash).flatMap { key =>
      transactions(key).map(held =>
        StoredEb(
          hash,
          points,
          get(families.bodies, key).map(Bytes.wrap),
          held,
          has(families.complete, key)
        )
      )
    }

  private def closeOptions(): Unit =
    latest.close()
    writeOptions.close()

object RocksLeiosStore:
  RocksDB.loadLibrary()

  /** The store's magic: the first eight bytes of its format record. */
  val Magic: String = "SCRBLEIO"

  /** The version of the store's layout. */
  val Version: Int = 1

  /** The format record a store of this build holds. */
  val FormatRecord: Bytes = Bytes.wrap(Magic.getBytes(US_ASCII) ++ be(Version.toLong, 4))

  /** The most hashes one write of a collection takes. */
  val GcBatch: Int = 256

  private val FormatKey: Array[Byte] = "format".getBytes(US_ASCII)
  private val Empty: Array[Byte] = Array.emptyByteArray
  private val SlotBytes = 8
  private val PointKeyBytes = SlotBytes + LeiosEb.HashBytes
  private val PointValueBytes = 9
  private val OffsetBytes = 4

  /** The store at one moment: the reads made through a view see a RocksDB snapshot. */
  private[storage] final class View(private[RocksLeiosStore] val options: ReadOptions)

  /** The column families, in the order they are opened after the default one. */
  private[storage] enum Family(val label: String):
    case Meta extends Family("meta")
    case Points extends Family("points")
    case Bodies extends Family("bodies")
    case Txs extends Family("txs")
    case Complete extends Family("complete")

  /** The handles, the default family's first, as RocksJava returns them for [[FamilyNames]]. */
  private[storage] final class Families(handles: Vector[ColumnFamilyHandle]):
    def apply(family: Family): ColumnFamilyHandle = handles(family.ordinal + 1)
    val meta: ColumnFamilyHandle = apply(Family.Meta)
    val points: ColumnFamilyHandle = apply(Family.Points)
    val bodies: ColumnFamilyHandle = apply(Family.Bodies)
    val txs: ColumnFamilyHandle = apply(Family.Txs)
    val complete: ColumnFamilyHandle = apply(Family.Complete)
    def all: Vector[ColumnFamilyHandle] = handles

  private enum Op:
    case Put(family: Family, key: Array[Byte], value: Array[Byte])
    case Delete(family: Family, key: Array[Byte])
    case DeleteRange(family: Family, from: Array[Byte], end: Array[Byte])

  private given Ordering[Word64] = Ordering.by(_.toBigInt)
  private given Ordering[EbHash] = Ordering.fromLessThan((a, b) => a.bytes.compareTo(b.bytes) < 0)

  /** The points held in memory: the slots of each hash's points, and the volatile and immutable
    * points, the ones a collection may take (the reference's `idx_ebs_sweepable`), by slot.
    */
  private[storage] final case class Points(
      slots: Map[EbHash, SortedSet[Word64]],
      sweepable: SortedSet[(Word64, EbHash)]
  )

  private object Points:
    val empty: Points = Points(Map.empty, SortedSet.empty)

  extension (p: Points)
    private def slotsOf(hash: EbHash): SortedSet[Word64] = p.slots.getOrElse(hash, SortedSet.empty)
    private def pointsOf(hash: EbHash): Vector[EbPoint] =
      slotsOf(hash).toVector.map(EbPoint(_, hash))

    /** A point stored with `status`. */
    private def added(hash: EbHash, slot: Word64, status: EbStatus): Points =
      Points(p.slots.updated(hash, slotsOf(hash) + slot), p.sweepable).restated(hash, slot, status)

    /** A point's status changed to `status`. */
    private def restated(hash: EbHash, slot: Word64, status: EbStatus): Points = status match
      case EbStatus.Volatile | EbStatus.Immutable =>
        p.copy(sweepable = p.sweepable + (slot -> hash))
      case EbStatus.Certified | EbStatus.Evicted => p.copy(sweepable = p.sweepable - (slot -> hash))

    private def removed(hash: EbHash, slot: Word64): Points =
      val left = slotsOf(hash) - slot
      Points(
        if left.isEmpty then p.slots - hash else p.slots.updated(hash, left),
        p.sweepable - (slot -> hash)
      )

  private[storage] def be(n: Long, width: Int): Array[Byte] =
    Array.tabulate(width)(i => (n >>> (8 * (width - 1 - i))).toByte)

  private def readBe(bytes: Array[Byte], from: Int, width: Int): Long =
    (from until from + width).foldLeft(0L)((n, i) => (n << 8) | (bytes(i) & 0xffL))

  private def startsWith(key: Array[Byte], prefix: Array[Byte]): Boolean =
    key.length >= prefix.length && Arrays.equals(key, 0, prefix.length, prefix, 0, prefix.length)

  /** A slot's 64 bits, its unsigned value. */
  private def slotBits(slot: Word64): Long = slot.toBigInt.longValue

  private def slotFromBits(bits: Long): Option[Word64] =
    Word64.from(BigInt(bits) & ((BigInt(1) << 64) - 1)).toOption

  private def slotOfKey(key: Array[Byte]): Long = readBe(key, 0, SlotBytes)

  private def hashKey(hash: EbHash): Either[LeiosStoreError, Array[Byte]] =
    Either.cond(
      hash.bytes.size == LeiosEb.HashBytes,
      hash.bytes.toArray,
      LeiosStoreError.HashLength(hash.bytes.size)
    )

  private def pointKey(slot: Long, hash: Array[Byte]): Array[Byte] = be(slot, SlotBytes) ++ hash

  private def pointKeyed(key: Array[Byte]): Either[LeiosStoreError, EbPoint] =
    Option
      .when(key.length == PointKeyBytes)(slotFromBits(slotOfKey(key)))
      .flatten
      .map(EbPoint(_, EbHash.fromBytes(Bytes.wrap(key.drop(SlotBytes)))))
      .toRight(LeiosStoreError.Corrupt(LeiosRecord.PointKey, Bytes.wrap(key)))

  private def pointValue(size: Long, status: EbStatus): Array[Byte] =
    be(size, SlotBytes) :+ status.code

  private def pointRecord(
      key: Array[Byte],
      value: Array[Byte]
  ): Either[LeiosStoreError, (Long, EbStatus)] =
    Option
      .when(value.length == PointValueBytes)(EbStatus.fromCode(value(SlotBytes)))
      .flatten
      .map(readBe(value, 0, SlotBytes) -> _)
      .toRight(LeiosStoreError.Corrupt(LeiosRecord.PointValue, Bytes.wrap(key)))

  private def txKey(hash: Array[Byte], offset: Int): Array[Byte] =
    hash ++ be(offset.toLong, OffsetBytes)

  /** The end, exclusive, of the transaction keys of a hash: offsets are non-negative `Int`s. */
  private def txRangeEnd(hash: Array[Byte]): Array[Byte] = hash ++ be(1L << 31, OffsetBytes)

  private def txOffset(key: Array[Byte]): Either[LeiosStoreError, Int] =
    Option
      .when(key.length == LeiosEb.HashBytes + OffsetBytes)(
        readBe(key, LeiosEb.HashBytes, OffsetBytes)
      )
      .filter(_ <= Int.MaxValue)
      .map(_.toInt)
      .toRight(LeiosStoreError.Corrupt(LeiosRecord.TxKey, Bytes.wrap(key)))

  private val DefaultFamily: Bytes = Bytes.wrap(RocksDB.DEFAULT_COLUMN_FAMILY)
  private val FamilyNames: Vector[Bytes] =
    DefaultFamily +: Family.values.toVector.map(f => Bytes.wrap(f.label.getBytes(US_ASCII)))

  /** The native objects an open database uses, closed after it; `create` for a new one. */
  private final class Natives(create: Boolean) extends AutoCloseable:
    val db = new DBOptions().setCreateIfMissing(create).setCreateMissingColumnFamilies(create)
    val families = new ColumnFamilyOptions()
    def close(): Unit =
      families.close()
      db.close()

  /** An open database and its families' handles, closed before it. */
  private final class Opened(val db: RocksDB, val families: Families) extends AutoCloseable:
    def close(): Unit =
      families.all.foreach(_.close())
      db.close()

  // RocksJava returns the handles by filling a list it is given (FUN-2 (b)).
  private def openAt(dir: java.nio.file.Path, natives: Natives): Opened =
    val descriptors =
      FamilyNames.map(n => new ColumnFamilyDescriptor(n.toArray, natives.families))
    val handles = new java.util.ArrayList[ColumnFamilyHandle]()
    val db = RocksDB.open(natives.db, dir.toString, descriptors.asJava, handles)
    new Opened(db, new Families(handles.asScala.toVector))

  /** Where the first opens of `dir` stage a new store, each in a directory of its own. */
  private[storage] def stagingRoot(dir: java.nio.file.Path): java.nio.file.Path =
    dir.resolveSibling(s"${dir.getFileName}.staging")

  /** The file a staging directory of this store holds, written before anything else in it: only a
    * directory that holds it is ever removed as a leftover of a first open.
    */
  private[storage] val StagingMarker: String = ".scarab-leios-staging"

  /** Make the families and the synced format record of a new store in `stage`, marked as staging.
    */
  private def build(stage: java.nio.file.Path): Unit =
    Files.createDirectories(stage.getParent)
    Files.createDirectory(stage)
    Files.write(stage.resolve(StagingMarker), Array.emptyByteArray)
    Using.resource(new Natives(create = true)) { natives =>
      Using.resource(openAt(stage, natives)) { opened =>
        Using.resource(new WriteOptions().setSync(true))(sync =>
          opened.db.put(opened.families.meta, sync, FormatKey, FormatRecord.toArray)
        )
      }
    }

  /** Publish `stage` as `dir`, which is absent or an empty directory: an atomic rename. */
  private def publish(stage: java.nio.file.Path, dir: java.nio.file.Path): Unit =
    if Files.isDirectory(dir) then Files.delete(dir)
    Files.move(stage, dir, StandardCopyOption.ATOMIC_MOVE)

  /** Make a rename in `dir`'s parent durable. */
  private def syncParent(dir: java.nio.file.Path): Unit =
    Using.resource(FileChannel.open(dir.toAbsolutePath.getParent, StandardOpenOption.READ))(
      _.force(true)
    )

  /** The first opens of this process in progress, one permit per lock file (by its real path): the
    * operating system's record locks belong to the process, so they cannot keep two opens of one
    * process apart, and closing one open's channel would drop the lock another holds. A kept site
    * of process-wide state (EFF-4) and of a Java concurrent type (FUN-2 (b), the record locks'
    * contract): it holds nothing but these permits, and only [[inProcess]] touches it.
    */
  private val opening = new ConcurrentHashMap[java.nio.file.Path, JSemaphore]()

  /** This process's permit for the lock file at `path`, held for the resource's life. */
  private def inProcess[F[_]: Async](path: java.nio.file.Path): Resource[F, java.nio.file.Path] =
    Resource
      .make(Async[F].interruptible {
        val permit = opening.computeIfAbsent(path, _ => new JSemaphore(1))
        permit.acquire()
        permit
      })(permit => Async[F].delay(permit.release()))
      .as(path)

  /** The lock a first open of `dir` holds: a sibling of `dir` in its parent, not part of the store.
    */
  private[storage] def lockFile(dir: java.nio.file.Path): java.nio.file.Path =
    dir.resolveSibling(s"${dir.getFileName}.lock")

  /** The staging directories of `dir` that first opens left, those that hold the marker. */
  private def stagedLeftovers(dir: java.nio.file.Path): Vector[java.nio.file.Path] =
    val root = stagingRoot(dir)
    if !Files.isDirectory(root) then Vector.empty
    else
      Using.resource(Files.list(root))(
        _.iterator.asScala
          .filter(p => Files.isDirectory(p) && Files.isRegularFile(p.resolve(StagingMarker)))
          .toVector
      )

  /** Remove the staging root of `dir` if nothing is left in it. */
  private def discardRoot(dir: java.nio.file.Path): Unit =
    val root = stagingRoot(dir)
    if Files.isDirectory(root) && Using.resource(Files.list(root))(_.findAny().isEmpty) then
      Files.delete(root)

  /** `open` once `dir` holds a store of this build's families, all under an exclusive lock of the
    * operating system on [[lockFile]], so that first opens, of this process or another, run one at
    * a time. Under the lock, in a straight line: a store already at `dir` (it holds `CURRENT`) has
    * its parent directory synced with `sync`, so that a store a crashed open published is durable
    * before it is taken, and its column families checked; an absent or empty `dir` gets a new
    * store, made in a marked staging directory of its own (`<dir>.staging/<random UUID>`),
    * published by an atomic rename and synced the same way; anything else is refused. The staging
    * directories earlier opens left are removed, on a best effort. The lock is given back once
    * `open` is done.
    *
    * The operating system's record locks belong to the process: within one process a second lock is
    * refused, and closing any channel of the file drops the lock another channel holds. So the
    * opens of one process are serialised first by [[opening]], from before the lock file is opened
    * until after it is closed, and the operating-system lock only ever excludes other processes.
    *
    * Ancestors of `dir` that do not exist are made, and each made directory's entry in its parent
    * synced with `sync`, walking up to the first that existed, so that the store's path is durable.
    */
  private[storage] def firstOpen[F[_]: Async, A](dir: Path, sync: java.nio.file.Path => F[Unit])(
      open: F[A]
  )(using Raise[F, LeiosStoreError]): F[A] =
    val F = Async[F]
    val nio = dir.toNioPath
    def fail[B](e: LeiosStoreError): F[B] = e.raise[F, B]
    def io[B](body: => B): F[B] =
      F.blocking(body).attempt.flatMap(_.fold(e => fail(LeiosStoreError.Io(e)), F.pure))
    def bestEffort(body: => Unit): F[Unit] = F.blocking(body).attempt.void
    val synced: F[Unit] =
      sync(nio).attempt.flatMap(_.fold(e => fail(LeiosStoreError.Io(e)), F.pure))
    val parent = nio.toAbsolutePath.getParent
    // the ancestors of `dir` that are missing, outermost first: each is made, then its entry synced
    val ancestors: F[Unit] =
      io(
        Iterator
          .iterate(Option(parent))(_.flatMap(d => Option(d.getParent)))
          .takeWhile(_.exists(Files.notExists(_)))
          .flatten
          .toVector
          .reverse
      ).flatMap { missing =>
        io(Files.createDirectories(parent)) *> missing.traverse_(made =>
          sync(made).attempt.flatMap(_.fold(e => fail(LeiosStoreError.Io(e)), F.pure))
        )
      }
    val locked: Resource[F, FileLock] =
      Resource
        .eval(ancestors *> io(parent.toRealPath().resolve(lockFile(nio).getFileName)))
        .flatMap(inProcess[F])
        .flatMap(path =>
          Resource.make(
            io(FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE))
          )(channel => F.blocking(channel.close()))
        )
        .flatMap(channel => Resource.make(io(channel.lock()))(lock => F.blocking(lock.release())))
    val validated: F[Unit] =
      io(Using.resource(new Options())(o => RocksDB.listColumnFamilies(o, nio.toString)))
        .map(_.asScala.toVector.map(Bytes.wrap))
        .flatMap(found =>
          F.unlessA(found.toSet == FamilyNames.toSet)(fail(LeiosStoreError.ForeignDatabase(found)))
        )
    val made: F[Unit] =
      SecureRandom
        .javaSecuritySecureRandom[F]
        .flatMap(random => UUIDGen.fromSecureRandom[F](using F, random).randomUUID)
        .flatMap { id =>
          val stage = stagingRoot(nio).resolve(id.toString)
          F.blocking {
            build(stage)
            publish(stage, nio)
            Files.delete(nio.resolve(StagingMarker))
          }.attempt
            .flatMap {
              case Right(()) => synced
              case Left(e) =>
                bestEffort(RocksUtxoStore.deleteTree(stage)) *> fail[Unit](LeiosStoreError.Io(e))
            }
        }
    val prepared: F[Unit] =
      io(Files.exists(nio.resolve("CURRENT"))).flatMap {
        case true => synced *> validated
        case false =>
          io(
            Files.notExists(nio) || Files.isDirectory(nio) && Using.resource(Files.list(nio))(
              _.findAny().isEmpty
            )
          )
            .ifM(made, fail(LeiosStoreError.ForeignDatabase(Vector.empty)))
      }
    // Looking for leftovers and removing them are a best effort: an unreadable staging root keeps
    // no valid store from opening, and what is left goes next time.
    val tidied: F[Unit] =
      F.blocking(stagedLeftovers(nio))
        .attempt
        .map(_.getOrElse(Vector.empty))
        .flatMap(_.traverse_(stage => bestEffort(RocksUtxoStore.deleteTree(stage))))
        *> bestEffort(discardRoot(nio))
    locked.surround(prepared *> tidied *> open)

  // Permits of `gate`: one for each call, all of them for the release.
  private val Permits = 1L << 40

  /** Open the store in `dir`, making it if `dir` is absent or an empty directory ([[firstOpen]]). A
    * database whose column families or format record are not this build's, and a directory that
    * holds anything but a database, are refused.
    */
  def resource[F[_]: Async](dir: Path)(using
      Raise[F, LeiosStoreError]
  ): Resource[F, RocksLeiosStore[F]] =
    val F = Async[F]
    val nio = dir.toNioPath
    def fail[A](e: LeiosStoreError): F[A] = e.raise[F, A]
    def io[A](body: => A): F[A] =
      F.blocking(body).attempt.flatMap(_.fold(e => fail(LeiosStoreError.Io(e)), F.pure))

    for
      natives <- Resource.make(F.delay(new Natives(create = false)))(n => F.blocking(n.close()))
      opened <- Resource.make(
        firstOpen[F, Opened](dir, target => F.blocking(syncParent(target)))(
          io(openAt(nio, natives))
        )
      )(o => F.blocking(o.close()))
      db = opened.db
      families = opened.families
      _ <- Resource.eval(
        io(Option(db.get(families.meta, FormatKey)).map(Bytes.wrap)).flatMap {
          case Some(found) =>
            F.unlessA(found == FormatRecord)(fail[Unit](LeiosStoreError.UnknownFormat(found)))
          case None => fail[Unit](LeiosStoreError.FormatMissing)
        }
      )
      loaded <- Resource.eval(io(Using.resource(db.newIterator(families.points)) { it =>
        @tailrec def go(p: Points): Either[LeiosStoreError, Points] =
          if !it.isValid then Right(p)
          else
            (pointKeyed(it.key), pointRecord(it.key, it.value)).tupled match
              case Right((point, (_, status))) =>
                it.next()
                go(p.added(point.hash, point.slot, status))
              case Left(e) => Left(e)
        it.seekToFirst()
        val result = go(Points.empty)
        it.status()
        result
      }).flatMap(_.fold(fail, F.pure)))
      writes <- Resource.eval(Mutex[F])
      gate <- Resource.eval(Semaphore[F](Permits))
      released <- Resource.eval(Ref.of[F, Boolean](false))
      state <- Resource.eval(Ref.of[F, Points](loaded))
      store <- Resource.make(
        F.pure(new RocksLeiosStore[F](db, families, writes, gate, released, state))
      )(store =>
        gate.acquireN(Permits) *> released.set(true) *> F.blocking(store.closeOptions()) *>
          gate.releaseN(Permits)
      )
    yield store
