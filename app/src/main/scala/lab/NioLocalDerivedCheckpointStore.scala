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
import LocalDerivedCheckpoint.{Claim, Publication, MaxBytes}

/** Package-private byte storage; it does not confer validated-state authority. */
private[lab] object NioLocalDerivedCheckpointStore:
  enum Mode:
    case Create, Resume
  enum Phase:
    case BeforeLock, Locked, BeforeOpen, Opened, WriteChunk, Written, BeforeFileForce,
      FileForced, BeforeFileClose, FileClosed, BeforeReplace, Replaced,
      BeforeDirectoryForce, DirectoryForced, BeforeClose, Closed
  trait Faults:
    def at(phase: Phase): Unit = ()
    def forceParent(path: Path): Unit = sync(path)
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
    requireValid(
      raw != null && raw.isAbsolute && raw.normalize() == raw,
      "canonical absolute path required"
    )
    val path = raw
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

  final class Disk private[NioLocalDerivedCheckpointStore] (
      val root: Path,
      binding: ControllerJournalCodec.Binding,
      channel: FileChannel,
      lock: FileLock,
      mode: Mode,
      faults: Faults
  ):
    private var closed = false
    private var poisoned = false
    private val published = root.resolve("local-derived.bin")
    private val staging = root.resolve("local-derived.tmp")
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
            Set("lock", "local-derived.bin", "local-derived.tmp")(name) &&
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
      // Resume permits an absent primary only for explicit initial-pending inspection.
      // The caller decides its recorded outcome; no image is promoted or initialized here.
    }
    private def decoded(raw: Bytes): Claim =
      val c = checked(LocalDerivedCheckpoint.decode(raw)).claim
      requireValid(
        c.token.storeId.hex == binding.store.id.value &&
          c.token.contextId.hex == binding.store.context.value &&
          c.format == binding.format && c.profile == binding.profile &&
          c.authority == binding.authority,
        "checkpoint binding mismatch"
      )
      c
    def readOption(): Option[Bytes] = synchronized {
      active(); inventory()
      if !Files.exists(published, LinkOption.NOFOLLOW_LINKS) then None
      else
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
        decoded(raw)
        Some(raw)
    }
    def read(): Bytes = synchronized {
      readOption().getOrElse(throw new Invalid("published checkpoint missing"))
    }
    def discardStagingAfterRecovery(): Unit = synchronized {
      active(); inventory()
      if Files.exists(staging, LinkOption.NOFOLLOW_LINKS) then
        Files.delete(noLinks(staging)); sync(root)
    }
    def install(expected: Option[Claim], publication: Publication): Unit = synchronized {
      active()
      try
        inventory()
        requireValid(publication != null && expected != null, "publication/expected required")
        val raw = publication.bytes
        val next = publication.claim
        requireValid(decoded(raw) == next, "publication full claim mismatch")
        expected match
          case Some(old) =>
            requireValid(
              old != null && old.token.storeId == next.token.storeId &&
                old.token.contextId == next.token.contextId &&
                old.token.generation < Long.MaxValue &&
                next.token.generation == old.token.generation + 1,
              "claim successor"
            )
            requireValid(decoded(read()) == old, "exact predecessor full claim mismatch")
          case None =>
            requireValid(
              mode == Mode.Create && next.token.generation == 0 &&
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
        try faults.at(Phase.BeforeClose)
        finally
          try lock.release()
          finally channel.close()
        faults.at(Phase.Closed)
    }

  private def acquire(binding: ControllerJournalCodec.Binding, mode: Mode, faults: Faults): Disk =
    requireValid(binding != null && mode != null && faults != null, "binding/mode/faults required")
    checked(
      ControllerJournalCodec.validate(
        ControllerJournalCodec.Image(
          binding,
          ControllerReducer.Journal(0, 0, ControllerReducer.Selection.Dormant(binding.store)),
          Vector.empty
        )
      )
    )
    val root = noLinks(Path.of(binding.checkpoint))
    noLinks(Path.of(binding.root))
    if !Files.exists(root, LinkOption.NOFOLLOW_LINKS) then
      requireValid(mode == Mode.Create, "initialization required: store directory missing")
      try Files.createDirectory(root)
      catch case _: FileAlreadyExistsException => ()
    if mode == Mode.Create then faults.forceParent(root.getParent)
    requireValid(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS), "store directory required")
    val path = noLinks(root.resolve("lock"))
    def checkLockType(): Unit =
      val exists = Files.exists(path, LinkOption.NOFOLLOW_LINKS)
      requireValid(
        !exists || Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS),
        "existing lock must be a regular file"
      )
      requireValid(mode == Mode.Create || exists, "initialization required: store lock missing")
    checkLockType() // Never open an existing FIFO/device/directory in either mode.
    faults.at(Phase.BeforeLock)
    checkLockType() // Recheck after any delayed acquisition observer.
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
        new Disk(root, binding, channel, lock, mode, faults)
      catch
        case error: Throwable =>
          lock.release()
          throw error
    catch
      case error: Throwable =>
        channel.close()
        throw error

  def resource[F[_]: Async](
      binding: ControllerJournalCodec.Binding,
      mode: Mode,
      faults: Faults = NoFaults
  ): Resource[F, Disk] =
    val F = Async[F]
    Resource
      .make(F.blocking(acquire(binding, mode, faults)))(disk => F.blocking(disk.close()))
      .evalTap(disk => F.blocking(disk.initialize()))
