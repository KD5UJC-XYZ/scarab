// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.{Async, Ref, Resource}
import cats.effect.std.{Mutex, Semaphore}
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import fs2.io.file.Path
import java.nio.channels.FileChannel
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
  * never closed under a call.
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
  ): Either[LeiosStoreError, Vector[Op]] =
    pointsOf(p, hash, key).map(_.collect { case (_, k, size, EbStatus.Certified) =>
      Op.Put(Family.Points, k, pointValue(size, EbStatus.Immutable))
    })

  // --- writes ---

  def writeEbPoint(point: EbPoint, announced: Word32)(using
      Raise[F, LeiosStoreError]
  ): F[Vector[EbPoint]] =
    writing { p =>
      hashKey(point.hash).map { key =>
        val k = pointKey(slotBits(point.slot), key)
        if has(families.points, k) then (Vector.empty, p)
        else
          commit(Vector(Op.Put(Family.Points, k, pointValue(announced.toLong, EbStatus.Volatile))))
          val completed = if has(families.complete, key) then Vector(point) else Vector.empty
          (completed, p.added(point.hash, point.slot, EbStatus.Volatile))
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
        result <-
          if has(families.bodies, key) then Right((BodyWritten(Vector.empty, Vector.empty), p))
          else
            val filled = fills.foldLeft(SortedMap.empty[Int, Bytes]) { (acc, fill) =>
              if acc.contains(fill.offset) then acc
              else filledFrom(eb, fill).fold(acc)(acc.updated(fill.offset, _))
            }
            val complete = filled.sizeIs == eb.size
            (if filled.isEmpty then Right(Vector.empty) else copied(p, point.hash, key)).map {
              copy =>
                commit(
                  Vector(Op.Put(Family.Bodies, key, eb.encoded.toArray)) ++
                    filled.toVector.map((o, tx) => Op.Put(Family.Txs, txKey(key, o), tx.toArray)) ++
                    Option.when(complete)(Op.Put(Family.Complete, key, Empty)) ++ copy
                )
                val completed = if complete then p.pointsOf(point.hash) else Vector.empty
                (BodyWritten(completed, filled.keys.toVector), p)
            }
      yield result
    }

  /** The bytes a fill copies, if its source holds a transaction of the declared size whose hash is
    * the one the body lists at the fill's offset (`fillFrom`, `Impl/SQLite/Insert.hs:204-237` at
    * 2416493b).
    */
  private def filledFrom(eb: LeiosEb, fill: LocalFill)(using ReadOptions): Option[Bytes] =
    for
      (listed, declared) <- eb.transactions.get(fill.offset)
      source = fill.source
      if source.offset >= 0 && source.hash.bytes.size == LeiosEb.HashBytes
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
        stored <- body(point.hash, key)
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
            copy <-
              if held.isEmpty && fresh.nonEmpty then copied(p, point.hash, key)
              else Right(Vector.empty)
          yield
            commit(
              fresh.map((o, tx) => Op.Put(Family.Txs, txKey(key, o), tx.toArray)) ++
                Option.when(completes)(Op.Put(Family.Complete, key, Empty)) ++ copy
            )
            (if completes then p.pointsOf(point.hash) else Vector.empty, p)
        }
      yield result
    }

  /** Collection, in writes of at most [[GcBatch]] volatile points each, as the reference's sweeper
    * evicts a batch at a time between the writer's jobs (`sweepEbBatch`,
    * `Impl/SQLite/Maintenance.hs:305-325` at 2416493b). Only the volatile points older than `slot`
    * are visited, from the points held in memory (the reference's `idx_ebs_sweepable`), so a
    * collection that removes nothing costs no more however many immutable points the store holds;
    * whether a hash's points are vetoed is decided again in each write.
    */
  def garbageCollect(slot: Word64)(using Raise[F, LeiosStoreError]): F[Unit] =
    val frontier = slot.toBigInt
    def after(cursor: Option[(Word64, EbHash)]): F[Unit] =
      writing { p =>
        val pending =
          cursor.fold(p.volatile.iterator)(c => p.volatile.iteratorFrom(c).filter(_ != c))
        val batch = pending.takeWhile(_._1.toBigInt < frontier).take(GcBatch).toVector
        batch
          .foldLeftM((Vector.empty[Op], p)) { case ((ops, q), (s, hash)) =>
            val key = hash.bytes.toArray
            pointsOf(q, hash, key).map { rows =>
              // `sql_gc_markable`, `Impl/SQLite/Queries.hs:193-200` at 2416493b
              val vetoed = rows.exists(r => r._4 == EbStatus.Certified || r._1.toBigInt >= frontier)
              val volatile = rows.exists(r => r._1 == s && r._4 == EbStatus.Volatile)
              if vetoed || !volatile then (ops, q)
              else
                val next = q.removed(hash, s)
                val swept =
                  if next.slotsOf(hash).nonEmpty then Vector.empty
                  else
                    Vector(
                      Op.Delete(Family.Bodies, key),
                      Op.Delete(Family.Complete, key),
                      Op.DeleteRange(Family.Txs, txKey(key, 0), txRangeEnd(key))
                    )
                ((ops :+ Op.Delete(Family.Points, pointKey(slotBits(s), key))) ++ swept, next)
            }
          }
          .map { (ops, next) =>
            commit(ops)
            (batch.lastOption, next)
          }
      }.flatMap(_.fold(F.unit)(last => after(Some(last))))
    after(None)

  def promoteToImmutable(points: Vector[EbPoint])(using Raise[F, LeiosStoreError]): F[Unit] =
    writing { p =>
      points
        .map(_.hash)
        .distinct
        .flatTraverse { hash =>
          hashKey(hash).flatMap { key =>
            val promoted =
              if holdsPrefix(families.txs, key) then EbStatus.Immutable else EbStatus.Certified
            pointsOf(p, hash, key).map(_.collect {
              case (slot, k, size, status) if status != EbStatus.Immutable && status != promoted =>
                (hash, slot, promoted, Op.Put(Family.Points, k, pointValue(size, promoted)))
            })
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
      fold(families.points, Array.emptyByteArray, _ => true, Vector.empty[EbPoint]) { (acc, k, _) =>
        pointKeyed(k).map(acc :+ _)
      }
    )

  def scanCompleteEbClosuresNotOlderThanSlot(slot: Word64)(using
      Raise[F, LeiosStoreError]
  ): F[Vector[EbPoint]] =
    viewing(scanCompleteIn(_, slot))

  private[storage] def scanCompleteIn(view: View, slot: Word64)(using
      Raise[F, LeiosStoreError]
  ): F[Vector[EbPoint]] =
    in(view)(
      fold(families.points, be(slotBits(slot), SlotBytes), _ => true, Vector.empty[EbPoint]) {
        (acc, k, _) =>
          pointKeyed(k).map(p =>
            if has(families.complete, p.hash.bytes.toArray) then acc :+ p else acc
          )
      }
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

  /** Every hash the store holds a point or a body of, in byte order. */
  private[storage] def hashes(using Raise[F, LeiosStoreError]): F[Vector[EbHash]] =
    state.get.flatMap { p =>
      reading(fold(families.bodies, Array.emptyByteArray, _ => true, p.slots.keySet.map(_.bytes)) {
        (acc, k, _) => Right(acc + Bytes.wrap(k))
      }).map(
        _.toVector
          .sorted(using Ordering.fromLessThan[Bytes](_.compareTo(_) < 0))
          .map(EbHash.fromBytes)
      )
    }

  /** What the store holds of `hash`. */
  private[storage] def stored(hash: EbHash)(using Raise[F, LeiosStoreError]): F[StoredEb] =
    state.get.flatMap { p =>
      reading(hashKey(hash).flatMap { key =>
        (pointsOf(p, hash, key), transactions(key)).mapN { (points, held) =>
          StoredEb(
            hash,
            points.map((slot, _, size, status) => (slot, size, status)),
            get(families.bodies, key).map(Bytes.wrap),
            held,
            has(families.complete, key)
          )
        }
      })
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

  /** The most volatile points one write of a collection visits. */
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

  /** The points held in memory: the slots of each hash's points, and the volatile points, the ones
    * a collection may remove, by slot.
    */
  private[storage] final case class Points(
      slots: Map[EbHash, SortedSet[Word64]],
      volatile: SortedSet[(Word64, EbHash)]
  )

  private object Points:
    val empty: Points = Points(Map.empty, SortedSet.empty)

  extension (p: Points)
    private def slotsOf(hash: EbHash): SortedSet[Word64] = p.slots.getOrElse(hash, SortedSet.empty)
    private def pointsOf(hash: EbHash): Vector[EbPoint] =
      slotsOf(hash).toVector.map(EbPoint(_, hash))

    /** A point stored with `status`. */
    private def added(hash: EbHash, slot: Word64, status: EbStatus): Points =
      Points(p.slots.updated(hash, slotsOf(hash) + slot), p.volatile).restated(hash, slot, status)

    /** A point's status changed to `status`. */
    private def restated(hash: EbHash, slot: Word64, status: EbStatus): Points = status match
      case EbStatus.Volatile => p.copy(volatile = p.volatile + (slot -> hash))
      case _                 => p.copy(volatile = p.volatile - (slot -> hash))

    private def removed(hash: EbHash, slot: Word64): Points =
      val left = slotsOf(hash) - slot
      Points(
        if left.isEmpty then p.slots - hash else p.slots.updated(hash, left),
        p.volatile - (slot -> hash)
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

  /** Where a new store is made before it is published at `dir`. */
  private[storage] def staging(dir: java.nio.file.Path): java.nio.file.Path =
    dir.resolveSibling(s"${dir.getFileName}.init")

  /** Make a new store at `dir`, which is absent or an empty directory: its families and format
    * record are written and synced in [[staging]], which is then renamed to `dir` atomically, and
    * the rename synced. A first open interrupted before the rename leaves only the staging
    * directory, which the next first open discards; one interrupted after it leaves the store.
    */
  private def initialise(dir: java.nio.file.Path): Unit =
    val stage = staging(dir)
    RocksUtxoStore.deleteTree(stage)
    Files.createDirectories(stage)
    Using.resource(new Natives(create = true)) { natives =>
      Using.resource(openAt(stage, natives)) { opened =>
        Using.resource(new WriteOptions().setSync(true))(sync =>
          opened.db.put(opened.families.meta, sync, FormatKey, FormatRecord.toArray)
        )
      }
    }
    if Files.isDirectory(dir) then Files.delete(dir)
    Files.move(stage, dir, StandardCopyOption.ATOMIC_MOVE)
    Using.resource(FileChannel.open(dir.toAbsolutePath.getParent, StandardOpenOption.READ))(
      _.force(true)
    )

  // Permits of `gate`: one for each call, all of them for the release.
  private val Permits = 1L << 40

  /** Open the store in `dir`, making it if `dir` is absent or an empty directory ([[initialise]]).
    * A database whose column families or format record are not this build's, and a directory that
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

    val prepared: F[Unit] = io(Files.exists(nio.resolve("CURRENT"))).flatMap {
      case true =>
        io(Using.resource(new Options())(o => RocksDB.listColumnFamilies(o, nio.toString)))
          .map(_.asScala.toVector.map(Bytes.wrap))
          .flatMap(found =>
            F.unlessA(found.toSet == FamilyNames.toSet)(
              fail(LeiosStoreError.ForeignDatabase(found))
            )
          )
      case false =>
        io(Files.isDirectory(nio) && Using.resource(Files.list(nio))(_.findAny().isPresent))
          .flatMap {
            case true  => fail(LeiosStoreError.ForeignDatabase(Vector.empty))
            case false => io(initialise(nio))
          }
    }

    for
      _ <- Resource.eval(prepared)
      natives <- Resource.make(F.delay(new Natives(create = false)))(n => F.blocking(n.close()))
      opened <- Resource.make(io(openAt(nio, natives)))(o => F.blocking(o.close()))
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
