// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.{Applicative, ApplicativeThrow}
import cats.effect.{Concurrent, Deferred}
import cats.effect.implicits.*
import cats.mtl.{Handle, Raise}
import cats.syntax.all.*
import scala.util.control.NonFatal

/** The error scope of a fiber.
  *
  * A fiber never raises through a capability it did not create: a `Raise` that comes from
  * `Handle.allow` is valid in the fibers of its scope only, and raised in any other fiber it is an
  * exception (`cats.mtl.Handle$Submarine`) that no scope catches, so the fiber dies unobserved and
  * whatever it held stays held (issue 130). Each fiber runs its body in a scope of its own and
  * finishes with an `Either`; the typed error is a value (`runExceptT`), and across the fiber
  * boundary only values travel (`Deferred`, a bounded `Queue`, `Fiber.joinWith`). The fiber's owner
  * turns the value back into a typed raise through its own `Raise` ([[land]], Haskell's
  * `either throwError pure`).
  */
object Fibers:
  /** `body` in an error scope of its own: the `E` it raises is the left of the result. An exception
    * that a library throws (not our domain) is wrapped into an `E` here by `defect`, the one place
    * it is given a type; cancellation is not an error and passes through.
    */
  def scoped[F[_], E, A](defect: Throwable => E)(body: Handle[F, E] ?=> F[A])(using
      F: ApplicativeThrow[F]
  ): F[Either[E, A]] =
    Handle
      .allow[E](body.map(_.asRight[E]))
      .rescue(e => F.pure(e.asLeft[A]))
      .handleErrorWith {
        case NonFatal(t) => F.pure(defect(t).asLeft[A])
        case other       => F.raiseError(other)
      }

  /** The value of a fiber's result raised in the scope of its owner. */
  def land[F[_]: Applicative, E, A](result: Either[E, A])(using raise: Raise[F, E]): F[A] =
    result.fold(raise.raise[E, A](_), Applicative[F].pure)

  /** The outcome of the first of two scoped bodies that fails, or of both: the others are cancelled
    * once one has failed (the `parSequence` of values, which fails fast without an exception).
    */
  def failFast[F[_]: Concurrent, E](bodies: List[F[Either[E, Unit]]]): F[Either[E, Unit]] =
    Deferred[F, E].flatMap { failed =>
      val watched = bodies.map(_.flatMap(_.fold(failed.complete(_).void, _ => Concurrent[F].unit)))
      Concurrent[F]
        .race(failed.get, watched.parSequence_)
        .map(_.fold(_.asLeft[Unit], _ => ().asRight[E]))
    }
