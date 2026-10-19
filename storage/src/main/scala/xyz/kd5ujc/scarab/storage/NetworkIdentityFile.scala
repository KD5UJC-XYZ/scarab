// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage
import cats.effect.Sync
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import fs2.io.file.Path
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger.{
  FileKind,
  FormatVersion,
  IdentityProvenance,
  NetworkIdentity,
  StorageError,
  StoredNetworkIdentity
}

/** The complete identity a database records in `<db>/network-identity`. V1/V2 retain their original
  * three fields and are represented as incomplete legacy data; opening a populated database with
  * one requires the explicit operator acknowledgement described in the running and
  * storage-migration documentation. That acknowledgement is durable metadata, not historical proof.
  */
object NetworkIdentityFile:
  val FileName = "network-identity"

  enum Admission derives CanEqual:
    case Unchanged
    case WriteFresh
    case AdoptMissing
    case AdoptLegacy(version: FormatVersion, original: Bytes)

  private def fileOf(dir: Path): java.nio.file.Path = (dir / FileName).toNioPath

  private def guard[F[_]: Sync, A](what: String)(body: => A)(using
      Raise[F, StorageError]
  ): F[A] = AtomicFile.guard[F, A](what)(body)

  private def parseText[F[_]: Sync](file: java.nio.file.Path, text: String)(using
      Raise[F, StorageError]
  ): F[StoredNetworkIdentity] =
    NetworkIdentity
      .parse(file.toString, text)
      .fold(_.raise[F, StoredNetworkIdentity], Sync[F].pure)

  /** The recorded identity, including whether a historical value is incomplete. */
  def read[F[_]: Sync](dir: Path)(using
      Raise[F, StorageError]
  ): F[Option[StoredNetworkIdentity]] =
    val file = fileOf(dir)
    guard[F, Option[String]](file.toString)(
      Option.when(Files.exists(file))(new String(Files.readAllBytes(file), StandardCharsets.UTF_8))
    ).flatMap(_.traverse(parseText[F](file, _)))

  /** Record a newly created or fully verified identity in the current format. */
  def write[F[_]: Sync](dir: Path, identity: NetworkIdentity)(using
      Raise[F, StorageError]
  ): F[Unit] =
    guard[F, Unit](dir.toString)(Files.createDirectories(dir.toNioPath)) *>
      AtomicFile.replace[F](dir / FileName, identity.render.getBytes(StandardCharsets.UTF_8))

  /** Validate identity fields without changing disk. `populated` is computed by ChainStorage from a
    * recursive inventory excluding only its ownership lock. Snapshot compatibility is checked by
    * the caller before it applies the returned plan.
    */
  def preflight[F[_]: Sync](
      dir: Path,
      expected: NetworkIdentity,
      populated: Boolean,
      confirmLegacy: Boolean
  )(using Raise[F, StorageError]): F[Admission] =
    read[F](dir).flatMap {
      case None if !populated    => Sync[F].pure(Admission.WriteFresh)
      case None if confirmLegacy => Sync[F].pure(Admission.AdoptMissing)
      case None => StorageError.MissingIdentityNeedsConfirmation(dir.toString).raise[F, Admission]
      case Some(StoredNetworkIdentity.Complete(found, _)) =>
        if found == expected then Sync[F].pure(Admission.Unchanged)
        else StorageError.NetworkMismatch(found, expected).raise[F, Admission]
      case Some(StoredNetworkIdentity.Legacy(version, found)) =>
        if !found.matches(expected) then
          StorageError.LegacyNetworkMismatch(found, expected).raise[F, Admission]
        else if confirmLegacy then
          guard[F, Bytes](fileOf(dir).toString)(Bytes.fromArray(Files.readAllBytes(fileOf(dir))))
            .map(Admission.AdoptLegacy(version, _))
        else StorageError.LegacyIdentityNeedsConfirmation(version).raise[F, Admission]
    }

  /** Apply a plan only after every read-only admission check has completed. A legacy backup is
    * published before the v6 identity and is never overwritten by different content, making a retry
    * after interruption safe.
    */
  def admit[F[_]: Sync](dir: Path, expected: NetworkIdentity, admission: Admission)(using
      Raise[F, StorageError]
  ): F[Unit] =
    val F = Sync[F]
    admission match
      case Admission.Unchanged  => F.unit
      case Admission.WriteFresh => write[F](dir, expected)
      case Admission.AdoptMissing =>
        writeWith[F](dir, expected, IdentityProvenance.OperatorConfirmedMissing)
      case Admission.AdoptLegacy(version, original) =>
        val versionName = version.toString.toLowerCase
        val backup = dir / s"$FileName.legacy-$versionName"
        val backupPath = backup.toNioPath
        val backupBytes = original.toArray
        guard[F, (Boolean, Boolean, Option[Array[Byte]])](backupPath.toString) {
          val exists = Files.exists(backupPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)
          val regular =
            exists && Files.isRegularFile(backupPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)
          val aliasesIdentity = regular && Files.isSameFile(backupPath, fileOf(dir))
          val existing =
            Option.when(regular && !aliasesIdentity)(Files.readAllBytes(backupPath))
          (exists, regular && !aliasesIdentity, existing)
        }.flatMap {
          case (false, _, _) => AtomicFile.replace[F](backup, backupBytes)
          case (true, true, Some(existing)) if java.util.Arrays.equals(existing, backupBytes) =>
            F.unit
          case _ => StorageError.IdentityBackupConflict(backupPath.toString).raise[F, Unit]
        } *> writeWith[F](
          dir,
          expected,
          IdentityProvenance.OperatorConfirmedLegacy(
            version,
            Blake2b.hash256.hash(original)
          )
        )

  private def writeWith[F[_]: Sync](
      dir: Path,
      identity: NetworkIdentity,
      provenance: IdentityProvenance
  )(using Raise[F, StorageError]): F[Unit] =
    guard[F, Unit](dir.toString)(Files.createDirectories(dir.toNioPath)) *>
      AtomicFile.replace[F](
        dir / FileName,
        identity
          .renderAs(FileKind.IdentityFile.current, provenance)
          .getBytes(StandardCharsets.UTF_8)
      )

  /** The identity file as a migration. Incomplete legacy files are not rewritten here: they need
    * operator confirmation and a complete identity from the actually loaded genesis inputs.
    */
  def migration[F[_]: Sync]: Migration[F] = new Migration[F]:
    val kind = FileKind.IdentityFile

    private def parsed(
        file: Path
    )(using cats.mtl.Raise[F, StorageError]): F[(FormatVersion, StoredNetworkIdentity)] =
      guard[F, String](file.toString)(
        new String(Files.readAllBytes(file.toNioPath), StandardCharsets.UTF_8)
      ).flatMap(text =>
        NetworkIdentity
          .parseVersioned(file.toString, text)
          .fold(_.raise[F, (FormatVersion, StoredNetworkIdentity)], Sync[F].pure)
      )

    def detect(file: Path)(using cats.mtl.Raise[F, StorageError]): F[FormatVersion] =
      parsed(file).map(_._1)

    def step(file: Path, from: FormatVersion, to: FormatVersion)(using
        cats.mtl.Raise[F, StorageError]
    ): F[Unit] =
      validateStep(file, from, to) *> parsed(file).flatMap {
        case (_, StoredNetworkIdentity.Complete(identity, provenance)) =>
          AtomicFile.replace[F](
            file,
            identity.renderAs(to, provenance).getBytes(StandardCharsets.UTF_8)
          )
        case (version, StoredNetworkIdentity.Legacy(_, _)) =>
          StorageError.LegacyIdentityNeedsConfirmation(version).raise[F, Unit]
      }

  def migrate[F[_]: Sync](dir: Path)(using Raise[F, StorageError]): F[List[Migrated]] =
    val file = dir / FileName
    guard[F, Boolean](dir.toString)(Files.exists(fileOf(dir))).flatMap {
      case false => Sync[F].pure(Nil)
      case true =>
        read[F](dir).flatMap {
          case Some(StoredNetworkIdentity.Legacy(version, _)) =>
            StorageError.LegacyIdentityNeedsConfirmation(version).raise[F, List[Migrated]]
          case _ => Migrate(migration[F], List(file))
        }
    }

  /** Compare complete identity on every open. Missing identity is handled by the admission plan. */
  def verify[F[_]: Sync](dir: Path, expected: NetworkIdentity)(using
      Raise[F, StorageError]
  ): F[Boolean] =
    read[F](dir).flatMap {
      case None => Sync[F].pure(false)
      case Some(StoredNetworkIdentity.Complete(found, _)) =>
        if found == expected then Sync[F].pure(true)
        else StorageError.NetworkMismatch(found, expected).raise[F, Boolean]
      case Some(StoredNetworkIdentity.Legacy(version, found)) =>
        if !found.matches(expected) then
          StorageError.LegacyNetworkMismatch(found, expected).raise[F, Boolean]
        else StorageError.LegacyIdentityNeedsConfirmation(version).raise[F, Boolean]
    }
