// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.codec

import scala.util.boundary

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

  /** An open node: the children still to visit and the results of those visited, in a typed buffer
    * that doubles (a level of deep nesting usually has one child).
    */
  private final class Frame[T, A, E](
      val children: Iterator[T],
      val build: Vector[A] => Either[E, A]
  ):
    private val results = new scala.collection.mutable.ArrayBuffer[A](2)
    def add(a: A): Unit = results += a
    def collected: Vector[A] = results.toVector

  /** The keys and values of a map in turn: `k0, v0, k1, v1, ...`. */
  def flatten[A](ps: Vector[(A, A)]): Vector[A] =
    val out = new scala.collection.mutable.ArrayBuffer[A](2 * ps.size)
    var i = 0
    while i < ps.size do
      out += ps(i)._1
      out += ps(i)._2
      i += 1
    Vector.from(out)

  /** The pairs `(ds(0), ds(1))`, `(ds(2), ds(3))`, ... of a flattened map. */
  def pairs[A](ds: Vector[A]): Vector[(A, A)] =
    val out = new Array[(A, A)](ds.size / 2)
    var i = 0
    while i < out.length do
      out(i) = (ds(2 * i), ds(2 * i + 1))
      i += 1
    Vector.from(scala.collection.immutable.ArraySeq.unsafeWrapArray(out))

  /** Fold the tree under `root`; the first error, in depth-first order, ends the fold. */
  def foldE[T, A, E](root: T)(step: T => Either[E, Step[T, A, E]]): Either[E, A] =
    boundary:
      val stack = new java.util.ArrayDeque[Frame[T, A, E]]
      var open: Option[T] = Some(root)
      var done: Option[A] = None
      while true do
        open.foreach { node =>
          step(node) match
            case Left(e)                         => boundary.break(Left(e))
            case Right(Step.Leaf(result))        => done = Some(result)
            case Right(Step.Branch(kids, build)) => stack.push(new Frame(kids.iterator, build))
        }
        open = None
        done.foreach { result =>
          if stack.isEmpty then boundary.break(Right(result))
          stack.peek().add(result)
        }
        done = None
        val top = stack.peek()
        if top.children.hasNext then open = Some(top.children.next())
        else
          stack.pop()
          top.build(top.collected) match
            case Left(e)  => boundary.break(Left(e))
            case Right(a) => done = Some(a)
      throw new IllegalStateException("unreachable")

  /** As [[foldE]] for a step that cannot fail. */
  def fold[T, A](root: T)(step: T => Step[T, A, Nothing]): A =
    foldE[T, A, Nothing](root)(t => Right(step(t))) match
      case Right(a) => a
