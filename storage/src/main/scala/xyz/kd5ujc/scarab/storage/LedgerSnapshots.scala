// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import xyz.kd5ujc.scarab.ledger.StorageStructureError
import cats.effect.{Async, Sync}
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import fs2.io.file.{Files, Path}
import java.security.MessageDigest
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.ledger.{CheckpointProblem, StorageError}

/** The node state at an immutable point: `anchor` is the newest block applied (none: genesis), and
  * `checkpoint` what the snapshot records of the store that holds the rest of the state
  * ([[CheckpointBinding]]).
  */
final case class StateSnapshot[S](anchor: Option[BlockRef], state: S, checkpoint: CheckpointBinding)

/** Periodic snapshots of the full node state: the LedgerDB snapshots of ouroboros-consensus. */
trait LedgerSnapshots[F[_], S]:
  /** Write atomically (temp file, fsync, rename, fsync of the directory), then prune old ones. A
    * state the format refuses is not written (and nothing is pruned): the refusal is the result.
    */
  def write(snapshot: StateSnapshot[S])(using
      Raise[F, StorageError]
  ): F[Either[SnapshotRefusal, LedgerSnapshots.Written]]

  /** The newest snapshot that decodes, passes its checksum and that `usable` admits (its anchor is
    * stored, its checkpoint is the one it was written with); `usable` gives the reason it refuses
    * one. A snapshot not used is reported and the next older one tried, and deleted when the
    * reference deletes it ([[LedgerSnapshots.Fallback.of]]). Unknown or retired format versions
    * raise a storage error, preventing fallback replay from bypassing format admission.
    */
  def restore(usable: StateSnapshot[S] => F[Either[StorageError, Unit]])(using
      cats.mtl.Raise[F, StorageError]
  ): F[LedgerSnapshots.Restored[S]]

  /** File names, newest first. */
  def list(using Raise[F, StorageError]): F[Vector[String]]

