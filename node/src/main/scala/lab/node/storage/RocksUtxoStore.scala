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
import lab.node.ledger.{TxIn, TxOut}
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
  * Keys are `txid[32] | index u32 big-endian`; values are the output's CBOR (see
  * [[UtxoStore.Coding]]). One reserved single-byte key holds the stamp, written in the same batch
  * as the changes.
  */
final class RocksUtxoStore[F[_]: Sync] private (
    dir: java.nio.file.Path,
    cache: LRUCache,
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

  def all: F[Map[TxIn, TxOut]] = guard("utxo scan") {
    val it = db.get.newIterator()
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

  def scan(visit: (TxIn, TxOut) => Unit): F[Unit] = guard("utxo scan") {
    val it = db.get.newIterator()
    try
      it.seekToFirst()
      while it.isValid do
        val k = it.key()
        if k.length == 36 then
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
        if it.key().length == 36 then n += 1
        it.next()
      n
    finally it.close()
  }

  def checkpoint(target: Path): F[Unit] = guard(target.toString) {
    val cp = Checkpoint.create(db.get)
    try cp.createCheckpoint(target.toString)
    finally cp.close()
  }

  def restore(source: Path): F[Boolean] = guard(source.toString) {
    val from = source.toNioPath
    if !Files.isDirectory(from) then false
    else
      db.getAndSet(null).close()
      RocksUtxoStore.deleteTree(dir)
      Files.createDirectories(dir)
      RocksUtxoStore.cloneInto(from, dir)
      db.set(RocksUtxoStore.open(dir, cache))
      true
  }

  def reset: F[Unit] = guard(dir.toString) {
    db.getAndSet(null).close()
    RocksUtxoStore.deleteTree(dir)
    Files.createDirectories(dir)
    db.set(RocksUtxoStore.open(dir, cache))
  }

  private def close(): Unit =
    val d = db.getAndSet(null)
    if d != null then d.close()
    writeOptions.close()
    cache.close()

object RocksUtxoStore:
  RocksDB.loadLibrary()

  private[storage] def open(dir: java.nio.file.Path, cache: LRUCache): RocksDB =
    val table = new BlockBasedTableConfig()
      .setBlockCache(cache)
      .setFilterPolicy(new BloomFilter(10, false))
      .setCacheIndexAndFilterBlocks(true)
      .setBlockSize(16 * 1024)
    val options = new Options()
      .setCreateIfMissing(true)
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
          val cache = new LRUCache(cacheBytes)
          cache -> new AtomicReference(open(dir.toNioPath, cache))
        }
        .attempt
        .flatMap {
          case Right((cache, ref)) =>
            Sync[F].pure(new RocksUtxoStore[F](dir.toNioPath, cache, ref))
          case Left(e) =>
            StorageError.Io(dir.toString, String.valueOf(e)).raise[F, RocksUtxoStore[F]]
        }
    )(s => Sync[F].blocking(s.close()))
