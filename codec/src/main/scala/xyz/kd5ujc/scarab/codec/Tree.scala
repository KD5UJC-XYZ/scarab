// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.codec

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
    val stack = new java.util.ArrayDeque[(A, A)]
    stack.push((x, y))
    var same = true
    while same && !stack.isEmpty do
      val (a, b) = stack.pop()
      if a ne b then
        val (as, bs) = (t.children(a), t.children(b))
        if as.size != bs.size || !t.sameLabel(a, b) then same = false
        else
          var i = as.size - 1
          while i >= 0 do
            stack.push((as(i), bs(i)))
            i -= 1
    same

  /** The hash of the labels and child counts in pre-order. */
  def hash[A <: AnyRef](x: A)(using t: Tree[A]): Int =
    val stack = new java.util.ArrayDeque[A]
    stack.push(x)
    var h = 17
    while !stack.isEmpty do
      val a = stack.pop()
      val kids = t.children(a)
      h = 31 * (31 * h + t.labelHash(a)) + kids.size
      var i = kids.size - 1
      while i >= 0 do
        stack.push(kids(i))
        i -= 1
    h

  /** `name[child, child, ...]` for the first `limit` characters, then `...`. */
  def render[A <: AnyRef](x: A, limit: Int = DefaultRenderLimit)(using t: Tree[A]): String =
    final class Open(val children: Iterator[A]):
      var first = true
    val out = new java.lang.StringBuilder
    val stack = new java.util.ArrayDeque[Open]
    def enter(a: A): Unit =
      out.append(t.name(a))
      val kids = t.children(a)
      if kids.nonEmpty then
        out.append('[')
        stack.push(Open(kids.iterator))
    enter(x)
    while !stack.isEmpty && out.length <= limit do
      val top = stack.peek()
      if top.children.hasNext then
        if !top.first then out.append(", ")
        top.first = false
        enter(top.children.next())
      else
        out.append(']')
        stack.pop()
    if stack.isEmpty && out.length <= limit then out.toString
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
