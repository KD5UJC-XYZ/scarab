// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.data.EitherT
import cats.effect.IO
import cats.mtl.{Handle, Raise}
import cats.syntax.all.*
import fs2.io.file.Path
import java.nio.file.Files
import munit.CatsEffectSuite
import xyz.kd5ujc.scarab.ledger.StorageError

class SnapshotPublicationSuite extends CatsEffectSuite:
  private type F[A] = EitherT[IO, LedgerSnapshotImport.Error, A]
  private given Handle[F, LedgerSnapshotImport.Error] =
    Handle.handleEitherT[IO, LedgerSnapshotImport.Error]
  private given Raise[F, StorageError] = new Raise[F, StorageError]:
    def functor: cats.Functor[F] = summon[cats.Functor[F]]
    def raise[E <: StorageError, A](error: E): F[A] =
      EitherT.leftT[IO, A](LedgerSnapshotImport.Error.Storage(error))

  Vector(false, true).foreach { denyCleanup =>
    test(
      s"late filesystem failure under EitherT preserves original error and database bytes (cleanup denied: $denyCleanup)"
    ) {
      StorageTestKit.tempDir.use { root =>
        val db = root / "db"
        val stage = root / "stage"
        val sentinel = Array[Byte](1, 2, 3, 4)
        val failedTarget = db / "missing-parent" / "snapshots"
        val native = LedgerSnapshotImport.Move.native[F]
        val move = new LedgerSnapshotImport.Move[F]:
          def apply(from: Path, to: Path): F[Unit] =
            if to == db / "snapshots" then
              EitherT.liftF[IO, LedgerSnapshotImport.Error, Unit](IO.blocking {
                assert(
                  Files.isDirectory((db / "utxo").toNioPath),
                  "first rename must really have happened"
                )
                if denyCleanup then
                  Files.setPosixFilePermissions(
                    (db / "utxo").toNioPath,
                    java.nio.file.attribute.PosixFilePermissions.fromString("---------")
                  )
                ()
              }) *> native(from, failedTarget)
            else native(from, to)
        for
          _ <- IO.blocking {
            Files.createDirectories((db / "immutable").toNioPath)
            Files.write((db / "immutable" / "existing.chunk").toNioPath, sentinel)
            Files.createDirectories((stage / "artifacts" / "utxo" / "checkpoints").toNioPath)
            Files.write((stage / "artifacts" / "utxo" / "checkpoint").toNioPath, Array[Byte](5))
            Files.createDirectories((stage / "artifacts" / "snapshots").toNioPath)
            Files.write(
              (stage / "artifacts" / "snapshots" / "snapshot.bin").toNioPath,
              Array[Byte](6)
            )
          }
          result <- LedgerSnapshotImport.publish[F](stage, db, move).value
          _ <- IO.blocking {
            // Restore our deliberately denied directory so the test resource can remove its orphan.
            if Files.exists((db / "utxo").toNioPath) then
              Files.setPosixFilePermissions(
                (db / "utxo").toNioPath,
                java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")
              )
          }
        yield
          assert(
            result.left.exists {
              case LedgerSnapshotImport.Error.Storage(StorageError.Io(what, _)) =>
                what == failedTarget.toString
              case _ => false
            },
            result.toString
          )
          assertEquals(Files.exists((db / "utxo").toNioPath), denyCleanup)
          assert(!Files.exists((db / "snapshots").toNioPath))
          assert(
            Files
              .readAllBytes((db / "immutable" / "existing.chunk").toNioPath)
              .sameElements(sentinel)
          )
          assert(Files.isDirectory((stage / "artifacts" / "snapshots").toNioPath))
      }
    }
  }
