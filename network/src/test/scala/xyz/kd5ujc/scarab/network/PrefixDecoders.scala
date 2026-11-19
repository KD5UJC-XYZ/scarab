// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.mtl.Raise
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.IncrementalDecoder
import xyz.kd5ujc.scarab.protocol.ChainSync.DecodeResult

/** Decoders of the tests' own messages, which keep no progress: each prefix is read from its start.
  * The node's mini-protocols are received through their incremental decoders.
  */
object PrefixDecoders:
  def restarting[A](decode: Bytes => DecodeResult[A]): IncrementalDecoder[A] =
    new IncrementalDecoder[A]:
      type Cursor = Unit
      def initial: Cursor = ()
      def resume(cursor: Cursor, input: Bytes): (Cursor, DecodeResult[A]) = ((), decode(input))

extension [F[_]](channel: MuxChannel[F])
  /** [[MuxChannel.recv]] of a message `decode` reads from the start of each prefix. */
  def recvPrefix[A](maxBytes: Int)(decode: Bytes => DecodeResult[A])(using
      Raise[F, NetworkError]
  ): F[A] = channel.recv(maxBytes)(PrefixDecoders.restarting(decode))
