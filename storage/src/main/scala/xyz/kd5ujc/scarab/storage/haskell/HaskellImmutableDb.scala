// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell

import java.util.zip.CRC32
import scala.annotation.tailrec
import scodec.{Attempt, Codec}
import scodec.bits.BitVector
import scodec.codecs.{int32, uint16, uint32, vector}
import xyz.kd5ujc.scarab.storage.Binary
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.codec.Bytes

/** A chunk number of the Haskell ImmutableDB (`NNNNN.chunk`, five digits). */
opaque type ChunkNo = Int

object ChunkNo:
  val zero: ChunkNo = 0
  def of(n: Int): Option[ChunkNo] = Option.when(n >= 0)(n)
  given Ordering[ChunkNo] = Ordering.Int

  extension (c: ChunkNo)
    def value: Int = c
    def next: ChunkNo = c + 1
    def stem: String = f"$c%05d"

/** Slots per chunk: `10 * k` of the first era (the Byron genesis' `k`; Shelley-start networks use
  * the same `k`). A chunk holds slots `[c * size, (c + 1) * size)`.
  */
opaque type ChunkSlots = Long

object ChunkSlots:
  def of(n: Long): Option[ChunkSlots] = Option.when(n > 0 && n < Int.MaxValue)(n)
  def fromSecurityParam(k: Long): Option[ChunkSlots] =
    if k > 0 && k < Int.MaxValue / 10 then of(k * 10) else None

  extension (s: ChunkSlots)
    def value: Long = s

    /** The first slot of chunk `c`. */
    def firstSlot(c: ChunkNo): Long = c.value.toLong * s

/** A byte offset into a chunk file. */
opaque type ByteOffset = Long

object ByteOffset:
  val zero: ByteOffset = 0L
  def of(n: Long): Option[ByteOffset] = Option.when(n >= 0)(n)

  extension (o: ByteOffset) def value: Long = o

  /** Eight big-endian bytes; a negative offset is [[Binary.Failure.OutOfRange]]. */
  given Codec[ByteOffset] = Binary.nonNegative(Binary.Field.BlockOffset)

/** What a block's place in the primary index says it is. */
enum BlockPosition:
  /** The epoch-boundary block of a Byron epoch (relative slot 0). Its secondary entry carries the
    * epoch number instead of a slot.
    */
  case Ebb(epoch: Long)

  /** A regular block at an absolute slot. */
  case Regular(slot: Long)

/** One 56-byte secondary index entry (`Secondary.hs`), big-endian, no header:
  * {{{
  * blockOffset u64 | headerOffset u16 | headerSize u16 | crc32 u32 | headerHash[32] | slotOrEpoch u64
  * }}}
  */
final case class SecondaryEntry(
    blockOffset: ByteOffset,
    headerOffset: Int,
    headerSize: Int,
    crc: Int,
    headerHash: Bytes,
    slotOrEpoch: Long
)

object SecondaryEntry:
  /** `getEntry` /`putEntry` of `Secondary.hs` (ouroboros-consensus 4.2.1.0, lines 108-133): the
    * `Binary` instances of `BlockOffset`, `HeaderOffset` and `HeaderSize` (lines 64-82) are
    * big-endian `Word64` and `Word16`, the checksum a `Word32`, the hash its raw 32 bytes, and
    * `putBlockOrEBB` (line 88) a `Word64`, a slot or an epoch. `Word64` fields beyond `Long.Max`
    * are not entries (no chain has such a slot), they fail as [[Binary.Failure.OutOfRange]].
    */
  given Codec[SecondaryEntry] =
    (summon[Codec[ByteOffset]] :: uint16 :: uint16 :: int32 :: Binary.bytes(32) ::
      Binary.nonNegative(Binary.Field.Slot)).as[SecondaryEntry]

/** Why a chunk's index files or data do not validate. Every case is something the Haskell node's
  * own validation rejects or repairs.
  */
