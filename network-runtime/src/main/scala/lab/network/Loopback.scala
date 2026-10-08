// SPDX-License-Identifier: Apache-2.0
package lab.network

import cats.effect.{Async, Deferred, Ref, Resource}
import cats.effect.std.Queue
import cats.syntax.all.*
import lab.cbor.Bytes

/** Narrow transport boundary. Addressing/lifecycle belong to interpreters; no peer discovery. */
trait ByteTransport[F[_]]:
  def read: F[Option[Bytes]]
  def write(bytes: Bytes): F[Unit]
  def close: F[Unit]
  def isClosed: F[Boolean]

object Loopback:
  final case class Limits(queueCapacity: Int = 4, maxChunkBytes: Int = 1024)

  /** Bounded in-memory duplex stream. Closing either endpoint closes both directions, interrupts
    * blocked readers/writers and drops pending data. Resource release is idempotent; cancellation
    * cannot leave a producer waiting on a full queue.
    */
  def pair[F[_]: Async](
      limits: Limits = Limits()
  ): Resource[F, (ByteTransport[F], ByteTransport[F])] =
    val F = Async[F]
    Resource.eval(
      F.raiseUnless(
        limits.queueCapacity > 0 && limits.queueCapacity <= 64 &&
          limits.maxChunkBytes > 0 && limits.maxChunkBytes <= 65543
      )(
        new IllegalArgumentException("invalid loopback resource limits")
      )
    ) *> Resource.make {
      for
        left <- Queue.bounded[F, Bytes](limits.queueCapacity)
        right <- Queue.bounded[F, Bytes](limits.queueCapacity)
        closed <- Deferred[F, Unit]
      yield
        def endpoint(in: Queue[F, Bytes], out: Queue[F, Bytes]): ByteTransport[F] =
          new ByteTransport[F]:
            def close: F[Unit] = closed.complete(()).void
            def isClosed: F[Boolean] = closed.tryGet.map(_.isDefined)
            def read: F[Option[Bytes]] =
              closed.tryGet.flatMap {
                case Some(_) => F.pure(None)
                case None    => F.race(closed.get, in.take).map(_.toOption)
              }
            def write(bytes: Bytes): F[Unit] =
              F.raiseWhen(bytes.size == 0 || bytes.size > limits.maxChunkBytes)(
                new IllegalArgumentException("empty or oversized transport chunk")
              ) *> closed.tryGet.flatMap {
                case Some(_) => F.raiseError(new IllegalStateException("transport closed"))
                case None =>
                  F.race(closed.get, out.offer(bytes)).flatMap {
                    case Left(_)  => F.raiseError(new IllegalStateException("transport closed"))
                    case Right(_) => F.unit
                  }
              }
        (endpoint(left, right), endpoint(right, left))
    } { case (left, _) => left.close }
