// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.data.EitherT
import cats.effect.{IO, Resource}
import cats.mtl.{Handle, Raise}
import cats.syntax.all.*
import fs2.io.file.Path
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import munit.CatsEffectSuite
import xyz.kd5ujc.scarab.ledger.StorageError
import xyz.kd5ujc.scarab.ledger.state.Gap

class SnapshotExportPublicationSuite extends CatsEffectSuite:
  private type F[A] = EitherT[IO, LedgerSnapshotExport.Error, A]
  private given Handle[F, LedgerSnapshotExport.Error] =
    Handle.handleEitherT[IO, LedgerSnapshotExport.Error]
  private given Raise[F, StorageError] = new Raise[F, StorageError]:
    def functor: cats.Functor[F] = summon[cats.Functor[F]]
    def raise[E <: StorageError, A](error: E): F[A] =
      EitherT.leftT[IO, A](LedgerSnapshotExport.Error.Storage(error))

  private def lift[A](action: IO[A]): F[A] = EitherT.liftF(action)
  private def prepare(stage: Path, out: Path): IO[Unit] = IO.blocking {
    Files.createDirectories(stage.toNioPath)
    Vector("state", "tables", "meta").zipWithIndex.foreach { (name, n) =>
      Files.write((stage / name).toNioPath, Array(n.toByte))
    }
    Files.write((out / "unrelated").toNioPath, Array[Byte](42))
    ()
  }
  private def stageResource(stage: Path): Resource[F, Unit] =
    Resource.make(lift(IO.unit))(_ =>
      AtomicFile.guard[F, Unit](stage.toString)(RocksUtxoStore.deleteTree(stage.toNioPath))
    )
  private def chmod(path: Path, mode: String): IO[Unit] = IO.blocking {
    Files.setPosixFilePermissions(path.toNioPath, PosixFilePermissions.fromString(mode))
    ()
  }

  test("a primary domain Left survives simultaneous native stage-cleanup failure under EitherT") {
    StorageTestKit.tempDir.use { root =>
      val out = root / "out"
      val stage = out / ".scarab-export-test"
      val primary: LedgerSnapshotExport.Error = LedgerSnapshotExport.Error.Gaps(Set(Gap.NonMyopic))
      for
        _ <- prepare(stage, out)
        _ <- chmod(stage, "---------")
        result <- stageResource(stage)
          .use(_ => EitherT.leftT[IO, Unit](primary))
          .value
          .guarantee(chmod(stage, "rwx------"))
      yield assertEquals(result, Left(primary))
    }
  }

  test("typed fallback-finalizer cleanup is best effort under EitherT") {
    StorageTestKit.tempDir.use { root =>
      val out = root / "out"
      val stage = out / ".scarab-export-test"
      for
        _ <- prepare(stage, out)
        _ <- chmod(stage, "---------")
        result <- stageResource(stage)
          .use(_ => EitherT.rightT[IO, LedgerSnapshotExport.Error](()))
          .value
          .guarantee(chmod(stage, "rwx------"))
      yield
        // MonadCancel's error channel is Throwable, not EitherT's domain Left. Required cleanup
        // therefore belongs in the normal effect before publication, not only in this fallback.
        assertEquals(result, Right(()))
        assert(Files.isDirectory(stage.toNioPath))
    }
  }

  test("required cleanup in the normal effect reports failure despite a failing fallback") {
    StorageTestKit.tempDir.use { root =>
      val out = root / "out"
      val stage = out / ".scarab-export-test"
      for
        _ <- prepare(stage, out)
        _ <- chmod(stage, "---------")
        result <- stageResource(stage)
          .use(_ =>
            AtomicFile.guard[F, Unit](stage.toString)(RocksUtxoStore.deleteTree(stage.toNioPath))
          )
          .value
          .guarantee(chmod(stage, "rwx------"))
      yield assert(
        result.left.exists {
          case LedgerSnapshotExport.Error.Storage(StorageError.Io(what, _)) =>
            what == stage.toString
          case _ => false
        },
        result.toString
      )
    }
  }

  test("successful whole-stage publication cannot fail during later owned-stage cleanup") {
    StorageTestKit.tempDir.use { root =>
      val out = root / "out"
      val stage = out / ".scarab-export-test"
      val target = out / "123_scarab"
      val native = LedgerSnapshotExport.DirectorySync.native[F]
      val sync = new LedgerSnapshotExport.DirectorySync[F]:
        def apply(path: Path): F[Unit] =
          native(path) *> (if path == out then lift(chmod(out, "r-x------"))
                           else EitherT.rightT[IO, LedgerSnapshotExport.Error](()))
      for
        _ <- prepare(stage, out)
        result <- stageResource(stage)
          .use(_ => LedgerSnapshotExport.publish[F](stage, out, target, sync))
          .value
          .guarantee(chmod(out, "rwx------"))
      yield
        assertEquals(result, Right(()))
        assert(!Files.exists(stage.toNioPath))
        assert(Files.isDirectory(target.toNioPath))
        assertEquals(Files.readAllBytes((out / "unrelated").toNioPath).toVector, Vector(42.toByte))
        Vector("state", "tables", "meta").foreach(name =>
          assert(Files.isRegularFile((target / name).toNioPath))
        )
    }
  }

  Vector(false, true).foreach { denyCleanup =>
    test(
      s"typed post-rename sync failure retains its original error (cleanup denied: $denyCleanup)"
    ) {
      StorageTestKit.tempDir.use { root =>
        val out = root / "out"
        val stage = out / ".scarab-export-test"
        val target = out / "123_scarab"
        val absent = out / "absent-sync-directory"
        val native = LedgerSnapshotExport.DirectorySync.native[F]
        val sync = new LedgerSnapshotExport.DirectorySync[F]:
          def apply(path: Path): F[Unit] =
            if path != out then native(path)
            else
              lift(IO(assert(Files.isDirectory(target.toNioPath), "rename must have happened"))) *>
                (if denyCleanup then lift(chmod(target, "---------"))
                 else EitherT.rightT[IO, LedgerSnapshotExport.Error](())) *>
                native(absent)
        for
          _ <- prepare(stage, out)
          result <- stageResource(stage)
            .use(_ => LedgerSnapshotExport.publish[F](stage, out, target, sync))
            .value
            .guarantee(
              IO.blocking(Files.exists(target.toNioPath))
                .flatMap(exists => if exists then chmod(target, "rwx------") else IO.unit)
            )
        yield
          assert(result.left.exists {
            case LedgerSnapshotExport.Error.Storage(StorageError.Io(what, _)) =>
              what == absent.toString
            case _ => false
          })
          assertEquals(Files.exists(target.toNioPath), denyCleanup)
          assert(!Files.exists(stage.toNioPath))
          assertEquals(
            Files.readAllBytes((out / "unrelated").toNioPath).toVector,
            Vector(42.toByte)
          )
      }
    }
  }
