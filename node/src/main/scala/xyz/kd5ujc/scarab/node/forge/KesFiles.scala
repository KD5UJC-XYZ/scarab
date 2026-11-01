// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import scala.util.Using.Releasable
import scala.util.Using
import xyz.kd5ujc.scarab.ledger.ForgeKeyProblem
import cats.syntax.all.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardCopyOption, StandardOpenOption as Open}
import java.nio.file.attribute.PosixFilePermissions
import scala.annotation.tailrec
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.kes.Sum6KesSigning
import xyz.kd5ujc.scarab.ledger.{
  ForgeError,
  KesFileFailureCause,
  KesFileOperation,
  KesStageMismatch
}

/** What became of an evolved key handed to a [[HotKeySource]] to write back. */
enum Persisted:
  case Written
  case Unchanged
  case Superseded
  case Failed(error: ForgeError)

private[forge] enum KesFileBoundary:
  case StagedWriteCompleted
  case StagedFileForced
  case StagedReadbackValidated
  case DirectoryForcedBeforeErase
  case PredecessorScrubCompleted
  case PredecessorFileForced
  case AtomicRenameCompleted
  case DirectoryForcedAfterRename

/** The KES key file of a pool and how an evolved key replaces its predecessor on it (H2a, #116).
  * The cardano-cli envelope and serialized key bytes are unchanged.
  *
  * A replacement is written, forced, decoded and checked, then its directory entry is forced before
  * the predecessor is overwritten. The predecessor is fully overwritten and forced before the
  * atomic rename. The directory is forced after the rename as well. Recovery applies the same
  * pre-erasure ordering to an existing stage.
  */