enum ChunkFault:
  case PrimaryEmpty
  case PrimaryVersion(found: Int)
  case PrimaryLength(bytes: Int)
  case PrimaryFirstOffset(found: Long)
  case PrimaryStep(relativeSlot: Int, from: Long, to: Long)
  case PrimaryTooLong(offsets: Int, max: Long)
  case SecondaryLength(bytes: Int)
  case BlockOffsetRange(entry: Int)
  case SlotRange(entry: Int)
  case IndexCountMismatch(primary: Int, secondary: Int)
  case OffsetMismatch(entry: Int, expected: ByteOffset, found: ByteOffset)
  case BlockOutOfBounds(entry: Int, end: Long, chunkBytes: Long)
  case BlockNotCbor(entry: Int)
  case TrailingBytes(from: ByteOffset, chunkBytes: Long)
  case HeaderOutOfBlock(entry: Int)
  case CrcMismatch(entry: Int, expected: Int, actual: Int)
  case HeaderHashMismatch(entry: Int)
  case SlotMismatch(entry: Int, expected: Long, found: Long)

  def message: String = this match
    case PrimaryEmpty              => "primary index is empty"
    case PrimaryVersion(v)         => s"primary index version $v, expected 1"
    case PrimaryLength(n)          => s"primary index of $n bytes is not 1 + 4n"
    case PrimaryFirstOffset(o)     => s"primary index starts at offset $o, expected 0"
    case PrimaryStep(r, f, t)      => s"primary index offset steps $f -> $t at relative slot $r"
    case PrimaryTooLong(n, max)    => s"primary index has $n offsets, at most $max for a chunk"
    case SecondaryLength(n)        => s"secondary index of $n bytes is not a multiple of 56"
    case BlockOffsetRange(i)       => s"entry $i: block offset out of range"
    case SlotRange(i)              => s"entry $i: slot out of range"
    case IndexCountMismatch(p, s)  => s"primary index lists $p blocks, secondary index $s"
    case OffsetMismatch(i, e, f)   => s"entry $i: block offset ${f.value}, expected ${e.value}"
    case BlockOutOfBounds(i, e, n) => s"entry $i: block ends at $e, chunk file has $n bytes"
    case BlockNotCbor(i)           => s"entry $i: block is not a complete CBOR item"
    case TrailingBytes(f, n)       => s"${n - f.value} bytes after the last block"
    case HeaderOutOfBlock(i)       => s"entry $i: header lies outside the block"
    case CrcMismatch(i, e, a)      => s"entry $i: CRC32 ${hex(a)}, index says ${hex(e)}"
    case HeaderHashMismatch(i)     => s"entry $i: header hash differs from the index"
    case SlotMismatch(i, e, f)     => s"entry $i: slot $f, expected $e"

  private def hex(i: Int): String = f"$i%08x"

/** The primary index: `1` then big-endian `u32` offsets into the secondary file, one per relative
  * slot plus one. Relative slot 0 is the EBB slot, a regular block at slot `s` has relative slot
  * `s mod chunkSize + 1`. An offset step is 56 when the slot is filled, 0 when empty.
  */
final case class PrimaryIndex(offsets: Vector[Long]):
  /** The filled relative slots, in order; entry `i` of the secondary index belongs to `filled(i)`.
    */
  lazy val filled: Vector[Int] =
    (0 until math.max(offsets.size - 1, 0)).toVector.filter(r => offsets(r + 1) > offsets(r))

