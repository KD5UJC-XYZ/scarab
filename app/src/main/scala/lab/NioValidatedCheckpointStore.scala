// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{Async, Resource}
import cats.syntax.all.*
import java.nio.ByteBuffer
import java.nio.channels.{FileChannel, FileLock, OverlappingFileLockException}
import java.nio.file.{
  Files,
  Path,
  LinkOption,
  StandardOpenOption as O,
  StandardCopyOption as C,
  FileAlreadyExistsException
}
import lab.cbor.Bytes
import ValidatedCheckpoint.{Token, MaxBytes}

/** Package-private byte storage; it does not confer validated-state authority. */
private[lab] object NioValidatedCheckpointStore:
  enum Mode:
    case Create, Resume
  enum Phase:
    case BeforeLock, Locked, BeforeOpen, Opened, WriteChunk, Written, BeforeFileForce,
      FileForced, BeforeFileClose, FileClosed, BeforeReplace, Replaced,
      BeforeDirectoryForce, DirectoryForced
  trait Faults:
    def at(phase: Phase): Unit = ()
    def write(channel: FileChannel, buffer: ByteBuffer): Int = channel.write(buffer)
    def replace(from: Path, to: Path): Unit =
      Files.move(from, to, C.ATOMIC_MOVE, C.REPLACE_EXISTING): Unit
  object NoFaults extends Faults
  final class Invalid(message: String) extends RuntimeException(message)
  private def requireValid(ok: Boolean, message: String): Unit =
    if !ok then throw new Invalid(message)
  private def checked[A](value: Either[String, A]): A =
    value.fold(s => throw new Invalid(s), identity)
  private def noLinks(raw: Path): Path =
    val path = raw.toAbsolutePath
    val parts = path.iterator()
    while parts.hasNext do requireValid(parts.next().toString != "..", "parent traversal")
    var ancestor = path.normalize()
    while ancestor != null do
      requireValid(!Files.isSymbolicLink(ancestor), "symbolic link")
      ancestor = ancestor.getParent
    path.normalize()
  private def sync(path: Path): Unit =
    val channel = FileChannel.open(path, O.READ)
    try channel.force(true)
    finally channel.close()

  final class Disk private[NioValidatedCheckpointStore] (
      val root: Path,
      channel: FileChannel,
      lock: FileLock,
      mode: Mode,
      faults: Faults
  ):
    private var closed = false
    private var poisoned = false
    private val published = root.resolve("validated.bin")
    private val staging = root.resolve("validated.tmp")
    private def active(): Unit = requireValid(!closed && !poisoned, "store closed or poisoned")
    private def inventory(): Unit =
      noLinks(root)
      val entries = Files.newDirectoryStream(root)
      var count = 0; var total = 0L
      try
        val it = entries.iterator()
        while it.hasNext do
          val path = noLinks(it.next()); val name = path.getFileName.toString
          requireValid(
            Set("lock", "validated.bin", "validated.tmp")(name) &&
              Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS),
            "unexpected store entry"
          )
          val size = Files.size(path)
          requireValid(size <= MaxBytes && (name != "lock" || size == 0), "file bound")
          count += 1; total += size
          requireValid(count <= 3 && total <= 2L * MaxBytes, "directory bound")
      finally entries.close()
    def initialize(): Unit = synchronized {
      active(); inventory()
      if mode == Mode.Create then
        // Mandatory recheck AFTER lock acquisition, including delayed competing creators.
        requireValid(
          !Files.exists(published, LinkOption.NOFOLLOW_LINKS) &&
            !Files.exists(staging, LinkOption.NOFOLLOW_LINKS),
          "create requires unpublished store"
        )
      else
        requireValid(
          Files.isRegularFile(published, LinkOption.NOFOLLOW_LINKS),
          "published file missing"
        )
    }
    def read(contextId: Bytes, token: Token): Bytes = synchronized {
      active(); inventory()
      val in = FileChannel.open(noLinks(published), O.READ, LinkOption.NOFOLLOW_LINKS)
      val raw =
        try
          val size = in.size()
          requireValid(size >= 32 && size <= MaxBytes, "published size bound")
          val buffer = ByteBuffer.allocate(size.toInt)
          while buffer.hasRemaining do requireValid(in.read(buffer) > 0, "short published read")
          requireValid(in.read(ByteBuffer.allocate(1)) == -1, "published grew")
          Bytes.fromArray(buffer.array())
        finally in.close()
      checked(ValidatedCheckpoint.decode(raw, contextId, token))
      raw
    }
    def discardStagingAfterRecovery(): Unit = synchronized {
      active(); inventory()
      if Files.exists(staging, LinkOption.NOFOLLOW_LINKS) then
        Files.delete(noLinks(staging)); sync(root)
    }
    def install(expected: Option[Token], raw: Bytes, next: Token): Unit = synchronized {
      active()
      try
        inventory()
        checked(ValidatedCheckpoint.decode(raw, next.contextId, next))
        expected match
          case Some(old) =>
            requireValid(
              old.storeId == next.storeId && old.contextId == next.contextId &&
                old.generation < Long.MaxValue && next.generation == old.generation + 1,
              "token successor"
            )
            read(old.contextId, old) // Exact disk-token CAS, never generation alone.
          case None =>
            requireValid(
              mode == Mode.Create && next.generation == 0 &&
                !Files.exists(published, LinkOption.NOFOLLOW_LINKS) &&
                !Files.exists(staging, LinkOption.NOFOLLOW_LINKS),
              "initial publication conflict"
            )
        faults.at(Phase.BeforeOpen)
        val output =
          FileChannel.open(noLinks(staging), O.CREATE_NEW, O.WRITE, LinkOption.NOFOLLOW_LINKS)
        try
          faults.at(Phase.Opened)
          val buffer = ByteBuffer.wrap(raw.toArray)
          while buffer.hasRemaining do
            val before = buffer.position()
            val written = faults.write(output, buffer)
            requireValid(
              written > 0 && buffer.position() - before == written,
              "stalled/invalid write"
            )
            faults.at(Phase.WriteChunk)
          faults.at(Phase.Written); faults.at(Phase.BeforeFileForce)
          output.force(true); faults.at(Phase.FileForced)
          faults.at(Phase.BeforeFileClose)
        finally output.close()
        faults.at(Phase.FileClosed); faults.at(Phase.BeforeReplace)
        faults.replace(staging, published)
        faults.at(Phase.Replaced); faults.at(Phase.BeforeDirectoryForce)
        sync(root); faults.at(Phase.DirectoryForced)
      catch
        case error: Throwable =>
          poisoned = true
          throw error
    }
    def close(): Unit = synchronized {
      if !closed then
        closed = true
        try lock.release()
        finally channel.close()
    }

  private def acquire(raw: Path, mode: Mode, faults: Faults): Disk =
    val root = noLinks(raw)
    if !Files.exists(root, LinkOption.NOFOLLOW_LINKS) then
      requireValid(mode == Mode.Create, "store directory missing")
      try Files.createDirectory(root)
      catch case _: FileAlreadyExistsException => ()
      sync(root.getParent)
    requireValid(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS), "store directory required")
    val path = noLinks(root.resolve("lock"))
    if mode == Mode.Resume then
      requireValid(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS), "lock missing")
    faults.at(Phase.BeforeLock)
    val options: Vector[java.nio.file.OpenOption] =
      if mode == Mode.Create then Vector(O.CREATE, O.WRITE, LinkOption.NOFOLLOW_LINKS)
      else Vector(O.WRITE, LinkOption.NOFOLLOW_LINKS)
    val channel = FileChannel.open(path, options*)
    try
      val lock =
        try channel.tryLock()
        catch case _: OverlappingFileLockException => null
      requireValid(lock != null, "store already owned")
      try
        faults.at(Phase.Locked)
        new Disk(root, channel, lock, mode, faults)
      catch
        case error: Throwable =>
          lock.release()
          throw error
    catch
      case error: Throwable =>
        channel.close()
        throw error

  def resource[F[_]: Async](root: Path, mode: Mode, faults: Faults = NoFaults): Resource[F, Disk] =
    val F = Async[F]
    Resource
      .make(F.blocking(acquire(root, mode, faults)))(disk => F.blocking(disk.close()))
      .evalTap(disk => F.blocking(disk.initialize()))
