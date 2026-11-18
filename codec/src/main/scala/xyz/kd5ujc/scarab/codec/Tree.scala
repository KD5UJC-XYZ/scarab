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
  /** The children, in order; a map's keys and values alternate. [[equal]] and [[hash]] read them by
    * index, and [[equal]] only once the labels agree, so a view that copies nothing (as
    * [[TreeFold.flatten]] is) keeps a comparison that stops early from paying for the whole node.
    */
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

  /** Whether `x` and `y` are equal: their labels and child counts first, and then their children in
    * pre-order, so that a walk stops at the first difference without reading any more children, as
    * Haskell's derived `(==)` stops at the first constructor that differs.
    */
  def equal[A <: AnyRef](x: A, y: A)(using t: Tree[A]): Boolean =
    // a level still open: the children of two nodes that agree so far, from the pair at `at`
    final case class Level(as: IndexedSeq[A], bs: IndexedSeq[A], at: Int)
    // the pairs of `as` and `bs` from `at` on, then the levels in `above`, innermost first
    @tailrec def go(as: IndexedSeq[A], bs: IndexedSeq[A], at: Int, above: List[Level]): Boolean =
      if at >= as.size then
        above match
          case Nil                         => true
          case Level(cs, ds, next) :: rest => go(cs, ds, next, rest)
      else
        val a = as(at)
        val b = bs(at)
        if a eq b then go(as, bs, at + 1, above)
        else if !t.sameLabel(a, b) then false
        else
          val cs = t.children(a)
          val ds = t.children(b)
          if cs.sizeIs != ds.size then false
          else if cs.isEmpty then go(as, bs, at + 1, above)
          else go(cs, ds, 0, Level(as, bs, at + 1) :: above)
    go(Vector(x), Vector(y), 0, Nil)

  /** The hash of the labels and child counts in pre-order. */
  def hash[A <: AnyRef](x: A)(using t: Tree[A]): Int =
    // the nodes of `level` from `at` on, then the levels still open in `above`, innermost first
    @tailrec def go(level: IndexedSeq[A], at: Int, above: List[(IndexedSeq[A], Int)], h: Int): Int =
      if at >= level.size then
        above match
          case Nil                  => h
          case (open, next) :: rest => go(open, next, rest, h)
      else
        val a = level(at)
        val kids = t.children(a)
        val hashed = 31 * (31 * h + t.labelHash(a)) + kids.size
        if kids.isEmpty then go(level, at + 1, above, hashed)
        else go(kids, 0, (level, at + 1) :: above, hashed)
    go(Vector(x), 0, Nil, 17)

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
