// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import xyz.kd5ujc.scarab.codec.DomainError
import scala.util.Using
import xyz.kd5ujc.scarab.ledger.StorageStructureError
import cats.data.NonEmptyVector
import cats.effect.{Async, Clock, Fiber, Ref, Resource, Sync, Temporal}
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
import scala.annotation.tailrec
import scala.collection.immutable.SortedMap
import scala.concurrent.duration.*
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

  /** Immutable index entries at a slot, without reading block bodies. A Byron boundary block and
    * the following main block can share a slot, so both references are returned in index order.
    */
  def pointsAtSlot(slot: BigInt)(using cats.mtl.Raise[F, StorageError]): F[Vector[BlockRef]]

  /** The first indexed block at or after a slot, by immutable index metadata only. */
  def pointAtOrAfterSlot(slot: BigInt)(using
      cats.mtl.Raise[F, StorageError]
  ): F[Option[BlockRef]]

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

  /** What opening the store found and repaired.
    *
    * @param migrated
    *   The files rewritten from an older format version on this open (none from the second open
    *   on).
    */
  final case class OpenReport(
      tip: Option[BlockRef],
      blocks: Long,
      chunks: Int,
      truncatedEntries: Int,
      truncatedBytes: Long,
      migrated: List[Migrated] = Nil
  )

/** The reads [[FileImmutableStore]] makes of its files: the whole of a file, and a range of one.
  * The store reads through [[ImmutableReads.files]]; a suite swaps in an interpreter that counts
  * what is read.
  */
private[storage] trait ImmutableReads[F[_]]:
  def all(path: Path): F[Array[Byte]]

  /** The bytes of `path` from `from` up to `until`, exclusive. */
  def range(path: Path, from: Long, until: Long): F[Array[Byte]]

