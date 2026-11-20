// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.IO
import cats.mtl.Raise
import cats.syntax.all.*
import java.nio.file.{Files as JFiles, StandardOpenOption}
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.ledger.StorageError
import xyz.kd5ujc.scarab.network.FailsOnUncaught

import StorageTestKit.*

class ReceivedBlocksSuite extends FailsOnUncaught:
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
            .open[IO](dir)(unread, decodes)
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
          _ <- ReceivedBlocks.open[IO](dir)(unread, decodes).use { store =>
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
          _ <- ReceivedBlocks.open[IO](dir)(unread, decodes).use { store =>
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
        ReceivedBlocks.open[IO](dir)(unread, decodes).use { store =>
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
          _ <- ReceivedBlocks.open[IO](dir, 2)(unread, decodes).use { store =>
            original.traverse_((ref, raw) => store.put(ref, raw)) *>
              store.put(first._1, first._2)
          }
          _ <- ReceivedBlocks.open[IO](dir, 2)(unread, decodes).use { store =>
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
          collected <- ReceivedBlocks.open[IO](dir, 2)(unread, decodes).use { store =>
            original.traverse_((ref, raw) => store.put(ref, raw)) *> store.collect(boundary) *>
              entries(store)
          }
          reopened <- ReceivedBlocks.open[IO](dir, 2)(unread, decodes).use { store =>
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
          collected <- ReceivedBlocks.open[IO](dir, 2)(unread, decodes).use { store =>
            order.traverse_((ref, raw) => store.put(ref, raw)) *> store.collect(boundary) *>
              entries(store)
          }
          reopened <- ReceivedBlocks.open[IO](dir, 2)(unread, decodes).use(entries)
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
          before <- ReceivedBlocks.open[IO](dir, 2)(predecessors.get, decodes).use { store =>
            for
              _ <- held.traverse_((ref, raw) => store.put(ref, raw))
              _ <- store.invalidate(fork._1.hash)
              listed <- store.hashes
              links <- linked(store)
              original <- store.original(fork._1.hash)
            yield (listed, links, original)
          }
          (listed, links, original) = before
          reopened <- ReceivedBlocks.open[IO](dir, 2)(predecessors.get, decodes).use(linked)
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
          _ <- ReceivedBlocks.open[IO](dir, 1)(unread, decodes).use { store =>
            Vector(before, ebb, regular, later).traverse_((ref, raw) => store.put(ref, raw)) *>
              store.collect(ebb._1.slot) *> entries(store)
                .map(found => assertEquals(found.toSet, expected))
          }
          _ <- ReceivedBlocks.open[IO](dir, 1)(unread, decodes).use { store =>
            store.collect(ebb._1.slot) *> entries(store)
              .map(found => assertEquals(found.toSet, expected))
          }
        yield ()
      }
    }
  }

  test("a repair writes only a block the log lacks, and never a second record of a held one") {
    // block 1 is held over another body (c); restoreSelected of block 1's original s writes
    // nothing, so the log holds one record of block 1, its first, as the reference's VolatileDB
    // skips a block whose hash it holds (`putBlockImpl`, `Storage/VolatileDB/Impl.hs` 387-399,
    // ouroboros-consensus 82ecba329). Block 2, which the log lacks, is written, and a refusal of a
    // held block is lifted
    tempDir.use { dir =>
      ok {
        val selected = block(1)
        val corrupt = selected._1 -> block(100)._2
        val (fork, lacked) = (block(3), block(2))
        def recordsOfHash(h: Bytes): Int =
          logFiles(dir).flatMap(recordsOf).count {
            case VolatileRecord.Block(ref, _) => ref.hash == h
            case _                            => false
          }
        for
          repaired <- ReceivedBlocks.open[IO](dir)(unread, decodes).use { store =>
            for
              _ <- store.put(corrupt._1, corrupt._2)
              _ <- store.put(fork._1, fork._2)
              _ <- store.invalidate(fork._1.hash)
              _ <- store.restoreSelected(selected._1, selected._2)
              _ <- store.restoreSelected(lacked._1, lacked._2)
              _ <- store.restoreSelected(fork._1, fork._2)
              held <- entries(store)
            yield held
          }
          records <- IO((recordsOfHash(selected._1.hash), recordsOfHash(lacked._1.hash)))
          files <- IO(logFiles(dir).map(recordsOf(_).size))
          reopened <- ReceivedBlocks.open[IO](dir)(unread, decodes).use(entries)
        yield
          assertEquals(records, (1, 1), "one record of the held block, one of the lacked block")
          assertEquals(files, Vector(3), "three records: c, the fork and block 2")
          assertEquals(repaired.toSet, Set(corrupt, fork, lacked), "the held record is kept")
          assertEquals(reopened.toSet, Set(corrupt, fork, lacked), "and the restart reads the same")
      }
    }
  }

  test("the body cache stays within its byte bound and evicted bodies read back from the log") {
    tempDir.use { dir =>
      ok {
        val original = (1 to 40).toVector.map(block(_))
        val limit = 1000L
        ReceivedBlocks.open[IO](dir, 4, limit)(unread, decodes).use { store =>
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
            .open[IO](dir, 4, limit)(unread, decodes)
            .use(store => original.traverse_((ref, raw) => store.put(ref, raw)))
          _ <- IO(
            assert(original.map(_._2.size.toLong).sum > 4 * limit, "the log exceeds the bound")
          )
          _ <- ReceivedBlocks.open[IO](dir, 4, limit)(unread, decodes).use { store =>
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
        ReceivedBlocks.open[IO](dir, 2, 0L)(unread, decodes).use { store =>
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
        ReceivedBlocks.open[IO](dir, 512, 0L)(unread, decodes).use { store =>
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
            .open[IO](dir)(unread, decodes)
            .use(store => original.traverse_((ref, raw) => store.put(ref, raw)))
          files <- IO(logFiles(dir).map(recordsOf(_).size))
          held <- ReceivedBlocks.open[IO](dir)(unread, decodes).use(_.residency)
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
        ReceivedBlocks.open[IO](dir, 2)(unread, decodes).use { store =>
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
            .open[IO](dir, 3)(unread, decodes)
            .use(store => original.traverse_((ref, raw) => store.put(ref, raw)))
          before <- IO(logFiles(dir))
          _ <- IO(damage(before(1), 1))
          recovered <- ReceivedBlocks.open[IO](dir, 3)(unread, decodes).use(entries)
          after <- IO(logFiles(dir).map(recordsOf))
          // the repair holds, and the log is written to as before
          later = block(9)
          again <- ReceivedBlocks.open[IO](dir, 3)(unread, decodes).use { store =>
            store.put(later._1, later._2) *> entries(store)
          }
          reopened <- ReceivedBlocks.open[IO](dir, 3)(unread, decodes).use(entries)
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
            .open[IO](dir, 2)(unread, decodes)
            .use(store => Vector(b1, b2, b3).traverse_((ref, raw) => store.put(ref, raw)))
          // the log's own appends, three to a file, write the copy into the second file
          _ <- VolatileStore
            .open[IO](dir, 3)
            .use((log, _) => log.append(b1._1, b1._2) *> log.append(b4._1, b4._2))
          _ <- ReceivedBlocks
            .open[IO](dir, 2)(unread, decodes)
            .use(store => Vector(b5, b6).traverse_((ref, raw) => store.put(ref, raw)))
          recovered <- ReceivedBlocks.open[IO](dir, 2)(unread, decodes).use(entries)
          files <- IO(logFiles(dir).map(recordsOf))
        yield
          assertEquals(recovered.toSet, Set(b1, b2, b3, b5, b6), "block 4 is lost with the copy")
          assertEquals(files.size, 4, "no file is removed")
          assertEquals(files(1), Vector(VolatileRecord.Block(b3._1, b3._2)), "cut at the copy")
          assertEquals(files(2).size, 2, "the file after it is whole")
      }
    }
  }

  test("a later record of an indexed block cuts its file there, whatever its bytes") {
    // two blocks to a file: [A 2] [A' B] [] where A' holds A's reference over another body. The
    // reference cuts the later file at A' without comparing bytes (`addToReverseIndex`,
    // `DuplicatedBlock`, `Storage/VolatileDB/Impl/State.hs` 464-492, ouroboros-consensus 82ecba329):
    // A is held as first written, and B after A' is lost
    tempDir.use { dir =>
      ok {
        val a = block(1)
        val other = a._1 -> block(100)._2
        val (two, b) = (block(2), block(3))
        for
          _ <- ReceivedBlocks
            .open[IO](dir, 2)(unread, decodes)
            .use(store => Vector(a, two).traverse_((ref, raw) => store.put(ref, raw)))
          _ <- VolatileStore
            .open[IO](dir, 2)
            .use((log, _) => log.append(other._1, other._2) *> log.append(b._1, b._2))
          recovered <- ReceivedBlocks.open[IO](dir, 2)(unread, decodes).use(entries)
          files <- IO(logFiles(dir).map(recordsOf))
          // a restart: the cut holds, and the log is written to as before
          again <- ReceivedBlocks.open[IO](dir, 2)(unread, decodes).use { store =>
            store.put(b._1, b._2) *> entries(store)
          }
          reopened <- ReceivedBlocks.open[IO](dir, 2)(unread, decodes).use(entries)
        yield
          assertEquals(recovered.toSet, Set(a, two), "A as first written, and B lost with A'")
          assertEquals(files.take(2).map(_.size), Vector(2, 0), "the later file cut at A'")
          assertEquals(again.toSet, Set(a, two, b), "B is received again")
          assertEquals(reopened.toSet, Set(a, two, b), "and the restart reads the same")
      }
    }
  }

  test("a repeat in the same file or a later one cuts its file, byte for byte or not") {
    // three blocks to a file: [A B A'] [C A D] [] where A' holds A's reference over another body.
    // The first file is cut at A', a repeat in the same file with other bytes; the second at A, a
    // repeat in a later file byte for byte; D after it is lost (`addToReverseIndex`,
    // `Storage/VolatileDB/Impl/State.hs` 464-492, ouroboros-consensus 82ecba329)
    tempDir.use { dir =>
      ok {
        val a = block(1)
        val other = a._1 -> block(100)._2
        val (b, c, d) = (block(2), block(3), block(4))
        for
          _ <- VolatileStore
            .open[IO](dir, 3)
            .use((log, _) => Vector(a, b, other, c, a, d).traverse_((r, raw) => log.append(r, raw)))
          recovered <- ReceivedBlocks.open[IO](dir, 3)(unread, decodes).use(entries)
          files <- IO(logFiles(dir).map(recordsOf))
          reopened <- ReceivedBlocks.open[IO](dir, 3)(unread, decodes).use(entries)
          after <- IO(logFiles(dir).map(recordsOf))
        yield
          assertEquals(recovered.toSet, Set(a, b, c), "A as first written; D is lost")
          assertEquals(
            files.take(2),
            Vector(
              Vector(VolatileRecord.Block(a._1, a._2), VolatileRecord.Block(b._1, b._2)),
              Vector(VolatileRecord.Block(c._1, c._2))
            ),
            "each file cut at its repeat"
          )
          assertEquals(reopened.toSet, Set(a, b, c), "a restart reads the same")
          assertEquals(after, files, "and cuts nothing more")
      }
    }
  }

  test("a record whose length no record can have cuts its file there, after a repeat too") {
    // two blocks to a file: [A A X] [C], X a record header whose length is the largest an Int
    // holds, in a file made long enough to hold it (sparse). The open reads past the repeat of A,
    // as the reference parses a whole file before it looks for repeats, stops at X without
    // allocating it, cuts the first file after A and reads the second
    tempDir.use { dir =>
      ok {
        val (a, c) = (block(1), block(3))
        for
          _ <- VolatileStore
            .open[IO](dir, 2)
            .use((log, _) => Vector(a, a, c).traverse_((r, raw) => log.append(r, raw)))
          _ <- IO {
            val first = logFiles(dir).head
            val header = java.nio.ByteBuffer.allocate(VolatileStore.HeaderSize)
            header.putInt(0).put(1.toByte).put(0.toByte).putInt(Int.MaxValue)
            JFiles.write(first, header.array(), StandardOpenOption.APPEND)
            val written = JFiles.size(first)
            scala.util.Using.resource(new java.io.RandomAccessFile(first.toFile, "rw"))(
              _.setLength(written + Int.MaxValue.toLong)
            )
          }
          recovered <- ReceivedBlocks.open[IO](dir, 2)(unread, decodes).use(entries)
          files <- IO(logFiles(dir).map(recordsOf))
          first <- IO(JFiles.size(logFiles(dir).head))
        yield
          assertEquals(recovered.toSet, Set(a, c), "A, and C in the second file")
          assertEquals(files.head, Vector(VolatileRecord.Block(a._1, a._2)), "cut after A")
          assertEquals(first, VolatileStore.encode(VolatileRecord.Block(a._1, a._2)).length.toLong)
      }
    }
  }

  test("a record of the largest length a header can give, that fails its checksum, is not read") {
    // two blocks to a file: [A B X] [C], X a block record's header whose length is the largest the
    // open takes, in a file made long enough to hold it (sparse). Its checksum is checked a chunk at
    // a time and fails: the first file is cut after B and the second is read
    tempDir.use { dir =>
      ok {
        val (a, b, c) = (block(1), block(2), block(3))
        val kept =
          Vector(a, b).map((ref, raw) => VolatileStore.encode(VolatileRecord.Block(ref, raw)))
        for
          _ <- VolatileStore
            .open[IO](dir, 2)
            .use((log, _) => Vector(a, b, c).traverse_((r, raw) => log.append(r, raw)))
          _ <- IO {
            val first = logFiles(dir).head
            val length = Int.MaxValue - VolatileStore.HeaderSize
            val header = java.nio.ByteBuffer.allocate(VolatileStore.HeaderSize)
            header.putInt(0).put(1.toByte).put(0.toByte).putInt(length)
            JFiles.write(first, header.array(), StandardOpenOption.APPEND)
            val written = JFiles.size(first)
            scala.util.Using.resource(new java.io.RandomAccessFile(first.toFile, "rw"))(
              _.setLength(written + length.toLong)
            )
          }
          recovered <- ReceivedBlocks.open[IO](dir, 2)(unread, decodes).use(entries)
          first <- IO(JFiles.size(logFiles(dir).head))
          _ <- IO(assertEquals(first, kept.map(_.length.toLong).sum, "the first file cut after B"))
          files <- IO(logFiles(dir).map(recordsOf))
        yield
          assertEquals(recovered.toSet, Set(a, b, c), "A and B, and C in the second file")
          assertEquals(files.head.size, 2, "A and B are what the first file holds")
      }
    }
  }

  test("a record longer than a checksum chunk reads back, and so do the records after it") {
    // a body of 1.5 MiB, its checksum checked in two chunks before it is read: a positive check
    // that the chunked checksum accepts a sound record; reading the record whole would pass it too
    tempDir.use { dir =>
      ok {
        val large = block(1)._1 -> Bytes(Vector.tabulate(3 << 19)(i => (i * 7).toByte))
        val after = block(2)
        for
          _ <- ReceivedBlocks
            .open[IO](dir, 1000, 0L)(unread, decodes)
            .use(store => store.put(large._1, large._2) *> store.put(after._1, after._2))
          recovered <- ReceivedBlocks.open[IO](dir, 1000, 0L)(unread, decodes).use(entries)
        yield assertEquals(recovered.toSet, Set(large, after), "both blocks read back")
      }
    }
  }

  test("each cut the open makes is reported, where and why, and the next open reports none") {
    // three records to a file: [1 2 3] [4 U 6] [7 1 8] [9 ~]: U holds block 5's reference over a
    // body that does not decode, the second file holds a repeat of block 1, and the last file ends
    // in bytes that are not a record. Each cut is reported as the reference traces `Truncate`, with
    // its file, offset and cause (`ParseError`: `BlockReadErr`, `DuplicatedBlock`;
    // `Storage/VolatileDB/Impl/Types.hs` 77-99, ouroboros-consensus 82ecba329)
    tempDir.use { dir =>
      ok {
        val bs = (1 to 9).toVector.map(block(_))
        val undecodableFive = bs(4)._1 -> undecodable
        val written =
          Vector(bs(0), bs(1), bs(2), bs(3), undecodableFive, bs(5), bs(6), bs(0), bs(7), bs(8))
        def size(b: (BlockRef, Bytes)): Long =
          VolatileStore.encode(VolatileRecord.Block(b._1, b._2)).length.toLong
        for
          _ <- VolatileStore
            .open[IO](dir, 3)
            .use((log, _) => written.traverse_((ref, raw) => log.append(ref, raw)))
          _ <- IO(
            JFiles.write(logFiles(dir)(3), Array.fill[Byte](10)(7), StandardOpenOption.APPEND)
          )
          first <- ReceivedBlocks
            .recovering[IO](dir, 3)(unread, decodes, _ => IO.unit)
            .use((_, r) => IO.pure(r))
          again <- ReceivedBlocks
            .recovering[IO](dir, 3)(unread, decodes, _ => IO.unit)
            .use((_, r) => IO.pure(r))
        yield
          assertEquals(
            first.cuts,
            Vector(
              VolatileStore.Cut(
                "00000001.log",
                size(bs(3)),
                VolatileStore.CutCause.ReadFailed(
                  VolatileStore.ReadFailure.Undecodable(
                    StorageError.Corrupt("synthetic block", Undecodable.Body)
                  )
                )
              ),
              VolatileStore.Cut(
                "00000002.log",
                size(bs(6)),
                VolatileStore.CutCause.Duplicated(bs(0)._1.hash, "00000000.log", "00000002.log")
              ),
              VolatileStore.Cut(
                "00000003.log",
                size(bs(8)),
                VolatileStore.CutCause.ReadFailed(VolatileStore.ReadFailure.Unreadable)
              )
            ),
            "one cut per file, at its offset, with its cause"
          )
          assertEquals(first.report.blocks, 6, "blocks 1, 2, 3, 4, 7 and 9")
          assertEquals(again.cuts, Vector.empty, "the next open cuts nothing")
          assertEquals(again.report.truncatedBytes, 0L, "and reports no bytes cut")
      }
    }
  }

  test("a file with a repeat before a refused block is cut and reported twice, as the reference") {
    // two files: [A] [B A U C], U a body that does not decode. The reference parses the second file
    // up to U and truncates there, then finds the repeat of A and truncates again, tracing both
    // (`validateFile`, `Storage/VolatileDB/Impl/State.hs` 412-424, ouroboros-consensus 82ecba329):
    // the cuts are told in that order, each just before it is made, and the file keeps B
    tempDir.use { dir =>
      ok {
        val (a, b, c, u) = (block(1), block(2), block(4), block(3)._1 -> undecodable)
        def size(x: (BlockRef, Bytes)): Long =
          VolatileStore.encode(VolatileRecord.Block(x._1, x._2)).length.toLong
        for
          _ <- VolatileStore
            .open[IO](dir, 1)
            .use((log, _) => log.append(a._1, a._2))
          _ <- IO {
            val second = dir.toNioPath.resolve("00000001.log")
            val records =
              Vector(b, a, u, c).map((r, raw) => VolatileStore.encode(VolatileRecord.Block(r, raw)))
            JFiles.write(second, records.flatten.toArray)
          }
          told <- cats.effect.Ref.of[IO, Vector[(VolatileStore.Cut, Long)]](Vector.empty)
          second = dir.toNioPath.resolve("00000001.log")
          whole <- IO(JFiles.size(second))
          recovery <- ReceivedBlocks
            .recovering[IO](dir, 4)(
              unread,
              decodes,
              {
                case VolatileStore.Trace.Truncated(c) =>
                  IO(JFiles.size(second)).flatMap(length => told.update(_ :+ (c -> length)))
                case _ => IO.unit
              }
            )
            .use((_, r) => IO.pure(r))
          toldWith <- told.get
          cuts = toldWith.map(_._1)
          files <- IO(logFiles(dir).map(recordsOf))
        yield
          val expected = Vector(
            VolatileStore.Cut(
              "00000001.log",
              size(b) + size(a),
              VolatileStore.CutCause.ReadFailed(
                VolatileStore.ReadFailure.Undecodable(
                  StorageError.Corrupt("synthetic block", Undecodable.Body)
                )
              )
            ),
            VolatileStore.Cut(
              "00000001.log",
              size(b),
              VolatileStore.CutCause.Duplicated(a._1.hash, "00000000.log", "00000001.log")
            )
          )
          assertEquals(cuts, expected, "both cuts told, the parse's first")
          assertEquals(
            toldWith.map(_._2),
            Vector(whole, size(b) + size(a)),
            "each told before its truncation: the whole file, then the file cut at U"
          )
          assertEquals(recovery.cuts, expected, "and the open's report holds the same")
          assertEquals(files(1), Vector(VolatileRecord.Block(b._1, b._2)), "the file keeps B")
          assertEquals(recovery.report.truncatedBytes, size(a) + size(u) + size(c), "bytes cut")
      }
    }
  }

  test("a cut is told before it is made, and before a later file fails to open") {
    // [1 ~] [2]: the first file ends in bytes that are not a record. When its cut is told, the second
    // file, already listed, is removed: the open fails on it after the first cut was told
    tempDir.use { dir =>
      val second = dir.toNioPath.resolve("00000001.log")
      for
        told <- cats.effect.Ref.of[IO, Vector[VolatileStore.Cut]](Vector.empty)
        result <- attempt {
          for
            _ <- VolatileStore
              .open[IO](dir, 1)
              .use((log, _) => Vector(block(1), block(2)).traverse_((r, raw) => log.append(r, raw)))
            _ <- IO(
              JFiles.write(logFiles(dir).head, Array.fill[Byte](10)(7), StandardOpenOption.APPEND)
            )
            _ <- ReceivedBlocks
              .recovering[IO](dir, 1)(
                unread,
                decodes,
                {
                  case VolatileStore.Trace.Truncated(c) =>
                    told.update(_ :+ c) *> IO.blocking(JFiles.deleteIfExists(second)).void
                  case _ => IO.unit
                }
              )
              .use_
          yield ()
        }
        cuts <- told.get
      yield
        result match
          case Left(StorageError.Io(what, _: java.nio.file.NoSuchFileException)) =>
            assertEquals(what, second.toString, "the open fails on the second file")
          case other => fail(s"the open fails on the removed file, got $other")
        assertEquals(
          cuts,
          Vector(
            VolatileStore.Cut(
              "00000000.log",
              VolatileStore.encode(VolatileRecord.Block(block(1)._1, block(1)._2)).length.toLong,
              VolatileStore.CutCause.ReadFailed(VolatileStore.ReadFailure.Unreadable)
            )
          ),
          "the first file's cut was told"
        )
    }
  }

  test("a log file name that is not the one the log writes refuses the log, which is unchanged") {
    // `1.log` reads as file number 1, but the log writes `00000001.log` for it and finds a block's
    // file by that name: the open refuses the log as it is, before any file changes, as it refuses
    // a name in a locale's digits (FileNameProblem). Both logs open their files the same way
    tempDir.use { dir =>
      val a = block(1)
      def contents: Map[String, Vector[Byte]] =
        scala.util.Using.resource(JFiles.list(dir.toNioPath))(
          _.iterator.asScala
            .map(f => f.getFileName.toString -> JFiles.readAllBytes(f).toVector)
            .toMap
        )
      for
        _ <- IO {
          JFiles.write(
            dir.toNioPath.resolve("1.log"),
            VolatileStore.encode(VolatileRecord.Block(a._1, a._2))
          )
          JFiles.write(dir.toNioPath.resolve("notes.txt"), Array[Byte](1, 2, 3))
        }
        before <- IO(contents)
        received <- attempt(ReceivedBlocks.open[IO](dir, 4)(unread, decodes).use_)
        selected <- attempt(VolatileStore.open[IO](dir, 4).use_)
        after <- IO(contents)
      yield
        List(received, selected).foreach {
          case Left(StorageError.Corrupt(what, FileNameProblem.NotCanonical(name, canonical))) =>
            assertEquals(what, dir.toNioPath.toString, "the log's directory")
            assertEquals((name, canonical), ("1.log", "00000001.log"), "the name and the log's own")
          case other => fail(s"the log is refused for the name, got $other")
        }
        assertEquals(after, before, "the directory is unchanged, byte for byte")
    }
  }

  test("the names the open does not read are traced before a cut, and a held block put again") {
    // [A ~]: `notes.txt` and `x.log` are not log files: the reference traces such names
    // (`InvalidFileNames`, `mkOpenState`, `Storage/VolatileDB/Impl/State.hs` 319-339) and a block
    // put that it holds (`BlockAlreadyHere`, `putBlockImpl`, `Storage/VolatileDB/Impl.hs` 387-399,
    // ouroboros-consensus 82ecba329)
    tempDir.use { dir =>
      ok {
        val (a, c) = (block(1), block(3))
        for
          _ <- VolatileStore.open[IO](dir, 4).use((log, _) => log.append(a._1, a._2))
          _ <- IO {
            JFiles.write(dir.toNioPath.resolve("notes.txt"), Array[Byte](1))
            JFiles.write(dir.toNioPath.resolve("x.log"), Array[Byte](1))
            JFiles.write(
              dir.toNioPath.resolve("00000000.log"),
              Array.fill[Byte](10)(7),
              StandardOpenOption.APPEND
            )
          }
          traced <- cats.effect.Ref.of[IO, Vector[VolatileStore.Trace]](Vector.empty)
          _ <- ReceivedBlocks
            .recovering[IO](dir, 4)(unread, decodes, t => traced.update(_ :+ t))
            .use((store, _) =>
              store.put(a._1, a._2) *> store.put(a._1, a._2) *> store.restoreSelected(a._1, a._2) *>
                store.put(c._1, c._2)
            )
          seen <- traced.get
          left <- IO(JFiles.exists(dir.toNioPath.resolve("x.log")))
        yield
          assertEquals(
            seen,
            Vector(
              VolatileStore.Trace.InvalidFileNames(Vector("notes.txt", "x.log")),
              VolatileStore.Trace.Truncated(
                VolatileStore.Cut(
                  "00000000.log",
                  VolatileStore.encode(VolatileRecord.Block(a._1, a._2)).length.toLong,
                  VolatileStore.CutCause.ReadFailed(VolatileStore.ReadFailure.Unreadable)
                )
              ),
              VolatileStore.Trace.BlockAlreadyHere(a._1.hash),
              VolatileStore.Trace.BlockAlreadyHere(a._1.hash),
              VolatileStore.Trace.BlockAlreadyHere(a._1.hash)
            ),
            "the names first, then the cut, then each put of the held block"
          )
          assert(left, "a file the open does not read is left alone")
      }
    }
  }

  test("a record whose block decodes and is corrupt cuts its file there, as BlockCorruptedErr") {
    tempDir.use { dir =>
      ok {
        val (a, b) = (block(1), block(2))
        val bad = block(3)._1 -> corrupt
        for
          _ <- VolatileStore
            .open[IO](dir, 4)
            .use((log, _) => Vector(a, bad, b).traverse_((r, raw) => log.append(r, raw)))
          recovery <- ReceivedBlocks
            .recovering[IO](dir, 4)(unread, decodes, _ => IO.unit)
            .use((_, r) => IO.pure(r))
        yield assertEquals(
          recovery.cuts,
          Vector(
            VolatileStore.Cut(
              "00000000.log",
              VolatileStore.encode(VolatileRecord.Block(a._1, a._2)).length.toLong,
              VolatileStore.CutCause.Corrupted(bad._1.hash)
            )
          ),
          "cut at the corrupt block, which is named by its hash"
        )
      }
    }
  }

  test("the scan and the repeat walk of a file of many records run on a small stack") {
    // STK-3: 20,000 records in one file, the last a repeat of the first, parsed and indexed on a
    // thread of 256 KiB of stack
    tempDir.use { dir =>
      val records = (1 to 20000).toVector.map(block(_, 1))
      val path = dir.toNioPath.resolve("00000000.log")
      IO.blocking {
        JFiles.write(
          path,
          (records :+ records.head)
            .flatMap((r, raw) => VolatileStore.encode(VolatileRecord.Block(r, raw)))
            .toArray
        )
      } *> IO
        .blocking {
          xyz.kd5ujc.scarab.ledger.PreprodDeepTx.onSmallStack {
            scala.util.Using.resource(java.nio.channels.FileChannel.open(path)) { ch =>
              val (parsed, _, stopped) =
                VolatileStore.parsedBlocks(ch, 0)((_, _) => Right[VolatileStore.Refusal, Unit](()))
              val (index, accepted, repeated) = VolatileStore.addedToIndex(Map.empty, parsed)
              (parsed.size, stopped, index.size, accepted.size, repeated.map(_._2))
            }
          }
        }
        .map((parsed, stopped, indexed, accepted, repeated) =>
          assertEquals(parsed, 20001, "every record parsed")
          assertEquals(stopped, None, "to the end of the file")
          assertEquals((indexed, accepted), (20000, 20000), "every block indexed once")
          assertEquals(repeated, Some(records.head._1.hash), "and the repeat found")
        )
    }
  }

  test("a record whose block does not decode cuts its file there, and later files are kept") {
    // three blocks to a file: [1 2 3] [4 U 6] [7 8] where U holds block 5's reference over a body
    // that does not decode. The second file is cut after block 4, as the reference cuts a file at
    // the first block it cannot read (`BlockReadErr`, `parseBlockFile`,
    // `Storage/VolatileDB/Impl/Parser.hs` 70-107, ouroboros-consensus 82ecba329); the third is read
    // all the same
    tempDir.use { dir =>
      ok {
        val original = (1 to 8).toVector.map(block(_))
        val written = original.updated(4, original(4)._1 -> undecodable)
        val kept = original.filterNot(b => b == original(4) || b == original(5))
        for
          _ <- VolatileStore
            .open[IO](dir, 3)
            .use((log, _) => written.traverse_((ref, raw) => log.append(ref, raw)))
          recovered <- ReceivedBlocks.open[IO](dir, 3)(unread, decodes).use(entries)
          files <- IO(logFiles(dir).map(recordsOf))
          // a restart: block 5 is received again, after the cut
          again <- ReceivedBlocks.open[IO](dir, 3)(unread, decodes).use { store =>
            store.put(original(4)._1, original(4)._2) *> entries(store)
          }
          reopened <- ReceivedBlocks.open[IO](dir, 3)(unread, decodes).use(entries)
        yield
          assertEquals(recovered.toSet, kept.toSet, "every block but U's and the one after it")
          assertEquals(
            files(1),
            Vector(VolatileRecord.Block(original(3)._1, original(3)._2)),
            "the second file is cut after block 4"
          )
          assertEquals(files(2).size, 2, "the third file is whole")
          assertEquals(again.toSet, kept.toSet + original(4), "block 5 is received again")
          assertEquals(reopened.toSet, kept.toSet + original(4), "and the restart reads the same")
      }
    }
  }
