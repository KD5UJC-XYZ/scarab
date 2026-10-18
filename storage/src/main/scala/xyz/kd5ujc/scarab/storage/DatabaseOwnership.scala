// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.{Resource, Sync}
import cats.effect.syntax.all.*
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import fs2.io.file.Path
import java.nio.channels.{FileChannel, FileLock, OverlappingFileLockException}
import java.nio.file.{Files, StandardOpenOption}
import xyz.kd5ujc.scarab.ledger.StorageError

/** One offline writer or follower owns a database. The stable lock inode is beside the database, so
  * refusing an import does not add anything to its contents. It is never unlinked: another process
  * may already have opened the same inode while waiting for its owner to finish.
  */
object DatabaseOwnership:
  def resource[F[_]: Sync](db: Path)(using Raise[F, StorageError]): Resource[F, Unit] =
    val F = Sync[F]
    val acquire = F
      .blocking {
        val requested = db.toNioPath.toAbsolutePath.normalize()
        Files.createDirectories(requested.getParent)
        val absolute = if Files.exists(requested) then requested.toRealPath() else requested
        val parent = absolute.getParent.toRealPath()
        val lock = parent.resolve(s".${absolute.getFileName}.scarab-lock")
        val channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        try
          val owned =
            try Option(channel.tryLock())
            catch case _: OverlappingFileLockException => None
          owned match
            case Some(lease) => Right((channel, lease))
            case None =>
              channel.close()
              Left(StorageError.DatabaseInUse(db.toString))
        catch
          case scala.util.control.NonFatal(e) =>
            channel.close()
            Left(StorageError.Io(lock.toString, e.toString))
      }
      .attempt
      .flatMap {
        case Right(result) => result.fold(_.raise[F, (FileChannel, FileLock)], F.pure)
        case Left(e) => StorageError.Io(db.toString, e.toString).raise[F, (FileChannel, FileLock)]
      }
    Resource
      .make(acquire) { (channel, lease) =>
        F.blocking(lease.release()).guarantee(F.blocking(channel.close()))
      }
      .void
