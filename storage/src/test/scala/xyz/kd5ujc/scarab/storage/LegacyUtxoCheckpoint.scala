// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import java.nio.file.Path
import org.rocksdb.{FlushOptions, Options, RocksDB}
import xyz.kd5ujc.scarab.ledger.{TxIn, TxOut}

/** FROZEN. The UTxO checkpoint of the RocksDB store as the builds before K6 (e7300826, 2026-10-14)
  * wrote it, and the snapshots of format 1 go with: the key is `txid[32] | index u32 big-endian`
  * (36 bytes) where the current one is `txid[32] | index u16` (34 bytes); the value, and the
  * reserved single-byte key that holds the stamp (the number of the block the store is at), are the
  * same. It writes with plain RocksDB calls, so that nothing of the current store can leak into the
  * old layout.
  */
object LegacyUtxoCheckpoint:
  private val StampKey = Array[Byte](0)

  /** The key of the layout before K6. */
  def key(in: TxIn): Array[Byte] =
    val k = new Array[Byte](36)
    in.id.copyTo(k, 0)
    val i = in.index.toLong
    k(32) = (i >>> 24).toByte
    k(33) = (i >>> 16).toByte
    k(34) = (i >>> 8).toByte
    k(35) = i.toByte
    k

  /** A checkpoint directory `dir` (which must not exist) holding `entries` at `stamp`, in the
    * layout before K6. `extra` are raw keys written beside them, for the tests of a store that two
    * builds wrote to.
    */
  def write(
      dir: Path,
      entries: Map[TxIn, TxOut],
      stamp: Long,
      extra: Iterable[Array[Byte]] = Nil
  ): Unit =
    RocksDB.loadLibrary()
    val options = new Options().setCreateIfMissing(true).setErrorIfExists(true)
    val db = RocksDB.open(options, dir.toString)
    try
      entries.foreach((in, out) => db.put(key(in), UtxoStore.Coding.encodeValue(out)))
      extra.foreach(k => db.put(k, Array[Byte](1)))
      db.put(StampKey, java.nio.ByteBuffer.allocate(8).putLong(stamp).array())
      val flush = new FlushOptions().setWaitForFlush(true)
      try db.flush(flush)
      finally flush.close()
    finally
      db.close()
      options.close()

  /** The checkpoint in `dir`, which is in the current layout, written again in the layout before
    * K6: the way to have a database of that time from one this build wrote.
    */
  def rewrite(dir: Path): Unit =
    RocksDB.loadLibrary()
    val options = new Options()
    val db = RocksDB.openReadOnly(options, dir.toString)
    val (entries, stamp) =
      try
        val it = db.newIterator()
        try
          val b = Map.newBuilder[TxIn, TxOut]
          it.seekToFirst()
          while it.isValid do
            if it.key().length == 34 then
              b += UtxoStore.Coding.decodeKey(it.key()) ->
                UtxoStore.Coding.decodeValue(it.value()).fold(e => sys.error(e), identity)
            it.next()
          b.result() -> java.nio.ByteBuffer.wrap(db.get(StampKey)).getLong
        finally it.close()
      finally
        db.close()
        options.close()
    RocksUtxoStore.deleteTree(dir)
    write(dir, entries, stamp)
