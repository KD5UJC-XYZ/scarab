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
import xyz.kd5ujc.scarab.ledger.{FileKind, FormatVersion, NetworkIdentity, StorageError}

/** The network identity a database records (`<db>/network-identity`), written when the database is
  * created and checked on every open, so a `--db` can never be followed with another network's
  * genesis. Plain text (see [[NetworkIdentity.render]]), written atomically ([[AtomicFile]]). A
  * file of format 1 (the former name in its first line) is read as before and rewritten by
  * [[migrate]].
  *
  * A database created before the identity existed has no file. It is adopted if its first block
  * names the configured genesis as its predecessor (the Byron genesis hash, or none for a chain
  * that starts in Shelley), and refused with [[StorageError.ForeignChain]] otherwise. The check
  * cannot tell two Shelley-start networks apart (neither names a genesis hash); for those it only
  * rules out a Byron-start database.
  */
object NetworkIdentityFile:
  val FileName = "network-identity"

  private def fileOf(dir: Path): java.nio.file.Path = (dir / FileName).toNioPath

  private def guard[F[_]: Sync, A](what: String)(body: => A)(using
      Raise[F, StorageError]
  ): F[A] = AtomicFile.guard[F, A](what)(body)

  /** The recorded identity, if the file exists. */
  def read[F[_]: Sync](dir: Path)(using Raise[F, StorageError]): F[Option[NetworkIdentity]] =
    val file = fileOf(dir)
    guard[F, Option[String]](file.toString)(
      Option.when(Files.exists(file))(new String(Files.readAllBytes(file), StandardCharsets.UTF_8))
    ).flatMap(
      _.traverse(text =>
        NetworkIdentity
          .parse(file.toString, text)
          .fold(_.raise[F, NetworkIdentity], Sync[F].pure)
      )
    )

  /** Record `identity` in the current format (replacing any file). */
  def write[F[_]: Sync](dir: Path, identity: NetworkIdentity)(using
      Raise[F, StorageError]
  ): F[Unit] =
    guard[F, Unit](dir.toString)(Files.createDirectories(dir.toNioPath)) *>
      AtomicFile.replace[F](
        dir / FileName,
        identity.render.getBytes(StandardCharsets.UTF_8)
      )

  /** The identity file as a [[Migration]]: a rewrite in the next version's spelling. */
  def migration[F[_]: Sync](using Raise[F, StorageError]): Migration[F] = new Migration[F]:
    val kind = FileKind.IdentityFile

    private def parsed(file: Path): F[(FormatVersion, NetworkIdentity)] =
      guard[F, String](file.toString)(
        new String(Files.readAllBytes(file.toNioPath), StandardCharsets.UTF_8)
      ).flatMap(text =>
        NetworkIdentity
          .parseVersioned(file.toString, text)
          .fold(_.raise[F, (FormatVersion, NetworkIdentity)], Sync[F].pure)
      )

    def detect(file: Path): F[FormatVersion] = parsed(file).map(_._1)

    def step(file: Path, from: FormatVersion): F[Unit] =
      parsed(file).flatMap((_, identity) =>
        from.next.traverse_(next =>
          AtomicFile.replace[F](file, identity.renderAs(next).getBytes(StandardCharsets.UTF_8))
        )
      )

  /** Bring the identity file, if there is one, to the current format; what was migrated. Called
    * before the identity is checked, so that everything below sees one format.
    */
  def migrate[F[_]: Sync](dir: Path)(using Raise[F, StorageError]): F[List[Migrated]] =
    guard[F, Boolean](dir.toString)(Files.exists(fileOf(dir))).flatMap(present =>
      Migrate(migration[F], if present then List(dir / FileName) else Nil)
    )

  /** Check the recorded identity against `expected`, before anything else in the database is
    * touched: `true` if it is there and equal, `false` if there is no file, a
    * [[StorageError.NetworkMismatch]] if it differs.
    */
  def verify[F[_]: Sync](dir: Path, expected: NetworkIdentity)(using
      Raise[F, StorageError]
  ): F[Boolean] =
    read[F](dir).flatMap {
      case None => Sync[F].pure(false)
      case Some(found) =>
        if found == expected then Sync[F].pure(true)
        else StorageError.NetworkMismatch(found, expected).raise[F, Boolean]
    }

  /** A database without an identity file: `firstPredecessor` is what the first stored block names
    * as its predecessor (`None`: the database holds no block yet). A database that holds blocks is
    * adopted only if that matches the genesis; then the identity is recorded.
    */
  def adopt[F[_]: Sync](
      dir: Path,
      expected: NetworkIdentity,
      firstPredecessor: F[Option[Option[Bytes]]]
  )(using Raise[F, StorageError]): F[Unit] =
    def show(h: Option[Bytes]) = h.fold("no predecessor (genesis)")(_.hex)
    firstPredecessor.flatMap {
      case Some(found) if found != expected.byronGenesis =>
        StorageError
          .ForeignChain(
            show(found),
            show(expected.byronGenesis)
          )
          .raise[F, Unit]
      case _ => write[F](dir, expected)
    }
