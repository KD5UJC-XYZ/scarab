// SPDX-License-Identifier: Apache-2.0
package lab.node.storage

import cats.effect.{Resource, Sync}
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import fs2.io.file.Path
import java.nio.file.{Files, StandardCopyOption}
import java.util.concurrent.atomic.AtomicReference
import lab.node.StorageError
import lab.node.ledger.{Table, TxIn, TxOut}
import org.rocksdb.{
  BlockBasedTableConfig,
  BloomFilter,
  Checkpoint,
  ColumnFamilyDescriptor,
  ColumnFamilyHandle,
  ColumnFamilyOptions,
  CompressionType,
  DBOptions,
  LRUCache,
  ReadOptions,
  RocksDB,
  WriteBatch,
  WriteBufferManager,
  WriteOptions
}
import scala.jdk.CollectionConverters.*

/** The ledger store on disk, in RocksDB (an embedded LSM tree; JNI from Maven Central, with the
  * native library bundled for linux x86_64, aarch64, macOS and Windows): the UTxO set in the
  * default column family and one column family per ledger table (see [[LedgerStore]]).
  *
  * It is only ever at an immutable point, so it needs no write-ahead log: a batch goes to the
  * memtables and is made durable by a checkpoint, which flushes them and hard-links the immutable
  * SST files into a directory next to the ledger snapshot. A restart always rebuilds the live
  * database from the checkpoint of the snapshot it restored (or empties it), so a crash can never
  * leave the store ahead of the snapshot it is paired with. One database, so one batch is atomic
  * over every table and one checkpoint covers them all.
  *
  * UTxO keys are `txid[32] | index u32 big-endian` with the output's CBOR as value (see
  * [[UtxoStore.Coding]]); table keys are `generation u64 | key encoding` (see [[LedgerStore]]). One
  * reserved single-byte key of the default family holds the stamp, written in the same batch as the
  * changes.
  *
  * Memory is bounded by one number, `cacheBytes`: all families share one LRU block cache (data,
  * index and filter blocks live in it, with the index and filter blocks of the open files), and a
  * write buffer manager charges the memtables of all families to the same cache, so the native
  * memory of the store stays near that figure however many families there are (roadmap K6).
  */
