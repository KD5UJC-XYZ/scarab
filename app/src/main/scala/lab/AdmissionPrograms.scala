// SPDX-License-Identifier: Apache-2.0
package lab

import cats.{Functor, Monad}
import cats.syntax.all.*
import lab.submission.{AdmissionState, AdmissionView, StatePin}

/** Package-trusted programs; pin data never supplies the owner capability. Interpreters retain
  * ownership of serialization, masking and poisoning. Programs introduce no fibers or masks.
  */
private[lab] object AdmissionPrograms:
  trait Read[F[_]]:
    def current: F[AdmissionView]

  trait Fence[F[_]]:
    def commit[A](expected: StatePin)(action: F[A]): F[Fenced[A]]

  enum Fenced[+A]:
    case Applied(value: A)
    case Stale(current: StatePin)

  enum Guarded[+A]:
    case Read(value: A)
    case Exhausted(current: StatePin)

  /** Explicit interpreter construction keeps read and fence tied to the same supplied owner. No
    * global given can manufacture commit authority from a view or a pin.
    */
  final class OwnerCapabilities[F[_]: Functor] private[AdmissionPrograms] (
      owner: AdmissionState[F]
  ) extends Read[F]
      with Fence[F]:
    def current: F[AdmissionView] = owner.current
    def commit[A](expected: StatePin)(action: F[A]): F[Fenced[A]] =
      owner.withCurrent(expected)(action).map {
        case Left(pin)    => Fenced.Stale(pin)
        case Right(value) => Fenced.Applied(value)
      }

  def fromOwner[F[_]: Functor](owner: AdmissionState[F]): OwnerCapabilities[F] =
    new OwnerCapabilities(owner)

  def current[F[_]](using read: Read[F]): F[AdmissionView] = read.current

  object syntax:
    extension (expected: StatePin)
      def commitIfCurrent[F[_], A](action: F[A])(using fence: Fence[F]): F[Fenced[A]] =
        fence.commit(expected)(action)

  /** Four complete read/fence attempts, exactly matching the service's historical retry budget. The
    * action is evaluated only by the fence on a match, never between the view and comparison.
    */
  def guarded[F[_]: Monad, A](action: F[A])(using Read[F], Fence[F]): F[Guarded[A]] =
    import syntax.*
    Monad[F].tailRecM(3) { retries =>
      current[F].flatMap(view => view.pin.commitIfCurrent(action)).map {
        case Fenced.Applied(value)          => Right(Guarded.Read(value))
        case Fenced.Stale(_) if retries > 0 => Left(retries - 1)
        case Fenced.Stale(pin)              => Right(Guarded.Exhausted(pin))
      }
    }
