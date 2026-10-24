// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.{IO, Ref}
import cats.mtl.{Ask, Handle, Raise}
import cats.syntax.all.*
import fs2.io.file.Path
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files as JFiles
import munit.CatsEffectSuite
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.consensus.state.{ConsensusMirrorEnv, MirrorState}
import xyz.kd5ujc.scarab.ledger.{FileKind, FormatVersion, NodeError, StorageError}
import xyz.kd5ujc.scarab.node.{Main, MirrorRules, NodeSetup}

import StorageTestKit.*

/** Incompatible snapshot families are an admission failure, never a reason to recover or reset
  * another part of the database. The identity is checked first without migrating its old spelling.
  */
class SnapshotPreflightSuite extends CatsEffectSuite:
  private val setup = NodeSetup
    .fromDirectory(java.nio.file.Path.of("fixtures/eras/genesis"))
    .fold(e => fail(e.message), identity)
  private given Ask[IO, ConsensusMirrorEnv] = Ask.const(setup.mirrorEnv())
  private given SnapshotFormat[IO, MirrorState] = SnapshotV7.format[IO](SnapshotCheck.Full)
  private val snapshotName = "snapshot-99999999999999999999-1.bin"

  private val versions =
    Vector(FormatVersion.V1, FormatVersion.V2, FormatVersion.V3, FormatVersion.V4)
      .map(v => Some(v) -> FileKind.SnapshotFile.identifier(v)) :+ (None -> "SCRBSNP9")

  private def header(version: Option[FormatVersion], identifier: String): Array[Byte] =
    version match
      case Some(v) if v <= FormatVersion.V2 =>
        ByteBuffer
          .allocate(12)
          .put("CSLSNAP1".getBytes(StandardCharsets.US_ASCII))
          .putInt(v.ordinal + 1)
          .array()
      case _ => identifier.getBytes(StandardCharsets.US_ASCII)

  private def seed(dir: Path, raw: Array[Byte]): IO[Vector[(Path, Vector[Byte])]] =
    val files = Vector(
      NetworkIdentityFile.FileName -> setup.identity
        .renderAs(FormatVersion.V6)
        .getBytes(StandardCharsets.UTF_8),
      s"snapshots/$snapshotName" -> raw,
      "immutable/format" -> s"${FileImmutableStore.FormatText} chunkSlots=100\n"
        .getBytes(StandardCharsets.UTF_8),
      "immutable/00000.index" -> (FileImmutableStore.IndexMagic ++ Array[Byte](1, 2, 3)),
      "immutable/00000.chunk" -> Array[Byte](1, 2, 3, 4),
      "volatile/00000000.log" -> Array[Byte](1, 2, 3)
    )
    files.traverse { (name, bytes) =>
      val path = dir / name
      IO.blocking {
        JFiles.createDirectories(path.toNioPath.getParent)
        JFiles.write(path.toNioPath, bytes)
        path -> bytes.toVector
      }
    }

  private def retained(files: Vector[(Path, Vector[Byte])]): IO[Unit] =
    files.traverse_ { (path, bytes) =>
      IO.blocking(assertEquals(JFiles.readAllBytes(path.toNioPath).toVector, bytes, path.toString))
    }

  private def attemptNode[A](body: Handle[IO, NodeError] ?=> IO[A]): IO[Either[NodeError, A]] =
    Handle
      .allow[NodeError](body.map(_.asRight[NodeError]))
      .rescue(e => IO.pure(e.asLeft[A]))

  for (version, identifier) <- versions; mismatch <- Vector(false, true) do
    test(s"$identifier, network mismatch=$mismatch: refuse before any recovery or backing reset") {
      tempDir.use { dir =>
        for
          original <- seed(dir, header(version, identifier))
          resets <- Ref.of[IO, Int](0)
          backing = new StateBacking[IO, MirrorState]:
            def advance(state: MirrorState)(using Raise[IO, StorageError]) = IO.unit
            def settled(state: MirrorState)(using Raise[IO, StorageError]) = IO.pure(state)
            def checkpoint(anchor: BlockRef)(using Raise[IO, StorageError]) =
              IO.pure(CheckpointBinding.Unbacked)
            def admit(anchor: Option[BlockRef], binding: CheckpointBinding)(using
                Raise[IO, StorageError]
            ) = IO.pure(Right(()))
            def restore(anchor: Option[BlockRef])(using Raise[IO, StorageError]) = IO.pure(true)
            def reset(using Raise[IO, StorageError]) = resets.update(_ + 1)
            def retain(stems: Set[String])(using Raise[IO, StorageError]) = IO.unit
          expected =
            if mismatch then setup.identity.copy(magic = setup.identity.magic + 1)
            else setup.identity
          result <- attemptNode {
            ChainStorage
              .open[IO, MirrorState](
                dir,
                ChainStorage.Config(
                  SnapshotPolicy.EverySlots(100),
                  byronEpochSlots = setup.geometry.byron.epochSlots,
                  chunkSlots = 100,
                  identity = Some(expected)
                ),
                MirrorRules[IO](
                  setup.praos,
                  setup.globals,
                  setup.byronRules,
                  setup.handOver,
                  setup.byronHistory
                ),
                setup.mirrorStart,
                _ => IO.unit,
                backing,
                _ => IO.unit,
                snapshotMigration = Some(SnapshotMigration.toCurrent[IO])
              )
              .use(_ => IO.unit)
          }
          _ <- retained(original)
          resetCount <- resets.get
        yield
          val error =
            if mismatch then StorageError.NetworkMismatch(setup.identity, expected)
            else
              version.fold[StorageError](
                StorageError.UnknownVersion(
                  (dir / "snapshots" / snapshotName).toString,
                  FileKind.SnapshotFile,
                  identifier
                )
              )(v =>
                StorageError.RetiredVersion(
                  (dir / "snapshots" / snapshotName).toString,
                  FileKind.SnapshotFile,
                  v,
                  FormatVersion.V5
                )
              )
          assertEquals(result, Left(error))
          assertEquals(resetCount, 0)
      }
    }

  for (version, identifier) <- Vector(versions.head, versions.last) do
    test(s"Main rejects $identifier before acquiring its Rocks backing") {
      tempDir.use { dir =>
        for
          original <- seed(dir, header(version, identifier))
          code <- Main.run(
            List(
              "follow",
              "--genesis-dir",
              "fixtures/eras/genesis",
              "--db",
              dir.toString,
              "--peer",
              "127.0.0.1:1"
            )
          )
          _ <- retained(original)
        yield
          assertEquals(code, cats.effect.ExitCode(1))
          // DatabaseOwnership's lock bookkeeping is expected; the Rocks store must never open.
          assert(!JFiles.exists((dir / "utxo").toNioPath))
      }
    }

  private enum EntryPoint:
    case Detect, Step, Run, Generic

  for
    (version, identifier) <- versions
    entry <- EntryPoint.values
    if entry != EntryPoint.Step || version.nonEmpty
  do
    test(s"$identifier is refused by the direct $entry migration entry point") {
      tempDir.use { dir =>
        val file = dir / "snapshots" / snapshotName
        for
          original <- seed(dir, header(version, identifier))
          result <- attemptNode {
            val migration = SnapshotMigration.toCurrent[IO]
            entry match
              case EntryPoint.Detect => migration.detect(file).void
              case EntryPoint.Step =>
                migration.step(file, version.getOrElse(FormatVersion.V1), FormatVersion.V5)
              case EntryPoint.Run     => migration.run(dir / "snapshots").void
              case EntryPoint.Generic => Migrate(migration, List(file)).void
          }
          _ <- retained(original)
        yield
          val error = version.fold[StorageError](
            StorageError.UnknownVersion(file.toString, FileKind.SnapshotFile, identifier)
          )(v =>
            StorageError.RetiredVersion(file.toString, FileKind.SnapshotFile, v, FormatVersion.V5)
          )
          assertEquals(result, Left(error))
      }
    }

  for (version, identifier) <- versions do
    test(s"step cannot bypass $identifier by supplying current V5") {
      tempDir.use { dir =>
        val file = dir / "snapshots" / snapshotName
        for
          original <- seed(dir, header(version, identifier))
          result <- attemptNode(
            SnapshotMigration.toCurrent[IO].step(file, FormatVersion.V5, FormatVersion.V5)
          )
          _ <- retained(original)
        yield
          val error = version.fold[StorageError](
            StorageError.UnknownVersion(file.toString, FileKind.SnapshotFile, identifier)
          )(v =>
            StorageError.RetiredVersion(file.toString, FileKind.SnapshotFile, v, FormatVersion.V5)
          )
          assertEquals(result, Left(error))
      }
    }

  test("step refuses a caller's old from argument for an actual current file") {
    tempDir.use { dir =>
      val file = dir / "snapshots" / snapshotName
      for
        original <- seed(dir, "SCRBSNP5".getBytes(StandardCharsets.US_ASCII))
        result <- attemptNode(
          SnapshotMigration.toCurrent[IO].step(file, FormatVersion.V4, FormatVersion.V5)
        )
        _ <- retained(original)
      yield assertEquals(
        result,
        Left(StorageError.VersionMismatch(file.toString, "SCRBSNP5", "SCRBSNP4"))
      )
    }
  }

  for (version, identifier) <- versions do
    test(s"direct snapshot restore propagates $identifier instead of skipping it") {
      tempDir.use { dir =>
        val raw = FileLedgerSnapshots.closed(header(version, identifier))
        for
          original <- seed(dir, raw)
          older <- IO.blocking(
            JFiles.readAllBytes(java.nio.file.Path.of("fixtures/snapshot-v5/conway-native-730.bin"))
          )
          olderFile = dir / "snapshots" / "snapshot-00000000000000003567-730.bin"
          _ <- IO.blocking(JFiles.write(olderFile.toNioPath, older))
          usableCalls <- Ref.of[IO, Int](0)
          result <- attemptNode {
            FileLedgerSnapshots
              .open[IO, MirrorState](dir / "snapshots")
              .flatMap(_.restore(_ => usableCalls.update(_ + 1).as(Right(()))))
          }
          _ <- retained(original :+ (olderFile -> older.toVector))
          calls <- usableCalls.get
        yield
          val error = version.fold[StorageError](
            StorageError.UnknownVersion("snapshot format", FileKind.SnapshotFile, identifier)
          )(v =>
            StorageError.RetiredVersion(
              "snapshot format",
              FileKind.SnapshotFile,
              v,
              FormatVersion.V5
            )
          )
          assertEquals(result, Left(error))
          assertEquals(calls, 0)
      }
    }

  test("the damaged-store controls really require recovery writes when opened directly") {
    tempDir.use { dir =>
      for
        original <- seed(dir, header(versions.head._1, versions.head._2))
        immutable <- ok(FileImmutableStore.open[IO](dir / "immutable", 100).use(x => IO.pure(x._2)))
        volatile <- ok(VolatileStore.open[IO](dir / "volatile").use(x => IO.pure(x._2.report)))
      yield
        assert(immutable.truncatedEntries > 0 || immutable.truncatedBytes > 0)
        assert(volatile.truncatedBytes > 0)
        assert(original.exists { (path, bytes) =>
          !JFiles.exists(path.toNioPath) || JFiles.readAllBytes(path.toNioPath).toVector != bytes
        })
    }
  }