final class RocksLedgerStore[F[_]: Sync] private (
    dir: java.nio.file.Path,
    shared: RocksLedgerStore.Shared,
    // The handles are swapped when the database is reopened (see `open`); the holder is private to
    // this store and its lifetime is the enclosing Resource. A Ref would only add a suspension
    // around every JNI call on the hot read path.
    db: AtomicReference[RocksLedgerStore.Handles]
)(using Raise[F, StorageError])
    extends LedgerStore[F]:
  import UtxoStore.Coding
  import RocksLedgerStore.*
  private val F = Sync[F]
  private val StampKey = Array[Byte](0)
  private val writeOptions = new WriteOptions().setDisableWAL(true)

  private def guard[A](what: String)(body: => A): F[A] =
    F.blocking(body).attempt.flatMap {
      case Right(a) => F.pure(a)
      case Left(e)  => StorageError.Io(what, String.valueOf(e)).raise[F, A]
    }

  private def family(h: Handles, name: String): ColumnFamilyHandle =
    h.families.getOrElse(name, throw new IllegalArgumentException(s"no table $name"))

  private def rowKey(gen: Long, key: Array[Byte]): Array[Byte] =
    LedgerStore.genPrefix(gen) ++ key

  def read(keys: Iterable[TxIn]): F[Map[TxIn, TxOut]] =
    val wanted = keys.iterator.flatMap(k => Coding.encodeKey(k).map(k -> _)).toVector
    if wanted.isEmpty then F.pure(Map.empty)
    else
      guard("utxo read") {
        val values = db.get.db.multiGetAsList(wanted.map(_._2).asJava).asScala
        wanted.iterator
          .zip(values.iterator)
          .collect { case ((in, _), v) if v != null => in -> v }
          .map { case (in, bytes) =>
            Coding.decodeValue(bytes).bimap(e => s"${in.render}: $e", out => in -> out)
          }
          .toVector
          .sequence
          .map(_.toMap)
      }.flatMap {
        case Right(found) => F.pure(found)
        case Left(reason) => StorageError.Corrupt("utxo entry", reason).raise[F, Map[TxIn, TxOut]]
      }

  def write(changes: Iterable[(TxIn, Option[TxOut])], stamp: Long): F[Unit] =
    writeAll(changes, Vector.empty, Vector.empty, stamp)

  def writeAll(
      utxo: Iterable[(TxIn, Option[TxOut])],
      rows: Vector[LedgerStore.TableWrite],
      retain: Vector[(String, Long)],
      stamp: Long
  ): F[Unit] =
    guard("ledger write") {
      val h = db.get
      val batch = new WriteBatch()
      try
        utxo.foreach { case (in, out) =>
          Coding.encodeKey(in).foreach { key =>
            out match
              case Some(o) => batch.put(key, Coding.encodeValue(o))
              case None    => batch.delete(key)
          }
        }
        rows.foreach { w =>
          val cf = family(h, w.space.name)
          w.changes.foreach { case (k, v) =>
            val key = rowKey(w.space.gen, k)
            v match
              case Some(bytes) => batch.put(cf, key, bytes)
              case None        => batch.delete(cf, key)
          }
        }
        retain.foreach { case (name, from) =>
          if from > 0 then
            batch.deleteRange(
              family(h, name),
              LedgerStore.genPrefix(0),
              LedgerStore.genPrefix(from)
            )
        }
        batch.put(StampKey, java.nio.ByteBuffer.allocate(8).putLong(stamp).array())
        h.db.write(writeOptions, batch)
      finally batch.close()
    }

  def readRows(space: Table.Space, keys: Vector[Array[Byte]]): F[Vector[Option[Array[Byte]]]] =
    if keys.isEmpty then F.pure(Vector.empty)
    else
      guard(s"${space.name} read") {
        val h = db.get
        val cf = family(h, space.name)
        val handles = java.util.Collections.nCopies(keys.size, cf)
        h.db
          .multiGetAsList(handles, keys.map(rowKey(space.gen, _)).asJava)
          .asScala
          .map(Option(_))
          .toVector
      }

  def scanRows(space: Table.Space): F[Vector[(Array[Byte], Array[Byte])]] =
    guard(s"${space.name} scan") {
      val h = db.get
      val prefix = LedgerStore.genPrefix(space.gen)
      val it = h.db.newIterator(family(h, space.name))
      try
        it.seek(prefix)
        val b = Vector.newBuilder[(Array[Byte], Array[Byte])]
        var inRange = true
        while inRange && it.isValid do
          val k = it.key()
          if k.length >= 8 && java.util.Arrays.equals(k, 0, 8, prefix, 0, 8) then
            b += java.util.Arrays.copyOfRange(k, 8, k.length) -> it.value()
            it.next()
          else inRange = false
        b.result()
      finally it.close()
    }

  def flushed: F[Long] = guard("utxo stamp") {
    val v = db.get.db.get(StampKey)
    if v == null then -1L else java.nio.ByteBuffer.wrap(v).getLong
  }

  def all: F[Map[TxIn, TxOut]] = guard("utxo scan") {
    val it = db.get.db.newIterator()
    try
      it.seekToFirst()
      val b = Map.newBuilder[TxIn, TxOut]
      while it.isValid do
        val k = it.key()
        if k.length == 36 then
          Coding.decodeValue(it.value()).foreach(out => b += Coding.decodeKey(k) -> out)
        it.next()
      b.result()
    finally it.close()
  }

  def size: F[Long] = guard("utxo count") {
    val it = db.get.db.newIterator()
    try
      it.seekToFirst()
      var n = 0L
      while it.isValid do
        if it.key().length == 36 then n += 1
        it.next()
      n
    finally it.close()
  }

  def memory: F[Map[String, Long]] = guard("ledger memory") {
    val h = db.get
    def sum(name: String): Long =
      h.families.valuesIterator.map(cf => h.db.getLongProperty(cf, s"rocksdb.$name")).sum
    Map(
      "estimate-table-readers-mem" -> sum("estimate-table-readers-mem"),
      "cur-size-all-mem-tables" -> sum("cur-size-all-mem-tables"),
      "size-all-mem-tables" -> sum("size-all-mem-tables"),
      "total-sst-files-size" -> sum("total-sst-files-size"),
      "estimate-live-data-size" -> sum("estimate-live-data-size"),
      "block-cache-usage" -> shared.cache.getUsage,
      "block-cache-pinned-usage" -> shared.cache.getPinnedUsage,
      "block-cache-capacity" -> shared.capacity
    )
  }

  def checkpoint(target: Path): F[Unit] = guard(target.toString) {
    val cp = Checkpoint.create(db.get.db)
    try cp.createCheckpoint(target.toString)
    finally cp.close()
  }

  def restore(source: Path): F[Boolean] = guard(source.toString) {
    val from = source.toNioPath
    if !Files.isDirectory(from) then false
    else
      db.getAndSet(null).close()
      RocksLedgerStore.deleteTree(dir)
      Files.createDirectories(dir)
      RocksLedgerStore.cloneInto(from, dir)
      db.set(RocksLedgerStore.open(dir, shared))
      true
  }

  def reset: F[Unit] = guard(dir.toString) {
    db.getAndSet(null).close()
    RocksLedgerStore.deleteTree(dir)
    Files.createDirectories(dir)
    db.set(RocksLedgerStore.open(dir, shared))
  }

  private def close(): Unit =
    val d = db.getAndSet(null)
    if d != null then d.close()
    writeOptions.close()
    shared.close()

