// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.IO
import cats.syntax.all.*
import fs2.io.file.{Files, Path}
import java.util.Locale
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.ledger.{GenesisFileHash, NetworkIdentity, StorageError}
import xyz.kd5ujc.scarab.protocol.Era
import munit.CatsEffectSuite

import NodeCodecs.blockRef
import StorageTestKit.*

/** The numbers in storage file names are ASCII digits under every locale, the names of the root
  * locale, so that a store written under one locale opens under another. `%d` writes Persian digits
  * under `fa-IR` and Arabic-Indic digits under `ar-EG`. Each test sets the locale only while it
  * runs ([[StorageTestKit.underLocale]]), and the names it expects are ASCII literals.
  */
class FileNameLocaleSuite extends CatsEffectSuite:
  private val persian = Locale.forLanguageTag("fa-IR")
  private val arabic = Locale.forLanguageTag("ar-EG")
  private val others = List(persian, arabic)

  /** Each pair of a locale with other digits and the root locale, both ways round. */
  private val crossings: List[(Locale, Locale)] =
    others.flatMap(l => List(Locale.ROOT -> l, l -> Locale.ROOT))

  /** `body` under `locale`; under a locale other than the root, once `%d` is seen to write digits
    * other than ASCII there, so that the test shows something.
    */
  private def under[A](locale: Locale)(body: IO[A]): IO[A] =
    underLocale(locale).surround(
      IO.unlessA(locale == Locale.ROOT)(
        IO(assertNotEquals("%d".format(7), "7", s"$locale writes ASCII digits in this JDK"))
      ) *> body
    )

  private def listing(dir: Path): IO[Vector[String]] =
    Files.forAsync[IO].list(dir).map(_.fileName.toString).compile.toVector.map(_.sorted)

  // ---------------------------------------------------------------- snapshot and checkpoint names

  /** Anchors and the stems their snapshot file and checkpoint directory are named by: the slot is
    * twenty digits, up to the largest slot (`Word64`).
    */
  private val stems: List[(BlockRef, String)] = List(
    BlockRef(BigInt(0), hash(0), BigInt(0)) -> "snapshot-00000000000000000000-0",
    BlockRef(BigInt(123456789), hash(1), BigInt(4567)) -> "snapshot-00000000000123456789-4567",
    BlockRef(BigInt("18446744073709551615"), hash(2), BigInt(98765)) ->
      "snapshot-18446744073709551615-98765"
  )

  test("snapshot files and checkpoints are named in ASCII under fa-IR and ar-EG, as under root") {
    (Locale.ROOT :: others).traverse_ { locale =>
      under(locale)(IO {
        stems.foreach { (anchor, stem) =>
          assertEquals(FileLedgerSnapshots.nameOf(anchor), s"$stem.bin", s"snapshot under $locale")
          assertEquals(StateBacking.stem(anchor), stem, s"checkpoint under $locale")
          assert(StateBacking.isStem(StateBacking.stem(anchor)), s"a stem under $locale")
        }
      })
    }
  }

  // ---------------------------------------------------------------- the immutable store

  private val chunkSlots = 30L

  /** Blocks in chunks 0, 1 and 100000: a chunk number wider than the five digits of its name. */
  private val immutable: List[(BlockRef, Bytes)] = List(0, 1, 12, 1000000).map(block(_))

  private val immutableNames = Vector(
    "00000.chunk",
    "00000.index",
    "00001.chunk",
    "00001.index",
    "100000.chunk",
    "100000.index",
    "format"
  )

  private def writeImmutable(dir: Path): IO[Unit] =
    ok(
      FileImmutableStore
        .open[IO](dir, chunkSlots)
        .use((store, _) => immutable.traverse_((ref, raw) => store.append(ref, raw)))
    )

  test("an immutable store names its files in ASCII under fa-IR and ar-EG, as under root") {
    (Locale.ROOT :: others).traverse_ { locale =>
      tempDir.use { dir =>
        for
          _ <- under(locale)(writeImmutable(dir))
          names <- listing(dir)
        yield assertEquals(names, immutableNames, s"written under $locale")
      }
    }
  }

  test("an immutable store written under root opens under fa-IR and ar-EG, and the reverse") {
    crossings.traverse_ { (writer, reader) =>
      tempDir.use { dir =>
        for
          _ <- under(writer)(writeImmutable(dir))
          read <- under(reader)(ok {
            for
              _ <- FileImmutableStore.preflightReadOnly[IO](dir, chunkSlots)
              first <- FileImmutableStore.firstBlockReadOnly[IO](dir)
              stored <- FileImmutableStore.open[IO](dir, chunkSlots).use { (store, report) =>
                (store.tip, immutable.traverse((ref, _) => store.get(ref.point)))
                  .mapN((tip, blocks) => (report.tip, tip, blocks))
              }
            yield (first, stored)
          })
          names <- listing(dir)
        yield
          val (first, (opened, tip, blocks)) = read
          val clue = s"written under $writer, read under $reader"
          assertEquals(first, immutable.headOption.map(_._2), clue)
          assertEquals(opened, immutable.lastOption.map(_._1), clue)
          assertEquals(tip, opened, clue)
          assertEquals(blocks, immutable.map((_, raw) => Some(raw)), clue)
          assertEquals(names, immutableNames, clue)
      }
    }
  }

  test("the import's point check reads a chunk written under root under fa-IR and ar-EG") {
    val identity = NetworkIdentity(
      42L,
      Bytes.fromArray(Array.tabulate[Byte](32)(_.toByte)),
      Some(Bytes.fromArray(Array.tabulate[Byte](32)(i => (i + 1).toByte))),
      GenesisFileHash(Bytes.fromArray(Array.tabulate[Byte](32)(i => (i + 2).toByte))),
      GenesisFileHash(Bytes.fromArray(Array.tabulate[Byte](32)(i => (i + 3).toByte))),
      Some(GenesisFileHash(Bytes.fromArray(Array.tabulate[Byte](32)(i => (i + 4).toByte))))
    )
    // in chunk 0, between two stored blocks: the check reads the chunk's index and does not find it
    val absent = BlockRef(BigInt(4), hash(99), BigInt(99))
    others.traverse_ { locale =>
      tempDir.use { db =>
        for
          _ <- ok(NetworkIdentityFile.write[IO](db, identity))
          _ <- ok(
            FileImmutableStore
              .open[IO](db / "immutable")
              .use((store, _) =>
                (0 until 3).toList.map(block(_)).traverse_((ref, raw) => store.append(ref, raw))
              )
          )
          checked <- under(locale)(
            attempt(SnapshotPoint.check[IO](db, identity, absent, Era.Shelley, BigInt(0)))
          )
        yield checked match
          case Left(StorageError.Missing(_)) => ()
          case other => fail(s"under $locale: expected the point to be missing, got $other")
      }
    }
  }

  // ---------------------------------------------------------------- the volatile log

  private val perFile = 2

  test("a volatile log written under root opens under fa-IR and ar-EG, and the reverse") {
    val first = (0 until 5).map(block(_)).toVector
    val later = (5 until 7).map(block(_)).toVector
    crossings.traverse_ { (writer, reader) =>
      tempDir.use { dir =>
        val clue = s"written under $writer, read under $reader"
        for
          _ <- under(writer)(ok(VolatileStore.open[IO](dir, perFile).use { (store, _) =>
            first.traverse_((ref, raw) => store.append(ref, raw))
          }))
          read <- under(reader)(ok(VolatileStore.open[IO](dir, perFile).use { (store, opened) =>
            for
              bodies <- opened.located.traverse((_, at) => store.read(at))
              _ <- later.traverse_((ref, raw) => store.append(ref, raw))
            yield (opened.chain, bodies)
          }))
          reopened <- under(writer)(ok(VolatileStore.open[IO](dir, perFile).use { (_, opened) =>
            IO.pure(opened.chain)
          }))
          names <- listing(dir)
        yield
          val (chain, bodies) = read
          assertEquals(chain, first, clue)
          assertEquals(bodies, first.map(Some(_)), clue)
          assertEquals(reopened, first ++ later, clue)
          assertEquals(
            names,
            Vector("00000000.log", "00000001.log", "00000002.log", "00000003.log"),
            clue
          )
      }
    }
  }

  // ---------------------------------------------------------------- ledger snapshots

  private given SnapshotFormat[IO, Toy] = ToyFormat.format[IO, Toy]

  private def snapshot(i: Int): StateSnapshot[Toy] =
    StateSnapshot(
      Some(block(i)._1),
      Toy(BigInt(i), Map.empty, Vector.empty),
      CheckpointBinding.Unbacked
    )

  test(
    "snapshots written under root, then under fa-IR or ar-EG, are kept and restored newest first"
  ) {
    // the anchor of snapshot `i` is block `i`, at slot `3 * i`
    val names = List(
      "snapshot-00000000000000000012-4.bin",
      "snapshot-00000000000000000009-3.bin",
      "snapshot-00000000000000000006-2.bin"
    )
    crossings.traverse_ { (writer, reader) =>
      tempDir.use { dir =>
        val clue = s"written under $writer, then under $reader"
        for
          _ <- under(writer)(ok(FileLedgerSnapshots.open[IO, Toy](dir, keep = 3).flatMap { s =>
            List(1, 2).traverse_(i => s.write(snapshot(i)))
          }))
          read <- under(reader)(ok(FileLedgerSnapshots.open[IO, Toy](dir, keep = 3).flatMap { s =>
            for
              _ <- List(3, 4).traverse_(i => s.write(snapshot(i)))
              listed <- s.list
              restored <- s.restore(_ => IO.pure(Right(())))
            yield (listed, restored.snapshot)
          }))
          files <- listing(dir)
        yield
          val (listed, restored) = read
          assertEquals(listed.toList, names, clue)
          assertEquals(files.toList, names.sorted, clue)
          assertEquals(restored, names.headOption.map(_ -> snapshot(4)), clue)
      }
    }
  }
