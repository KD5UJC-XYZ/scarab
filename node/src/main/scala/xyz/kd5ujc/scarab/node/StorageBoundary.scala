// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.Async
import cats.effect.std.Queue
import cats.effect.syntax.all.*
import fs2.Stream
import cats.mtl.Raise
import cats.syntax.all.*
import xyz.kd5ujc.scarab.ledger.StorageError
import xyz.kd5ujc.scarab.storage.SnapshotFailure

/** A serving callback owns its storage scope. A failed store stops the node; the callback waits
  * cancelably for shutdown so neither a storage outage nor a defect is attributed to its client.
  */
private[node] trait StorageBoundary[F[_]]:
  def apply[A](body: Raise[F, StorageError] ?=> F[A]): F[A]

private[node] object StorageBoundary:
  def apply[F[_]: Async](fatal: Fatal => F[Unit]): StorageBoundary[F] =
    new StorageBoundary[F]:
      def apply[A](body: Raise[F, StorageError] ?=> F[A]): F[A] = run(fatal)(body)

  def run[F[_]: Async, A](fatal: Fatal => F[Unit])(
      body: Raise[F, StorageError] ?=> F[A]
  ): F[A] =
    SnapshotFailure.capture(body).flatMap {
      case Right(value)                         => Async[F].pure(value)
      case Left(SnapshotFailure.Storage(error)) => fatal(Fatal.Storage(error)) *> Async[F].never[A]
      case Left(SnapshotFailure.Defect(cause))  => fatal(Fatal.Defect(cause)) *> Async[F].never[A]
    }

  /** A bounded producer keeps the scope alive for the whole store stream. Only typed values cross
    * to the consumer; cancellation releases both producer and its file/iterator resources.
    */
  def stream[F[_]: Async, A](fatal: Fatal => F[Unit])(
      body: Raise[F, StorageError] ?=> Stream[F, A]
  ): Stream[F, A] =
    Stream.eval(Queue.bounded[F, Option[Either[SnapshotFailure, A]]](1)).flatMap { queue =>
      val produce = SnapshotFailure
        .capture {
          body.evalMap(value => queue.offer(Some(Right(value)))).compile.drain
        }
        .flatMap {
          case Right(_)    => queue.offer(None)
          case Left(error) => queue.offer(Some(Left(error)))
        }
      Stream.resource(produce.background).flatMap { _ =>
        Stream.repeatEval(queue.take).unNoneTerminate.evalMap {
          case Right(value) => Async[F].pure(value)
          case Left(SnapshotFailure.Storage(error)) =>
            fatal(Fatal.Storage(error)) *> Async[F].never[A]
          case Left(SnapshotFailure.Defect(cause)) =>
            fatal(Fatal.Defect(cause)) *> Async[F].never[A]
        }
      }
    }
