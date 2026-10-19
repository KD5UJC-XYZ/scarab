// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.Monad
import cats.effect.Sync
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import fs2.io.file.Path
import java.nio.channels.FileChannel
import java.nio.file.{Files, StandardCopyOption, StandardOpenOption}
import xyz.kd5ujc.scarab.ledger.{FileKind, FormatVersion, StorageError}

/** What a migration did: `count` files of `kind` found at `from` and brought to `to`. This is what
  * the JSON log reports (`formatMigrated`).
  */
final case class Migrated(kind: FileKind, from: FormatVersion, to: FormatVersion, count: Int)

/** How the files of one [[FileKind]] are read and rewritten: the part of a format migration that is
  * specific to the format. The generic part, [[Migrate]], runs it over the files of a database.
  *
  * A step rewrites a file from `from` to the next changed version `to` and must be atomic (see
  * [[AtomicFile]]): after a crash the file is entirely at `v` or entirely at `v.next`, so a restart
  * finds every file at some version and carries on. Reading a file's version raises
  * [[StorageError.UnknownVersion]] for a version this build does not know. Later kinds (the ledger
  * snapshots, issue #83) add a `Migration` and reuse [[Migrate]].
  */
trait Migration[F[_]]:
  def kind: FileKind

  /** The next on-disk version written by one step. Most formats use the next changed version;
    * formats whose converter writes directly to the current version can override this.
    */
  def nextVersion(from: FormatVersion): Option[FormatVersion] = kind.nextChangedVersion(from)

  /** The version `file` is at (only its start is read). */
  def detect(file: Path)(using cats.mtl.Raise[F, StorageError]): F[FormatVersion]

  /** Rewrite `file`, which is at `from`, as `from.next`. */
  def step(file: Path, from: FormatVersion, to: FormatVersion)(using
      cats.mtl.Raise[F, StorageError]
  ): F[Unit]

  /** A step is called only for the exact next changed version, and only when the on-disk source
    * still has the version discovered during preflight.
    */
  protected def validateStep(file: Path, from: FormatVersion, to: FormatVersion)(using
      cats.Monad[F],
      Raise[F, StorageError]
  ): F[Unit] =
    detect(file).flatMap { actual =>
      if actual != from then
        StorageError
          .VersionMismatch(file.toString, kind.identifier(actual), kind.identifier(from))
          .raise[F, Unit]
      else if nextVersion(from).contains(to) then cats.Monad[F].unit
      else
        StorageError
          .VersionMismatch(
            file.toString,
            s"${kind.identifier(from)} -> ${kind.identifier(to)}",
            "next changed version"
          )
          .raise[F, Unit]
    }

/** Bring files to the current version of their kind ([[FileKind.current]]), one step at a time,
  * each step atomic. A kind that did not change at a newer shared version is not touched by it.
  */
object Migrate:

  /** Migrate `files`. The version of every file is read first, so a file of an unknown version
    * refuses the whole run before anything is rewritten; then each file is stepped to the current
    * version in turn. The result groups the files that had to move by the version they were at
    * (nothing to report when everything was current, which is every run after the first).
    */
  def apply[F[_]: Monad](migration: Migration[F], files: List[Path])(using
      cats.mtl.Raise[F, StorageError]
  ): F[List[Migrated]] =
    val current = migration.kind.current
    def steps(file: Path, at: FormatVersion): F[Unit] =
      Monad[F].tailRecM(at) { version =>
        migration.nextVersion(version).filter(_ <= current) match
          case Some(next) => migration.step(file, version, next).as(Left(next))
          case None       => Monad[F].pure(Right(()))
      }
    for
      found <- files.traverse(file => migration.detect(file).map(file -> _))
      moving = found.filter(_._2 != current)
      _ <- moving.traverse_ { case (file, at) => steps(file, at) }
    yield moving
      .groupMapReduce(_._2)(_ => 1)(_ + _)
      .toList
      .sortBy(_._1)
      .map((from, count) => Migrated(migration.kind, from, current, count))

/** Writing a file so that a crash leaves either the old content or the new, never a mixture. */
object AtomicFile:

  /** Run `body`, an operating-system action on `what`, mapping its exceptions to
    * [[StorageError.Io]].
    */
  def guard[F[_]: Sync, A](what: String)(body: => A)(using Raise[F, StorageError]): F[A] =
    Sync[F].blocking(body).attempt.flatMap {
      case Right(a) => Sync[F].pure(a)
      case Left(e)  => StorageError.Io(what, e).raise[F, A]
    }

  /** Replace `file` by `bytes`: a temporary file next to it (the same directory, so the rename
    * cannot cross a file system), fsync of the data, atomic rename over the original, fsync of the
    * directory. The temporary file is `<name>.tmp`; one left by a crash is overwritten by the next
    * attempt and is not a file of any kind the store reads.
    */
  def replace[F[_]: Sync](file: Path, bytes: Array[Byte])(using Raise[F, StorageError]): F[Unit] =
    replaceAll[F](file, Seq(bytes))

  /** As [[replace]] for a file that is the concatenation of `parts`, written in turn: a file made
    * of a large content and a small trailer needs no copy of the content to put them together.
    */
  def replaceAll[F[_]: Sync](file: Path, parts: Seq[Array[Byte]])(using
      Raise[F, StorageError]
  ): F[Unit] =
    val target = file.toNioPath
    val tmp = target.resolveSibling(target.getFileName.toString + ".tmp")
    guard[F, Unit](file.toString) {
      val ch = FileChannel.open(
        tmp,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE
      )
      try
        parts.foreach { bytes =>
          val buffer = java.nio.ByteBuffer.wrap(bytes)
          while buffer.hasRemaining do ch.write(buffer)
        }
        ch.force(true)
      finally ch.close()
      Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
      val dir = FileChannel.open(target.getParent, StandardOpenOption.READ)
      try dir.force(true)
      finally dir.close()
    }
