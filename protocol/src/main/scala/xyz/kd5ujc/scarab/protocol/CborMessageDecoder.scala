// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import cats.syntax.all.*
import java.nio.ByteBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import scala.annotation.tailrec
import xyz.kd5ujc.scarab.codec.Bytes

import ChainSync.DecodeResult

/** Resumable framing for a protocol message consisting of one ordinary CBOR item. This does not
  * apply to detached ledger records, which are not CBOR trees. The supplied decoder remains the
  * authority on the message's grammar and errors. Completed string chunks and container elements
  * are scanned once even when every mux SDU arrives after the ingress queue runs dry (#749).
  */
private[protocol] object CborMessageDecoder:
  private enum Frame:
    case Count(left: Long)
    case Array, MapKey, MapValue
    case Chunks(major: Int)

  private final case class Progress(offset: Int, frames: List[Frame], items: Int)
  private enum Scan:
    case More(progress: Progress)
    case Complete
    case Invalid

  private final case class Head(major: Int, additional: Int, argument: Long, end: Int)
  private enum HeadRead:
    case Read(head: Head)
    case More, Invalid

  private def head(input: Bytes, offset: Int, chunk: Option[Int]): HeadRead =
    input.lift(offset) match
      case None => HeadRead.More
      case Some(byte) =>
        val major = (byte & 0xff) / 32
        val ai = byte & 31
        // A chunk's type is known from its first byte, before its length argument is read.
        if (byte & 0xff) != 255 && chunk.exists(m => major != m || ai == 31) then HeadRead.Invalid
        else if ai < 24 || ai == 31 then HeadRead.Read(Head(major, ai, ai.toLong, offset + 1))
        else if ai >= 28 then HeadRead.Invalid
        else
          val width = 1 << (ai - 24)
          input.bigEndian(offset + 1, width) match
            case None        => HeadRead.More
            case Some(value) => HeadRead.Read(Head(major, ai, value, offset + 1 + width))

  private def after(frame: Frame): Frame = frame match
    case Frame.Count(left) => Frame.Count(left - 1)
    case Frame.MapKey      => Frame.MapValue
    case Frame.MapValue    => Frame.MapKey
    case other             => other

  private def utf8(input: Bytes, from: Int, until: Int): Boolean =
    Either
      .catchNonFatal(
        StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(input.slice(from, until).toArray))
      )
      .isRight

  private def step(
      progress: Progress,
      input: Bytes,
      frame: Frame,
      rest: List[Frame]
  ): Either[Scan, Progress] =
    val chunk = frame match
      case Frame.Chunks(major) => Some(major)
      case _                   => None
    head(input, progress.offset, chunk) match
      case HeadRead.More    => Left(Scan.More(progress))
      case HeadRead.Invalid => Left(Scan.Invalid)
      case HeadRead.Read(h) =>
        val isBreak = h.major == 7 && h.additional == 31
        val closes = frame match
          case Frame.Array | Frame.MapKey | Frame.Chunks(_) => true
          case _                                            => false
        val chunk = frame match
          case Frame.Chunks(major) => Some(major)
          case _                   => None
        if isBreak then
          if closes then Right(progress.copy(offset = h.end, frames = rest)) else Left(Scan.Invalid)
        else if progress.items == Int.MaxValue then Left(Scan.Invalid)
        else if chunk.exists(major => h.major != major || h.additional == 31) then
          Left(Scan.Invalid)
        else
          val parents = after(frame) :: rest
          val items = progress.items + 1
          def scalar(end: Int) = Right(Progress(end, parents, items))
          def container(child: Frame) = Right(Progress(h.end, child :: parents, items))
          h.major match
            case 0 | 1 | 6 =>
              if h.additional == 31 then Left(Scan.Invalid)
              else if h.major == 6 then container(Frame.Count(1))
              else scalar(h.end)
            case 2 | 3 =>
              if h.additional == 31 then container(Frame.Chunks(h.major))
              else if h.argument < 0 || h.argument > Int.MaxValue - h.end then Left(Scan.Invalid)
              else
                val end = h.end + h.argument.toInt
                if end > input.size then Left(Scan.More(progress))
                else if h.major == 3 && !utf8(input, h.end, end) then Left(Scan.Invalid)
                else scalar(end)
            case 4 | 5 =>
              if h.additional == 31 then
                container(if h.major == 4 then Frame.Array else Frame.MapKey)
              // ProtocolWire counts the container itself and every preceding item/chunk before
              // checking its declared children, even when their bytes have not arrived yet.
              else if h.argument < 0 ||
                h.argument > (Int.MaxValue - items) / (if h.major == 5 then 2 else 1)
              then Left(Scan.Invalid)
              else container(Frame.Count(h.argument * (if h.major == 5 then 2 else 1)))
            case _ =>
              if h.additional == 24 && h.argument < 32 then Left(Scan.Invalid)
              else scalar(h.end)

  @tailrec private def scan(progress: Progress, input: Bytes): Scan = progress.frames match
    case Nil                    => Scan.Complete
    case Frame.Count(0) :: rest => scan(progress.copy(frames = rest), input)
    case frame :: rest =>
      step(progress, input, frame, rest) match
        case Left(result) => result
        case Right(next)  => scan(next, input)

  def apply[A](
      prefix: Bytes => DecodeResult[Option[Int]],
      decode: Bytes => DecodeResult[A]
  ): IncrementalDecoder[A] =
    new IncrementalDecoder[A]:
      type Cursor = Option[Progress]
      val initial: Cursor = None

      def resume(cursor: Cursor, input: Bytes): (Cursor, DecodeResult[A]) =
        def continue(progress: Progress): (Cursor, DecodeResult[A]) =
          scan(progress, input) match
            case Scan.More(next)              => (Some(next), DecodeResult.NeedMore)
            case Scan.Complete | Scan.Invalid => (None, decode(input))
        cursor match
          case Some(progress) => continue(progress)
          case None =>
            prefix(input) match
              case DecodeResult.Decoded(Some(offset), _) =>
                continue(Progress(offset, List(Frame.Count(1)), 0))
              case _ => (None, decode(input))
