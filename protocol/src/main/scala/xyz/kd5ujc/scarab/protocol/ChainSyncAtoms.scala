// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import xyz.kd5ujc.scarab.codec.Bytes

import ChainSync.{Point, Tip, UInt64}
import ProtocolWire.bad
import StreamingDecoder.Step

/** Cardano scalar structure with streamed hash bodies. Native decodeRawHash checks the size after
  * decodeBytes has consumed the whole body (consensus 82ecba329 Block/Abstract.hs:307–314).
  */
private[protocol] object ChainSyncAtoms:
  def scalar[A](read: ProtocolWire.Scope ?=> ProtocolWire.Reader => A): StreamingDecoder[A] =
    StreamingDecoder.bounded { input =>
      ProtocolWire.prefix {
        val reader = new ChainSyncWire.Reader(input, ChainSync.Limits.NodeToClient)
        ChainSync.DecodeResult.Decoded(read(reader), reader.position)
      }
    }

  val point: StreamingDecoder[Point] = scalar { reader =>
    reader.arrayArity() match
      case n if n == 0 => Left(Point.Origin)
      case n if n == 2 => Right(reader.uint() -> reader.byteStringLength())
      case _           => bad(ProtocolError.PointArity)
  }.flatMap {
    case Left(origin)            => StreamingDecoder.pure(origin)
    case Right((slot, declared)) => hash(slot, declared)
  }

  private def hash(slot: UInt64, declared: BigInt): StreamingDecoder[Point] =
    new StreamingDecoder[Point]:
      type Cursor = (BigInt, Bytes)
      def initial = (declared, Bytes.empty)
      def resume(cursor: Cursor, input: Bytes): Step[Cursor, Point] =
        val (unread, retained) = cursor
        val count = unread.min(input.size).toInt
        val bytes =
          if declared == ChainSync.HashBytes then retained ++ input.take(count)
          else Bytes.empty
        val left = unread - count
        if left != 0 then Step.More((left, bytes), count)
        else if declared != ChainSync.HashBytes then
          Step.Failed(ProtocolError.CardanoPointHashMustContain32Bytes(declared))
        else Step.Done(Point.Block(slot, bytes), count)

  val tip: StreamingDecoder[Tip] = scalar { reader =>
    if reader.arrayArity() != 2 then bad(ProtocolError.TipArity)
  }.flatMap(_ => point.flatMap(p => scalar(_.uint()).map(Tip(p, _))))
