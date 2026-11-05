// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.codec

import cats.data.Chain
import scala.annotation.tailrec

/** Immutable bytes with a checked `Long` size and bounded, shared [[Bytes]] chunks.
  *
  * No operation implicitly flattens the content. [[toBytes]] is an explicit, caller-bounded
  * conversion; its successful size check does not promise that the allocation will fit in memory.
  * [[Bytes]] remains the compact representation for hashes and other small values. See
  * `docs/design/chunked-bytes.md` for the reference correspondence and remaining integration.
  */
final class ChunkedBytes private (val chunks: Chain[Bytes], val size: Long):
  import ChunkedBytes.{Cursor, Error}

  def isEmpty: Boolean = size == 0
  def nonEmpty: Boolean = size != 0

  /** Concatenation shares both inputs and refuses signed-64-bit length overflow. */
  def concat(other: ChunkedBytes): Either[Error, ChunkedBytes] =
    if other.size > Long.MaxValue - size then Left(Error.LengthOverflow(size, other.size))
    else if other.isEmpty then Right(this)
    else if isEmpty then Right(other)
    else Right(new ChunkedBytes(chunks ++ other.chunks, size + other.size))

  def foldChunks[A](initial: A)(f: (A, Bytes) => A): A = chunks.foldLeft(initial)(f)

  def cursor: Cursor = ChunkedBytes.cursorFrom(chunks, 0L, size)

  /** An immutable cursor at `offset`, including the exhausted cursor at `size`. */
  def cursorAt(offset: Long): Either[Error, Cursor] =
    if offset < 0 || offset > size then Left(Error.InvalidOffset(offset, size))
    else Right(cursor.skipUnchecked(offset))

  def byteAt(offset: Long): Option[Byte] =
    if offset < 0 || offset >= size then None
    else cursor.skipUnchecked(offset).readByte.map(_._1)

  /** A checked half-open range. Its chunks share the original immutable storage. */
  def slice(from: Long, until: Long): Either[Error, ChunkedBytes] =
    if from < 0 || until < from || until > size then Left(Error.InvalidRange(from, until, size))
    else if from == 0 && until == size then Right(this)
    else
      @tailrec def collect(at: Cursor, left: Long, out: Chain[Bytes]): ChunkedBytes =
        if left == 0 then new ChunkedBytes(out, until - from)
        else
          at.currentChunk match
            case None => new ChunkedBytes(out, until - from - left)
            case Some(chunk) =>
              val n = math.min(left, chunk.size.toLong).toInt
              collect(at.advanceWithinChunk(n), left - n, out.append(chunk.take(n)))
      Right(collect(cursor.skipUnchecked(from), until - from, Chain.empty))

  def take(count: Long): Either[Error, ChunkedBytes] = slice(0, count)
  def drop(count: Long): Either[Error, ChunkedBytes] = slice(count, size)

  /** Materialize only when the caller's contiguous boundary permits it. A single chunk is shared.
    */
  def toBytes(maxBytes: Int): Either[Error, Bytes] =
    if maxBytes < 0 then Left(Error.InvalidContiguousLimit(maxBytes))
    else if size > maxBytes.toLong then Left(Error.ContiguousLimitExceeded(size, maxBytes))
    else
      chunks.uncons match
        case None                                => Right(Bytes.empty)
        case Some((chunk, tail)) if tail.isEmpty => Right(chunk)
        case _ =>
          val bytes = chunks.iterator.flatMap { chunk =>
            chunk.withRange((array, offset, length) =>
              array.iterator.slice(offset, offset + length)
            )
          }
          Right(Bytes.wrap(bytes.toArray))

  /** Unsigned lexicographic comparison, independent of chunk boundaries. */
  def compareTo(other: ChunkedBytes): Int =
    @tailrec def compare(left: Cursor, right: Cursor): Int =
      (left.currentChunk, right.currentChunk) match
        case (None, None)    => 0
        case (None, Some(_)) => -1
        case (Some(_), None) => 1
        case (Some(a), Some(b)) =>
          val n = math.min(a.size, b.size)
          val order = a.take(n).compareTo(b.take(n))
          if order != 0 then order
          else compare(left.advanceWithinChunk(n), right.advanceWithinChunk(n))
    if this eq other then 0 else compare(cursor, other.cursor)

  override def equals(other: Any): Boolean = other match
    case bytes: ChunkedBytes => (this eq bytes) || (size == bytes.size && compareTo(bytes) == 0)
    case _                   => false

  private lazy val contentHash: Int =
    val computed = foldChunks(1)((hash, chunk) => chunk.foldLeft(hash)((h, byte) => 31 * h + byte))
    if computed == 0 then 1 else computed

  override def hashCode(): Int = contentHash
  override def toString: String = s"ChunkedBytes(size=$size)"

