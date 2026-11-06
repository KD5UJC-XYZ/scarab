// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import cats.data.Chain
import scala.annotation.tailrec
import xyz.kd5ujc.scarab.codec.Bytes

/** The requests a ChainSync server receives in StIdle. Offers stay segmented through the server's
  * intersection search; neither their wire bytes nor their cardinality must fit an Int.
  */
enum ChainSyncRequest[+Q]:
  case RequestNext
  case FindIntersect(points: Chain[Q])
  case Done

object ChainSyncRequest:
  import ChainSync.{Point, UInt64}
  import ProtocolWire.{bad, attempt}
  import StreamingDecoder.Step

  private enum Reading:
    case Envelope
    case Candidates
    case Points(remaining: Option[BigInt], points: Chain[Point])

  private enum Position:
    case Read(value: Reading)
    case Hash(
        remaining: Option[BigInt],
        points: Chain[Point],
        slot: UInt64,
        declared: BigInt,
        unread: BigInt,
        bytes: Bytes
    )

  /** Native NtC uses plain runPeer (consensus 82ecba329 Network/NodeToClient.hs:438-453).
    * ChainSync/Codec.hs:157-168,204-231 at network 2fb69829 accepts definite and indefinite lists.
    * The Cardano adapter reads a complete hash before checking its size (consensus 82ecba329
    * Block/Abstract.hs:307-314), so an invalid long hash is drained without retaining its bytes. No
    * source is consulted until this decoder returns a complete, validated request.
    */
  val decoder: StreamingDecoder[ChainSyncRequest[Point]] =
    new StreamingDecoder[ChainSyncRequest[Point]]:
      type Cursor = Position
      def initial = Position.Read(Reading.Envelope)

      def resume(cursor: Cursor, input: Bytes): Step[Cursor, ChainSyncRequest[Point]] =
        @tailrec def loop(position: Position, offset: Int): Step[Cursor, ChainSyncRequest[Point]] =
          position match
            case Position.Hash(remaining, points, slot, declared, unread, bytes) =>
              if unread == 0 then
                if declared != ChainSync.HashBytes then
                  Step.Failed(ProtocolError.CardanoPointHashMustContain32Bytes(declared))
                else
                  loop(
                    Position.Read(
                      Reading.Points(remaining.map(_ - 1), points.append(Point.Block(slot, bytes)))
                    ),
                    offset
                  )
              else
                val available = input.size - offset
                if available == 0 then Step.More(position, offset)
                else
                  val count = unread.min(available).toInt
                  val kept =
                    if declared == ChainSync.HashBytes then
                      bytes ++ input.slice(offset, offset + count).compact
                    else Bytes.empty
                  loop(
                    Position.Hash(remaining, points, slot, declared, unread - count, kept),
                    offset + count
                  )
            case Position.Read(reading) =>
              val parsed = attempt {
                val reader =
                  new ChainSyncWire.Reader(input.drop(offset), ChainSync.Limits.NodeToClient)
                val next: Either[Position, ChainSyncRequest[Point]] = reading match
                  case Reading.Envelope =>
                    val arity = reader.arrayArity()
                    val tag = reader.uint().value
                    if arity == 1 && tag == 0 then Right(RequestNext)
                    else if arity == 1 && tag == 7 then Right(Done)
                    else if arity == 2 && tag == 4 then Left(Position.Read(Reading.Candidates))
                    else
                      val expected = tag match
                        case n if n == 1                     => 1
                        case n if n == 6                     => 2
                        case n if n == 2 || n == 3 || n == 5 => 3
                        case _                               => 0
                      if expected != 0 && arity == expected then
                        bad(
                          ProtocolError.ChainSyncAgency(ChainSync.State.Idle, ChainSync.Role.Client)
                        )
                      else bad(ProtocolError.UnknownTagOrWrongExactOuterArity)
                  case Reading.Candidates =>
                    Left(Position.Read(Reading.Points(reader.candidateCount(), Chain.empty)))
                  case Reading.Points(remaining, points) =>
                    if remaining
                        .contains(BigInt(0)) || (remaining.isEmpty && reader.breakIfPresent())
                    then Right(FindIntersect(points))
                    else
                      reader.arrayArity() match
                        case n if n == 0 =>
                          Left(
                            Position.Read(
                              Reading.Points(remaining.map(_ - 1), points.append(Point.Origin))
                            )
                          )
                        case n if n == 2 =>
                          val slot = reader.uint()
                          val length = reader.byteStringLength()
                          Left(Position.Hash(remaining, points, slot, length, length, Bytes.empty))
                        case _ => bad(ProtocolError.PointArity)
                next -> reader.position
              }
              parsed match
                case Left(error) if error.incomplete   => Step.More(position, offset)
                case Left(error)                       => Step.Failed(error)
                case Right((Right(request), consumed)) => Step.Done(request, offset + consumed)
                case Right((Left(next), consumed))     => loop(next, offset + consumed)
        loop(cursor, 0)