object PrimaryIndex:
  val Version = 1
  val EntrySize = 56

  private val offsets: Codec[Vector[Long]] = vector(uint32)

  /** `Primary.hs` (4.2.1.0): the version byte `currentVersionNumber = 1` (lines 155-156), then the
    * `SecondaryOffset` s, each a big-endian `Word32` (lines 84-88), up to the end of the file
    * (`get`, lines 313-323; `write`, lines 350-353). Offsets are kept as `Long`, so the full
    * `Word32` range reads back unsigned.
    */
  given Codec[PrimaryIndex] =
    (Binary.version(Version) ~> offsets).xmap(PrimaryIndex(_), _.offsets)

  /** The file's bytes; an offset beyond `Word32` (a chunk of 4 GiB of index) does not fit. */
  def encode(index: PrimaryIndex): Attempt[Array[Byte]] =
    summon[Codec[PrimaryIndex]].encode(index).map(_.toByteArray)

  /** The index with the given filled relative slots among `slots` relative slots (a finished chunk
    * has `chunkSlots + 1`, the EBB slot included).
    */
  def build(filled: Seq[Int], slots: Int): PrimaryIndex =
    val set = filled.toSet
    PrimaryIndex(
      (0 until slots)
        .scanLeft(0L)((acc, r) => if set.contains(r) then acc + EntrySize else acc)
        .toVector
    )

  /** Parse a primary index file. With `tolerateTornTail` a trailing partial offset (the node was
    * writing) is dropped instead of being a fault.
    */
  def parse(
      bytes: Array[Byte],
      chunkSlots: ChunkSlots,
      tolerateTornTail: Boolean
  ): Either[ChunkFault, PrimaryIndex] =
    val max = chunkSlots.value + 2
    for
      _ <- Either.cond(bytes.nonEmpty, (), ChunkFault.PrimaryEmpty)
      versioned <- Binary
        .version(Version)
        .decode(BitVector.view(bytes))
        .toEither
        .left
        .map {
          case Binary.Failure.UnknownVersion(found, _) => ChunkFault.PrimaryVersion(found)
          case _                                       => ChunkFault.PrimaryLength(bytes.length)
        }
      _ <- Either.cond(
        (bytes.length - 1) % 4 == 0 || tolerateTornTail,
        (),
        ChunkFault.PrimaryLength(bytes.length)
      )
      count = (bytes.length - 1) / 4
      _ <- Either.cond(count.toLong <= max, (), ChunkFault.PrimaryTooLong(count, max))
      decoded <- offsets
        .decode(versioned.remainder.take(count * 32L))
        .toEither
        .left
        .map(_ => ChunkFault.PrimaryLength(bytes.length))
      index <- check(decoded.value)
    yield index

  /** The offsets of a file are an index only if they start at 0 and step by an entry or not at all.
    */
  private def check(offsets: Vector[Long]): Either[ChunkFault, PrimaryIndex] =
    offsets.headOption match
      case Some(first) if first != 0L => Left(ChunkFault.PrimaryFirstOffset(first))
      case _ =>
        offsets
          .zip(offsets.drop(1))
          .zipWithIndex
          .collectFirst {
            case ((f, t), r) if t - f != 0L && t - f != EntrySize.toLong =>
              ChunkFault.PrimaryStep(r, f, t)
          }
          .toLeft(PrimaryIndex(offsets))

object SecondaryIndex:
  val EntrySize = 56

  private val entry = summon[Codec[SecondaryEntry]]

  /** The file's bytes; a header offset or size beyond `Word16`, or a hash that is not 32 bytes, has
    * no encoding (it used to be truncated silently).
    */
  def encode(entries: Seq[SecondaryEntry]): Attempt[Array[Byte]] =
    vector(entry).encode(entries.toVector).map(_.toByteArray)

  /** Parse a secondary index file. With `tolerateTornTail` a trailing partial entry is dropped. */
  def parse(
      bytes: Array[Byte],
      tolerateTornTail: Boolean
  ): Either[ChunkFault, Vector[SecondaryEntry]] =
    if bytes.length % EntrySize != 0 && !tolerateTornTail then
      Left(ChunkFault.SecondaryLength(bytes.length))
    else
      Binary.decodeEach(entry, bytes, 0, EntrySize, bytes.length / EntrySize).left.map {
        case (i, Binary.Failure.OutOfRange(Binary.Field.BlockOffset, _, _)) =>
          ChunkFault.BlockOffsetRange(i)
        case (i, Binary.Failure.OutOfRange(Binary.Field.Slot, _, _)) => ChunkFault.SlotRange(i)
        case _ => ChunkFault.SecondaryLength(bytes.length)
      }

