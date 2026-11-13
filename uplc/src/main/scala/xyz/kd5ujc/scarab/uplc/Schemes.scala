// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc

import cats.{Id, Monad, Traverse}
import cats.data.State
import xyz.kd5ujc.scarab.codec.Tree
import xyz.kd5ujc.scarab.codec.Tree.{treeHash, treeString}

/** The fixed point of a functor `F`: a value whose layers are `F` s. It keeps the `Traverse` of its
  * functor so that equality, hashing and rendering walk the layers on the heap ([[Tree]]): a term
  * is as deep as its script, which the stack does not bound (ADR 0011). Two layers are alike when
  * they are equal with their children forgotten (`F.void`).
  */
final case class Fix[F[_]](unfix: F[Fix[F]])(using val functor: Traverse[F]):
  override def equals(that: Any): Boolean = that match
    case o: Fix[?] => Fix.equal(this, o)
    case _         => false
  override def hashCode(): Int = this.treeHash(using Fix.tree[F])
  override def toString: String = this.treeString(using Fix.tree[F])

object Fix:
  given tree[F[_]]: Tree[Fix[F]] with
    def children(x: Fix[F]): IndexedSeq[Fix[F]] = x.functor.toList(x.unfix).toVector
    def sameLabel(x: Fix[F], y: Fix[F]): Boolean =
      x.functor.void(x.unfix) == y.functor.void(y.unfix)
    def labelHash(x: Fix[F]): Int = x.functor.void(x.unfix).hashCode
    def name(x: Fix[F]): String = x.functor.void(x.unfix).toString

  // Values of one Scala type `Fix[?]` may have different functors; only those of the same functor
  // instance are compared by layers.
  private def equal[F[_]](x: Fix[F], y: Fix[?]): Boolean = y match
    case same: Fix[F] @unchecked if same.functor eq x.functor => Tree.equal(x, same)(using tree[F])
    case _                                                    => false

/** Folds and unfolds of a [[Fix]] that run in constant stack: one machine, [[hyloM]], keeps the
  * layers still open on a list on the heap and steps through `tailRecM`, which every lawful
  * `cats.Monad` runs in constant stack. The rest are instances of it.
  *
  * The order of effects is that of the plain recursive definitions: the coalgebra runs on a seed
  * before the seeds of its layer, and those left to right in the order of `Traverse`; the algebra
  * runs on a layer after all of its children, so the coalgebra runs in pre-order and the algebra in
  * post-order. A decoder threading a cursor through `M` therefore reads in the order of the input,
  * provided each layer's children are known when the layer is: see [[TermF]] for the lists of
  * `constr` and `case`.
  */
object Schemes:

  /** The fold: `algebra` applied bottom-up. */
  def cata[F[_]: Traverse, A](term: Fix[F])(algebra: F[A] => A): A =
    hyloM[F, Id, Fix[F], A](term)(_.unfix, algebra)

  /** The unfold: `coalgebra` applied top-down from `seed`. */
  def ana[F[_]: Traverse, A](seed: A)(coalgebra: A => F[A]): Fix[F] =
    hyloM[F, Id, A, Fix[F]](seed)(coalgebra, Fix(_))

  /** An unfold followed by a fold, without building the intermediate value. */
  def hylo[F[_]: Traverse, A, B](seed: A)(coalgebra: A => F[A], algebra: F[B] => B): B =
    hyloM[F, Id, A, B](seed)(coalgebra, algebra)

  /** The fold with an effect in `M`. */
  def cataM[F[_]: Traverse, M[_]: Monad, A](term: Fix[F])(algebra: F[A] => M[A]): M[A] =
    hyloM[F, M, Fix[F], A](term)(t => Monad[M].pure(t.unfix), algebra)

  /** The unfold with an effect in `M`. */
  def anaM[F[_]: Traverse, M[_]: Monad, A](seed: A)(coalgebra: A => M[F[A]]): M[Fix[F]] =
    hyloM[F, M, A, Fix[F]](seed)(coalgebra, layer => Monad[M].pure(Fix(layer)))

  /** The machine: unfold `seed` with `coalgebra` and fold the layers with `algebra`, in constant
    * stack.
    */
  def hyloM[F[_], M[_], A, B](seed: A)(coalgebra: A => M[F[A]], algebra: F[B] => M[B])(using
      F: Traverse[F],
      M: Monad[M]
  ): M[B] =
    // An open layer: its seeds, the results of the children done so far (the latest first) and the
    // seeds still to unfold, in the order of `Traverse`.
    final case class Open(layer: F[A], done: List[B], todo: List[A])
    enum Step:
      case Unfold(seed: A, stack: List[Open])
      case Return(result: B, stack: List[Open])

    // The layer with its children's results in place, or the seed of the first child without one.
    // Every child has a result once `todo` is empty, so the second case does not arise; the machine
    // unfolds that seed if it did, which keeps it total without a partial match.
    def filled(layer: F[A], results: List[B]): Either[A, F[B]] =
      val slots = F
        .traverse(layer)(a =>
          State[List[B], Either[A, B]] {
            case b :: rest => (rest, Right(b))
            case Nil       => (Nil, Left(a))
          }
        )
        .runA(results)
        .value
      F.sequence[[x] =>> Either[A, x], B](slots)

    def descend(layer: F[A], done: List[B], todo: List[A], below: List[Open]): M[Either[Step, B]] =
      todo match
        case next :: rest => M.pure(Left(Step.Unfold(next, Open(layer, done, rest) :: below)))
        case Nil =>
          filled(layer, done.reverse) match
            case Right(complete) => M.map(algebra(complete))(b => Left(Step.Return(b, below)))
            case Left(missing) =>
              M.pure(Left(Step.Unfold(missing, Open(layer, done, Nil) :: below)))

    M.tailRecM[Step, B](Step.Unfold(seed, Nil)) {
      case Step.Unfold(a, stack) =>
        M.flatMap(coalgebra(a))(layer => descend(layer, Nil, F.toList(layer), stack))
      case Step.Return(b, Nil) => M.pure(Right(b))
      case Step.Return(b, Open(layer, done, todo) :: below) =>
        descend(layer, b :: done, todo, below)
    }
