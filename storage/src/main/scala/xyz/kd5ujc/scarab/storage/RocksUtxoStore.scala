// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.{Resource, Sync}
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import fs2.io.file.Path
import java.nio.file.{Files, StandardCopyOption}
import java.util.concurrent.atomic.AtomicReference
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{CheckpointProblem, StorageError, TxIn, TxOut}
import org.rocksdb.{
  BlockBasedTableConfig,
  BloomFilter,
  Checkpoint,
  CompressionType,
  LRUCache,
  Options,
  ReadOptions,
  RocksDB,
  WriteBatch,
  WriteBufferManager,
  WriteOptions
}
import scala.jdk.CollectionConverters.*

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
  */
final class RocksUtxoStore[F[_]: Sync] private (
    dir: java.nio.file.Path,
    shared: RocksUtxoStore.Shared,
    // The handle is swapped when the database is reopened (see `open`); the holder is private to
    // this store and its lifetime is the enclosing Resource. A Ref would only add a suspension
    // around every JNI call on the hot read path.
    db: AtomicReference[RocksDB]
)(using Raise[F, StorageError])
    extends UtxoStore[F]:
  import UtxoStore.Coding
  private val F = Sync[F]
  private val StampKey = Array[Byte](0)
  private val writeOptions = new WriteOptions().setDisableWAL(true)

  private def guard[A](what: String)(body: => A): F[A] =
    F.blocking(body).attempt.flatMap {
      case Right(a) => F.pure(a)
      case Left(e)  => StorageError.Io(what, String.valueOf(e)).raise[F, A]
    }

  def read(keys: Iterable[TxIn]): F[Map[TxIn, TxOut]] =
    val wanted = keys.iterator.flatMap(k => Coding.encodeKey(k).map(k -> _)).toVector
    if wanted.isEmpty then F.pure(Map.empty)
    else
      guard("utxo read") {
        val values = db.get.multiGetAsList(wanted.map(_._2).asJava).asScala
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
    guard("utxo write") {
      val batch = new WriteBatch()
      try
        changes.foreach { case (in, out) =>
          Coding.encodeKey(in).foreach { key =>
            out match
              case Some(o) => batch.put(key, Coding.encodeValue(o))
              case None    => batch.delete(key)
          }
        }
        batch.put(StampKey, java.nio.ByteBuffer.allocate(8).putLong(stamp).array())
        db.get.write(writeOptions, batch)
      finally batch.close()
    }

  def flushed: F[Long] = guard("utxo stamp") {
    val v = db.get.get(StampKey)
    if v == null then -1L else java.nio.ByteBuffer.wrap(v).getLong
  }

  def all: F[Map[TxIn, TxOut]] = select(_ => true)

  def select(keep: TxOut => Boolean): F[Map[TxIn, TxOut]] = guard("utxo scan") {
    val it = db.get.newIterator()
    try
      it.seekToFirst()
      val b = Map.newBuilder[TxIn, TxOut]
      while it.isValid do
        val k = it.key()
        if k.length == 34 then
          Coding
            .decodeValue(it.value())
            .foreach(out => if keep(out) then b += Coding.decodeKey(k) -> out)
        it.next()
      b.result()
    finally it.close()
  }

  /** One streaming pass over the raw values: an output is decoded only when the head of its
    * encoding says it may pay to a wanted address ([[UtxoStore.Coding.mayPayTo]]). The scan does
    * not fill the block cache, so a wallet query does not evict what the block path reads.
    */
  def selectAddresses(addresses: Set[Bytes]): F[Map[TxIn, TxOut]] = guard("utxo scan") {
    val wanted = addresses.iterator.map(_.toArray).toArray
    val options = new ReadOptions().setFillCache(false).setReadaheadSize(2L * 1024 * 1024)
    val it = db.get.newIterator(options)
    try
      it.seekToFirst()
      val b = Map.newBuilder[TxIn, TxOut]
      while it.isValid do
        val k = it.key()
        if k.length == 34 then
          val v = it.value()
          if Coding.mayPayTo(v, wanted) then
            Coding
              .decodeValue(v)
              .foreach(out => if addresses(out.address.raw) then b += Coding.decodeKey(k) -> out)
        it.next()
      b.result()
    finally
      it.close()
      options.close()
  }

  def scan(visit: (TxIn, TxOut) => Unit): F[Unit] = guard("utxo scan") {
    val it = db.get.newIterator()
    try
      it.seekToFirst()
      while it.isValid do
        val k = it.key()
        if k.length == 34 then
          Coding.decodeValue(it.value()).foreach(out => visit(Coding.decodeKey(k), out))
        it.next()
    finally it.close()
  }

  def size: F[Long] = guard("utxo count") {
    val it = db.get.newIterator()
    try
      it.seekToFirst()
      var n = 0L
      while it.isValid do
        if it.key().length == 34 then n += 1
        it.next()
      n
    finally it.close()
  }

  def memory: F[Map[String, Long]] = guard("utxo memory") {
    val d = db.get
    def prop(name: String): Option[(String, Long)] =
      Option(d.getProperty(s"rocksdb.$name")).flatMap(_.toLongOption).map(name -> _)
    List(
      "estimate-table-readers-mem",
      "cur-size-all-mem-tables",
      "size-all-mem-tables",
      "estimate-live-data-size",
      "total-sst-files-size"
    ).flatMap(prop).toMap ++ Map(
      "block-cache-usage" -> shared.cache.getUsage,
      "block-cache-pinned-usage" -> shared.cache.getPinnedUsage,
      "block-cache-capacity" -> shared.capacity
    )
  }

  def checkpoint(target: Path): F[Unit] = guard(target.toString) {
    val cp = Checkpoint.create(db.get)
    try cp.createCheckpoint(target.toString)
    finally cp.close()
  }

  def restore(source: Path): F[Boolean] =
    guard(source.toString) {
      val from = source.toNioPath
      if !Files.isDirectory(from) then Right(false)
      else
        db.getAndSet(null).close()
        RocksUtxoStore.deleteTree(dir)
        Files.createDirectories(dir)
        val rebuilt: Either[CheckpointProblem, Boolean] =
          if RocksUtxoStore.hasCurrentKeys(from) then
            RocksUtxoStore.cloneInto(from, dir)
            Right(true)
          else RocksUtxoStore.rekeyInto(from, dir, shared).map(_ => true)
        // A refused checkpoint leaves the store empty, not half written.
        if rebuilt.isLeft then
          RocksUtxoStore.deleteTree(dir)
          Files.createDirectories(dir)
        db.set(RocksUtxoStore.open(dir, shared))
        rebuilt
    }.flatMap {
      case Right(found) => F.pure(found)
      case Left(problem) =>
        StorageError.UtxoCheckpoint(source.toString, problem).raise[F, Boolean]
    }

  def reset: F[Unit] = guard(dir.toString) {
    db.getAndSet(null).close()
    RocksUtxoStore.deleteTree(dir)
    Files.createDirectories(dir)
    db.set(RocksUtxoStore.open(dir, shared))
  }

  private def close(): Unit =
    val d = db.getAndSet(null)
    if d != null then d.close()
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

  /** The length of a key of the layout before K6 (`txid[32] | index u32`), of the current one
    * (`txid[32] | index u16`), and of the reserved stamp key.
    */
  private val LegacyKeyLength = 36
  private val KeyLength = 34
  private val StampKeyLength = 1

  /** Whether the data keys of the checkpoint in `dir` are in the current layout, as far as its
    * first and its last data key say (a store with no data key is). A store of the layout before K6
    * has no key of 34 bytes, so either end tells it from the current one; [[rekeyInto]] looks at
    * all of them before it trusts that.
    */
  private[storage] def hasCurrentKeys(dir: java.nio.file.Path): Boolean =
    val options = new Options().setMaxOpenFiles(64)
    val db = RocksDB.openReadOnly(options, dir.toString)
    try
      val it = db.newIterator()
      try
        it.seekToFirst()
        while it.isValid && it.key().length == StampKeyLength do it.next()
        val first = if it.isValid then Some(it.key().length) else None
        it.seekToLast()
        val last =
          if it.isValid && it.key().length != StampKeyLength then Some(it.key().length) else None
        (first ++ last).forall(_ == KeyLength)
      finally it.close()
    finally
      db.close()
      options.close()

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
    val readOptions = new Options().setMaxOpenFiles(64)
    val source = RocksDB.openReadOnly(readOptions, from.toString)
    val target = open(dir, shared)
    val writeOptions = new WriteOptions().setDisableWAL(true)
    try
      val it = source.newIterator()
      val batch = new WriteBatch()
      try
        var inBatch = 0
        var problem: Option[CheckpointProblem] = None
        def flush(): Unit =
          target.write(writeOptions, batch)
          batch.clear()
          inBatch = 0
        it.seekToFirst()
        while problem.isEmpty && it.isValid do
          val key = it.key()
          key.length match
            case StampKeyLength => batch.put(key, it.value())
            case LegacyKeyLength =>
              val index = ((key(32) & 0xffL) << 24) | ((key(33) & 0xffL) << 16) |
                ((key(34) & 0xffL) << 8) | (key(35) & 0xffL)
              if index > 0xffffL then problem = Some(CheckpointProblem.IndexTooLarge(index))
              else
                val current = new Array[Byte](KeyLength)
                System.arraycopy(key, 0, current, 0, 32)
                current(32) = key(34)
                current(33) = key(35)
                batch.put(current, it.value())
            case KeyLength => problem = Some(CheckpointProblem.MixedKeyLayouts)
            case other     => problem = Some(CheckpointProblem.UnknownKeyLength(other))
          inBatch += 1
          if inBatch >= BatchSize then flush()
          it.next()
        if problem.isEmpty then flush()
        problem.toLeft(())
      finally
        batch.close()
        it.close()
    finally
      writeOptions.close()
      target.close()
      source.close()
      readOptions.close()

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
          shared -> new AtomicReference(open(dir.toNioPath, shared))
        }
        .attempt
        .flatMap {
          case Right((shared, ref)) =>
            Sync[F].pure(new RocksUtxoStore[F](dir.toNioPath, shared, ref))
          case Left(e) =>
            StorageError.Io(dir.toString, String.valueOf(e)).raise[F, RocksUtxoStore[F]]
        }
    )(s => Sync[F].blocking(s.close()))
