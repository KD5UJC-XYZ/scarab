// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.IO
import cats.mtl.Handle
import cats.syntax.all.*
import fs2.io.file.Path
import java.nio.charset.StandardCharsets
import java.nio.file.Files as JFiles
import munit.CatsEffectSuite
import xyz.kd5ujc.scarab.ledger.{FileKind, FormatVersion, StorageError}
import xyz.kd5ujc.scarab.node.{Main, NodeSetup}

import StorageTestKit.*

/** Identity admission probes only bounded headers. It accepts every snapshot version whose live
  * decoder/migration remains registered and refuses unknown or malformed stores before writing.
  */
class SnapshotPreflightSuite extends CatsEffectSuite:
  private val setup = NodeSetup
    .fromDirectory(java.nio.file.Path.of("fixtures/eras/genesis"))
    .fold(e => fail(e.message), identity)
  private val snapshotName = "snapshot-99999999999999999999-1.bin"
  private val chunkSlots = 100L

  private def header(version: FormatVersion): Array[Byte] =
    if version <= FormatVersion.V2 then
      java.nio.ByteBuffer
        .allocate(12)
        .put("CSLSNAP1".getBytes(StandardCharsets.US_ASCII))
        .putInt(version.ordinal + 1)
        .array()
    else FileKind.SnapshotFile.identifier(version).getBytes(StandardCharsets.US_ASCII)

  private def write(path: Path, bytes: Array[Byte]): IO[Unit] =
    IO.blocking {
      JFiles.createDirectories(path.toNioPath.getParent)
      JFiles.write(path.toNioPath, bytes)
      ()
    }

  private def seed(
      dir: Path,
      snapshot: Array[Byte],
      format: String = FileImmutableStore.FormatText,
      index: Array[Byte] = FileImmutableStore.IndexMagic,
      slots: Long = chunkSlots
  ): IO[Unit] =
    val files = Vector(
      NetworkIdentityFile.FileName -> setup.identity
        .renderAs(FormatVersion.V2)
        .getBytes(StandardCharsets.UTF_8),
      s"snapshots/$snapshotName" -> snapshot,
      "immutable/format" -> s"$format chunkSlots=$slots\n".getBytes(StandardCharsets.UTF_8),
      "immutable/00000.index" -> index
    )
    files.traverse_ { (name, bytes) =>
      val path = dir / name
      write(path, bytes)
    }

  private def tree(dir: Path): IO[Map[String, Vector[Byte]]] = IO.blocking {
    val stream = JFiles.walk(dir.toNioPath)
    try
      import scala.jdk.CollectionConverters.*
      stream
        .iterator()
        .asScala
        .filter(JFiles.isRegularFile(_))
        .map(path => dir.toNioPath.relativize(path).toString -> JFiles.readAllBytes(path).toVector)
        .toMap
    finally stream.close()
  }

  private def retained(dir: Path, before: Map[String, Vector[Byte]]): IO[Unit] =
    tree(dir).map(after => assertEquals(after, before))

  private def attempt[A](body: Handle[IO, StorageError] ?=> IO[A]): IO[Either[StorageError, A]] =
    Handle
      .allow[StorageError](body.map(_.asRight[StorageError]))
      .rescue(e => IO.pure(e.asLeft[A]))

  for version <- Vector(
      FormatVersion.V1,
      FormatVersion.V2,
      FormatVersion.V3,
      FormatVersion.V4,
      FormatVersion.V5
    )
  do
    test(s"read-only snapshot preflight retains registered V${version.ordinal + 1} support") {
      tempDir.use { dir =>
        for
          _ <- write(dir / "snapshots" / snapshotName, header(version))
          result <- attempt(SnapshotMigration.preflight[IO](dir / "snapshots"))
        yield assertEquals(result, Right(()))
      }
    }

  test("unknown snapshot version leaves an adoption candidate byte-for-byte unchanged") {
    tempDir.use { dir =>
      val futureSnapshot = "SCRBSNP9".getBytes(StandardCharsets.US_ASCII)
      for
        _ <- seed(dir, futureSnapshot)
        original <- tree(dir)
        result <- attempt(
          ChainStorage
            .preflight[IO](
              dir,
              Some(setup.identity),
              confirmLegacy = true,
              byronEpochSlots = setup.geometry.byron.epochSlots,
              chunkSlots = chunkSlots
            )
            .flatMap(NetworkIdentityFile.admit[IO](dir, setup.identity, _))
        )
        _ <- retained(dir, original)
      yield assert(result.left.exists(_.isInstanceOf[StorageError.UnknownVersion]), result.toString)
    }
  }

  test("Main refuses an unknown snapshot before acquiring its UTXO backing") {
    tempDir.use { dir =>
      for
        _ <- seed(dir, "SCRBSNP9".getBytes(StandardCharsets.US_ASCII))
        original <- tree(dir)
        code <- Main.run(
          List(
            "follow",
            "--genesis-dir",
            "fixtures/eras/genesis",
            "--db",
            dir.toString,
            "--adopt-legacy-network-identity",
            "--peer",
            "127.0.0.1:1"
          )
        )
        _ <- retained(dir, original)
      yield
        assertEquals(code, cats.effect.ExitCode(1))
        // Database ownership's lease is read/write bookkeeping, but no RocksDB path is created.
        assert(!JFiles.exists((dir / "utxo").toNioPath))
    }
  }

  test("truncated snapshot version header leaves an adoption candidate unchanged") {
    tempDir.use { dir =>
      val malformed = "CSLSNAP1".getBytes(StandardCharsets.US_ASCII)
      for
        _ <- seed(dir, malformed)
        original <- tree(dir)
        result <- attempt(
          ChainStorage
            .preflight[IO](
              dir,
              Some(setup.identity),
              confirmLegacy = true,
              byronEpochSlots = setup.geometry.byron.epochSlots,
              chunkSlots = chunkSlots
            )
            .flatMap(NetworkIdentityFile.admit[IO](dir, setup.identity, _))
        )
        _ <- retained(dir, original)
      yield assert(result.isLeft, result.toString)
    }
  }

  test("unknown immutable format refuses before legacy identity adoption") {
    tempDir.use { dir =>
      val v5 = header(FormatVersion.V5)
      for
        _ <- seed(dir, v5, format = "scarab immutable store v9")
        original <- tree(dir)
        result <- attempt(
          ChainStorage
            .preflight[IO](
              dir,
              Some(setup.identity),
              confirmLegacy = true,
              byronEpochSlots = setup.geometry.byron.epochSlots,
              chunkSlots = chunkSlots
            )
            .flatMap(NetworkIdentityFile.admit[IO](dir, setup.identity, _))
        )
        _ <- retained(dir, original)
      yield assert(result.left.exists(_.isInstanceOf[StorageError.UnknownVersion]), result.toString)
    }
  }

  test("unknown immutable index refuses before legacy identity adoption") {
    tempDir.use { dir =>
      val v5 = header(FormatVersion.V5)
      val futureIndex = "SCRBIDX9".getBytes(StandardCharsets.US_ASCII)
      for
        _ <- seed(dir, v5, index = futureIndex)
        original <- tree(dir)
        result <- attempt(
          ChainStorage
            .preflight[IO](
              dir,
              Some(setup.identity),
              confirmLegacy = true,
              byronEpochSlots = setup.geometry.byron.epochSlots,
              chunkSlots = chunkSlots
            )
            .flatMap(NetworkIdentityFile.admit[IO](dir, setup.identity, _))
        )
        _ <- retained(dir, original)
      yield assert(result.left.exists(_.isInstanceOf[StorageError.UnknownVersion]), result.toString)
    }
  }

  test("chunk slot mismatch refuses before legacy identity adoption") {
    tempDir.use { dir =>
      val v5 = header(FormatVersion.V5)
      for
        _ <- seed(dir, v5, slots = chunkSlots + 1)
        original <- tree(dir)
        result <- attempt(
          ChainStorage
            .preflight[IO](
              dir,
              Some(setup.identity),
              confirmLegacy = true,
              byronEpochSlots = setup.geometry.byron.epochSlots,
              chunkSlots = chunkSlots
            )
            .flatMap(NetworkIdentityFile.admit[IO](dir, setup.identity, _))
        )
        _ <- retained(dir, original)
      yield assert(
        result.left.exists(_.isInstanceOf[StorageError.VersionMismatch]),
        result.toString
      )
    }
  }