object LedgerSnapshots:
  final case class Written(name: String, bytes: Long, anchor: Option[BlockRef])

  /** A snapshot the restore did not use, why, and what became of it. */
  final case class Skipped(name: String, error: StorageError, unused: Unused)

  /** What became of a snapshot the restore did not use. */
  enum Unused derives CanEqual:
    /** Deleted; its checkpoint goes by the open's best-effort trim ([[StateBacking.trim]]). */
    case Deleted

    /** Not deleted: the deletion failed (`cause`), and the fallback went on, as the reference's
      * `defaultDeleteSnapshotIfTemporary` catches every exception of its deletion
      * (ouroboros-consensus 82ecba32 `Storage/LedgerDB/Snapshots.hs:320-330`).
      */
    case NotDeleted(cause: StorageError)

    /** Kept on disk. */
    case Kept

  /** What the restore does with a snapshot it refuses with an error: what the reference's
    * `tryNewestFirst` does (ouroboros-consensus 82ecba32 `Storage/LedgerDB/API.hs:599-646`). A
    * snapshot ahead of the immutable tip, or one whose loading returns a failure other than three,
    * is deleted if it is temporary (`deleteSnapshotIfTemporary`, `:600-604, 625-631`) and the next
    * older one is tried; one without its metadata file, of another backend or of the legacy layout
    * is kept (`:607-623`). What the reference raises instead of returning, a file system error
    * above all (`readIncremental` opens the file outside its `ExceptT`, `Util/CBOR.hs:181-230`),
    * escapes `tryNewestFirst` and stops the start: nothing is deleted for it. A snapshot is
    * temporary when its name has no suffix (`diskSnapshotIsTemporary`,
    * `Storage/LedgerDB/Snapshots.hs:288-297`): every native snapshot is
    * `snapshot-<slot>-<blockNo>.bin`, the node's, an import's and an SCLS import's alike, so every
    * one is temporary.
    */
  enum Fallback derives CanEqual:
    /** Delete it and try the next older one. */
    case Delete

    /** Keep it and try the next older one. */
    case Keep

    /** Stop the start with the error. */
    case Stop

  object Fallback:
    /** What the reference does with what `error` corresponds to. The line between the reference's
      * cases and Scarab's envelope: the format version of the file is Scarab's own and ADR 0006
      * governs it, so an unknown or retired version, or a previous file whose state cannot be
      * converted, stops the start, and a binding, a sidecar or a format of another version or
      * schema (`VersionMismatch`, a previous file the migration could not convert) is kept.
      *
      *   - An anchor missing from the immutable store is the reference's too-recent snapshot:
      *     deleted (`:600-604`).
      *   - A checksum, a framing, a sidecar or a state that does not decode, and a state recorded
      *     under a build of another layout or an unknown one, are `ReadSnapshotDataCorruption` and
      *     `ReadSnapshotFailed`: deleted (`:625-631`).
      *   - A checkpoint whose digest is not the recorded one, or that holds keys no layout has, is
      *     a tables file that fails its check or does not decode: deleted. A checkpoint of another
      *     key layout, or none where this node keeps a store, is the reference's
      *     `MetadataBackendMismatch`: kept (`:613-617`).
      *   - An absent checkpoint is the reference's missing tables file, which `readIncremental`
      *     raises (`Util/CBOR.hs:206`): it stops the start, and so does a file that cannot be read
      *     (`Io`), a decoder defect, and any other error.
      */
    def of(error: StorageError): Fallback = error match
      case _: StorageError.Corrupt[?] | _: StorageError.Missing |
          _: StorageError.ReferenceBuildMismatch | _: StorageError.UnknownReferenceBuild =>
        Delete
      case StorageError.UtxoCheckpoint(
            _,
            CheckpointProblem.DigestMismatch(_, _) | CheckpointProblem.MixedKeyLayouts |
            CheckpointProblem.IndexTooLarge(_) | CheckpointProblem.UnknownKeyLength(_)
          ) =>
        Delete
      case _: StorageError.VersionMismatch => Keep
      case StorageError.UtxoCheckpoint(
            _,
            CheckpointProblem.LayoutMismatch(_, _) | CheckpointProblem.Unbound
          ) =>
        Keep
      case _ => Stop

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
  * [[SnapshotV10]]); the store adds the checksum, atomic write, pruning and restore. Corrupt files
  * can be skipped; known retired or unknown versions refuse restoration rather than cause replay.
  * Opening this generic store tidies temporary files: callers requiring admission before any
  * mutation must first run [[ChainStorage.preflight]], as the node does.
  */
