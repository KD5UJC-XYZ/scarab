// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import cats.effect.{Resource, Sync}
import cats.effect.syntax.all.*
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import java.nio.channels.{FileChannel, FileLock, OverlappingFileLockException}
import java.nio.file.{Files, Path, StandardOpenOption}
import java.nio.file.attribute.{PosixFileAttributeView, PosixFilePermission as Perm}
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.ledger.{ForgeError, ForgeKeyProblem}

/** What the permission check found on a key file that it did not refuse. */
enum KeyFileAccess:
  /** Neither "group" nor "other" has any access. */
  case OwnerOnly

  /** "Group" has some access: allowed, and reported as a warning. */
  case GroupAccess(mode: Int)

  /** The file system has no POSIX permissions, so nothing was checked. */
  case NotChecked

/** The permission check of the VRF signing key file, as `checkVRFFilePermissions` does it
  * (cardano-node 11.1.3, `Cardano.Node.Run`, with `VRFPrivateKeyFilePermissionError` in
  * `Cardano.Node.Types`; the node runs it at start-up on `--shelley-vrf-key` only). Any "other"
  * access (`0o007`) is refused; any "group" access (`0o070`) is a warning. The reference does not
  * look at the KES key or the certificate, and neither does this check. Where the file system has
  * no POSIX view (Windows), the reference tests file attributes against generic access rights that
  * no attribute sets, so it never refuses; nothing is checked here either.
  */
object KeyFilePermissions:
  private val Other = Set(Perm.OTHERS_READ, Perm.OTHERS_WRITE, Perm.OTHERS_EXECUTE)
  private val Group = Set(Perm.GROUP_READ, Perm.GROUP_WRITE, Perm.GROUP_EXECUTE)

  private val Bits: List[(Perm, Int)] = List(
    Perm.OWNER_READ -> 0x100,
    Perm.OWNER_WRITE -> 0x80,
    Perm.OWNER_EXECUTE -> 0x40,
    Perm.GROUP_READ -> 0x20,
    Perm.GROUP_WRITE -> 0x10,
    Perm.GROUP_EXECUTE -> 0x08,
    Perm.OTHERS_READ -> 0x04,
    Perm.OTHERS_WRITE -> 0x02,
    Perm.OTHERS_EXECUTE -> 0x01
  )

  /** Follows a symbolic link, as the reference's `getFileStatus` does. */
  def check(file: Path): Either[ForgeError, KeyFileAccess] =
    Either
      .catchNonFatal(
        Option(Files.getFileAttributeView(file, classOf[PosixFileAttributeView]))
          .map(_.readAttributes().permissions().asScala.toSet)
      )
      .leftMap(e => ForgeError.KeyFile(file.toString, ForgeKeyProblem.Io(e)))
      .flatMap {
        case None => Right(KeyFileAccess.NotChecked)
        case Some(perms) =>
          val mode = Bits.collect { case (p, bit) if perms(p) => bit }.sum
          if perms.exists(Other) then
            Left(ForgeError.KeyFile(file.toString, ForgeKeyProblem.OtherPermissions(mode)))
          else if perms.exists(Group) then Right(KeyFileAccess.GroupAccess(mode))
          else Right(KeyFileAccess.OwnerOnly)
      }

/** The credentials guard of a forging node: the VRF key's permission check at start-up and, unless
  * switched off, an exclusive lock beside the KES key file for the node's lifetime. The reference
  * has no such lock (see the improvement recorded in `docs/implementer-notes.md`): the lock only
  * stops a second Scarab instance that uses these key files, whatever its `--db`. It is held on a
  * stable hidden file beside the KES key as named on the command line, never unlinked, and the
  * operating system drops it when the process ends.
  */
object KeyGuard:
  def lockFile(kes: Path): Path =
    val absolute = kes.toAbsolutePath.normalize()
    absolute.resolveSibling(s".${absolute.getFileName}.scarab-lock")

  /** @param vrf
    *   the VRF key file, whose permissions are checked
    * @param kes
    *   the KES key file the lock sits beside
    * @param lock
    *   take the lock (`--key-lock on`, the default)
    * @param events
    *   receives [[ForgeEvent.KeyFileGroupAccess]] for a group-readable VRF key
    */
  def resource[F[_]: Sync](vrf: Path, kes: Path, lock: Boolean, events: ForgeEvents[F])(using
      Raise[F, ForgeError]
  ): Resource[F, Unit] =
    Resource.eval(permissions(vrf, events)) *> held(kes, lock)

  /** The VRF key's permission check alone, as the reference runs it before it reads the genesis and
    * the credentials (cardano-node 11.1.3, `cardano-node/src/Cardano/Node/Run.hs` 193-194).
    */
  def permissions[F[_]: Sync](vrf: Path, events: ForgeEvents[F])(using
      Raise[F, ForgeError]
  ): F[Unit] =
    val F = Sync[F]
    F
      .blocking(KeyFilePermissions.check(vrf))
      .flatMap(
        _.fold(
          _.raise[F, KeyFileAccess],
          F.pure
        )
      )
      .flatMap {
        case KeyFileAccess.GroupAccess(mode) =>
          events.emit(ForgeEvent.KeyFileGroupAccess(vrf.toString, mode))
        case KeyFileAccess.OwnerOnly | KeyFileAccess.NotChecked => F.unit
      }

  /** The lock beside the KES key alone (`lock` off: nothing). */
  def held[F[_]: Sync](kes: Path, lock: Boolean)(using Raise[F, ForgeError]): Resource[F, Unit] =
    val F = Sync[F]
    val path = lockFile(kes)
    val acquire = F
      .blocking {
        Either
          .catchNonFatal(
            FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
          )
          .flatMap { channel =>
            // the channel is kept with its lease; without one it is closed. A lock held in this
            // JVM (an overlapping lock) is another instance's, as one held elsewhere is
            Either.catchNonFatal(Option(channel.tryLock())) match
              case Right(Some(lease)) =>
                Right(Right((channel, lease)): Either[ForgeError, (FileChannel, FileLock)])
              case Right(None) | Left(_: OverlappingFileLockException) =>
                Either
                  .catchNonFatal(channel.close())
                  .map(_ =>
                    Left(
                      ForgeError
                        .KeyFile(kes.toString, ForgeKeyProblem.InstanceLocked(path.toString))
                    )
                  )
              case Left(e) => Either.catchNonFatal(channel.close()).flatMap(_ => Left(e))
          }
      }
      .flatMap {
        case Right(Right(held)) => F.pure(held)
        case Right(Left(error)) => error.raise[F, (FileChannel, FileLock)]
        case Left(e) =>
          ForgeError
            .KeyFile(path.toString, ForgeKeyProblem.Io(e))
            .raise[F, (FileChannel, FileLock)]
      }
    if !lock then Resource.unit[F]
    else
      Resource
        .make(acquire)((channel, lease) =>
          F.blocking(lease.release()).guarantee(F.blocking(channel.close()))
        )
        .void
