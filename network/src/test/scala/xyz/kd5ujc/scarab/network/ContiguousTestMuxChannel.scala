// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.IO
import cats.mtl.Raise
import xyz.kd5ujc.scarab.codec.ChunkedBytes

/** Scripted finite tests explicitly materialize their small messages. Live and wide-message tests
  * use the production Mux, whose sendChunked never requires a whole-message array.
  */
private[network] trait ContiguousTestMuxChannel extends MuxChannel[IO]:
  final def sendChunked(message: ChunkedBytes)(using raise: Raise[IO, NetworkError]): IO[Unit] =
    message
      .toBytes(Int.MaxValue)
      .fold(
        error => raise.raise(NetworkError.ByteRepresentation(protocol, error)),
        send
      )
