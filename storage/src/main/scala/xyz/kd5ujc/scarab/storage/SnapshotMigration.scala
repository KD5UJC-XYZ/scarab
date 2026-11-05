// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.Sync
import scala.util.Using
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import fs2.io.file.Path
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files as JFiles
import xyz.kd5ujc.scarab.ledger.{FileKind, FormatVersion, StorageError}

/** Registered snapshot migration and read-only compatibility preflight. Retired payload readers are
  * recovered from pinned historical source when converting archived data, not the live node.
  */
trait SnapshotMigration[F[_]]:
  def preflight(dir: Path)(using Raise[F, StorageError]): F[Unit]
  def run(dir: Path)(using Raise[F, StorageError]): F[SnapshotMigration.Report]

object SnapshotMigration:
  /** Check supported snapshot headers before identity admission or mutable store acquisition. */
  def preflight[F[_]: Sync](dir: Path)(using Raise[F, StorageError]): F[Unit] =
    toCurrent[F].preflight(dir)

  /** Identify the registered header without retaining a previous-version payload decoder. */
  private[storage] def versionOf(
      what: String,
      header: Array[Byte]
  ): Either[StorageError, FormatVersion] =
    val magic = new String(header.take(8), StandardCharsets.ISO_8859_1)
    if magic == "CSLSNAP1" && header.length < 12 then
      Left(StorageError.Corrupt(what, SnapshotReadError.VersionHeaderLength(header.length, 12)))
    else
      val identifier =
        if magic == "CSLSNAP1" then s"$magic v${ByteBuffer.wrap(header, 8, 4).getInt}"
        else magic
      FileKind.SnapshotFile.versionOf(what, identifier)

  final case class Refused(name: String, refusal: SnapshotRefusal)
  final case class Report(migrated: List[Migrated], refused: List[Refused])

  object Report:
    val empty: Report = Report(Nil, Nil)

  /** The native snapshot migration registration: V5 files are brought to V7 ([[SnapshotV7.fromV5]]:
    * the V5 body unchanged behind a [[CheckpointBinding.Legacy]] binding), V7 files to V8
    * ([[SnapshotV8.fromV7]]: the sidecar read in the frozen V7 schema and written in the frozen V8
    * one), and V8 files to V9 ([[SnapshotV9.fromV8]]: the sidecar read in the frozen V8 schema and
    * written in the current one), each step atomically. A future format adds its frozen previous
    * reader and atomic conversion here; retired V1–V4 identifiers remain recognized and refuse
    * admission before any mutable store opens, and so does a V5 or V7 file that no migration can
    * convert without inventing state ([[StorageError.ResyncRequired]]).
    */
  def toCurrent[F[_]: Sync]: NodeSnapshotMigration[F] =
    new NodeSnapshotMigration[F]

  final class NodeSnapshotMigration[F[_]: Sync] extends SnapshotMigration[F] with Migration[F]:
    private val F = Sync[F]
    val kind: FileKind = FileKind.SnapshotFile

    private def probe(
        file: Path
    )(using Raise[F, StorageError]): F[Either[StorageError, FormatVersion]] =
      AtomicFile
        .guard[F, Array[Byte]](file.toString) {
          Using.resource(JFiles.newInputStream(file.toNioPath))(_.readNBytes(12))
        }
        .map(versionOf(file.toString, _))

    private def files(dir: Path)(using Raise[F, StorageError]): F[List[String]] =
      F.blocking(JFiles.isDirectory(dir.toNioPath)).flatMap {
        case false => F.pure(Nil)
        case true =>
          AtomicFile.guard[F, List[String]](dir.toString) {
            Using.resource(JFiles.list(dir.toNioPath)) { listing =>
              import scala.jdk.CollectionConverters.*
              listing.iterator.asScala
                .map(_.getFileName.toString)
                .filter(FileLedgerSnapshots.isSnapshot)
                .toList
                .sorted
            }
          }
      }

    private def supported(file: Path)(using
        Raise[F, StorageError]
    ): F[Either[StorageError, FormatVersion]] =
      probe(file).map(_.flatMap(v => kind.readable(file.toString, v).as(v)))

    /** A supported file that its migration can also convert: a V5 or V7 file whose Byron update
      * state lost installer metadata is [[StorageError.ResyncRequired]]. Only the sidecar is read
      * for it; any other problem of the file is its migration step's to report, as before.
      */
    private def admissible(file: Path)(using
        Raise[F, StorageError]
    ): F[Either[StorageError, FormatVersion]] =
      supported(file).flatMap {
        case Right(v @ (FormatVersion.V5 | FormatVersion.V7)) =>
          AtomicFile
            .guard[F, Array[Byte]](file.toString)(sidecarPrefix(file, v))
            .map { prefix =>
              val sidecar =
                if v == FormatVersion.V5 then SnapshotV5.sidecarOf(prefix)
                else SnapshotV7.sidecarOf(prefix)
              sidecar.left.toOption
                .collect { case refusal: StorageError.ResyncRequired => named(file, refusal) }
                .toLeft(v)
            }
        case other => F.pure(other)
      }

    /** A refusal of a file's state, naming the file. */
    private def named(file: Path, error: StorageError): StorageError = error match
      case refusal: StorageError.ResyncRequired => refusal.copy(what = file.toString)
      case other                                => other

    /** The start of a V5 or V7 file up to the end of its sidecar: the magic, the binding (V7) and
      * the sidecar with their lengths. A length out of range reads what there is; the sidecar
      * reader then finds the file corrupt.
      */
    private def sidecarPrefix(file: Path, version: FormatVersion): Array[Byte] =
      Using.resource(JFiles.newInputStream(file.toNioPath)) { in =>
        def counted(): Array[Byte] =
          val length = in.readNBytes(4)
          val count = if length.length == 4 then ByteBuffer.wrap(length).getInt else 0
          length ++ in.readNBytes(math.max(0, count))
        val magic = in.readNBytes(8)
        val binding = if version == FormatVersion.V7 then counted() else Array.emptyByteArray
        magic ++ binding ++ counted()
      }

    private def refusals(
        found: List[(String, Either[StorageError, FormatVersion])]
    )(using Raise[F, StorageError]): F[Unit] =
      // These are bounded-header errors, not current payload corruption. Every refused header must
      // stop identity admission before any backup or rewritten identity can be published.
      found.collectFirst { case (_, Left(error)) => error }.traverse_(_.raise[F, Unit])

    def preflight(dir: Path)(using Raise[F, StorageError]): F[Unit] =
      files(dir)
        .flatMap(_.traverse(name => admissible(dir / name).map(name -> _)))
        .flatMap(refusals)

    /** The version of a supported file that its migration can convert: [[Migrate]] detects every
      * file before it converts any, so a file that would lose state is refused before another is
      * rewritten ([[admissible]]).
      */
    def detect(file: Path)(using Raise[F, StorageError]): F[FormatVersion] =
      admissible(file).flatMap(_.fold(_.raise[F, FormatVersion], F.pure))

    /** Verify the actual source header and the exact registered version transition. Retired or
      * unknown source files refuse before a caller-provided version can bypass admission.
      */
    def step(file: Path, from: FormatVersion, to: FormatVersion)(using
        Raise[F, StorageError]
    ): F[Unit] =
      validateStep(file, from, to) *> ((from, to) match
        case (FormatVersion.V5, FormatVersion.V7) =>
          AtomicFile
            .guard[F, Array[Byte]](file.toString)(JFiles.readAllBytes(file.toNioPath))
            .flatMap(raw =>
              FileLedgerSnapshots
                .verified(raw)
                .flatMap(SnapshotV7.fromV5)
                .fold(
                  e => named(file, e).raise[F, Unit],
                  content =>
                    AtomicFile
                      .replaceAll[F](file, Seq(content, FileLedgerSnapshots.digest(content)))
                )
            )
        case (FormatVersion.V7, FormatVersion.V8) =>
          AtomicFile
            .guard[F, Array[Byte]](file.toString)(JFiles.readAllBytes(file.toNioPath))
            .flatMap(raw =>
              FileLedgerSnapshots
                .verified(raw)
                .flatMap(SnapshotV8.fromV7)
                .fold(
                  e => named(file, e).raise[F, Unit],
                  content =>
                    AtomicFile
                      .replaceAll[F](file, Seq(content, FileLedgerSnapshots.digest(content)))
                )
            )
        case (FormatVersion.V8, FormatVersion.V9) =>
          AtomicFile
            .guard[F, Array[Byte]](file.toString)(JFiles.readAllBytes(file.toNioPath))
            .flatMap(raw =>
              FileLedgerSnapshots
                .verified(raw)
                .flatMap(SnapshotV9.fromV8)
                .fold(
                  e => named(file, e).raise[F, Unit],
                  content =>
                    AtomicFile
                      .replaceAll[F](file, Seq(content, FileLedgerSnapshots.digest(content)))
                )
            )
        case _ =>
          StorageError
            .VersionMismatch(
              file.toString,
              s"${kind.identifier(from)} -> ${kind.identifier(to)}",
              "a registered snapshot conversion"
            )
            .raise[F, Unit])

    def run(dir: Path)(using Raise[F, StorageError]): F[Report] =
      for
        names <- files(dir)
        found <- names.traverse(name => admissible(dir / name).map(name -> _))
        _ <- refusals(found)
        migrated <- Migrate(this, found.collect { case (name, Right(_)) => dir / name })
      yield Report(
        migrated,
        found.collect { case (name, Left(error)) =>
          Refused(name, SnapshotRefusal.Unreadable(error))
        }
      )