private[storage] object ImmutableReads:
  def files[F[_]: Async]: ImmutableReads[F] =
    val files = Files.forAsync[F]
    new ImmutableReads[F]:
      def all(path: Path): F[Array[Byte]] = files.readAll(path).compile.to(Chunk).map(_.toArray)

      def range(path: Path, from: Long, until: Long): F[Array[Byte]] =
        files.readRange(path, 1 << 16, from, until).compile.to(Chunk).map(_.toArray)

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
  private[storage] def indexMigration[F[_]: Sync]: Migration[F] =
    new Migration[F]:
      val kind = FileKind.IndexFile

      def detect(file: Path)(using cats.mtl.Raise[F, StorageError]): F[FormatVersion] =
        AtomicFile
          .guard[F, Array[Byte]](file.toString) {
            Using.resource(JFiles.newInputStream(file.toNioPath))(_.readNBytes(HeaderSize))
          }
          .flatMap { header =>
            // A file shorter than a header is a crash during chunk creation: recovery owns it.
            if header.length < HeaderSize then Sync[F].pure(kind.current)
            else
              kind
                .versionOf(file.toString, new String(header, StandardCharsets.ISO_8859_1))
                .fold(_.raise[F, FormatVersion], Sync[F].pure)
          }

      def step(file: Path, from: FormatVersion, to: FormatVersion)(using
          cats.mtl.Raise[F, StorageError]
      ): F[Unit] =
        validateStep(file, from, to) *> {
          AtomicFile
            .guard[F, Array[Byte]](file.toString)(JFiles.readAllBytes(file.toNioPath))
            .flatMap(old => AtomicFile.replace[F](file, magicOf(to) ++ old.drop(HeaderSize)))
        }

  /** The `format` file (`<identifier> chunkSlots=<n>`) as a [[Migration]]. Reading it also checks
    * that the database was made with this build's `chunkSlots`: a different value is a
    * [[StorageError.VersionMismatch]], since the chunk a block lives in depends on it.
    */
  private[storage] def formatMigration[F[_]: Sync](chunkSlots: Long): Migration[F] =
    new Migration[F]:
      val kind = FileKind.FormatFile
      private val Suffix = " chunkSlots="

      def detect(file: Path)(using cats.mtl.Raise[F, StorageError]): F[FormatVersion] =
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

      def step(file: Path, from: FormatVersion, to: FormatVersion)(using
          cats.mtl.Raise[F, StorageError]
      ): F[Unit] =
        validateStep(file, from, to) *> {
          AtomicFile.replace[F](
            file,
            s"${kind.identifier(to)}$Suffix$chunkSlots\n".getBytes(StandardCharsets.UTF_8)
          )
        }

  /** The chunk numbers of the files among `names` named `<chunk><suffix>`, in order. A name that
    * holds a locale's digits refuses the store in `dir` ([[FileNameProblem.LocaleDigits]]).
    */
  private def chunksNamed(
      dir: Path,
      names: Seq[String],
      suffix: String
  ): Either[StorageError, Vector[Int]] =
    names.sorted.toVector
      .traverse(FileNumber.numberOf(suffix))
      .bimap(StorageError.Corrupt(dir.toString, _), _.flatten.sorted)

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
      .guard[F, List[String]](dir.toString) {
        if !JFiles.isDirectory(dir.toNioPath) then Nil
        else
          Using.resource(JFiles.list(dir.toNioPath)) { stream =>
            import scala.jdk.CollectionConverters.*
            stream.iterator().asScala.map(_.getFileName.toString).toList
          }
      }
      .flatMap(names =>
        (chunksNamed(dir, names, ".index"), chunksNamed(dir, names, ".chunk")).tupled
          .fold(_.raise[F, Vector[Int]], (indexed, _) => indexed.pure[F])
      )
      .flatMap(_.traverse_ { chunk =>
        val index = dir / s"${FileNumber.padded(chunk, 5)}.index"
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

    def readChunk(root: java.nio.file.Path, chunk: Int): Either[StorageError, Option[Bytes]] =
      val indexPath = root.resolve(s"${FileNumber.padded(chunk, 5)}.index")
      val invalid = StorageError.FirstImmutable(FirstImmutableProblem.InvalidIndexEntry)
      Using.resource(FileChannel.open(indexPath, StandardOpenOption.READ)) { index =>
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
                    val dataPath = root.resolve(s"${FileNumber.padded(chunk, 5)}.chunk")
                    if !JFiles.isRegularFile(dataPath) then
                      Left(StorageError.FirstImmutable(FirstImmutableProblem.MissingChunk))
                    else
                      Using.resource(FileChannel.open(dataPath, StandardOpenOption.READ)) { data =>
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
                      }
            }
      }

    def scan: Either[StorageError, Option[Bytes]] =
      val root = dir.toNioPath
      if !JFiles.isDirectory(root) then Right(None)
      else
        val names =
          Using.resource(JFiles.list(root)) { stream =>
            import scala.jdk.CollectionConverters.*
            stream.iterator().asScala.map(_.getFileName.toString).toList
          }
        (chunksNamed(dir, names, ".index"), chunksNamed(dir, names, ".chunk")).tupled.flatMap {
          (chunks, data) =>
            val indexed = chunks.toSet
            if data.exists(chunk => !indexed.contains(chunk)) then
              Left(StorageError.FirstImmutable(FirstImmutableProblem.InvalidIndexEntry))
            else
              cats
                .Monad[[A] =>> Either[StorageError, A]]
                .tailRecM[List[Int], Option[Bytes]](chunks.toList) {
                  case Nil => Right(Right(None))
                  case chunk :: rest =>
                    readChunk(root, chunk).flatMap {
                      case found @ Some(_) => Right(Right(found))
                      case None            => Right(Left(rest))
                    }
                }
        }

    AtomicFile
      .guard[F, Either[StorageError, Option[Bytes]]](dir.toString)(scan)
      .flatMap(_.fold(_.raise[F, Option[Bytes]], Sync[F].pure))

  /** The chunk appended to: its handles, its data size and the entries of its index so far, which
    * the reference also holds in memory for the chunk it appends to (`CurrentChunkInfo`,
    * `Index/Cache.hs` 118-127 and `appendEntry` 928-957, ouroboros-consensus 82ecba32).
    */
  private final case class Current[F[_]](
      chunk: Int,
      data: FileHandle[F],
      index: FileHandle[F],
      release: F[Unit],
      dataSize: Long,
      appended: Vector[Entry]
  )

  /** The most index entries of sealed chunks held in memory. The reference holds the indexes of 250
    * past chunks (`ImmutableDB/Impl.hs` 180-193, ouroboros-consensus 82ecba32). A chunk of 21,600
    * slots holds about 1,080 blocks at mainnet's active slot coefficient of 1/20, so 270,000
    * entries hold as many of those; a Byron chunk, where every slot may hold a block, has up to
    * 21,601 entries, twenty times as many, which is why the bound counts entries and not chunks. A
    * Deviation, recorded in the decisions register (2026-11-18).
    */
  private val IndexCacheEntries = 270_000

  /** How long a sealed chunk's index stays held while it is not used, and how long the store waits
    * between two looks for those unused for longer: the reference's `expireUnusedAfter`, five
    * minutes, which its background thread also waits between two looks (`ImmutableDB/Impl.hs`
    * 189-193, `Index/Cache.hs` `expireUnusedChunks` 450-461, ouroboros-consensus 82ecba32).
    */
  private val IndexCacheExpiry: FiniteDuration = 5.minutes

  /** The indexes of sealed chunks held in memory, each with its last use, and the number of entries
    * they hold. A use is the time of the monotonic clock and the number of the use, which orders
    * the uses made at one time as they were made; `byUse` orders the chunks by it. The reference's
    * cache of past chunks orders them by the time of their last use (`LastUsed`, `pastChunksInfo`,
    * `Index/Cache.hs` 163-211, ouroboros-consensus 82ecba32), drops the least recently used first
    * when it is full (`evictIfNecessary` 289-316) and drops every one unused for
    * [[IndexCacheExpiry]] (`garbageCollect` 463-505). It bounds the number of chunks; this cache
    * bounds the number of entries ([[IndexCacheEntries]]).
    */
  private[storage] final case class IndexCache(
      held: Map[Int, (Vector[Entry], (FiniteDuration, Long))],
      byUse: SortedMap[(FiniteDuration, Long), Int],
      total: Long,
      uses: Long
  ):
    /** The index of `chunk` if it is held, now used at `now` (`lookupPastChunkInfo`,
      * `Index/Cache.hs` 318-335).
      */
    def lookup(chunk: Int, now: FiniteDuration): (IndexCache, Option[Vector[Entry]]) =
      held.get(chunk) match
        case None => (this, None)
        case Some((index, used)) =>
          val use = (now, uses)
          val touched = copy(
            held = held.updated(chunk, (index, use)),
            byUse = byUse - used + (use -> chunk),
            uses = uses + 1
          )
          (touched, Some(index))

    /** `index` held as the index of `chunk`, used at `now`, and then the least recently used chunks
      * dropped until the entries held are within `bound`, as the reference adds a past chunk and
      * then drops the least recently used if it holds too many (`addPastChunkInfo` and
      * `evictIfNecessary`, `Index/Cache.hs` 251-316, composed at 661-665 and 807-811). The chunk
      * added is dropped too if its use is older than every other's, which a use read before another
      * that was added first makes possible. An index of more than `bound` entries is not held, and
      * nothing is dropped for it.
      */
    def added(chunk: Int, index: Vector[Entry], bound: Long, now: FiniteDuration): IndexCache =
      @tailrec def within(cache: IndexCache): IndexCache =
        if cache.total <= bound then cache
        else
          cache.byUse.headOption match
            case None              => cache
            case Some((_, oldest)) => within(cache.without(oldest))
      val fresh = without(chunk)
      if index.size.toLong > bound then fresh
      else
        val use = (now, fresh.uses)
        within(
          fresh.copy(
            held = fresh.held.updated(chunk, (index, use)),
            byUse = fresh.byUse + (use -> chunk),
            total = fresh.total + index.size,
            uses = fresh.uses + 1
          )
        )

    /** The cache without the chunks last used `after` or longer before `now`, as the reference's
      * `garbageCollect` drops every past chunk last used at or before `now` less the expiry
      * (`PSQ.atMostView`, `Index/Cache.hs` 478-494). The age is taken as `now` less a use, two
      * readings of one monotonic clock, so that no reading near the clock's least value overflows.
      */
    def expired(now: FiniteDuration, after: FiniteDuration): IndexCache =
      byUse.iterator
        .takeWhile((use, _) => now - use._1 >= after)
        .foldLeft(this)((cache, used) => cache.without(used._2))

    private def without(chunk: Int): IndexCache =
      held.get(chunk).fold(this) { (index, used) =>
        copy(held = held - chunk, byUse = byUse - used, total = total - index.size)
      }

  private[storage] object IndexCache:
    val empty: IndexCache = IndexCache(Map.empty, SortedMap.empty, 0L, 0L)

  /** Looks at once, and then every [[IndexCacheExpiry]], for the sealed chunks' indexes not used
    * for that long, and drops them, as the reference's background thread does
    * (`expireUnusedChunks`, `Index/Cache.hs` 446-461, ouroboros-consensus 82ecba32). The chunk
    * appended to is held apart from the cache, as the reference holds its current chunk apart from
    * its past chunks (`currentChunkInfo` 175-181), and is never dropped. It uses no error
    * capability: a reading of the clock, an update of the cache and a wait, until the store that
    * started it is released and cancels it ([[alongside]]).
    */
  private def expireUnused[F[_]: Temporal](cache: Ref[F, IndexCache]): F[Nothing] =
    (Clock[F].monotonic.flatMap(now => cache.update(_.expired(now, IndexCacheExpiry))) *>
      Temporal[F].sleep(IndexCacheExpiry)).foreverM

  /** `work` in a fiber for the life of the resource, ending with a value (FIB-2): the fiber runs it
    * in a scope of its own (`attempt`), so a failure ends it with the failure as its value, which
    * the runtime does not report as unobserved. The resource gives the effect that lands it: it
    * waits for the fiber's end and raises its failure again in the fiber that lands it. On release
    * the fiber is cancelled and landed, so the failure reaches the fiber that releases the
    * resource. The reference links its cache's expiry thread to the thread that made its registry
    * (`forkLinkedThread`, `Index/Cache.hs` 428-432, ouroboros-consensus 82ecba32), and
    * resource-registry's `linkTo` throws such a thread's exception to that thread at once; a fiber
    * cannot be interrupted so, and here the failure reaches the owner when it lands the work or
    * releases it. The expiry loop ([[expireUnused]]) has no failure of its own.
    */
  private[storage] def alongside[F[_]: Temporal](work: F[Nothing]): Resource[F, F[Unit]] =
    def landed(fiber: Fiber[F, Throwable, Either[Throwable, Nothing]]): F[Unit] =
      fiber.join.flatMap(
        _.fold(
          Temporal[F].unit,
          Temporal[F].raiseError,
          _.flatMap(_.fold(Temporal[F].raiseError, nothing => nothing))
        )
      )
    Resource.make(work.attempt.start)(fiber => fiber.cancel *> landed(fiber)).map(landed)

  /** Slots per chunk file. A storage granularity, independent of any era's epoch length (it is the
    * length of a Byron epoch on mainnet and preprod by origin); changing it makes an existing
    * database unreadable.
    */
  val DefaultChunkSlots: Long = 21_600L

  def open[F[_]: Async](dir: Path, chunkSlots: Long = DefaultChunkSlots)(using
      Raise[F, StorageError]
  ): Resource[F, (ImmutableStore[F], ImmutableStore.OpenReport)] =
    open(dir, chunkSlots, IndexCacheEntries, ImmutableReads.files[F])

  /** [[open]], holding at most `cacheEntries` index entries of sealed chunks and reading the
    * store's files through `reads`.
    */
  private[storage] def open[F[_]: Async](
      dir: Path,
      chunkSlots: Long,
      cacheEntries: Int,
      reads: ImmutableReads[F]
  )(using Raise[F, StorageError]): Resource[F, (ImmutableStore[F], ImmutableStore.OpenReport)] =
    val F = Async[F]
    val files = Files.forAsync[F]

    def fail[A](e: StorageError)(using Raise[F, StorageError]): F[A] = e.raise[F, A]
    def guard[A](what: String)(fa: F[A])(using Raise[F, StorageError]): F[A] =
      fa.attempt.flatMap(_.fold(e => fail(StorageError.Io(what, e)), F.pure))

    def dataPath(c: Int) = dir / s"${FileNumber.padded(c, 5)}.chunk"
    def indexPath(c: Int) = dir / s"${FileNumber.padded(c, 5)}.index"

    def readAll(path: Path)(using cats.mtl.Raise[F, StorageError]): F[Array[Byte]] =
      guard(path.toString)(reads.all(path))

    def syncDir(using cats.mtl.Raise[F, StorageError]): F[Unit] = guard(dir.toString)(F.blocking {
      Using.resource(FileChannel.open(dir.toNioPath, StandardOpenOption.READ))(_.force(true))
    })

    def writeFully(h: FileHandle[F], bytes: Array[Byte], offset: Long): F[Unit] =
      def go(chunk: Chunk[Byte], at: Long): F[Unit] =
        if chunk.isEmpty then F.unit
        else h.write(chunk, at).flatMap(n => go(chunk.drop(n), at + n))
      go(Chunk.array(bytes), offset)

    val readWrite = Flags(Flag.Create, Flag.Read, Flag.Write)

    /** Open chunk `c` for appending after `appended`, the entries its index holds. */
    def openChunk(c: Int, appended: Vector[Entry])(using
        cats.mtl.Raise[F, StorageError]
    ): F[Current[F]] =
      guard(indexPath(c).toString) {
        for
          dataH <- files.open(dataPath(c), readWrite).allocated
          indexH <- files.open(indexPath(c), readWrite).allocated
          dataSize <- dataH._1.size
          indexSize <- indexH._1.size
          _ <- writeFully(indexH._1, IndexMagic, 0).whenA(indexSize < HeaderSize)
        yield new Current[F](c, dataH._1, indexH._1, dataH._2 *> indexH._2, dataSize, appended)
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
      // the entries up to the first that is out of place, out of order or fails its checksum
      @tailrec def go(
          rest: List[Entry],
          expected: Long,
          previous: Option[BlockRef],
          good: Vector[Entry]
      ): Vector[Entry] =
        rest match
          case Nil => good
          case e :: more =>
            val inChunk = e.ref.slot / chunkSlots == BigInt(c)
            val ordered =
              previous.forall(p => e.ref.slot > p.slot || (e.ref.slot == p.slot && p.boundary))
            val ok = e.offset == expected && e.length >= 0 && e.end <= data.length && inChunk &&
              ordered && crcOf(data, e.offset.toInt, e.length) == e.crc
            if ok then go(more, e.end, Some(e.ref), good :+ e) else good
      go(all.toList, 0L, None, Vector.empty)

    /** Drop newest chunks that hold no valid block; returns the surviving chunk numbers. */
    def recoverNewest(
        chunks: Vector[Int],
        entries: Int,
        bytes: Long
    ): F[(Vector[Int], Option[Entry], Int, Long)] =
      chunks match
        case older :+ c =>
          recoverChunk(c).flatMap { case (last, goodCount, badBytes, badEntries) =>
            if goodCount == 0 then recoverNewest(older, entries + badEntries, bytes + badBytes)
            else F.pure((chunks, last, entries + badEntries, bytes + badBytes))
          }
        case _ => F.pure((Vector.empty, None, entries, bytes))

    val setup: F[(Vector[Int], Option[BlockRef], ImmutableStore.OpenReport, Option[Current[F]])] =
      for
        _ <- guard(dir.toString)(files.createDirectories(dir))
        // The numbered files, read before any file is written or removed: a name that holds a
        // locale's digits refuses the store as it is.
        names <- guard(dir.toString)(files.list(dir).map(_.fileName.toString).compile.toVector)
        numbered <- chunksNamed(dir, names, ".index").fold(fail[Vector[Int]](_), F.pure)
        data <- chunksNamed(dir, names, ".chunk").fold(fail[Vector[Int]](_), F.pure)
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
        // Data files without an index are leftovers of a crash during chunk creation.
        stray = data.filterNot(numbered.contains)
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
        // the newest chunk's index as recovery left it
        current <- chunks.lastOption.traverse(c =>
          readAll(indexPath(c)).flatMap(index => openChunk(c, entries(index)))
        )
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
            chunksRef: Ref[F, Vector[Int]],
            cache: Ref[F, IndexCache]
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
                      // the time of the move, which the reference reads before it opens the new
                      // chunk's index (`Index/Cache.hs` `openPrimaryIndex` 798-811)
                      movedAt <- Clock[F].monotonic
                      _ <- cur.traverse_(c => forceCurrent(c) *> c.release)
                      created <- openChunk(wanted, Vector.empty)
                      _ <- syncDir
                      // The chunk left is sealed, and its index is held as the most recently used,
                      // before a reader can see the new chunk: the reference's cache keeps it so
                      // (`Index/Cache.hs` `openChunk` 337-367, ouroboros-consensus 82ecba32).
                      _ <- cur.traverse_(c =>
                        cache.update(_.added(c.chunk, c.appended, cacheEntries, movedAt))
                      )
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
                    HeaderSize.toLong + target.appended.size.toLong * EntrySize
                  )
                )
                // after its bytes are written: a reader that finds the entry can read them
                _ <- current.set(
                  Some(
                    target.copy(
                      dataSize = target.dataSize + bytes.length,
                      appended = target.appended :+ entry
                    )
                  )
                )
                _ <- tipRef.set(Some(ref))
              yield ()
            }

          /** A chunk's index entries: the chunk appended to holds them in memory, and a sealed
            * chunk's, which never change, are held once read while they are among the most recently
            * used ([[IndexCache]]), as the reference's cache answers (`Index/Cache.hs`
            * `getChunkInfo` 614-670, ouroboros-consensus 82ecba32). A miss reads the file without
            * holding the cache and adds it after, so two misses at once may both read it, as there.
            * A hit is used at the time read before it, and a miss at the time read after the file,
            * as there (626 and 660).
            */
          private def indexOf(c: Int)(using cats.mtl.Raise[F, StorageError]): F[Vector[Entry]] =
            def fromFile = readAll(indexPath(c)).map(entries)
            current.get.flatMap {
              case Some(appending) if appending.chunk == c => F.pure(appending.appended)
              case Some(appending) if appending.chunk > c =>
                Clock[F].monotonic.flatMap(now => cache.modify(_.lookup(c, now))).flatMap {
                  case Some(index) => F.pure(index)
                  case None =>
                    fromFile.flatTap(index =>
                      Clock[F].monotonic
                        .flatMap(read => cache.update(_.added(c, index, cacheEntries, read)))
                    )
                }
              // not a chunk of this store yet
              case _ => fromFile
            }

          private def read(c: Int, e: Entry)(using cats.mtl.Raise[F, StorageError]): F[Bytes] =
            val path = dataPath(c)
            guard(path.toString)(reads.range(path, e.offset, e.end)).flatMap { bytes =>
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

          def pointsAtSlot(slot: BigInt)(using
              cats.mtl.Raise[F, StorageError]
          ): F[Vector[BlockRef]] =
            val chunkIndex = slot / chunkSlots
            if !slot.isValidLong || slot < 0 || !chunkIndex.isValidInt then F.pure(Vector.empty)
            else
              val chunk = chunkIndex.toInt
              chunksRef.get.flatMap { chunks =>
                if !chunks.contains(chunk) then F.pure(Vector.empty)
                else
                  indexOf(chunk)
                    .map(_.collect { case entry if entry.ref.slot == slot => entry.ref })
              }

          def pointAtOrAfterSlot(slot: BigInt)(using
              cats.mtl.Raise[F, StorageError]
          ): F[Option[BlockRef]] =
            val chunkIndex = slot / chunkSlots
            if !slot.isValidLong || slot < 0 || !chunkIndex.isValidInt then F.pure(None)
            else
              chunksRef.get.flatMap { chunks =>
                def locate(lo: Int, hi: Int): F[Int] =
                  F.tailRecM((lo, hi)) { (left, right) =>
                    if left >= right then F.pure(Right(left))
                    else
                      val middle = left + (right - left) / 2
                      indexOf(chunks(middle)).map { entries =>
                        if entries.lastOption.exists(_.ref.slot < slot) then
                          Left((middle + 1, right))
                        else Left((left, middle))
                      }
                  }
                locate(0, chunks.size).flatMap { at =>
                  if at >= chunks.size then F.pure(None)
                  else
                    indexOf(chunks(at)).map { entries =>
                      val from = entries.dropWhile(_.ref.slot < slot)
                      from.headOption.flatMap { first =>
                        val sameSlot = from.takeWhile(_.ref.slot == first.ref.slot)
                        sameSlot.find(!_.ref.boundary).orElse(sameSlot.headOption).map(_.ref)
                      }
                    }
                }
              }

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
                    NonEmptyVector.fromVector(es) match
                      case None => search(lo, mid)
                      case Some(held) =>
                        if held.last.ref.blockNo < n then search(mid + 1, hi)
                        else if lo == mid || held.head.ref.blockNo <= n then
                          F.pure(Some((cs(mid), es)))
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
                // As the reference's iterator: each block is read, and its checksum checked, when
                // it is pulled, at the offset its index entry gives (`ImmutableDB/Impl/Iterator.hs`
                // 443-469 and 682-691, ouroboros-consensus 82ecba32). A chunk's index is taken
                // when the stream reaches it, after the chunks were listed: a chunk listed before
                // another was sealed by then, and its index is whole.
                Stream.eval(chunksRef.get).flatMap { cs =>
                  Stream.emits(cs).filter(c => c >= fromChunk).flatMap { c =>
                    Stream
                      .eval(indexOf(c))
                      .flatMap(es =>
                        Stream.emits(if c == fromChunk then es.drop(fromIndex) else es)
                      )
                      .evalMap(e => read(c, e).map(ImmutableStore.Stored(e.ref, _)))
                  }
                }
            }

          def close: F[Unit] =
            mutex.lock.surround(
              current.get.flatMap(_.traverse_(c => forceCurrent(c).guarantee(c.release)))
            )

        def acquire(cache: Ref[F, IndexCache]): F[Impl] =
          for
            mutex <- Mutex[F]
            current <- Ref.of[F, Option[Current[F]]](initialCurrent)
            tipRef <- Ref.of[F, Option[BlockRef]](initialTip)
            chunksRef <- Ref.of[F, Vector[Int]](initialChunks)
          yield new Impl(mutex, current, tipRef, chunksRef, cache)
        for
          cache <- Resource.eval(Ref.of[F, IndexCache](IndexCache.empty))
          store <- Resource.make(acquire(cache))(_.close)
          // released first: the expiry ends before the store closes
          _ <- alongside(expireUnused(cache))
        yield (store: ImmutableStore[F], report)
    }

/** A persisted immutable record that cannot be trusted. */
private[storage] enum ImmutableReadError extends DomainError:
  case BlockChecksum(offset: Long)
