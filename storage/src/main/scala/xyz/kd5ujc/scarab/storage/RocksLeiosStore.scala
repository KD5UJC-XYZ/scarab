// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.{Async, Poll, Ref, Resource}
import cats.effect.std.{Mutex, SecureRandom, Semaphore, UUIDGen}
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import fs2.io.file.Path
import java.nio.channels.{FileChannel, FileLock}
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
import scala.concurrent.duration.*
import scala.collection.immutable.{SortedMap, SortedSet}
import scala.jdk.CollectionConverters.*
import scala.util.Using
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.{EbHash, EbPoint, LeiosTxHash}
import xyz.kd5ujc.scarab.protocol.LeiosWord.{Word32, Word64}

/** What one partition of the store holds of an endorser block, as the reference's tables of that
  * partition hold it: its rows of `ebs` (`P`), its row of `ebBodies`, and its row of `ebClosures`
  * if one is allocated, with the transactions written in it by offset.
  */
final case class Held[P](
    points: Vector[P],
    body: Option[Bytes],
    closure: Option[SortedMap[Int, Bytes]]
)

/** What the store holds of one endorser block, in each partition: what the import from and the
  * export to the reference's database exchange. A point of the volatile partition has its announced
  * size and status, one of the immutable partition its announced size.
  */
final case class StoredEb(
    hash: EbHash,
    volatile: Held[(Word64, Long, EbStatus)],
    immutable: Held[(Word64, Long)]
)

