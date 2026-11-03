// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.injection

import cats.Functor
import cats.effect.{Resource, Sync}
import cats.mtl.{Handle, Raise}
import cats.syntax.all.*
import scala.util.control.NonFatal
import xyz.kd5ujc.scarab.codec.{Bytes, DomainError}

/** The narrow HasFS operations consumed by `foldInjectionData`; no OS interpreter is supplied.
  * `use` creates the body error scope and materializes its outcome before closing the handle. Thus
  * close failure takes precedence over body failure, as fs-api 0.4.0.0 `API.hs:214-216` does.
  */
final class InjectionFiles[F[_], FileE <: DomainError, H](
    operations: InjectionFiles.Operations[F, FileE, H]
)(using F: Sync[F]):
  import InjectionFiles.*

  private def mapped[BodyE <: DomainError](using
      errors: Raise[F, Failure[FileE, BodyE]]
  ): Raise[F, FileE] = new Raise[F, FileE]:
    def functor: Functor[F] = F
    def raise[E <: FileE, A](error: E): F[A] = errors.raise(Failure.File(error))

  private def capture[BodyE <: DomainError, A](
      body: Handle[F, Failure[FileE, BodyE]] ?=> F[A]
  ): F[Either[Failure[FileE, BodyE], A]] =
    F.defer {
      Handle
        .allow[Failure[FileE, BodyE]](body.map(_.asRight[Failure[FileE, BodyE]]))
        .rescue(error => F.pure(Left(error)))
    }.handleErrorWith {
      case NonFatal(cause) => F.pure(Left(Failure.Defect(cause)))
      case fatal           => F.raiseError(fatal)
    }

  private[injection] def scoped[BodyE <: DomainError, A](
      body: Handle[F, Failure[FileE, BodyE]] ?=> F[A]
  )(using errors: Raise[F, Failure[FileE, BodyE]]): F[A] =
    capture(body).flatMap(_.fold(errors.raise(_), F.pure))

  /** The callback receives its own capability, never a caller capability kept by the handle. A
    * callback error or unexpected exception becomes a value inside Resource.use; only a successful
    * finalizer allows that value to be raised again. Cancellation still runs release.
    */
  def use[BodyE <: DomainError, A](segments: Vector[String])(
      body: Read[F, Failure[FileE, BodyE]] => Handle[F, Failure[FileE, BodyE]] ?=> F[A]
  )(using errors: Raise[F, Failure[FileE, BodyE]]): F[A] =
    val result = capture[BodyE, Either[Failure[FileE, BodyE], A]] {
      given Raise[F, FileE] = mapped[BodyE]
      Resource
        .make(operations.open(segments))(operations.close)
        .use { handle =>
          capture[BodyE, A] {
            val reader = new Read[F, Failure[FileE, BodyE]]:
              def readSome(using Raise[F, Failure[FileE, BodyE]]): F[Bytes] =
                operations.readSome(handle, 65536)(using mapped[BodyE])
            body(reader)
          }
        }
    }
    result.flatMap(_.flatten.fold(errors.raise(_), F.pure))

object InjectionFiles:
  enum Failure[+FileE <: DomainError, +BodyE <: DomainError] extends DomainError:
    case File(cause: FileE)
    case Body(cause: BodyE)
    case Defect(cause: Throwable)

  trait Read[F[_], E <: DomainError]:
    /** One native hGetSome request of 65,536 bytes; empty bytes mark the stream's EOF. */
    def readSome(using Raise[F, E]): F[Bytes]

  trait Operations[F[_], E <: DomainError, H]:
    def open(segments: Vector[String])(using Raise[F, E]): F[H]
    def readSome(handle: H, requested: Int)(using Raise[F, E]): F[Bytes]
    def close(handle: H)(using Raise[F, E]): F[Unit]