/** The length of the CBOR data item that starts at `from`, without decoding it. */
object CborItem:
  private val MaxDepth = 4096

  def length(a: Array[Byte], from: Int): Option[Int] =
    // `left`: the items left in the current container (-1: indefinite); `parents`: those of the
    // containers around it, innermost first, `depth` of them
    @tailrec def go(pos: Int, left: Long, parents: List[Long], depth: Int): Option[Int] =
      if left == 0L then
        parents match
          case Nil        => Some(pos - from)
          case up :: rest => go(pos, up, rest, depth - 1)
      else if pos >= a.length then None
      else
        val ib = a(pos) & 0xff
        val at = pos + 1
        if left == -1L && ib == 0xff then go(at, 0L, parents, depth)
        else
          val remaining = if left > 0L then left - 1 else left
          val major = ib >> 5
          val ai = ib & 31
          val indefinite = ai == 31
          // the head's argument and the position after it
          val argument: Option[(Long, Int)] =
            if ai < 24 then Some((ai.toLong, at))
            else if ai > 27 && !indefinite then None
            else if indefinite then Some((0L, at))
            else
              val width = 1 << (ai - 24)
              Option.when(width <= a.length - at)(
                ((0 until width).foldLeft(0L)((v, i) => (v << 8) | (a(at + i) & 0xffL)), at + width)
              )
          argument match
            case None => None
            case Some((arg, next)) =>
              major match
                case 0 | 1 | 7 => if indefinite then None else go(next, remaining, parents, depth)
                case 2 | 3 =>
                  if indefinite then
                    if depth >= MaxDepth then None
                    else go(next, -1L, remaining :: parents, depth + 1)
                  else if arg >= 0 && arg <= (a.length - next).toLong then
                    go(next + arg.toInt, remaining, parents, depth)
                  else None
                case 4 | 5 =>
                  if indefinite then
                    if depth >= MaxDepth then None
                    else go(next, -1L, remaining :: parents, depth + 1)
                  else if arg < 0 || arg > (a.length - next).toLong then None
                  else if arg == 0 then go(next, remaining, parents, depth)
                  else if depth >= MaxDepth then None
                  else
                    go(next, if major == 5 then arg * 2 else arg, remaining :: parents, depth + 1)
                case _ => // 6: a tag wraps one more item
                  if indefinite || depth >= MaxDepth then None
                  else go(next, 1L, remaining :: parents, depth + 1)
    go(from, 1L, Nil, 0)

/** A block that passed validation, located in the chunk file. */
final case class IndexedBlock(
    entry: SecondaryEntry,
    position: BlockPosition,
    start: Int,
    length: Int
)

/** What was dropped from the end of the most recent chunk (the node may be writing it). */
final case class Truncation(entries: Int, bytes: Long, cause: Option[ChunkFault])

final case class Validated(blocks: Vector[IndexedBlock], dropped: Option[Truncation])

