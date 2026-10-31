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
    * the V5 body unchanged behind a [[CheckpointBinding.Legacy]] binding), each atomically. A
    * future format adds its frozen previous reader and atomic conversion here; retired V1–V4
    * identifiers remain recognized and refuse admission before any mutable store opens.
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

    private def refusals(
        found: List[(String, Either[StorageError, FormatVersion])]
    )(using Raise[F, StorageError]): F[Unit] =
      // These are bounded-header errors, not current payload corruption. Every refused header must
      // stop identity admission before any backup or rewritten identity can be published.
      found.collectFirst { case (_, Left(error)) => error }.traverse_(_.raise[F, Unit])

    def preflight(dir: Path)(using Raise[F, StorageError]): F[Unit] =
      files(dir)
        .flatMap(_.traverse(name => supported(dir / name).map(name -> _)))
        .flatMap(refusals)

    def detect(file: Path)(using Raise[F, StorageError]): F[FormatVersion] =
      supported(file).flatMap(_.fold(_.raise[F, FormatVersion], F.pure))

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
                  _.raise[F, Unit],
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
        found <- names.traverse(name => supported(dir / name).map(name -> _))
        _ <- refusals(found)
        migrated <- Migrate(this, found.collect { case (name, Right(_)) => dir / name })
      yield Report(
        migrated,
        found.collect { case (name, Left(error)) =>
          Refused(name, SnapshotRefusal.Unreadable(error))
        }
      )
