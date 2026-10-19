// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import xyz.kd5ujc.scarab.codec.DomainError
import xyz.kd5ujc.scarab.ledger.StorageStructureError
import cats.effect.{Async, Ref, Resource, Sync}
import cats.effect.std.Mutex
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import fs2.{Chunk, Stream}
import cats.effect.syntax.all.*
import fs2.io.file.{FileHandle, Files, Flag, Flags, Path}
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files as JFiles
import java.nio.file.StandardOpenOption
import java.util.zip.CRC32
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.ChainSync
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.ledger.{
  Block,
  FileKind,
  FirstImmutableProblem,
  FormatVersion,
  StorageError
}

/** Append-only storage of the blocks older than `k`: the ImmutableDB of ouroboros-consensus. */
trait ImmutableStore[F[_]]:
  /** Append a block that extends the stored chain. Durable after the next [[sync]]. */
  def append(ref: BlockRef, raw: Bytes)(using cats.mtl.Raise[F, StorageError]): F[Unit]

  /** Force everything appended so far to disk (data, then index). */
  def sync(using Raise[F, StorageError]): F[Unit]

  def tip(using cats.mtl.Raise[F, StorageError]): F[Option[BlockRef]]

  /** The raw block at `point` (slot and hash), if stored. */
  def get(point: ChainSync.Point)(using cats.mtl.Raise[F, StorageError]): F[Option[Bytes]]

  /** The block with number `n` (the main block, for a Byron boundary/main pair). */
  def byBlockNo(n: BigInt)(using cats.mtl.Raise[F, StorageError]): F[Option[ImmutableStore.Stored]]

  /** Every stored block strictly after `point` (`Origin`: from the first), oldest first. Raises
    * `StorageError.Missing` if `point` is not stored.
    */
  def stream(after: ChainSync.Point)(using
      cats.mtl.Raise[F, StorageError]
  ): Stream[F, ImmutableStore.Stored]

object ImmutableStore:
  final case class Stored(ref: BlockRef, raw: Bytes)

  /** What opening the store found and repaired. */
  final case class OpenReport(
      tip: Option[BlockRef],
      blocks: Long,
      chunks: Int,
      truncatedEntries: Int,
      truncatedBytes: Long,
      /** The files rewritten from an older format version on this open (none from the second open
        * on).
        */
      migrated: List[Migrated] = Nil
  )

/** Chunked files: blocks of slots `[c * chunkSlots, (c + 1) * chunkSlots)` live in `<c>.chunk`
  * (concatenated raw blocks) with an index `<c>.index`, an 8-byte magic followed by 68-byte
  * big-endian entries:
  *
  * {{{
  * offset u64 | length u32 | crc32 u32 | slot u64 | blockNo u64 | hash[32] | flags u8 | zero[3]
  * }}}
  *
  * Appends write data then index without syncing; [[ImmutableStore.sync]] and chunk roll-over force
  * both. A crash can therefore only damage the newest chunk, and opening validates it entry by
  * entry (contiguous offsets, data in bounds, CRC) and truncates both files to the last good entry.
  */
