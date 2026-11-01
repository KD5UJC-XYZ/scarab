// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.codec

import scala.annotation.tailrec

/** A recursive value seen as a tree: what the node itself says (its label) and its children, in
  * order. Structural equality, hashing and rendering over a `Tree` walk it with a stack on the
  * heap, so a value nested as deep as a block can hold (a level takes a byte) can be compared,
  * hashed and printed on a default thread stack. The compiler-derived versions of these recurse
  * once per level and overflow the stack at a few thousand levels.
  */
trait Tree[A <: AnyRef]:
  /** The children, in order; a map's keys and values alternate. */
  def children(a: A): IndexedSeq[A]

  /** Whether `x` and `y` agree on everything but their children (the number of children is compared
    * by the walk).
    */
  def sameLabel(x: A, y: A): Boolean

  /** A hash of the label alone, consistent with [[sameLabel]]. */
  def labelHash(a: A): Int

  /** A short rendering of the label alone: `Constr(1)`, `UInt(5)`, `B(00ff...(70 bytes))`. */
  def name(a: A): String

object Tree:
  /** The characters [[render]] keeps by default. A rendering is for logs and error messages and
    * truncated on purpose: a value can be megabytes, and `toString` must not allocate that.
    */
  val DefaultRenderLimit = 1024

  /** Longest run of a byte string or text a label shows. */
  val NameLimit = 32

  def equal[A <: AnyRef](x: A, y: A)(using t: Tree[A]): Boolean =
    // the pairs still to compare, in pre-order: a pair's children come before its later siblings
    @tailrec def go(pending: List[(A, A)]): Boolean = pending match
      case Nil                      => true
      case (a, b) :: rest if a eq b => go(rest)
      case (a, b) :: rest =>
        val (as, bs) = (t.children(a), t.children(b))
        if as.size != bs.size || !t.sameLabel(a, b) then false
        else go(as.iterator.zip(bs.iterator).toList ::: rest)
    go(List((x, y)))

  /** The hash of the labels and child counts in pre-order. */
  def hash[A <: AnyRef](x: A)(using t: Tree[A]): Int =
    @tailrec def go(pending: List[A], h: Int): Int = pending match
      case Nil => h
      case a :: rest =>
        val kids = t.children(a)
        go(kids.toList ::: rest, 31 * (31 * h + t.labelHash(a)) + kids.size)
    go(List(x), 17)

  /** `name[child, child, ...]` for the first `limit` characters, then `...`. */
  def render[A <: AnyRef](x: A, limit: Int = DefaultRenderLimit)(using t: Tree[A]): String =
    // an open level: the children still to render (an iterator, so that a value of a million
    // children is not listed to show its first few) and whether the next is the level's first
    final case class Open(children: Iterator[A], first: Boolean)
    val out = new java.lang.StringBuilder
    def enter(a: A, stack: List[Open]): List[Open] =
      out.append(t.name(a))
      val kids = t.children(a)
      if kids.nonEmpty then
        out.append('[')
        Open(kids.iterator, true) :: stack
      else stack
    // the levels still open when the limit is passed, none when the whole value was written
    @tailrec def go(stack: List[Open]): List[Open] = stack match
      case Open(children, first) :: below if out.length <= limit =>
        if children.hasNext then
          if !first then out.append(", ")
          go(enter(children.next(), Open(children, false) :: below))
        else
          out.append(']')
          go(below)
      case _ => stack
    if go(enter(x, Nil)).isEmpty && out.length <= limit then out.toString
    else out.substring(0, limit) + "..."

  extension [A <: AnyRef](a: A)(using Tree[A])
    def treeEquals(other: A): Boolean = equal(a, other)
    def treeHash: Int = hash(a)
    def treeString: String = render(a)

  /** A byte string as a label shows it: whole when short, else its start and its length. */
  def shown(b: Bytes): String =
    if b.size <= NameLimit then b.hex
    else s"${b.slice(0, NameLimit).hex}...(${b.size} bytes)"

  def shown(s: String): String =
    if s.length <= NameLimit then s"\"$s\"" else s"\"${s.take(NameLimit)}...\"(${s.length} chars)"
