// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.{Defer, MonadThrow}
import cats.mtl.Handle
import cats.syntax.all.*
import scala.util.control.NonFatal
import xyz.kd5ujc.scarab.codec.DomainError
import xyz.kd5ujc.scarab.ledger.StorageError

/** Snapshot failures stay separate from format refusals: both allow replay on the next start, but a
  * failed operation must retain its storage classification or original unexpected cause.
  */
enum SnapshotFailure extends DomainError:
  case Storage(error: StorageError)
  case Defect(cause: Throwable)

  def message: String = this match
    case Storage(error) => error.message
    case Defect(cause)  => s"$cause"

object SnapshotFailure:
  /** Each snapshot worker/finalizer owns its error scope (ADR0017). Cancellation passes through. */
  private[storage] def capture[F[_]: MonadThrow: Defer, A](
      body: Handle[F, StorageError] ?=> F[A]
  ): F[Either[SnapshotFailure, A]] =
    Defer[F]
      .defer {
        Handle
          .allow[StorageError](body.map(_.asRight[SnapshotFailure]))
          .rescue(error => MonadThrow[F].pure(Left(Storage(error))))
      }
      .handleErrorWith {
        case NonFatal(cause) => MonadThrow[F].pure(Left(Defect(cause)))
        case fatal           => MonadThrow[F].raiseError(fatal)
      }
