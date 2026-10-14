// SPDX-License-Identifier: Apache-2.0
package lab.node.storage

import cats.effect.IO
import cats.syntax.all.*
import fs2.io.file.Path
import java.nio.file.Files as JFiles
import lab.cbor.Bytes
import lab.network.ChainSync
import lab.node.{BlockRef, StorageError}
import munit.CatsEffectSuite
import scala.jdk.CollectionConverters.*
import scala.util.Random
import StorageTestKit.*

/** The volatile log: model equivalence over random operation sequences with reopening, collection,
  * every crash cut point of the newest file, and damage anywhere in the log.
  */
class VolatileStoreSuite extends CatsEffectSuite:
  private val perFile = 5

  private def open(dir: Path, blocksPerFile: Int = perFile)(using
      cats.mtl.Raise[IO, StorageError]
  ) = VolatileStore.open[IO](dir, blocksPerFile)

  private def logFiles(dir: Path): Vector[java.nio.file.Path] =
    val s = JFiles.list(dir.toNioPath)
    try s.iterator.asScala.filter(_.toString.endsWith(".log")).toVector.sortBy(_.toString)
    finally s.close()

  private def pointOf(ref: BlockRef): ChainSync.Point = ref.point

  /** An operation of the random test; the model is [[VolatileStore.chainOf]] over the same log. */
  private enum Op:
    case Append(i: Int)
    case Rollback(keep: Int) // roll back to the block `keep` places from the tip (0: origin)
    case Collect(slot: Int)
    case Reopen

  /** Block `i` of fork `fork`: distinct hashes per fork, slots grow with `i`. */
  private def forked(i: Int, fork: Int): (BlockRef, Bytes) =
    val (ref, raw) = block(i)
    (ref.copy(hash = hash(i * 1000 + fork)), raw)

  private def randomRun(seed: Int): IO[Unit] =
    val random = new Random(seed)
    tempDir.use { dir =>
      ok {
        for
          model <- cats.effect.Ref.of[IO, Vector[VolatileRecord]](Vector.empty)
          durable <- cats.effect.Ref.of[IO, BigInt](BigInt(-1))
          next <- cats.effect.Ref.of[IO, Int](0)
          fork <- cats.effect.Ref.of[IO, Int](0)
          opened <- open(dir).allocated
          current <- cats.effect.Ref.of[IO, (VolatileStore[IO], IO[Unit])](
            (opened._1._1, opened._2)
          )
          check = for
            records <- model.get
            slot <- durable.get
            old <- current.get
            _ <- old._2
            reopened <- open(dir).allocated
            _ <- current.set((reopened._1._1, reopened._2))
            expected = VolatileStore.chainOf(records).filter(_._1.slot > slot)
            actual = reopened._1._2.chain.filter(_._1.slot > slot)
            _ <- IO(assertEquals(actual.map(_._1), expected.map(_._1), s"seed $seed"))
            _ <- IO(assertEquals(actual.map(_._2), expected.map(_._2)))
          yield ()
          steps = 60 + random.nextInt(60)
          _ <- (0 until steps).toList.traverse_ { _ =>
            val roll = random.nextInt(100)
            for
              store <- current.get.map(_._1)
              _ <-
                if roll < 60 then
                  for
                    i <- next.getAndUpdate(_ + 1)
                    f <- fork.get
                    (ref, raw) = forked(i, f)
                    _ <- store.append(ref, raw)
                    _ <- model.update(_ :+ VolatileRecord.Block(ref, raw))
                  yield ()
                else if roll < 80 then
                  for
                    records <- model.get
                    chain = VolatileStore.chainOf(records)
                    keep = random.nextInt(chain.size.min(4) + 1)
                    target =
                      if keep == chain.size then ChainSync.Point.Origin
                      else pointOf(chain(chain.size - 1 - keep)._1)
                    _ <- store.rollback(target)
                    _ <- model.update(_ :+ VolatileRecord.Rollback(target))
                    // blocks after the target are replaced by a fork that reuses their numbers
                    _ <- next.update(n => n - (if keep == chain.size then chain.size else keep))
                    _ <- fork.update(_ + 1)
                  yield ()
                else if roll < 90 then
                  for
                    records <- model.get
                    chain = VolatileStore.chainOf(records)
                    // only slots of the part of the chain that is "immutable" in a real node
                    cut = chain.dropRight(3).lastOption.map(_._1.slot).getOrElse(BigInt(-1))
                    _ <- store.sync
                    _ <- store.gc(cut)
                    _ <- durable.update(_.max(cut))
                  yield ()
                else check
            yield ()
          }
          _ <- check
          last <- current.get
          _ <- last._2
        yield ()
      }
    }

  test("the log equals its model across random appends, rollbacks, collections and reopens") {
    (1 to 40).toList.traverse_(randomRun)
  }

  test("append, rollback and reopen: the chain is what the log says") {
    tempDir.use { dir =>
      ok {
        for
          first <- open(dir).use { (store, opened) =>
            val bs = (0 until 12).map(block(_))
            for
              _ <- IO(assertEquals(opened.chain, Vector.empty))
              _ <- bs.toList.traverse_((r, raw) => store.append(r, raw))
              _ <- store.rollback(bs(7)._1.point)
              _ <- store.append(forked(8, 1)._1, forked(8, 1)._2)
            yield ()
          }
          chain <- open(dir).use((_, opened) => IO.pure(opened))
          _ <- IO {
            assertEquals(chain.chain.map(_._1.blockNo.toInt), (0 to 7).toVector :+ 8)
            assertEquals(chain.chain.last._1.hash, forked(8, 1)._1.hash)
            assertEquals(chain.report.files, 3) // 5 blocks per file
          }
        yield ()
      }
    }
  }

  test("collection removes only whole files below the slot and keeps the newest file") {
    tempDir.use { dir =>
      ok {
        open(dir).use { (store, _) =>
          for
            _ <- (0 until 23).toList.traverse_(i => store.append(block(i)._1, block(i)._2))
            _ <- IO(assertEquals(logFiles(dir).size, 5))
            // files hold blocks 0-4, 5-9, 10-14, 15-19, 20-22
            _ <- store.gc(block(12)._1.slot)
            _ <- IO(
              assertEquals(logFiles(dir).size, 3)
            ) // the files of blocks 0-4 and 5-9 are wholly below
            _ <- store.gc(block(999)._1.slot)
            _ <- IO(assertEquals(logFiles(dir).size, 1)) // the newest file is kept
          yield ()
        } *> open(dir).use((_, opened) =>
          IO(assertEquals(opened.chain.map(_._1.blockNo.toInt), (20 to 22).toVector))
        )
      }
    }
  }

  test("a crash at every cut point of the newest file loses only what was not completely written") {
    tempDir.use { dir =>
      ok {
        for
          _ <- open(dir).use { (store, _) =>
            (0 until 8).toList.traverse_ { i =>
              store.append(block(i)._1, block(i)._2) *>
                (if i == 5 then store.rollback(block(3)._1.point) else IO.unit)
            }
          }
          files = logFiles(dir)
          newest = files.last
          whole = JFiles.readAllBytes(newest)
          records = VolatileStore.decodeAll(whole)
          before = files
            .dropRight(1)
            .flatMap(f => VolatileStore.decodeAll(JFiles.readAllBytes(f)).map(_._1))
          _ <- (0 to whole.length).toList.traverse_ { cut =>
            for
              _ <- IO(JFiles.write(newest, whole.take(cut)))
              kept = records.takeWhile(_._2 <= cut).map(_._1)
              expected = VolatileStore.chainOf(before ++ kept)
              got <- open(dir).use((_, opened) => IO.pure(opened))
              _ <- IO(assertEquals(got.chain.map(_._1), expected.map(_._1), s"cut at $cut"))
              _ <- IO(assertEquals(got.chain.map(_._2), expected.map(_._2)))
              // after the repair the file is exactly its valid prefix and appending works
              _ <- IO(
                assertEquals(
                  JFiles.size(newest).toInt,
                  records.takeWhile(_._2 <= cut).lastOption.fold(0)(_._2)
                )
              )
            yield ()
          }
        yield ()
      }
    }
  }

  test("a damaged record in an older file ends the log there and removes the files after it") {
    tempDir.use { dir =>
      ok {
        for
          _ <- open(dir).use((store, _) =>
            (0 until 17).toList.traverse_(i => store.append(block(i)._1, block(i)._2))
          )
          files = logFiles(dir)
          _ <- IO(assertEquals(files.size, 4))
          // flip a byte inside the payload of the third block of the second file (block 7)
          bytes = JFiles.readAllBytes(files(1))
          second = VolatileStore.decodeAll(bytes)
          flipAt = second(1)._2 + VolatileStore.HeaderSize + 1
          _ <- IO(bytes(flipAt) = (bytes(flipAt) ^ 0x55).toByte)
          _ <- IO(JFiles.write(files(1), bytes))
          got <- open(dir).use((_, opened) => IO.pure(opened))
          _ <- IO {
            // the first file (blocks 0-4) and the two blocks of the second before the damage survive
            assertEquals(got.chain.map(_._1.blockNo.toInt), (0 to 6).toVector)
            assertEquals(got.report.removedFiles, 2)
            assert(got.report.truncatedBytes > 0)
            assertEquals(logFiles(dir).size, 2)
          }
          // the log keeps working after the repair
          _ <- open(dir).use((store, _) => store.append(block(7)._1, block(7)._2))
          again <- open(dir).use((_, opened) => IO.pure(opened))
          _ <- IO(assertEquals(again.chain.map(_._1.blockNo.toInt), (0 to 7).toVector))
        yield ()
      }
    }
  }

  test("records round trip and foreign bytes are not records") {
    val (ref, raw) = block(3)
    val boundary = (ref.copy(boundary = true), raw)
    val records = Vector(
      VolatileRecord.Block(ref, raw),
      VolatileRecord.Block(boundary._1, boundary._2),
      VolatileRecord.Rollback(ChainSync.Point.Origin),
      VolatileRecord.Rollback(ref.point)
    )
    val bytes = records.flatMap(r => VolatileStore.encode(r).toVector).toArray
    assertEquals(VolatileStore.decodeAll(bytes).map(_._1), records)
    assertEquals(VolatileStore.decodeAll(Array.fill(300)(7.toByte)), Vector.empty)
    assertEquals(VolatileStore.decodeAll(Array.empty[Byte]), Vector.empty)
  }
