// SPDX-License-Identifier: Apache-2.0
package lab.network.runtime

import cats.effect.{Async, Concurrent, Resource}
import cats.effect.std.Queue
import cats.effect.syntax.all.*
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import com.comcast.ip4s.{Host, SocketAddress}
import fs2.{Chunk, Stream}
import fs2.io.net.{Network, Socket}
import scala.concurrent.duration.*

/** An ordered, reliable byte pipe to one peer. Interpreters surface transport failures as
  * Throwables; the multiplexer is the single place that turns them into [[NetworkError]].
  */
trait Bearer[F[_]]:
  /** Bytes from the peer; the stream ends when the peer closes its side. Consume it once. */
  def reads: Stream[F, Byte]

  def write(bytes: Chunk[Byte]): F[Unit]

  /** Signal end of output to the peer. */
  def close: F[Unit]

object Bearer:
  def fromSocket[F[_]](socket: Socket[F]): Bearer[F] = new Bearer[F]:
    def reads = socket.reads
    def write(bytes: Chunk[Byte]) = socket.write(bytes)
    def close = socket.endOfOutput

  /** Open a TCP connection. Host names are resolved by the JVM, so DNS relays work. */
  def tcp[F[_]: Async](address: SocketAddress[Host], connectTimeout: FiniteDuration)(using
      Raise[F, NetworkError]
  ): Resource[F, Bearer[F]] =
    Resource
      .make(
        Network
          .forAsync[F]
          .connect(address)
          .allocated
          .timeout(connectTimeout)
          .attempt
          .flatMap {
            case Right(allocated) => allocated.pure[F]
            case Left(e) =>
              val reason = Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
              NetworkError.Connect(address.toString, reason).raise[F, (Socket[F], F[Unit])]
          }
      )(_._2)
      .map(allocated => fromSocket(allocated._1))

  /** Two connected in-memory ends. Each direction holds at most `capacity` writes, so a slow reader
    * blocks the writer exactly like a full socket buffer.
    */
  def inMemory[F[_]: Concurrent](capacity: Int = 64): F[(Bearer[F], Bearer[F])] =
    for
      aToB <- Queue.bounded[F, Option[Chunk[Byte]]](capacity)
      bToA <- Queue.bounded[F, Option[Chunk[Byte]]](capacity)
    yield (end(incoming = bToA, outgoing = aToB), end(incoming = aToB, outgoing = bToA))

  private def end[F[_]](
      incoming: Queue[F, Option[Chunk[Byte]]],
      outgoing: Queue[F, Option[Chunk[Byte]]]
  ): Bearer[F] = new Bearer[F]:
    def reads = Stream.fromQueueNoneTerminatedChunk(incoming)
    def write(bytes: Chunk[Byte]) = outgoing.offer(Some(bytes))
    def close = outgoing.offer(None)
