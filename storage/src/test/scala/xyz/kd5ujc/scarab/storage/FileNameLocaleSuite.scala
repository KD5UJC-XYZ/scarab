// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.IO
import cats.mtl.Handle
import cats.syntax.all.*
import fs2.Chunk
import fs2.io.file.{Files, Path}
import java.util.Locale
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.ledger.{GenesisFileHash, NetworkIdentity, StorageError}
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.storage.haskell.{
  ChunkNo,
  ChunkSlots,
  HaskellImmutableError,
  HaskellImmutableReader
}
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

  // ---------------------------------------------------------------- names in a locale's digits

  /** `name` with its ASCII digits written from `zero`, as `%d` wrote them under a locale whose
    * digits start there before the numbers of names were written in the root locale.
    */
  private def inDigits(zero: Char)(name: String): String =
    name.map(c => if c >= '0' && c <= '9' then (zero + (c - '0')).toChar else c)

  /** Persian (`fa-IR`) and Arabic-Indic (`ar-EG`) zeros. */
  private val zeros = List('\u06f0', '\u0660')

  /** Rename every file of `dir` as [[inDigits]] does. */
  private def relabel(dir: Path, zero: Char): IO[Unit] =
    listing(dir).flatMap(_.traverse_ { name =>
      val renamed = inDigits(zero)(name)
      Files.forAsync[IO].move(dir / name, dir / renamed).whenA(renamed != name)
    })

  /** Each file of `dir` and its bytes. */
  private def contents(dir: Path): IO[Map[String, Bytes]] =
    listing(dir)
      .flatMap(_.traverse { name =>
        Files
          .forAsync[IO]
          .readAll(dir / name)
          .compile
          .to(Chunk)
          .map(c => name -> Bytes.wrap(c.toArray))
      })
      .map(_.toMap)

  test("an immutable store whose names hold a locale's digits is refused and left as it was") {
    zeros.traverse_ { zero =>
      tempDir.use { dir =>
        for
          _ <- writeImmutable(dir)
          _ <- relabel(dir, zero)
          before <- contents(dir)
          opened <- attempt(FileImmutableStore.open[IO](dir, chunkSlots).use(_ => IO.unit))
          preflight <- attempt(FileImmutableStore.preflightReadOnly[IO](dir, chunkSlots))
          first <- attempt(FileImmutableStore.firstBlockReadOnly[IO](dir))
          after <- contents(dir)
        yield
          val refused: Left[StorageError, Nothing] = Left(
            StorageError.Corrupt(
              dir.toString,
              FileNameProblem.LocaleDigits(inDigits(zero)("00000.index"))
            )
          )
          assertEquals(opened, refused, "open")
          assertEquals(preflight, refused, "the read-only preflight")
          assertEquals(first, refused, "the read-only first block")
          assertEquals(after, before, "the files and their bytes")
      }
    }
  }

  test("a chunk file in a locale's digits beside an ASCII store is refused by every reader") {
    zeros.traverse_ { zero =>
      tempDir.use { dir =>
        val stray = inDigits(zero)("00002.chunk")
        for
          _ <- writeImmutable(dir)
          _ <- Files
            .forAsync[IO]
            .writeAll(dir / stray)
            .apply(fs2.Stream.emits(Array[Byte](1, 2)))
            .compile
            .drain
          before <- contents(dir)
          opened <- attempt(FileImmutableStore.open[IO](dir, chunkSlots).use(_ => IO.unit))
          preflight <- attempt(FileImmutableStore.preflightReadOnly[IO](dir, chunkSlots))
          first <- attempt(FileImmutableStore.firstBlockReadOnly[IO](dir))
          after <- contents(dir)
        yield
          val refused: Left[StorageError, Nothing] =
            Left(StorageError.Corrupt(dir.toString, FileNameProblem.LocaleDigits(stray)))
          assertEquals(preflight, refused, "the read-only preflight")
          assertEquals(first, refused, "the read-only first block")
          assertEquals(opened, refused, "open")
          assertEquals(after, before, "the files and their bytes")
      }
    }
  }

  test("a volatile log whose names hold a locale's digits is refused and left as it was") {
    zeros.traverse_ { zero =>
      tempDir.use { dir =>
        for
          _ <- ok(VolatileStore.open[IO](dir, perFile).use { (store, _) =>
            (0 until 5).toList.map(block(_)).traverse_((ref, raw) => store.append(ref, raw))
          })
          _ <- relabel(dir, zero)
          before <- contents(dir)
          opened <- attempt(VolatileStore.open[IO](dir, perFile).use(_ => IO.unit))
          // the received-block log is opened by its index
          indexed <- attempt(VolatileStore.openIndex[IO](dir, perFile).use(_ => IO.unit))
          after <- contents(dir)
        yield
          // the log reads its files in the order they are listed: the refusal names any of them
          val renamed = before.keySet
          List("open" -> opened, "openIndex" -> indexed).foreach { (how, result) =>
            result match
              case Left(StorageError.Corrupt(what, FileNameProblem.LocaleDigits(name))) =>
                assertEquals(what, dir.toString, how)
                assert(renamed.contains(name), s"$how: $name is not a file of the log")
              case other => fail(s"$how: expected a refusal of a locale's digits, got $other")
          }
          assertEquals(after, before, "the files and their bytes")
      }
    }
  }

  // ---------------------------------------------------------------- a Haskell database

  test("a Haskell database's chunk files are found under fa-IR and ar-EG, as under root") {
    // the first three chunks of a cardano-node Preview database: 648 blocks (its README)
    val dir = Path("fixtures/haskell-immutable/preview/immutable")
    val slots = ChunkSlots.of(4320).getOrElse(fail("Preview's chunk size"))
    (Locale.ROOT :: others).traverse_ { locale =>
      under(locale)(
        (
          IO(List(0, 1, 100000).flatMap(ChunkNo.of).map(_.stem)),
          Handle
            .allow[HaskellImmutableError](
              HaskellImmutableReader[IO](dir, slots).blocks().compile.count.map(_.asRight)
            )
            .rescue(e => IO.pure(e.asLeft[Long]))
        ).tupled
      ).map { (stems, counted) =>
        assertEquals(counted, Right(648L), s"blocks read under $locale")
        assertEquals(stems, List("00000", "00001", "100000"), s"chunk names under $locale")
      }
    }
  }
