// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.data.NonEmptyVector
import cats.effect.{Async, Ref, Resource}
import scala.util.Using
import cats.effect.std.Mutex
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import fs2.io.file.Path
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.{Files, StandardOpenOption}
import java.util.zip.CRC32
import scala.annotation.tailrec
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.ChainSync
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.ledger.StorageError

/** One entry of the volatile log. */
enum VolatileRecord:
  /** A block was adopted (it extends the chain as the log stands, or starts a fork). */
  case Block(ref: BlockRef, raw: Bytes)

  /** The chain was rolled back to `to`: the blocks after it are no longer on the chain. */
  case Rollback(to: ChainSync.Point)

/** The blocks of the volatile part of the chain (the last `k`), kept on disk so a restart resumes
  * at the previous tip instead of fetching them again: the VolatileDB of ouroboros-consensus, as an
  * append-only log of the chain's own updates.
  *
  * The store is a hint, not a source of truth: whatever it returns is validated by applying it to
  * the immutable state, and anything it loses or cannot return is simply fetched from a peer. It
  * must never return a block that was not appended (checksums), and it must never lose a block that
  * the immutable store does not hold ([[gc]] is only given a slot the immutable store has made
  * durable).
  */
trait VolatileStore[F[_]]:
  /** Log an adopted block; where its record is. */
  def append(ref: BlockRef, raw: Bytes)(using
      cats.mtl.Raise[F, StorageError]
  ): F[VolatileStore.Location]

  /** The block whose record is at `at`, none when the bytes there are not one whole block record
    * (they do not pass the checksum). A location of a file that [[gc]] has deleted is an
    * [[StorageError.Io]]: callers drop their locations below the slot they collect first.
    */
  def read(at: VolatileStore.Location)(using
      cats.mtl.Raise[F, StorageError]
  ): F[Option[(BlockRef, Bytes)]]

  /** Log a rollback of the chain to `to` (`Origin` or a block). */
  def rollback(to: ChainSync.Point)(using cats.mtl.Raise[F, StorageError]): F[Unit]

  /** Delete the log files all of whose blocks are at or below `durableSlot` (the slot of the newest
    * block the immutable store has forced to disk). The newest file is kept.
    */
  def gc(durableSlot: BigInt)(using cats.mtl.Raise[F, StorageError]): F[Unit]

  /** Delete every log file but the newest whose blocks all have a slot below `slot`, wherever it is
    * in the log, and answer the numbers of the files deleted: the reference's VolatileDB collection
    * (`garbageCollectImpl` and `getFilesToGC`, `Storage/VolatileDB/Impl.hs` 453-516, and
    * `FileInfo.canGC`, `Impl/FileInfo.hs` 88-97, ouroboros-consensus 82ecba32), which takes any
    * file but the one being written whose newest slot is below the slot, empty files too. For a log
    * of blocks only: a log with rollback records is collected from its oldest file ([[gc]]), since
    * its rollbacks are replayed in order.
    */
  def collect(slot: BigInt)(using cats.mtl.Raise[F, StorageError]): F[Set[Int]]

  /** Force the log to disk. */
  def sync(using cats.mtl.Raise[F, StorageError]): F[Unit]

