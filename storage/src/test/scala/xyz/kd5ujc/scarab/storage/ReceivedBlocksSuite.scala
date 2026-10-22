// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.IO
import cats.syntax.all.*
import munit.CatsEffectSuite

import StorageTestKit.*

class ReceivedBlocksSuite extends CatsEffectSuite:
  test("reads the existing volatile record format without rewriting original bodies") {
    tempDir.use { dir =>
      ok {
        val (ref, raw) = block(1)
        VolatileStore.open[IO](dir).use { case (store, _) => store.append(ref, raw) } *>
          ReceivedBlocks
            .open[IO](dir)
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
          _ <- ReceivedBlocks.open[IO](dir).use { store =>
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
          _ <- ReceivedBlocks.open[IO](dir).use { store =>
            store.get(first._1.hash).map(found => assertEquals(found, Some(first)))
          }
        yield ()
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
          _ <- ReceivedBlocks.open[IO](dir, 2).use { store =>
            original.traverse_((ref, raw) => store.put(ref, raw)) *>
              store.put(first._1, first._2)
          }
          _ <- ReceivedBlocks.open[IO](dir, 2).use { store =>
            for
              recovered <- store.entries
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

  test("collection retains the native cutoff slot and every later received block") {
    tempDir.use { dir =>
      ok {
        val original = (1 to 7).toVector.map(block(_))
        val boundary = original(3)._1.slot
        for
          _ <- ReceivedBlocks.open[IO](dir, 2).use { store =>
            original.traverse_((ref, raw) => store.put(ref, raw)) *> store.collect(boundary)
          }
          _ <- ReceivedBlocks.open[IO](dir, 2).use { store =>
            store.collect(boundary) *> store.entries.map { recovered =>
              assertEquals(recovered.toSet, original.filter(_._1.slot >= boundary).toSet)
            }
          }
        yield ()
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
        for
          _ <- ReceivedBlocks.open[IO](dir, 2).use { store =>
            Vector(before, ebb, regular, later).traverse_((ref, raw) => store.put(ref, raw)) *>
              store.collect(ebb._1.slot) *> store.entries
                .map(found => assertEquals(found.toSet, expected))
          }
          _ <- ReceivedBlocks.open[IO](dir, 2).use { store =>
            store.collect(ebb._1.slot) *> store.entries
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
          _ <- ReceivedBlocks.open[IO](dir).use { store =>
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
          _ <- ReceivedBlocks.open[IO](dir).use { store =>
            store.entries.map(found => assertEquals(found.toSet, Set(selected, fork)))
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
          _ <- ReceivedBlocks.open[IO](dir).use(_.put(corrupt._1, corrupt._2))
          _ <- ReceivedBlocks.open[IO](dir).use { store =>
            for
              _ <- store.discardRecovered(selected._1.hash)
              excluded <- store.get(selected._1.hash)
              _ <- IO(assertEquals(excluded, None))
              _ <- store.put(selected._1, selected._2)
            yield ()
          }
          _ <- ReceivedBlocks.open[IO](dir).use { store =>
            store.entries.map(found => assertEquals(found, Vector(selected)))
          }
        yield ()
      }
    }
  }
