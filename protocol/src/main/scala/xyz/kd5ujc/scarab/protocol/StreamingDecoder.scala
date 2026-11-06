// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import xyz.kd5ujc.scarab.codec.Bytes

/** A decoder that releases complete input prefixes before the message ends. `consumed` counts bytes
  * of this call's input only; the caller retains the suffix and appends new input to it. A
  * continuation owns everything needed from the consumed prefix. No total-message byte count or
  * contiguous representation is required.
  */
trait StreamingDecoder[A]:
  self =>
  type Cursor
  def initial: Cursor
  def resume(cursor: Cursor, input: Bytes): StreamingDecoder.Step[Cursor, A]

  final def decodePrefix(input: Bytes): ChainSync.DecodeResult[A] =
    resume(initial, input) match
      case StreamingDecoder.Step.More(_, _) => ChainSync.DecodeResult.NeedMore
      case StreamingDecoder.Step.Done(value, consumed) =>
        ChainSync.DecodeResult.Decoded(value, consumed)
      case StreamingDecoder.Step.Failed(error) => ChainSync.DecodeResult.Failed(error)

  final def map[B](f: A => B): StreamingDecoder[B] = new StreamingDecoder[B]:
    type Cursor = self.Cursor
    def initial = self.initial
    def resume(cursor: Cursor, input: Bytes) = self.resume(cursor, input) match
      case StreamingDecoder.Step.More(next, consumed) => StreamingDecoder.Step.More(next, consumed)
      case StreamingDecoder.Step.Done(value, consumed) =>
        StreamingDecoder.Step.Done(f(value), consumed)
      case StreamingDecoder.Step.Failed(error) => StreamingDecoder.Step.Failed(error)

  /** Fixed protocol structure may compose decoders. Data-dependent repetitions use an explicit
    * cursor or tail-recursive loop, rather than nesting this combinator per item.
    */
  final def flatMap[B](f: A => StreamingDecoder[B]): StreamingDecoder[B] = new StreamingDecoder[B]:
    enum Position:
      case First(cursor: self.Cursor)
      case Second(next: StreamingDecoder.Continuation[B])
    type Cursor = Position
    def initial = Position.First(self.initial)
    def resume(cursor: Cursor, input: Bytes): StreamingDecoder.Step[Cursor, B] =
      def second(
          next: StreamingDecoder.Continuation[B],
          offset: Int
      ): StreamingDecoder.Step[Cursor, B] =
        next.resume(input.drop(offset)) match
          case StreamingDecoder.Step.More(after, consumed) =>
            StreamingDecoder.Step.More(Position.Second(after), offset + consumed)
          case StreamingDecoder.Step.Done(value, consumed) =>
            StreamingDecoder.Step.Done(value, offset + consumed)
          case StreamingDecoder.Step.Failed(error) => StreamingDecoder.Step.Failed(error)
      cursor match
        case Position.Second(next) => second(next, 0)
        case Position.First(before) =>
          self.resume(before, input) match
            case StreamingDecoder.Step.More(after, consumed) =>
              StreamingDecoder.Step.More(Position.First(after), consumed)
            case StreamingDecoder.Step.Done(value, consumed) => second(f(value).start, consumed)
            case StreamingDecoder.Step.Failed(error)         => StreamingDecoder.Step.Failed(error)

  final def start: StreamingDecoder.Continuation[A] = continued(initial)
  private def continued(cursor: Cursor): StreamingDecoder.Continuation[A] =
    new StreamingDecoder.Continuation[A]:
      def resume(input: Bytes) = self.resume(cursor, input) match
        case StreamingDecoder.Step.More(next, consumed) =>
          StreamingDecoder.Step.More(continued(next), consumed)
        case StreamingDecoder.Step.Done(value, consumed) =>
          StreamingDecoder.Step.Done(value, consumed)
        case StreamingDecoder.Step.Failed(error) => StreamingDecoder.Step.Failed(error)

object StreamingDecoder:
  enum Step[+S, +A]:
    case More(cursor: S, consumed: Int)
    case Done(value: A, consumed: Int)
    case Failed(error: ProtocolError)

  trait Continuation[A]:
    def resume(input: Bytes): Step[Continuation[A], A]

  /** A deliberately bounded adapter for scalar items or existing contiguous codecs. It releases no
    * prefix until the supplied decoder finishes; it does not give that codec a wider
    * representation.
    */
  def bounded[A](decode: Bytes => ChainSync.DecodeResult[A]): StreamingDecoder[A] =
    new StreamingDecoder[A]:
      type Cursor = Unit
      def initial = ()
      def resume(cursor: Unit, input: Bytes): Step[Unit, A] = decode(input) match
        case ChainSync.DecodeResult.NeedMore                 => Step.More((), 0)
        case ChainSync.DecodeResult.Failed(error)            => Step.Failed(error)
        case ChainSync.DecodeResult.Decoded(value, consumed) => Step.Done(value, consumed)

  def pure[A](value: A): StreamingDecoder[A] =
    bounded(_ => ChainSync.DecodeResult.Decoded(value, 0))
  def failed[A](error: ProtocolError): StreamingDecoder[A] =
    bounded(_ => ChainSync.DecodeResult.Failed(error))