object RocksLedgerStore:
  RocksDB.loadLibrary()

  /** What every column family shares: the block cache and the write buffer manager (which charges
    * the memtables to the cache). `capacity` is the bound the store is configured to.
    */
  private[storage] final class Shared(val capacity: Long):
    val cache = new LRUCache(capacity)
    // Memtables may take up to half the cache before the largest is flushed.
    val writeBuffers = new WriteBufferManager(capacity / 2, cache)
    def close(): Unit =
      writeBuffers.close()
      cache.close()

  /** An open database with its column families. */
  private[storage] final class Handles(
      val db: RocksDB,
      val families: Map[String, ColumnFamilyHandle],
      options: DBOptions,
      tableOptions: Vector[ColumnFamilyOptions]
  ):
    def close(): Unit =
      families.valuesIterator.foreach(_.close())
      db.close()
      tableOptions.foreach(_.close())
      options.close()

  private val Default = RocksDB.DEFAULT_COLUMN_FAMILY

  private[storage] def open(dir: java.nio.file.Path, shared: Shared): Handles =
    val table = new BlockBasedTableConfig()
      .setBlockCache(shared.cache)
      .setFilterPolicy(new BloomFilter(10, false))
      .setCacheIndexAndFilterBlocks(true)
      .setPinL0FilterAndIndexBlocksInCache(true)
      .setBlockSize(16 * 1024)
    val dbOptions = new DBOptions()
      .setCreateIfMissing(true)
      .setCreateMissingColumnFamilies(true)
      .setWriteBufferManager(shared.writeBuffers)
      .setMaxBackgroundJobs(4)
      .setMaxOpenFiles(512)
    def familyOptions(bufferMb: Long) = new ColumnFamilyOptions()
      .setTableFormatConfig(table)
      .setCompressionType(CompressionType.LZ4_COMPRESSION)
      .setWriteBufferSize(bufferMb * 1024 * 1024)
      .setMaxWriteBufferNumber(2)
    // The UTxO set is the big one; the ledger tables are small next to it.
    val utxoOptions = familyOptions(64)
    val tableOptions = familyOptions(16)
    val names = LedgerStore.tableNames
    val descriptors =
      new ColumnFamilyDescriptor(Default, utxoOptions) +:
        names.map(n => new ColumnFamilyDescriptor(n.getBytes("UTF-8"), tableOptions))
    val handles = new java.util.ArrayList[ColumnFamilyHandle]()
    val db = RocksDB.open(dbOptions, dir.toString, descriptors.asJava, handles)
    val families = names.zip(handles.asScala.drop(1)).toMap
    new Handles(db, families + ("" -> handles.get(0)), dbOptions, Vector(utxoOptions, tableOptions))

  private[storage] def deleteTree(path: java.nio.file.Path): Unit =
    if Files.exists(path) then
      val walk = Files.walk(path)
      try
        walk
          .sorted(java.util.Comparator.reverseOrder[java.nio.file.Path]())
          .forEach(Files.delete(_))
      finally walk.close()

  /** Rebuild a live database directory from a checkpoint: the immutable SST files are hard-linked
    * (a copy where linking fails), everything else (MANIFEST, OPTIONS, CURRENT) is copied because
    * the live database rewrites those.
    */
  private[storage] def cloneInto(from: java.nio.file.Path, to: java.nio.file.Path): Unit =
    val entries = Files.list(from)
    try
      entries.forEach { f =>
        val target = to.resolve(f.getFileName.toString)
        if f.getFileName.toString.endsWith(".sst") then
          try Files.createLink(target, f)
          catch case _: Exception => Files.copy(f, target, StandardCopyOption.REPLACE_EXISTING)
        else Files.copy(f, target, StandardCopyOption.REPLACE_EXISTING)
      }
    finally entries.close()

  /** Open (or create) the store in `dir`. The caller decides what it contains by calling `restore`
    * or `reset` straight after; a database found in `dir` is otherwise used as it is. `cacheBytes`
    * is the native memory bound (see the class comment).
    */
  def resource[F[_]: Sync](dir: Path, cacheBytes: Long = 256L * 1024 * 1024)(using
      Raise[F, StorageError]
  ): Resource[F, RocksLedgerStore[F]] =
    Resource.make(
      Sync[F]
        .blocking {
          Files.createDirectories(dir.toNioPath)
          val shared = new Shared(cacheBytes)
          shared -> new AtomicReference(open(dir.toNioPath, shared))
        }
        .attempt
        .flatMap {
          case Right((shared, ref)) =>
            Sync[F].pure(new RocksLedgerStore[F](dir.toNioPath, shared, ref))
          case Left(e) =>
            StorageError.Io(dir.toString, String.valueOf(e)).raise[F, RocksLedgerStore[F]]
        }
    )(s => Sync[F].blocking(s.close()))
