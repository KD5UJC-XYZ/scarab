// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.IO
import cats.mtl.Raise
import cats.syntax.all.*
import fs2.Stream
import fs2.io.file.{Files, Path}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.ledger.StorageError
import munit.CatsEffectSuite

import NodeCodecs.blockRef
import StorageTestKit.*

/** A small state to exercise the snapshot file format without the full node state. */
final case class Toy(counter: BigInt, names: Map[Bytes, BigInt], tags: Vector[Option[Bytes]])

/** The same type after a field was added: a different schema. */
final case class ToyV2(
    counter: BigInt,
    names: Map[Bytes, BigInt],
    tags: Vector[Option[Bytes]],
    extra: Int
)

class LedgerSnapshotsSuite extends CatsEffectSuite:
  private val files = Files.forAsync[IO]

  private def toy(i: Int): Toy =
    Toy(
      BigInt(i) * 1000,
      (0 until 50 + i).map(j => Bytes(Vector(j.toByte, i.toByte)) -> BigInt(j * i)).toMap,
      Vector(None, Some(hash(i)))
    )

  private def snap(i: Int): StateSnapshot[Toy] = StateSnapshot(Some(block(i)._1), toy(i))

  private def open(dir: Path, keep: Int = 3)(using Raise[IO, StorageError]) =
    FileLedgerSnapshots.open[IO, Toy](dir, keep)

  private val anyAnchor: Option[BlockRef] => IO[Boolean] = _ => IO.pure(true)

  private def newest(dir: Path)(using Raise[IO, StorageError]): IO[String] =
    open(dir).flatMap(_.list).map(_.head)

  test("write then restore returns the newest snapshot") {
    tempDir.use { dir =>
      ok {
        for
          s <- open(dir)
          written <- (1 to 3).toList.traverse(i => s.write(snap(i)))
          _ <- IO(assert(written.forall(_.bytes > 0)))
          restored <- s.restore(anyAnchor)
          _ <- IO(assertEquals(restored.snapshot.map(_._2), Some(snap(3))))
          _ <- IO(assertEquals(restored.skipped, Vector.empty))
        yield ()
      }
    }
  }

  test("only the newest `keep` snapshots are kept") {
    tempDir.use { dir =>
      ok {
        for
          s <- open(dir, keep = 2)
          _ <- (1 to 5).toList.traverse_(i => s.write(snap(i)))
          names <- s.list
          _ <- IO(assertEquals(names.size, 2))
          restored <- s.restore(anyAnchor)
          _ <- IO(assertEquals(restored.snapshot.map(_._2.state.counter), Some(BigInt(5000))))
        yield ()
      }
    }
  }

  test("a snapshot without usable anchor is skipped for an older one") {
    tempDir.use { dir =>
      ok {
        for
          s <- open(dir)
          _ <- (1 to 3).toList.traverse_(i => s.write(snap(i)))
          restored <- s.restore(anchor => IO.pure(anchor.exists(_.blockNo < 3)))
          _ <- IO(assertEquals(restored.snapshot.map(_._2.state.counter), Some(BigInt(2000))))
          _ <- IO(assertEquals(restored.skipped.size, 1))
        yield ()
      }
    }
  }

  test("no snapshots restores nothing; a genesis snapshot is never written") {
    tempDir.use { dir =>
      for
        empty <- ok(open(dir).flatMap(_.restore(anyAnchor)))
        genesis <- attempt(open(dir).flatMap(_.write(StateSnapshot(None, toy(0)))))
      yield
        assertEquals(empty.snapshot, None)
        assert(genesis.isLeft)
    }
  }

  private def corrupt(path: Path, damage: Array[Byte] => Array[Byte]): IO[Unit] =
    files
      .readAll(path)
      .compile
      .to(Array)
      .flatMap(b => Stream.emits(damage(b).toSeq).through(files.writeAll(path)).compile.drain)

  test("recovery falls back to an earlier snapshot when the newest is damaged") {
    val damages: List[(String, Array[Byte] => Array[Byte])] = List(
      "truncated in the payload" -> (b => b.take(b.length / 2)),
      "truncated checksum" -> (b => b.take(b.length - 5)),
      "empty file" -> (_ => Array.empty[Byte]),
      "flipped payload byte" -> (b => b.updated(b.length / 2, (b(b.length / 2) ^ 0x40).toByte)),
      "flipped checksum byte" -> (b => b.updated(b.length - 1, (b.last ^ 1).toByte)),
      "bad magic" -> (b => b.updated(0, 'X'.toByte)),
      "trailing garbage" -> (b => b ++ Array[Byte](1, 2, 3))
    )
    damages.traverse_ { (label, damage) =>
      tempDir.use { dir =>
        ok {
          for
            s <- open(dir)
            _ <- (1 to 3).toList.traverse_(i => s.write(snap(i)))
            name <- newest(dir)
            _ <- corrupt(dir / name, damage)
            restored <- s.restore(anyAnchor)
            _ <- IO(assertEquals(restored.snapshot.map(_._2), Some(snap(2)), label))
            _ <- IO(assertEquals(restored.skipped.map(_.name), Vector(name), label))
            _ <- IO(
              assert(
                restored.skipped.head.error.isInstanceOf[StorageError.Corrupt],
                label + restored.skipped
              )
            )
          yield ()
        }
      }
    }
  }

  test("every truncation point of the newest snapshot is detected, never raised") {
    tempDir.use { dir =>
      ok {
        for
          s <- open(dir)
          _ <- (1 to 2).toList.traverse_(i => s.write(snap(i)))
          name <- newest(dir)
          full <- files.readAll(dir / name).compile.to(Array)
          cuts = (0 until full.length by math.max(1, full.length / 40)).toList
          _ <- cuts.traverse_ { cut =>
            corrupt(dir / name, _ => full.take(cut)) *> s.restore(anyAnchor).map { r =>
              assertEquals(r.snapshot.map(_._2), Some(snap(1)), s"cut at $cut")
            }
          }
        yield ()
      }
    }
  }

  test("all snapshots damaged restores nothing and reports each") {
    tempDir.use { dir =>
      ok {
        for
          s <- open(dir)
          _ <- (1 to 2).toList.traverse_(i => s.write(snap(i)))
          names <- s.list
          _ <- names.traverse_(n => corrupt(dir / n, _.take(10)))
          restored <- s.restore(anyAnchor)
          _ <- IO(assertEquals(restored.snapshot, None))
          _ <- IO(assertEquals(restored.skipped.size, 2))
        yield ()
      }
    }
  }

  test("a snapshot of another schema is a version mismatch, not garbage") {
    tempDir.use { dir =>
      ok {
        for
          _ <- open(dir).flatMap(_.write(snap(1)))
          v2 <- FileLedgerSnapshots.open[IO, ToyV2](dir)
          restored <- v2.restore(anyAnchor)
          _ <- IO(assertEquals(restored.snapshot, None))
          _ <- IO(
            assert(
              restored.skipped.head.error.isInstanceOf[StorageError.VersionMismatch],
              restored.skipped.toString
            )
          )
        yield ()
      }
    }
  }

  test("a format version bump is a version mismatch") {
    tempDir.use { dir =>
      ok {
        for
          s <- open(dir)
          _ <- s.write(snap(1))
          name <- newest(dir)
          // the version is the u32 after the 8-byte magic
          _ <- corrupt(dir / name, b => b.updated(11, 9.toByte))
          restored <- s.restore(anyAnchor)
          _ <- IO(
            assert(
              restored.skipped.head.error.isInstanceOf[StorageError.VersionMismatch],
              restored.skipped.toString
            )
          )
        yield ()
      }
    }
  }

  test("an interrupted write leaves only a temp file, removed on the next open") {
    tempDir.use { dir =>
      ok {
        for
          s <- open(dir)
          _ <- s.write(snap(1))
          tmp = dir / "snapshot-00000000000000000099-9.bin.tmp"
          _ <- Stream.emits(List.fill(100)(1.toByte)).through(files.writeAll(tmp)).compile.drain
          restored <- s.restore(anyAnchor)
          _ <- IO(assertEquals(restored.snapshot.map(_._2), Some(snap(1))))
          _ <- open(dir)
          exists <- files.exists(tmp)
          _ <- IO(assert(!exists))
        yield ()
      }
    }
  }

  test("a larger state round-trips through the file") {
    tempDir.use { dir =>
      ok {
        val big = Toy(
          1,
          (0 until 200000)
            .map(j => Bytes(Vector.tabulate(32)(k => (j + k).toByte)) -> BigInt(j))
            .toMap,
          Vector.empty
        )
        for
          s <- open(dir)
          _ <- s.write(StateSnapshot(Some(block(1)._1), big))
          restored <- s.restore(anyAnchor)
          _ <- IO(assertEquals(restored.snapshot.map(_._2.state), Some(big)))
        yield ()
      }
    }
  }
