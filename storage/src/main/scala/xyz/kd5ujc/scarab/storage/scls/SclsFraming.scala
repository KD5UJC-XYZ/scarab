// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.scls

import cats.syntax.all.*
import scala.annotation.tailrec
import scodec.{Attempt, Err}
import scodec.bits.ByteVector
import scodec.codecs.uint32
import xyz.kd5ujc.scarab.codec.{Bytes, DomainError}

/** Length-delimited SCLS records. This layer only frames bytes; record schemas, ordering and
  * commitments are validated separately before an imported state can be published. Reference:
  * tweag/cardano-cls@8e2bba9442cb388089f53d708cc4aedcb47726ea,
  * scls-format/src/Cardano/SCLS/Internal/Frame.hs:113-124,137-145 (`fetchNextFrame`,
  * `hWriteFrameBuffer`).
  */
object SclsFraming:
  enum Kind(val tag: Int):
    case Header extends Kind(0x00)
    case Manifest extends Kind(0x01)
    case Chunk extends Kind(0x10)
    case Metadata extends Kind(0x31)

  final case class Frame(kind: Kind, contents: Bytes)

  enum Error extends DomainError:
    case InvalidLength(offset: Long, found: Long)
    case RecordTooLarge(offset: Long, found: Long, limit: Int)
    case UnknownKind(offset: Long, tag: Int)
    case Incomplete(offset: Long, missing: Int)
    case Binary(cause: Err)

  /** A caller-selected allocation bound, including the record tag but excluding its length prefix.
    * The format permits unsigned32 lengths; a service chooses a smaller bound for its own profile.
    */
  opaque type Limit = Int

  object Limit:
    def fromInt(bytes: Int): Either[Error, Limit] =
      Either.cond(bytes > 0, bytes, Error.InvalidLength(0L, bytes.toLong))

  private enum Phase:
    case Length(prefix: Bytes)
    case Tag(size: Int)
    case Body(kind: Kind, size: Int, buffered: Int, pending: Bytes, reversed: List[Bytes])

  private val SegmentSize = 256

  private def kind(tag: Int, offset: Long): Either[Error, Kind] =
    Kind.values.find(_.tag == tag).toRight(Error.UnknownKind(offset, tag))

  private def length(prefix: Bytes, offset: Long, limit: Limit): Either[Error, Int] =
    uint32.decode(ByteVector.view(prefix.toArray).bits) match
      case Attempt.Failure(cause) => Left(Error.Binary(cause))
      case Attempt.Successful(decoded) =>
        val size = decoded.value
        if size < 1 then Left(Error.InvalidLength(offset, size))
        else if size > limit.toLong then Left(Error.RecordTooLarge(offset, size, limit))
        else Right(size.toInt)

  private def assemble(size: Int, reversed: List[Bytes]): Bytes =
    val output = new Array[Byte](size)
    reversed.reverse.foldLeft(0) { (offset, bytes) =>
      bytes.copyTo(output, offset)
      offset + bytes.size
    }
    Bytes.wrap(output)

  /** Keep sub-segment feed fragments coalesced, so retained object overhead is bounded by the
    * record byte limit rather than by the caller's feed granularity.
    */
  private def appendBody(
      pending: Bytes,
      reversed: List[Bytes],
      fragment: Bytes
  ): (Bytes, List[Bytes]) =
    if fragment.isEmpty then pending -> reversed
    else if pending.isEmpty && fragment.size >= SegmentSize then
      Bytes.empty -> (fragment :: reversed)
    else if pending.isEmpty then fragment -> reversed
    else
      val needed = SegmentSize - pending.size
      if fragment.size < needed then (pending ++ fragment) -> reversed
      else
        val complete = pending ++ fragment.take(needed)
        val remainder = fragment.drop(needed).compact
        if remainder.size >= SegmentSize then Bytes.empty -> (remainder :: complete :: reversed)
        else remainder -> (complete :: reversed)

  final class Decoder private[SclsFraming] (
      private val phase: Phase,
      val offset: Long,
      private val limit: Limit
  ):
    /** A partial record retains at most `limit` bytes, with one final copy when complete. Input
      * fragments are collected without repeatedly copying an expanding payload.
      */
    def feed(input: Bytes): Either[Error, (Decoder, Vector[Frame])] =
      @tailrec def loop(
          current: Phase,
          position: Int,
          at: Long,
          emitted: Vector[Frame]
      ): Either[Error, (Decoder, Vector[Frame])] = (current, input.lift(position)) match
        case (Phase.Body(recordKind, size, buffered, pending, reversed), _) if buffered == size =>
          val segments = if pending.isEmpty then reversed else pending :: reversed
          loop(
            Phase.Length(Bytes.empty),
            position,
            at,
            emitted :+ Frame(recordKind, assemble(size, segments))
          )
        case (_, None) =>
          Right(new Decoder(current, at, limit) -> emitted)
        case (Phase.Length(prefix), Some(_)) =>
          val count = math.min(4 - prefix.size, input.size - position)
          val next = prefix ++ input.slice(position, position + count)
          if next.size < 4 then loop(Phase.Length(next), position + count, at + count, emitted)
          else
            length(next, at + count - 4, limit) match
              case Left(error) => Left(error)
              case Right(size) => loop(Phase.Tag(size), position + count, at + count, emitted)
        case (Phase.Tag(size), Some(tag)) =>
          kind(tag & 0xff, at) match
            case Left(error) => Left(error)
            case Right(recordKind) =>
              loop(
                Phase.Body(recordKind, size - 1, 0, Bytes.empty, Nil),
                position + 1,
                at + 1,
                emitted
              )
        case (Phase.Body(recordKind, size, buffered, pending, reversed), Some(_)) =>
          val count = math.min(size - buffered, input.size - position)
          val fragment = input.slice(position, position + count).compact
          val (nextPending, nextReversed) = appendBody(pending, reversed, fragment)
          loop(
            Phase.Body(recordKind, size, buffered + count, nextPending, nextReversed),
            position + count,
            at + count,
            emitted
          )
      loop(phase, 0, offset, Vector.empty)

    /** Package-private diagnostic used to assert the memory-bound invariant in adversarial tests.
      */
    private[scls] def retainedSegmentCount: Int = phase match
      case Phase.Body(_, _, _, pending, reversed) =>
        reversed.size + (if pending.isEmpty then 0 else 1)
      case _ => 0

    def finish: Either[Error, Unit] = phase match
      case Phase.Length(prefix) if prefix.isEmpty => Right(())
      case Phase.Length(prefix)                   => Left(Error.Incomplete(offset, 4 - prefix.size))
      case Phase.Tag(size)                        => Left(Error.Incomplete(offset, size))
      case Phase.Body(_, size, buffered, _, _)    => Left(Error.Incomplete(offset, size - buffered))

  def decoder(limit: Limit): Decoder = new Decoder(Phase.Length(Bytes.empty), 0L, limit)

  /** Convenience for small fixtures. Services feed bounded chunks and consume records as emitted.
    */
  def decode(bytes: Bytes, limit: Limit): Either[Error, Vector[Frame]] =
    decoder(limit).feed(bytes).flatMap((end, records) => end.finish.as(records))

  def encode(frame: Frame, limit: Limit): Either[Error, Bytes] =
    val size = frame.contents.size.toLong + 1L
    if size > limit.toLong then Left(Error.RecordTooLarge(0L, size, limit))
    else
      uint32.encode(size) match
        case Attempt.Failure(cause) => Left(Error.Binary(cause))
        case Attempt.Successful(bits) =>
          Right(
            Bytes.wrap(bits.toByteArray) ++ Bytes.wrap(
              Array(frame.kind.tag.toByte)
            ) ++ frame.contents
          )