object KesFiles:
  private val ZeroChunkSize = 8192

  /** Narrow seam for deterministic short-write and durability-boundary tests. */
  private[forge] trait FileOps:
    def write(channel: FileChannel, bytes: ByteBuffer, position: Long): Int =
      channel.write(bytes, position)
    def force(channel: FileChannel, metadata: Boolean): Unit = channel.force(metadata)
    def forceStageFile(channel: FileChannel): Unit = force(channel, true)
    def forcePredecessorFile(channel: FileChannel): Unit = force(channel, true)
    def forceDirectoryBeforeErase(channel: FileChannel): Unit = force(channel, true)
    def forceDirectoryAfterRename(channel: FileChannel): Unit = force(channel, true)
    def readStage(file: Path): String = Files.readString(file)
    def rename(next: Path, file: Path): Unit =
      Files.move(next, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    def checkpoint(boundary: KesFileBoundary): Unit = ()

  private object Nio extends FileOps

  private def nextOf(file: Path): Path =
    file.resolveSibling(file.getFileName.toString + ".next")

  private def io[A](file: Path)(body: => A): Either[ForgeError, A] =
    Either
      .catchNonFatal(body)
      .leftMap(e => ForgeError.KeyFile(file.toString, ForgeKeyProblem.Io(e)))

  private def at[A](file: Path, operation: KesFileOperation)(body: => A): Either[ForgeError, A] =
    Either
      .catchNonFatal(body)
      .leftMap(e =>
        ForgeError.KesFileFailure(
          file.toString,
          operation,
          KesFileFailureCause.FileSystem(e)
        )
      )

  /** The file a path leads to, through symbolic links (a rotation switches generations by
    * re-pointing a link): the node writes back where it read from, so a rotation that happens
    * meanwhile is not overwritten by the key it replaces.
    */
  def resolved(file: Path): Either[ForgeError, Path] =
    io(file) {
      if Files.exists(file) then file.toRealPath()
      else file.toAbsolutePath.getParent.toRealPath().resolve(file.getFileName)
    }

  private def parse(file: Path): Either[ForgeError, Sum6KesSigning.SigningKey] =
    KeyFiles.read(file).flatMap(KeyFiles.kesSigningKey(file.toString, _))

  private final case class KeySnapshot(period: Int, verificationKey: Bytes, bytes: Bytes)
  private final case class KeyStatus(period: Int, verificationKey: Bytes)

  private def snapshot(key: Sum6KesSigning.SigningKey): KeySnapshot =
    KeySnapshot(key.period, key.verificationKey, key.toBytes)

  // A key read only to be looked at is forgotten (its material zeroed) however the look ends.
  private given Releasable[Sum6KesSigning.SigningKey] = _.forget()

  private def inspect(file: Path): Either[ForgeError, KeyStatus] =
    parse(file).map(key => Using.resource(key)(k => KeyStatus(k.period, k.verificationKey)))

  private def writeAll(
      channel: FileChannel,
      bytes: ByteBuffer,
      start: Long,
      file: Path,
      operation: KesFileOperation,
      ops: FileOps
  ): Either[ForgeError, Long] =
    @tailrec def loop(position: Long): Either[ForgeError, Long] =
      if !bytes.hasRemaining then Right(position)
      else
        at(file, operation)(ops.write(channel, bytes, position)) match
          case Left(error) => Left(error)
          case Right(written) if written <= 0 =>
            Left(
              ForgeError.KesFileFailure(
                file.toString,
                operation,
                KesFileFailureCause.NoWriteProgress
              )
            )
          case Right(written) => loop(position + written.toLong)
    loop(start)

  private def closeAfter[A](
      file: Path,
      operation: KesFileOperation,
      channel: FileChannel,
      result: Either[ForgeError, A]
  ): Either[ForgeError, A] =
    val closed = at(file, operation)(channel.close())
    result match
      case Left(primary) => Left(primary)
      case Right(value)  => closed.as(value)

  private def overwrite(file: Path, ops: FileOps): Either[ForgeError, Unit] =
    at(file, KesFileOperation.ScrubWrite)(Files.size(file)).flatMap { length =>
      at(file, KesFileOperation.ScrubWrite)(FileChannel.open(file, Open.WRITE)).flatMap { channel =>
        val zeros = new Array[Byte](ZeroChunkSize)
        @tailrec def chunks(offset: Long): Either[ForgeError, Unit] =
          if offset >= length then Right(())
          else
            val bytes = ByteBuffer.wrap(zeros)
            val chunk = math.min(zeros.length.toLong, length - offset).toInt
            bytes.limit(chunk)
            writeAll(channel, bytes, offset, file, KesFileOperation.ScrubWrite, ops) match
              case Left(error) => Left(error)
              case Right(_)    => chunks(offset + chunk.toLong)
        val written = chunks(0L).flatMap(_ =>
          at(file, KesFileOperation.ScrubWrite)(
            ops.checkpoint(KesFileBoundary.PredecessorScrubCompleted)
          )
        )
        val forced = written.flatMap(_ =>
          at(file, KesFileOperation.ScrubSync) {
            ops.forcePredecessorFile(channel)
            ops.checkpoint(KesFileBoundary.PredecessorFileForced)
          }
        )
        closeAfter(file, KesFileOperation.ScrubSync, channel, forced)
      }
    }

  private def destroy(file: Path, ops: FileOps): Either[ForgeError, Unit] =
    if !Files.exists(file) then Right(())
    else
      overwrite(file, ops).orElse(Right(())) *>
        at(file, KesFileOperation.Cleanup)(Files.deleteIfExists(file)).void

  private def syncDirectory(
      file: Path,
      operation: KesFileOperation,
      boundary: KesFileBoundary,
      force: FileChannel => Unit,
      ops: FileOps
  ): Either[ForgeError, Unit] =
    val dir = file.toAbsolutePath.getParent
    at(file, operation)(FileChannel.open(dir, Open.READ)).flatMap { channel =>
      val forced = at(file, operation)(force(channel))
      closeAfter(file, operation, channel, forced).flatMap(_ =>
        at(file, operation)(ops.checkpoint(boundary))
      )
    }

  private def forceStage(file: Path, ops: FileOps): Either[ForgeError, Unit] =
    val next = nextOf(file)
    at(file, KesFileOperation.StageSync)(FileChannel.open(next, Open.WRITE)).flatMap { channel =>
      val forced = at(file, KesFileOperation.StageSync) {
        ops.forceStageFile(channel)
        ops.checkpoint(KesFileBoundary.StagedFileForced)
      }
      closeAfter(file, KesFileOperation.StageSync, channel, forced)
    }

  private def stage(
      file: Path,
      key: Sum6KesSigning.SigningKey,
      ops: FileOps
  ): Either[ForgeError, Unit] =
    val next = nextOf(file)
    KeyFiles.kesEnvelope(file.toString, key).flatMap { text =>
      val raw = text.getBytes(StandardCharsets.UTF_8)
      at(file, KesFileOperation.StageWrite) {
        // owner-only where the file system has POSIX permissions
        Either
          .catchOnly[UnsupportedOperationException](
            FileChannel.open(
              next,
              java.util.Set.of[java.nio.file.OpenOption](Open.CREATE_NEW, Open.WRITE),
              PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
            )
          )
          .getOrElse(FileChannel.open(next, Open.CREATE_NEW, Open.WRITE))
      }.flatMap { channel =>
        val written = writeAll(
          channel,
          ByteBuffer.wrap(raw),
          0L,
          file,
          KesFileOperation.StageWrite,
          ops
        )
        val complete = written.flatMap(_ =>
          at(file, KesFileOperation.StageWrite)(
            ops.checkpoint(KesFileBoundary.StagedWriteCompleted)
          )
        )
        val forced = complete.flatMap(_ =>
          at(file, KesFileOperation.StageSync) {
            ops.forceStageFile(channel)
            ops.checkpoint(KesFileBoundary.StagedFileForced)
          }
        )
        closeAfter(file, KesFileOperation.StageSync, channel, forced)
          .flatMap(_ => validateStage(file, snapshot(key), ops))
      }
    }

  private def validateStage(
      file: Path,
      expected: KeySnapshot,
      ops: FileOps
  ): Either[ForgeError, Unit] =
    val next = nextOf(file)
    at(file, KesFileOperation.StageReadback)(ops.readStage(next)).flatMap { raw =>
      KeyFiles
        .kesSigningKey(next.toString, raw)
        .leftMap { error =>
          ForgeError.KesFileFailure(
            file.toString,
            KesFileOperation.StageReadback,
            KesFileFailureCause.StageReadback(error)
          )
        }
        .flatMap { found =>
          val actual = Using.resource(found)(snapshot)
          if actual.period != expected.period then
            Left(
              ForgeError.KesFileFailure(
                file.toString,
                KesFileOperation.StageReadback,
                KesFileFailureCause.StagedKeyMismatch(
                  KesStageMismatch.Period(expected.period, actual.period)
                )
              )
            )
          else if actual.verificationKey != expected.verificationKey then
            Left(
              ForgeError.KesFileFailure(
                file.toString,
                KesFileOperation.StageReadback,
                KesFileFailureCause.StagedKeyMismatch(
                  KesStageMismatch.VerificationKey(expected.verificationKey, actual.verificationKey)
                )
              )
            )
          else if actual.bytes != expected.bytes then
            Left(
              ForgeError.KesFileFailure(
                file.toString,
                KesFileOperation.StageReadback,
                KesFileFailureCause.StagedKeyMismatch(KesStageMismatch.SerializedKeyBytes)
              )
            )
          else
            at(file, KesFileOperation.StageReadback)(
              ops.checkpoint(KesFileBoundary.StagedReadbackValidated)
            )
        }
    }

  /** Test seam for staging a key without deleting an existing, possibly sole-valid `.next`. */
  private[forge] def stage(file: Path, key: Sum6KesSigning.SigningKey): Either[ForgeError, Unit] =
    stage(file, key, Nio)

  private[forge] def stageWith(
      file: Path,
      key: Sum6KesSigning.SigningKey,
      ops: FileOps
  ): Either[ForgeError, Unit] = stage(file, key, ops)

  private def scrub(file: Path, ops: FileOps): Either[ForgeError, Unit] =
    if Files.exists(file) then overwrite(file, ops) else Right(())

  private[forge] def scrub(file: Path): Either[ForgeError, Unit] = scrub(file, Nio)

  private def commit(file: Path, ops: FileOps): Either[ForgeError, Unit] =
    val next = nextOf(file)
    at(file, KesFileOperation.Rename) {
      ops.rename(next, file)
      ops.checkpoint(KesFileBoundary.AtomicRenameCompleted)
    }.flatMap(_ =>
      syncDirectory(
        file,
        KesFileOperation.DirectorySyncAfterRename,
        KesFileBoundary.DirectoryForcedAfterRename,
        ops.forceDirectoryAfterRename,
        ops
      )
    )

  private def finish(file: Path, ops: FileOps): Either[ForgeError, Unit] =
    forceStage(file, ops)
      .flatMap(_ =>
        syncDirectory(
          file,
          KesFileOperation.DirectorySyncBeforeErase,
          KesFileBoundary.DirectoryForcedBeforeErase,
          ops.forceDirectoryBeforeErase,
          ops
        )
      )
      .flatMap(_ => scrub(file, ops))
      .flatMap(_ => commit(file, ops))

  private def persistEither(
      file: Path,
      key: Sum6KesSigning.SigningKey,
      ops: FileOps
  ): Either[ForgeError, Persisted] =
    @tailrec def loop(afterRecovery: Boolean): Either[ForgeError, Persisted] =
      val main = if Files.exists(file) then inspect(file).toOption else None
      main match
        case Some(d) if d.verificationKey != key.verificationKey => Right(Persisted.Superseded)
        case Some(d) if d.period >= key.period                   =>
          // A prior rename may have succeeded while its directory force failed. Retry that force.
          syncDirectory(
            file,
            KesFileOperation.DirectorySyncAfterRename,
            KesFileBoundary.DirectoryForcedAfterRename,
            ops.forceDirectoryAfterRename,
            ops
          ).as(Persisted.Unchanged)
        case _ =>
          val next = nextOf(file)
          val staged = if Files.exists(next) then inspect(next).toOption else None
          staged match
            case Some(s)
                if main.isEmpty ||
                  (s.verificationKey == key.verificationKey && main.exists(_.period <= s.period)) =>
              if afterRecovery then
                Left(
                  ForgeError.KesFileFailure(
                    file.toString,
                    KesFileOperation.StageReadback,
                    KesFileFailureCause.StageStillPresentAfterRecovery
                  )
                )
              else
                // A valid stage may be the only surviving key. Make it the main file first, then
                // retry with the newer in-memory key; never destroy the only recoverable copy.
                recoverWith(file, ops) match
                  case Left(error) => Left(error)
                  case Right(recovered) =>
                    recovered.forget()
                    loop(afterRecovery = true)
            case Some(_) =>
              destroy(next, ops)
                .flatMap(_ => stage(file, key, ops))
                .flatMap(_ => finish(file, ops))
                .as(Persisted.Written)
            case None =>
              (if Files.exists(next) then destroy(next, ops) else Right(()))
                .flatMap(_ => stage(file, key, ops))
                .flatMap(_ => finish(file, ops))
                .as(Persisted.Written)
    loop(afterRecovery = false)

  /** Write `key` as the content of `file`, replacing the key it holds when that is the same KES key
    * at an earlier period (or is damaged or missing).
    */
  def persist(file: Path, key: Sum6KesSigning.SigningKey): Persisted =
    persistWith(file, key, Nio)

  private[forge] def persistWith(
      file: Path,
      key: Sum6KesSigning.SigningKey,
      ops: FileOps
  ): Persisted =
    persistEither(file, key, ops).fold(Persisted.Failed(_), identity)

  /** The key of `file` after any replacement that a crash interrupted is put right. */
  def recover(file: Path): Either[ForgeError, Sum6KesSigning.SigningKey] = recoverWith(file, Nio)

  private[forge] def recoverWith(
      file: Path,
      ops: FileOps
  ): Either[ForgeError, Sum6KesSigning.SigningKey] =
    val next = nextOf(file)
    val main = parse(file)
    if !Files.exists(next) then main
    else
      parse(next) match
        case Right(n) if main.fold(_ => true, m => same(m, n) && m.period <= n.period) =>
          val completed = validateStage(file, snapshot(n), ops).flatMap(_ => finish(file, ops))
          completed match
            case Left(error) =>
              n.forget()
              main.foreach(_.forget())
              Left(error)
            case Right(_) =>
              main.foreach(_.forget())
              Right(n)
        case Right(n) =>
          n.forget()
          destroy(next, ops) match
            case Left(error) =>
              main.foreach(_.forget())
              Left(error)
            case Right(_) => main
        case Left(_) =>
          destroy(next, ops) match
            case Left(error) =>
              main.foreach(_.forget())
              Left(error)
            case Right(_) => main

  private def same(a: Sum6KesSigning.SigningKey, b: Sum6KesSigning.SigningKey): Boolean =
    a.verificationKey == b.verificationKey

  /** The key (recovered), certificate and cold key of a pool, from where the operator installed
    * them.
    */
  def readHot(kesFile: Path, opCertFile: Path): Either[ForgeError, HotCredentials] =
    for
      real <- resolved(kesFile)
      kes <- recover(real)
      ocText <- KeyFiles.read(opCertFile)
      oc <- KeyFiles.opCert(opCertFile.toString, ocText)
    yield HotCredentials(kes, oc._1, oc._2)
