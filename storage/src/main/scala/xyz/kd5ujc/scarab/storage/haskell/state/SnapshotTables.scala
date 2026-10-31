// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import fs2.Chunk
import scala.annotation.tailrec
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.mempack.MemPack
import xyz.kd5ujc.scarab.ledger.TxIn

/** The streaming framing of the Mem backend's `tables`: `valuesMKEncoder/Decoder`, consensus
  * 82ecba3 `Ledger/Tables.hs:276-283`, `V2/InMemory.hs:373-451`; the map is the era's
  * `encodeMap encodeMemPack encodeMemPack` (`Shelley/Ledger/Ledger.hs:373-375`).
  *
  * This layer checks framing and key width, not MemPack values or whole-table uniqueness. The
  * reference accepts unsorted maps; checking arbitrary duplicates requires a semantic sink (an
  * in-memory map in tests, a disk-backed insert-if-absent at the import boundary). Do not mistake a
  * successful framing decode for a validated ledger table.
  */
object SnapshotTables:
  enum Field:
    case Envelope, Map, Key, Value

  enum Error:
    case WrongType(field: Field, head: Int, offset: Long)
    case WrongEnvelopeLength(length: BigInt)
    case WrongKeyLength(length: BigInt)
    case LengthOutOfRange(field: Field, length: BigInt)
    case Incomplete(field: Field, offset: Long)
    case TrailingBytes(offset: Long)
    case CountMismatch(expected: Long, actual: Long)
    case KeysNotAscending(previous: Bytes, current: Bytes)
    case InvalidValue(key: Bytes, cause: MemPack.Failure)
    case InvalidKey(key: TxIn)
    case DuplicateKey(key: TxIn)

  /** Original key and MemPack value bytes. Values are deliberately not re-encoded when reframing a
    * reference file: an output upgraded from an older era can retain a noncanonical constructor.
    */
  final case class Entry(key: Bytes, value: Bytes)
  final case class Summary(entries: Long, crc: SnapshotCrc)

  private enum Site:
    case Envelope, Map
    case Key(remaining: Option[Long])
    case Value(key: Bytes, remaining: Option[Long])

    def field: Field = this match
      case Envelope    => Field.Envelope
      case Map         => Field.Map
      case Key(_)      => Field.Key
      case Value(_, _) => Field.Value

    def major: Int = this match
      case Envelope    => 4
      case Map         => 5
      case Key(_)      => 2
      case Value(_, _) => 2

  private enum Phase:
    case Head(site: Site)
    case Length(site: Site, left: Int, value: BigInt)
    case Body(site: Site, left: Int, parts: List[Chunk[Byte]])
    case Done

  private def sized(site: Site, n: BigInt): Either[Error, Phase] = site match
    case Site.Envelope =>
      Either.cond(n == 1, Phase.Head(Site.Map), Error.WrongEnvelopeLength(n))
    case Site.Map =>
      Either.cond(
        n.isValidLong,
        Phase.Head(Site.Key(Some(n.toLong))),
        Error.LengthOutOfRange(Field.Map, n)
      )
    case Site.Key(_) =>
      Either.cond(n == 34, Phase.Body(site, 34, Nil), Error.WrongKeyLength(n))
    case Site.Value(_, _) =>
      Either.cond(
        n.isValidInt,
        Phase.Body(site, n.toInt, Nil),
        Error.LengthOutOfRange(Field.Value, n)
      )

  /** Immutable incremental state, retaining at most the current key/value plus the supplied input
    * chunk. Declared lengths never allocate their body; only bytes actually received do.
    */
  final class Decoder private[SnapshotTables] (
      private val phase: Phase,
      private val offset: Long,
      private val count: Long,
      private val crc: SnapshotCrc
  ):
    def feed(bytes: Bytes): Either[Error, (Decoder, Vector[Entry])] =
      @tailrec def go(
          state: Phase,
          at: Int,
          seen: Long,
          emitted: List[Entry]
      ): Either[Error, (Decoder, Vector[Entry])] = (state, bytes.lift(at)) match
        case (Phase.Head(Site.Key(Some(0))), _) => go(Phase.Done, at, seen, emitted)
        case (Phase.Body(site, 0, parts), _) =>
          val body = Bytes.wrap(Chunk.concat(parts.reverse).toArray)
          site match
            case Site.Key(left) => go(Phase.Head(Site.Value(body, left)), at, seen, emitted)
            case Site.Value(key, left) =>
              go(Phase.Head(Site.Key(left.map(_ - 1))), at, seen + 1, Entry(key, body) :: emitted)
            case _ => Left(Error.WrongType(site.field, -1, offset + at))
        case (_, None) =>
          Right(
            (new Decoder(state, offset + at, seen, crc.update(bytes)), emitted.reverse.toVector)
          )
        case (Phase.Done, Some(_)) => Left(Error.TrailingBytes(offset + at))
        case (Phase.Body(site, left, parts), Some(_)) =>
          val taken = math.min(left, bytes.size - at)
          val part = bytes.slice(at, at + taken).compact
          val chunk = Chunk.array(part.unsafeArray, part.unsafeOffset, part.size)
          go(Phase.Body(site, left - taken, chunk :: parts), at + taken, seen, emitted)
        case (Phase.Length(site, left, value), Some(b)) =>
          val next = (value << 8) | (b & 0xff)
          if left > 1 then go(Phase.Length(site, left - 1, next), at + 1, seen, emitted)
          else
            sized(site, next) match
              case Left(e)      => Left(e)
              case Right(state) => go(state, at + 1, seen, emitted)
        case (Phase.Head(site), Some(b)) =>
          val head = b & 0xff
          val additional = head & 31
          if head == 0xff && site == Site.Key(None) then go(Phase.Done, at + 1, seen, emitted)
          else if head == 0xbf && site == Site.Map then
            go(Phase.Head(Site.Key(None)), at + 1, seen, emitted)
          else if head >>> 5 != site.major || additional > 27 then
            Left(Error.WrongType(site.field, head, offset + at))
          else if additional >= 24 then
            go(Phase.Length(site, 1 << (additional - 24), BigInt(0)), at + 1, seen, emitted)
          else
            sized(site, BigInt(additional)) match
              case Left(e)      => Left(e)
              case Right(state) => go(state, at + 1, seen, emitted)
      go(phase, 0, count, Nil)

    def finish: Either[Error, Summary] = phase match
      case Phase.Done               => Right(Summary(count, crc))
      case Phase.Head(site)         => Left(Error.Incomplete(site.field, offset))
      case Phase.Length(site, _, _) => Left(Error.Incomplete(site.field, offset))
      case Phase.Body(site, _, _)   => Left(Error.Incomplete(site.field, offset))

  def decoder: Decoder = new Decoder(Phase.Head(Site.Envelope), 0L, 0L, SnapshotCrc.empty)

  /** A complete small file (fixtures/fuzz); production uses the incremental decoder. */
  def decode(bytes: Bytes): Either[Error, (Vector[Entry], Summary)] =
    decoder.feed(bytes).flatMap((next, entries) => next.finish.map(entries -> _))

  /** The Haskell node's framing policy (`Binary/Encoding/Encoder.hs:397-443`): a definite map up to
    * 23 entries, otherwise indefinite. The outer array always has exactly one item.
    */
  def prefix(entries: Long): Either[Error, Bytes] =
    Either.cond(
      entries >= 0,
      Bytes.wrap(Array(0x81.toByte, (if entries <= 23 then 0xa0 | entries.toInt else 0xbf).toByte)),
      Error.LengthOutOfRange(Field.Map, BigInt(entries))
    )

  def suffix(entries: Long): Bytes =
    if entries <= 23 then Bytes.empty else Bytes.wrap(Array(0xff.toByte))

  private def stringHead(size: Int): Bytes =
    val (head, width) =
      if size < 24 then (0x40 | size, 0)
      else if size < 256 then (0x58, 1)
      else if size < 65536 then (0x59, 2)
      else (0x5a, 4)
    Bytes.wrap(head.toByte +: Array.tabulate(width)(i => (size >>> (8 * (width - i - 1))).toByte))

  def encodeEntry(entry: Entry): Either[Error, Bytes] =
    Either.cond(
      entry.key.size == 34,
      stringHead(34) ++ entry.key ++ stringHead(entry.value.size) ++ entry.value,
      Error.WrongKeyLength(BigInt(entry.key.size))
    )
