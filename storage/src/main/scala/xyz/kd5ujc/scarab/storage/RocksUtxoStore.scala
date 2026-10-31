// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.{Resource, Sync}
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import fs2.io.file.Path
import fs2.Stream
import java.nio.file.{Files, StandardCopyOption}
import java.util.concurrent.atomic.AtomicReference
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{
  CheckpointProblem,
  StorageError,
  StorageStructureError,
  TxIn,
  TxOut,
  UtxoStoreClosure
}
import org.rocksdb.{
  BlockBasedTableConfig,
  BloomFilter,
  Checkpoint,
  CompressionType,
  LRUCache,
  Options,
  ReadOptions,
  RocksDB,
  RocksDBException,
  RocksIterator,
  Status,
  WriteBatch,
  WriteBufferManager,
  WriteOptions
}
import scala.annotation.tailrec
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** The UTxO set on disk, in RocksDB (an embedded LSM tree; JNI from Maven Central, with the native
  * library bundled for linux x86_64, aarch64, macOS and Windows).
  *
  * It is only ever at an immutable point, so it needs no write-ahead log: a batch goes to the
  * memtable and is made durable by a checkpoint, which flushes the memtable and hard-links the
  * immutable SST files into a directory next to the ledger snapshot. A restart always rebuilds the
  * live database from the checkpoint of the snapshot it restored (or empties it), so a crash can
  * never leave the store ahead of the snapshot it is paired with.
  *
  * Keys are `txid[32] | index u16 big-endian` (34 bytes, the Haskell node's UTxO-HD key); values
  * are the output's CBOR (see [[UtxoStore.Coding]]). One reserved single-byte key holds the stamp,
  * written in the same batch as the changes.
  *
  * Builds before K6 (2026-10-14, e7300826) wrote the index as a u32 (36-byte keys), and the
  * snapshots of format 1 go with checkpoints of that layout. [[restore]] reads such a checkpoint
  * and writes the live store in the current layout ([[RocksUtxoStore.rekeyInto]]); the checkpoint
  * itself is not touched.
  *
  * @param db
  *   The open database, swapped when [[restore]] or [[reset]] rebuilds it, or why there is none:
  *   [[UtxoStoreClosure.NoDatabase]] while a rebuild runs and after one that failed once the old
  *   database was closed (a later rebuild opens one), [[UtxoStoreClosure.Released]] once the store
  *   is released (for good: a rebuild is refused before it touches the directory or the native
  *   resources). Every operation without a database fails with [[StorageError.UtxoStoreClosed]]
  *   instead of reaching a closed native handle. The rebuilds and the release run while no other
  *   operation does: [[restore]] when the node opens its storage, [[reset]] at the block that
  *   enters Shelley, where no pinned view is open (those pin Conway states only), and the release
  *   when the enclosing Resource ends. The holder is private to this store. A Ref would only add a
  *   suspension around every JNI call on the hot read path.
  */
final class RocksUtxoStore[F[_]: Sync] private (
    dir: java.nio.file.Path,
    shared: RocksUtxoStore.Shared,
    db: AtomicReference[Either[UtxoStoreClosure, RocksDB]]
) extends UtxoStore[F]:
  import UtxoStore.Coding
  private val F = Sync[F]
  private val StampKey = Array[Byte](0)
  private val writeOptions = new WriteOptions().setDisableWAL(true)

  def readView: Resource[F, Either[StorageError, UtxoPinnedReadView[F]]] =
    Resource.eval(F.delay(db.get)).flatMap {
      case Left(why) => Resource.pure(Left(StorageError.UtxoStoreClosed(dir.toString, why)))
      case Right(d) =>
        Resource
          .make(
            F.blocking(d.getSnapshot).attempt.map(_.leftMap(failure("utxo snapshot", _)))
          )(snapshot => snapshot.traverse_(value => F.blocking(d.releaseSnapshot(value))))
          .flatMap {
            case Left(error) => Resource.pure(Left(error))
            case Right(snapshot) =>
              Resource
                .make(
                  F.blocking(new ReadOptions())
                    .attempt
                    .map(
                      _.leftMap(failure("utxo snapshot options", _))
                    )
                )(options => options.traverse_(value => F.blocking(value.close())))
                .flatMap {
                  case Left(error) => Resource.pure(Left(error))
                  case Right(options) =>
                    Resource.eval(F.blocking(options.setSnapshot(snapshot)).attempt).flatMap {
                      case Left(error) =>
                        Resource.pure(Left(failure("utxo snapshot options", error)))
                      case Right(_) =>
                        Resource.eval(
                          F.blocking(d.get(options, StampKey))
                            .attempt
                            .map {
                              case Left(error) => Left(failure("utxo snapshot stamp", error))
                              case Right(raw) if raw == null => Right(-1L)
                              case Right(raw) if raw.length == 8 =>
                                Right(java.nio.ByteBuffer.wrap(raw).getLong)
                              case Right(raw) =>
                                Left(
                                  StorageError
                                    .Corrupt(
                                      "utxo stamp",
                                      StorageStructureError.StampLength(raw.length)
                                    )
                                )
                            }
                            .map(_.map { at =>
                              new UtxoPinnedReadView[F]:
                                val stamp: Long = at

                                def read(keys: Iterable[TxIn])
                                    : F[Either[StorageError, Map[TxIn, TxOut]]] =
                                  val wanted = keys.iterator
                                    .flatMap(k => Coding.encodeKey(k).map(k -> _))
                                    .toVector
                                  if wanted.isEmpty then F.pure(Right(Map.empty))
                                  else
                                    guardResult("utxo snapshot read") {
                                      wanted.iterator
                                        .zip(
                                          d.multiGetAsList(options, wanted.map(_._2).asJava)
                                            .asScala
                                            .iterator
                                        )
                                        .collect {
                                          case ((in, _), bytes) if bytes != null =>
                                            Coding.decodeValue(bytes).map(in -> _)
                                        }
                                        .toVector
                                        .sequence
                                        .leftMap(reason =>
                                          StorageError.Corrupt("utxo entry", reason)
                                        )
                                        .map(_.toMap)
                                    }

                                def all: F[Either[StorageError, Map[TxIn, TxOut]]] =
                                  select(_ => true)

                                def select(keep: TxOut => Boolean) =
                                  guardResult("utxo snapshot scan") {
                                    Using.resource(d.newIterator(options)) { it =>
                                      fold(it, Map.empty[TxIn, TxOut]) { (found, k, positioned) =>
                                        Coding.decodeValue(positioned.value()) match
                                          case Right(out) =>
                                            Right(
                                              if keep(out) then found + (Coding.decodeKey(k) -> out)
                                              else found
                                            )
                                          case Left(reason) => Left(corruptEntry(k, reason))
                                      }
                                    }
                                  }

                                def selectAddresses(addresses: Set[Bytes]) =
                                  guardResult("utxo snapshot scan") {
                                    val wanted = addresses.iterator.map(_.toArray).toArray
                                    Using.resource(d.newIterator(options)) { it =>
                                      fold(it, Map.empty[TxIn, TxOut]) { (found, k, positioned) =>
                                        val value = positioned.value()
                                        if !Coding.mayPayTo(value, wanted) then Right(found)
                                        else
                                          Coding.decodeValue(value) match
                                            case Right(out) =>
                                              Right(
                                                if addresses(out.address.raw) then
                                                  found + (Coding.decodeKey(k) -> out)
                                                else found
                                              )
                                            case Left(reason) => Left(corruptEntry(k, reason))
                                      }
                                    }
                                  }
                            })
                        )
                    }
                }
          }
    }

  private def guardResult[A](what: String)(
      body: => Either[StorageError, A]
  ): F[Either[StorageError, A]] =
    F.blocking(body).attempt.map {
      case Right(result) => result
      case Left(error)   => Left(failure(what, error))
    }

  /** A failure of the database as a typed error: a checksum or format failure the library reports
    * is damaged data ([[StorageError.Corrupt[?]]]), anything else an I/O error
    * ([[StorageError.Io]]).
    */
  private def failure(what: String, e: Throwable): StorageError = e match
    case r: RocksDBException
        if r.getStatus != null && r.getStatus.getCode == Status.Code.Corruption =>
      StorageError.Corrupt(what, StorageStructureError.RocksFailure(e))
    case _ => StorageError.Io(what, e)

  private def guard[A](what: String)(body: => A)(using Raise[F, StorageError]): F[A] =
    F.blocking(body).attempt.flatMap {
      case Right(a) => F.pure(a)
      case Left(e)  => failure(what, e).raise[F, A]
    }

  /** [[guard]] for a body that can also find damaged data itself. */
  private def guardE[A](
      what: String
  )(body: => Either[StorageError, A])(using Raise[F, StorageError]): F[A] =
    guard(what)(body).flatMap {
      case Right(a)  => F.pure(a)
      case Left(err) => err.raise[F, A]
    }

  /** The error for the entry at `key` whose value does not decode, the same as a direct read's. */
  private def corruptEntry(key: Array[Byte], reason: Coding.ValueError): StorageError =
    StorageError.Corrupt("utxo entry", Coding.EntryError(Coding.decodeKey(key), reason))

  /** `use` with the open database, or the store's [[StorageError.UtxoStoreClosed]] when it has none
    * (see `db`). It reads the holder when it is called, so it belongs inside the suspended body.
    */
  private inline def opened[A](
      inline use: RocksDB => Either[StorageError, A]
  ): Either[StorageError, A] =
    db.get match
      case Right(d)  => use(d)
      case Left(why) => Left(StorageError.UtxoStoreClosed(dir.toString, why))

  /** One pass over every entry of the store, which either visits all of them or fails.
    *
    * `step` sees the result so far and each data key (34 bytes) with the iterator positioned on it,
    * and returns the next result or the error that stops the pass. The reserved stamp key is
    * skipped; any other key length is refused as damage, like the checkpoint guard of
    * [[RocksUtxoStore.rekeyInto]]. A pass that ended because the iterator hit an error, not the end
    * of the data, throws from `status()` (mapped by [[guard]]) instead of returning the result so
    * far. It is `inline` so that each caller's per-entry code is compiled into its own loop (a
    * shared loop calling a function made the 4.5 million entry `select` about 5 percent slower).
    */
  private inline def fold[A](it: RocksIterator, zero: A)(
      inline step: (A, Array[Byte], RocksIterator) => Either[StorageError, A]
  ): Either[StorageError, A] =
    @tailrec def go(result: A): Either[StorageError, A] =
      if !it.isValid then Right(result)
      else
        val k = it.key()
        val next =
          if k.length == RocksUtxoStore.KeyLength then step(result, k, it)
          else if k.length == RocksUtxoStore.StampKeyLength && k(0) == 0 then Right(result)
          else Left(StorageError.Corrupt("utxo key", StorageStructureError.KeyLength(k.length)))
        it.next()
        next match
          case Right(more) => go(more)
          case stopped     => stopped
    it.seekToFirst()
    val result = go(zero)
    it.status()
    result

  def read(keys: Iterable[TxIn])(using cats.mtl.Raise[F, StorageError]): F[Map[TxIn, TxOut]] =
    val wanted = keys.iterator.flatMap(k => Coding.encodeKey(k).map(k -> _)).toVector
    if wanted.isEmpty then F.pure(Map.empty)
    else
      guardE("utxo read") {
        opened { d =>
          val values = d.multiGetAsList(wanted.map(_._2).asJava).iterator().asScala
          wanted.iterator
            .zip(values)
            // The batch read gives `null` for a key it could not read as well as for an absent one
            // (a failed block checksum is not an exception there), so a miss is read again singly,
            // which throws on an error and returns `null` only for an absent key.
            .map { case ((in, key), v) => in -> (if v != null then v else d.get(key)) }
            .collect { case (in, v) if v != null => in -> v }
            .map { case (in, bytes) =>
              Coding.decodeValue(bytes).bimap(e => Coding.EntryError(in, e), out => in -> out)
            }
            .toVector
            .sequence
            .bimap(StorageError.Corrupt("utxo entry", _), _.toMap)
        }
      }

  def write(changes: Iterable[(TxIn, Option[TxOut])], stamp: Long)(using
      Raise[F, StorageError]
  ): F[Unit] =
    guardE("utxo write") {
      opened { d =>
        Using.resource(new WriteBatch()) { batch =>
          changes.foreach { case (in, out) =>
            Coding.encodeKey(in).foreach { key =>
              out match
                case Some(o) => batch.put(key, Coding.encodeValue(o))
                case None    => batch.delete(key)
            }
          }
          batch.put(StampKey, java.nio.ByteBuffer.allocate(8).putLong(stamp).array())
          Right(d.write(writeOptions, batch))
        }
      }
    }

  def flushed(using Raise[F, StorageError]): F[Long] = guardE("utxo stamp") {
    opened { d =>
      val v = d.get(StampKey)
      if v == null then Right(-1L)
      else if v.length == 8 then Right(java.nio.ByteBuffer.wrap(v).getLong)
      else Left(StorageError.Corrupt("utxo stamp", StorageStructureError.StampLength(v.length)))
    }
  }
  def stamp(using Raise[F, StorageError]): F[Long] = flushed

  def all(using cats.mtl.Raise[F, StorageError]): F[Map[TxIn, TxOut]] = select(_ => true)

  def select(keep: TxOut => Boolean)(using cats.mtl.Raise[F, StorageError]): F[Map[TxIn, TxOut]] =
    guardE("utxo scan") {
      opened { d =>
        Using.resource(d.newIterator()) { it =>
          fold(it, Map.empty[TxIn, TxOut]) { (found, k, at) =>
            Coding.decodeValue(at.value()) match
              case Right(out) =>
                Right(if keep(out) then found + (Coding.decodeKey(k) -> out) else found)
              case Left(reason) => Left(corruptEntry(k, reason))
          }
        }
      }
    }

  /** One streaming pass over the raw values: an output is decoded only when the head of its
    * encoding says it may pay to a wanted address ([[UtxoStore.Coding.mayPayTo]]). The scan does
    * not fill the block cache, so a wallet query does not evict what the block path reads. A value
    * whose head is not of a known shape is always decoded, so a malformed one fails the pass; one
    * with a well-formed head for another address is passed over undecoded, which is the point.
    */
  def selectAddresses(
      addresses: Set[Bytes]
  )(using cats.mtl.Raise[F, StorageError]): F[Map[TxIn, TxOut]] = guardE("utxo scan") {
    val wanted = addresses.iterator.map(_.toArray).toArray
    opened { d =>
      // the iterator is closed before the options it was made with
      Using.resource(new ReadOptions().setFillCache(false).setReadaheadSize(2L * 1024 * 1024)) {
        options =>
          Using.resource(d.newIterator(options)) { it =>
            fold(it, Map.empty[TxIn, TxOut]) { (found, k, at) =>
              val v = at.value()
              if !Coding.mayPayTo(v, wanted) then Right(found)
              else
                Coding.decodeValue(v) match
                  case Right(out) =>
                    Right(
                      if addresses(out.address.raw) then found + (Coding.decodeKey(k) -> out)
                      else found
                    )
                  case Left(reason) => Left(corruptEntry(k, reason))
            }
          }
      }
    }
  }

  def scan[A](zero: A)(step: (A, TxIn, TxOut) => A)(using cats.mtl.Raise[F, StorageError]): F[A] =
    guardE("utxo scan") {
      opened { d =>
        Using.resource(d.newIterator()) { it =>
          fold(it, zero) { (acc, k, at) =>
            Coding.decodeValue(at.value()) match
              case Right(out)   => Right(step(acc, Coding.decodeKey(k), out))
              case Left(reason) => Left(corruptEntry(k, reason))
          }
        }
      }
    }

  /** An owned iterator, closed on completion, failure or cancellation. Iterator faults and
    * malformed keys/values are reported exactly as for the other enumerations (AUD-04).
    */
  def entries(using cats.mtl.Raise[F, StorageError]): Stream[F, (TxIn, TxOut)] =
    Stream
      .resource(
        Resource.make(guardE("utxo iterator")(opened(d => Right(d.newIterator()))))(it =>
          guard("utxo iterator close")(it.close())
        )
      )
      .flatMap { it =>
        Stream.eval(guard("utxo iterator seek")(it.seekToFirst())).drain ++ Stream
          .repeatEval(guardE("utxo iterator") {
            @scala.annotation.tailrec
            def next(): Either[StorageError, Option[(TxIn, TxOut)]] =
              if !it.isValid then
                it.status()
                Right(None)
              else
                val key = it.key()
                if key.length == RocksUtxoStore.StampKeyLength && key(0) == 0 then
                  it.next()
                  next()
                else if key.length != RocksUtxoStore.KeyLength then
                  Left(
                    StorageError.Corrupt("utxo key", StorageStructureError.KeyLength(key.length))
                  )
                else
                  val value = Coding.decodeValue(it.value()).left.map(corruptEntry(key, _))
                  it.next()
                  value.map(out => Some(Coding.decodeKey(key) -> out))
            next()
          })
          .unNoneTerminate
      }

  /** The number of data entries. The values are not decoded (a count must stay cheap), so damage to
    * a value shows only in the enumerations and reads; damage to a key or to the files is reported
    * as for them.
    */
  def size(using cats.mtl.Raise[F, StorageError]): F[Long] = guardE("utxo count") {
    opened(d => Using.resource(d.newIterator())(it => fold(it, 0L)((n, _, _) => Right(n + 1))))
  }

  def memory(using cats.mtl.Raise[F, StorageError]): F[Map[String, Long]] = guardE("utxo memory") {
    opened { d =>
      def prop(name: String): Option[(String, Long)] =
        Option(d.getProperty(s"rocksdb.$name")).flatMap(_.toLongOption).map(name -> _)
      Right(
        List(
          "estimate-table-readers-mem",
          "cur-size-all-mem-tables",
          "size-all-mem-tables",
          "estimate-live-data-size",
          "total-sst-files-size",
          "num-snapshots"
        ).flatMap(prop).toMap ++ Map(
          "block-cache-usage" -> shared.cache.getUsage,
          "block-cache-pinned-usage" -> shared.cache.getPinnedUsage,
          "block-cache-capacity" -> shared.capacity
        )
      )
    }
  }

  def checkpoint(target: Path)(using Raise[F, StorageError]): F[Unit] = guardE(target.toString) {
    opened(d =>
      Using.resource(Checkpoint.create(d))(c => Right(c.createCheckpoint(target.toString)))
    )
  }

  def restore(source: Path)(using cats.mtl.Raise[F, StorageError]): F[Boolean] =
    val from = source.toNioPath
    guard(source.toString)(Files.isDirectory(from)).flatMap {
      case false => F.pure(false)
      case true =>
        rebuild(source.toString) {
          val rebuilt =
            if RocksUtxoStore.hasCurrentKeys(from) then Right(RocksUtxoStore.cloneInto(from, dir))
            else RocksUtxoStore.rekeyInto(from, dir, shared)
          // A refused checkpoint leaves the store empty, not half written.
          if rebuilt.isLeft then
            RocksUtxoStore.deleteTree(dir)
            Files.createDirectories(dir)
          rebuilt
        }.flatMap {
          case Right(_) => F.pure(true)
          case Left(problem) =>
            StorageError.UtxoCheckpoint(source.toString, problem).raise[F, Boolean]
        }
    }

  def reset(using cats.mtl.Raise[F, StorageError]): F[Unit] = rebuild(dir.toString)(())

  /** The database replaced by one that `fill` writes into the emptied directory: the old one is
    * closed first, and the new one opened on the directory after `fill` returns. A released store
    * is refused before the directory or the native resources are touched.
    */
  private def rebuild[A](what: String)(fill: => A)(using Raise[F, StorageError]): F[A] =
    guardE(what) {
      db.get match
        case Left(UtxoStoreClosure.Released) =>
          Left(StorageError.UtxoStoreClosed(dir.toString, UtxoStoreClosure.Released))
        case current =>
          db.set(Left(UtxoStoreClosure.NoDatabase))
          current.foreach(_.close())
          RocksUtxoStore.deleteTree(dir)
          Files.createDirectories(dir)
          val filled = fill
          db.set(Right(RocksUtxoStore.open(dir, shared)))
          Right(filled)
    }

  private def close(): Unit =
    db.getAndSet(Left(UtxoStoreClosure.Released)).foreach(_.close())
    writeOptions.close()
    shared.close()

object RocksUtxoStore:
  RocksDB.loadLibrary()

  /** What the store's native memory is bounded by: one LRU block cache (data, index and filter
    * blocks) of `capacity` bytes, and a write buffer manager that charges the memtables to the same
    * cache and flushes the largest once they hold half of it, so the store stays near `capacity`
    * whatever the number of memtables (roadmap K6).
    */
  private[storage] final class Shared(val capacity: Long):
    val cache = new LRUCache(capacity)
    val writeBuffers = new WriteBufferManager(capacity / 2, cache)
    def close(): Unit =
      writeBuffers.close()
      cache.close()

  private[storage] def open(dir: java.nio.file.Path, shared: Shared): RocksDB =
    val table = new BlockBasedTableConfig()
      .setBlockCache(shared.cache)
      .setFilterPolicy(new BloomFilter(10, false))
      .setCacheIndexAndFilterBlocks(true)
      .setBlockSize(16 * 1024)
    val options = new Options()
      .setCreateIfMissing(true)
      .setWriteBufferManager(shared.writeBuffers)
      .setTableFormatConfig(table)
      .setCompressionType(CompressionType.LZ4_COMPRESSION)
      .setWriteBufferSize(64L * 1024 * 1024)
      .setMaxWriteBufferNumber(3)
      .setMaxBackgroundJobs(4)
      .setMaxOpenFiles(512)
    RocksDB.open(options, dir.toString)

  private[storage] def deleteTree(path: java.nio.file.Path): Unit =
    if Files.exists(path) then
      Using.resource(Files.walk(path)) { walk =>
        walk
          .sorted(java.util.Comparator.reverseOrder[java.nio.file.Path]())
          .forEach(Files.delete(_))
      }

  /** Rebuild a live database directory from a checkpoint: the immutable SST files are hard-linked
    * (a copy where linking fails), everything else (MANIFEST, OPTIONS, CURRENT) is copied because
    * the live database rewrites those.
    */
  private[storage] def cloneInto(from: java.nio.file.Path, to: java.nio.file.Path): Unit =
    Using.resource(Files.list(from)) { entries =>
      entries.forEach { f =>
        val target = to.resolve(f.getFileName.toString)
        if f.getFileName.toString.endsWith(".sst") then
          Either
            .catchOnly[Exception](Files.createLink(target, f))
            .getOrElse(Files.copy(f, target, StandardCopyOption.REPLACE_EXISTING))
        else Files.copy(f, target, StandardCopyOption.REPLACE_EXISTING)
      }
    }

  /** The length of a key of the layout before K6 (`txid[32] | index u32`), of the current one
    * (`txid[32] | index u16`), and of the reserved stamp key.
    */
  private val LegacyKeyLength = 36
  private[storage] val KeyLength = 34
  private[storage] val StampKeyLength = 1

  /** Whether the data keys of the checkpoint in `dir` are in the current layout, as far as its
    * first and its last data key say (a store with no data key is). A store of the layout before K6
    * has no key of 34 bytes, so either end tells it from the current one; [[rekeyInto]] looks at
    * all of them before it trusts that.
    */
  private[storage] def hasCurrentKeys(dir: java.nio.file.Path): Boolean =
    Using.resource(new Options().setMaxOpenFiles(64)) { options =>
      Using.resource(RocksDB.openReadOnly(options, dir.toString)) { db =>
        Using.resource(db.newIterator()) { it =>
          @tailrec def pastStamps(): Unit =
            if it.isValid && it.key().length == StampKeyLength then
              it.next()
              pastStamps()
          it.seekToFirst()
          pastStamps()
          val first = if it.isValid then Some(it.key().length) else None
          it.seekToLast()
          val last =
            if it.isValid && it.key().length != StampKeyLength then Some(it.key().length) else None
          it.status()
          (first ++ last).forall(_ == KeyLength)
        }
      }
    }

  /** The live database `dir` (empty) filled from the checkpoint `from`, whose keys are of the
    * layout before K6 (`txid[32] | index u32`), in the current layout. The reserved stamp key is
    * copied as it is. The checkpoint is read and not changed.
    *
    * Every key is looked at: one of 34 bytes means two builds have written to the checkpoint's
    * store, and an entry of the old layout may have been spent by the later one (the delete went to
    * the other key), so nothing is restored ([[CheckpointProblem.MixedKeyLayouts]]).
    */
  private[storage] def rekeyInto(
      from: java.nio.file.Path,
      dir: java.nio.file.Path,
      shared: Shared
  ): Either[CheckpointProblem, Unit] =
    val BatchSize = 50000
    // acquired in this order and closed in the reverse one
    Using.resource(new Options().setMaxOpenFiles(64)) { readOptions =>
      Using.resource(RocksDB.openReadOnly(readOptions, from.toString)) { source =>
        Using.resource(open(dir, shared)) { target =>
          Using.resource(new WriteOptions().setDisableWAL(true)) { writeOptions =>
            Using.resource(source.newIterator()) { it =>
              Using.resource(new WriteBatch()) { batch =>
                def flush(): Unit =
                  target.write(writeOptions, batch)
                  batch.clear()
                // every entry into the batch, flushed every `BatchSize`; the first problem stops it
                @tailrec def copy(inBatch: Int): Option[CheckpointProblem] =
                  if !it.isValid then None
                  else
                    val key = it.key()
                    val problem = key.length match
                      case StampKeyLength if key(0) == 0 =>
                        batch.put(key, it.value())
                        None
                      case LegacyKeyLength =>
                        val index = ((key(32) & 0xffL) << 24) | ((key(33) & 0xffL) << 16) |
                          ((key(34) & 0xffL) << 8) | (key(35) & 0xffL)
                        if index > 0xffffL then Some(CheckpointProblem.IndexTooLarge(index))
                        else
                          val current = new Array[Byte](KeyLength)
                          System.arraycopy(key, 0, current, 0, 32)
                          current(32) = key(34)
                          current(33) = key(35)
                          batch.put(current, it.value())
                          None
                      case KeyLength => Some(CheckpointProblem.MixedKeyLayouts)
                      case other     => Some(CheckpointProblem.UnknownKeyLength(other))
                    val counted = inBatch + 1
                    val left =
                      if counted >= BatchSize then
                        flush()
                        0
                      else counted
                    it.next()
                    if problem.isEmpty then copy(left) else problem
                it.seekToFirst()
                val problem = copy(0)
                // an iterator that stopped on an error must not pass for the end of the checkpoint
                it.status()
                if problem.isEmpty then flush()
                problem.toLeft(())
              }
            }
          }
        }
      }
    }

  /** Open (or create) the store in `dir`. The caller decides what it contains by calling `restore`
    * or `reset` straight after; a database found in `dir` is otherwise used as it is.
    */
  def resource[F[_]: Sync](dir: Path, cacheBytes: Long = 256L * 1024 * 1024)(using
      Raise[F, StorageError]
  ): Resource[F, RocksUtxoStore[F]] =
    Resource.make(
      Sync[F]
        .blocking {
          Files.createDirectories(dir.toNioPath)
          val shared = new Shared(cacheBytes)
          shared -> new AtomicReference[Either[UtxoStoreClosure, RocksDB]](
            Right(open(dir.toNioPath, shared))
          )
        }
        .attempt
        .flatMap {
          case Right((shared, ref)) =>
            Sync[F].pure(new RocksUtxoStore[F](dir.toNioPath, shared, ref))
          case Left(e) =>
            StorageError.Io(dir.toString, e).raise[F, RocksUtxoStore[F]]
        }
    )(s => Sync[F].blocking(s.close()))
