// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import xyz.kd5ujc.scarab.ledger.StorageStructureError
import cats.effect.Sync
import cats.mtl.Ask
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import fs2.io.file.Path
import java.nio.charset.StandardCharsets
import java.nio.file.Files as JFiles
import xyz.kd5ujc.scarab.consensus.state.ConsensusMirrorEnv
import xyz.kd5ujc.scarab.ledger.{FileKind, FormatVersion, StorageError}
import xyz.kd5ujc.scarab.legacy

/** Bringing the snapshot files of a database to the current format ([[FileKind.SnapshotFile]]),
  * before the snapshots are opened. A migration is one-way: it rewrites the files, atomically and
  * one at a time, and does not keep the old ones (see docs/running.md for keeping a copy).
  */
trait SnapshotMigration[F[_]]:
  def run(dir: Path): F[SnapshotMigration.Report]

object SnapshotMigration:
  /** A snapshot left as it was, because this build could not bring it to the current format: it is
    * skipped when the snapshots are opened, as any snapshot that does not read.
    */
  final case class Refused(name: String, refusal: SnapshotRefusal)

  /** What a run did: the files it rewrote, grouped by the version they were at, and the ones it
    * could not.
    */
  final case class Report(migrated: List[Migrated], refused: List[Refused])

  object Report:
    val empty: Report = Report(Nil, Nil)

  /** The migration of the snapshots of the node state to the current format (V5): a version 1 file
    * (written before the packages were renamed) and a version 2 file are decoded with the frozen
    * reader of the `legacy` module and brought to the mirror, a version 3 or 4 file has its Haskell
    * section decoded and its sidecar converted ([[SnapshotV5.migrated]]); each is written again as
    * V5 under the same name.
    *
    * A version this build does not know, in a file that starts like a snapshot, refuses the whole
    * run before any file is rewritten (it was written by a newer build). A file of a known version
    * that does not convert (corrupt, another schema, a state the live types or the mirror refuse)
    * is left alone and reported: it is a snapshot that will be skipped, which costs a replay at
    * worst. Nothing is ever replayed here.
    */
  def toCurrent[F[_]: Sync](using
      env: Ask[F, ConsensusMirrorEnv],
      raise: cats.mtl.Raise[F, StorageError]
  ): NodeSnapshotMigration[F] = new NodeSnapshotMigration[F]

  final class NodeSnapshotMigration[F[_]: Sync](using
      env: Ask[F, ConsensusMirrorEnv],
      raise: cats.mtl.Raise[F, StorageError]
  ) extends SnapshotMigration[F]
      with Migration[F]:
    private val F = Sync[F]
    val kind: FileKind = FileKind.SnapshotFile
    private val current = kind.current

    /** The version of the file, or why it has none. */
    private def probe(file: Path): F[Either[StorageError, FormatVersion]] =
      AtomicFile
        .guard[F, Array[Byte]](file.toString) {
          val in = JFiles.newInputStream(file.toNioPath)
          try in.readNBytes(legacy.SnapshotV2.HeaderSize)
          finally in.close()
        }
        .map { header =>
          legacy.SnapshotV2.numberOf(header) match
            case Some(number) => legacy.SnapshotV2.versionOf(file.toString, number)
            case None =>
              kind.versionOf(
                file.toString,
                new String(header.take(legacy.SnapshotV2.Magic.length), StandardCharsets.ISO_8859_1)
              )
        }

    def detect(file: Path): F[FormatVersion] =
      probe(file).flatMap(_.fold(_.raise[F, FormatVersion], F.pure))

    /** The file rewritten as the current version, or why it was not. */
    private def convert(file: Path): F[Either[SnapshotRefusal, Unit]] =
      for
        mirrorEnv <- env.ask[ConsensusMirrorEnv]
        raw <- AtomicFile.guard[F, Array[Byte]](file.toString)(JFiles.readAllBytes(file.toNioPath))
        result <- FileLedgerSnapshots.verified(raw).leftMap(SnapshotRefusal.Unreadable(_)) match
          case Left(refusal) => F.pure(Left(refusal))
          case Right(content) =>
            F.blocking(SnapshotV5.migrated(mirrorEnv, content)).flatMap {
              case Left(refusal) => F.pure(Left(refusal))
              case Right(migrated) =>
                AtomicFile.replace[F](file, FileLedgerSnapshots.closed(migrated)).map(Right(_))
            }
      yield result

    /** One step, as [[Migrate]] steps. Every older version goes straight to the current one (the
      * intermediate versions are not written), so the steps after the first find the file current;
      * a snapshot that does not convert raises here, where [[run]] reports it instead.
      */
    def step(file: Path, from: FormatVersion): F[Unit] =
      if from == current then F.unit
      else
        convert(file).flatMap(
          _.fold(
            r =>
              StorageError.Corrupt(file.toString, r).raise[F, Unit],
            F.pure
          )
        )

    def run(dir: Path): F[Report] =
      for
        present <- F.blocking(JFiles.isDirectory(dir.toNioPath))
        names <-
          if present then
            AtomicFile.guard[F, List[String]](dir.toString) {
              val listing = JFiles.list(dir.toNioPath)
              try
                import scala.jdk.CollectionConverters.*
                listing.iterator.asScala
                  .map(_.getFileName.toString)
                  .filter(FileLedgerSnapshots.isSnapshot)
                  .toList
                  .sorted
              finally listing.close()
            }
          else F.pure(Nil)
        probed <- names.traverse(n => probe(dir / n).map(n -> _))
        // A version of the family that this build does not know refuses the open, with nothing rewritten.
        _ <- probed
          .collectFirst { case (_, Left(e: StorageError.UnknownVersion)) => e }
          .traverse_(_.raise[F, Unit])
        outcomes <- probed.traverse {
          case (name, Left(error)) =>
            F.pure(Left(Refused(name, SnapshotRefusal.Unreadable(error))))
          case (_, Right(version)) if version == current => F.pure(Right(None))
          case (name, Right(version)) =>
            convert(dir / name).map {
              case Left(refusal) => Left(Refused(name, refusal))
              case Right(())     => Right(Some(version))
            }
        }
      yield Report(
        outcomes
          .collect { case Right(Some(from)) => from }
          .groupMapReduce(identity)(_ => 1)(_ + _)
          .toList
          .sortBy(_._1)
          .map((from, count) => Migrated(kind, from, current, count)),
        outcomes.collect { case Left(refused) => refused }
      )
