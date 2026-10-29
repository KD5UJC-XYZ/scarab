// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.Async
import cats.effect.std.Queue
import cats.effect.syntax.all.*
import fs2.Stream
import cats.mtl.{Handle, Raise}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.ledger.StorageError

/** A serving callback owns its storage scope. A failed store stops the node, as a storage failure
  * does in the reference (consensus 82ecba329 `Node/RethrowPolicy.hs`: `ChainDbFailure`, the
  * databases' `UnexpectedFailure` and `FsError` are `shutdownNode`), and so does stored data that
  * does not decode ([[StorageError.DecoderDefect]]). The callback then waits cancelably for
  * shutdown, so a storage outage is never attributed to its client. Any other exception is the
  * serving connection's: it is raised again, so the connection's scope ends that connection and the
  * node goes on, as the reference's default `ShutdownPeer` does (issue 567).
  */
private[node] object StorageBoundary:
  /** The body's value, its storage failure, or the exception it threw. */
  private def attempt[F[_]: Async, A](
      body: Raise[F, StorageError] ?=> F[A]
  ): F[Either[Either[StorageError, Throwable], A]] =
    Async[F]
      .defer(
        Handle
          .allow[StorageError](body.map(_.asRight[Either[StorageError, Throwable]]))
          .rescue(error => Async[F].pure(Left(Left(error))))
      )
      .handleError(cause => Left(Right(cause)))

  private def stop[F[_]: Async, A](fatal: Fatal => F[Unit])(error: StorageError): F[A] =
    val reported = error match
      case StorageError.DecoderDefect(_, cause) => Fatal.Defect(cause)
      case StorageError.Reconstruction(what, cause) =>
        Fatal.Internal(StorageError.Reconstruction(what, cause))
      case other => Fatal.Storage(other)
    fatal(reported) *> Async[F].never[A]

  def run[F[_]: Async, A](fatal: Fatal => F[Unit])(
      body: Raise[F, StorageError] ?=> F[A]
  ): F[A] =
    attempt(body).flatMap {
      case Right(value)        => Async[F].pure(value)
      case Left(Left(error))   => stop(fatal)(error)
      case Left(Right(thrown)) => Async[F].raiseError[A](thrown)
    }

  /** A bounded producer keeps the scope alive for the whole store stream. Only values cross to the
    * consumer; cancellation releases both producer and its file/iterator resources.
    */
  def stream[F[_]: Async, A](fatal: Fatal => F[Unit])(
      body: Raise[F, StorageError] ?=> Stream[F, A]
  ): Stream[F, A] =
    Stream.eval(Queue.bounded[F, Option[Either[Either[StorageError, Throwable], A]]](1)).flatMap {
      queue =>
        val produce = attempt {
          body.evalMap(value => queue.offer(Some(Right(value)))).compile.drain
        }.flatMap {
          case Right(_)    => queue.offer(None)
          case Left(error) => queue.offer(Some(Left(error)))
        }
        Stream.resource(produce.background).flatMap { _ =>
          Stream.repeatEval(queue.take).unNoneTerminate.evalMap {
            case Right(value)        => Async[F].pure(value)
            case Left(Left(error))   => stop[F, A](fatal)(error)
            case Left(Right(thrown)) => Async[F].raiseError[A](thrown)
          }
        }
    }
