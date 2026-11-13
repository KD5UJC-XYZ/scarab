// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.cek

import scala.annotation.tailrec

/** The environment of the machine: a skew-binary random-access list, the reference's
  * `Data.RandomAccessList.SkewBinary`
  * (`plutus-core/index-envs/src/Data/RandomAccessList/SkewBinary.hs`, plutus 1.70.0.0 `507cab8c`),
  * which `CekValEnv` is (`Cek/Internal.hs:322`). A list of complete binary trees in non-decreasing
  * order of size: `cons` is constant time (`SkewBinary.hs:70-77`), an index is found in logarithmic
  * time (`contIndexZero`, `SkewBinary.hs:104-126`).
  *
  * A lookup returns the tree whose root is the element, or [[Env.Absent]], so that it allocates
  * nothing on the machine's hottest path.
  */
sealed abstract class Env[+A]:
  /** `cons` (`SkewBinary.hs:70-77`): two leading trees of one size become the children of a new
    * root.
    */
  def cons[B >: A](x: B): Env[B] = this match
    case Env.Trees(w1, t1, Env.Trees(w2, t2, rest)) if w1 == w2 =>
      Env.Trees((w1 << 1) | 1L, Env.Node(x, t1, t2), rest)
    case _ => Env.Trees(1L, Env.Leaf(x), this)

  /** `contIndexOne` (`SkewBinary.hs:128-130`): the element at the 1-based `index`, which is read as
    * an unsigned 64-bit word; index 0 and an index past the end are absent.
    */
  def indexOne(index: Long): Env.Lookup[A] =
    // A word of 2^63 or more is a negative `Long` and longer than any list.
    if index <= 0L then Env.Absent else Env.indexZero(this, index - 1L)

  /** The number of elements (`length`, `SkewBinary.hs:165-168`). */
  def length: Long = Env.length(this, 0L)

object Env:
  private val emptyEnv: Env[Nothing] = Empty
  def empty[A]: Env[A] = emptyEnv

  /** The result of a lookup: the subtree whose root is the element, or none. */
  sealed abstract class Lookup[+A]

  case object Absent extends Lookup[Nothing]

  /** A complete binary tree; its size is kept by the spine (`SkewBinary.hs:32-34`). */
  sealed abstract class Tree[+A] extends Lookup[A]:
    def value: A

  final case class Node[+A](value: A, left: Tree[A], right: Tree[A]) extends Tree[A]
  final case class Leaf[+A](value: A) extends Tree[A]

  private case object Empty extends Env[Nothing]

  /** `BHead`: a tree of `width` elements before the rest of the list. */
  private final case class Trees[+A](width: Long, tree: Tree[A], rest: Env[A]) extends Env[A]

  /** `findTree` (`SkewBinary.hs:109-113`); `index` is not negative. */
  @tailrec private def indexZero[A](env: Env[A], index: Long): Lookup[A] = env match
    case Empty                       => Absent
    case Trees(w, t, _) if index < w => indexTree(w, index, t)
    case Trees(w, _, rest)           => indexZero(rest, index - w)

  /** `indexTree` (`SkewBinary.hs:117-125`). */
  @tailrec private def indexTree[A](size: Long, offset: Long, tree: Tree[A]): Lookup[A] =
    tree match
      case node: Node[A] if offset == 0L => node
      case leaf: Leaf[A]                 => if offset == 0L && size == 1L then leaf else Absent
      case Node(_, left, right) =>
        val half = size >>> 1
        if offset <= half then indexTree(half, offset - 1L, left)
        else indexTree(half, offset - 1L - half, right)

  @tailrec private def length[A](env: Env[A], acc: Long): Long = env match
    case Empty             => acc
    case Trees(w, _, rest) => length(rest, acc + w)