object FileLedgerSnapshots:
  val DigestSize = 32
  private val Prefix = "snapshot-"
  private val Suffix = ".bin"

  private def sha256(bytes: Array[Byte]): Array[Byte] =
    MessageDigest.getInstance("SHA-256").digest(bytes)

  /** The checksum that closes the file of a content. */
  def digest(content: Array[Byte]): Array[Byte] = sha256(content)

  /** The content of a file whose checksum holds: the file without its last [[DigestSize]] bytes. */
  def verified(file: Array[Byte]): Either[StorageError, Array[Byte]] =
    if file.length < DigestSize then
      Left(StorageError.Corrupt("snapshot", SnapshotReadError.FileTooShort(file.length)))
    else
      val content = file.take(file.length - DigestSize)
      Either.cond(
        java.util.Arrays.equals(sha256(content), file.drop(file.length - DigestSize)),
        content,
        StorageError.Corrupt("snapshot", SnapshotReadError.ChecksumMismatch)
      )

  /** The file of a content: the content and its checksum. */
  def closed(content: Array[Byte]): Array[Byte] = content ++ sha256(content)

  /** The name of the file of the snapshot at `a`. */
  def nameOf(a: BlockRef): String = f"$Prefix${a.slot}%020d-${a.blockNo}$Suffix"

  /** Whether `name` is the name of a snapshot file. */
  def isSnapshot(name: String): Boolean = name.startsWith(Prefix) && name.endsWith(Suffix)

  /** Delete `file`, a temporary snapshot the restore does not use, best effort: a failure is
    * [[LedgerSnapshots.Unused.NotDeleted]], never an error, as the reference's
    * `defaultDeleteSnapshotIfTemporary` catches the exceptions of its deletion (ouroboros-consensus
    * 82ecba32 `Storage/LedgerDB/Snapshots.hs:320-330`).
    */
  private[storage] def deleted[F[_]: Sync](file: Path): F[LedgerSnapshots.Unused] =
    Sync[F]
      .blocking(java.nio.file.Files.deleteIfExists(file.toNioPath))
      .attempt
      .map(
        _.fold(
          e => LedgerSnapshots.Unused.NotDeleted(StorageError.Io(file.toString, e)),
          _ => LedgerSnapshots.Unused.Deleted
        )
      )

  def open[F[_]: Async, S](dir: Path, keep: Int = 3)(using
      format: SnapshotFormat[F, S],
      raise: Raise[F, StorageError]
  ): F[LedgerSnapshots[F, S]] =
    val F = Async[F]
    val files = Files.forAsync[F]

    def fail[A](e: StorageError)(using Raise[F, StorageError]): F[A] = e.raise[F, A]
    def guard[A](what: String)(fa: F[A])(using Raise[F, StorageError]): F[A] =
      fa.attempt.flatMap(_.fold(e => fail(StorageError.Io(what, e)), F.pure))

    def names(using Raise[F, StorageError]): F[Vector[String]] =
      guard(dir.toString)(
        files
          .list(dir)
          .map(_.fileName.toString)
          .filter(isSnapshot)
          .compile
          .toVector
          .map(_.sorted.reverse)
      )

    def readFile(
        name: String
    )(using cats.mtl.Raise[F, StorageError]): F[Either[StorageError, StateSnapshot[S]]] =
      F.blocking(java.nio.file.Files.readAllBytes((dir / name).toNioPath)).attempt.flatMap {
        case Left(e) => F.pure(Left(StorageError.Io(name, e)))
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
      def list(using Raise[F, StorageError]): F[Vector[String]] = names

      def write(snapshot: StateSnapshot[S])(using
          Raise[F, StorageError]
      ): F[Either[SnapshotRefusal, LedgerSnapshots.Written]] =
        snapshot.anchor match
          case None => fail(StorageError.OutOfOrder(StorageStructureError.GenesisSnapshot))
          case Some(anchor) =>
            val name = nameOf(anchor)
            format.encode(snapshot).flatMap {
              case Left(SnapshotRefusal.WouldNotRead(error)) if DecodeFailure.terminal(error) =>
                fail(error)
              case Left(refusal)  => F.pure(Left(refusal))
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

      def restore(usable: StateSnapshot[S] => F[Either[StorageError, Unit]])(using
          cats.mtl.Raise[F, StorageError]
      ): F[LedgerSnapshots.Restored[S]] =
        /** Skip `name`, refused with `error`, deleting it or stopping where the reference would. */
        def refused(
            name: String,
            error: StorageError,
            tail: List[String],
            skipped: Vector[LedgerSnapshots.Skipped]
        ): F[LedgerSnapshots.Restored[S]] =
          LedgerSnapshots.Fallback.of(error) match
            case LedgerSnapshots.Fallback.Stop => fail(error)
            case LedgerSnapshots.Fallback.Keep =>
              go(tail, skipped :+ LedgerSnapshots.Skipped(name, error, LedgerSnapshots.Unused.Kept))
            case LedgerSnapshots.Fallback.Delete =>
              FileLedgerSnapshots
                .deleted[F](dir / name)
                .flatMap(unused =>
                  go(tail, skipped :+ LedgerSnapshots.Skipped(name, error, unused))
                )
        def go(
            rest: List[String],
            skipped: Vector[LedgerSnapshots.Skipped]
        ): F[LedgerSnapshots.Restored[S]] = rest match
          case Nil => F.pure(LedgerSnapshots.Restored(None, skipped))
          case name :: tail =>
            readFile(name).flatMap {
              case Left(error) => refused(name, error, tail, skipped)
              case Right(snapshot) =>
                usable(snapshot).flatMap {
                  case Right(()) =>
                    F.pure(LedgerSnapshots.Restored(Some(name -> snapshot), skipped))
                  case Left(error) => refused(name, error, tail, skipped)
                }
            }
        names.flatMap(ns => go(ns.toList, Vector.empty)))
