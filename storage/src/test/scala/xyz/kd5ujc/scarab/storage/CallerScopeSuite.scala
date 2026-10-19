// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.IO
import java.nio.file.Files
import xyz.kd5ujc.scarab.ledger.StorageError
import xyz.kd5ujc.scarab.protocol.ChainSync

/** A store is reusable from a different error scope. Opening does not own later operations. */
class CallerScopeSuite extends munit.CatsEffectSuite:
  import StorageTestKit.*

  test("memory restore uses the caller after the construction scope ended") {
    tempDir.use { dir =>
      for
        store <- ok(MemoryUtxoStore[IO])
        _ <- IO.blocking(Files.write((dir / "utxo.bin").toNioPath, Array[Byte](0)))
        first <- attempt(store.restore(dir))
        second <- attempt(store.restore(dir))
      yield
        assert(first.swap.exists(_.isInstanceOf[StorageError.Corrupt[?]]))
        assertEquals(second, first)
    }
  }

  test("immutable stream reports a missing point to the operation scope") {
    tempDir.use { dir =>
      ok {
        FileImmutableStore.open[IO](dir).use { (store, _) =>
          val unknown = ChainSync.Point.Block(ChainSync.UInt64.saturating(4), hash(999))
          attempt(store.stream(unknown).compile.toVector).map { result =>
            assert(result.swap.exists(_.isInstanceOf[StorageError.Missing]))
          }
        }
      }
    }
  }

  test("volatile collection uses the caller and still releases the live file") {
    tempDir.use { dir =>
      val root = dir / "volatile"
      for
        result <- attempt {
          VolatileStore.open[IO](root, blocksPerFile = 1).use { (store, _) =>
            for
              _ <- store.append.tupled(block(1))
              _ <- store.append.tupled(block(2))
              _ <- IO.blocking {
                val old = (root / "00000000.log").toNioPath
                Files.delete(old)
                Files.createDirectory(old)
                Files.write(old.resolve("obstruction"), Array[Byte](1))
              }
              refused <- attempt(store.gc(100))
            yield assert(refused.swap.exists(_.isInstanceOf[StorageError.Io]))
          }
        }
        // The opening scope sees no error: the nested operation scope owns the failed collection.
        _ <- IO(assertEquals(result, Right(())))
        _ <- IO.blocking(Files.delete((root / "00000001.log").toNioPath))
      yield ()
    }
  }
