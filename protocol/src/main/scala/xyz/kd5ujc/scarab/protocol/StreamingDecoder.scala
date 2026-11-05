// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import xyz.kd5ujc.scarab.codec.Bytes

/** A decoder that releases complete input prefixes before the message ends. `consumed` counts bytes
  * of this call's input only; the caller retains the suffix and appends new input to it. A
  * continuation owns everything needed from the consumed prefix. No total-message byte count or
  * contiguous representation is required.
  */
trait StreamingDecoder[A]:
  type Cursor
  def initial: Cursor
  def resume(cursor: Cursor, input: Bytes): StreamingDecoder.Step[Cursor, A]

  final def decodePrefix(input: Bytes): ChainSync.DecodeResult[A] =
    resume(initial, input) match
      case StreamingDecoder.Step.More(_, _) => ChainSync.DecodeResult.NeedMore
      case StreamingDecoder.Step.Done(value, consumed) =>
        ChainSync.DecodeResult.Decoded(value, consumed)
      case StreamingDecoder.Step.Failed(error) => ChainSync.DecodeResult.Failed(error)

object StreamingDecoder:
  enum Step[+S, +A]:
    case More(cursor: S, consumed: Int)
    case Done(value: A, consumed: Int)
    case Failed(error: ProtocolError)