object ChunkedBytes:
  enum Error extends DomainError:
    case LengthOverflow(left: Long, right: Long)
    case InvalidOffset(offset: Long, size: Long)
    case InvalidRange(from: Long, until: Long, size: Long)
    case InvalidAdvance(count: Long, remaining: Long)
    case InvalidChunkSize(maxBytes: Int)
    case InvalidContiguousLimit(maxBytes: Int)
    case ContiguousLimitExceeded(size: Long, maxBytes: Int)

  val empty: ChunkedBytes = new ChunkedBytes(Chain.empty, 0L)

  def fromBytes(bytes: Bytes): ChunkedBytes =
    if bytes.isEmpty then empty else new ChunkedBytes(Chain.one(bytes), bytes.size.toLong)

  /** Drop empty chunks and check the sum before exposing a value. */
  def fromChunks(chunks: Chain[Bytes]): Either[Error, ChunkedBytes] =
    chunks.foldLeft[Either[Error, ChunkedBytes]](Right(empty)) { (built, chunk) =>
      built.flatMap(_.concat(fromBytes(chunk)))
    }

  given Ordering[ChunkedBytes] = (left, right) => left.compareTo(right)

  private def cursorFrom(chunks: Chain[Bytes], position: Long, end: Long): Cursor =
    chunks.uncons match
      case None                => new Cursor(None, Chain.empty, 0, position, end)
      case Some((chunk, tail)) => new Cursor(Some(chunk), tail, 0, position, end)

  /** A persistent forward cursor. Every returned nonempty chunk advances the global position; reads
    * never alter this cursor or its source. Only the offset within the first chunk is `Int`. The
    * first chunk and tail stay decomposed until that chunk is exhausted, so partial reads do not
    * repeatedly walk a left-associated concatenation spine.
    */
  final class Cursor private[ChunkedBytes] (
      private val current: Option[Bytes],
      private val tail: Chain[Bytes],
      private val localOffset: Int,
      val position: Long,
      private val end: Long
  ):
    def remaining: Long = end - position
    def isEmpty: Boolean = remaining == 0

    private[ChunkedBytes] def currentChunk: Option[Bytes] =
      current.map(_.drop(localOffset))

    private[ChunkedBytes] def advanceWithinChunk(count: Int): Cursor =
      current match
        case None => this
        case Some(chunk) =>
          if count == chunk.size - localOffset then cursorFrom(tail, position + count, end)
          else new Cursor(current, tail, localOffset + count, position + count, end)

    def readByte: Option[(Byte, Cursor)] =
      current.flatMap { chunk =>
        chunk.lift(localOffset).map(byte => (byte, advanceWithinChunk(1)))
      }

    /** Read at most the remainder of one stored chunk. A nonpositive request is refused. */
    def takeChunk(maxBytes: Int): Either[Error, Option[(Bytes, Cursor)]] =
      if maxBytes <= 0 then Left(Error.InvalidChunkSize(maxBytes))
      else
        Right(currentChunk.map { chunk =>
          val n = math.min(maxBytes, chunk.size)
          (chunk.take(n), advanceWithinChunk(n))
        })

    def skip(count: Long): Either[Error, Cursor] =
      if count < 0 || count > remaining then Left(Error.InvalidAdvance(count, remaining))
      else Right(skipUnchecked(count))

    private[ChunkedBytes] def skipUnchecked(count: Long): Cursor =
      @tailrec def advance(at: Cursor, left: Long): Cursor =
        if left == 0 then at
        else
          at.current match
            case None => at
            case Some(chunk) =>
              val n = math.min(left, (chunk.size - at.localOffset).toLong).toInt
              advance(at.advanceWithinChunk(n), left - n)
      advance(this, count)
