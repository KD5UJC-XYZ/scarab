// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.IO
import java.nio.file.StandardOpenOption
import munit.CatsEffectSuite
import org.rocksdb.{FlushOptions, RocksDB, Statistics, TickerType}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{StorageError, TxIn, TxOut}
import xyz.kd5ujc.scarab.ledger.testByteStrings
import scala.jdk.CollectionConverters.*

import StorageTestKit.*
import UtxoStore.Coding

/** The UTxO store's reads of keys it does not hold (#79): with the store's own options, a batch of
  * absent keys is answered by the tables' filters, which their readers hold, so no filter block is
  * read again for a key, even where the filter is larger than the block cache; and an absent key is
  * told from one the database cannot read by the batch's own status, with no read of its own.
  * Counted by the database's statistics, not timed.
  */
class UtxoAbsentReadSuite extends CatsEffectSuite:
  private def be(n: Long, size: Int): Array[Byte] =
    Array.tabulate(size)(i => ((n >>> (8 * (size - 1 - i))) & 0xff).toByte)

  private def txIn(n: Int): TxIn = TxIn(Bytes.wrap(be(n.toLong + 1, 32)), BigInt(0))

  private def key(n: Int): Array[Byte] = Coding.encodeKey(txIn(n)).getOrElse(fail("no key"))

  private def output(seed: Int): TxOut =
    val address = 0x61.toByte +: be(seed.toLong, 28)
    val cbor =
      Array[Byte](0x82.toByte, 0x58.toByte, address.length.toByte) ++ address ++
        Array[Byte](0x1a.toByte) ++ be(1000L + seed, 4)
    TxOut.fromBytes(Bytes.wrap(cbor)).fold(e => fail(e.toString), identity)

  private val stored = 100000

  private def tables(dir: fs2.io.file.Path): List[java.nio.file.Path] =
    val files = java.nio.file.Files.list(dir.toNioPath)
    try files.iterator.asScala.filter(_.getFileName.toString.endsWith(".sst")).toList
    finally files.close()

  /** The even keys below `2 * stored` flushed to one table with the store's options, then `use` on
    * the database reopened with the store's options over a block cache of 64 KiB, smaller than the
    * table's filter (about 120 KB), and with `stats`. The odd keys are absent, inside the table's
    * key range, so only its filter can answer for them.
    */
  private def withTable[A](dir: fs2.io.file.Path)(use: (RocksDB, Statistics) => A): A =
    RocksDB.loadLibrary()
    val writing = new RocksUtxoStore.Shared(64L * 1024 * 1024)
    val written = RocksUtxoStore.options(writing)
    val db0 = RocksDB.open(written, dir.toString)
    try
      (0 until stored).foreach(n => db0.put(key(2 * n), Coding.encodeValue(output(2 * n))))
      val flush = new FlushOptions().setWaitForFlush(true)
      try db0.flush(flush)
      finally flush.close()
    finally
      db0.close()
      written.close()
      writing.close()
    val shared = new RocksUtxoStore.Shared(64L * 1024)
    val stats = new Statistics()
    val options = RocksUtxoStore.options(shared).setStatistics(stats)
    val db = RocksDB.open(options, dir.toString)
    try use(db, stats)
    finally
      db.close()
      options.close()
      stats.close()
      shared.close()

  test("absent keys read no filter block of their own, with a filter larger than the cache") {
    tempDir.use { dir =>
      IO {
        withTable(dir / "db") { (db, stats) =>
          val absent = (0 until 2000).map(n => key(2 * n + 1)).toVector
          val read = RocksUtxoStore.multiRead(db, None, absent)
          assertEquals(read.map(_.flatten), Right(Vector.empty))
          // the filter is the table reader's: never looked up in the cache, so never read again
          assertEquals(stats.getTickerCount(TickerType.BLOCK_CACHE_FILTER_MISS), 0L)
          assertEquals(stats.getTickerCount(TickerType.BLOCK_CACHE_FILTER_HIT), 0L)
          // the filter answers all but its false positives (10 bits a key: about 1 %)
          val useful = stats.getTickerCount(TickerType.BLOOM_FILTER_USEFUL)
          assert(useful >= absent.size * 95 / 100, clue(useful))
          val dataReads = stats.getTickerCount(TickerType.BLOCK_CACHE_DATA_MISS) +
            stats.getTickerCount(TickerType.BLOCK_CACHE_DATA_HIT)
          assert(dataReads <= absent.size * 5 / 100, clue(dataReads))
        }
      }
    }
  }

  test("a batch gives each present value in order, none for an absent key, a large one whole") {
    tempDir.use { dir =>
      IO {
        withTable(dir / "db") { (db, _) =>
          val large = Array.tabulate[Byte](3000)(i => (i % 251).toByte)
          db.put(key(1), large)
          val asked = Vector(6, 3, 1, 2 * (stored - 1), 5)
          val read = RocksUtxoStore.multiRead(db, None, asked.map(key))
          def bytes(n: Int) = Some(Coding.encodeValue(output(n)).toVector)
          assertEquals(
            read.map(_.map(_.map(_.toVector))),
            Right(Vector(bytes(6), None, Some(large.toVector), bytes(2 * (stored - 1)), None))
          )
        }
      }
    }
  }

  test("a key the database cannot read is its failure, not an absent key") {
    tempDir.use { dir =>
      val db = dir / "db"
      IO(withTable(db)((_, _) => ())) *> IO {
        // flip bytes inside the table's first data block: its checksum no longer matches
        val sst = tables(db) match
          case one :: Nil => one
          case other      => fail(s"expected one SST file, found ${other.size}")
        val file =
          java.nio.channels.FileChannel.open(sst, StandardOpenOption.READ, StandardOpenOption.WRITE)
        try file.write(java.nio.ByteBuffer.wrap(Array[Byte](0x5a, 0x5a, 0x5a, 0x5a)), 8L)
        finally file.close()
      } *> attempt(
        RocksUtxoStore.resource[IO](db, 8L * 1024 * 1024).use(_.read(List(txIn(0))))
      ).map(result =>
        assert(
          result.left.exists {
            case StorageError.Corrupt(_, _) => true
            case _                          => false
          },
          clue(result)
        )
      )
    }
  }
