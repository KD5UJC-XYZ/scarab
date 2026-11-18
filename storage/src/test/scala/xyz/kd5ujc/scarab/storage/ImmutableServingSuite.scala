// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.{Deferred, IO, Outcome}
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import fs2.io.file.{Files, Flag, Flags, Path}
import scala.annotation.tailrec
import scala.concurrent.duration.*
import org.scalacheck.Gen
import org.scalacheck.rng.Seed
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.ledger.StorageError
import xyz.kd5ujc.scarab.protocol.ChainSync
import xyz.kd5ujc.scarab.network.FailsOnUncaught

import CountedReads.Read
import StorageTestKit.*

/** What serving stored blocks reads: the block asked for, from the entry its index gives, and the
  * index of a sealed chunk once while it is held. The reference reads one block per step of an
  * iterator (`ImmutableDB/Impl/Iterator.hs` 682-691, ouroboros-consensus 82ecba32).
  */
class ImmutableServingSuite extends FailsOnUncaught:
  private val ChunkSlots = 30L // ten blocks (slot gap 3) per chunk
  private val Bound = 1000
  private val files = Files.forAsync[IO]

  private def fill(store: ImmutableStore[IO], range: Range)(using
      cats.mtl.Raise[IO, StorageError]
  ): IO[Unit] =
    range.toList.traverse_ { i =>
      val (ref, raw) = block(i)
      store.append(ref, raw)
    }

  private def chunkFile(dir: Path, c: Int) = dir / f"$c%05d.chunk"
  private def indexFile(dir: Path, c: Int) = dir / f"$c%05d.index"

  /** The read of block `i`'s bytes alone, in a chunk that holds blocks from `10 * (i / 10)` on. */
  private def blockRead(dir: Path, i: Int) =
    val offset = (10 * (i / 10) until i).map(block(_)._2.size.toLong).sum
    Read(chunkFile(dir, i / 10), offset, offset + block(i)._2.size)

  /** The read of chunk `c`'s index, holding `entries` entries. */
  private def indexRead(dir: Path, c: Int, entries: Int = 10) =
    Read(
      indexFile(dir, c),
      0L,
      FileImmutableStore.HeaderSize.toLong + entries.toLong * FileImmutableStore.EntrySize
    )

  private def next(store: ImmutableStore[IO], i: Int)(using cats.mtl.Raise[IO, StorageError]) =
    store.stream(block(i)._1.point).take(1).compile.toList.map(_.map(_.ref))

  test("serving a block reads its bytes alone, the chunk indexes being held in memory") {
    tempDir.use { dir =>
      ok {
        CountedReads.open(dir, ChunkSlots, Bound).use { (store, log) =>
          for
            _ <- fill(store, 0 until 35)
            _ <- log.set(Vector.empty)
            first <- next(store, 12)
            second <- next(store, 13)
            // the last block of chunk 1: the next is the first of chunk 2
            across <- next(store, 19)
            // in chunk 3, the chunk appended to
            newest <- next(store, 31)
            got <- store.get(block(15)._1.point)
            reads <- log.get
          yield
            assertEquals(
              List(first, second, across, newest),
              List(13, 14, 20, 32).map(i => List(block(i)._1))
            )
            assertEquals(got, Some(block(15)._2))
            assertEquals(reads, Vector(13, 14, 20, 32, 15).map(blockRead(dir, _)))
        }
      }
    }
  }

  test("after an open, a sealed chunk's index is read once; the chunk appended to is held") {
    tempDir.use { dir =>
      for
        _ <- ok(FileImmutableStore.open[IO](dir, ChunkSlots).use((s, _) => fill(s, 0 until 35)))
        result <- ok {
          CountedReads.open(dir, ChunkSlots, Bound).use { (store, log) =>
            for
              first <- next(store, 12)
              firstReads <- log.getAndSet(Vector.empty)
              second <- next(store, 13)
              newest <- next(store, 31)
              laterReads <- log.get
            yield (List(first, second, newest), firstReads, laterReads)
          }
        }
        (served, firstReads, laterReads) = result
      yield
        assertEquals(served, List(13, 14, 32).map(i => List(block(i)._1)))
        assertEquals(firstReads, Vector(indexRead(dir, 1), blockRead(dir, 13)))
        assertEquals(laterReads, Vector(blockRead(dir, 14), blockRead(dir, 32)))
    }
  }

  test("a range read partway has read the blocks it gave and the indexes it reached, no others") {
    tempDir.use { dir =>
      for
        _ <- ok(FileImmutableStore.open[IO](dir, ChunkSlots).use((s, _) => fill(s, 0 until 35)))
        result <- ok {
          CountedReads.open(dir, ChunkSlots, Bound).use { (store, log) =>
            store.stream(block(7)._1.point).take(5).compile.toList.product(log.get)
          }
        }
        (taken, reads) = result
      yield
        assertEquals(taken.map(_.ref), (8 to 12).map(block(_)._1).toList)
        assertEquals(taken.map(_.raw), (8 to 12).map(block(_)._2).toList)
        assertEquals(
          reads,
          Vector(indexRead(dir, 0), blockRead(dir, 8), blockRead(dir, 9), indexRead(dir, 1)) ++
            (10 to 12).map(blockRead(dir, _))
        )
    }
  }

  test("the chunk appended to is held in memory, and stays held once a later chunk seals it") {
    tempDir.use { dir =>
      ok {
        CountedReads.open(dir, ChunkSlots, Bound).use { (store, log) =>
          for
            _ <- fill(store, 0 until 25)
            _ <- log.set(Vector.empty)
            appending <- (next(store, 21), next(store, 21)).tupled
            _ <- fill(store, 25 until 35)
            sealedNow <- (next(store, 21), next(store, 21)).tupled
            reads <- log.get
          yield
            assertEquals(
              List(appending, sealedNow),
              List.fill(2)((List(block(22)._1), List(block(22)._1)))
            )
            assertEquals(reads, Vector.fill(4)(blockRead(dir, 22)))
        }
      }
    }
  }

  test("a sealed index larger than the bound is not held, and is read from its file each time") {
    tempDir.use { dir =>
      ok {
        // chunks of ten entries, a bound of five
        CountedReads.open(dir, ChunkSlots, 5).use { (store, log) =>
          for
            _ <- fill(store, 0 until 35)
            _ <- log.set(Vector.empty)
            served <- (next(store, 12), next(store, 13), next(store, 31)).tupled
            reads <- log.get
          yield
            assertEquals(served, (List(block(13)._1), List(block(14)._1), List(block(32)._1)))
            // a stream takes the index once to find the point and once to read on from it
            val unheld = (i: Int) => Vector(indexRead(dir, 1), indexRead(dir, 1), blockRead(dir, i))
            assertEquals(reads, unheld(13) ++ unheld(14) :+ blockRead(dir, 32))
        }
      }
    }
  }

  /** Opens a store under `TestControl` at time zero and appends blocks 0 to 34, sealing chunks 0 to
    * 2 and appending to chunk 3. Then, for each step, advances the clock by the step's delay, which
    * runs the expiry of unused indexes that falls due, and gets the step's block. Gives the block
    * and the reads of each get, the time the runtime next has work for while the store is open, and
    * once it is released.
    */
  private def getsOverTime(dir: Path, steps: List[(FiniteDuration, Int)]) =
    for
      gates <- steps.traverse(_ => Deferred[IO, Unit])
      release <- Deferred[IO, Unit]
      control <- TestControl.execute(ok {
        CountedReads.open(dir, ChunkSlots, Bound).use { (store, log) =>
          for
            _ <- fill(store, 0 until 35)
            _ <- log.set(Vector.empty)
            got <- steps.zip(gates).traverse { case ((_, i), gate) =>
              gate.get *> store.get(block(i)._1.point).product(log.getAndSet(Vector.empty))
            }
            _ <- release.get
          yield got
        }
      })
      _ <- control.tick
      _ <- steps.zip(gates).traverse_ { case ((delay, _), gate) =>
        control.advanceAndTick(delay).whenA(delay > Duration.Zero) *>
          gate.complete(()) *> control.tick
      }
      whileOpen <- control.nextInterval
      _ <- release.complete(()) *> control.tick
      afterRelease <- control.nextInterval
      outcome <- control.results
    yield outcome match
      case Some(Outcome.Succeeded(got)) => (got, whileOpen, afterRelease)
      case other                        => fail(s"the store's program did not finish: $other")

  test("a sealed chunk's index unused for five minutes is dropped; the one appended to never is") {
    tempDir.use { dir =>
      getsOverTime(
        dir,
        List(0.minutes -> 15, 0.minutes -> 32, 5.minutes -> 15, 0.minutes -> 25, 15.minutes -> 32)
      ).map { (got, _, _) =>
        assertEquals(got.map(_._1), List(15, 32, 15, 25, 32).map(i => Some(block(i)._2)))
        // The look at five minutes dropped chunks 1 and 2, last used at zero; the chunk appended
        // to is read from alone fifteen minutes and a look later.
        assertEquals(
          got.map(_._2),
          List(
            Vector(blockRead(dir, 15)),
            Vector(blockRead(dir, 32)),
            Vector(indexRead(dir, 1), blockRead(dir, 15)),
            Vector(indexRead(dir, 2), blockRead(dir, 25)),
            Vector(blockRead(dir, 32))
          )
        )
      }
    }
  }

  test("an index used since the look before is kept; one last used five minutes before is not") {
    tempDir.use { dir =>
      getsOverTime(
        dir,
        List(4.minutes -> 15, 1.minute -> 15, 0.minutes -> 25, 5.minutes -> 15)
      ).map { (got, _, _) =>
        assertEquals(got.map(_._1), List(15, 15, 25, 15).map(i => Some(block(i)._2)))
        // The look at five minutes kept chunk 1, used at four, and dropped chunk 2; the look at
        // ten minutes dropped chunk 1, last used at five.
        assertEquals(
          got.map(_._2),
          List(
            Vector(blockRead(dir, 15)),
            Vector(blockRead(dir, 15)),
            Vector(indexRead(dir, 2), blockRead(dir, 25)),
            Vector(indexRead(dir, 1), blockRead(dir, 15))
          )
        )
      }
    }
  }

  test("the expiry waits five minutes between looks while the store is open, and ends with it") {
    tempDir.use { dir =>
      getsOverTime(dir, List(7.minutes -> 15)).map { (got, whileOpen, afterRelease) =>
        assertEquals(got.map(_._2), List(Vector(indexRead(dir, 1), blockRead(dir, 15))))
        // the look due at five minutes ran when the clock reached seven, and waits five more
        assertEquals(whileOpen, 5.minutes)
        assertEquals(afterRelease, Duration.Zero)
      }
    }
  }

  test("the work beside the store ends with its failure as a value, raised where it is landed") {
    val failure = new IllegalStateException("the work beside the store")
    for
      ended <- Deferred[IO, Unit]
      landed <- Deferred[IO, Either[Throwable, Unit]]
      work = IO.raiseError[Nothing](failure).guarantee(ended.complete(()).void)
      // the work has failed before it is landed: a fiber that ended with an uncaught failure is
      // reported by the runtime, which this suite fails on, unless the land's join is registered
      // before the fiber ends; the fiber's type in `alongside` pins that it ends with a value
      released <- FileImmutableStore
        .alongside[IO](work)
        .use(land => ended.get *> land.attempt.flatMap(landed.complete).void)
        .attempt
      value <- landed.get
    yield
      assertEquals(value, Left(failure))
      assertEquals(released, Left(failure))
  }

  /** An index of five entries. */
  private val five = Vector.fill(5)(FileImmutableStore.Entry(0L, 1, 0, block(0)._1))

  test("the index added is held and the least recently used dropped, even when the added is it") {
    val inOrder = FileImmutableStore.IndexCache.empty
      .added(1, five, 10, 1.second)
      .added(2, five, 10, 2.seconds)
      .added(3, five, 10, 3.seconds)
    // a miss that read the clock before chunks 2 and 3 were added
    val late = FileImmutableStore.IndexCache.empty
      .added(2, five, 10, 2.seconds)
      .added(3, five, 10, 3.seconds)
      .added(1, five, 10, 1.second)
    assertEquals(inOrder.held.keySet, Set(2, 3))
    assertEquals(late.held.keySet, Set(2, 3))
    assertEquals((inOrder.total, late.total), (10L, 10L))
  }

  test("an index's age is taken from two readings of the clock, so none near its least overflows") {
    val least = Duration.fromNanos(Long.MinValue + 1)
    val cache = FileImmutableStore.IndexCache.empty.added(1, five, 10, least)
    assertEquals(cache.expired(least + 1.minute, 5.minutes).held.keySet, Set(1))
    assertEquals(cache.expired(least + 5.minutes, 5.minutes).held.keySet, Set.empty[Int])
  }

  /** The entries of chunks 0 to 6 in the store the cache is checked on; chunk 6 is appended to. */
  private val Sizes = Vector(3, 10, 6, 1, 8, 4, 5)

  /** The index files read for `accesses` (chunk numbers) by a cache of `bound` entries over the
    * sealed chunks of [[Sizes]] that drops the least recently used chunk until a new one fits, and
    * holds no chunk larger than the bound; chunk 6, the one appended to, is held apart and never
    * read.
    */
  private def leastRecentlyUsedReads(bound: Int, accesses: List[Int]): List[Int] =
    @tailrec def fit(held: List[Int], room: Int): List[Int] =
      if held.map(Sizes(_)).sum + room <= bound then held else fit(held.init, room)
    accesses
      .filter(_ != 6)
      .foldLeft((List.empty[Int], List.empty[Int])) { case ((held, read), c) =>
        if held.contains(c) then (c :: held.filterNot(_ == c), read)
        else if Sizes(c) > bound then (held, read :+ c)
        else (c :: fit(held, Sizes(c)), read :+ c)
      }
      ._2

  test("sealed indexes are held within a bound in entries, the least recently used dropped first") {
    val cases = for
      bound <- Gen.choose(0, Sizes.init.sum)
      accesses <- Gen.listOf(Gen.choose(0, 6).flatMap(c => Gen.choose(0, Sizes(c) - 1).map((c, _))))
    yield (bound, accesses)
    val stored = Sizes.indices.flatMap(c => (0 until Sizes(c)).map(10 * c + _)).toList
    tempDir.use { dir =>
      ok {
        FileImmutableStore
          .open[IO](dir, ChunkSlots)
          .use((s, _) => stored.traverse_(i => s.append(block(i)._1, block(i)._2)))
      } *>
        (0L until 60L).toList.traverse_ { seed =>
          val (bound, accesses) = cases.pureApply(Gen.Parameters.default, Seed(seed))
          ok {
            CountedReads.open(dir, ChunkSlots, bound).use { (store, log) =>
              accesses.traverse_((c, j) => store.get(block(10 * c + j)._1.point)) *> log.get
            }
          }.map { reads =>
            val indexes = reads.toList.flatMap(r => (0 to 6).find(c => indexFile(dir, c) == r.path))
            assertEquals(
              indexes,
              leastRecentlyUsedReads(bound, accesses.map(_._1)),
              s"seed $seed, bound $bound, accesses $accesses"
            )
          }
        }
    }
  }

  /** A chain of main blocks at increasing slots, with boundary blocks that share their slot with
    * the main block after them and repeat the number of the block before them, as a Byron epoch
    * boundary block does.
    */
  private val chains: Gen[Vector[(BlockRef, Bytes)]] =
    Gen
      .listOf(Gen.frequency(5 -> Gen.choose(1, 12).map(Some(_)), 1 -> Gen.const(None)))
      .map { steps =>
        steps
          .foldLeft((Vector.empty[(BlockRef, Bytes)], BigInt(0), BigInt(0))) {
            case ((chain, slot, number), step) =>
              def at(s: BigInt, n: BigInt, boundary: Boolean) =
                (BlockRef(s, hash(chain.size), n, boundary), block(chain.size)._2)
              step match
                case Some(gap) =>
                  (chain :+ at(slot + gap, number, false), slot + gap, number + 1)
                case None =>
                  val boundary = at(slot + 1, (number - 1).max(0), true)
                  val main =
                    (BlockRef(slot + 1, hash(chain.size + 1), number), block(chain.size + 1)._2)
                  (chain :+ boundary :+ main, slot + 1, number + 1)
          }
          ._1
      }

  test(
    "every stored point streams the chain after it, across chunks and boundary blocks, and is found in the index"
  ) {
    val cases = for
      chunkSlots <- Gen.oneOf(4L, 10L, 30L)
      bound <- Gen.oneOf(0, 7, 40, 100_000)
      chain <- chains
    yield (chunkSlots, bound, chain)
    (0L until 30L).toList.traverse_ { seed =>
      val (chunkSlots, bound, chain) =
        cases.pureApply(Gen.Parameters.default.withSize(25), Seed(seed))
      val clue = s"seed $seed, chunkSlots $chunkSlots, bound $bound"
      tempDir.use { dir =>
        ok {
          CountedReads.open(dir, chunkSlots, bound).use { (store, log) =>
            def at(slot: BigInt, hash: Bytes) =
              new ChainSync.Point.Block(ChainSync.UInt64.saturating(slot), hash)
            for
              _ <- chain.traverse_((ref, raw) => store.append(ref, raw))
              _ <- log.set(Vector.empty)
              found <- chain.traverse((ref, _) => store.refOf(at(ref.slot, ref.hash)))
              // another block at each stored slot, and one past the newest
              others <- chain.traverse((ref, _) => store.refOf(at(ref.slot, hash(-1))))
              past <- store.refOf(at(chain.lastOption.fold(BigInt(0))(_._1.slot) + 1, hash(0)))
              finding <- log.get
              points = ChainSync.Point.Origin +: chain.map(_._1.point)
              streamed <- points.traverse(p => store.stream(p).compile.toVector)
              firsts <- points.traverse(p => store.stream(p).take(1).compile.toVector)
              gets <- chain.traverse((ref, _) => store.get(ref.point))
            yield
              assertEquals(found, chain.map(b => Some(b._1)), s"$clue: found")
              assertEquals(others.flatten, Vector.empty[BlockRef], s"$clue: others")
              assertEquals(past, None, s"$clue: past the newest")
              assertEquals(
                finding.filterNot(r => r.path.extName == ".index"),
                Vector.empty[Read],
                s"$clue: read finding the points"
              )
              streamed.zipWithIndex.foreach { (blocks, i) =>
                assertEquals(blocks.map(b => (b.ref, b.raw)), chain.drop(i), s"$clue, after $i")
              }
              firsts.zipWithIndex.foreach { (blocks, i) =>
                assertEquals(blocks.map(_.ref), chain.drop(i).take(1).map(_._1), s"$clue, at $i")
              }
              assertEquals(gets, chain.map(b => Some(b._2)), clue)
          }
        }
      }
    }
  }

  test("a block whose bytes changed fails its checksum when read; the blocks before it stream") {
    // block 15 starts after blocks 10 to 14 in chunk 1
    val offset = (10 until 15).map(block(_)._2.size.toLong).sum
    tempDir.use { dir =>
      val corrupt = StorageError.Corrupt(
        chunkFile(dir, 1).toString,
        ImmutableReadError.BlockChecksum(offset)
      )
      ok {
        FileImmutableStore.open[IO](dir, ChunkSlots).use { (store, _) =>
          for
            _ <- fill(store, 0 until 35)
            _ <- files.open(chunkFile(dir, 1), Flags(Flag.Read, Flag.Write)).use { h =>
              h.write(fs2.Chunk.singleton(0x77.toByte), offset + 3L).void
            }
            before <- store.stream(block(12)._1.point).take(2).compile.toList
            after <- next(store, 15)
            got <- attempt(store.get(block(15)._1.point))
            streamed <- attempt(store.stream(block(12)._1.point).compile.toList)
          yield
            assertEquals(before.map(_.ref), List(block(13)._1, block(14)._1))
            assertEquals(after, List(block(16)._1))
            assertEquals(got, Left(corrupt))
            assertEquals(streamed.map(_.map(_.ref)), Left(corrupt))
        }
      }
    }
  }
