// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{Async, Concurrent, Resource}
import cats.effect.std.Queue
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import com.comcast.ip4s.{Host, IpAddress, SocketAddress}
import xyz.kd5ujc.scarab.network.selection.DiffusionMode
import xyz.kd5ujc.scarab.protocol.ConnectionIdentityError
import fs2.{Chunk, Stream}
import scala.concurrent.duration.*

/** An ordered, reliable byte pipe to one peer. Interpreters surface transport failures as
  * Throwables; the multiplexer is the single place that turns them into [[NetworkError]].
  */
trait Bearer[F[_]]:
  /** Socket metadata is acquired once by Mux, outside every mini-protocol error scope. */
  def connectionId: Option[F[Either[ConnectionIdentityError, ConnectionId]]] = None

  /** Bytes from the peer; the stream ends when the peer closes its side. Consume it once. It fails
    * by raising, never in a finalizer: the multiplexer reads it inside fs2's `merge`, which would
    * report a finalizer's failure on interruption to the runtime as well (issue 647).
    */
  def reads: Stream[F, Byte]

  def write(bytes: Chunk[Byte]): F[Unit]

  /** Signal end of output to the peer. */
  def close: F[Unit]

object Bearer:
  /** A tool's TCP connection to `address`: its host resolved by the JVM, so DNS relays work, then a
    * socket opened as the node opens one with no listening address of its own ([[TcpSockets.dial]],
    * no bind). The node's peers are dialled by its transport, which binds them.
    */
  def tcp[F[_]: Async](address: SocketAddress[Host], connectTimeout: FiniteDuration)(using
      Raise[F, NetworkError]
  ): Resource[F, Bearer[F]] =
    Resource
      .eval(ConnectionManager.resolve[F](address).attempt.flatMap {
        case Right(remote) => remote.pure[F]
        case Left(e: java.io.IOException) =>
          NetworkError
            .Connect(
              address.toString,
              TcpSocketError.Failed(SocketStep.Resolve(address.host), Errno.of(e))
            )
            .raise[F, SocketAddress[IpAddress]]
        case Left(other) => NetworkError.Defect(other).raise[F, SocketAddress[IpAddress]]
      })
      .flatMap(remote =>
        TcpSockets.dial[F](
          OutboundPlan.of(remote, NodeAddresses.none, DiffusionMode.InitiatorOnly),
          connectTimeout
        )
      )
      .map(_.bearer)

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