/** The Leios store in RocksDB, at `leios/` in the node's database directory, beside the chain and
  * UTxO stores and not part of the ledger snapshot. A prototype format of the Leios testnet build,
  * unstable like the build's other layouts: a store of another format or version is refused, never
  * migrated.
  *
  * It holds the reference's two partitions, `leios.vol.db` and `leios.imm.db`, table for table
  * (`Impl/SQLite/Schema.hs` at 2416493b), each in column families of its own:
  *
  *   - `meta`: `format` to the magic `SCRBLEIO` and the version, a 32-bit big-endian integer (1);
  *   - `vol.ebs` and `imm.ebs`, the rows of `ebs`: the slot (64-bit big-endian) and the
  *     endorser-block hash (32 bytes) to the announced size (64-bit big-endian), and in the
  *     volatile partition the status ([[EbStatus.code]]);
  *   - `vol.ebBodies` and `imm.ebBodies`, the rows of `ebBodies`: the hash to the body's encoding
  *     ([[LeiosEb.encoded]]);
  *   - `vol.ebClosures` and `imm.ebClosures`, the rows of `ebClosures`: the hash to nothing,
  *     present once the closure is allocated;
  *   - `vol.txs` and `imm.txs`, the bytes the closure holds: the hash and the transaction's offset
  *     (32-bit big-endian) to its bytes, for the transactions written, where the reference's
  *     closure blob holds zeroes for the others (`Impl/SQLite/Insert.hs:337-343`);
  *   - `vol.complete`: the hash to nothing, present once every transaction the volatile body lists
  *     is written, which the reference keeps in memory instead (`Impl/SQLite/Completion.hs`).
  *
  * The rows of the volatile partition, by hash, with their sizes and statuses, are held in memory,
  * read once when the store opens, as the reference's `idx_ebs_ebHashBytes`, `idx_ebs_sweepable`
  * and `idx_ebs_markedForGc` indexes find them.
  *
  * Writes are serialised (`writes`), as the reference's one write queue serialises them, and each
  * is one atomic batch with RocksDB's write-ahead log on: the store has no checkpoint to be rebuilt
  * from. The log is not synced at every write, so a crash of the process loses no write, and power
  * loss can lose the latest writes while the store recovers to a consistent state, as the
  * reference's SQLite files run in WAL mode with `synchronous = normal`
  * (`Impl/SQLite/Connection.hs` at 2416493b). The reference's copier and sweeper run in threads of
  * their own; here a write that leaves a pinned endorser block with a volatile closure copies it in
  * the same batch, and a collection marks and sweeps in the same batch. A read sees the store at
  * one moment, through a RocksDB snapshot ([[RocksLeiosStore.View]]), as the reference reads inside
  * one SQLite transaction. Every call holds a permit of `gate` while it runs and the release takes
  * all of them, so the database is never closed under a call. A first open holds an exclusive lock
  * of the operating system on `<dir>.lock`, beside the store, while it makes, syncs, checks and
  * opens the store ([[RocksLeiosStore.firstOpen]]), after the permit of that file among the
  * process's opens ([[LeiosStoreLocks]], which the caller makes once for the process and gives
  * every open); the lock file is not part of the store, as the reference's SQLite files have
  * SQLite's own locking.
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

  /** A write: `body` sees the volatile rows of every hash and gives the new ones with its result,
    * which replace them once its batch is written. The batch and the replacement are not cancelable
    * apart, so the rows held in memory are always those on disk.
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

  private def get(family: Family, key: Array[Byte])(using read: ReadOptions): Option[Array[Byte]] =
    Option(db.get(families(family), read, key))

  private def has(family: Family, key: Array[Byte])(using read: ReadOptions): Boolean =
    db.keyExists(families(family), read, key)

  /** One pass over the records of `family` from `from` while `keep` holds of their keys. */
  private def fold[A](
      family: Family,
      from: Array[Byte],
      keep: Array[Byte] => Boolean,
      zero: A
  )(
      step: (A, Array[Byte], Array[Byte]) => Either[LeiosStoreError, A]
  )(using read: ReadOptions): Either[LeiosStoreError, A] =
    Using.resource(db.newIterator(families(family), read)) { it =>
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

  private def commit(ops: Vector[Op]): Unit =
    Using.resource(new WriteBatch()) { batch =>
      ops.foreach {
        case Op.Put(family, key, value)        => batch.put(families(family), key, value)
        case Op.Delete(family, key)            => batch.delete(families(family), key)
        case Op.DeleteRange(family, from, end) => batch.deleteRange(families(family), from, end)
      }
      db.write(writeOptions, batch)
    }

  private def body(in: Partition, hash: EbHash, key: Array[Byte])(using
      ReadOptions
  ): Either[LeiosStoreError, Option[LeiosEb]] =
    get(in.bodies, key).traverse(raw =>
      LeiosEb.decode(Bytes.wrap(raw)).leftMap(LeiosStoreError.CorruptBody(hash, _))
    )

  /** The transactions written in the partition's closure of the hash, by offset. */
  private def transactions(in: Partition, key: Array[Byte])(using
      ReadOptions
  ): Either[LeiosStoreError, SortedMap[Int, Bytes]] =
    fold(in.txs, key, startsWith(_, key), SortedMap.empty[Int, Bytes]) { (held, k, v) =>
      txOffset(k).map(held.updated(_, Bytes.wrap(v)))
    }

  /** The reference's copier, for a hash whose volatile partition will hold `body` and a closure of
    * `txs` once this write is done: unless no row of the hash is pinned, the newest row, the body
    * and the closure go into the immutable partition, each only if it is not there yet
    * (`copyEbToImmutable`, `Impl/SQLite/Maintenance.hs:112-168`; `sql_copy_insert_eb`,
    * `sql_copy_insert_ebBody` and `sql_copy_insert_ebClosures`, `Impl/SQLite/Queries.hs:142-164` at
    * 2416493b), and the pinned rows become copied (`sql_mark_as_copied`, `Queries.hs:125-127`).
    */
  private def copied(
      p: Points,
      hash: EbHash,
      key: Array[Byte],
      body: Bytes,
      txs: SortedMap[Int, Bytes]
  )(using
      ReadOptions
  ): (Vector[Op], Points) =
    val rows = p.rowsOf(hash)
    val pinned = rows.filter(_._2.status == EbStatus.Certified)
    rows.lastOption.filter(_ => pinned.nonEmpty).fold((Vector.empty[Op], p)) { (newest, row) =>
      val newestKey = pointKey(slotBits(newest), key)
      val point =
        Option.unless(has(Family.ImmEbs, newestKey))(
          Op.Put(Family.ImmEbs, newestKey, be(row.size, SizeBytes))
        )
      val stored =
        Option.unless(has(Family.ImmBodies, key))(Op.Put(Family.ImmBodies, key, body.toArray))
      val closure =
        if has(Family.ImmClosures, key) then Vector.empty
        else
          Op.Put(Family.ImmClosures, key, Empty) +:
            txs.toVector.map((o, tx) => Op.Put(Family.ImmTxs, txKey(key, o), tx.toArray))
      val marks = pinned.toVector.map((slot, r) =>
        Op.Put(Family.VolEbs, pointKey(slotBits(slot), key), volValue(r.size, EbStatus.Copied))
      )
      (
        point.toVector ++ stored.toVector ++ closure ++ marks,
        pinned.keys.foldLeft(p)((q, slot) => q.restated(hash, slot, EbStatus.Copied))
      )
    }

  // --- writes ---

  def writeEbPoint(point: EbPoint, announced: Word32)(using
      Raise[F, LeiosStoreError]
  ): F[Vector[EbPoint]] =
    writing { p =>
      hashKey(point.hash).map { key =>
        val k = pointKey(slotBits(point.slot), key)
        if has(Family.VolEbs, k) then (Vector.empty, p)
        else
          // the reference completes a novel point whose body and closure its volatile partition
          // holds complete; read before the commit, so that nothing after it can fail and leave the
          // rows held in memory behind the store
          val completed = if has(Family.VolComplete, key) then Vector(point) else Vector.empty
          commit(
            Vector(Op.Put(Family.VolEbs, k, volValue(announced.toLong, EbStatus.Volatile)))
          )
          (completed, p.added(point.hash, point.slot, Row(announced.toLong, EbStatus.Volatile)))
      }
    }

  def writeEbBody(point: EbPoint, eb: LeiosEb, fills: Vector[LocalFill])(using
      Raise[F, LeiosStoreError]
  ): F[BodyWritten] =
    writing { p =>
      for
        key <- hashKey(point.hash)
        _ <- Either.cond(
          has(Family.VolEbs, pointKey(slotBits(point.slot), key)),
          (),
          LeiosStoreError.PointNotWritten(point)
        )
      yield
        if has(Family.VolBodies, key) then (BodyWritten(Vector.empty, Vector.empty), p)
        else
          val filled = fills.foldLeft(SortedMap.empty[Int, Bytes]) { (acc, fill) =>
            if acc.contains(fill.offset) then acc
            else filledFrom(eb, fill).fold(acc)(acc.updated(fill.offset, _))
          }
          // the reference allocates the closure whenever there are fills, filled or not
          // (`fillLocally`, `Impl/SQLite/Insert.hs:184-195` at 2416493b)
          val allocated = fills.nonEmpty
          val complete = filled.sizeIs == eb.size
          val (copy, next) =
            if allocated then copied(p, point.hash, key, eb.encoded, filled) else (Vector.empty, p)
          commit(
            Vector(Op.Put(Family.VolBodies, key, eb.encoded.toArray)) ++
              Option.when(allocated)(Op.Put(Family.VolClosures, key, Empty)) ++
              filled.toVector.map((o, tx) => Op.Put(Family.VolTxs, txKey(key, o), tx.toArray)) ++
              Option.when(complete)(Op.Put(Family.VolComplete, key, Empty)) ++ copy
          )
          val completed = if complete then p.pointsOf(point.hash) else Vector.empty
          (BodyWritten(completed, filled.keys.toVector), next)
    }

  /** The bytes a fill copies, if the volatile partition holds a transaction of the declared size at
    * the source whose hash is the one the body lists at the fill's offset (`fillFrom`,
    * `Impl/SQLite/Insert.hs:204-237` at 2416493b).
    */
  private def filledFrom(eb: LeiosEb, fill: LocalFill)(using ReadOptions): Option[Bytes] =
    for
      (listed, declared) <- eb.transactions.get(fill.offset)
      source = fill.source
      if source.offset >= 0 && source.hash.bytes.size == LeiosEb.HashBytes
      raw <- get(Family.VolTxs, txKey(source.hash.bytes.toArray, source.offset))
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
        // the reference drops what its volatile partition holds no body for
        stored <- body(Partition.Volatile, point.hash, key)
        result <- stored.fold(Right((Vector.empty[EbPoint], p))) { eb =>
          // the first of each offset, as the reference keeps it (`Insert.hs:287-297` at 2416493b)
          val accepted = txs.foldLeft(SortedMap.empty[Int, Bytes]) { case (acc, (o, tx)) =>
            if acc.contains(o) || !eb.declared(o).contains(tx.size.toLong) || !LeiosEb.written(tx)
            then acc
            else acc.updated(o, tx)
          }
          if accepted.isEmpty then Right((Vector.empty[EbPoint], p))
          else
            transactions(Partition.Volatile, key).map { held =>
              val fresh = accepted.view.filterKeys(o => !held.contains(o)).toVector
              val completes = !has(Family.VolComplete, key) && held.size + fresh.size == eb.size
              val (copy, next) = copied(p, point.hash, key, eb.encoded, held ++ fresh)
              commit(
                Option
                  .unless(has(Family.VolClosures, key))(Op.Put(Family.VolClosures, key, Empty))
                  .toVector ++
                  fresh.map((o, tx) => Op.Put(Family.VolTxs, txKey(key, o), tx.toArray)) ++
                  Option.when(completes)(Op.Put(Family.VolComplete, key, Empty)) ++ copy
              )
              (if completes then p.pointsOf(point.hash) else Vector.empty, next)
            }
        }
      yield result
    }

  /** Collection, as the reference's mark (`sql_gc_mark`, `Impl/SQLite/Queries.hs:193-206` at
    * 2416493b) and its sweeper (`sweepEbBatch`, `Impl/SQLite/Maintenance.hs:305-325`), each write
    * taking at most [[GcBatch]] hashes. First the hashes marked and not swept yet, those of a
    * database read from the reference: a hash all of whose rows are marked goes from the volatile
    * partition, its rows, body and closure. Then the hashes with a volatile or copied row older
    * than `slot`, visited from the rows held in memory (the reference's `idx_ebs_sweepable`):
    * unless a row of the hash is pinned or at or after `slot`, all its rows are marked, and it
    * goes. The immutable partition keeps what the copier copied. A hash is decided once in the
    * write that takes it, so no hash is ever left half collected.
    */
  def garbageCollect(slot: Word64)(using Raise[F, LeiosStoreError]): F[Unit] =
    def marked(cursor: Option[EbHash]): F[Unit] =
      sweepMarked(cursor).flatMap(_.fold(F.unit)(last => marked(Some(last))))
    def after(cursor: Option[(Word64, EbHash)]): F[Unit] =
      collectBatch(slot, cursor).flatMap(_.fold(F.unit)(last => after(Some(last))))
    marked(None) *> after(None)

  /** What a sweep of `hash` removes from the volatile partition: its rows, body and closure. */
  private def swept(p: Points, hash: EbHash, key: Array[Byte]): (Vector[Op], Points) =
    val rows = p.rowsOf(hash)
    (
      rows.keys.toVector.map(slot => Op.Delete(Family.VolEbs, pointKey(slotBits(slot), key))) ++
        Vector(
          Op.Delete(Family.VolBodies, key),
          Op.Delete(Family.VolClosures, key),
          Op.Delete(Family.VolComplete, key),
          Op.DeleteRange(Family.VolTxs, txKey(key, 0), txRangeEnd(key))
        ),
      rows.keys.foldLeft(p)((q, slot) => q.removed(hash, slot))
    )

  /** One write of the sweep of the marked hashes after `cursor`: of the next [[GcBatch]], those all
    * of whose rows are marked go (`sql_sweep_pick_marked`, `Impl/SQLite/Queries.hs:217-226` at
    * 2416493b). The last hash taken, from which the next write goes on; none when there was none.
    */
  private def sweepMarked(cursor: Option[EbHash])(using
      Raise[F, LeiosStoreError]
  ): F[Option[EbHash]] =
    writing { p =>
      val batch = cursor
        .fold(p.marked.iterator)(c => p.marked.iteratorFrom(c).filter(_ != c))
        .take(GcBatch)
        .toVector
      batch
        .traverse(hash => hashKey(hash).map(hash -> _))
        .map { keyed =>
          val (ops, next) = keyed.foldLeft((Vector.empty[Op], p)) { case ((ops, q), (hash, key)) =>
            if q.rowsOf(hash).forall(_._2.status == EbStatus.Marked) then
              val (gone, after) = swept(q, hash, key)
              (ops ++ gone, after)
            else (ops, q)
          }
          commit(ops)
          (batch.lastOption, next)
        }
    }

  /** One write of a collection: the hashes of the next [[GcBatch]] volatile or copied rows older
    * than `slot` after `cursor`, and the last of those rows, from which the next write goes on;
    * none when there was none.
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
        .traverse(hash => hashKey(hash).map(hash -> _))
        .map { keyed =>
          val (ops, next) = keyed.foldLeft((Vector.empty[Op], p)) { case ((ops, q), (hash, key)) =>
            val rows = q.rowsOf(hash)
            // `sql_gc_markable`: a pinned row or one at or after the frontier, marked or not,
            // vetoes the hash; otherwise every row is older than the frontier and is marked, and
            // the hash is swept
            val vetoed =
              rows.exists((s, r) => r.status == EbStatus.Certified || s.toBigInt >= frontier)
            if vetoed then (ops, q)
            else
              val (gone, after) = swept(q, hash, key)
              (ops ++ gone, after)
          }
          commit(ops)
          (last, next)
        }
    }

  /** Promote the points' endorser blocks (`sqlPromoteToImmutable`,
    * `Impl/SQLite/Maintenance.hs:96-101` at 2416493b): every volatile or marked row of each hash is
    * pinned (`sql_pin_eb`, `Impl/SQLite/Queries.hs:118-120`), and the copier copies a hash whose
    * volatile partition holds its closure.
    */
  def promoteToImmutable(points: Vector[EbPoint])(using Raise[F, LeiosStoreError]): F[Unit] =
    writing { p =>
      points
        .map(_.hash)
        .distinct
        .foldLeftM((Vector.empty[Op], p)) { case ((ops, q), hash) =>
          hashKey(hash).flatMap { key =>
            val pins = q.rowsOf(hash).toVector.collect {
              case (slot, r) if r.status == EbStatus.Volatile || r.status == EbStatus.Marked =>
                (slot, r)
            }
            val pinned = pins.foldLeft(q)((acc, s) => acc.restated(hash, s._1, EbStatus.Certified))
            val pinOps = pins.map((slot, r) =>
              Op.Put(
                Family.VolEbs,
                pointKey(slotBits(slot), key),
                volValue(r.size, EbStatus.Certified)
              )
            )
            val copying =
              if !has(Family.VolClosures, key) then Right((Vector.empty[Op], pinned))
              else
                (body(Partition.Volatile, hash, key), transactions(Partition.Volatile, key)).mapN {
                  (eb, txs) =>
                    eb.fold((Vector.empty[Op], pinned))(b =>
                      copied(pinned, hash, key, b.encoded, txs)
                    )
                }
            // a pin and the copy's mark of the same row: the mark is the later put
            copying.map((copy, next) => (ops ++ pinOps ++ copy, next))
          }
        }
        .map { (ops, next) =>
          commit(ops)
          ((), next)
        }
    }

  /** What the reference resumes when it starts: the copier's pass, its doorbell starting rung
    * (`copierDoorbell`, `Impl/SQLite.hs:157-161` at 2416493b), and the sweep pass, the writer's
    * sweep state starting as a pass in progress (`serve 0 (Just 0)`, `Impl/SQLite.hs:345-361`).
    * Every hash with a pinned row and a volatile closure is copied, and every hash all of whose
    * rows are marked is swept, in writes of at most [[GcBatch]] hashes, as a store read from a
    * reference database stopped between a pin and its copy, or a mark and its sweep, holds them.
    */
  private[storage] def resume(using Raise[F, LeiosStoreError]): F[Unit] =
    def swept(cursor: Option[EbHash]): F[Unit] =
      sweepMarked(cursor).flatMap(_.fold(F.unit)(last => swept(Some(last))))
    def copies(pending: Vector[EbHash]): F[Unit] =
      if pending.isEmpty then F.unit
      else copyPending(pending.take(GcBatch)) *> copies(pending.drop(GcBatch))
    state.get
      .map(
        _.rows
          .collect {
            case (hash, rows) if rows.values.exists(_.status == EbStatus.Certified) => hash
          }
          .toVector
          .sorted
      )
      .flatMap(copies) *> swept(None)

  /** One write of the copier's pass: each of `hashes` with a pinned row and a volatile closure is
    * copied (`copyEbToImmutable`, `Impl/SQLite/Maintenance.hs:112-168` at 2416493b).
    */
  private def copyPending(hashes: Vector[EbHash])(using Raise[F, LeiosStoreError]): F[Unit] =
    writing { p =>
      hashes
        .foldLeftM((Vector.empty[Op], p)) { case ((ops, q), hash) =>
          hashKey(hash).flatMap { key =>
            if !has(Family.VolClosures, key) then Right((ops, q))
            else
              (body(Partition.Volatile, hash, key), transactions(Partition.Volatile, key)).mapN {
                (eb, txs) =>
                  eb.fold((ops, q)) { b =>
                    val (copy, next) = copied(q, hash, key, b.encoded, txs)
                    (ops ++ copy, next)
                  }
              }
          }
        }
        .map { (ops, next) =>
          commit(ops)
          ((), next)
        }
    }

  // --- reads ---

  /** The partition whose body a read takes: the volatile one if it holds the body, the immutable
    * one otherwise, as the reference's reads fall back to the immutable partition for what its
    * volatile one does not hold (`Impl/SQLite/Read.hs` at 2416493b).
    */
  private def holding(key: Array[Byte])(using ReadOptions): Option[Partition] =
    Partition.values.find(in => has(in.bodies, key))

  def lookupEbBody(hash: EbHash)(using Raise[F, LeiosStoreError]): F[Option[Bytes]] =
    reading(
      hashKey(hash).map(key => holding(key).flatMap(in => get(in.bodies, key)).map(Bytes.wrap))
    )

  def lookupEbClosure(hash: EbHash)(using
      Raise[F, LeiosStoreError]
  ): F[Option[Vector[(LeiosTxHash, Bytes)]]] =
    viewing(lookupEbClosureIn(_, hash))

  /** Nothing while the volatile partition holds the body and its closure is incomplete there, even
    * if the immutable partition holds it complete, as the reference reads its `Missing` first; then
    * the closure of the partition that holds the body, if every transaction is written in it.
    */
  private[storage] def lookupEbClosureIn(view: View, hash: EbHash)(using
      Raise[F, LeiosStoreError]
  ): F[Option[Vector[(LeiosTxHash, Bytes)]]] =
    in(view)(hashKey(hash).flatMap { key =>
      holding(key) match
        case None                                                      => Right(None)
        case Some(Partition.Volatile) if !has(Family.VolComplete, key) => Right(None)
        case Some(within) =>
          (body(within, hash, key), transactions(within, key)).mapN { (eb, held) =>
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
      holding(key).fold(Right(Vector.empty)) { within =>
        body(within, hash, key).flatMap(_.fold(Right(Vector.empty)) { eb =>
          offsets.distinct.sorted.flatMap(o => eb.declared(o).map(o -> _)).traverse { (o, size) =>
            get(within.txs, txKey(key, o)) match
              case Some(raw) => Right(o -> Bytes.wrap(raw))
              case None =>
                Either.cond(
                  size <= Int.MaxValue,
                  o -> Bytes.wrap(new Array[Byte](size.toInt)),
                  LeiosStoreError.DeclaredSizeTooLarge(hash, o, size)
                )
          }
        })
      }
    })

  def scanEbPoints(using Raise[F, LeiosStoreError]): F[Vector[EbPoint]] =
    reading(
      fold(Family.VolEbs, Array.emptyByteArray, _ => true, Vector.empty[EbPoint]) { (acc, k, _) =>
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
    val from = slot.toBigInt
    in(view)(
      fold(Family.VolEbs, be(slotBits(slot), SlotBytes), _ => true, Vector.empty[EbPoint]) {
        (listed, k, _) =>
          pointKeyed(k).map { p =>
            if p.slot.toBigInt >= from && has(Family.VolComplete, p.hash.bytes.toArray) then
              listed :+ p
            else listed
          }
      }
    )

  // --- what the import from and the export to the reference's database exchange ---

  /** Store rows read from the volatile partition of the reference's database, as read. */
  private[storage] def importVolatile(rows: Vector[(EbPoint, Long, EbStatus)])(using
      Raise[F, LeiosStoreError]
  ): F[Unit] =
    writing { p =>
      rows
        .traverse((point, size, status) =>
          hashKey(point.hash).map(key =>
            Op.Put(Family.VolEbs, pointKey(slotBits(point.slot), key), volValue(size, status))
          )
        )
        .map { ops =>
          commit(ops)
          ((), rows.foldLeft(p)((q, r) => q.added(r._1.hash, r._1.slot, Row(r._2, r._3))))
        }
    }

  /** Store rows read from the immutable partition of the reference's database, as read. */
  private[storage] def importImmutable(rows: Vector[(EbPoint, Long)])(using
      Raise[F, LeiosStoreError]
  ): F[Unit] =
    writing { p =>
      rows
        .traverse((point, size) =>
          hashKey(point.hash).map(key =>
            Op.Put(Family.ImmEbs, pointKey(slotBits(point.slot), key), be(size, SizeBytes))
          )
        )
        .map { ops =>
          commit(ops)
          ((), p)
        }
    }

  /** Store a body read from a partition of the reference's database, with its closure if one is
    * allocated there and the transactions written in it. The body must be the one the partition
    * stores, if it stores one. A volatile closure every transaction of the body is written in is
    * complete.
    */
  private[storage] def importBody(
      within: Partition,
      hash: EbHash,
      eb: LeiosEb,
      closure: Option[SortedMap[Int, Bytes]]
  )(using
      Raise[F, LeiosStoreError]
  ): F[Unit] =
    writing { p =>
      for
        key <- hashKey(hash)
        _ <- get(within.bodies, key).fold(Right(())) { stored =>
          Either.cond(Bytes.wrap(stored) == eb.encoded, (), LeiosStoreError.BodyConflict(hash))
        }
        held <- transactions(within, key)
      yield
        val txs = closure.getOrElse(SortedMap.empty[Int, Bytes])
        val complete = within == Partition.Volatile && (held ++ txs).sizeIs == eb.size
        commit(
          Vector(Op.Put(within.bodies, key, eb.encoded.toArray)) ++
            closure.map(_ => Op.Put(within.closures, key, Empty)) ++
            txs.toVector.map((o, tx) => Op.Put(within.txs, txKey(key, o), tx.toArray)) ++
            Option.when(complete)(Op.Put(Family.VolComplete, key, Empty))
        )
        ((), p)
    }

  /** `use` with every hash the store holds a row or a body of, in either partition, in byte order,
    * and what it holds of each: all read from one view, so that what is enumerated and what is read
    * are the store at one moment, whatever a collection does meanwhile.
    */
  private[storage] def exporting[A](use: (Vector[EbHash], EbHash => F[StoredEb]) => F[A])(using
      Raise[F, LeiosStoreError]
  ): F[A] =
    viewing { v =>
      in(v)(for
        volRows <- rowsByHash(Family.VolEbs)((k, v) => volRecord(k, v).map(r => (r.size, r.status)))
        immRows <- rowsByHash(Family.ImmEbs)(immRecord)
        bodies <- Partition.values.toVector.foldLeftM(volRows.keySet ++ immRows.keySet) {
          (acc, within) =>
            fold(within.bodies, Array.emptyByteArray, _ => true, acc)((held, k, _) =>
              Right(held + EbHash.fromBytes(Bytes.wrap(k)))
            )
        }
      yield (volRows, immRows, bodies)).flatMap { (volRows, immRows, hashes) =>
        use(
          hashes.toVector.sorted,
          hash =>
            in(v)(
              storedIn(
                hash,
                volRows.getOrElse(hash, Vector.empty).map((slot, r) => (slot, r._1, r._2)),
                immRows.getOrElse(hash, Vector.empty)
              )
            )
        )
      }
    }

  /** What the store holds of `hash`, read from one view. */
  private[storage] def stored(hash: EbHash)(using Raise[F, LeiosStoreError]): F[StoredEb] =
    exporting((_, read) => read(hash))

  /** The rows of a partition's `ebs`, by hash, each with its slot and its record. */
  private def rowsByHash[R](
      family: Family
  )(record: (Array[Byte], Array[Byte]) => Either[LeiosStoreError, R])(using
      ReadOptions
  ): Either[LeiosStoreError, Map[EbHash, Vector[(Word64, R)]]] =
    fold(family, Array.emptyByteArray, _ => true, Map.empty[EbHash, Vector[(Word64, R)]]) {
      (acc, k, v) =>
        (pointKeyed(k), record(k, v)).mapN((point, r) =>
          acc.updated(point.hash, acc.getOrElse(point.hash, Vector.empty) :+ (point.slot -> r))
        )
    }

  private def storedIn(
      hash: EbHash,
      volRows: Vector[(Word64, Long, EbStatus)],
      immRows: Vector[(Word64, Long)]
  )(using ReadOptions): Either[LeiosStoreError, StoredEb] =
    hashKey(hash).flatMap { key =>
      def closure(within: Partition) =
        if has(within.closures, key) then transactions(within, key).map(Some(_)) else Right(None)
      (closure(Partition.Volatile), closure(Partition.Immutable)).mapN { (volClosure, immClosure) =>
        StoredEb(
          hash,
          Held(volRows, get(Family.VolBodies, key).map(Bytes.wrap), volClosure),
          Held(immRows, get(Family.ImmBodies, key).map(Bytes.wrap), immClosure)
        )
      }
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
  private val SizeBytes = 8
  private val PointKeyBytes = SlotBytes + LeiosEb.HashBytes
  private val VolValueBytes = SizeBytes + 1
  private val OffsetBytes = 4

  /** The store at one moment: the reads made through a view see a RocksDB snapshot. */
  private[storage] final class View(private[RocksLeiosStore] val options: ReadOptions)

  /** The column families, in the order they are opened after the default one: the reference's
    * tables, `vol.` those of its volatile partition and `imm.` those of its immutable one.
    */
  private[storage] enum Family(val label: String):
    case Meta extends Family("meta")
    case VolEbs extends Family("vol.ebs")
    case VolBodies extends Family("vol.ebBodies")
    case VolClosures extends Family("vol.ebClosures")
    case VolTxs extends Family("vol.txs")
    case VolComplete extends Family("vol.complete")
    case ImmEbs extends Family("imm.ebs")
    case ImmBodies extends Family("imm.ebBodies")
    case ImmClosures extends Family("imm.ebClosures")
    case ImmTxs extends Family("imm.txs")

  /** The reference's two partitions, and the families of the tables both have. */
  private[storage] enum Partition(
      private[RocksLeiosStore] val bodies: Family,
      private[RocksLeiosStore] val closures: Family,
      private[RocksLeiosStore] val txs: Family
  ) derives CanEqual:
    case Volatile extends Partition(Family.VolBodies, Family.VolClosures, Family.VolTxs)
    case Immutable extends Partition(Family.ImmBodies, Family.ImmClosures, Family.ImmTxs)

  /** The handles, the default family's first, as RocksJava returns them for [[FamilyNames]]. */
  private[storage] final class Families(handles: Vector[ColumnFamilyHandle]):
    def apply(family: Family): ColumnFamilyHandle = handles(family.ordinal + 1)
    val meta: ColumnFamilyHandle = apply(Family.Meta)
    def all: Vector[ColumnFamilyHandle] = handles

  private enum Op:
    case Put(family: Family, key: Array[Byte], value: Array[Byte])
    case Delete(family: Family, key: Array[Byte])
    case DeleteRange(family: Family, from: Array[Byte], end: Array[Byte])

  private given Ordering[Word64] = Ordering.by(_.toBigInt)
  private given Ordering[EbHash] = Ordering.fromLessThan((a, b) => a.bytes.compareTo(b.bytes) < 0)

  /** A row of the volatile partition's `ebs`: its announced size and status. */
  private[storage] final case class Row(size: Long, status: EbStatus)

  /** The rows of the volatile partition held in memory: each hash's, by slot; the volatile and
    * copied ones, those a mark may take (the reference's `idx_ebs_sweepable`), by slot; and the
    * hashes a row of which is marked (its `idx_ebs_markedForGc`).
    */
  private[storage] final case class Points(
      rows: Map[EbHash, SortedMap[Word64, Row]],
      sweepable: SortedSet[(Word64, EbHash)],
      marked: SortedSet[EbHash]
  )

  private object Points:
    val empty: Points = Points(Map.empty, SortedSet.empty, SortedSet.empty)

  extension (p: Points)
    private def rowsOf(hash: EbHash): SortedMap[Word64, Row] =
      p.rows.getOrElse(hash, SortedMap.empty)
    private def pointsOf(hash: EbHash): Vector[EbPoint] =
      rowsOf(hash).keys.toVector.map(EbPoint(_, hash))

    /** The indexes of `hash` once its rows are `rows`. */
    private def indexed(hash: EbHash, rows: SortedMap[Word64, Row]): Points =
      val before = rowsOf(hash).keys.map(_ -> hash)
      val after = rows.collect {
        case (slot, r) if r.status == EbStatus.Volatile || r.status == EbStatus.Copied =>
          slot -> hash
      }
      Points(
        if rows.isEmpty then p.rows - hash else p.rows.updated(hash, rows),
        p.sweepable -- before ++ after,
        if rows.values.exists(_.status == EbStatus.Marked) then p.marked + hash else p.marked - hash
      )

    /** A row stored. */
    private def added(hash: EbHash, slot: Word64, row: Row): Points =
      indexed(hash, rowsOf(hash).updated(slot, row))

    /** A row's status changed to `status`. */
    private def restated(hash: EbHash, slot: Word64, status: EbStatus): Points =
      rowsOf(hash)
        .get(slot)
        .fold(p)(r => indexed(hash, rowsOf(hash).updated(slot, r.copy(status = status))))

    private def removed(hash: EbHash, slot: Word64): Points =
      indexed(hash, rowsOf(hash) - slot)

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

  private def volValue(size: Long, status: EbStatus): Array[Byte] =
    be(size, SizeBytes) :+ status.code

  private def volRecord(key: Array[Byte], value: Array[Byte]): Either[LeiosStoreError, Row] =
    Option
      .when(value.length == VolValueBytes)(EbStatus.fromCode(value(SizeBytes)))
      .flatten
      .map(Row(readBe(value, 0, SizeBytes), _))
      .toRight(LeiosStoreError.Corrupt(LeiosRecord.PointValue, Bytes.wrap(key)))

  private def immRecord(key: Array[Byte], value: Array[Byte]): Either[LeiosStoreError, Long] =
    Option
      .when(value.length == SizeBytes)(readBe(value, 0, SizeBytes))
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
    * opens of one process are serialised first by `locks` ([[LeiosStoreLocks]]), from before the
    * lock file is opened until after it is closed, and the operating-system lock only ever excludes
    * other processes.
    *
    * Ancestors of `dir` that do not exist are made, and each made directory's entry in its parent
    * synced with `sync`, walking up to the first that existed, so that the store's path is durable.
    * The lock is waited for with `attemptLock` (a `tryLock` that finds the lock held gives
    * nothing), pausing between attempts. The open runs where it cannot be cancelled, as a
    * resource's acquisition does, and `poll` is that region's: only the pauses are cancellable
    * through it, so that cancelling an open that waits closes its lock file and gives back its
    * permit, while an open that has made or opened the store always returns it.
    */
  private[storage] def firstOpen[F[_]: Async, A](
      dir: Path,
      locks: LeiosStoreLocks[F],
      sync: java.nio.file.Path => F[Unit],
      attemptLock: FileChannel => F[Option[FileLock]],
      poll: Poll[F]
  )(open: F[A])(using Raise[F, LeiosStoreError]): F[A] =
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
    // The operating-system lock, waited for with `tryLock` so that the wait can be cancelled: a
    // holder in another process may hold it for long.
    def waited(channel: FileChannel, pause: FiniteDuration): F[FileLock] =
      attemptLock(channel).attempt.flatMap {
        case Left(e)           => fail(LeiosStoreError.Io(e))
        case Right(Some(lock)) => F.pure(lock)
        case Right(None) => poll(F.sleep(pause)) *> waited(channel, (pause * 2).min(LockPauseMax))
      }
    // The ancestors are made first, since the lock file lives in the parent. The permit is taken
    // before the lock file is opened and given back after it is closed.
    val locked: Resource[F, FileLock] =
      Resource
        .eval(ancestors *> io(parent.toRealPath().resolve(lockFile(nio).getFileName)))
        .flatTap(locks.held)
        .flatMap(path =>
          Resource.make(
            io(FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE))
          )(channel => F.blocking(channel.close()))
        )
        .flatMap(channel =>
          Resource.makeFull[F, FileLock](poll => poll(waited(channel, LockPauseMin)))(lock =>
            F.blocking(lock.release())
          )
        )
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

  // The first and the longest pause between two attempts of a first open at its lock.
  private val LockPauseMin: FiniteDuration = 10.millis
  private val LockPauseMax: FiniteDuration = 1.second

  // Permits of `gate`: one for each call, all of them for the release.
  private val Permits = 1L << 40

  /** Open the store in `dir`, making it if `dir` is absent or an empty directory ([[firstOpen]]). A
    * database whose column families or format record are not this build's, and a directory that
    * holds anything but a database, are refused. `locks` keeps the process's opens apart: the
    * caller makes one for the process and gives it to every open. Once open, the store resumes what
    * the reference resumes when it starts ([[RocksLeiosStore#resume]]): a pending copy and a
    * pending sweep.
    */
  def resource[F[_]: Async](dir: Path, locks: LeiosStoreLocks[F])(using
      Raise[F, LeiosStoreError]
  ): Resource[F, RocksLeiosStore[F]] =
    opening(dir, locks, channel => Async[F].blocking(Option(channel.tryLock())))(_.resume)

  /** [[resource]] without what the reference resumes when it starts ([[RocksLeiosStore#resume]]):
    * the store as stored, so that an import from the reference's database or an export to it holds
    * the reference's state as it is, a pin awaiting its copy and a mark awaiting its sweep
    * included. The next open by [[resource]] resumes them.
    */
  private[storage] def verbatim[F[_]: Async](dir: Path, locks: LeiosStoreLocks[F])(using
      Raise[F, LeiosStoreError]
  ): Resource[F, RocksLeiosStore[F]] =
    opening(dir, locks, channel => Async[F].blocking(Option(channel.tryLock())))(_ => Async[F].unit)

  /** Where an import of `dir` builds the store that replaces it. */
  private[storage] def importStage(dir: java.nio.file.Path): java.nio.file.Path =
    dir.resolveSibling(s"${dir.getFileName}.import")

  /** A store that replaces the one in `dir` once `fill` succeeds: built from nothing at
    * [[importStage]] ([[verbatim]], so it holds what `fill` writes as it writes it), then, under
    * the lock of a first open of `dir` ([[firstOpen]]), published in place of the store there by a
    * rename, the parent synced. What an interrupted import left at the stage is removed first; if
    * `fill` fails, the store in `dir` stays as it was. An interruption between the removal of the
    * old store and the rename leaves no store in `dir` and the whole new one at the stage, which
    * the next import removes and builds again.
    */
  private[storage] def replacing[F[_]: Async, A](dir: Path, locks: LeiosStoreLocks[F])(
      fill: RocksLeiosStore[F] => F[A]
  )(using Raise[F, LeiosStoreError]): F[A] =
    staging[F, A](dir, locks)(fill).flatMap(staged => staged.publish.as(staged.value))

  /** A store built at [[importStage]] by `fill` and not yet in place of the one in `dir`:
    * [[publish]] puts it there, as [[replacing]] does; [[discard]] removes it, and the store in
    * `dir` stays as it was. Until one of them runs, the store in `dir` is untouched.
    */
  private[storage] final class Staged[F[_], A](
      val value: A,
      val publish: F[Unit],
      val discard: F[Unit]
  )

  /** [[replacing]] up to its publication: the store is built at [[importStage]] by `fill` (what an
    * interrupted import left there removed first), and published or discarded by the caller once it
    * knows whether the import it belongs to succeeds.
    */
  private[storage] def staging[F[_]: Async, A](dir: Path, locks: LeiosStoreLocks[F])(
      fill: RocksLeiosStore[F] => F[A]
  )(using Raise[F, LeiosStoreError]): F[Staged[F, A]] =
    val F = Async[F]
    val nio = dir.toNioPath
    val stage = importStage(nio)
    def io[B](body: => B): F[B] =
      F.blocking(body).attempt.flatMap(_.fold(e => LeiosStoreError.Io(e).raise[F, B], F.pure))
    def cleared(path: java.nio.file.Path): F[Unit] =
      io(if Files.exists(path) then RocksUtxoStore.deleteTree(path))
    // the stage's own lock file, once nothing holds it
    val unlocked = F.blocking(Files.deleteIfExists(lockFile(stage))).attempt.void
    val publish = F.uncancelable(poll =>
      firstOpen[F, Unit](
        dir,
        locks,
        target => F.blocking(syncParent(target)),
        channel => F.blocking(Option(channel.tryLock())),
        poll
      )(
        cleared(nio) *> io(Files.move(stage, nio, StandardCopyOption.ATOMIC_MOVE)) *>
          io(syncParent(nio))
      )
    ) *> unlocked
    for
      _ <- cleared(stage)
      filled <- verbatim[F](Path.fromNioPath(stage), locks).use(fill)
    yield Staged(filled, publish, cleared(stage) *> unlocked)

  /** [[resource]], attempting the first open's lock with `attemptLock`. */
  private[storage] def resourceWith[F[_]: Async](
      dir: Path,
      locks: LeiosStoreLocks[F],
      attemptLock: FileChannel => F[Option[FileLock]]
  )(using Raise[F, LeiosStoreError]): Resource[F, RocksLeiosStore[F]] =
    opening(dir, locks, attemptLock)(_.resume)

  /** An open, `resumed` once the store is open and before it is given out. */
  private def opening[F[_]: Async](
      dir: Path,
      locks: LeiosStoreLocks[F],
      attemptLock: FileChannel => F[Option[FileLock]]
  )(resumed: RocksLeiosStore[F] => F[Unit])(using
      Raise[F, LeiosStoreError]
  ): Resource[F, RocksLeiosStore[F]] =
    val F = Async[F]
    val nio = dir.toNioPath
    def fail[A](e: LeiosStoreError): F[A] = e.raise[F, A]
    def io[A](body: => A): F[A] =
      F.blocking(body).attempt.flatMap(_.fold(e => fail(LeiosStoreError.Io(e)), F.pure))

    for
      natives <- Resource.make(F.delay(new Natives(create = false)))(n => F.blocking(n.close()))
      // cancellable only while it waits for the lock ([[firstOpen]])
      opened <- Resource.makeFull[F, Opened](poll =>
        firstOpen[F, Opened](
          dir,
          locks,
          target => F.blocking(syncParent(target)),
          attemptLock,
          poll
        )(
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
      loaded <- Resource.eval(io(Using.resource(db.newIterator(families(Family.VolEbs))) { it =>
        @tailrec def go(p: Points): Either[LeiosStoreError, Points] =
          if !it.isValid then Right(p)
          else
            (pointKeyed(it.key), volRecord(it.key, it.value)).tupled match
              case Right((point, row)) =>
                it.next()
                go(p.added(point.hash, point.slot, row))
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
      _ <- Resource.eval(resumed(store))
    yield store
