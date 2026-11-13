// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import scala.util.Using.Releasable
import scala.util.Using
import xyz.kd5ujc.scarab.ledger.ForgeKeyProblem
import cats.syntax.all.*
import java.lang.foreign.{
  Arena,
  FunctionDescriptor,
  Linker,
  MemoryLayout,
  MemorySegment,
  ValueLayout
}
import java.lang.invoke.MethodHandle
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.{
  Files,
  NoSuchFileException,
  Path,
  StandardCopyOption,
  StandardOpenOption as Open
}
import java.nio.file.attribute.PosixFilePermissions
import scala.annotation.tailrec
import scala.jdk.OptionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.kes.Sum6KesSigning
import xyz.kd5ujc.scarab.crypto.vrf.Draft03Prover
import xyz.kd5ujc.scarab.ledger.{
  ForgeError,
  KesFileFailureCause,
  KesFileOperation,
  KesStageMismatch
}

/** Why a key file was replaced by the rename rather than the atomic exchange. */
enum NoExchange:
  /** Not Linux: there is no `renameat2`. */
  case NotLinux

  /** Linux, but the C library has no `renameat2` (glibc before 2.28). */
  case NoRenameat2

  /** The kernel or the file system refused `RENAME_EXCHANGE` with this errno (`EINVAL`, `ENOSYS`,
    * `EOPNOTSUPP`).
    */
  case Refused(errno: Int)

/** How an evolved key took the key file's place. */
enum WriteProtocol:
  case Exchanged
  case Renamed(reason: NoExchange)

/** What became of an evolved key handed to a [[HotKeySource]] to write back. */
enum Persisted:
  case Written(by: WriteProtocol)
  case Unchanged
  case Superseded

  /** The key file is not there (an operator moved it away): nothing was written, and no file was
    * made at `file` (#918).
    */
  case Absent(file: String)
  case Failed(error: ForgeError)

private[forge] enum KesFileBoundary:
  case StagedWriteCompleted
  case StagedFileForced
  case StagedReadbackValidated
  case DirectoryForcedBeforeErase
  case PredecessorScrubCompleted
  case PredecessorFileForced
  case AtomicRenameCompleted
  case AtomicExchangeCompleted
  case DirectoryForcedAfterRename

