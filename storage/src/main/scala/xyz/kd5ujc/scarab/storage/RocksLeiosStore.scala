// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.{Async, Ref, Resource}
import cats.effect.std.{Mutex, Semaphore}
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import fs2.io.file.Path
import java.nio.charset.StandardCharsets.US_ASCII
import java.nio.file.Files
import java.util.Arrays
import org.rocksdb.{
  ColumnFamilyDescriptor,
  ColumnFamilyHandle,
  ColumnFamilyOptions,
  DBOptions,
  Options,
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
  * points of each hash are held in memory, read once when the store opens, as the reference's
  * `idx_ebs_ebHashBytes` index finds them. Writes are serialised (`writes`), as the reference's one
  * write queue serialises them, and each is one atomic batch with RocksDB's write-ahead log, which
  * makes it durable: the store has no checkpoint to be rebuilt from. Every call holds a permit of
  * `gate` while it runs and the release takes all of them, so the database is never closed under a
  * call.
  */
final class RocksLeiosStore[F[_]] private (
    db: RocksDB,
    families: RocksLeiosStore.Families,
    writes: Mutex[F],
    gate: Semaphore[F],
    released: Ref[F, Boolean],
    index: Ref[F, RocksLeiosStore.Index]
)(using F: Async[F])
    extends LeiosStore[F]:
  import RocksLeiosStore.*

  private val writeOptions = new WriteOptions()

  private def guarded[A](
      body: => Either[LeiosStoreError, A]
  )(using Raise[F, LeiosStoreError]): F[A] =
    gate.permit.surround(released.get.flatMap {
      case true => LeiosStoreError.Closed.raise[F, A]
      case false =>
        F.blocking(body).attempt.flatMap {
          case Right(Right(a)) => F.pure(a)
          case Right(Left(e))  => e.raise[F, A]
          case Left(e)         => LeiosStoreError.Io(e).raise[F, A]
        }
    })

  /** A write: `body` sees the points of every hash and gives the new ones with its result, which
    * replace them once its batch is written. The batch and the replacement are not cancelable
    * apart, so the points held in memory are always those on disk: collection reads them to tell
    * that no point names a hash any more.
    */
  private def writing[A](body: Index => Either[LeiosStoreError, (A, Index)])(using
      Raise[F, LeiosStoreError]
  ): F[A] =
    writes.lock.surround(
      F.uncancelable(_ =>
        index.get
          .flatMap(i => guarded(body(i)))
          .flatMap((result, next) => index.set(next).as(result))
      )
    )

  // --- records ---

  private def get(family: ColumnFamilyHandle, key: Array[Byte]): Option[Array[Byte]] =
    Option(db.get(family, key))

  private def has(family: ColumnFamilyHandle, key: Array[Byte]): Boolean =
    db.keyExists(family, key)

  /** One pass over the records of `family` from `from` while `keep` holds of their keys. */
  private def fold[A](
      family: ColumnFamilyHandle,
      from: Array[Byte],
      keep: Array[Byte] => Boolean,
      zero: A
  )(
      step: (A, Array[Byte], Array[Byte]) => Either[LeiosStoreError, A]
  ): Either[LeiosStoreError, A] =
    Using.resource(db.newIterator(family)) { it =>
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
  private def holdsPrefix(family: ColumnFamilyHandle, prefix: Array[Byte]): Boolean =
    Using.resource(db.newIterator(family)) { it =>
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

  private def body(hash: EbHash, key: Array[Byte]): Either[LeiosStoreError, Option[LeiosEb]] =
    get(families.bodies, key).traverse(raw =>
      LeiosEb.decode(Bytes.wrap(raw)).leftMap(LeiosStoreError.CorruptBody(hash, _))
    )

  /** The transactions stored for the hash, by offset. */
  private def transactions(key: Array[Byte]): Either[LeiosStoreError, SortedMap[Int, Bytes]] =
    fold(families.txs, key, startsWith(_, key), SortedMap.empty[Int, Bytes]) { (held, k, v) =>
      txOffset(k).map(held.updated(_, Bytes.wrap(v)))
    }

  private def heldOffsets(key: Array[Byte]): Either[LeiosStoreError, Set[Int]] =
    fold(families.txs, key, startsWith(_, key), Set.empty[Int])((held, k, _) =>
      txOffset(k).map(held + _)
    )

  private def storedPoint(key: Array[Byte]): Either[LeiosStoreError, (Long, EbStatus)] =
    get(families.points, key)
      .toRight(LeiosStoreError.Corrupt(LeiosRecord.PointKey, Bytes.wrap(key)))
      .flatMap(pointRecord(key, _))

  /** The points of the hash, as stored. */
  private def pointsOf(i: Index, hash: EbHash, key: Array[Byte]) =
    i.slotsOf(hash).toVector.traverse { slot =>
      val k = pointKey(slotBits(slot), key)
      storedPoint(k).map((size, status) => (slot, k, size, status))
    }

  /** The reference's copier for a hash whose first transaction is stored: its pinned points become
    * copied (`sql_mark_as_copied`, `Impl/SQLite/Queries.hs:125-127` at 2416493b).
    */
  private def copied(
      i: Index,
      hash: EbHash,
      key: Array[Byte]
  ): Either[LeiosStoreError, Vector[Op]] =
    pointsOf(i, hash, key).map(_.collect { case (_, k, size, EbStatus.Certified) =>
      Op.Put(Family.Points, k, pointValue(size, EbStatus.Immutable))
    })

  // --- writes ---

  def writeEbPoint(point: EbPoint, announced: Word32)(using
      Raise[F, LeiosStoreError]
  ): F[Vector[EbPoint]] =
    writing { i =>
      hashKey(point.hash).map { key =>
        val k = pointKey(slotBits(point.slot), key)
        if has(families.points, k) then (Vector.empty, i)
        else
          commit(Vector(Op.Put(Family.Points, k, pointValue(announced.toLong, EbStatus.Volatile))))
          val completed = if has(families.complete, key) then Vector(point) else Vector.empty
          (completed, i.add(point.hash, point.slot))
      }
    }

  def writeEbBody(point: EbPoint, eb: LeiosEb, fills: Vector[LocalFill])(using
      Raise[F, LeiosStoreError]
  ): F[BodyWritten] =
    writing { i =>
      for
        key <- hashKey(point.hash)
        _ <- Either.cond(
          has(families.points, pointKey(slotBits(point.slot), key)),
          (),
          LeiosStoreError.PointNotWritten(point)
        )
        result <-
          if has(families.bodies, key) then Right((BodyWritten(Vector.empty, Vector.empty), i))
          else
            val filled = fills.foldLeft(SortedMap.empty[Int, Bytes]) { (acc, fill) =>
              if acc.contains(fill.offset) then acc
              else filledFrom(eb, fill).fold(acc)(acc.updated(fill.offset, _))
            }
            val complete = filled.sizeIs == eb.size
            (if filled.isEmpty then Right(Vector.empty) else copied(i, point.hash, key)).map {
              copy =>
                commit(
                  Vector(Op.Put(Family.Bodies, key, eb.encoded.toArray)) ++
                    filled.toVector.map((o, tx) => Op.Put(Family.Txs, txKey(key, o), tx.toArray)) ++
                    Option.when(complete)(Op.Put(Family.Complete, key, Empty)) ++ copy
                )
                val completed = if complete then i.pointsOf(point.hash) else Vector.empty
                (BodyWritten(completed, filled.keys.toVector), i)
            }
      yield result
    }

  /** The bytes a fill copies, if its source holds a transaction of the declared size whose hash is
    * the one the body lists at the fill's offset (`fillFrom`, `Impl/SQLite/Insert.hs:204-237` at
    * 2416493b).
    */
  private def filledFrom(eb: LeiosEb, fill: LocalFill): Option[Bytes] =
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
    writing { i =>
      for
        key <- hashKey(point.hash)
        stored <- body(point.hash, key)
        result <- stored.fold(Right((Vector.empty[EbPoint], i))) { eb =>
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
              if held.isEmpty && fresh.nonEmpty then copied(i, point.hash, key)
              else Right(Vector.empty)
          yield
            commit(
              fresh.map((o, tx) => Op.Put(Family.Txs, txKey(key, o), tx.toArray)) ++
                Option.when(completes)(Op.Put(Family.Complete, key, Empty)) ++ copy
            )
            (if completes then i.pointsOf(point.hash) else Vector.empty, i)
        }
      yield result
    }

  def garbageCollect(slot: Word64)(using Raise[F, LeiosStoreError]): F[Unit] =
    writing { i =>
      val frontier = slot.toBigInt
      val limit = slotBits(slot)
      val older = (k: Array[Byte]) =>
        k.length < SlotBytes || java.lang.Long.compareUnsigned(slotOfKey(k), limit) < 0
      fold(families.points, Array.emptyByteArray, older, Vector.empty[Row]) { (rows, k, v) =>
        (pointKeyed(k), pointRecord(k, v)).mapN((p, value) => rows :+ Row(p, k, value._2))
      }.map { rows =>
        val (ops, next) =
          rows.groupBy(_.point.hash).toVector.foldLeft((Vector.empty[Op], i)) {
            case ((ops, i0), (hash, old)) =>
              val vetoed = old.exists(_.status == EbStatus.Certified) ||
                i0.slotsOf(hash).exists(_.toBigInt >= frontier)
              val gone = if vetoed then Vector.empty else old.filter(_.status == EbStatus.Volatile)
              val i1 = i0.remove(hash, gone.map(_.point.slot).toSet)
              val key = hash.bytes.toArray
              val swept =
                if gone.isEmpty || i1.slotsOf(hash).nonEmpty then Vector.empty
                else
                  Vector(
                    Op.Delete(Family.Bodies, key),
                    Op.Delete(Family.Complete, key),
                    Op.DeleteRange(Family.Txs, txKey(key, 0), txRangeEnd(key))
                  )
              (ops ++ gone.map(r => Op.Delete(Family.Points, r.key)) ++ swept, i1)
          }
        commit(ops)
        ((), next)
      }
    }

  def promoteToImmutable(points: Vector[EbPoint])(using Raise[F, LeiosStoreError]): F[Unit] =
    writing { i =>
      points
        .map(_.hash)
        .distinct
        .flatTraverse { hash =>
          hashKey(hash).flatMap { key =>
            val promoted =
              if holdsPrefix(families.txs, key) then EbStatus.Immutable else EbStatus.Certified
            pointsOf(i, hash, key).map(_.collect {
              case (_, k, size, status) if status != EbStatus.Immutable && status != promoted =>
                Op.Put(Family.Points, k, pointValue(size, promoted))
            })
          }
        }
        .map { ops =>
          commit(ops)
          ((), i)
        }
    }

  // --- reads ---

  def lookupEbBody(hash: EbHash)(using Raise[F, LeiosStoreError]): F[Option[Bytes]] =
    guarded(hashKey(hash).map(key => get(families.bodies, key).map(Bytes.wrap)))

  def lookupEbClosure(hash: EbHash)(using
      Raise[F, LeiosStoreError]
  ): F[Option[Vector[(LeiosTxHash, Bytes)]]] =
    guarded(hashKey(hash).flatMap { key =>
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
    guarded(hashKey(hash).flatMap { key =>
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
    guarded(
      fold(families.points, Array.emptyByteArray, _ => true, Vector.empty[EbPoint]) { (acc, k, _) =>
        pointKeyed(k).map(acc :+ _)
      }
    )

  def scanCompleteEbClosuresNotOlderThanSlot(slot: Word64)(using
      Raise[F, LeiosStoreError]
  ): F[Vector[EbPoint]] =
    guarded(fold(families.points, be(slotBits(slot), SlotBytes), _ => true, Vector.empty[EbPoint]) {
      (acc, k, v) =>
        (pointKeyed(k), pointRecord(k, v)).mapN { (p, record) =>
          val volatile = record._2 != EbStatus.Immutable
          if volatile && has(families.complete, p.hash.bytes.toArray) then acc :+ p else acc
        }
    })

  // --- what the import from and the export to the reference's database exchange ---

  /** Merge points read from the reference's database: a point already stored keeps the later of the
    * two statuses.
    */
  private[storage] def importPoints(points: Vector[(EbPoint, Word32, EbStatus)])(using
      Raise[F, LeiosStoreError]
  ): F[Unit] =
    writing { i =>
      points
        .traverse { (p, size, status) =>
          hashKey(p.hash).flatMap { key =>
            val k = pointKey(slotBits(p.slot), key)
            get(families.points, k)
              .traverse(pointRecord(k, _))
              .map { stored =>
                val kept = stored.fold(status)((_, s) => if s.code > status.code then s else status)
                (Op.Put(Family.Points, k, pointValue(size.toLong, kept)), p)
              }
          }
        }
        .map { written =>
          commit(written.map(_._1))
          ((), written.foldLeft(i)((acc, w) => acc.add(w._2.hash, w._2.slot)))
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
    writing { i =>
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
        ((), i)
    }

  /** Every hash the store holds a point or a body of, in byte order. */
  private[storage] def hashes(using Raise[F, LeiosStoreError]): F[Vector[EbHash]] =
    index.get.flatMap { i =>
      guarded(fold(families.bodies, Array.emptyByteArray, _ => true, i.keySet.map(_.bytes)) {
        (acc, k, _) => Right(acc + Bytes.wrap(k))
      }).map(
        _.toVector
          .sorted(using Ordering.fromLessThan[Bytes](_.compareTo(_) < 0))
          .map(EbHash.fromBytes)
      )
    }

  /** What the store holds of `hash`. */
  private[storage] def stored(hash: EbHash)(using Raise[F, LeiosStoreError]): F[StoredEb] =
    index.get.flatMap { i =>
      guarded(hashKey(hash).flatMap { key =>
        (pointsOf(i, hash, key), transactions(key)).mapN { (points, held) =>
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

  private def closeOptions(): Unit = writeOptions.close()

object RocksLeiosStore:
  RocksDB.loadLibrary()

  /** The store's magic: the first eight bytes of its format record. */
  val Magic: String = "SCRBLEIO"

  /** The version of the store's layout. */
  val Version: Int = 1

  /** The format record a store of this build holds. */
  val FormatRecord: Bytes = Bytes.wrap(Magic.getBytes(US_ASCII) ++ be(Version.toLong, 4))

  private val FormatKey: Array[Byte] = "format".getBytes(US_ASCII)
  private val Empty: Array[Byte] = Array.emptyByteArray
  private val SlotBytes = 8
  private val PointKeyBytes = SlotBytes + LeiosEb.HashBytes
  private val PointValueBytes = 9
  private val OffsetBytes = 4

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

  private final case class Row(point: EbPoint, key: Array[Byte], status: EbStatus)

  /** The slots of each hash's points. */
  private[storage] type Index = Map[EbHash, SortedSet[Word64]]

  private given Ordering[Word64] = Ordering.by(_.toBigInt)

  extension (i: Index)
    private def slotsOf(hash: EbHash): SortedSet[Word64] = i.getOrElse(hash, SortedSet.empty)
    private def add(hash: EbHash, slot: Word64): Index = i.updated(hash, slotsOf(hash) + slot)
    private def remove(hash: EbHash, slots: Set[Word64]): Index =
      val left = slotsOf(hash) -- slots
      if left.isEmpty then i - hash else i.updated(hash, left)
    private def pointsOf(hash: EbHash): Vector[EbPoint] =
      slotsOf(hash).toVector.map(EbPoint(_, hash))

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

  /** The native objects the open database uses, closed after it. */
  private final class Natives:
    val db = new DBOptions().setCreateIfMissing(true).setCreateMissingColumnFamilies(true)
    val families = new ColumnFamilyOptions()
    def close(): Unit =
      families.close()
      db.close()

  // Permits of `gate`: one for each call, all of them for the release.
  private val Permits = 1L << 40

  /** Open the store in `dir`, creating it if `dir` holds no database. A database whose column
    * families or format record are not this build's is refused.
    */
  def resource[F[_]: Async](dir: Path)(using
      Raise[F, LeiosStoreError]
  ): Resource[F, RocksLeiosStore[F]] =
    val F = Async[F]
    val nio = dir.toNioPath
    def fail[A](e: LeiosStoreError): F[A] = e.raise[F, A]
    def io[A](body: => A): F[A] =
      F.blocking(body).attempt.flatMap(_.fold(e => fail(LeiosStoreError.Io(e)), F.pure))

    val existing: F[Boolean] = io(Files.exists(nio.resolve("CURRENT"))).flatTap { exists =>
      F.whenA(exists)(
        io(Using.resource(new Options())(o => RocksDB.listColumnFamilies(o, nio.toString)))
          .map(_.asScala.toVector.map(Bytes.wrap))
          .flatMap(found =>
            F.unlessA(found.toSet == FamilyNames.toSet)(
              fail(LeiosStoreError.ForeignDatabase(found))
            )
          )
      )
    }

    // RocksJava returns the handles by filling a list it is given (FUN-2 (b)).
    def open(natives: Natives): (RocksDB, Families) =
      val descriptors =
        FamilyNames.map(n => new ColumnFamilyDescriptor(n.toArray, natives.families))
      val handles = new java.util.ArrayList[ColumnFamilyHandle]()
      Files.createDirectories(nio)
      val db = RocksDB.open(natives.db, nio.toString, descriptors.asJava, handles)
      db -> new Families(handles.asScala.toVector)

    for
      fresh <- Resource.eval(existing.map(!_))
      natives <- Resource.make(F.delay(new Natives))(n => F.blocking(n.close()))
      opened <- Resource.make(io(open(natives)))((db, families) =>
        F.blocking {
          families.all.foreach(_.close())
          db.close()
        }
      )
      (db, families) = opened
      _ <- Resource.eval(
        io(Option(db.get(families.meta, FormatKey)).map(Bytes.wrap)).flatMap {
          case Some(found) =>
            F.unlessA(found == FormatRecord)(fail[Unit](LeiosStoreError.UnknownFormat(found)))
          case None if fresh => io(db.put(families.meta, FormatKey, FormatRecord.toArray))
          case None          => fail[Unit](LeiosStoreError.FormatMissing)
        }
      )
      loaded <- Resource.eval(io(Using.resource(db.newIterator(families.points)) { it =>
        @tailrec def go(i: Index): Either[LeiosStoreError, Index] =
          if !it.isValid then Right(i)
          else
            pointKeyed(it.key) match
              case Right(p) =>
                it.next()
                go(i.add(p.hash, p.slot))
              case Left(e) => Left(e)
        it.seekToFirst()
        val result = go(Map.empty)
        it.status()
        result
      }).flatMap(_.fold(fail, F.pure)))
      writes <- Resource.eval(Mutex[F])
      gate <- Resource.eval(Semaphore[F](Permits))
      released <- Resource.eval(Ref.of[F, Boolean](false))
      index <- Resource.eval(Ref.of[F, Index](loaded))
      store <- Resource.make(
        F.pure(new RocksLeiosStore[F](db, families, writes, gate, released, index))
      )(store =>
        gate.acquireN(Permits) *> released.set(true) *> F.blocking(store.closeOptions()) *>
          gate.releaseN(Permits)
      )
    yield store