object ChunkValidator:
  /** Validate a chunk against its two indexes. `mostRecent` is Haskell's `ValidateMostRecentChunk`:
    * the first entry that fails, and everything after it, is dropped and reported instead of being
    * a fault, since the node writes the chunk file before the secondary and the primary index.
    * Nothing is ever repaired on disk.
    */
  def validate(
      chunk: ChunkNo,
      chunkSlots: ChunkSlots,
      primary: PrimaryIndex,
      secondary: Vector[SecondaryEntry],
      data: Array[Byte],
      mostRecent: Boolean
  ): Either[ChunkFault, Validated] =
    val filled = primary.filled
    val listed = math.min(filled.size, secondary.size)
    if !mostRecent && filled.size != secondary.size then
      Left(ChunkFault.IndexCountMismatch(filled.size, secondary.size))
    else
      def position(rel: Int): BlockPosition =
        if rel == 0 then BlockPosition.Ebb(chunk.value.toLong)
        else BlockPosition.Regular(chunkSlots.firstSlot(chunk) + rel - 1)

      def check(i: Int, expectedOffset: Long): Either[ChunkFault, IndexedBlock] =
        val e = secondary(i)
        val pos = position(filled(i))
        val start = e.blockOffset.value
        for
          _ <- Either.cond(
            start == expectedOffset,
            (),
            ChunkFault.OffsetMismatch(
              i,
              ByteOffset.of(expectedOffset).getOrElse(ByteOffset.zero),
              e.blockOffset
            )
          )
          end <-
            if i + 1 < secondary.size then Right(secondary(i + 1).blockOffset.value)
            else if start >= data.length then
              Left(ChunkFault.BlockOutOfBounds(i, start, data.length))
            else
              CborItem.length(data, start.toInt).map(start + _).toRight(ChunkFault.BlockNotCbor(i))
          _ <- Either.cond(
            end > start && end <= data.length,
            (),
            ChunkFault.BlockOutOfBounds(i, end, data.length)
          )
          size = (end - start).toInt
          _ <- Either.cond(
            e.headerOffset + e.headerSize <= size,
            (),
            ChunkFault.HeaderOutOfBlock(i)
          )
          actual = crc(data, start.toInt, size)
          _ <- Either.cond(actual == e.crc, (), ChunkFault.CrcMismatch(i, e.crc, actual))
          _ <- Either.cond(
            !shelleyBased(data, start.toInt, size) ||
              Blake2b.hash256.hash(
                Bytes.wrap(data, start.toInt + e.headerOffset, e.headerSize)
              ) == e.headerHash,
            (),
            ChunkFault.HeaderHashMismatch(i)
          )
          expectedSlot = pos match
            case BlockPosition.Ebb(epoch) => epoch
            case BlockPosition.Regular(s) => s
          _ <- Either.cond(
            e.slotOrEpoch == expectedSlot,
            (),
            ChunkFault.SlotMismatch(i, expectedSlot, e.slotOrEpoch)
          )
        yield IndexedBlock(e, pos, start.toInt, size)

      // the entries in order, each where the one before it ends, until the first that fails
      @tailrec def walk(
          i: Int,
          next: Long,
          good: Vector[IndexedBlock]
      ): (Vector[IndexedBlock], Option[ChunkFault]) =
        if i >= listed then (good, None)
        else
          check(i, next) match
            case Right(b) => walk(i + 1, b.start.toLong + b.length, good :+ b)
            case Left(f)  => (good, Some(f))
      val (blocks, fault) = walk(0, 0L, Vector.empty)
      val goodEnd = blocks.lastOption.fold(0L)(b => b.start.toLong + b.length)
      val leftoverEntries = math.max(filled.size, secondary.size) - blocks.size
      val leftoverBytes = data.length - goodEnd
      fault match
        case Some(f) if !mostRecent => Left(f)
        case Some(f) =>
          Right(Validated(blocks, Some(Truncation(leftoverEntries, leftoverBytes, Some(f)))))
        case None if leftoverBytes > 0 && !mostRecent =>
          Left(
            ChunkFault.TrailingBytes(
              ByteOffset.of(goodEnd).getOrElse(ByteOffset.zero),
              data.length.toLong
            )
          )
        case None if leftoverBytes > 0 || leftoverEntries > 0 =>
          Right(Validated(blocks, Some(Truncation(leftoverEntries, leftoverBytes, None))))
        case None => Right(Validated(blocks, None))

  private def crc(a: Array[Byte], from: Int, len: Int): Int =
    val c = new CRC32()
    c.update(a, from, len)
    c.getValue.toInt

  /** `[tag, block]` with a tag of 2 or more: a Shelley-based block, whose header hash is the
    * Blake2b-256 of the header bytes (Byron's is over a wrapper and is checked by decoding the
    * block).
    */
  private def shelleyBased(a: Array[Byte], from: Int, size: Int): Boolean =
    size >= 2 && a(from) == 0x82.toByte && (a(from + 1) & 0xff) >= 2 && (a(from + 1) & 0xff) < 24

/** The three files of a chunk. */
enum FileKind:
  case Chunk, Primary, Secondary

  def extension: String = this match
    case Chunk     => "chunk"
    case Primary   => "primary"
    case Secondary => "secondary"

/** Why the Haskell ImmutableDB cannot be read. */
enum HaskellImmutableError:
  case Io(path: String, cause: Throwable)
  case NoImmutableDir(path: String)
  case MissingFile(chunk: ChunkNo, kind: FileKind)
  case ChunkGap(expected: ChunkNo, found: ChunkNo)
  case Invalid(chunk: ChunkNo, fault: ChunkFault)

  def message: String = this match
    case Io(p, r)          => s"io $p: $r"
    case NoImmutableDir(p) => s"$p is not a directory"
    case MissingFile(c, k) => s"chunk ${c.stem}: missing .${k.extension} file"
    case ChunkGap(e, f)    => s"chunk ${e.stem} is missing (next chunk present: ${f.stem})"
    case Invalid(c, f)     => s"chunk ${c.stem}: ${f.message}"
