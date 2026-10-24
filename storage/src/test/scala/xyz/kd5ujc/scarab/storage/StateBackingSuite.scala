// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.IO
import java.nio.file.Files as JFiles
import munit.CatsEffectSuite
import xyz.kd5ujc.scarab.consensus.state.MirrorState
import xyz.kd5ujc.scarab.ledger.{CheckpointProblem, StorageError, UtxoKeyLayout}

import StorageTestKit.*

/** The binding of a snapshot to its UTxO checkpoint: what [[UtxoBacking.checkpoint]] records, what
  * [[UtxoBacking.admit]] accepts, and what [[UtxoBacking.retain]] deletes.
  */
class StateBackingSuite extends CatsEffectSuite:
  private val (anchor, _) = block(7)
  private val (other, _) = block(8)

  private def refused(problem: CheckpointProblem): PartialFunction[StorageError, Boolean] =
    case StorageError.UtxoCheckpoint(_, found) => found == problem

  test("a checkpoint is admitted with the binding it was taken with, also after it was restored") {
    tempDir.use { dir =>
      ok {
        RocksUtxoStore.resource[IO](dir / "live").use { store =>
          val backing = new UtxoBacking[IO, MirrorState](store, dir / "checkpoints")
          for
            _ <- store.write(Vector.empty, 1L)
            binding <- backing.checkpoint(anchor)
            first <- backing.admit(Some(anchor), binding)
            // restoring opens the checkpoint read-only, which may add RocksDB's diagnostics to it
            _ <- backing.restore(Some(anchor))
            again <- backing.admit(Some(anchor), binding)
          yield
            binding match
              case CheckpointBinding.Bound(layout, digest) =>
                assertEquals(layout, UtxoKeyLayout.current)
                assertEquals(digest.size, 32)
              case other => fail(s"expected a bound checkpoint, got $other")
            assertEquals(first, Right(()))
            assertEquals(again, Right(()))
        }
      }
    }
  }

  test(
    "another directory under the same stem, an absent one and an unbacked snapshot are refused"
  ) {
    tempDir.use { dir =>
      ok {
        RocksUtxoStore.resource[IO](dir / "live").use { store =>
          val backing = new UtxoBacking[IO, MirrorState](store, dir / "checkpoints")
          for
            _ <- store.write(Vector.empty, 1L)
            binding <- backing.checkpoint(anchor)
            _ <- store.write(Vector.empty, 2L)
            _ <- backing.checkpoint(anchor)
            replaced <- backing.admit(Some(anchor), binding)
            absent <- backing.admit(Some(other), binding)
            unbacked <- backing.admit(Some(anchor), CheckpointBinding.Unbacked)
            legacy <- backing.admit(Some(anchor), CheckpointBinding.Legacy)
            legacyAbsent <- backing.admit(Some(other), CheckpointBinding.Legacy)
          yield
            assert(
              replaced.left.exists {
                case StorageError.UtxoCheckpoint(_, CheckpointProblem.DigestMismatch(_, _)) => true
                case _                                                                      => false
              },
              replaced.toString
            )
            assert(absent.left.exists(refused(CheckpointProblem.Absent)), absent.toString)
            assert(unbacked.left.exists(refused(CheckpointProblem.Unbound)), unbacked.toString)
            assertEquals(legacy, Right(()))
            assert(legacyAbsent.left.exists(refused(CheckpointProblem.Absent)))
        }
      }
    }
  }

  test("retain deletes only unlisted checkpoint directories") {
    tempDir.use { dir =>
      ok {
        RocksUtxoStore.resource[IO](dir / "live").use { store =>
          val root = dir / "checkpoints"
          val backing = new UtxoBacking[IO, MirrorState](store, root)
          for
            _ <- backing.checkpoint(anchor)
            _ <- backing.checkpoint(other)
            _ <- IO.blocking(JFiles.createDirectories((root / "operator-copy").toNioPath))
            _ <- backing.retain(Set(StateBacking.stem(anchor)))
          yield
            assert(JFiles.isDirectory((root / StateBacking.stem(anchor)).toNioPath))
            assert(!JFiles.exists((root / StateBacking.stem(other)).toNioPath))
            assert(JFiles.isDirectory((root / "operator-copy").toNioPath))
        }
      }
    }
  }

  test("the digest ignores RocksDB diagnostics and covers every data file") {
    tempDir.use { dir =>
      IO.blocking {
        val d = dir.toNioPath
        JFiles.write(d.resolve("CURRENT"), "MANIFEST-000001\n".getBytes)
        JFiles.write(d.resolve("000007.sst"), Array.fill[Byte](16)(1))
        val digest = CheckpointDigest.of(d)
        JFiles.write(d.resolve("LOG"), "opened".getBytes)
        JFiles.write(d.resolve("LOG.old.1"), "opened".getBytes)
        JFiles.write(d.resolve("LOCK"), Array.emptyByteArray)
        assertEquals(CheckpointDigest.of(d), digest)
        JFiles.write(d.resolve("000007.sst"), Array.fill[Byte](17)(1))
        val longer = CheckpointDigest.of(d)
        assertNotEquals(longer, digest)
        JFiles.write(d.resolve("CURRENT"), "MANIFEST-000002\n".getBytes)
        assertNotEquals(CheckpointDigest.of(d), longer)
      }
    }
  }
