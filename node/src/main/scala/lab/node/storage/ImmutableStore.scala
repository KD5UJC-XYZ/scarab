// SPDX-License-Identifier: Apache-2.0
package lab.node.storage

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
import java.nio.file.StandardOpenOption
import java.util.zip.CRC32
import lab.cbor.Bytes
import lab.network.ChainSync
import lab.node.{BlockRef, StorageError}

/** Append-only storage of the blocks older than `k`: the ImmutableDB of ouroboros-consensus. */
trait ImmutableStore[F[_]]:
  /** Append a block that extends the stored chain. Durable after the next [[sync]]. */
  def append(ref: BlockRef, raw: Bytes): F[Unit]

  /** Force everything appended so far to disk (data, then index). */
  def sync: F[Unit]

  def tip: F[Option[BlockRef]]

  /** The raw block at `point` (slot and hash), if stored. */
  def get(point: ChainSync.Point): F[Option[Bytes]]

  /** The block with number `n` (the main block, for a Byron boundary/main pair). */
  def byBlockNo(n: BigInt): F[Option[ImmutableStore.Stored]]

  /** Every stored block strictly after `point` (`Origin`: from the first), oldest first. Raises
    * `StorageError.Missing` if `point` is not stored.
    */
  def stream(after: ChainSync.Point): Stream[F, ImmutableStore.Stored]

object ImmutableStore:
  final case class Stored(ref: BlockRef, raw: Bytes)

  /** What opening the store found and repaired. */
  final case class OpenReport(
      tip: Option[BlockRef],
      blocks: Long,
      chunks: Int,
      truncatedEntries: Int,
      truncatedBytes: Long
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
  val IndexMagic: Array[Byte] = "CSLIDX01".getBytes("US-ASCII")
  val FormatText = "cardano-scala-lab immutable store v1"
  val HeaderSize = 8
  val EntrySize = 68

  private final case class Entry(offset: Long, length: Int, crc: Int, ref: BlockRef):
    def end: Long = offset + length

  private def encode(e: Entry): Array[Byte] =
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

  private def crcOf(a: Array[Byte], from: Int, len: Int): Int =
    val c = new CRC32()
    c.update(a, from, len)
    c.getValue.toInt

  private def entries(index: Array[Byte]): Vector[Entry] =
    if index.length < HeaderSize then Vector.empty
    else
      val count = (index.length - HeaderSize) / EntrySize
      val b = ByteBuffer.wrap(index, HeaderSize, count * EntrySize)
      Vector.fill(count)(decode(b))

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

    def fail[A](e: StorageError): F[A] = e.raise[F, A]
    def guard[A](what: String)(fa: F[A]): F[A] =
      fa.attempt.flatMap(_.fold(e => fail(StorageError.Io(what, String.valueOf(e))), F.pure))

    def dataPath(c: Int) = dir / f"$c%05d.chunk"
    def indexPath(c: Int) = dir / f"$c%05d.index"

    def readAll(path: Path): F[Array[Byte]] =
      guard(path.toString)(files.readAll(path).compile.to(Chunk).map(_.toArray))

    def syncDir: F[Unit] = guard(dir.toString)(F.blocking {
      val ch = FileChannel.open(dir.toNioPath, StandardOpenOption.READ)
      try ch.force(true)
      finally ch.close()
    })

    def writeFully(h: FileHandle[F], bytes: Array[Byte], offset: Long): F[Unit] =
      def go(chunk: Chunk[Byte], at: Long): F[Unit] =
        if chunk.isEmpty then F.unit
        else h.write(chunk, at).flatMap(n => go(chunk.drop(n), at + n))
      go(Chunk.array(bytes), offset)

    val readWrite = Flags(Flag.Create, Flag.Read, Flag.Write)

    def openChunk(c: Int): F[Current[F]] =
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
          then fail[Unit](StorageError.Corrupt(indexPath(c).toString, "bad index magic"))
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
        present <- files.exists(formatFile)
        _ <-
          if present then
            guard(formatFile.toString)(files.readUtf8Lines(formatFile).compile.string).flatMap {
              text =>
                val expected = s"$FormatText chunkSlots=$chunkSlots"
                if text.trim == expected then F.unit
                else
                  fail[Unit](StorageError.VersionMismatch(formatFile.toString, text.trim, expected))
            }
          else
            guard(formatFile.toString)(
              Stream
                .emit(s"$FormatText chunkSlots=$chunkSlots\n")
                .through(files.writeUtf8(formatFile))
                .compile
                .drain
            ) *> syncDir
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
        ImmutableStore.OpenReport(tip, total, chunks.size, truncEntries, truncBytes),
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
          private def forceCurrent(cur: Current[F]): F[Unit] =
            guard(dir.toString)(cur.data.force(false) *> cur.index.force(false))

          def sync: F[Unit] = mutex.lock.surround(current.get.flatMap(_.traverse_(forceCurrent)))

          def tip: F[Option[BlockRef]] = tipRef.get

          def append(ref: BlockRef, raw: Bytes): F[Unit] = mutex.lock.surround {
            for
              before <- tipRef.get
              _ <- before.traverse_ { t =>
                if ref.slot > t.slot || (ref.slot == t.slot && t.boundary) then F.unit
                else
                  fail[Unit](StorageError.OutOfOrder(s"slot ${ref.slot} does not follow ${t.slot}"))
              }
              _ <- F.unlessA(ref.hash.size == 32)(
                fail[Unit](StorageError.OutOfOrder("block hash is not 32 bytes"))
              )
              _ <- F.unlessA(ref.slot.isValidLong && ref.blockNo.isValidLong)(
                fail[Unit](StorageError.OutOfOrder("slot or block number out of range"))
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
              _ <- guard(dataPath(wanted).toString)(writeFully(target.data, bytes, target.dataSize))
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

          private def indexOf(c: Int): F[Vector[Entry]] = readAll(indexPath(c)).map(entries)

          private def read(c: Int, e: Entry): F[Bytes] =
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
                    .Corrupt(dataPath(c).toString, s"block at ${e.offset} fails its checksum")
                )
            }

          private def locate(point: ChainSync.Point.Block): F[Option[(Int, Int, Vector[Entry])]] =
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

          def get(point: ChainSync.Point): F[Option[Bytes]] = point match
            case ChainSync.Point.Origin => F.pure(None)
            case b: ChainSync.Point.Block =>
              locate(b).flatMap(_.traverse { case (c, i, es) => read(c, es(i)) })

          def byBlockNo(n: BigInt): F[Option[ImmutableStore.Stored]] =
            chunksRef.get.flatMap { cs =>
              // First chunk whose newest block number reaches n.
              def search(lo: Int, hi: Int): F[Option[(Int, Vector[Entry])]] =
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

          def stream(after: ChainSync.Point): Stream[F, ImmutableStore.Stored] =
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

          private def readChunk(c: Int): F[Vector[ImmutableStore.Stored]] =
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
                          .Corrupt(dataPath(c).toString, s"block at ${e.offset} fails its checksum")
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
