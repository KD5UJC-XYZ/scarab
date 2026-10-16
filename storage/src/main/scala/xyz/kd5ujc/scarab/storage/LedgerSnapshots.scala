// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.Async
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import fs2.io.file.{Files, Path}
import java.security.MessageDigest
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.ledger.StorageError

/** The node state at an immutable point: `anchor` is the newest block applied (none: genesis). */
final case class StateSnapshot[S](anchor: Option[BlockRef], state: S)

/** Periodic snapshots of the full node state: the LedgerDB snapshots of ouroboros-consensus. */
trait LedgerSnapshots[F[_], S]:
  /** Write atomically (temp file, fsync, rename, fsync of the directory), then prune old ones. A
    * state the format refuses is not written (and nothing is pruned): the refusal is the result.
    */
  def write(snapshot: StateSnapshot[S]): F[Either[SnapshotRefusal, LedgerSnapshots.Written]]

  /** The newest snapshot that decodes, passes its checksum and whose anchor `usable` accepts.
    * Snapshots that fail are reported and skipped, never raised.
    */
  def restore(usable: Option[BlockRef] => F[Boolean]): F[LedgerSnapshots.Restored[S]]

  /** File names, newest first. */
  def list: F[Vector[String]]

object LedgerSnapshots:
  final case class Written(name: String, bytes: Long, anchor: Option[BlockRef])
  final case class Skipped(name: String, error: StorageError)
  final case class Restored[S](
      snapshot: Option[(String, StateSnapshot[S])],
      skipped: Vector[Skipped]
  )

/** File layout: `snapshot-<slot, 20 digits>-<blockNo>.bin` holding
  *
  * {{{
  * content | SHA-256 of the content
  * }}}
  *
  * The content is a [[SnapshotFormat]]'s and starts with the magic that names the format (see
  * [[SnapshotV4]]); the store adds the checksum and does the rest: the atomic write, the pruning,
  * and the restore of the newest snapshot that reads. A file of an older format or schema fails
  * with `VersionMismatch` instead of decoding garbage; recovery then falls back to an older
  * snapshot or, with none usable, to replaying the immutable blocks from genesis. Files of the
  * older formats are brought to the current one before the store is opened ([[SnapshotMigration]]).
  */
object FileLedgerSnapshots:
  val DigestSize = 32
  private val Prefix = "snapshot-"
  private val Suffix = ".bin"

  private def sha256(bytes: Array[Byte]): Array[Byte] =
    MessageDigest.getInstance("SHA-256").digest(bytes)

  /** The content of a file whose checksum holds: the file without its last [[DigestSize]] bytes. */
  def verified(file: Array[Byte]): Either[StorageError, Array[Byte]] =
    if file.length < DigestSize then
      Left(StorageError.Corrupt("snapshot", s"file of ${file.length} bytes is too short"))
    else
      val content = file.take(file.length - DigestSize)
      Either.cond(
        java.util.Arrays.equals(sha256(content), file.drop(file.length - DigestSize)),
        content,
        StorageError.Corrupt("snapshot", "checksum mismatch")
      )

  /** The file of a content: the content and its checksum. */
  def closed(content: Array[Byte]): Array[Byte] = content ++ sha256(content)

  /** The name of the file of the snapshot at `a`. */
  def nameOf(a: BlockRef): String = f"$Prefix${a.slot}%020d-${a.blockNo}$Suffix"

  /** Whether `name` is the name of a snapshot file. */
  def isSnapshot(name: String): Boolean = name.startsWith(Prefix) && name.endsWith(Suffix)

  def open[F[_]: Async, S](dir: Path, keep: Int = 3)(using
      format: SnapshotFormat[F, S],
      raise: Raise[F, StorageError]
  ): F[LedgerSnapshots[F, S]] =
    val F = Async[F]
    val files = Files.forAsync[F]

    def fail[A](e: StorageError): F[A] = e.raise[F, A]
    def guard[A](what: String)(fa: F[A]): F[A] =
      fa.attempt.flatMap(_.fold(e => fail(StorageError.Io(what, String.valueOf(e))), F.pure))

    def names: F[Vector[String]] =
      guard(dir.toString)(
        files
          .list(dir)
          .map(_.fileName.toString)
          .filter(isSnapshot)
          .compile
          .toVector
          .map(_.sorted.reverse)
      )

    def readFile(name: String): F[Either[StorageError, StateSnapshot[S]]] =
      F.blocking(java.nio.file.Files.readAllBytes((dir / name).toNioPath)).attempt.flatMap {
        case Left(e) => F.pure(Left(StorageError.Io(name, String.valueOf(e))))
        case Right(file) =>
          verified(file).fold(e => F.pure(Left(e)), content => format.decode(content))
      }

    val tidy: F[Unit] =
      guard(dir.toString)(
        files.createDirectories(dir) *>
          files
            .list(dir)
            .filter(_.fileName.toString.endsWith(".tmp"))
            .evalMap(files.deleteIfExists)
            .compile
            .drain
      )

    tidy.as(new LedgerSnapshots[F, S]:
      def list: F[Vector[String]] = names

      def write(snapshot: StateSnapshot[S]): F[Either[SnapshotRefusal, LedgerSnapshots.Written]] =
        snapshot.anchor match
          case None => fail(StorageError.OutOfOrder("a genesis snapshot is never written"))
          case Some(anchor) =>
            val name = nameOf(anchor)
            format.encode(snapshot).flatMap {
              case Left(refusal) => F.pure(Left(refusal))
              case Right(content) =>
                // the checksum is written after the content, not appended to a copy of it
                val size = content.length.toLong + DigestSize
                for
                  _ <- AtomicFile.replaceAll[F](dir / name, Seq(content, sha256(content)))
                  all <- names
                  _ <- guard(dir.toString)(
                    all.drop(keep).traverse_(n => files.deleteIfExists(dir / n))
                  )
                yield Right(LedgerSnapshots.Written(name, size, snapshot.anchor))
            }

      def restore(usable: Option[BlockRef] => F[Boolean]): F[LedgerSnapshots.Restored[S]] =
        def go(
            rest: List[String],
            skipped: Vector[LedgerSnapshots.Skipped]
        ): F[LedgerSnapshots.Restored[S]] = rest match
          case Nil => F.pure(LedgerSnapshots.Restored(None, skipped))
          case name :: tail =>
            readFile(name).flatMap {
              case Left(error) => go(tail, skipped :+ LedgerSnapshots.Skipped(name, error))
              case Right(snapshot) =>
                usable(snapshot.anchor).flatMap {
                  case true => F.pure(LedgerSnapshots.Restored(Some(name -> snapshot), skipped))
                  case false =>
                    go(
                      tail,
                      skipped :+ LedgerSnapshots.Skipped(
                        name,
                        StorageError.Missing("snapshot anchor in the immutable store")
                      )
                    )
                }
            }
        names.flatMap(ns => go(ns.toList, Vector.empty)))
