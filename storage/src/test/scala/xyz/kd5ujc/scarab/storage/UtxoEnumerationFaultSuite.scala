// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.IO
import cats.syntax.all.*
import fs2.io.file.Path
import java.nio.file.{Files as JFiles, StandardOpenOption}
import munit.CatsEffectSuite
import org.rocksdb.{FlushOptions, Options, RocksDB}
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{CheckpointProblem, StorageError, TxIn, TxOut, Utxo}

import StorageTestKit.*
import UtxoStore.Coding

/** Issue #156 (AUD-04): every enumeration of the RocksDB UTxO store either returns all its entries
  * or fails with a typed [[StorageError]], the same one a direct read gives; a damaged entry is
  * never omitted, and an iterator that stopped on an error never passes for the end of the data.
  *
  * The damage is injected below the store: raw RocksDB puts of a malformed value or of a key of
  * another layout (as [[LegacyUtxoCheckpoint]] writes the old layout), and a flipped byte in the
  * data block of an SST file (the block checksum fails when the iterator reaches it).
  */
class UtxoEnumerationFaultSuite extends CatsEffectSuite:
  private def be(n: Long, size: Int): Array[Byte] =
    Array.tabulate(size)(i => ((n >>> (8 * (size - 1 - i))) & 0xff).toByte)

  private def txIn(n: Int, index: Int = 0): TxIn =
    TxIn(Bytes.wrap(be(n.toLong + 1, 32)), BigInt(index))

  private def output(seed: Int): TxOut =
    val address = 0x61.toByte +: be(seed.toLong, 28)
    val cbor =
      Array[Byte](0x82.toByte, 0x58.toByte, address.length.toByte) ++ address ++
        Array[Byte](0x1a.toByte) ++ be(1000L + seed, 4)
    TxOut.fromBytes(Bytes.wrap(cbor)).fold(e => fail(e), identity)

  private val entries: Map[TxIn, TxOut] = (0 until 50).map(n => txIn(n) -> output(n)).toMap
  private val stamp = 77L
  private val StampKey = Array[Byte](0)

  private def key(in: TxIn): Array[Byte] =
    Coding.encodeKey(in).getOrElse(fail(s"no key for $in"))

  /** The key of the entry that is damaged. */
  private val damaged = txIn(900)

  /** Write `entries`, the stamp and the raw `extra` puts to a RocksDB in `dir` with plain calls
    * (flushed to an SST file), and close it.
    */
  private def writeRaw(dir: Path, extra: List[(Array[Byte], Array[Byte])]): Unit =
    RocksDB.loadLibrary()
    val options = new Options().setCreateIfMissing(true).setErrorIfExists(true)
    val db = RocksDB.open(options, dir.toString)
    try
      entries.foreach((in, out) => db.put(key(in), Coding.encodeValue(out)))
      extra.foreach((k, v) => db.put(k, v))
      if !extra.exists(_._1.sameElements(StampKey)) then
        db.put(StampKey, java.nio.ByteBuffer.allocate(8).putLong(stamp).array())
      val flush = new FlushOptions().setWaitForFlush(true)
      try db.flush(flush)
      finally flush.close()
    finally
      db.close()
      options.close()

  private def open(dir: Path)(using cats.mtl.Raise[IO, StorageError]) =
    RocksUtxoStore.resource[IO](dir, 8L * 1024 * 1024)

  /** The result of every API that reads the set, as the typed error it raised or its success. */
  private def everything(
      dir: Path,
      probe: TxIn
  ): IO[List[(String, Either[StorageError, Any])]] =
    val wanted = entries.values.map(_.address.raw).toSet
    val ledger = Utxo.empty
    def run[A](
        name: String,
        fa: RocksUtxoStore[IO] => IO[A]
    ): IO[(String, Either[StorageError, Any])] =
      attempt(open(dir).use(fa)).map(name -> _)
    List(
      run("read", _.read(List(probe))),
      run("all", _.all),
      run("select", _.select(_ => true)),
      run("selectAddresses", _.selectAddresses(wanted)),
      run("scan", _.scan((_, _) => ())),
      run("size", _.size),
      run("materialize", UtxoStore.materialize(_, ledger)),
      run("materializeWhere", UtxoStore.materializeWhere(_, ledger, _ => true)),
      run("materializeAddresses", UtxoStore.materializeAddresses(_, ledger, wanted))
    ).sequence

  /** The enumerations (everything but the direct read and the count). */
  private val enumerations =
    List(
      "all",
      "select",
      "selectAddresses",
      "scan",
      "materialize",
      "materializeWhere",
      "materializeAddresses"
    )

  private def failureOf(results: List[(String, Either[StorageError, Any])], name: String) =
    results.collectFirst { case (`name`, r) => r }.getOrElse(fail(s"no result for $name"))

  test("a malformed stored value: the direct read and every enumeration give the same Corrupt") {
    tempDir.use { dir =>
      writeRaw(dir / "db", List(key(damaged) -> Array[Byte](1, 0xff.toByte)))
      everything(dir / "db", damaged).map { results =>
        val direct = failureOf(results, "read")
        assert(
          direct.left.exists {
            case StorageError.Corrupt("utxo entry", _) => true; case _ => false
          },
          clue(direct)
        )
        enumerations.foreach(name => assertEquals(failureOf(results, name), direct, name))
        // The audit probe: the same store counted the entry and the scans came back empty or
        // short. The count still counts keys (it decodes nothing); the enumerations now fail.
        assertEquals(failureOf(results, "size"), Right((entries.size + 1).toLong))
      }
    }
  }

  test("a value that is empty fails the enumerations the same way") {
    tempDir.use { dir =>
      writeRaw(dir / "db", List(key(damaged) -> Array.emptyByteArray))
      everything(dir / "db", damaged).map { results =>
        val direct = failureOf(results, "read")
        assert(direct.isLeft, clue(direct))
        enumerations.foreach(name => assertEquals(failureOf(results, name), direct, name))
      }
    }
  }

  test("a key of an unexpected length fails every enumeration and the count, not just skips it") {
    tempDir.use { dir =>
      val results = List(35, 36, 33, 2).traverse { length =>
        val d = dir / s"db$length"
        writeRaw(d, List(Array.fill[Byte](length)(7) -> Array[Byte](1)))
        everything(d, txIn(0)).map(length -> _)
      }
      results.map(_.foreach { case (length, rs) =>
        (enumerations :+ "size").foreach { name =>
          failureOf(rs, name) match
            case Left(StorageError.Corrupt("utxo key", reason)) =>
              assert(reason.contains(s"$length bytes"), clue(reason))
            case other => fail(s"key of $length bytes, $name: $other")
        }
        // a direct read of an existing key is unaffected by another key's layout
        assertEquals(failureOf(rs, "read"), Right(Map(txIn(0) -> entries(txIn(0)))))
      })
    }
  }

  test("a single-byte key other than the stamp key is refused like any unexpected length") {
    tempDir.use { dir =>
      writeRaw(dir / "db", List(Array[Byte](5) -> Array[Byte](1)))
      everything(dir / "db", txIn(0)).map { rs =>
        (enumerations :+ "size").foreach(name =>
          assert(
            failureOf(rs, name).left.exists {
              case StorageError.Corrupt("utxo key", _) => true
              case _                                   => false
            },
            clue(name)
          )
        )
      }
    }
  }

  test("the stamp key is not an entry: an intact store enumerates exactly its entries") {
    tempDir.use { dir =>
      writeRaw(dir / "db", Nil)
      everything(dir / "db", txIn(0)).flatMap { rs =>
        (enumerations ++ List("read")).foreach(name =>
          assert(failureOf(rs, name).isRight, clue(name))
        )
        assertEquals(failureOf(rs, "all"), Right(entries))
        assertEquals(failureOf(rs, "select"), Right(entries))
        assertEquals(failureOf(rs, "selectAddresses"), Right(entries))
        assertEquals(failureOf(rs, "size"), Right(entries.size.toLong))
        attempt(open(dir / "db").use(_.flushed)).map(at => assertEquals(at, Right(stamp)))
      }
    }
  }

  test("a stamp value of the wrong width is Corrupt, not a decoding exception") {
    tempDir.use { dir =>
      writeRaw(dir / "db", List(StampKey -> Array[Byte](1, 2, 3)))
      attempt(open(dir / "db").use(_.flushed)).map(r =>
        assert(
          r.left.exists { case StorageError.Corrupt("utxo stamp", _) => true; case _ => false },
          clue(r)
        )
      )
    }
  }

  /** The SST files of `dir`. */
  private def tables(dir: Path): List[java.nio.file.Path] =
    val files = JFiles.list(dir.toNioPath)
    try files.iterator.asScala.filter(_.getFileName.toString.endsWith(".sst")).toList
    finally files.close()

  test("an iterator that stops on a storage error: every API fails, none returns a part") {
    tempDir.use { dir =>
      writeRaw(dir / "db", Nil)
      // flip bytes inside the first data block: its checksum no longer matches
      val sst = tables(dir / "db") match
        case one :: Nil => one
        case other      => fail(s"expected one SST file, found ${other.size}")
      val file =
        java.nio.channels.FileChannel.open(sst, StandardOpenOption.READ, StandardOpenOption.WRITE)
      try
        val at = java.nio.ByteBuffer.wrap(Array[Byte](0x5a, 0x5a, 0x5a, 0x5a))
        file.write(at, 8L)
      finally file.close()
      everything(dir / "db", txIn(0)).map { rs =>
        ("read" :: "size" :: enumerations).foreach { name =>
          assert(
            failureOf(rs, name).left.exists {
              case StorageError.Corrupt(_, _) => true
              case _                          => false
            },
            clue(s"$name: ${failureOf(rs, name)}")
          )
        }
      }
    }
  }

  test("a legacy checkpoint with a stamp-like key of another byte is refused") {
    tempDir.use { dir =>
      val checkpoint = dir / "checkpoint"
      LegacyUtxoCheckpoint.write(
        checkpoint.toNioPath,
        entries.take(5),
        stamp,
        extra = List(Array[Byte](9))
      )
      attempt(open(dir / "live").use(_.restore(checkpoint))).map {
        case Left(StorageError.UtxoCheckpoint(_, CheckpointProblem.UnknownKeyLength(1))) => ()
        case other => fail(other.toString)
      }
    }
  }