object FileImmutableStore:
  // Persisted format identifiers (see FileKind): the magic of the chunk index and the text of the
  // format file, in the current version. Version 1 spelled them with the project's former name
  // (`CSLIDX01`, `cardano-scala-lab immutable store v1`); opening migrates those files, and the read
  // path below only ever sees the current magic. scripts/oracle/export.py reads both.
  val IndexMagic: Array[Byte] = magicOf(FileKind.IndexFile.current)
  val FormatText: String = FileKind.FormatFile.identifier(FileKind.FormatFile.current)
  val HeaderSize = 8
  val EntrySize = 68

  private def magicOf(v: FormatVersion): Array[Byte] =
    FileKind.IndexFile.identifier(v).getBytes(StandardCharsets.US_ASCII)

  /** The chunk index files as a [[Migration]]: only the 8 magic bytes differ between versions, so a
    * step is the file with its header replaced. Reading the version looks at the header alone, once
    * per file at open: the per-entry read path never checks the magic.
    */
  private[storage] def indexMigration[F[_]: Sync](using Raise[F, StorageError]): Migration[F] =
    new Migration[F]:
      val kind = FileKind.IndexFile

      def detect(file: Path): F[FormatVersion] =
        AtomicFile
          .guard[F, Array[Byte]](file.toString) {
            val in = JFiles.newInputStream(file.toNioPath)
            try in.readNBytes(HeaderSize)
            finally in.close()
          }
          .flatMap { header =>
            // A file shorter than a header is a crash during chunk creation: recovery owns it.
            if header.length < HeaderSize then Sync[F].pure(kind.current)
            else
              kind
                .versionOf(file.toString, new String(header, StandardCharsets.ISO_8859_1))
                .fold(_.raise[F, FormatVersion], Sync[F].pure)
          }

      def step(file: Path, from: FormatVersion, to: FormatVersion): F[Unit] =
        validateStep(file, from, to) *> {
          AtomicFile
            .guard[F, Array[Byte]](file.toString)(JFiles.readAllBytes(file.toNioPath))
            .flatMap(old => AtomicFile.replace[F](file, magicOf(to) ++ old.drop(HeaderSize)))
        }

  /** The `format` file (`<identifier> chunkSlots=<n>`) as a [[Migration]]. Reading it also checks
    * that the database was made with this build's `chunkSlots`: a different value is a
    * [[StorageError.VersionMismatch]], since the chunk a block lives in depends on it.
    */
  private[storage] def formatMigration[F[_]: Sync](chunkSlots: Long)(using
      Raise[F, StorageError]
  ): Migration[F] = new Migration[F]:
    val kind = FileKind.FormatFile
    private val Suffix = " chunkSlots="

    def detect(file: Path): F[FormatVersion] =
      AtomicFile
        .guard[F, String](file.toString)(
          new String(JFiles.readAllBytes(file.toNioPath), StandardCharsets.UTF_8).trim
        )
        .flatMap { text =>
          val at = text.lastIndexOf(Suffix)
          val (identifier, slots) = if at < 0 then (text, "") else text.splitAt(at)
          kind
            .versionOf(file.toString, identifier)
            .fold(_.raise[F, FormatVersion], Sync[F].pure)
            .flatMap { version =>
              if slots == s"$Suffix$chunkSlots" then Sync[F].pure(version)
              else
                StorageError
                  .VersionMismatch(
                    file.toString,
                    text,
                    s"${kind.identifier(version)}$Suffix$chunkSlots"
                  )
                  .raise[F, FormatVersion]
            }
        }

    def step(file: Path, from: FormatVersion, to: FormatVersion): F[Unit] =
      validateStep(file, from, to) *> {
        AtomicFile.replace[F](
          file,
          s"${kind.identifier(to)}$Suffix$chunkSlots\n".getBytes(StandardCharsets.UTF_8)
        )
      }

  /** Validate immutable format headers without opening the recovery-capable store. Identity
    * adoption calls this before publishing a new identity, so an unknown or incompatible database
    * remains byte-for-byte untouched.
    */
  private[storage] def preflightReadOnly[F[_]: Sync](dir: Path, chunkSlots: Long)(using
      Raise[F, StorageError]
  ): F[Unit] =
    val format = dir / "format"
    val formatMigration = FileImmutableStore.formatMigration[F](chunkSlots)
    val indexMigration = FileImmutableStore.indexMigration[F]
    AtomicFile.guard[F, Boolean](format.toString)(JFiles.exists(format.toNioPath)).flatMap {
      exists => if exists then formatMigration.detect(format).void else Sync[F].unit
    } *> AtomicFile
      .guard[F, List[Int]](dir.toString) {
        if !JFiles.isDirectory(dir.toNioPath) then Nil
        else
          val stream = JFiles.list(dir.toNioPath)
          try
            import scala.jdk.CollectionConverters.*
            stream
              .iterator()
              .asScala
              .toList
              .flatMap { file =>
                val name = file.getFileName.toString
                Option.when(name.endsWith(".index"))(name.stripSuffix(".index").toIntOption).flatten
              }
              .sorted
          finally stream.close()
      }
      .flatMap(_.traverse_ { chunk =>
        val index = dir / f"$chunk%05d.index"
        AtomicFile.guard[F, Long](index.toString)(JFiles.size(index.toNioPath)).flatMap { size =>
          if size < HeaderSize || (size - HeaderSize) % EntrySize != 0 then
            StorageError.FirstImmutable(FirstImmutableProblem.InvalidIndexEntry).raise[F, Unit]
          else indexMigration.detect(index).void
        }
      })

  private[storage] final case class Entry(offset: Long, length: Int, crc: Int, ref: BlockRef):
    def end: Long = offset + length

  private[storage] def encode(e: Entry): Array[Byte] =
    val b = ByteBuffer.allocate(EntrySize)
    b.putLong(e.offset).putInt(e.length).putInt(e.crc)
    b.putLong(e.ref.slot.toLong).putLong(e.ref.blockNo.toLong)
    b.put(e.ref.hash.toArray)
    b.put(if e.ref.boundary then 1.toByte else 0.toByte)
    b.array()

  private def decode(b: ByteBuffer): Entry =
    val offset = b.getLong
    val length = b.getInt
    val crc = b.getInt
    val slot = b.getLong
    val blockNo = b.getLong
    val hash = new Array[Byte](32)
    b.get(hash)
    val flags = b.get()
    b.position(b.position() + 3)
    Entry(offset, length, crc, BlockRef(slot, Bytes.fromArray(hash), blockNo, (flags & 1) != 0))

  private[storage] def crcOf(a: Array[Byte], from: Int, len: Int): Int =
    val c = new CRC32()
    c.update(a, from, len)
    c.getValue.toInt

  private[storage] def entries(index: Array[Byte]): Vector[Entry] =
    if index.length < HeaderSize then Vector.empty
    else
      val count = (index.length - HeaderSize) / EntrySize
      val b = ByteBuffer.wrap(index, HeaderSize, count * EntrySize)
      Vector.fill(count)(decode(b))

  /** Read the first immutable block without opening the mutable store or running tail recovery.
    * Used by identity admission when an operator confirms a populated database with no identity.
    */
  private[storage] def firstBlockReadOnly[F[_]: Sync](dir: Path)(using
      Raise[F, StorageError]
  ): F[Option[Bytes]] =
    @annotation.tailrec
    def readFully(channel: FileChannel, buffer: ByteBuffer, position: Long): Boolean =
      if !buffer.hasRemaining then true
      else
        val read = channel.read(buffer, position)
        if read <= 0 then false else readFully(channel, buffer, position + read)

    def readChunk(root: java.nio.file.Path, chunk: Int)(using
        cats.mtl.Raise[F, StorageError]
    ): Either[StorageError, Option[Bytes]] =
      val indexPath = root.resolve(f"$chunk%05d.index")
      val invalid = StorageError.FirstImmutable(FirstImmutableProblem.InvalidIndexEntry)
      val index = FileChannel.open(indexPath, StandardOpenOption.READ)
      try
        val indexSize = index.size()
        if indexSize < HeaderSize || (indexSize - HeaderSize) % EntrySize != 0 then Left(invalid)
        else
          val header = ByteBuffer.allocate(HeaderSize)
          if !readFully(index, header, 0L) then Left(invalid)
          else
            val magic = new String(header.array(), StandardCharsets.ISO_8859_1)
            FileKind.IndexFile.versionOf(indexPath.toString, magic).flatMap { _ =>
              if indexSize == HeaderSize then Right(None)
              else
                val entryBytes = ByteBuffer.allocate(EntrySize)
                if !readFully(index, entryBytes, HeaderSize.toLong) then Left(invalid)
                else
                  val entry = decode(ByteBuffer.wrap(entryBytes.array()))
                  if entry.offset < 0 || entry.length <= 0 ||
                    entry.length > Block.MaxInputBytes ||
                    entry.offset > Long.MaxValue - entry.length.toLong
                  then Left(invalid)
                  else
                    val dataPath = root.resolve(f"$chunk%05d.chunk")
                    if !JFiles.isRegularFile(dataPath) then
                      Left(StorageError.FirstImmutable(FirstImmutableProblem.MissingChunk))
                    else
                      val data = FileChannel.open(dataPath, StandardOpenOption.READ)
                      try
                        val end = entry.offset + entry.length.toLong
                        if end > data.size() then Left(invalid)
                        else
                          val block = ByteBuffer.allocate(entry.length)
                          if !readFully(data, block, entry.offset) then Left(invalid)
                          else
                            val bytes = block.array()
                            if crcOf(bytes, 0, bytes.length) != entry.crc then
                              Left(
                                StorageError.FirstImmutable(FirstImmutableProblem.ChecksumMismatch)
                              )
                            else Right(Some(Bytes.fromArray(bytes)))
                      finally data.close()
            }
      finally index.close()

    def scan: Either[StorageError, Option[Bytes]] =
      val root = dir.toNioPath
      if !JFiles.isDirectory(root) then Right(None)
      else
        val names =
          val stream = JFiles.list(root)
          try
            import scala.jdk.CollectionConverters.*
            stream.iterator().asScala.map(_.getFileName.toString).toList
          finally stream.close()
        val chunks = names
          .collect {
            case name if name.endsWith(".index") => name.stripSuffix(".index").toIntOption
          }
          .flatten
          .sorted
        val indexed = chunks.toSet
        val orphanedData = names.exists { name =>
          name.endsWith(".chunk") &&
          name.stripSuffix(".chunk").toIntOption.exists(chunk => !indexed.contains(chunk))
        }
        if orphanedData then
          Left(StorageError.FirstImmutable(FirstImmutableProblem.InvalidIndexEntry))
        else
          cats.Monad[[A] =>> Either[StorageError, A]].tailRecM[List[Int], Option[Bytes]](chunks) {
            case Nil => Right(Right(None))
            case chunk :: rest =>
              readChunk(root, chunk).flatMap {
                case found @ Some(_) => Right(Right(found))
                case None            => Right(Left(rest))
              }
          }

    AtomicFile
      .guard[F, Either[StorageError, Option[Bytes]]](dir.toString)(scan)
      .flatMap(_.fold(_.raise[F, Option[Bytes]], Sync[F].pure))

  private final class Current[F[_]](
      val chunk: Int,
      val data: FileHandle[F],
      val index: FileHandle[F],
      val release: F[Unit],
      var dataSize: Long,
      var count: Int
  )

  /** Slots per chunk file. A storage granularity, independent of any era's epoch length (it is the
    * length of a Byron epoch on mainnet and preprod by origin); changing it makes an existing
    * database unreadable.
    */
  val DefaultChunkSlots: Long = 21_600L

  def open[F[_]: Async](dir: Path, chunkSlots: Long = DefaultChunkSlots)(using
      Raise[F, StorageError]
  ): Resource[F, (ImmutableStore[F], ImmutableStore.OpenReport)] =
    val F = Async[F]
    val files = Files.forAsync[F]

    def fail[A](e: StorageError)(using Raise[F, StorageError]): F[A] = e.raise[F, A]
    def guard[A](what: String)(fa: F[A])(using Raise[F, StorageError]): F[A] =
      fa.attempt.flatMap(_.fold(e => fail(StorageError.Io(what, e)), F.pure))

    def dataPath(c: Int) = dir / f"$c%05d.chunk"
    def indexPath(c: Int) = dir / f"$c%05d.index"

    def readAll(path: Path)(using cats.mtl.Raise[F, StorageError]): F[Array[Byte]] =
      guard(path.toString)(files.readAll(path).compile.to(Chunk).map(_.toArray))

    def syncDir(using cats.mtl.Raise[F, StorageError]): F[Unit] = guard(dir.toString)(F.blocking {
      val ch = FileChannel.open(dir.toNioPath, StandardOpenOption.READ)
      try ch.force(true)
      finally ch.close()
    })

    def writeFully(h: FileHandle[F], bytes: Array[Byte], offset: Long)(using
        cats.mtl.Raise[F, StorageError]
    ): F[Unit] =
      def go(chunk: Chunk[Byte], at: Long): F[Unit] =
        if chunk.isEmpty then F.unit
        else h.write(chunk, at).flatMap(n => go(chunk.drop(n), at + n))
      go(Chunk.array(bytes), offset)

    val readWrite = Flags(Flag.Create, Flag.Read, Flag.Write)

    def openChunk(c: Int)(using cats.mtl.Raise[F, StorageError]): F[Current[F]] =
      guard(indexPath(c).toString) {
        for
          dataH <- files.open(dataPath(c), readWrite).allocated
          indexH <- files.open(indexPath(c), readWrite).allocated
          dataSize <- dataH._1.size
          indexSize <- indexH._1.size
          _ <- writeFully(indexH._1, IndexMagic, 0).whenA(indexSize < HeaderSize)
          count = ((indexSize - HeaderSize).max(0) / EntrySize).toInt
        yield new Current[F](c, dataH._1, indexH._1, dataH._2 *> indexH._2, dataSize, count)
      }

    /** Validate the newest chunk; truncate it to its good prefix. */
    def recoverChunk(c: Int): F[(Option[Entry], Int, Long, Int)] =
      for
        index <- readAll(indexPath(c))
        data <- files.exists(dataPath(c)).ifM(readAll(dataPath(c)), F.pure(Array.empty[Byte]))
        _ <-
          if index.length >= HeaderSize && !java.util.Arrays
              .equals(index.take(HeaderSize), IndexMagic)
          then fail[Unit](StorageError.Corrupt(indexPath(c).toString, SnapshotReadError.IndexMagic))
          else F.unit
        all = entries(index)
        good = validPrefix(c, all, data)
        goodEnd = good.lastOption.fold(0L)(_.end)
        badEntries =
          (all.size - good.size) + (if (index.length - HeaderSize).max(0) % EntrySize != 0 then 1
                                    else 0)
        badBytes = (data.length - goodEnd).max(0L)
        _ <-
          if good.isEmpty then
            files.deleteIfExists(indexPath(c)) *> files.deleteIfExists(dataPath(c)).void
          else if badEntries > 0 || badBytes > 0 || index.length != HeaderSize + good.size * EntrySize
          then
            truncate(indexPath(c), HeaderSize + good.size.toLong * EntrySize) *>
              truncate(dataPath(c), goodEnd)
          else F.unit
      yield (good.lastOption, good.size, badBytes, badEntries)

    def truncate(path: Path, size: Long): F[Unit] =
      guard(path.toString)(
        files.open(path, Flags(Flag.Write)).use(h => h.truncate(size) *> h.force(true))
      )

    def validPrefix(c: Int, all: Vector[Entry], data: Array[Byte]): Vector[Entry] =
      val b = Vector.newBuilder[Entry]
      var expected = 0L
      var previous: Option[BlockRef] = None
      val it = all.iterator
      var ok = true
      while ok && it.hasNext do
        val e = it.next()
        val inChunk = e.ref.slot / chunkSlots == BigInt(c)
        val ordered =
          previous.forall(p => e.ref.slot > p.slot || (e.ref.slot == p.slot && p.boundary))
        ok = e.offset == expected && e.length >= 0 && e.end <= data.length && inChunk && ordered &&
          crcOf(data, e.offset.toInt, e.length) == e.crc
        if ok then
          b += e
          expected = e.end
          previous = Some(e.ref)
      b.result()

    /** Drop newest chunks that hold no valid block; returns the surviving chunk numbers. */
    def recoverNewest(
        chunks: Vector[Int],
        entries: Int,
        bytes: Long
    ): F[(Vector[Int], Option[Entry], Int, Long)] =
      chunks.lastOption match
        case None => F.pure((Vector.empty, None, entries, bytes))
        case Some(c) =>
          recoverChunk(c).flatMap { case (last, goodCount, badBytes, badEntries) =>
            if goodCount == 0 then
              recoverNewest(chunks.init, entries + badEntries, bytes + badBytes)
            else F.pure((chunks, last, entries + badEntries, bytes + badBytes))
          }

    val setup: F[(Vector[Int], Option[BlockRef], ImmutableStore.OpenReport, Option[Current[F]])] =
      for
        _ <- guard(dir.toString)(files.createDirectories(dir))
        formatFile = dir / "format"
        format = formatMigration[F](chunkSlots)
        present <- files.exists(formatFile)
        // The format file is checked (version, chunkSlots) before any file is rewritten.
        _ <-
          if present then format.detect(formatFile).void
          else
            AtomicFile.replace[F](
              formatFile,
              s"$FormatText chunkSlots=$chunkSlots\n".getBytes(StandardCharsets.UTF_8)
            )
        names <- guard(dir.toString)(files.list(dir).map(_.fileName.toString).compile.toVector)
        numbered = names
          .collect { case n if n.endsWith(".index") => n.stripSuffix(".index").toIntOption }
          .flatten
          .sorted
        // Data files without an index are leftovers of a crash during chunk creation.
        stray = names
          .collect { case n if n.endsWith(".chunk") => n.stripSuffix(".chunk").toIntOption }
          .flatten
          .filterNot(numbered.contains)
        _ <- stray.traverse_(c => files.deleteIfExists(dataPath(c)))
        // Version 1 databases: the indexes first, the format file last, each file atomically; a
        // crash in between leaves every file whole at one version and the next open finishes.
        migratedIndexes <- Migrate(indexMigration[F], numbered.map(indexPath).toList)
        migratedFormat <- Migrate(format, if present then List(formatFile) else Nil)
        recovered <- recoverNewest(numbered, 0, 0L)
        (chunks, lastEntry, truncEntries, truncBytes) = recovered
        total <- chunks
          .traverse(c =>
            readAll(indexPath(c)).map(i => ((i.length - HeaderSize).max(0) / EntrySize).toLong)
          )
          .map(_.sum)
        current <- chunks.lastOption.traverse(openChunk)
        tip = lastEntry.map(_.ref)
      yield (
        chunks,
        tip,
        ImmutableStore.OpenReport(
          tip,
          total,
          chunks.size,
          truncEntries,
          truncBytes,
          migratedIndexes ++ migratedFormat
        ),
        current
      )

    Resource.make(setup)(_ => F.unit).flatMap {
      case (initialChunks, initialTip, report, initialCurrent) =>
        final class Impl(
            mutex: Mutex[F],
            current: Ref[F, Option[Current[F]]],
            tipRef: Ref[F, Option[BlockRef]],
            chunksRef: Ref[F, Vector[Int]]
        ) extends ImmutableStore[F]:
          private def forceCurrent(cur: Current[F])(using Raise[F, StorageError]): F[Unit] =
            guard(dir.toString)(cur.data.force(false) *> cur.index.force(false))

          def sync(using Raise[F, StorageError]): F[Unit] =
            mutex.lock.surround(current.get.flatMap(_.traverse_(forceCurrent)))

          def tip(using cats.mtl.Raise[F, StorageError]): F[Option[BlockRef]] = tipRef.get

          def append(ref: BlockRef, raw: Bytes)(using cats.mtl.Raise[F, StorageError]): F[Unit] =
            mutex.lock.surround {
              for
                before <- tipRef.get
                _ <- before.traverse_ { t =>
                  if ref.slot > t.slot || (ref.slot == t.slot && t.boundary) then F.unit
                  else
                    fail[Unit](
                      StorageError.OutOfOrder(StorageStructureError.SlotOrder(ref.slot, t.slot))
                    )
                }
                _ <- F.unlessA(ref.hash.size == 32)(
                  fail[Unit](
                    StorageError.OutOfOrder(StorageStructureError.HashLength(ref.hash.size))
                  )
                )
                _ <- F.unlessA(ref.slot.isValidLong && ref.blockNo.isValidLong)(
                  fail[Unit](
                    StorageError
                      .OutOfOrder(StorageStructureError.InvalidBlockNumbers(ref.slot, ref.blockNo))
                  )
                )
                wanted = (ref.slot / chunkSlots).toInt
                cur <- current.get
                target <- cur match
                  case Some(c) if c.chunk == wanted => F.pure(c)
                  case _ =>
                    for
                      _ <- cur.traverse_(c => forceCurrent(c) *> c.release)
                      created <- openChunk(wanted)
                      _ <- syncDir
                      _ <- current.set(Some(created))
                      _ <- chunksRef
                        .update(cs => if cs.lastOption.contains(wanted) then cs else cs :+ wanted)
                    yield created
                bytes = raw.toArray
                entry = Entry(target.dataSize, bytes.length, crcOf(bytes, 0, bytes.length), ref)
                _ <- guard(dataPath(wanted).toString)(
                  writeFully(target.data, bytes, target.dataSize)
                )
                _ <- guard(indexPath(wanted).toString)(
                  writeFully(
                    target.index,
                    encode(entry),
                    HeaderSize.toLong + target.count.toLong * EntrySize
                  )
                )
                _ = {
                  target.dataSize += bytes.length
                  target.count += 1
                }
                _ <- tipRef.set(Some(ref))
              yield ()
            }

          private def indexOf(c: Int)(using cats.mtl.Raise[F, StorageError]): F[Vector[Entry]] =
            readAll(indexPath(c)).map(entries)

          private def read(c: Int, e: Entry)(using cats.mtl.Raise[F, StorageError]): F[Bytes] =
            guard(dataPath(c).toString)(
              files
                .readRange(dataPath(c), 1 << 16, e.offset, e.end)
                .compile
                .to(Chunk)
                .map(_.toArray)
            ).flatMap { bytes =>
              if bytes.length == e.length && crcOf(bytes, 0, bytes.length) == e.crc then
                F.pure(Bytes.fromArray(bytes))
              else
                fail(
                  StorageError
                    .Corrupt(dataPath(c).toString, ImmutableReadError.BlockChecksum(e.offset))
                )
            }

          private def locate(point: ChainSync.Point.Block)(using
              cats.mtl.Raise[F, StorageError]
          ): F[Option[(Int, Int, Vector[Entry])]] =
            val slot = point.slot.value
            val c = (slot / chunkSlots).toInt
            chunksRef.get.flatMap { cs =>
              if !cs.contains(c) then F.pure(None)
              else
                indexOf(c).map { es =>
                  val i = es.indexWhere(e => e.ref.slot == slot && e.ref.hash == point.hash)
                  Option.when(i >= 0)((c, i, es))
                }
            }

          def get(point: ChainSync.Point)(using cats.mtl.Raise[F, StorageError]): F[Option[Bytes]] =
            point match
              case ChainSync.Point.Origin => F.pure(None)
              case b: ChainSync.Point.Block =>
                locate(b).flatMap(_.traverse { case (c, i, es) => read(c, es(i)) })

          def byBlockNo(n: BigInt)(using
              cats.mtl.Raise[F, StorageError]
          ): F[Option[ImmutableStore.Stored]] =
            chunksRef.get.flatMap { cs =>
              // First chunk whose newest block number reaches n.
              def search(lo: Int, hi: Int)(using
                  cats.mtl.Raise[F, StorageError]
              ): F[Option[(Int, Vector[Entry])]] =
                if lo >= hi then F.pure(None)
                else
                  val mid = (lo + hi) / 2
                  indexOf(cs(mid)).flatMap { es =>
                    if es.isEmpty then search(lo, mid)
                    else if es.last.ref.blockNo < n then search(mid + 1, hi)
                    else if lo == mid || es.head.ref.blockNo <= n then F.pure(Some((cs(mid), es)))
                    else search(lo, mid)
                  }
              search(0, cs.size).flatMap {
                case None => F.pure(None)
                case Some((c, es)) =>
                  val matches = es.filter(_.ref.blockNo == n)
                  matches.find(!_.ref.boundary).orElse(matches.headOption).traverse { e =>
                    read(c, e).map(ImmutableStore.Stored(e.ref, _))
                  }
              }
            }

          def stream(after: ChainSync.Point)(using
              cats.mtl.Raise[F, StorageError]
          ): Stream[F, ImmutableStore.Stored] =
            val start: F[Option[(Int, Int)]] = after match
              case ChainSync.Point.Origin => F.pure(Some((-1, 0)))
              case b: ChainSync.Point.Block =>
                locate(b).map(_.map { case (c, i, _) => (c, i + 1) })
            Stream.eval(start).flatMap {
              case None =>
                Stream
                  .eval(fail[Unit](StorageError.Missing(s"point ${after} in the immutable store")))
                  .drain
              case Some((fromChunk, fromIndex)) =>
                Stream.eval(chunksRef.get).flatMap { cs =>
                  Stream.emits(cs).filter(c => c >= fromChunk).flatMap { c =>
                    Stream.eval(readChunk(c)).flatMap { stored =>
                      Stream.emits(if c == fromChunk then stored.drop(fromIndex) else stored)
                    }
                  }
                }
            }

          private def readChunk(c: Int)(using
              cats.mtl.Raise[F, StorageError]
          ): F[Vector[ImmutableStore.Stored]] =
            indexOf(c).flatMap { es =>
              if es.isEmpty then F.pure(Vector.empty)
              else
                guard(dataPath(c).toString)(
                  files
                    .readRange(dataPath(c), 1 << 16, 0L, es.last.end)
                    .compile
                    .to(Chunk)
                    .map(_.toArray)
                ).flatMap { data =>
                  es.traverse { e =>
                    if e.end <= data.length && crcOf(data, e.offset.toInt, e.length) == e.crc then
                      F.pure(
                        ImmutableStore.Stored(
                          e.ref,
                          Bytes.fromArray(
                            java.util.Arrays.copyOfRange(data, e.offset.toInt, e.end.toInt)
                          )
                        )
                      )
                    else
                      fail[ImmutableStore.Stored](
                        StorageError
                          .Corrupt(dataPath(c).toString, ImmutableReadError.BlockChecksum(e.offset))
                      )
                  }
                }
            }

          def close: F[Unit] =
            mutex.lock.surround(
              current.get.flatMap(_.traverse_(c => forceCurrent(c).guarantee(c.release)))
            )

        val acquire: F[Impl] =
          for
            mutex <- Mutex[F]
            current <- Ref.of[F, Option[Current[F]]](initialCurrent)
            tipRef <- Ref.of[F, Option[BlockRef]](initialTip)
            chunksRef <- Ref.of[F, Vector[Int]](initialChunks)
          yield new Impl(mutex, current, tipRef, chunksRef)
        Resource.make(acquire)(_.close).map(s => (s: ImmutableStore[F], report))
    }

/** A persisted immutable record that cannot be trusted. */
private[storage] enum ImmutableReadError extends DomainError:
  case BlockChecksum(offset: Long)
