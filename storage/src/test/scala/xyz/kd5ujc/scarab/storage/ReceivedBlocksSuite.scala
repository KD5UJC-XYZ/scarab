// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.IO
import cats.mtl.Raise
import cats.syntax.all.*
import java.nio.file.{Files as JFiles, StandardOpenOption}
import munit.CatsEffectSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.ledger.StorageError

import StorageTestKit.*

class ReceivedBlocksSuite extends CatsEffectSuite:
  /** For a test that reads no predecessor: no header is read. */
  private val unread: Bytes => Option[ReceivedBlocks.Predecessor] = _ => None

  /** The log's files, in order of number. */
  private def logFiles(dir: fs2.io.file.Path): Vector[java.nio.file.Path] =
    scala.util.Using.resource(JFiles.list(dir.toNioPath))(
      _.iterator.asScala.filter(_.toString.endsWith(".log")).toVector.sortBy(_.toString)
    )

  /** The records of a log file. */
  private def recordsOf(file: java.nio.file.Path): Vector[VolatileRecord] =
    VolatileStore.decodeAll(JFiles.readAllBytes(file)).map(_._1)

  /** Flip a byte inside the body of the record at `index` of `file`. */
  private def damage(file: java.nio.file.Path, index: Int): Unit =
    val bytes = JFiles.readAllBytes(file)
    val start = if index == 0 then 0 else VolatileStore.decodeAll(bytes)(index - 1)._2
    val at = start + VolatileStore.HeaderSize + 1
    bytes(at) = (bytes(at) ^ 0x55).toByte
    JFiles.write(file, bytes)

  /** Every block held, each read through `get` (from the cache or the log). */
  private def entries(store: ReceivedBlocks[IO])(using
      Raise[IO, StorageError]
  ): IO[Vector[(BlockRef, Bytes)]] =
    store.refs.flatMap(_.traverse(ref => store.get(ref.hash))).map(_.flatten)

  test("reads the existing volatile record format without rewriting original bodies") {
    tempDir.use { dir =>
      ok {
        val (ref, raw) = block(1)
        VolatileStore.open[IO](dir).use { case (store, _) => store.append(ref, raw) } *>
          ReceivedBlocks
            .open[IO](dir)(unread)
            .use(_.get(ref.hash).map(found => assertEquals(found, Some(ref -> raw))))
      }
    }
  }

  test("a refused recovered body is suppressed without discarding other received forks") {
    tempDir.use { dir =>
      ok {
        val first = block(1)
        val fork = first._1.copy(hash = hash(100)) -> block(100)._2
        for
          _ <- ReceivedBlocks.open[IO](dir)(unread).use { store =>
            for
              _ <- store.put(first._1, first._2)
              _ <- store.put(fork._1, fork._2)
              _ <- store.invalidate(first._1.hash)
              refused <- store.get(first._1.hash)
              kept <- store.get(fork._1.hash)
              readable <- store.hashes
              _ <- IO(assertEquals(refused, None))
              _ <- IO(assertEquals(kept, Some(fork)))
              _ <- IO(assertEquals(readable, Set(fork._1.hash)))
            yield ()
          }
          // Restart clears process-local validation verdicts; originals must be revalidated.
          _ <- ReceivedBlocks.open[IO](dir)(unread).use { store =>
            store.get(first._1.hash).map(found => assertEquals(found, Some(first)))
          }
        yield ()
      }
    }
  }

  test("holds answers what hashes lists, from the state it read") {
    tempDir.use { dir =>
      ok {
        val first = block(1)
        val fork = first._1.copy(hash = hash(100)) -> block(100)._2
        val later = block(2)
        ReceivedBlocks.open[IO](dir)(unread).use { store =>
          for
            _ <- store.put(first._1, first._2)
            _ <- store.put(fork._1, fork._2)
            _ <- store.invalidate(first._1.hash)
            listed <- store.hashes
            held <- store.holds
            _ <- store.put(later._1, later._2)
            fresh <- store.holds
            asked = Vector(first._1.hash, fork._1.hash, later._1.hash, hash(200))
            _ <- IO(
              assertEquals(
                asked.filter(held),
                asked.filter(listed),
                "the read before the put answers as hashes did"
              )
            )
            _ <- IO(
              assertEquals(asked.filter(held), Vector(fork._1.hash), "refused and later are out")
            )
            _ <- IO(
              assertEquals(
                asked.filter(fresh),
                Vector(fork._1.hash, later._1.hash),
                "a new read sees the put"
              )
            )
          yield ()
        }
      }
    }
  }

  test("restart retains both received forks independently of selection") {
    tempDir.use { dir =>
      ok {
        val first = block(1)
        val fork = first._1.copy(hash = hash(100)) -> block(100)._2
        val next = block(2)
        val original = Vector(first, fork, next)
        for
          _ <- ReceivedBlocks.open[IO](dir, 2)(unread).use { store =>
            original.traverse_((ref, raw) => store.put(ref, raw)) *>
              store.put(first._1, first._2)
          }
          _ <- ReceivedBlocks.open[IO](dir, 2)(unread).use { store =>
            for
              recovered <- entries(store)
              _ <- IO(assertEquals(recovered.toSet, original.toSet))
              _ <- original.traverse_ { case (ref, raw) =>
                store.get(ref.hash).map(found => assertEquals(found, Some(ref -> raw)))
              }
            yield ()
          }
        yield ()
      }
    }
  }

  test("collection deletes the files wholly below the cutoff, and only their blocks leave") {
    // two blocks to a file: [1 2] [3 4] [5 6] [7]. The cutoff is block 4's slot: the first file is
    // wholly below it and goes; block 3 is below it but in a file that is kept, and stays held, as
    // the reference's VolatileDB removes the index entries of the files it deletes and no other
    tempDir.use { dir =>
      ok {
        val original = (1 to 7).toVector.map(block(_))
        val boundary = original(3)._1.slot
        val kept = original.drop(2).toSet
        for
          collected <- ReceivedBlocks.open[IO](dir, 2)(unread).use { store =>
            original.traverse_((ref, raw) => store.put(ref, raw)) *> store.collect(boundary) *>
              entries(store)
          }
          reopened <- ReceivedBlocks.open[IO](dir, 2)(unread).use { store =>
            store.collect(boundary) *> entries(store)
          }
        yield
          assertEquals(collected.toSet, kept, "the index after collection")
          assertEquals(reopened.toSet, kept, "the index the files give after a restart")
      }
    }
  }

  test("collection takes a file wholly below the cutoff wherever it is in the log") {
    // two blocks to a file: [1 9] [2 3] [10 11] [12]. Below block 5's slot are blocks 1, 2 and 3;
    // only the second file holds nothing else, and it goes though an older file is kept, as the
    // reference's VolatileDB takes any file but the current one whose newest slot is below the
    // cutoff (`getFilesToGC`)
    tempDir.use { dir =>
      ok {
        val order = Vector(1, 9, 2, 3, 10, 11, 12).map(block(_))
        val boundary = block(5)._1.slot
        val kept = Vector(1, 9, 10, 11, 12).map(block(_)).toSet
        for
          collected <- ReceivedBlocks.open[IO](dir, 2)(unread).use { store =>
            order.traverse_((ref, raw) => store.put(ref, raw)) *> store.collect(boundary) *>
              entries(store)
          }
          reopened <- ReceivedBlocks.open[IO](dir, 2)(unread).use(entries)
        yield
          assertEquals(collected.toSet, kept, "the index after collection")
          assertEquals(reopened.toSet, kept, "the index the files give after a restart")
      }
    }
  }

  test("links answer every held block with its predecessor, held invalid too, and after reopen") {
    tempDir.use { dir =>
      ok {
        val first = block(1)
        val second = block(2)
        val fork = block(3)._1.copy(hash = hash(300)) -> block(300)._2
        val unreadable = block(4)
        val predecessors: Map[Bytes, ReceivedBlocks.Predecessor] = Map(
          first._2 -> ReceivedBlocks.Predecessor.Genesis,
          second._2 -> ReceivedBlocks.Predecessor.Block(first._1.hash),
          fork._2 -> ReceivedBlocks.Predecessor.Block(first._1.hash)
        )
        val held = Vector(first, second, fork, unreadable)
        def linked(store: ReceivedBlocks[IO]) =
          store.links.map(lookup => held.map((ref, _) => lookup(ref.hash)))
        val expected = Vector(
          Some(ReceivedBlocks.Linked(first._1, ReceivedBlocks.Predecessor.Genesis)),
          Some(ReceivedBlocks.Linked(second._1, ReceivedBlocks.Predecessor.Block(first._1.hash))),
          Some(ReceivedBlocks.Linked(fork._1, ReceivedBlocks.Predecessor.Block(first._1.hash))),
          None
        )
        for
          before <- ReceivedBlocks.open[IO](dir, 2)(predecessors.get).use { store =>
            for
              _ <- held.traverse_((ref, raw) => store.put(ref, raw))
              _ <- store.invalidate(fork._1.hash)
              listed <- store.hashes
              links <- linked(store)
              original <- store.original(fork._1.hash)
            yield (listed, links, original)
          }
          (listed, links, original) = before
          reopened <- ReceivedBlocks.open[IO](dir, 2)(predecessors.get).use(linked)
        yield
          assert(!listed.contains(fork._1.hash), "the refused block is not listed")
          assertEquals(links, expected, "held blocks, the refused one too")
          assertEquals(original, Some(fork), "the refused block's original reads back")
          assertEquals(reopened, expected, "the predecessors read again from the records")
      }
    }
  }

  test("same-slot Byron boundary and regular originals survive collection and reopen") {
    tempDir.use { dir =>
      ok {
        val before = block(1)
        val boundary = block(2)
        val ebb = boundary._1.copy(boundary = true) -> boundary._2
        val regular =
          boundary._1.copy(hash = hash(100), blockNo = boundary._1.blockNo + 1) -> block(100)._2
        val later = block(3)
        val expected = Vector(ebb, regular, later).toSet
        // one block to a file: the first file is wholly below the cutoff, the boundary block's is not
        for
          _ <- ReceivedBlocks.open[IO](dir, 1)(unread).use { store =>
            Vector(before, ebb, regular, later).traverse_((ref, raw) => store.put(ref, raw)) *>
              store.collect(ebb._1.slot) *> entries(store)
                .map(found => assertEquals(found.toSet, expected))
          }
          _ <- ReceivedBlocks.open[IO](dir, 1)(unread).use { store =>
            store.collect(ebb._1.slot) *> entries(store)
              .map(found => assertEquals(found.toSet, expected))
          }
        yield ()
      }
    }
  }

  test("validated selected originals repair an inconsistent cache across reopen") {
    tempDir.use { dir =>
      ok {
        val selected = block(1)
        val corrupt = selected._1 -> block(100)._2
        val fork = block(2)
        for
          _ <- ReceivedBlocks.open[IO](dir)(unread).use { store =>
            for
              _ <- store.put(corrupt._1, corrupt._2)
              _ <- store.put(fork._1, fork._2)
              _ <- store.put(selected._1, selected._2)
              firstReceipt <- store.get(selected._1.hash)
              _ <- IO(assertEquals(firstReceipt, Some(corrupt)))
              _ <- store.restoreSelected(selected._1, selected._2)
              repaired <- store.get(selected._1.hash)
              _ <- IO(assertEquals(repaired, Some(selected)))
            yield ()
          }
          _ <- ReceivedBlocks.open[IO](dir)(unread).use { store =>
            entries(store).map(found => assertEquals(found.toSet, Set(selected, fork)))
          }
        yield ()
      }
    }
  }

  test("discarding a malformed recovered extra permits a later valid receipt of that hash") {
    tempDir.use { dir =>
      ok {
        val selected = block(1)
        val corrupt = selected._1 -> block(100)._2
        for
          _ <- ReceivedBlocks.open[IO](dir)(unread).use(_.put(corrupt._1, corrupt._2))
          _ <- ReceivedBlocks.open[IO](dir)(unread).use { store =>
            for
              _ <- store.discardRecovered(selected._1.hash)
              excluded <- store.get(selected._1.hash)
              _ <- IO(assertEquals(excluded, None))
              _ <- store.put(selected._1, selected._2)
            yield ()
          }
          _ <- ReceivedBlocks.open[IO](dir)(unread).use { store =>
            entries(store).map(found => assertEquals(found, Vector(selected)))
          }
        yield ()
      }
    }
  }

  test("the body cache stays within its byte bound and evicted bodies read back from the log") {
    tempDir.use { dir =>
      ok {
        val original = (1 to 40).toVector.map(block(_))
        val limit = 1000L
        ReceivedBlocks.open[IO](dir, 4, limit)(unread).use { store =>
          for
            _ <- original.traverse_((ref, raw) => store.put(ref, raw))
            held <- store.residency
            _ <- IO(assert(held.cachedBytes <= limit, held.toString))
            _ <- IO(assert(held.evictions > 0, held.toString))
            _ <- IO(assertEquals(held.blocks, original.size))
            found <- original.traverse((ref, _) => store.get(ref.hash))
            _ <- IO(assertEquals(found, original.map(Some(_))))
            after <- store.residency
            _ <- IO(assertEquals(after.reads, original.size.toLong - held.cachedBlocks))
            _ <- IO(assert(after.cachedBytes <= limit, after.toString))
          yield ()
        }
      }
    }
  }

  test("opening a log larger than the cache's budget keeps no body and reads every block back") {
    tempDir.use { dir =>
      ok {
        val original = (1 to 40).toVector.map(block(_))
        val limit = 1000L
        for
          _ <- ReceivedBlocks
            .open[IO](dir, 4, limit)(unread)
            .use(store => original.traverse_((ref, raw) => store.put(ref, raw)))
          _ <- IO(
            assert(original.map(_._2.size.toLong).sum > 4 * limit, "the log exceeds the bound")
          )
          _ <- ReceivedBlocks.open[IO](dir, 4, limit)(unread).use { store =>
            for
              opened <- store.residency
              _ <- IO(assertEquals(opened.blocks, original.size))
              _ <- IO(assertEquals((opened.cachedBlocks, opened.cachedBytes), (0, 0L)))
              found <- original.traverse((ref, _) => store.get(ref.hash))
              _ <- IO(assertEquals(found, original.map(Some(_))))
              after <- store.residency
            yield
              assertEquals(after.reads, original.size.toLong)
              assertEquals(after.cachedBytes, 0L, "a body read back is not cached")
          }
        yield ()
      }
    }
  }

  test("after collection every indexed block still reads back from a kept log file") {
    tempDir.use { dir =>
      ok {
        val original = (1 to 20).toVector.map(block(_))
        val boundary = original(9)._1.slot
        ReceivedBlocks.open[IO](dir, 2, 0L)(unread).use { store =>
          for
            _ <- original.traverse_((ref, raw) => store.put(ref, raw))
            _ <- store.collect(boundary)
            kept <- entries(store)
            // two to a file: block 9 shares its file with block 10, at the cutoff, and stays
            _ <- IO(assertEquals(kept, original.drop(8)))
            held <- store.residency
            _ <- IO(assertEquals(held.unreadable, 0L))
            _ <- IO(assertEquals(held.cachedBytes, 0L))
          yield ()
        }
      }
    }
  }

  test("a record that fails its checksum on read back is dropped, not returned") {
    tempDir.use { dir =>
      ok {
        val (ref, raw) = block(1)
        val other = block(2)
        ReceivedBlocks.open[IO](dir, 512, 0L)(unread).use { store =>
          for
            _ <- store.put(ref, raw)
            _ <- store.put(other._1, other._2)
            // flip the last payload byte of the first record (header 58 bytes, then the body)
            _ <- IO.blocking {
              val log = JFiles.list(dir.toNioPath).iterator.asScala.toVector.head
              val ch = JFiles.newByteChannel(log, StandardOpenOption.READ, StandardOpenOption.WRITE)
              try
                val at = VolatileStore.HeaderSize.toLong + raw.size - 1
                val one = java.nio.ByteBuffer.allocate(1)
                ch.position(at).read(one)
                one.flip()
                val flipped = java.nio.ByteBuffer.wrap(Array((one.get() ^ 0xff).toByte))
                ch.position(at).write(flipped)
              finally ch.close()
            }
            lost <- store.get(ref.hash)
            kept <- store.get(other._1.hash)
            hashes <- store.hashes
            held <- store.residency
            _ <- IO(assertEquals(lost, None))
            _ <- IO(assertEquals(kept, Some(other)))
            _ <- IO(assertEquals(hashes, Set(other._1.hash)))
            _ <- IO(assertEquals(held.unreadable, 1L))
          yield ()
        }
      }
    }
  }

  // ---- files of 1,000 blocks, rolled when they fill, and the recovery of each file (#1247) ----

  test("a file holds 1,000 blocks, and the next one is started when it fills") {
    // the reference's volMaxBlocksPerFile (`Storage/VolatileDB/Impl.hs` 186, ouroboros-consensus
    // 82ecba329); a block that fills a file is followed at once by the next, empty file
    tempDir.use { dir =>
      ok {
        val original = (1 to 1000).toVector.map(block(_))
        for
          _ <- ReceivedBlocks
            .open[IO](dir)(unread)
            .use(store => original.traverse_((ref, raw) => store.put(ref, raw)))
          files <- IO(logFiles(dir).map(recordsOf(_).size))
          held <- ReceivedBlocks.open[IO](dir)(unread).use(_.residency)
        yield
          assertEquals(files, Vector(1000, 0), "a full file of 1,000 blocks, and an empty one")
          assertEquals(held.blocks, original.size, "every block is held after a reopen")
      }
    }
  }

  test("a file that fills is no longer the one being written, and is collected at once") {
    // two blocks to a file: [1 2] fills with block 2 and the empty file after it is the one being
    // written, so a cutoff above both blocks takes the full file before any block follows
    tempDir.use { dir =>
      ok {
        val original = Vector(block(1), block(2))
        ReceivedBlocks.open[IO](dir, 2)(unread).use { store =>
          for
            _ <- original.traverse_((ref, raw) => store.put(ref, raw))
            _ <- store.collect(block(3)._1.slot)
            held <- entries(store)
            files <- IO(logFiles(dir).size)
          yield
            assertEquals(held, Vector.empty, "the full file is collected")
            assertEquals(files, 1, "only the file being written is left")
        }
      }
    }
  }

  test("a damaged file is cut at its last good record, and the files after it are kept") {
    // three blocks to a file: [1 2 3] [4 5 6] [7 8]. A byte of block 5's body is flipped: the
    // second file is cut after block 4 and the third is read all the same, as the reference's
    // open truncates each file on its own (`mkOpenStateHelper`, `Storage/VolatileDB/Impl/State.hs`
    // 372-436, ouroboros-consensus 82ecba329)
    tempDir.use { dir =>
      ok {
        val original = (1 to 8).toVector.map(block(_))
        val kept = original.filterNot(b => b == original(4) || b == original(5))
        for
          _ <- ReceivedBlocks
            .open[IO](dir, 3)(unread)
            .use(store => original.traverse_((ref, raw) => store.put(ref, raw)))
          before <- IO(logFiles(dir))
          _ <- IO(damage(before(1), 1))
          recovered <- ReceivedBlocks.open[IO](dir, 3)(unread).use(entries)
          after <- IO(logFiles(dir).map(recordsOf))
          // the repair holds, and the log is written to as before
          later = block(9)
          again <- ReceivedBlocks.open[IO](dir, 3)(unread).use { store =>
            store.put(later._1, later._2) *> entries(store)
          }
          reopened <- ReceivedBlocks.open[IO](dir, 3)(unread).use(entries)
        yield
          assertEquals(before.size, 3, "two full files and the one being written")
          assertEquals(recovered.toSet, kept.toSet, "every block but the damaged one's and after")
          assertEquals(after.size, 3, "no file is removed")
          assertEquals(
            after(1),
            Vector(VolatileRecord.Block(original(3)._1, original(3)._2)),
            "the second file is cut after block 4"
          )
          assertEquals(after(2).size, 2, "the third file is whole")
          assertEquals(again.toSet, kept.toSet + later, "the log keeps working")
          assertEquals(reopened.toSet, kept.toSet + later, "and reopens the same")
      }
    }
  }

  test("a later file is cut at a copy of a block already indexed, and the files after it kept") {
    // two blocks to a file: [1 2] [3 1 4] [5 6] []. The copy of block 1 cuts the second file
    // there, so block 4 after it is lost, and the third file is kept, as the reference's open
    // truncates a file at a block its index already holds (`addToReverseIndex`, `DuplicatedBlock`,
    // `Storage/VolatileDB/Impl/State.hs` 464-492, ouroboros-consensus 82ecba329)
    tempDir.use { dir =>
      ok {
        val bs = (1 to 6).toVector.map(block(_))
        val (b1, b2, b3, b4, b5, b6) = (bs(0), bs(1), bs(2), bs(3), bs(4), bs(5))
        for
          _ <- ReceivedBlocks
            .open[IO](dir, 2)(unread)
            .use(store => Vector(b1, b2, b3).traverse_((ref, raw) => store.put(ref, raw)))
          // the log's own appends, three to a file, write the copy into the second file
          _ <- VolatileStore
            .open[IO](dir, 3)
            .use((log, _) => log.append(b1._1, b1._2) *> log.append(b4._1, b4._2))
          _ <- ReceivedBlocks
            .open[IO](dir, 2)(unread)
            .use(store => Vector(b5, b6).traverse_((ref, raw) => store.put(ref, raw)))
          recovered <- ReceivedBlocks.open[IO](dir, 2)(unread).use(entries)
          files <- IO(logFiles(dir).map(recordsOf))
        yield
          assertEquals(recovered.toSet, Set(b1, b2, b3, b5, b6), "block 4 is lost with the copy")
          assertEquals(files.size, 4, "no file is removed")
          assertEquals(files(1), Vector(VolatileRecord.Block(b3._1, b3._2)), "cut at the copy")
          assertEquals(files(2).size, 2, "the file after it is whole")
      }
    }
  }

  test("a replacement in a later file survives reopen, with the blocks after it") {
    // two blocks to a file: [c 2] [3 s] [4 5] []: c holds block 1's reference over another body,
    // and restoreSelected writes the selected original s two files later. Its bytes differ from
    // c's: s takes c's place in the index, and nothing after it is cut
    tempDir.use { dir =>
      ok {
        val selected = block(1)
        val corrupt = selected._1 -> block(100)._2
        val others = Vector(block(2), block(3), block(4), block(5))
        for
          _ <- ReceivedBlocks.open[IO](dir, 2)(unread).use { store =>
            for
              _ <- store.put(corrupt._1, corrupt._2)
              _ <- others.take(2).traverse_((ref, raw) => store.put(ref, raw))
              _ <- store.restoreSelected(selected._1, selected._2)
              _ <- others.drop(2).traverse_((ref, raw) => store.put(ref, raw))
            yield ()
          }
          recovered <- ReceivedBlocks.open[IO](dir, 2)(unread).use(entries)
          files <- IO(logFiles(dir).map(recordsOf(_).size))
        yield
          assertEquals(recovered.toSet, others.toSet + selected, "the selected original is held")
          assertEquals(files, Vector(2, 2, 2, 0), "no file is cut")
      }
    }
  }
