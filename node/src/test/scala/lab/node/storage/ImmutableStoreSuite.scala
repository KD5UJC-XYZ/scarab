// SPDX-License-Identifier: Apache-2.0
package lab.node.storage

import cats.effect.IO
import cats.syntax.all.*
import fs2.Stream
import fs2.io.file.{Files, Flag, Flags, Path}
import lab.cbor.Bytes
import lab.network.ChainSync
import lab.node.{BlockRef, StorageError}
import munit.CatsEffectSuite
import StorageTestKit.*

class ImmutableStoreSuite extends CatsEffectSuite:
  private val chunkSlots = 30L // ten blocks (slot gap 3) per chunk
  private val files = Files.forAsync[IO]

  private def open(dir: Path, slots: Long = chunkSlots)(using cats.mtl.Raise[IO, StorageError]) =
    FileImmutableStore.open[IO](dir, slots)

  private def fill(store: ImmutableStore[IO], range: Range): IO[Unit] =
    range.toList.traverse_ { i =>
      val (ref, raw) = block(i)
      store.append(ref, raw)
    }

  private def truncateFile(path: Path, size: Long): IO[Unit] =
    files.open(path, Flags(Flag.Write)).use(_.truncate(size))

  private def chunkFile(dir: Path, c: Int) = dir / f"$c%05d.chunk"
  private def indexFile(dir: Path, c: Int) = dir / f"$c%05d.index"

  test("append, get, tip, byBlockNo and stream across chunks") {
    tempDir.use { dir =>
      ok {
        open(dir).use { (store, report) =>
          for
            _ <- IO(assertEquals(report.tip, None))
            _ <- fill(store, 0 until 35)
            tip <- store.tip
            _ <- IO(assertEquals(tip.map(_.blockNo), Some(BigInt(34))))
            gets <- (0 until 35).toList.traverse(i => store.get(block(i)._1.point))
            _ <- IO(assertEquals(gets.flatten, (0 until 35).map(block(_)._2).toList))
            byNo <- store.byBlockNo(17)
            _ <- IO(assertEquals(byNo.map(_.ref), Some(block(17)._1)))
            _ <- store.byBlockNo(35).map(r => assertEquals(r, None))
            all <- store.stream(ChainSync.Point.Origin).compile.toList
            _ <- IO(assertEquals(all.map(_.ref), (0 until 35).map(block(_)._1).toList))
            _ <- IO(assertEquals(all.map(_.raw), (0 until 35).map(block(_)._2).toList))
            from <- store.stream(block(12)._1.point).compile.toList
            _ <- IO(assertEquals(from.map(_.ref.blockNo.toInt), (13 until 35).toList))
            last <- store.stream(block(34)._1.point).compile.toList
            _ <- IO(assertEquals(last, Nil))
            // a point with the right slot and a wrong hash is not stored
            wrong = ChainSync.Point.Block(ChainSync.UInt64.from(BigInt(36)).toOption.get, hash(999))
            _ <- store.get(wrong).map(r => assertEquals(r, None))
          yield ()
        }
      }
    }
  }

  test("streaming after an unknown point raises Missing") {
    tempDir.use { dir =>
      attempt {
        open(dir).use { (store, _) =>
          val unknown =
            ChainSync.Point.Block(ChainSync.UInt64.from(BigInt(5)).toOption.get, hash(77))
          fill(store, 0 until 3) *> store.stream(unknown).compile.drain
        }
      }.map(r => assert(r.left.exists(_.isInstanceOf[StorageError.Missing]), r.toString))
    }
  }

  test("reopening finds everything and keeps appending") {
    tempDir.use { dir =>
      ok {
        for
          _ <- open(dir).use((store, _) => fill(store, 0 until 25))
          report <- open(dir).use { (store, report) =>
            fill(store, 25 until 31).as(report)
          }
          _ <- IO(assertEquals(report.blocks, 25L))
          _ <- IO(assertEquals(report.tip.map(_.blockNo), Some(BigInt(24))))
          _ <- IO(assertEquals((report.truncatedEntries, report.truncatedBytes), (0, 0L)))
          all <- open(dir).use((store, _) => store.stream(ChainSync.Point.Origin).compile.toList)
          _ <- IO(assertEquals(all.map(_.ref), (0 until 31).map(block(_)._1).toList))
        yield ()
      }
    }
  }

  test("appends must extend the chain: slot order, 32-byte hashes") {
    tempDir.use { dir =>
      for
        decreasing <- attempt {
          open(dir).use((store, _) =>
            fill(store, 0 until 5) *> store.append(block(2)._1, block(2)._2)
          )
        }
        shortHash <- attempt {
          open(dir).use((store, _) =>
            store.append(BlockRef(1000, Bytes(Vector(1)), 99), Bytes.empty)
          )
        }
      yield
        assert(decreasing.left.exists(_.isInstanceOf[StorageError.OutOfOrder]), decreasing.toString)
        assert(shortHash.left.exists(_.isInstanceOf[StorageError.OutOfOrder]), shortHash.toString)
    }
  }

  test("a Byron boundary block may share its slot with the block after it") {
    tempDir.use { dir =>
      ok {
        open(dir).use { (store, _) =>
          val boundary = BlockRef(60, hash(1), 7, boundary = true)
          val first = BlockRef(60, hash(2), 8)
          for
            _ <- store.append(boundary, Bytes(Vector(1)))
            _ <- store.append(first, Bytes(Vector(2)))
            both <- store.stream(ChainSync.Point.Origin).compile.toList
            _ <- IO(assertEquals(both.map(_.ref), List(boundary, first)))
            _ <- store.get(first.point).map(r => assertEquals(r, Some(Bytes(Vector(2)))))
            _ <- store.get(boundary.point).map(r => assertEquals(r, Some(Bytes(Vector(1)))))
          yield ()
        }
      }
    }
  }

  test("a different chunk size is a version mismatch") {
    tempDir.use { dir =>
      for
        _ <- ok(open(dir).use((s, _) => fill(s, 0 until 3)))
        r <- attempt(open(dir, 99).use((_, _) => IO.unit))
      yield assert(r.left.exists(_.isInstanceOf[StorageError.VersionMismatch]), r.toString)
    }
  }

  // ---------------------------------------------------------------- crash recovery

  /** Write 35 blocks (chunks 0..3, the last holding blocks 30..34), then damage the newest chunk.
    */
  private def damaged(
      damage: (Path) => IO[Unit]
  )(check: (ImmutableStore.OpenReport, ImmutableStore[IO]) => IO[Unit]) =
    tempDir.use { dir =>
      ok {
        for
          _ <- open(dir).use((s, _) => fill(s, 0 until 35))
          _ <- damage(dir)
          _ <- open(dir).use((store, report) => check(report, store))
        yield ()
      }
    }

  private def dataEnd(blockNo: Int): Long = (30 to blockNo).map(i => block(i)._2.size.toLong).sum

  test("crash: data shorter than the index claims drops the unwritten tail") {
    damaged(dir => truncateFile(chunkFile(dir, 3), dataEnd(32) + 5)) { (report, store) =>
      IO {
        assertEquals(report.tip.map(_.blockNo), Some(BigInt(32)))
        assertEquals(report.truncatedEntries, 2)
        assertEquals(report.truncatedBytes, 5L)
      } *> store.stream(ChainSync.Point.Origin).compile.toList.map(r => assertEquals(r.size, 33)) *>
        // and the store keeps working from the repaired tip
        fill(store, 33 until 36) *> store.tip.map(t =>
          assertEquals(t.map(_.blockNo), Some(BigInt(35)))
        )
    }
  }

  test("crash: a torn index entry is dropped") {
    damaged(dir =>
      files.size(indexFile(dir, 3)).flatMap(s => truncateFile(indexFile(dir, 3), s - 10))
    ) { (report, _) =>
      IO(assertEquals(report.tip.map(_.blockNo), Some(BigInt(33))))
    }
  }

  test("crash: garbage after the last block is truncated away") {
    damaged(dir =>
      Stream
        .emits(List.fill(50)(0x5a.toByte))
        .through(files.writeAll(chunkFile(dir, 3), Flags(Flag.Append, Flag.Write)))
        .compile
        .drain
    ) { (report, _) =>
      IO(assertEquals((report.tip.map(_.blockNo), report.truncatedBytes), (Some(BigInt(34)), 50L)))
    }
  }

  test("crash: a flipped byte fails the checksum, dropping that block and everything after") {
    damaged { dir =>
      files.open(chunkFile(dir, 3), Flags(Flag.Read, Flag.Write)).use { h =>
        h.write(fs2.Chunk.singleton(0x77.toByte), dataEnd(31) + 3L).void
      }
    } { (report, _) =>
      IO(assertEquals(report.tip.map(_.blockNo), Some(BigInt(31))))
    }
  }

  test("crash: a newest chunk with no valid block disappears; the previous chunk is the tail") {
    damaged(dir => truncateFile(chunkFile(dir, 3), 0)) { (report, store) =>
      IO(assertEquals(report.tip.map(_.blockNo), Some(BigInt(29)))) *>
        IO(assertEquals(report.chunks, 3)) *> fill(store, 30 until 33)
    }
  }

  test("crash: a data file whose index was never created is discarded") {
    damaged { dir =>
      files.delete(indexFile(dir, 3)) *>
        Stream.emit("x").through(files.writeUtf8(dir / "00004.chunk")).compile.drain
    } { (report, store) =>
      IO(assertEquals(report.tip.map(_.blockNo), Some(BigInt(29)))) *> fill(store, 30 until 32)
    }
  }

  test("crash at any byte of the newest chunk leaves a consistent prefix") {
    val total = dataEnd(34)
    (0L until total by 37L).toList.traverse_ { cut =>
      damaged(dir => truncateFile(chunkFile(dir, 3), cut)) { (report, store) =>
        val expected = (30 to 34).takeWhile(i => dataEnd(i) <= cut).lastOption.getOrElse(29)
        for
          _ <- IO(assertEquals(report.tip.map(_.blockNo.toInt), Some(expected), s"cut at $cut"))
          all <- store.stream(ChainSync.Point.Origin).compile.toList
          _ <- IO(assertEquals(all.map(_.ref), (0 to expected).map(block(_)._1).toList))
        yield ()
      }
    }
  }
