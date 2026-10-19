// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.io.file.Path
import java.nio.charset.StandardCharsets
import java.nio.file.Files as JFiles
import munit.{CatsEffectSuite, ScalaCheckSuite}
import org.scalacheck.{Gen, Prop}
import org.scalacheck.Prop.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger.{
  Block,
  FileKind,
  FormatVersion,
  FirstImmutableProblem,
  IdentityProvenance,
  LegacyNetworkIdentity,
  NetworkIdentity,
  StorageError,
  StoredNetworkIdentity
}
import xyz.kd5ujc.scarab.protocol.ChainSync
import xyz.kd5ujc.scarab.consensus.BlockRef

import StorageTestKit.*

/** The version-1 to version-2 migration of the persisted file formats (first user of [[Migrate]]):
  * a store written under the former name opens, is rewritten file by file, and reads back
  * identically; a crash in the middle leaves whole files of either version; a version this build
  * does not know refuses to open and rewrites nothing.
  *
  * The version-1 databases here are built from literals of the former spelling, not from the
  * constants of the current code, so renaming a constant cannot change what "version 1" means.
  */
class FormatMigrationSuite extends CatsEffectSuite with ScalaCheckSuite:
  private val chunkSlots = 30L
  private val blocks = 0 until 35 // chunks 0..3

  private val v1Magic = "CSLIDX01".getBytes(StandardCharsets.US_ASCII)
  private val v2Magic = "SCRBIDX2".getBytes(StandardCharsets.US_ASCII)
  private val v1Format = s"cardano-scala-lab immutable store v1 chunkSlots=$chunkSlots\n"
  private val v2Format = s"scarab immutable store v2 chunkSlots=$chunkSlots\n"

  private def indexFile(dir: Path, c: Int) = dir / f"$c%05d.index"
  private def formatFile(dir: Path) = dir / "format"
  private def indexes(dir: Path): List[Path] = (0 to 3).map(indexFile(dir, _)).toList
  private def text(p: Path): String =
    new String(JFiles.readAllBytes(p.toNioPath), StandardCharsets.UTF_8)
  private def bytes(p: Path): Array[Byte] = JFiles.readAllBytes(p.toNioPath)
  private def magic(p: Path): List[Byte] = bytes(p).take(8).toList

  private def open(dir: Path)(using cats.mtl.Raise[IO, StorageError]) =
    FileImmutableStore.open[IO](dir, chunkSlots)

  private def fill(store: ImmutableStore[IO]): IO[Unit] =
    blocks.toList.traverse_(i => block(i) match { case (ref, raw) => store.append(ref, raw) })

  private def everything(store: ImmutableStore[IO]): IO[List[ImmutableStore.Stored]] =
    store.stream(ChainSync.Point.Origin).compile.toList

  /** Rewrite a current-format store as version 1 would have left it: the former magic and text. */
  private def downgrade(dir: Path, only: List[Int] = (0 to 3).toList): IO[Unit] = IO {
    only.foreach { c =>
      val p = indexFile(dir, c).toNioPath
      JFiles.write(p, v1Magic ++ JFiles.readAllBytes(p).drop(8))
    }
    if only.size == 4 then
      JFiles.write(formatFile(dir).toNioPath, v1Format.getBytes(StandardCharsets.UTF_8))
  }

  /** A store of 35 blocks at version 1 and what it holds. */
  private def version1(dir: Path): IO[List[ImmutableStore.Stored]] =
    for
      held <- ok(open(dir).use((store, _) => fill(store) *> everything(store)))
      _ <- downgrade(dir)
    yield held

  test(
    "a version 1 store opens migrated, with the same blocks, and the next open migrates nothing"
  ) {
    tempDir.use { dir =>
      for
        held <- version1(dir)
        _ <- IO(assertEquals(magic(indexFile(dir, 2)), v1Magic.toList))
        first <- ok(open(dir).use((store, report) => everything(store).tupleRight(report)))
        _ <- IO {
          assertEquals(first._1, held)
          assertEquals(
            first._2.migrated,
            List(
              Migrated(FileKind.IndexFile, FormatVersion.V1, FormatVersion.V2, 4),
              Migrated(FileKind.FormatFile, FormatVersion.V1, FormatVersion.V2, 1)
            )
          )
          assertEquals(indexes(dir).map(magic).distinct, List(v2Magic.toList))
          assertEquals(text(formatFile(dir)), v2Format)
        }
        before = indexes(dir).map(bytes(_).toList)
        second <- ok(open(dir).use((store, report) => everything(store).tupleRight(report)))
        _ <- IO {
          assertEquals(second._1, held)
          assertEquals(second._2.migrated, Nil)
          assertEquals(indexes(dir).map(bytes(_).toList), before)
        }
        // only the magic changed: every entry is as version 1 wrote it
        _ <- IO(assertEquals(before.map(_.drop(8)), downgradedEntries(held)))
      yield ()
    }
  }

  /** The entries of each chunk's index, as the (unchanged) entry encoding of the held blocks. */
  private def downgradedEntries(held: List[ImmutableStore.Stored]): List[List[Byte]] =
    held
      .groupBy(_.ref.slot / chunkSlots)
      .toList
      .sortBy(_._1)
      .map { (_, inChunk) =>
        inChunk
          .foldLeft((0L, List.empty[Byte])) { (acc, b) =>
            val raw = b.raw.toArray
            val crc = new java.util.zip.CRC32()
            crc.update(raw)
            val entry = FileImmutableStore.encode(
              FileImmutableStore.Entry(acc._1, raw.length, crc.getValue.toInt, b.ref)
            )
            (acc._1 + raw.length, acc._2 ++ entry.toList)
          }
          ._2
      }

  test("a store the new build creates is version 2 from the start and migrates nothing") {
    tempDir.use { dir =>
      for
        _ <- ok(
          open(dir).use((store, report) => IO(assertEquals(report.migrated, Nil)) *> fill(store))
        )
        _ <- IO {
          assertEquals(indexes(dir).map(magic).distinct, List(v2Magic.toList))
          assertEquals(text(formatFile(dir)), v2Format)
        }
        report <- ok(open(dir).use((_, report) => IO.pure(report)))
        _ <- IO(assertEquals(report.migrated, Nil))
      yield ()
    }
  }

  test("a crash in the middle leaves whole files of either version and the next open finishes") {
    tempDir.use { dir =>
      for
        held <- version1(dir)
        // chunks 0 and 1 were migrated before the crash (each atomically), and a temporary file of
        // the interrupted rewrite of chunk 2 is left behind
        _ <- ok(
          Migrate(FileImmutableStore.indexMigration[IO], List(indexFile(dir, 0), indexFile(dir, 1)))
        )
        _ <- IO(JFiles.write(dir.toNioPath.resolve("00002.index.tmp"), Array[Byte](1, 2, 3)))
        _ <- IO(
          assertEquals(
            indexes(dir).map(magic),
            List(v2Magic, v2Magic, v1Magic, v1Magic).map(_.toList)
          )
        )
        resumed <- ok(open(dir).use((store, report) => everything(store).tupleRight(report)))
        _ <- IO {
          assertEquals(resumed._1, held)
          assertEquals(
            resumed._2.migrated,
            List(
              Migrated(FileKind.IndexFile, FormatVersion.V1, FormatVersion.V2, 2),
              Migrated(FileKind.FormatFile, FormatVersion.V1, FormatVersion.V2, 1)
            )
          )
          assertEquals(indexes(dir).map(magic).distinct, List(v2Magic.toList))
        }
      yield ()
    }
  }

  test("all indexes migrated but the format file still at version 1: only that file is migrated") {
    tempDir.use { dir =>
      for
        held <- version1(dir)
        _ <- ok(Migrate(FileImmutableStore.indexMigration[IO], indexes(dir)))
        resumed <- ok(open(dir).use((store, report) => everything(store).tupleRight(report)))
        _ <- IO {
          assertEquals(resumed._1, held)
          assertEquals(
            resumed._2.migrated,
            List(Migrated(FileKind.FormatFile, FormatVersion.V1, FormatVersion.V2, 1))
          )
        }
      yield ()
    }
  }

  test("an index of an unknown version refuses the open and nothing is rewritten") {
    tempDir.use { dir =>
      for
        _ <- version1(dir)
        _ <- IO(
          JFiles.write(
            indexFile(dir, 3).toNioPath,
            "SCRBIDX9".getBytes(StandardCharsets.US_ASCII) ++ bytes(indexFile(dir, 3)).drop(8)
          )
        )
        before = (indexes(dir).map(bytes(_).toList), text(formatFile(dir)))
        r <- attempt(open(dir).use((_, _) => IO.unit))
        _ <- IO {
          r match
            case Left(StorageError.UnknownVersion(_, FileKind.IndexFile, found)) =>
              assertEquals(found, "SCRBIDX9")
            case other => fail(other.toString)
          assertEquals((indexes(dir).map(bytes(_).toList), text(formatFile(dir))), before)
        }
      yield ()
    }
  }

  test(
    "a format file of an unknown version refuses the open; a foreign index magic is corruption"
  ) {
    tempDir.use { dir =>
      for
        _ <- version1(dir)
        _ <- IO(
          JFiles.write(
            formatFile(dir).toNioPath,
            s"scarab immutable store v7 chunkSlots=$chunkSlots\n".getBytes(StandardCharsets.UTF_8)
          )
        )
        unknown <- attempt(open(dir).use((_, _) => IO.unit))
        _ <- IO(JFiles.write(formatFile(dir).toNioPath, v1Format.getBytes(StandardCharsets.UTF_8)))
        _ <- IO(
          JFiles.write(
            indexFile(dir, 1).toNioPath,
            "NOTANIDX".getBytes(StandardCharsets.US_ASCII) ++ bytes(indexFile(dir, 1)).drop(8)
          )
        )
        foreign <- attempt(open(dir).use((_, _) => IO.unit))
        _ <- IO {
          assert(unknown.left.exists(_.isInstanceOf[StorageError.UnknownVersion]), unknown.toString)
          assert(foreign.left.exists(_.isInstanceOf[StorageError.Corrupt[?]]), foreign.toString)
        }
      yield ()
    }
  }

  test("a different chunk size is still a version mismatch, for either format version") {
    tempDir.use { dir =>
      for
        _ <- version1(dir)
        r <- attempt(FileImmutableStore.open[IO](dir, 99).use((_, _) => IO.unit))
        _ <- IO(assert(r.left.exists(_.isInstanceOf[StorageError.VersionMismatch]), r.toString))
        _ <- IO(
          assertEquals(magic(indexFile(dir, 0)), v1Magic.toList)
        ) // refused before any rewrite
      yield ()
    }
  }

  test("read-only first-block lookup crosses empty chunks without reading whole chunk files") {
    tempDir.use { dir =>
      val (firstRef, firstRaw) = block(0)
      val (laterRef, laterRaw) = block(1)
      def index(chunk: Int) = dir / f"$chunk%05d.index"
      def data(chunk: Int) = dir / f"$chunk%05d.chunk"
      def entry(ref: BlockRef, raw: Bytes): Array[Byte] =
        val crc = new java.util.zip.CRC32()
        crc.update(raw.toArray)
        FileImmutableStore.encode(
          FileImmutableStore.Entry(0L, raw.size, crc.getValue.toInt, ref)
        )
      for
        _ <- IO(JFiles.createDirectories(dir.toNioPath))
        _ <- IO(JFiles.write(index(0).toNioPath, FileImmutableStore.IndexMagic))
        _ <- IO(JFiles.write(data(1).toNioPath, laterRaw.toArray))
        _ <- IO(
          JFiles.write(
            index(1).toNioPath,
            FileImmutableStore.IndexMagic ++ entry(laterRef, laterRaw)
          )
        )
        afterEmpty <- ok(FileImmutableStore.firstBlockReadOnly[IO](dir))
        _ <- IO(assertEquals(afterEmpty, Some(laterRaw)))
        _ <- IO(JFiles.write(data(0).toNioPath, firstRaw.toArray))
        _ <- IO(
          JFiles.write(
            index(0).toNioPath,
            FileImmutableStore.IndexMagic ++ entry(firstRef, firstRaw)
          )
        )
        _ <- IO.blocking {
          val channel = java.nio.channels.FileChannel.open(
            data(0).toNioPath,
            java.nio.file.StandardOpenOption.WRITE
          )
          try
            channel.position(128L * 1024 * 1024 - 1)
            channel.write(java.nio.ByteBuffer.wrap(Array[Byte](0)))
          finally channel.close()
        }
        bounded <- ok(FileImmutableStore.firstBlockReadOnly[IO](dir))
        _ <- IO(assertEquals(bounded, Some(firstRaw)))
      yield ()
    }
  }

  test("read-only first-block lookup refuses truncated indexes and missing referenced chunks") {
    tempDir.use { dir =>
      val (ref, raw) = block(0)
      val index = dir / "00000.index"
      val data = dir / "00000.chunk"
      val crc = new java.util.zip.CRC32()
      crc.update(raw.toArray)
      val entry = FileImmutableStore.encode(
        FileImmutableStore.Entry(0L, raw.size, crc.getValue.toInt, ref)
      )
      for
        _ <- IO(JFiles.createDirectories(dir.toNioPath))
        _ <- IO(
          JFiles.write(
            index.toNioPath,
            FileImmutableStore.IndexMagic ++ entry.take(13)
          )
        )
        truncated <- attempt(FileImmutableStore.firstBlockReadOnly[IO](dir))
        _ <- IO(
          assert(
            truncated.left.exists(_.isInstanceOf[StorageError.FirstImmutable]),
            truncated.toString
          )
        )
        _ <- IO(JFiles.write(index.toNioPath, FileImmutableStore.IndexMagic ++ entry))
        missing <- attempt(FileImmutableStore.firstBlockReadOnly[IO](dir))
        _ <- IO {
          assert(
            missing.left.exists {
              case StorageError.FirstImmutable(FirstImmutableProblem.MissingChunk) => true
              case _                                                               => false
            },
            missing.toString
          )
        }
        _ <- IO {
          val tooLarge = FileImmutableStore.encode(
            FileImmutableStore.Entry(0L, Block.MaxInputBytes + 1, 0, ref)
          )
          JFiles.write(index.toNioPath, FileImmutableStore.IndexMagic ++ tooLarge)
          val channel = java.nio.channels.FileChannel.open(
            data.toNioPath,
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.WRITE,
            java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
          )
          try
            channel.position(Block.MaxInputBytes.toLong)
            channel.write(java.nio.ByteBuffer.wrap(Array[Byte](0)))
          finally channel.close()
        }
        oversized <- attempt(FileImmutableStore.firstBlockReadOnly[IO](dir))
        _ <- IO {
          assert(
            oversized.left.exists {
              case StorageError.FirstImmutable(FirstImmutableProblem.InvalidIndexEntry) => true
              case _                                                                    => false
            },
            oversized.toString
          )
        }
      yield ()
    }
  }

  // ---------------------------------------------------------------- the network identity file

  private val identity = NetworkIdentity(
    42L,
    Bytes.fromArray(Array.tabulate[Byte](32)(_.toByte)),
    Some(Bytes.fromArray(Array.tabulate[Byte](32)(i => (i + 1).toByte))),
    xyz.kd5ujc.scarab.ledger.GenesisFileHash(
      Bytes.fromArray(Array.tabulate[Byte](32)(i => (i + 2).toByte))
    ),
    xyz.kd5ujc.scarab.ledger.GenesisFileHash(
      Bytes.fromArray(Array.tabulate[Byte](32)(i => (i + 3).toByte))
    )
  )
  private val v1Header = "cardano-scala-lab network identity v1"

  test("the identity text of each version round trips and carries its version") {
    assertEquals(identity.renderAs(FormatVersion.V1).linesIterator.next(), v1Header)
    assertEquals(identity.render.linesIterator.next(), "scarab network identity v6")
    for v <- FileKind.IdentityFile.history.toList.map(_._1) do
      val expected =
        if v == FormatVersion.V6 then
          StoredNetworkIdentity.Complete(identity, IdentityProvenance.Fresh)
        else StoredNetworkIdentity.Legacy(v, identity.legacy)
      assertEquals(NetworkIdentity.parseVersioned("f", identity.renderAs(v)), Right((v, expected)))
      val shelleyOnly = identity.copy(byronGenesis = None)
      val expectedShelleyOnly =
        if v == FormatVersion.V6 then
          StoredNetworkIdentity.Complete(shelleyOnly, IdentityProvenance.Fresh)
        else
          StoredNetworkIdentity.Legacy(v, LegacyNetworkIdentity(42L, identity.shelleyGenesis, None))
      assertEquals(NetworkIdentity.parse("f", shelleyOnly.renderAs(v)), Right(expectedShelleyOnly))
    val unknown = identity.render.replace("v6", "v9")
    assert(
      NetworkIdentity.parse("f", unknown).left.exists(_.isInstanceOf[StorageError.UnknownVersion])
    )
    assert(
      NetworkIdentity.parse("f", "garbage").left.exists(_.isInstanceOf[StorageError.Corrupt[?]])
    )
    assert(
      NetworkIdentity
        .parse(
          "f",
          identity.render.replace(
            "provenance fresh",
            "provenance operator-confirmed-legacy:v2:none"
          )
        )
        .left
        .exists(_.isInstanceOf[StorageError.Corrupt[?]])
    )
    assert(
      NetworkIdentity
        .parse(
          "f",
          identity.render.replace(
            "provenance fresh",
            "provenance operator-confirmed-legacy:missing:blake2b256=" + "00" * 32
          )
        )
        .left
        .exists(_.isInstanceOf[StorageError.Corrupt[?]])
    )
  }

  test(
    "a legacy identity is incomplete until explicitly adopted, and its original bytes are backed up"
  ) {
    tempDir.use { dir =>
      val file = dir / NetworkIdentityFile.FileName
      val legacy = identity.renderAs(FormatVersion.V2)
      val backup = dir / s"${NetworkIdentityFile.FileName}.legacy-v2"
      for
        _ <- IO(
          JFiles.write(
            file.toNioPath,
            legacy.getBytes(StandardCharsets.UTF_8)
          )
        )
        read <- ok(NetworkIdentityFile.read[IO](dir))
        refused <- attempt(NetworkIdentityFile.migrate[IO](dir))
        plan <- ok(
          NetworkIdentityFile.preflight[IO](dir, identity, populated = true, confirmLegacy = true)
        )
        _ <- ok(NetworkIdentityFile.admit[IO](dir, identity, plan))
        second <- ok(
          NetworkIdentityFile.preflight[IO](dir, identity, populated = true, confirmLegacy = true)
        )
        _ <- ok(NetworkIdentityFile.admit[IO](dir, identity, second))
        verified <- ok(NetworkIdentityFile.verify[IO](dir, identity))
        _ <- IO {
          assertEquals(read, Some(StoredNetworkIdentity.Legacy(FormatVersion.V2, identity.legacy)))
          assert(refused.left.exists(_.isInstanceOf[StorageError.LegacyIdentityNeedsConfirmation]))
          assert(verified)
          val provenance = IdentityProvenance.OperatorConfirmedLegacy(
            FormatVersion.V2,
            Blake2b.hash256.hash(Bytes.fromArray(legacy.getBytes(StandardCharsets.UTF_8)))
          )
          assertEquals(text(file), identity.renderAs(FormatVersion.V6, provenance))
          assertEquals(text(backup), legacy)
          assert(!JFiles.exists(dir.toNioPath.resolve(NetworkIdentityFile.FileName + ".tmp")))
        }
      yield ()
    }
  }

  test("legacy adoption refuses a backup that aliases or differs from the original identity") {
    tempDir.use { dir =>
      val file = dir / NetworkIdentityFile.FileName
      val backup = dir / s"${NetworkIdentityFile.FileName}.legacy-v2"
      val legacy = identity.renderAs(FormatVersion.V2)
      val legacyBytes = legacy.getBytes(StandardCharsets.UTF_8)
      for
        _ <- IO(JFiles.write(file.toNioPath, legacyBytes))
        _ <- IO.blocking(JFiles.createLink(backup.toNioPath, file.toNioPath))
        plan <- ok(
          NetworkIdentityFile.preflight[IO](
            dir,
            identity,
            populated = true,
            confirmLegacy = true
          )
        )
        aliased <- attempt(NetworkIdentityFile.admit[IO](dir, identity, plan))
        _ <- IO.blocking(JFiles.delete(backup.toNioPath))
        _ <- IO(JFiles.write(backup.toNioPath, "different".getBytes(StandardCharsets.UTF_8)))
        different <- attempt(NetworkIdentityFile.admit[IO](dir, identity, plan))
        _ <- IO {
          assert(aliased.left.exists(_.isInstanceOf[StorageError.IdentityBackupConflict]))
          assert(different.left.exists(_.isInstanceOf[StorageError.IdentityBackupConflict]))
          assertEquals(text(file), legacy)
          assertEquals(text(backup), "different")
        }
      yield ()
    }
  }

  test("an identity file of an unknown version is refused and left alone") {
    tempDir.use { dir =>
      val file = dir / NetworkIdentityFile.FileName
      val unknown = identity.render.replace("v6", "v9")
      for
        _ <- IO(JFiles.write(file.toNioPath, unknown.getBytes(StandardCharsets.UTF_8)))
        r <- attempt(NetworkIdentityFile.migrate[IO](dir))
        _ <- IO {
          assert(r.left.exists(_.isInstanceOf[StorageError.UnknownVersion]), r.toString)
          assertEquals(text(file), unknown)
        }
      yield ()
    }
  }

  // ---------------------------------------------------------------- the generic algebra

  /** Files held in memory as the version they are at; a step moves each file to its next changed
    * version, skipping shared database versions that did not alter this kind.
    */
  private def inMemory(
      of: FileKind,
      state: Ref[IO, Map[Path, FormatVersion]],
      stepped: Ref[IO, List[(Path, FormatVersion)]]
  ): Migration[IO] = new Migration[IO]:
    val kind = of
    def detect(file: Path) = state.get.map(_(file))
    def step(file: Path, from: FormatVersion, to: FormatVersion) =
      stepped.update(_ :+ (file -> from)) *>
        state.update(_.updated(file, to))

  property(
    "Migrate brings every file to the current version, steps each stale file once, reports the stale count"
  ) {
    forAll(
      Gen.oneOf(FileKind.values.toList).flatMap { kind =>
        Gen
          .listOf(Gen.oneOf(kind.history.toList.map(_._1)))
          .map(kind -> _)
      }
    ) { case (kind, versions) =>
      val current = kind.current
      val paths = versions.indices.map(i => Path(s"/f/$i")).toList
      val program =
        for
          state <- Ref.of[IO, Map[Path, FormatVersion]](paths.zip(versions).toMap)
          stepped <- Ref.of[IO, List[(Path, FormatVersion)]](Nil)
          report <- Migrate(inMemory(kind, state, stepped), paths)
          after <- state.get
          steps <- stepped.get
        yield (report, after, steps)
      val (report, after, steps) = program.unsafeRunSync()
      val stale = versions.count(_ != current)
      Prop(after.values.forall(_ == current)) :| "all current" &&
      (report.map(_.count).sum ?= stale) :| "count" &&
      (steps.size ?= versions
        .map(v =>
          kind.history.toList.count { case (changed, _) =>
            v.ordinal < changed.ordinal && changed.ordinal <= current.ordinal
          }
        )
        .sum) :| "steps" &&
      (Migrate(inMemory2(kind, after), paths).unsafeRunSync() ?= Nil) :| "idempotent"
    }
  }

  private def inMemory2(of: FileKind, after: Map[Path, FormatVersion]): Migration[IO] =
    new Migration[IO]:
      val kind = of
      def detect(file: Path) = IO.pure(after(file))
      def step(file: Path, from: FormatVersion, to: FormatVersion) =
        IO.raiseError(new AssertionError("nothing is stale"))

  test("global V6 changes only identity; each kind at its current layout is a no-op") {
    assertEquals(FileKind.IdentityFile.current, FormatVersion.V6)
    assertEquals(FileKind.SnapshotFile.current, FormatVersion.V5)
    assertEquals(FileKind.FormatFile.current, FormatVersion.V2)
    assertEquals(FileKind.IndexFile.current, FormatVersion.V2)
    FileKind.values.foreach(kind => assertEquals(kind.nextChangedVersion(kind.current), None))
  }
