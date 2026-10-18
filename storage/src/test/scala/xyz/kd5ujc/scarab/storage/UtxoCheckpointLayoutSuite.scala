// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.IO
import cats.mtl.Raise
import cats.syntax.all.*
import fs2.io.file.Path
import java.nio.file.Files as JFiles
import munit.CatsEffectSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{CheckpointProblem, StorageError, TxIn, TxOut}

import StorageTestKit.*

/** The UTxO checkpoints that builds before K6 (2026-10-14) wrote, keyed `txid ++ u32` where this
  * build reads `txid ++ u16`: restoring one gives the live store the same set under the current
  * keys, and a checkpoint that cannot be converted safely is refused with a typed error (issue
  * #145: the snapshots of format 1 migrated, but their checkpoints were read as empty, and the
  * first block that spent a stored output was rejected with `BadInputsUTxO`).
  */
class UtxoCheckpointLayoutSuite extends CatsEffectSuite:
  private def be(n: Long, size: Int): Array[Byte] =
    Array.tabulate(size)(i => ((n >>> (8 * (size - 1 - i))) & 0xff).toByte)

  private def output(seed: Int): TxOut =
    val address = (0x61.toByte +: be(seed.toLong, 28))
    val cbor =
      Array[Byte](0x82.toByte, 0x58.toByte, address.length.toByte) ++ address ++
        Array[Byte](0x1a.toByte) ++ be(1000L + seed, 4)
    TxOut.fromBytes(Bytes.wrap(cbor)).fold(e => fail(e.toString), identity)

  /** A set of outputs of 120 transactions with up to five outputs each, the larger indexes among
    * them.
    */
  private val entries: Map[TxIn, TxOut] =
    (for
      t <- 0 until 120
      i <- 0 to (t % 5)
    yield TxIn(Bytes.wrap(be(t.toLong + 1, 32)), BigInt(i * 3000 + t % 7)) -> output(
      t * 10 + i
    )).toMap

  private val stamp = 4242L

  private def open(dir: Path)(using Raise[IO, StorageError]) =
    RocksUtxoStore.resource[IO](dir, 8L * 1024 * 1024)

  private def listing(dir: Path): Map[String, Long] =
    val files = JFiles.list(dir.toNioPath)
    try
      files.iterator.asScala
        .map(f => f.getFileName.toString -> JFiles.size(f))
        .toMap
    finally files.close()

  test(
    "a checkpoint of the layout before K6 restores as the set it holds, under the current keys"
  ) {
    tempDir.use { dir =>
      val checkpoint = dir / "checkpoint"
      LegacyUtxoCheckpoint.write(checkpoint.toNioPath, entries, stamp)
      val before = listing(checkpoint)
      ok {
        open(dir / "live").use { store =>
          for
            restored <- store.restore(checkpoint)
            all <- store.all
            at <- store.flushed
            size <- store.size
            some = entries.keys.take(10).toList
            read <- store.read(some)
            // what the store writes next is readable beside what it restored
            fresh = TxIn(Bytes.wrap(be(9999, 32)), BigInt(1)) -> output(777)
            _ <- store.write(List(fresh._1 -> Some(fresh._2)), stamp + 1)
            readFresh <- store.read(List(fresh._1))
          yield
            assert(restored)
            assertEquals(all, entries)
            assertEquals(at, stamp)
            assertEquals(size, entries.size.toLong)
            assertEquals(read, some.map(i => i -> entries(i)).toMap)
            assertEquals(readFresh, Map(fresh))
        }
      }.map { _ =>
        // the checkpoint is read, never changed
        assertEquals(listing(checkpoint), before)
        assert(!RocksUtxoStore.hasCurrentKeys(checkpoint.toNioPath))
      }
    }
  }

  test("a checkpoint taken from a store that restored one of the old layout has the current keys") {
    tempDir.use { dir =>
      val old = dir / "old"
      val next = dir / "next"
      LegacyUtxoCheckpoint.write(old.toNioPath, entries, stamp)
      ok {
        open(dir / "live").use { store =>
          store.restore(old) *> store.checkpoint(next)
        }
      } *>
        ok {
          open(dir / "live2").use { store =>
            for
              restored <- store.restore(next)
              all <- store.all
            yield
              assert(RocksUtxoStore.hasCurrentKeys(next.toNioPath))
              assert(restored)
              assertEquals(all, entries)
          }
        }
    }
  }

  test("a checkpoint of the current layout is linked, not rewritten") {
    tempDir.use { dir =>
      val checkpoint = dir / "checkpoint"
      ok {
        open(dir / "live").use { store =>
          store.write(entries.toList.map((k, v) => k -> Some(v)), stamp) *>
            store.checkpoint(checkpoint)
        }
      } *>
        ok {
          open(dir / "live2").use { store =>
            for
              restored <- store.restore(checkpoint)
              all <- store.all
              at <- store.flushed
            yield
              assert(restored)
              assertEquals(all, entries)
              assertEquals(at, stamp)
          }
        }
    }
  }

  private val legacyOnly = entries.take(5)

  /** The problem a refused restore names, from the typed error. */
  private def problemOf(refused: Either[StorageError, Boolean]): Option[CheckpointProblem] =
    refused match
      case Left(StorageError.UtxoCheckpoint(_, p)) => Some(p)
      case _                                       => None

  /** Restore `checkpoint` into a fresh live store, which must refuse it; then reopen the live store
    * to see what the refusal left in it.
    */
  private def refusal(
      dir: Path,
      checkpoint: Path
  ): IO[(Either[StorageError, Boolean], Long, Long)] =
    for
      refused <- attempt(open(dir / "live").use(_.restore(checkpoint)))
      left <- ok(open(dir / "live").use(s => (s.size, s.flushed).tupled))
    yield (refused, left._1, left._2)

  test("a checkpoint with keys of both layouts is refused, and the store is left empty") {
    tempDir.use { dir =>
      val checkpoint = dir / "checkpoint"
      // a build of the current layout wrote to the store of one of the old: this key is one of its
      LegacyUtxoCheckpoint.write(
        checkpoint.toNioPath,
        legacyOnly,
        stamp,
        extra = List(Array.fill[Byte](34)(7))
      )
      refusal(dir, checkpoint).map { (refused, size, at) =>
        assertEquals(problemOf(refused), Some(CheckpointProblem.MixedKeyLayouts))
        assert(refused.left.exists(_.message.contains("replay from the immutable chunks")))
        assertEquals(size, 0L)
        assertEquals(at, -1L)
      }
    }
  }

  test("an output index the current key cannot hold refuses the checkpoint") {
    tempDir.use { dir =>
      val checkpoint = dir / "checkpoint"
      val big = new Array[Byte](36)
      big(33) = 1 // index 0x10001
      big(35) = 1
      LegacyUtxoCheckpoint.write(checkpoint.toNioPath, legacyOnly, stamp, extra = List(big))
      refusal(dir, checkpoint).map((refused, _, _) =>
        assertEquals(problemOf(refused), Some(CheckpointProblem.IndexTooLarge(0x10001L)))
      )
    }
  }

  test("a key of a length no layout has refuses the checkpoint") {
    tempDir.use { dir =>
      val checkpoint = dir / "checkpoint"
      LegacyUtxoCheckpoint.write(
        checkpoint.toNioPath,
        legacyOnly,
        stamp,
        extra = List(Array.fill[Byte](35)(9))
      )
      refusal(dir, checkpoint).map((refused, _, _) =>
        assertEquals(problemOf(refused), Some(CheckpointProblem.UnknownKeyLength(35)))
      )
    }
  }

  test("a directory that is no checkpoint restores nothing") {
    tempDir.use { dir =>
      ok(open(dir / "live").use(_.restore(dir / "absent"))).map(restored => assert(!restored))
    }
  }
