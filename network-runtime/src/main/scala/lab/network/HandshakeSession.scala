// SPDX-License-Identifier: Apache-2.0
package lab.network

import cats.effect.{Async, Ref, Resource}
import cats.effect.std.Semaphore
import cats.effect.syntax.all.*
import cats.syntax.all.*
import lab.cbor.Bytes
import scala.concurrent.duration.*

/** One protocol-0 stream, with a fixed receive direction and a whole-phase deadline. Ownership is
  * scoped: releasing the session closes its transport, and a failed or canceled phase poisons the
  * session by closing the stream rather than reusing it.
  */
final class HandshakeSession[F[_]: Async] private (
    transport: ByteTransport[F],
    suite: Handshake.Suite,
    direction: Mux.Direction,
    deadline: FiniteDuration,
    maxSdu: Int,
    stream: Ref[F, (Mux.Decoder, Bytes)],
    gate: Semaphore[F]
):
  private val F = Async[F]
  private def checked[A](result: Either[String, A]): F[A] =
    F.fromEither(result.leftMap(new IllegalArgumentException(_)))

  def send(message: Handshake.Message): F[Unit] =
    val operation = for
      bytes <- checked(Handshake.encode(suite, message))
      frames <- checked(Mux.segment(bytes, 0L, direction, maxSdu))
      _ <- frames.traverse_(frame => checked(Mux.encode(frame)).flatMap(transport.write))
    yield ()
    bounded(operation)

  def receive(state: Handshake.State): F[Handshake.Message] =
    def loop: F[Handshake.Message] = stream.get.flatMap { case (decoder, pending) =>
      Handshake.decodePrefix(suite, state, pending) match
        case Right((message, rest)) => stream.set((decoder, rest)).as(message)
        case Left(error) if !error.incomplete =>
          F.raiseError(new IllegalArgumentException(error.message))
        case Left(_) =>
          transport.read.flatMap {
            case None =>
              checked(decoder.finish) *> F.raiseError(
                new IllegalStateException("EOF before complete handshake message")
              )
            case Some(chunk) if chunk.size == 0 =>
              F.raiseError(new IllegalArgumentException("empty transport read"))
            case Some(chunk) =>
              for
                decoded <- checked(decoder.feed(chunk))
                (next, frames) = decoded
                _ <- F.raiseWhen(
                  frames.exists(frame => frame.protocol != 0 || frame.direction == direction)
                )(
                  new IllegalArgumentException("unexpected mux protocol or direction")
                )
                added = frames.foldLeft(pending.value)((acc, frame) => acc ++ frame.payload.value)
                _ <- F.raiseWhen(added.size > Handshake.MaxMessageBytes)(
                  new IllegalArgumentException("handshake pending bytes exceed local limit")
                )
                _ <- stream.set((next, Bytes(added)))
                result <- loop
              yield result
          }
    }
    gate.permit.use(_ => bounded(loop))

  private def bounded[A](operation: F[A]): F[A] =
    F.timeoutTo(
      operation,
      deadline,
      F.raiseError(new java.util.concurrent.TimeoutException("handshake phase deadline"))
    ).onError { case _ => transport.close }
      .onCancel(transport.close)

object HandshakeSession:
  def resource[F[_]: Async](
      transport: ByteTransport[F],
      suite: Handshake.Suite,
      direction: Mux.Direction,
      deadline: FiniteDuration = 10.seconds,
      maxSdu: Int = 256
  ): Resource[F, HandshakeSession[F]] =
    val F = Async[F]
    Resource.make {
      for
        _ <- F.raiseUnless(
          deadline > Duration.Zero && maxSdu > 0 && maxSdu <= Handshake.MaxMessageBytes
        )(
          new IllegalArgumentException("invalid handshake session limits")
        )
        decoder <- F.fromEither(
          Mux.Decoder
            .create(
              Mux.Limits(
                maxPayloadBytes = Handshake.MaxMessageBytes,
                maxInputBytes = 65543,
                maxFramesPerFeed = 5760
              )
            )
            .leftMap(new IllegalArgumentException(_))
        )
        stream <- Ref.of[F, (Mux.Decoder, Bytes)]((decoder, Bytes.empty))
        gate <- Semaphore[F](1)
      yield new HandshakeSession(transport, suite, direction, deadline, maxSdu, stream, gate)
    }(_ => transport.close)
