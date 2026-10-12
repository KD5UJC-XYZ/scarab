// SPDX-License-Identifier: Apache-2.0
package lab.node.storage

import cats.effect.Sync
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import fs2.io.file.Path
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, StandardCopyOption, StandardOpenOption}
import lab.cbor.Bytes
import lab.node.{NetworkIdentity, StorageError}

/** The network identity a database records (`<db>/network-identity`), written when the database is
  * created and checked on every open, so a `--db` can never be followed with another network's
  * genesis. Plain text (see [[NetworkIdentity.render]]), written atomically: temp file, fsync,
  * rename, fsync of the directory.
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
  ): F[A] =
    Sync[F].blocking(body).attempt.flatMap {
      case Right(a) => Sync[F].pure(a)
      case Left(e)  => StorageError.Io(what, String.valueOf(e)).raise[F, A]
    }

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

  /** Record `identity` (replacing any file). */
  def write[F[_]: Sync](dir: Path, identity: NetworkIdentity)(using
      Raise[F, StorageError]
  ): F[Unit] =
    val file = fileOf(dir)
    val tmp = file.resolveSibling(FileName + ".tmp")
    guard[F, Unit](file.toString) {
      Files.createDirectories(dir.toNioPath)
      val ch = FileChannel.open(
        tmp,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE
      )
      try
        ch.write(java.nio.ByteBuffer.wrap(identity.render.getBytes(StandardCharsets.UTF_8)))
        ch.force(true)
      finally ch.close()
      Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
      val d = FileChannel.open(dir.toNioPath, StandardOpenOption.READ)
      try d.force(true)
      finally d.close()
    }

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
