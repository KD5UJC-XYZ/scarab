// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

// Higher-kinded data (cardano-ledger f649f975 `libs/cardano-ledger-core/src/Cardano/Ledger/HKD.hs`):
// one record of an era's protocol parameters serves as the parameters, each field a value
// (`Identity`), and as an update, each field present or not (`StrictMaybe`).

/** `Identity`: a field of the parameters is its value (`HKD Identity a = a`, `HKD.hs:30-32`,
  * cardano-ledger f649f975).
  */
type Identity[A] = A

/** `StrictMaybe` (`Data.Maybe.Strict`): a field of an update is set or not. Scala's `Option` is
  * strict, so it is the reference's type.
  */
type StrictMaybe[A] = Option[A]

/** `NoUpdate` (`HKD.hs:34-35`, cardano-ledger f649f975): the field an update cannot set, such as
  * Conway's protocol version. Every two are equal.
  */
final case class NoUpdate[A]()

object HKD:
  /** The argument [[HKDNoUpdate]] applies `F` to, to tell `Identity` from `StrictMaybe`. No value
    * has this type.
    */
  sealed trait Probe

  /** An invariant wrapper, so that a case of [[HKDNoUpdate]] matches its type constructor exactly
    * and not a subtype of it (`Some` is not `Option`).
    */
  final class Exactly[X] private ()

/** `HKDNoUpdate f a` (`HKD.hs:41-45`, cardano-ledger f649f975): the value at `Identity`, nothing to
  * set at `StrictMaybe`, and `F[A]` at any other type constructor. A match type tells constructors
  * apart by subtype, so at `Some`, the one constructor below `Option`, it does not reduce, and a
  * record over `Some` does not compile, rather than taking `Option` 's case.
  */
type HKDNoUpdate[F[_], A] = HKD.Exactly[F[HKD.Probe]] match
  case HKD.Exactly[HKD.Probe]         => A
  case HKD.Exactly[Option[HKD.Probe]] => NoUpdate[A]
  case Any                            => F[A]

/** `HKDFunctor` (`HKD.hs:47-65`, cardano-ledger f649f975): what a lens of the parameters needs of
  * the record's type constructor. An instance for `Identity` and for `StrictMaybe`.
  */
trait HKDFunctor[F[_]]:
  def hkdMap[A, B](f: A => B)(fa: F[A]): F[B]
  def toNoUpdate[A](fa: F[A]): HKDNoUpdate[F, A]
  def fromNoUpdate[A](n: HKDNoUpdate[F, A]): F[A]

object HKDFunctor:
  given identity: HKDFunctor[Identity] with
    def hkdMap[A, B](f: A => B)(fa: A): B = f(fa)
    def toNoUpdate[A](fa: A): A = fa
    def fromNoUpdate[A](n: A): A = n

  given strictMaybe: HKDFunctor[StrictMaybe] with
    def hkdMap[A, B](f: A => B)(fa: Option[A]): Option[B] = fa.map(f)
    def toNoUpdate[A](fa: Option[A]): NoUpdate[A] = NoUpdate()
    def fromNoUpdate[A](n: NoUpdate[A]): Option[A] = None