/** The KES key file of a pool and how an evolved key replaces its predecessor on it (H2a, #116).
  * The cardano-cli envelope and serialized key bytes are unchanged.
  *
  * A replacement is written, forced, decoded and checked, then its directory entry is forced. On
  * Linux it then takes the key file's place in one atomic exchange (`renameat2` with
  * `RENAME_EXCHANGE`), which fails when the key file is not there, so a key file an operator moved
  * away is never made again (#918, ADR 0015 amendment of 2026-11-13); the directory is forced, and
  * the predecessor, now at the stage's name, is fully overwritten, forced and removed. Where there
  * is no such exchange, the predecessor is fully overwritten and forced before an atomic rename,
  * after a check that the key file is still there, and the directory is forced after the rename.
  * Recovery (a later start) keeps the rename and its pre-erasure ordering for an existing stage.
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
    def exchange(next: Path, file: Path): Exchange = AtomicExchange(next, file)
    def checkpoint(boundary: KesFileBoundary): Unit = ()

  private object Nio extends FileOps

  /** What an atomic exchange of the stage with the key file did. */
  private[forge] enum Exchange:
    case Swapped

    /** The key file (or the stage) is not there (`ENOENT`): nothing moved. */
    case Absent

    /** No atomic exchange here, and why. */
    case Unsupported(reason: NoExchange)

    /** Another errno. */
    case Failed(errno: Int)

    /** The native call could not be made (a binding or an access the JDK refused): a defect, never
      * a reason to fall back to the rename quietly.
      */
    case Defect(cause: Throwable)

  /** `renameat2(AT_FDCWD, next, AT_FDCWD, file, RENAME_EXCHANGE)` (Linux 3.15, glibc 2.28) through
    * the foreign-function interface, as the local socket's `unlink` is made: the JDK has no call
    * that replaces a file only if it exists. The release JAR's `Enable-Native-Access` manifest
    * attribute allows the native access; started from a classpath, `--enable-native-access` does.
    */
  private[forge] object AtomicExchange:
    private val AtFdCwd = -100
    private val RenameExchange = 2
    private val Enoent = 2
    private val Refused = Set(22, 38, 95)
    private val linker = Linker.nativeLinker()
    private val state = Linker.Option.captureStateLayout()
    private val errnoOffset = state.byteOffset(MemoryLayout.PathElement.groupElement("errno"))

    /** The handle, or why there is none: no exchange on this system, or a defect of the binding. */
    private lazy val call: Either[Exchange, MethodHandle] =
      if !System.getProperty("os.name", "").startsWith("Linux") then
        Left(Exchange.Unsupported(NoExchange.NotLinux))
      else
        linker
          .defaultLookup()
          .find("renameat2")
          .toScala
          .toRight(Exchange.Unsupported(NoExchange.NoRenameat2))
          .flatMap(symbol =>
            Either
              .catchNonFatal(
                linker.downcallHandle(
                  symbol,
                  FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT
                  ),
                  Linker.Option.captureCallState("errno")
                )
              )
              .leftMap(Exchange.Defect(_))
          )

    private def cString(arena: Arena, path: Path): MemorySegment =
      val bytes = path.toAbsolutePath.toString.getBytes(StandardCharsets.UTF_8)
      val name = arena.allocate(bytes.length + 1L)
      MemorySegment.copy(bytes, 0, name, ValueLayout.JAVA_BYTE, 0L, bytes.length)
      name.set(ValueLayout.JAVA_BYTE, bytes.length.toLong, 0.toByte)
      name

    def apply(next: Path, file: Path): Exchange =
      call.fold(
        identity,
        { renameat2 =>
          Either
            .catchNonFatal(Using.resource(Arena.ofConfined()) { arena =>
              val captured = arena.allocate(state)
              // signature-polymorphic: the ascribed Int is the handle's
              // `(MemorySegment, int, MemorySegment, int, MemorySegment, int)int`
              val result: Int = renameat2.invokeExact(
                captured,
                AtFdCwd,
                cString(arena, next),
                AtFdCwd,
                cString(arena, file),
                RenameExchange
              )
              if result == 0 then Exchange.Swapped
              else
                captured.get(ValueLayout.JAVA_INT, errnoOffset) match
                  case Enoent => Exchange.Absent
                  case errno if Refused.contains(errno) =>
                    Exchange.Unsupported(NoExchange.Refused(errno))
                  case errno => Exchange.Failed(errno)
            })
            .fold(Exchange.Defect(_), identity)
        }
      )

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

  private def stageDurable(file: Path, ops: FileOps): Either[ForgeError, Unit] =
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

  /** The stage put in the key file's place once it is durable: by the atomic exchange, after which
    * the predecessor, now at the stage's name, is erased and removed; where there is no exchange,
    * by erasing the predecessor and renaming the stage over it, after a check that the key file is
    * there. None: the key file is not there (moved away), and nothing was moved.
    */
  private def replace(file: Path, ops: FileOps): Either[ForgeError, Option[WriteProtocol]] =
    val next = nextOf(file)
    def failure(cause: KesFileFailureCause) =
      Left(ForgeError.KesFileFailure(file.toString, KesFileOperation.Rename, cause))
    stageDurable(file, ops).flatMap(_ =>
      ops.exchange(next, file) match
        case Exchange.Swapped =>
          at(file, KesFileOperation.Rename)(ops.checkpoint(KesFileBoundary.AtomicExchangeCompleted))
            .flatMap(_ =>
              syncDirectory(
                file,
                KesFileOperation.DirectorySyncAfterRename,
                KesFileBoundary.DirectoryForcedAfterRename,
                ops.forceDirectoryAfterRename,
                ops
              )
            )
            // the predecessor is at the stage's name now
            .flatMap(_ => overwrite(next, ops))
            .flatMap(_ => at(file, KesFileOperation.Cleanup)(Files.deleteIfExists(next)).void)
            .as(Some(WriteProtocol.Exchanged))
        // `ENOENT` names either path: the key file, or a stage that went missing meanwhile
        case Exchange.Absent if Files.exists(file) =>
          failure(KesFileFailureCause.FileSystem(new NoSuchFileException(next.toString)))
        case Exchange.Absent => Right(None)
        case Exchange.Unsupported(reason) =>
          if !Files.exists(file) then Right(None)
          else
            scrub(file, ops)
              .flatMap(_ => commit(file, ops))
              .as(Some(WriteProtocol.Renamed(reason)))
        case Exchange.Failed(errno) => failure(KesFileFailureCause.Exchange(errno))
        case Exchange.Defect(cause) => failure(KesFileFailureCause.FileSystem(cause))
    )

  /** Recovery's ending of a valid stage. A key file that is not there is not made from the stage:
    * the read fails, as the reference's does on a missing key file, and the stage is kept.
    */
  private def finish(file: Path, ops: FileOps): Either[ForgeError, Unit] =
    replace(file, ops).flatMap(_.fold(parse(file).void)(_ => Right(())))

  /** The write-back's ending of a stage it has just written. A key file that is not there is not
    * made again: the stage, the new key, is destroyed.
    */
  private def install(file: Path, ops: FileOps): Either[ForgeError, Persisted] =
    replace(file, ops).flatMap(
      _.fold(destroy(nextOf(file), ops).as(Persisted.Absent(file.toString)))(by =>
        Right(Persisted.Written(by))
      )
    )

  private def persistEither(
      file: Path,
      key: Sum6KesSigning.SigningKey,
      ops: FileOps
  ): Either[ForgeError, Persisted] =
    @tailrec def loop(afterRecovery: Boolean): Either[ForgeError, Persisted] =
      val main = if Files.exists(file) then inspect(file).toOption else None
      // a key file moved away is not made again (#918)
      if !Files.exists(file) then Right(Persisted.Absent(file.toString))
      else
        main match
          case Some(d) if d.verificationKey != key.verificationKey => Right(Persisted.Superseded)
          case Some(d) if d.period >= key.period                   =>
            // A prior rename or exchange may have succeeded while its directory force failed, or,
            // after an exchange, before its predecessor at the stage's name was erased. Retry both.
            val next = nextOf(file)
            val older =
              Option
                .when(Files.exists(next))(inspect(next).toOption)
                .flatten
                .exists(s => s.verificationKey == d.verificationKey && s.period < d.period)
            syncDirectory(
              file,
              KesFileOperation.DirectorySyncAfterRename,
              KesFileBoundary.DirectoryForcedAfterRename,
              ops.forceDirectoryAfterRename,
              ops
            ).flatMap(_ => if older then destroy(next, ops) else Right(()))
              .as(Persisted.Unchanged)
          case _ =>
            val next = nextOf(file)
            val staged = if Files.exists(next) then inspect(next).toOption else None
            staged match
              case Some(s)
                  if main.isEmpty ||
                    (s.verificationKey == key.verificationKey && main.exists(
                      _.period <= s.period
                    )) =>
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
                  .flatMap(_ => install(file, ops))
              case None =>
                (if Files.exists(next) then destroy(next, ops) else Right(()))
                  .flatMap(_ => stage(file, key, ops))
                  .flatMap(_ => install(file, ops))
    loop(afterRecovery = false)

  /** Write `key` as the content of `file`, replacing the key it holds when that is the same KES key
    * at an earlier period (or is damaged). A file that is not there is not made
    * ([[Persisted.Absent]], #918).
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
    // a key file that is not there is not made from a stage (#918); the read fails as it is
    if !Files.exists(next) || !Files.exists(file) then main
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

  /** What a reload found in the credential files. */
  enum Reread:
    case Found(credentials: HotCredentials, vrf: Draft03Prover.SigningKey)

    /** The first file, in the reference's reading order, that does not exist. */
    case Absent(file: Path)

  /** Whether `file` exists as the reference decides it before it reads a credential file:
    * cardano-api 11.6.0.0 `fileIOExceptT` (`cardano-api/src/Cardano/Api/Error.hs` 113-122) asks
    * `doesFileExist` (directory 1.3.8.5, `System/Directory/OsPath.hs` 1268-1277), which follows
    * links and answers false for a directory and for a path whose metadata cannot be read; false is
    * `FileDoesNotExistError`.
    */
  def exists(file: Path): Boolean = Files.exists(file) && !Files.isDirectory(file)

  /** The credentials again at a reload, in the order the reference reads them on SIGHUP
    * (cardano-node 11.1.3, `cardano-node/src/Cardano/Node/Protocol/Shelley.hs` 183-205 and 214-224,
    * `readLeaderCredentialsSingleton` and `opCertKesKeyCheck`): the VRF key, then the certificate,
    * then the KES key, each checked to exist just before it is read. The first that does not exist
    * ends the read ([[Reread.Absent]]). The VRF key read is the one forging takes, as the
    * reference's new block forging does (#919).
    */
  def reread(vrfFile: Path, kesFile: Path, opCertFile: Path): Either[ForgeError, Reread] =
    def present(file: Path)(read: => Either[ForgeError, Reread]): Either[ForgeError, Reread] =
      if exists(file) then read else Right(Reread.Absent(file))
    present(vrfFile) {
      KeyFiles.read(vrfFile).flatMap(KeyFiles.vrfSigningKey(vrfFile.toString, _)).flatMap { vrf =>
        present(opCertFile) {
          KeyFiles.read(opCertFile).flatMap(KeyFiles.opCert(opCertFile.toString, _)).flatMap {
            (opCert, cold) =>
              present(kesFile) {
                for
                  real <- resolved(kesFile)
                  kes <- recover(real)
                yield Reread.Found(HotCredentials(kes, opCert, cold), vrf)
              }
          }
        }
      }
    }
