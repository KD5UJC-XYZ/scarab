// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.IO
import cats.mtl.Raise
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.ChainSync.DecodeResult

/** A multiplexer whose decoder of one mini-protocol throws: `recv` on `poisoned` hands the bytes to
  * a decoder that throws `injected`. Everything else, `closed` and `abort` included, is the wrapped
  * multiplexer's. Real decoders refuse with `DecodeResult.Failed` and never throw, so the throwing
  * decoder is the only way to observe what the node does when one does.
  */
final class PoisonedMux(underlying: Mux[IO], poisoned: Int, injected: Throwable) extends Mux[IO]:
  def awaitResponderActivity(using Raise[IO, NetworkError]): IO[Unit] =
    underlying.awaitResponderActivity
  def installApplication(initiators: Set[Int], responders: Set[Int])(using
      Raise[IO, NetworkError]
  ): IO[Unit] = underlying.installApplication(initiators, responders)
  override def connectionId = underlying.connectionId
  def channel(protocol: Int)(using Raise[IO, NetworkError]): IO[MuxChannel[IO]] =
    underlying.channel(protocol).map(wrap)
  def channel(protocol: Int, role: Mux.Role)(using Raise[IO, NetworkError]): IO[MuxChannel[IO]] =
    underlying.channel(protocol, role).map(wrap)
  def closed: IO[NetworkError] = underlying.closed
  def failure: IO[Option[NetworkError]] = underlying.failure
  def abort(reason: NetworkError): IO[Unit] = underlying.abort(reason)

  private def wrap(channel: MuxChannel[IO]): MuxChannel[IO] =
    if channel.protocol != poisoned then channel else PoisonedMux.channel(channel, injected)

object PoisonedMux:
  def channel(underlying: MuxChannel[IO], injected: Throwable): MuxChannel[IO] =
    new MuxChannel[IO]:
      def protocol: Int = underlying.protocol
      def send(message: Bytes)(using Raise[IO, NetworkError]): IO[Unit] = underlying.send(message)
      def flush: IO[Unit] = underlying.flush
      def recv[A](maxBytes: Int)(decode: Bytes => DecodeResult[A])(using
          Raise[IO, NetworkError]
      ): IO[A] = underlying.recv(maxBytes)(_ => throw injected)