object VolatileStore:
  /** Where a block record is: the log file, the offset of the record in it and its length (header
    * and payload), as the reference's VolatileDB keeps the file, offset and size of each block in
    * its in-memory index (`InternalBlockInfo`,
    * Ouroboros/Consensus/Storage/VolatileDB/Impl/Types.hs: 127-133 at
    * `release-ouroboros-consensus-4.2.1.0`) and reads the block from there
    * (`getBlockComponentImpl`, VolatileDB/Impl.hs:259-306).
    */
  final case class Location(seq: Int, offset: Long, length: Int)

  /** What opening found.
    *
    * @param blocks
    *   Blocks on the logged chain (after applying the rollbacks).
    * @param truncatedBytes
    *   Bytes of a torn or damaged tail that were cut off, and files after it that were removed.
    */
  final case class OpenReport(
      blocks: Int,
      files: Int,
      truncatedBytes: Long,
      removedFiles: Int
  )

  /** The chain a log describes: its blocks in order, rollbacks applied. A rollback to a block the
    * log no longer holds (it became immutable and its file was collected) leaves nothing, which is
    * where the volatile chain starts.
    */
  def chainOf(records: Iterable[VolatileRecord]): Vector[(BlockRef, Bytes)] =
    records.foldLeft(Vector.empty[(BlockRef, Bytes)]) {
      case (chain, VolatileRecord.Block(ref, raw)) => chain :+ (ref -> raw)
      case (chain, VolatileRecord.Rollback(to))    => rolledBack(chain, to)
    }

  /** `chain` rolled back to `to`, as [[chainOf]] applies a rollback record. */
  private def rolledBack[A](
      chain: Vector[(BlockRef, A)],
      to: ChainSync.Point
  ): Vector[(BlockRef, A)] =
    to match
      case ChainSync.Point.Origin => Vector.empty
      case ChainSync.Point.Block(slot, hash) =>
        val i = chain.indexWhere((r, _) => r.hash == hash && r.slot == slot.value)
        if i >= 0 then chain.take(i + 1) else Vector.empty

  // Record layout (big-endian):
  //   crc u32 | kind u8 | flags u8 | length u32 | slot u64 | blockNo u64 | hash[32] | payload[length]
  // The CRC-32 covers every byte after the crc field.
  val HeaderSize = 58
  private val KindBlock = 1
  private val KindRollback = 2
  private val FlagBoundary = 1
  private val FlagOrigin = 2

  def encode(record: VolatileRecord): Array[Byte] =
    val (kind, flags, slot, blockNo, hash, payload) = record match
      case VolatileRecord.Block(ref, raw) =>
        (
          KindBlock,
          if ref.boundary then FlagBoundary else 0,
          ref.slot,
          ref.blockNo,
          ref.hash.toArray,
          raw.toArray
        )
      case VolatileRecord.Rollback(ChainSync.Point.Origin) =>
        (KindRollback, FlagOrigin, BigInt(0), BigInt(0), new Array[Byte](32), Array.empty[Byte])
      case VolatileRecord.Rollback(ChainSync.Point.Block(slot, hash)) =>
        (KindRollback, 0, slot.value, BigInt(0), hash.toArray, Array.empty[Byte])
    val b = ByteBuffer.allocate(HeaderSize + payload.length)
    b.putInt(0).put(kind.toByte).put(flags.toByte).putInt(payload.length)
    b.putLong(slot.toLong).putLong(blockNo.toLong).put(hash).put(payload)
    val a = b.array()
    val crc = new CRC32()
    crc.update(a, 4, a.length - 4)
    ByteBuffer.wrap(a).putInt(0, crc.getValue.toInt)
    a

  /** The valid prefix of a log file: its records, each with the offset where it ends. Parsing stops
    * at the first record that is incomplete, fails its checksum or is not understood.
    */
  def decodeAll(bytes: Array[Byte]): Vector[(VolatileRecord, Int)] =
    // the record that starts at `at`, and where it ends, if it is complete, checks and is understood
    def recordAt(at: Int): Option[(VolatileRecord, Int)] =
      val b = ByteBuffer.wrap(bytes, at, bytes.length - at)
      val crc = b.getInt
      val kind = b.get() & 0xff
      val flags = b.get() & 0xff
      val length = b.getInt
      val slot = b.getLong
      val blockNo = b.getLong
      val hash = new Array[Byte](32)
      b.get(hash)
      if length < 0 || length > bytes.length - at - HeaderSize || slot < 0 || blockNo < 0 then None
      else
        val end = at + HeaderSize + length
        val check = new CRC32()
        check.update(bytes, at + 4, end - at - 4)
        if check.getValue.toInt != crc then None
        else
          val record = kind match
            case KindBlock =>
              val payload = java.util.Arrays.copyOfRange(bytes, at + HeaderSize, end)
              Some(
                VolatileRecord.Block(
                  BlockRef(slot, Bytes.fromArray(hash), blockNo, (flags & FlagBoundary) != 0),
                  Bytes.fromArray(payload)
                )
              )
            case KindRollback if length == 0 =>
              Some(
                VolatileRecord.Rollback(
                  if (flags & FlagOrigin) != 0 then ChainSync.Point.Origin
                  else
                    ChainSync.Point
                      .Block(ChainSync.UInt64.saturating(slot), Bytes.fromArray(hash))
                )
              )
            case _ => None
          record.map(_ -> end)
    @tailrec def go(at: Int, out: Vector[(VolatileRecord, Int)]): Vector[(VolatileRecord, Int)] =
      if bytes.length - at < HeaderSize then out
      else
        recordAt(at) match
          case Some(found @ (_, end)) => go(end, out :+ found)
          case None                   => out
    go(0, Vector.empty)

  /** What [[open]] and [[openIndex]] hand back besides the store: the chain the log describes, each
    * block with what the open keeps of it (`A`: its body, or where its record is), and where every
    * block record of the log is, in log order (a block logged twice appears twice).
    */
  final case class Opened[A](
      chain: Vector[(BlockRef, A)],
      report: OpenReport,
      located: Vector[(BlockRef, Location)],
      cuts: Vector[Cut]
  )

  /** Why the check the open is given refuses a record's block, as the reference's parser tells a
    * block it cannot decode from one it finds corrupt (`parseBlockFile`,
    * `Storage/VolatileDB/Impl/Parser.hs` 86-107, ouroboros-consensus 82ecba329).
    */
  enum Refusal:
    /** The block does not decode. */
    case Undecodable(error: StorageError)

    /** The block decodes, but the check finds it corrupt. */
    case Corrupt(error: StorageError)

  /** Why a record cannot be read, the reason a `BlockReadErr` carries (the reference's
    * `ReadIncrementalErr`, which describes its reader of bare CBOR blocks).
    */
  enum ReadFailure:
    /** The record is incomplete, fails its checksum, is not understood or is not a block. */
    case Unreadable

    /** The record's block does not decode. */
    case Undecodable(error: StorageError)

  /** Why the open cut a file where it did: the reference's VolatileDB's `ParseError`
    * (`Storage/VolatileDB/Impl/Types.hs` 77-87, ouroboros-consensus 82ecba329, the same at
    * 808e40054).
    */
  enum CutCause:
    /** The record there cannot be read (`BlockReadErr`). */
    case ReadFailed(failure: ReadFailure)

    /** The block of the record there, `hash`, decodes but is corrupt (`BlockCorruptedErr`). */
    case Corrupted(hash: Bytes)

    /** The block of the record there, `hash`, is already indexed from the file `first`, this file
      * or an earlier one, and occurs again in `second`, the file cut (`DuplicatedBlock`).
      */
    case Duplicated(hash: Bytes, first: String, second: String)

  /** A cut the open made: the file, by the name it is listed under, the offset it was cut at and
    * why, as the reference's VolatileDB traces `Truncate` for each truncation
    * (`Storage/VolatileDB/Impl/Types.hs` 99; `truncateError`, `Storage/VolatileDB/Impl/State.hs`
    * 438-450, ouroboros-consensus 82ecba329, the same at 808e40054).
    */
  final case class Cut(file: String, offset: Long, cause: CutCause)

  /** What a log of blocks only reports as it works, as the reference's VolatileDB traces it
    * (`TraceEvent`, `Storage/VolatileDB/Impl/Types.hs` 96-102, ouroboros-consensus 82ecba329, the
    * same at 808e40054).
    */
  enum Trace:
    /** A cut of a file at the open, just before it is made (`Truncate`). */
    case Truncated(cut: Cut)

    /** The files of the log's directory whose names the open does not read as log files, which it
      * leaves alone (`InvalidFileNames`).
      */
    case InvalidFileNames(names: Vector[String])

    /** A block to be logged that the log holds already, and is not logged again
      * (`BlockAlreadyHere`).
      */
    case BlockAlreadyHere(hash: Bytes)

  private final case class FileInfo(seq: Int, blocks: Int, maxSlot: BigInt)

  /** What the scan at open has found so far: references and locations, and of each block on the
    * chain only what the open keeps (`A`). No other body outlives the record it was read in.
    */
  private final case class Scanned[A](
      infos: Vector[FileInfo],
      chain: Vector[(BlockRef, A)],
      located: Vector[(BlockRef, Location)],
      cut: Long,
      removed: Int,
      damaged: Boolean
  )
  private object Scanned:
    def empty[A]: Scanned[A] = Scanned(Vector.empty, Vector.empty, Vector.empty, 0L, 0, false)

  /** Fill `buffer` from `channel`, its position `p` read from the file at `at + p`; false when the
    * file ends first.
    */
  @tailrec private def fill(channel: FileChannel, buffer: ByteBuffer, at: Long): Boolean =
    if !buffer.hasRemaining then true
    else if channel.read(buffer, at + buffer.position()) < 0 then false
    else fill(channel, buffer, at)

  /** The bytes a record's checksum is checked a chunk of at a time. */
  private val ChecksumChunk = 1 << 20

  /** Whether the bytes of the file open on `channel` from `from` up to `end` have the CRC-32 `crc`,
    * read a chunk at a time: a record's checksum checked before the record is read whole, so a
    * record whose checksum fails is never allocated.
    */
  private def checksummed(channel: FileChannel, from: Long, end: Long, crc: Int): Boolean =
    val check = new CRC32()
    val chunk = ByteBuffer.allocate(math.min(ChecksumChunk.toLong, end - from).toInt)
    @tailrec def go(at: Long): Boolean =
      if at >= end then check.getValue.toInt == crc
      else
        chunk.clear()
        chunk.limit(math.min(chunk.capacity.toLong, end - at).toInt)
        if !fill(channel, chunk, at) then false
        else
          check.update(chunk.array(), 0, chunk.limit())
          go(at + chunk.limit())
    go(from)

  /** The valid prefix of the log file open on `channel`, as [[decodeAll]] finds it, folded record
    * by record into `z`, and the offset where the prefix ends. One record is read at a time and
    * only what `step` keeps outlives it, as the reference's VolatileDB parses a file incrementally
    * and keeps only each block's index entry (`parseBlockFile`,
    * `Storage/VolatileDB/Impl/Parser.hs:34-40,52-104`, ouroboros-consensus 82ecba32). The prefix
    * ends where a record that cannot be read starts (`unreadable`), or where `step` refuses one
    * (its reason); none when it is the whole file.
    */
  private def foldRecords[A, R](channel: FileChannel, seq: Int, z: A, unreadable: R)(
      step: (A, VolatileRecord, Location) => Either[R, A]
  ): (A, Long, Option[R]) =
    val size = channel.size()
    @tailrec def go(at: Long, acc: A): (A, Long, Option[R]) =
      val header = ByteBuffer.allocate(HeaderSize)
      if at == size then (acc, at, None)
      else if size - at < HeaderSize || !fill(channel, header, at) then (acc, at, Some(unreadable))
      else
        val length = header.getInt(6)
        val kind = header.get(4) & 0xff
        // a length past the file's end or past what an Int holds, or a header [[decodeAll]] does
        // not understand, is not a record, and one whose bytes fail the checksum is not read whole
        val understood =
          header.getLong(10) >= 0 && header.getLong(18) >= 0 &&
            (kind == KindBlock || (kind == KindRollback && length == 0))
        if length < 0 || length > Int.MaxValue - HeaderSize || length > size - at - HeaderSize ||
          !understood
        then (acc, at, Some(unreadable))
        else if !checksummed(channel, at + 4, at + HeaderSize + length, header.getInt(0)) then
          (acc, at, Some(unreadable))
        else
          val record = new Array[Byte](HeaderSize + length)
          System.arraycopy(header.array(), 0, record, 0, HeaderSize)
          if !fill(channel, ByteBuffer.wrap(record, HeaderSize, length), at) then
            (acc, at, Some(unreadable))
          else
            decodeAll(record) match
              case Vector((found, end)) if end == record.length =>
                step(acc, found, Location(seq, at, end)) match
                  case Right(next) => go(at + end, next)
                  case Left(why)   => (acc, at, Some(why))
              case _ => (acc, at, Some(unreadable))
    go(0L, z)

  /** Force the directory's entries (a file created, cut or removed) to disk. */
  private def syncDir(root: java.nio.file.Path): Unit =
    Using.resource(FileChannel.open(root, StandardOpenOption.READ))(_.force(true))

  /** Cut the file at `path` to its first `good` bytes, forced to disk. */
  private def truncate(path: java.nio.file.Path, good: Long): Unit =
    Using.resource(FileChannel.open(path, StandardOpenOption.WRITE)) { ch =>
      ch.truncate(good)
      ch.force(true)
    }

  private val Suffix = ".log"
  private def nameOf(seq: Int) = s"${FileNumber.padded(seq, 8)}$Suffix"

  /** What the recovery at open found: each file's blocks and newest slot, in order of number, and
    * what the open hands back.
    */
  private final case class Found[A](infos: Vector[FileInfo], opened: Opened[A])

  /** Open (or create) the log in `dir`. Files are `<seq>.log`; a file holds `blocksPerFile` blocks,
    * and the next one is started when it fills. Opening validates every file in order, cuts the
    * first damaged one at its last good record and removes the files after it ([[inOrder]]): the
    * log's rollbacks are replayed in order, and one after a gap would apply to a chain that is not
    * the one it was logged on. The recovered chain keeps the bodies of its blocks: what resuming
    * the selected chain applies.
    */
  def open[F[_]: Async](dir: Path, blocksPerFile: Int = 512)(using
      Raise[F, StorageError]
  ): Resource[F, (VolatileStore[F], Opened[Bytes])] =
    opening[F, Bytes](dir, blocksPerFile)((root, listing) =>
      guarded(root.toString)(inOrder((raw, _) => raw)(root, listing.numbered))
    )

  /** As [[open]], keeping no body: the recovered chain has where each block's record is, and a body
    * is read back with [[VolatileStore.read]]. The open reads one record at a time, so it never
    * holds more than one body, as the reference's VolatileDB rebuilds its in-memory index from its
    * files and keeps no block (`OpenState`, `Storage/VolatileDB/Impl/State.hs:92-108`,
    * ouroboros-consensus 82ecba32).
    */
  def openIndex[F[_]: Async](dir: Path, blocksPerFile: Int = 512)(using
      Raise[F, StorageError]
  ): Resource[F, (VolatileStore[F], Opened[Location])] =
    opening[F, Location](dir, blocksPerFile)((root, listing) =>
      guarded(root.toString)(inOrder((_, at) => at)(root, listing.numbered))
    )

  /** The open of a log of blocks only (for [[ReceivedBlocks]]): an index of its blocks, each with
    * where its record is and what `read(ref, body)` makes of the record, read while the record is
    * read and the body dropped after, as the reference's VolatileDB decodes each block of a file as
    * it parses it and keeps its `BlockInfo` in its index (`parseBlockFile` and `extractBlockInfo`,
    * `Storage/VolatileDB/Impl/Parser.hs` 52-128; `Storage/VolatileDB/Impl/Types.hs` 127-133,
    * ouroboros-consensus 82ecba329). A record `read` refuses is one whose block the reference could
    * not read, and its file is cut there. The names of the directory's files it does not read are
    * traced first, then each cut just before it is made, as the reference traces each truncation
    * and then truncates; each cut is in [[Opened.cuts]] too. Each file is validated on its own
    * ([[eachFile]]), as the reference's VolatileDB opens its files (`mkOpenStateHelper`,
    * `Storage/VolatileDB/Impl/State.hs` 372-436, ouroboros-consensus 82ecba329, the same at
    * 808e40054). The recovered chain is the index, a block once, in the order of its records.
    */
  def openIndexWith[F[_]: Async, N](dir: Path, blocksPerFile: Int = 512)(
      read: (BlockRef, Bytes) => Either[Refusal, N],
      trace: Trace => F[Unit]
  )(using Raise[F, StorageError]): Resource[F, (VolatileStore[F], Opened[(Location, N)])] =
    opening[F, (Location, N)](dir, blocksPerFile)(eachFile(read, trace))

  /** The recovery of a log with rollbacks: the files are read in order of number, the first damaged
    * file is cut at its last good record and every file after it is removed. The chain keeps
    * `keep(body, location)` of each of its blocks.
    */
  private def inOrder[A](keep: (Bytes, Location) => A)(
      root: java.nio.file.Path,
      names: Vector[(Int, String)]
  ): Found[A] =
    // what was scanned, and the file cut (the first damaged one), if any
    val (scanned, cuts) = names.foldLeft((Scanned.empty[A], Vector.empty[Cut])) {
      case ((acc, cuts), (seq, name)) =>
        val path = root.resolve(name)
        if acc.damaged then
          Files.delete(path)
          (acc.copy(removed = acc.removed + 1), cuts)
        else
          val (read, good, size) =
            Using.resource(FileChannel.open(path, StandardOpenOption.READ)) { ch =>
              val (read, good, _) =
                foldRecords(ch, seq, (acc, FileInfo(seq, 0, BigInt(-1))), ()) {
                  case ((scanned, info), VolatileRecord.Block(ref, raw), at) =>
                    Right(
                      (
                        scanned.copy(
                          chain = scanned.chain :+ (ref -> keep(raw, at)),
                          located = scanned.located :+ (ref -> at)
                        ),
                        info.copy(blocks = info.blocks + 1, maxSlot = info.maxSlot.max(ref.slot))
                      )
                    )
                  case ((scanned, info), VolatileRecord.Rollback(to), _) =>
                    Right((scanned.copy(chain = rolledBack(scanned.chain, to)), info))
                }
              (read, good, ch.size())
            }
          val torn = good < size
          if torn then truncate(path, good)
          val (found, info) = read
          (
            found.copy(
              infos = found.infos :+ info,
              cut = found.cut + (if torn then size - good else 0L),
              damaged = torn
            ),
            if torn then cuts :+ Cut(name, good, CutCause.ReadFailed(ReadFailure.Unreadable))
            else cuts
          )
    }
    if scanned.cut > 0 || scanned.removed > 0 then syncDir(root)
    val Scanned(infos, chain, located, cut, removed, _) = scanned
    Found(infos, Opened(chain, OpenReport(chain.size, infos.size, cut, removed), located, cuts))

  /** What the recovery of a log of blocks only has found so far: each indexed block's reference,
    * where its record is and what is kept of its body (`N`), by hash; where each block record kept
    * is, in log order; the files read and the bytes cut.
    */
  private final case class Indexed[N](
      infos: Vector[FileInfo],
      index: Map[Bytes, (BlockRef, Location, N)],
      located: Vector[(BlockRef, Location)],
      cut: Long,
      cuts: Vector[Cut],
      files: Map[Bytes, String]
  )
  private object Indexed:
    def empty[N]: Indexed[N] =
      Indexed(Vector.empty, Map.empty, Vector.empty, 0L, Vector.empty, Map.empty)

  /** The blocks of the log file open on `channel`, each with where its record is and what `read`
    * makes of it, up to the first record that is incomplete, fails its checksum, is not understood,
    * is not a block or whose block `read` refuses, and the offset where they end: the reference's
    * `parseBlockFile`, which decodes the blocks of a file in turn and stops at the first it cannot
    * read (`BlockReadErr`) or, validating, finds corrupt (`BlockCorruptedErr`)
    * (`Storage/VolatileDB/Impl/Parser.hs` 52-107, ouroboros-consensus 82ecba329). A log of blocks
    * only holds no rollback record, so one is a record the parse cannot read.
    */
  private[storage] def parsedBlocks[N](channel: FileChannel, seq: Int)(
      read: (BlockRef, Bytes) => Either[Refusal, N]
  ): (Vector[(BlockRef, Location, N)], Long, Option[CutCause]) =
    val unreadable: CutCause = CutCause.ReadFailed(ReadFailure.Unreadable)
    foldRecords[Vector[(BlockRef, Location, N)], CutCause](channel, seq, Vector.empty, unreadable) {
      case (parsed, VolatileRecord.Block(ref, raw), at) =>
        read(ref, raw) match
          case Right(n) => Right(parsed :+ ((ref, at, n)))
          case Left(Refusal.Undecodable(error)) =>
            Left(CutCause.ReadFailed(ReadFailure.Undecodable(error)))
          case Left(Refusal.Corrupt(_)) => Left(CutCause.Corrupted(ref.hash))
      case (_, VolatileRecord.Rollback(_), _) => Left(unreadable)
    }

  /** `parsed` added to `index` in order up to the first block the index already holds, from an
    * earlier file or earlier in the same one, whatever the bytes of its record: the index after,
    * the blocks added, and the offset of the first such block's record and its hash. The
    * reference's `addToReverseIndex`, whose file is cut there (`DuplicatedBlock`,
    * `Storage/VolatileDB/Impl/State.hs` 464-492, ouroboros-consensus 82ecba329).
    */
  private[storage] def addedToIndex[N](
      index: Map[Bytes, (BlockRef, Location, N)],
      parsed: Vector[(BlockRef, Location, N)]
  ): (
      Map[Bytes, (BlockRef, Location, N)],
      Vector[(BlockRef, Location, N)],
      Option[(Long, Bytes)]
  ) =
    @tailrec def go(
        index: Map[Bytes, (BlockRef, Location, N)],
        accepted: Vector[(BlockRef, Location, N)],
        rest: List[(BlockRef, Location, N)]
    ): (
        Map[Bytes, (BlockRef, Location, N)],
        Vector[(BlockRef, Location, N)],
        Option[(Long, Bytes)]
    ) =
      rest match
        case Nil => (index, accepted, None)
        case (entry @ (ref, at, _)) :: more =>
          index.get(ref.hash) match
            case Some(_) => (index, accepted, Some((at.offset, ref.hash)))
            case None    => go(index.updated(ref.hash, entry), accepted :+ entry, more)
    go(index, Vector.empty, parsed.toList)

  /** The recovery of a log of blocks only, as the reference's VolatileDB validates its files
    * (`mkOpenStateHelper` and `validateFile`, `Storage/VolatileDB/Impl/State.hs` 372-436,
    * ouroboros-consensus 82ecba329, the same at 808e40054), a file at a time ([[validated]]), each
    * cut given to `cut` before it is made and before the next file is read. The index keeps the
    * first record of each block, as the reference's writer never logs a block twice
    * (`putBlockImpl`, `Storage/VolatileDB/Impl.hs` 387-399).
    */
  private def eachFile[F[_]: Async, N](
      read: (BlockRef, Bytes) => Either[Refusal, N],
      trace: Trace => F[Unit]
  )(root: java.nio.file.Path, listing: Listing)(using
      Raise[F, StorageError]
  ): F[Found[(Location, N)]] =
    trace(Trace.InvalidFileNames(listing.ignored)).whenA(listing.ignored.nonEmpty) *>
      listing.numbered
        .foldLeftM(Indexed.empty[N]) { case (acc, (seq, name)) =>
          validated(read, c => trace(Trace.Truncated(c)))(root, acc, seq, name)
        }
        .flatMap(indexed =>
          guarded(root.toString) {
            if indexed.cut > 0 then syncDir(root)
            val chain = indexed.index.valuesIterator.toVector
              .sortBy((_, at, _) => (at.seq, at.offset))
              .map((ref, at, n) => ref -> (at, n))
            Found(
              indexed.infos,
              Opened(
                chain,
                OpenReport(chain.size, indexed.infos.size, indexed.cut, 0),
                indexed.located,
                indexed.cuts
              )
            )
          }
        )

  /** One file of a log of blocks only validated, as the reference's `validateFile`
    * (`Storage/VolatileDB/Impl/State.hs` 412-436): parsed ([[parsedBlocks]]) and cut where the
    * parse stopped, then its blocks added to the index ([[addedToIndex]]) and cut again at its
    * first block the index already held, in this order. Each cut is given to `cut` and then made,
    * as the reference traces each truncation and then truncates (`truncateError`, 438-450). A
    * file's blocks and newest slot are those of the blocks it added; a repeat names the file that
    * holds the block, this one when it was added from earlier in it.
    */
  private def validated[F[_]: Async, N](
      read: (BlockRef, Bytes) => Either[Refusal, N],
      cut: Cut => F[Unit]
  )(root: java.nio.file.Path, acc: Indexed[N], seq: Int, name: String)(using
      Raise[F, StorageError]
  ): F[Indexed[N]] =
    val path = root.resolve(name)
    def made(c: Cut): F[Unit] = cut(c) *> guarded(path.toString)(truncate(path, c.offset))
    for
      scanned <- guarded(path.toString)(
        Using.resource(FileChannel.open(path, StandardOpenOption.READ))(ch =>
          (parsedBlocks(ch, seq)(read), ch.size())
        )
      )
      ((parsed, parsedTo, stopped), size) = scanned
      parseCut = stopped.map(Cut(name, parsedTo, _))
      _ <- parseCut.traverse_(made)
      (index, accepted, repeated) = addedToIndex(acc.index, parsed)
      repeatCut = repeated.map((offset, hash) =>
        Cut(name, offset, CutCause.Duplicated(hash, acc.files.getOrElse(hash, name), name))
      )
      _ <- repeatCut.traverse_(made)
      cuts = parseCut.toVector ++ repeatCut
      good = cuts.lastOption.fold(size)(_.offset)
      info = accepted.foldLeft(FileInfo(seq, 0, BigInt(-1))) { case (info, (ref, _, _)) =>
        info.copy(blocks = info.blocks + 1, maxSlot = info.maxSlot.max(ref.slot))
      }
    yield Indexed(
      acc.infos :+ info,
      index,
      acc.located ++ accepted.map((ref, at, _) => ref -> at),
      acc.cut + (size - good),
      acc.cuts ++ cuts,
      acc.files ++ accepted.map((ref, _, _) => ref.hash -> name)
    )

  /** `body`, run as blocking work, an exception it throws the storage error of `what`. */
  private def guarded[F[_]: Async, A](what: String)(body: => A)(using
      Raise[F, StorageError]
  ): F[A] =
    Async[F].blocking(body).attempt.flatMap {
      case Right(a) => Async[F].pure(a)
      case Left(e)  => StorageError.Io(what, e).raise[F, A]
    }

  /** A log's directory as the open lists it: its files and their numbers, in order of number, and
    * the names of the files it does not read.
    */
  private final case class Listing(numbered: Vector[(Int, String)], ignored: Vector[String])
  private object Listing:
    /** The listing of `names`, each with its number when it has one; refused when two names have
      * one number.
      */
    def of(names: Vector[(String, Option[Int])]): Either[FileNameProblem, Listing] =
      val numbered = names.collect { case (name, Some(n)) => n -> name }.sortBy(_._1)
      numbered
        .groupBy(_._1)
        .filter((_, files) => files.sizeIs > 1)
        .minByOption(_._1)
        .fold[Either[FileNameProblem, Listing]](
          Right(Listing(numbered, names.collect { case (name, None) => name }.sorted))
        )((n, files) => Left(FileNameProblem.SameNumber(n, files.map(_._2).sorted)))

  /** The open: the files recovered by `recover`, and a store that appends after the newest. */
  private def opening[F[_]: Async, A](dir: Path, blocksPerFile: Int)(
      recover: (java.nio.file.Path, Listing) => F[Found[A]]
  )(using Raise[F, StorageError]): Resource[F, (VolatileStore[F], Opened[A])] =
    val F = Async[F]
    val root = dir.toNioPath

    def guard[A](what: String)(body: => A)(using cats.mtl.Raise[F, StorageError]): F[A] =
      guarded(what)(body)

    final class Current(val seq: Int, val channel: FileChannel)

    /** The log's files and their numbers, in order of number, and the names of the other files of
      * its directory, read before any file is changed. A name that holds a locale's digits
      * ([[FileNameProblem.LocaleDigits]]), or two names of one number
      * ([[FileNameProblem.SameNumber]]), refuses the log as it is.
      */
    val listed: F[Listing] =
      guard(root.toString) {
        Files.createDirectories(root)
        Using.resource(Files.list(root))(_.iterator.asScala.map(_.getFileName.toString).toVector)
      }.flatMap(names =>
        names
          .traverse(n => FileNumber.numberOf(Suffix)(n).map(n -> _))
          .flatMap(Listing.of)
          .fold(StorageError.Corrupt(root.toString, _).raise[F, Listing], F.pure)
      )

    /** Read the existing files; returns what was found and what was repaired. */
    val scan: F[Found[A]] = listed.flatMap(listing => recover(root, listing))

    Resource
      .make(
        for
          found <- scan
          Found(infos, opened) = found
          mutex <- Mutex[F]
          // the newest file, unless it is full or there is none: then a new one after it
          (seq, startNew, opening) = infos.lastOption match
            case Some(last) if last.blocks < blocksPerFile =>
              (last.seq, false, NonEmptyVector.one(last).prependVector(infos.dropRight(1)))
            case newest =>
              val next = newest.fold(0)(_.seq + 1)
              (next, true, NonEmptyVector.one(FileInfo(next, 0, BigInt(-1))).prependVector(infos))
          channel <- guard(root.toString) {
            val ch = FileChannel.open(
              root.resolve(nameOf(seq)),
              StandardOpenOption.CREATE,
              StandardOpenOption.READ,
              StandardOpenOption.WRITE
            )
            ch.position(ch.size())
            if startNew then syncDir(root)
            ch
          }
          files <- Ref.of[F, NonEmptyVector[FileInfo]](opening)
          current <- Ref.of[F, Current](new Current(seq, channel))
        yield (mutex, files, current, opened)
      ) { (_, _, current, _) =>
        current.get.flatMap(c => guard(root.toString)(Using.resource(c.channel)(_.force(true))))
      }
      .map { (mutex, files, current, opened) =>
        val store = new VolatileStore[F]:
          private def write(channel: FileChannel, bytes: Array[Byte]): Unit =
            @tailrec def rest(buf: ByteBuffer): Unit =
              if buf.hasRemaining then
                channel.write(buf)
                rest(buf)
            rest(ByteBuffer.wrap(bytes))

          /** Start a new file when the current one holds `blocksPerFile` blocks: right after the
            * block that fills it, as the reference's VolatileDB opens its next file when a write
            * fills the current one (`putBlockImpl` and `nextFile`, `Storage/VolatileDB/Impl.hs`
            * 387-399 and 574-603, ouroboros-consensus 82ecba329, the same at 808e40054), so that a
            * full file is no longer the one being written and can be collected.
            */
          private def roll(using cats.mtl.Raise[F, StorageError]): F[Unit] =
            files.get.flatMap { fs =>
              if fs.last.blocks < blocksPerFile then F.unit
              else
                current.get.flatMap { c =>
                  val next = c.seq + 1
                  guard(root.toString) {
                    c.channel.force(true)
                    c.channel.close()
                    val ch = FileChannel.open(
                      root.resolve(nameOf(next)),
                      StandardOpenOption.CREATE,
                      StandardOpenOption.READ,
                      StandardOpenOption.WRITE
                    )
                    syncDir(root)
                    ch
                  }.flatMap(ch =>
                    current.set(new Current(next, ch)) *>
                      files.update(_ :+ FileInfo(next, 0, BigInt(-1)))
                  )
                }
            }

          private def log(record: VolatileRecord)(using
              cats.mtl.Raise[F, StorageError]
          ): F[Location] =
            mutex.lock.surround {
              for
                c <- current.get
                bytes = encode(record)
                at <- guard(root.toString) {
                  val offset = c.channel.position()
                  write(c.channel, bytes)
                  Location(c.seq, offset, bytes.length)
                }
                _ <- record match
                  case VolatileRecord.Block(ref, _) =>
                    files.update(fs =>
                      NonEmptyVector
                        .one(
                          fs.last.copy(
                            blocks = fs.last.blocks + 1,
                            maxSlot = fs.last.maxSlot.max(ref.slot)
                          )
                        )
                        .prependVector(fs.init)
                    ) *> roll
                  case _ => F.unit
              yield at
            }

          def append(ref: BlockRef, raw: Bytes)(using
              cats.mtl.Raise[F, StorageError]
          ): F[Location] =
            log(VolatileRecord.Block(ref, raw))

          def read(at: Location)(using
              cats.mtl.Raise[F, StorageError]
          ): F[Option[(BlockRef, Bytes)]] =
            mutex.lock.surround {
              val path = root.resolve(nameOf(at.seq))
              guard(path.toString) {
                Using.resource(FileChannel.open(path, StandardOpenOption.READ)) { ch =>
                  // until the buffer is full or the file ends
                  @tailrec def fill(buf: ByteBuffer): Array[Byte] =
                    if buf.hasRemaining && ch.read(buf, at.offset + buf.position()) >= 0 then
                      fill(buf)
                    else buf.array()
                  fill(ByteBuffer.allocate(at.length))
                }
              }.map { bytes =>
                decodeAll(bytes) match
                  case Vector((VolatileRecord.Block(ref, raw), end)) if end == bytes.length =>
                    Some(ref -> raw)
                  case _ => None
              }
            }

          def rollback(to: ChainSync.Point)(using cats.mtl.Raise[F, StorageError]): F[Unit] =
            log(VolatileRecord.Rollback(to)).void

          def sync(using cats.mtl.Raise[F, StorageError]): F[Unit] =
            mutex.lock.surround(
              current.get.flatMap(c => guard(root.toString)(c.channel.force(true)))
            )

          def gc(durableSlot: BigInt)(using cats.mtl.Raise[F, StorageError]): F[Unit] =
            mutex.lock.surround {
              files.get.flatMap { fs =>
                val doomed = fs.init.takeWhile(_.maxSlot <= durableSlot)
                if doomed.isEmpty then F.unit
                else
                  guard(root.toString) {
                    doomed.foreach(f => Files.deleteIfExists(root.resolve(nameOf(f.seq))))
                    syncDir(root)
                  } *> files.update(fs =>
                    NonEmptyVector.one(fs.last).prependVector(fs.init.drop(doomed.size))
                  )
              }
            }

          def collect(slot: BigInt)(using cats.mtl.Raise[F, StorageError]): F[Set[Int]] =
            mutex.lock.surround {
              files.get.flatMap { fs =>
                // the newest file is the one being written, which is never collected
                val doomed = fs.init.filter(_.maxSlot < slot)
                val gone = doomed.map(_.seq).toSet
                if gone.isEmpty then F.pure(gone)
                else
                  guard(root.toString) {
                    doomed.foreach(f => Files.deleteIfExists(root.resolve(nameOf(f.seq))))
                    syncDir(root)
                  } *> files
                    .update(fs =>
                      NonEmptyVector.one(fs.last).prependVector(fs.init.filterNot(f => gone(f.seq)))
                    )
                    .as(gone)
              }
            }
        (store: VolatileStore[F], opened)
      }
