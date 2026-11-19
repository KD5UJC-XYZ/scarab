// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import xyz.kd5ujc.scarab.codec.Bytes

import ChainSync.DecodeResult

/** A prefix decoder whose cursor remembers work on an incomplete message. Each input is the
  * complete prefix accumulated so far, extending the preceding input without changing its bytes.
  * Starting again with [[initial]] must give the same verdict; the cursor changes only the work
  * needed to reach it. A caller discards the cursor after a complete message or failure.
  *
  * The multiplexer receives every message through one ([[decodePrefix]] is the decoder started
  * afresh), as the reference's driver feeds every chunk to its decoder's continuation
  * (`runDecoderWithLimit`,
  * `ouroboros-network/framework/lib/Ouroboros/Network/Driver/Limits.hs:281-331`, c45735a5 and
  * 4646039f): a decoder that keeps in its cursor what it read of a message that arrives in many
  * parts does not read that again from its start, as each of the node's codecs does.
  */
trait IncrementalDecoder[A]:
  type Cursor
  def initial: Cursor
  def resume(cursor: Cursor, input: Bytes): (Cursor, DecodeResult[A])
  final def decodePrefix(input: Bytes): DecodeResult[A] = resume(initial, input)._2

  /** This decoder with each of its verdicts on an input passed through `judge`, with the same
    * cursor.
    */
  final def mapResult[B](
      judge: (Bytes, DecodeResult[A]) => DecodeResult[B]
  ): IncrementalDecoder[B] =
    val self = this
    new IncrementalDecoder[B]:
      type Cursor = self.Cursor
      def initial: Cursor = self.initial
      def resume(cursor: Cursor, input: Bytes): (Cursor, DecodeResult[B]) =
        val (next, result) = self.resume(cursor, input)
        (next, judge(input, result))

  /** This decoder with `f` applied to the message it decodes. */
  final def map[B](f: A => B): IncrementalDecoder[B] =
    mapResult((_, result) =>
      result match
        case DecodeResult.Decoded(value, consumed) => DecodeResult.Decoded(f(value), consumed)
        case DecodeResult.NeedMore                 => DecodeResult.NeedMore
        case DecodeResult.Failed(error)            => DecodeResult.Failed(error)
    )
