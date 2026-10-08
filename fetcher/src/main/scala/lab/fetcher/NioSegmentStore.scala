// SPDX-License-Identifier: Apache-2.0
package lab.fetcher

import cats.effect.{Async, Resource}
import cats.syntax.all.*
import java.nio.ByteBuffer
import java.nio.channels.{FileChannel, FileLock, OverlappingFileLockException}
import java.nio.file.{Files, LinkOption, Path, StandardCopyOption, StandardOpenOption}
import java.nio.charset.StandardCharsets.UTF_8
import lab.chain.CardanoBlockIndex
import lab.chain.CardanoBlockIndex.IndexedBlock
import lab.cbor.Bytes

/** One owner, one directory. Fault hooks are synchronous test-only commit boundaries. */
object NioSegmentStore:
  enum Phase:
    case BeforeObjectWrite, ObjectWritten, ObjectForced, BeforeObjectInstall, ObjectInstalled,
      BeforeManifestWrite, ManifestWritten, ManifestForced, BeforeManifestInstall,
      ManifestInstalled,
      BeforeCheckpointWrite, CheckpointWritten, CheckpointForced, BeforeCheckpointInstall,
      CheckpointInstalled
  private val noFault: Phase => Unit = _ => ()
  private def outputErrors[A](body: => A): A =
    try body
    catch
      case e: FetchError if e.code == 2 || e.code == 3 => throw new FetchError(6, e.getMessage)
      case e: java.nio.file.NoSuchFileException =>
        throw new FetchError(6, "missing referenced output file: " + e.getFile)
      case _: java.nio.charset.CharacterCodingException => FetchError.output("invalid output UTF-8")
  private final class Owner(val root: Path, val channel: FileChannel, val lock: FileLock):
    private var active = true
    def use[A](body: => A): A = synchronized {
      if !active then FetchError.output("store is closed")
      body
    }
    def close(): Unit = synchronized {
      active = false
      try lock.release()
      finally channel.close()
    }
  private def acquire(root: Path, resume: Boolean): Owner =
    val path = LocalFiles.noLinks(root)
    if !Files.exists(path) then
      if resume then FetchError.output("resume output does not exist")
      Files.createDirectory(path)
    if !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) then
      FetchError.output("output is not a directory")
    if !resume then
      val stream = Files.list(path)
      try if stream.findAny().isPresent then FetchError.output("new output must be empty")
      finally stream.close()
    else if !Files.isRegularFile(path.resolve("lock"), LinkOption.NOFOLLOW_LINKS) then
      FetchError.output("missing output lock")
    LocalFiles.noLinks(path.resolve("lock"))
    val channel = FileChannel.open(
      path.resolve("lock"),
      StandardOpenOption.CREATE,
      StandardOpenOption.WRITE,
      LinkOption.NOFOLLOW_LINKS
    )
    try
      val lock =
        try channel.tryLock()
        catch case _: OverlappingFileLockException => null
      if lock == null then FetchError.output("output is locked")
      new Owner(path, channel, lock)
    catch
      case e: Throwable =>
        channel.close()
        throw e

  def resource[F[_]: Async](
      root: Path,
      spec: FetchSpec,
      source: SourceIdentity,
      resume: Boolean,
      fault: Phase => Unit = noFault
  ): Resource[F, SegmentStore[F]] =
    val F = Async[F]
    Resource
      .make(F.blocking(acquire(root, resume)))(o =>
        F.blocking {
          o.close()
        }
      )
      .evalMap { owner =>
        F.blocking {
          val store = new DiskStore(owner.root, spec, source, fault)
          outputErrors(store.initialize(resume))
          store
        }.map { disk =>
          new SegmentStore[F]:
            def selectionIdentity: String = spec.identity
            def sourceIdentity: SourceIdentity = source
            def snapshot: F[Snapshot] = F.blocking(owner.use(disk.current))
            def append(block: IndexedBlock): F[Snapshot] =
              F.uncancelable(_ => F.blocking(owner.use(disk.append(block))))
        }
      }

  /** Inspection holds the same lock and verifies every referenced object and manifest. */
  def inspect[F[_]: Async](root: Path, limits: Limits): F[Snapshot] =
    val F = Async[F]
    Resource
      .make(F.blocking(acquire(root, true)))(o =>
        F.blocking {
          o.close()
        }
      )
      .use { owner =>
        F.blocking {
          outputErrors {
            val id =
              LocalFiles.lines(
                LocalFiles.utf8(LocalFiles.read(owner.root.resolve("identity"), 4096))
              )
            if id.size != 3 || id.head != "segment-v1" then
              FetchError.output("invalid store identity")
            val selection = id(1).split("\t", -1).toList
            val spec = selection match
              case after :: count :: end :: "fail" :: Nil =>
                FetchSpec
                  .checked(
                    Point.parse(after).fold(FetchError.output, identity),
                    if count == "-" then None
                    else Some(count.toIntOption.getOrElse(FetchError.output("invalid count"))),
                    if end == "-" then None
                    else Some(Point.parse(end).fold(FetchError.output, identity)),
                    limits
                  )
                  .fold(FetchError.output, identity)
              case _ => FetchError.output("invalid selection")
            val source = id(2).split("\t", -1).toList match
              case digest :: label :: predecessor :: Nil =>
                SourceIdentity
                  .checked(
                    digest,
                    label,
                    Point.parse(predecessor).fold(FetchError.output, identity)
                  )
                  .fold(FetchError.output, identity)
              case _ => FetchError.output("invalid source identity")
            if !Files.isRegularFile(owner.root.resolve("checkpoint"), LinkOption.NOFOLLOW_LINKS)
            then
              FetchError.output(
                "inspection requires an existing checkpoint; use run --resume for empty initialization recovery"
              )
            val store = new DiskStore(owner.root, spec, source, noFault)
            store.initialize(true)
            store.current
          }
        }
      }

  private final class DiskStore(
      root: Path,
      spec: FetchSpec,
      source: SourceIdentity,
      fault: Phase => Unit
  ):
    private val limits = spec.limits
    private val identityText = s"segment-v1\n${spec.identity}\n${source.encoded}\n"
    private val fingerprint = Digests.text(identityText)
    private var state = Snapshot(Vector.empty, "")
    private var poisoned = false
    def current: Snapshot = synchronized(state)
    private def syncDirectory(path: Path): Unit =
      val c = FileChannel.open(path, StandardOpenOption.READ)
      try c.force(true)
      finally c.close()
    private def write(
        path: Path,
        bytes: Array[Byte],
        replace: Boolean = false,
        written: Option[Phase] = None
    ): Unit =
      LocalFiles.noLinks(path)
      val option =
        if replace then StandardOpenOption.TRUNCATE_EXISTING else StandardOpenOption.CREATE_NEW
      val c = FileChannel.open(path, StandardOpenOption.WRITE, option, LinkOption.NOFOLLOW_LINKS)
      try
        val b = ByteBuffer.wrap(bytes)
        while b.hasRemaining do c.write(b)
        written.foreach(fault)
        c.force(true)
      finally c.close()
    private def stage(name: String, bytes: Array[Byte], written: Option[Phase] = None): Path =
      val p = root.resolve("staging").resolve(name)
      write(p, bytes, Files.exists(p, LinkOption.NOFOLLOW_LINKS), written)
      p
    private def install(stage: Path, target: Path, bytes: Array[Byte]): Unit =
      if Files.exists(target, LinkOption.NOFOLLOW_LINKS) then
        if !java.util.Arrays.equals(LocalFiles.read(target, bytes.length), bytes) then
          FetchError.output("immutable object collision")
        Files.delete(stage)
      else Files.move(stage, target, StandardCopyOption.ATOMIC_MOVE)
      syncDirectory(target.getParent)
      syncDirectory(stage.getParent)
    private def manifest(records: Vector[Record], previous: String): String =
      s"manifest-v1\n$fingerprint\n$previous\n" + records.map(_.encoded + "\n").mkString
    private def inventory(): (Long, Int) =
      var bytes = 0L
      var files = 0
      def visit(dir: Path, kind: String): Unit =
        val stream = Files.newDirectoryStream(dir)
        try
          val iter = stream.iterator()
          while iter.hasNext do
            val p = iter.next()
            LocalFiles.noLinks(p)
            val name = p.getFileName.toString
            if kind == "root" && Set("objects", "manifests", "staging")(name) then
              if !Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) then
                FetchError.output("invalid output directory")
              visit(p, name)
            else
              val allowed = kind match
                case "root"      => Set("lock", "identity", "checkpoint")(name)
                case "objects"   => name.matches("[0-9a-f]{64}\\.cbor")
                case "manifests" => name.matches("[0-9a-f]{64}\\.manifest")
                case "staging" =>
                  Set("object.tmp", "manifest.tmp", "checkpoint.tmp", "identity.tmp")(name)
                case _ => false
              if !allowed || !Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) then
                FetchError.output("unrelated or unsafe output entry")
              val size = Files.size(p)
              if size > limits.maxStoredBytes - bytes then
                FetchError.output("stored-byte budget exceeded")
              bytes += size
              files += 1
              if files > limits.maxFiles then FetchError.output("stored-file budget exceeded")
        finally stream.close()
      visit(root, "root")
      (bytes, files)
    private def pointer(hash: String): Unit =
      val bytes = s"checkpoint-v1\n$hash\n".getBytes(UTF_8)
      fault(Phase.BeforeCheckpointWrite)
      val tmp = stage("checkpoint.tmp", bytes, Some(Phase.CheckpointWritten))
      fault(Phase.CheckpointForced)
      fault(Phase.BeforeCheckpointInstall)
      Files.move(
        tmp,
        root.resolve("checkpoint"),
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING
      )
      syncDirectory(root)
      syncDirectory(root.resolve("staging"))
      fault(Phase.CheckpointInstalled)
    def initialize(resume: Boolean): Unit = synchronized {
      inventory()
      val identityPath = root.resolve("identity")
      val checkpointPath = root.resolve("checkpoint")
      if Files.exists(identityPath, LinkOption.NOFOLLOW_LINKS) &&
        LocalFiles.utf8(LocalFiles.read(identityPath, 4096)) != identityText
      then FetchError.output("incompatible output identity")
      if resume && Files.exists(checkpointPath, LinkOption.NOFOLLOW_LINKS) then
        if !Files.exists(identityPath, LinkOption.NOFOLLOW_LINKS) then
          FetchError.output("missing store identity")
        val cp = LocalFiles.lines(LocalFiles.utf8(LocalFiles.read(checkpointPath, 128)))
        if cp.size != 2 || cp.head != "checkpoint-v1" || !Digests.valid(cp(1)) then
          FetchError.output("invalid checkpoint")
        state = verify(cp(1))
      else
        val raw = manifest(Vector.empty, "-").getBytes(UTF_8)
        val hash = Digests.sha256(raw)
        val (used, files) = inventory()
        val required = identityText.getBytes(UTF_8).length.toLong + raw.length + 128
        if required > limits.maxStoredBytes - used || files + 3 > limits.maxFiles then
          FetchError.output("insufficient initialization storage quota")
        // No committed pointer: only an interrupted EMPTY initialization may be recovered.
        // Never promote an orphan block/manifest to a committed checkpoint.
        Vector("objects", "manifests", "staging").foreach { name =>
          val dir = root.resolve(name)
          if !Files.exists(dir, LinkOption.NOFOLLOW_LINKS) then Files.createDirectory(dir)
          val stream = Files.newDirectoryStream(dir)
          try
            val iter = stream.iterator()
            while iter.hasNext do
              val file = iter.next()
              val allowed = name match
                case "objects" => false
                case "manifests" =>
                  file.getFileName.toString == s"$hash.manifest" &&
                  java.util.Arrays.equals(LocalFiles.read(file, raw.length), raw)
                case "staging" =>
                  Set("identity.tmp", "manifest.tmp", "checkpoint.tmp")(file.getFileName.toString)
                case _ => false
              if !allowed then FetchError.output("missing checkpoint with non-initial output")
          finally stream.close()
        }
        syncDirectory(root)
        if !Files.exists(identityPath, LinkOption.NOFOLLOW_LINKS) then
          val idBytes = identityText.getBytes(UTF_8)
          install(stage("identity.tmp", idBytes), identityPath, idBytes)
        install(
          stage("manifest.tmp", raw),
          root.resolve("manifests").resolve(s"$hash.manifest"),
          raw
        )
        pointer(hash)
        state = Snapshot(Vector.empty, hash)
      inventory()
    }
    private def readManifest(hash: String): (Vector[Record], String) =
      val bytes =
        LocalFiles.read(root.resolve("manifests").resolve(s"$hash.manifest"), 2L * 1024 * 1024)
      if Digests.sha256(bytes) != hash then FetchError.output("manifest SHA256 mismatch")
      val lines = LocalFiles.lines(LocalFiles.utf8(bytes))
      if lines.size < 3 || lines.size > 4099 || lines.head != "manifest-v1" || lines(
          1
        ) != fingerprint || !(lines(2) == "-" || Digests.valid(lines(2)))
      then FetchError.output("invalid manifest")
      (lines.drop(3).map(Record.parse), lines(2))
    private def verify(hash: String): Snapshot =
      val (records, previous) = readManifest(hash)
      if records.size > limits.maxBlocks then
        FetchError.output("resume object budget below current usage")
      var expected = records.dropRight(1)
      var prior = previous
      var depth = 0
      while prior != "-" do
        depth += 1
        if depth > records.size then FetchError.output("manifest ancestry depth mismatch")
        val (rs, parent) = readManifest(prior)
        if rs != expected then FetchError.output("manifest ancestry mismatch")
        expected = rs.dropRight(1)
        prior = parent
      if depth != records.size then FetchError.output("manifest ancestry missing")
      var previousPoint = spec.after
      records.foreach { record =>
        if record.size > limits.maxBlockBytes then
          FetchError.output("resume block-byte budget below current usage")
        val raw = LocalFiles.read(
          root.resolve("objects").resolve(s"${record.rawHash}.cbor"),
          limits.maxBlockBytes
        )
        val index = CardanoBlockIndex
          .inspect(Bytes.fromArray(raw), limits.maxBlockBytes)
          .fold(FetchError.output, identity)
        if Record.of(
            index
          ) != record || record.parent != previousPoint.hash || record.point.slot <= previousPoint.slot
        then FetchError.output("corrupt object or continuity")
        previousPoint = record.point
      }
      Snapshot(records, hash)
    def append(block: IndexedBlock): Snapshot = synchronized {
      if poisoned then FetchError.output("store requires reopen after interrupted commit")
      val record = Record.of(block)
      val previous = state.records.lastOption.map(_.point).getOrElse(spec.after)
      if record.parent != previous.hash || record.point.slot <= previous.slot then
        FetchError.integrity("append continuity mismatch")
      if record.size > limits.maxBlockBytes || state.records.size >= limits.maxBlocks then
        throw new FetchError(3, "objectBudget")
      if spec.count.exists(state.records.size >= _) || spec.end.contains(previous) then
        FetchError.integrity("selection is already complete")
      if spec.end.exists(end => record.point.slot >= end.slot && record.point != end) then
        FetchError.integrity("append exceeds or forks inclusive end")
      val records = state.records :+ record
      val rawManifest = manifest(records, state.manifestHash).getBytes(UTF_8)
      val hash = Digests.sha256(rawManifest)
      val raw = block.rawBytes.toArray
      val (used, files) = inventory()
      // Conservative admission reserves all transient bytes, even if immutable objects already exist.
      if raw.length.toLong + rawManifest.length + 128 > limits.maxStoredBytes - used || files + 3 > limits.maxFiles
      then throw new FetchError(3, "storageBudget")
      try
        fault(Phase.BeforeObjectWrite)
        val temp = stage("object.tmp", raw, Some(Phase.ObjectWritten))
        fault(Phase.ObjectForced)
        fault(Phase.BeforeObjectInstall)
        install(temp, root.resolve("objects").resolve(s"${record.rawHash}.cbor"), raw)
        fault(Phase.ObjectInstalled)
        fault(Phase.BeforeManifestWrite)
        val meta = stage("manifest.tmp", rawManifest, Some(Phase.ManifestWritten))
        fault(Phase.ManifestForced)
        fault(Phase.BeforeManifestInstall)
        install(meta, root.resolve("manifests").resolve(s"$hash.manifest"), rawManifest)
        fault(Phase.ManifestInstalled)
        pointer(hash)
        state = Snapshot(records, hash)
        state
      catch
        case e: Throwable =>
          poisoned = true
          throw e
    }
