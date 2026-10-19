// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.{Defer, MonadThrow}
import cats.mtl.Handle
import cats.syntax.all.*
import scala.util.control.NonFatal
import xyz.kd5ujc.scarab.codec.DomainError

/** The listener keeps intentional handler failures separate from unexpected exceptions. */
enum HandlerFailure[+E <: DomainError] extends DomainError:
  case Domain(error: E)
  case Defect(cause: Throwable)

object HandlerFailure:
  private[network] def capture[F[_]: MonadThrow: Defer, E <: DomainError, A](
      body: Handle[F, E] ?=> F[A]
  ): F[Either[HandlerFailure[E], A]] =
    Defer[F]
      .defer {
        Handle
          .allow[E](body.map(_.asRight[HandlerFailure[E]]))
          .rescue(error => MonadThrow[F].pure(Left(Domain(error))))
      }
      .handleErrorWith {
        case NonFatal(cause) => MonadThrow[F].pure(Left(Defect(cause)))
        case fatal           => MonadThrow[F].raiseError(fatal)
      }
