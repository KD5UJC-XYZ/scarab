// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{Async, Resource}
import cats.syntax.all.*
import java.nio.ByteBuffer
import java.nio.channels.{FileChannel, FileLock, OverlappingFileLockException}
import java.nio.file.{Files, LinkOption, Path, StandardCopyOption, StandardOpenOption}
import AcquisitionCheckpoint.*

/** Single owner, three bounded files. Uses the segment-store force/atomic-move pattern. */
object NioAcquisitionCheckpointStore:
  final class Invalid(message: String) extends RuntimeException(message)
  enum Phase:
    case BeforeWrite, Written, Forced, BeforeInstall, Installed, DirectoryForced
  enum Open:
    case Create
    case Resume(expected: Option[Revision] = None)

  private def fail(message: String): Nothing = throw new Invalid(message)
  private def checked[A](value: Either[String, A]): A = value.fold(fail, identity)
  private def noLinks(raw: Path): Path =
    val path = raw.toAbsolutePath
    val parts = path.iterator()
    while parts.hasNext do
      if parts.next().toString == ".." then fail("parent path components forbidden")
    var part = path.normalize()
    while part != null do
      if Files.isSymbolicLink(part) then fail("symbolic links forbidden")
      part = part.getParent
    path.normalize()

  private def sync(path: Path): Unit =
    val channel = FileChannel.open(path, StandardOpenOption.READ)
    try channel.force(true)
    finally channel.close()

  private final class Owner(val root: Path, channel: FileChannel, lock: FileLock):
    private var active = true
    def use[A](body: => A): A = synchronized {
      if !active then fail("checkpoint store closed")
      body
    }
    def close(): Unit = synchronized {
      active = false
      try lock.release()
      finally channel.close()
    }

  private def acquire(raw: Path, mode: Open): Owner =
    val root = noLinks(raw)
    if !Files.exists(root, LinkOption.NOFOLLOW_LINKS) then
      if mode != Open.Create then fail("checkpoint directory missing")
      Files.createDirectory(root)
      sync(root.getParent)
    if !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) then
      fail("checkpoint root is not directory")
    if mode == Open.Create then
      val entries = Files.newDirectoryStream(root)
      try if entries.iterator().hasNext then fail("new checkpoint directory must be empty")
      finally entries.close()
    else if !Files.isRegularFile(root.resolve("lock"), LinkOption.NOFOLLOW_LINKS) then
      fail("checkpoint owner lock missing")
    val path = noLinks(root.resolve("lock"))
    val channel = FileChannel.open(
      path,
      StandardOpenOption.CREATE,
      StandardOpenOption.WRITE,
      LinkOption.NOFOLLOW_LINKS
    )
    try
      val lock =
        try channel.tryLock()
        catch case _: OverlappingFileLockException => null
      if lock == null then fail("checkpoint directory locked")
      new Owner(root, channel, lock)
    catch
      case error: Throwable =>
        channel.close()
        throw error

  def resource[F[_]: Async](
      root: Path,
      context: Context,
      mode: Open,
      fault: Phase => Unit = _ => ()
  ): Resource[F, AcquisitionCheckpointStore[F]] =
    val F = Async[F]
    Resource.make(F.blocking(acquire(root, mode)))(o => F.blocking(o.close())).evalMap { owner =>
      F.blocking {
        val disk = new Disk(owner.root, context, fault)
        disk.initialize(mode)
        disk
      }.map { disk =>
        new AcquisitionCheckpointStore[F]:
          def context: Context = disk.context
          def snapshot: F[Saved] = F.blocking(owner.use(disk.snapshot()))
          def save(expected: Revision, checkpoint: BoundedChainFollower.Checkpoint): F[Saved] =
            F.uncancelable(_ => F.blocking(owner.use(disk.save(expected, checkpoint))))
      }
    }

  private final class Disk(root: Path, val context: Context, fault: Phase => Unit):
    private var current: Option[Saved] = None
    private var poisoned = false
    private val published = root.resolve("checkpoint.bin")
    private val staging = root.resolve("checkpoint.tmp")
    private def inventory(): Unit =
      noLinks(root)
      var files = 0
      var total = 0L
      val entries = Files.newDirectoryStream(root)
      try
        val iterator = entries.iterator()
        while iterator.hasNext do
          val path = noLinks(iterator.next())
          val name = path.getFileName.toString
          if !Set("lock", "checkpoint.bin", "checkpoint.tmp")(name) ||
            !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
          then fail("unrelated or unsafe checkpoint entry")
          val size = Files.size(path)
          if size > MaxBytes || (name == "lock" && size != 0) then fail("checkpoint file bound")
          files += 1
          total += size
          if files > 3 || total > 2L * MaxBytes then fail("checkpoint directory bound")
      finally entries.close()
    private def read(): Saved =
      inventory()
      val channel =
        FileChannel.open(noLinks(published), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
      try
        val size = channel.size()
        if size < 64 || size > MaxBytes then fail("checkpoint file bound")
        val bytes = ByteBuffer.allocate(size.toInt)
        while bytes.hasRemaining do
          if channel.read(bytes) < 0 then fail("checkpoint truncated during read")
        if channel.read(ByteBuffer.allocate(1)) != -1 then fail("checkpoint grew during read")
        checked(decode(bytes.array(), context))
      finally channel.close()

    def initialize(mode: Open): Unit =
      inventory()
      mode match
        case Open.Create =>
          val empty = checked(BoundedChainFollower.checked(context.anchor, Vector.empty))
          current = Some(publish(0, empty))
        case Open.Resume(expected) =>
          // Never promote a staging file, even when no published checkpoint exists.
          val saved = read()
          if expected.exists(_ != saved.revision) then fail("stale published checkpoint revision")
          current = Some(saved)
          if Files.exists(staging, LinkOption.NOFOLLOW_LINKS) then
            Files.delete(staging)
            sync(root)

    def snapshot(): Saved =
      if poisoned then fail("interrupted publication requires reopen")
      val saved = read()
      if !current.exists(_.revision == saved.revision) then fail("checkpoint changed outside owner")
      saved

    private def publish(generation: Long, checkpoint: BoundedChainFollower.Checkpoint): Saved =
      val bytes = checked(encode(context, generation, checkpoint))
      if bytes.length > MaxBytes then fail("checkpoint encoding bound")
      val saved = checked(decode(bytes, context))
      inventory()
      try
        fault(Phase.BeforeWrite)
        val channel = FileChannel.open(
          noLinks(staging),
          StandardOpenOption.CREATE_NEW,
          StandardOpenOption.WRITE,
          LinkOption.NOFOLLOW_LINKS
        )
        try
          val buffer = ByteBuffer.wrap(bytes)
          while buffer.hasRemaining do channel.write(buffer)
          fault(Phase.Written)
          channel.force(true)
          fault(Phase.Forced)
        finally channel.close()
        fault(Phase.BeforeInstall)
        Files.move(
          staging,
          published,
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING
        )
        fault(Phase.Installed)
        sync(root)
        fault(Phase.DirectoryForced)
        saved
      catch
        case error: Throwable =>
          poisoned = true
          throw error

    def save(expected: Revision, checkpoint: BoundedChainFollower.Checkpoint): Saved =
      val before = snapshot()
      if before.revision != expected then fail("stale checkpoint save revision")
      if before.revision.generation == Long.MaxValue then fail("checkpoint generation exhausted")
      val saved = publish(before.revision.generation + 1, checkpoint)
      current = Some(saved)
      saved
