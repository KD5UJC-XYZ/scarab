// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.codec

import scala.annotation.tailrec

/** A post-order fold over a tree on the heap: the recursion of a decoder over a nested item becomes
  * a loop over an explicit stack, so the depth it handles is bounded by memory (and so by the size
  * of its input: a level costs at least one byte) and not by the thread's stack. The reference
  * decoders recurse on a stack that grows on the heap, with no depth limit of their own.
  */
object TreeFold:
  /** One step: the node is a leaf with a result, or it has children and `build` combines their
    * results, in order.
    */
  enum Step[+T, A, +E]:
    case Leaf(result: A)
    case Branch(children: Iterable[T], build: Vector[A] => Either[E, A])

  /** An open node: the children still to visit and the results of those visited, the newest first.
    */
  private final case class Frame[T, A, E](
      remaining: List[T],
      results: List[A],
      build: Vector[A] => Either[E, A]
  )

  /** Where the walk is: a node to step, a result to give to the frame above, or a frame to go on
    * with (its next child, or its build once it has none).
    */
  private enum Position[T, A, E]:
    case Visit(node: T)
    case Deliver(result: A)
    case Advance(frame: Frame[T, A, E])

  /** The keys and values of a map in turn: `k0, v0, k1, v1, ...`. */
  def flatten[A](ps: Vector[(A, A)]): Vector[A] =
    ps.iterator.flatMap((k, v) => Iterator(k, v)).toVector

  /** The pairs `(ds(0), ds(1))`, `(ds(2), ds(3))`, ... of a flattened map. */
  def pairs[A](ds: Vector[A]): Vector[(A, A)] =
    Vector.tabulate(ds.size / 2)(i => (ds(2 * i), ds(2 * i + 1)))

  /** Fold the tree under `root`; the first error, in depth-first order, ends the fold. */
  def foldE[T, A, E](root: T)(step: T => Either[E, Step[T, A, E]]): Either[E, A] =
    import Position.*
    // `stack`: the open frames under the position, innermost first
    @tailrec def go(at: Position[T, A, E], stack: List[Frame[T, A, E]]): Either[E, A] = at match
      case Visit(node) =>
        step(node) match
          case Left(e)                         => Left(e)
          case Right(Step.Leaf(result))        => go(Deliver(result), stack)
          case Right(Step.Branch(kids, build)) => go(Advance(Frame(kids.toList, Nil, build)), stack)
      case Deliver(result) =>
        stack match
          case Nil          => Right(result)
          case top :: below => go(Advance(top.copy(results = result :: top.results)), below)
      case Advance(frame) =>
        frame.remaining match
          case child :: others => go(Visit(child), frame.copy(remaining = others) :: stack)
          case Nil =>
            frame.build(frame.results.reverse.toVector) match
              case Left(e)  => Left(e)
              case Right(a) => go(Deliver(a), stack)
    go(Visit(root), Nil)

  /** As [[foldE]] for a step that cannot fail. */
  def fold[T, A](root: T)(step: T => Step[T, A, Nothing]): A =
    foldE[T, A, Nothing](root)(t => Right(step(t))) match
      case Right(a) => a
