// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.codec

import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.AtomicInteger
import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
import scala.annotation.tailrec

/** [[Tree.equal]] and [[Tree.hash]]: what they read of a node, and that they agree with the walk
  * they replaced, which read every child of a node before it compared the labels.
  */
class TreeSuite extends ScalaCheckSuite:
  import TreeSuite.*

  /** What `body` allocates the second time it runs: the first loads and initialises classes. */
  private def allocated[A](body: => A): (A, Long) =
    body
    val meter = ManagementFactory.getThreadMXBean match
      case bean: com.sun.management.ThreadMXBean
          if bean.isThreadAllocatedMemorySupported && bean.isThreadAllocatedMemoryEnabled =>
        bean
      case _ => fail("allocation counters unavailable")
    val thread = Thread.currentThread().threadId()
    val before = meter.getThreadAllocatedBytes(thread)
    val result = body
    val after = meter.getThreadAllocatedBytes(thread)
    assert(before >= 0 && after >= before, s"the allocation counter read $before, then $after")
    (result, after - before)

  /** Far below what a copy of the children of the nodes these tests build allocates (megabytes). */
  private val Small = 64 * 1024L

  private val wide = Vector.tabulate(100_000)(i => (Item.Leaf(i), Item.Leaf(-i)))

  test("equal reads no children of two nodes whose labels differ") {
    val counting = Counting(summon[Tree[Item]])
    val equal =
      Tree.equal(Item.Branch(1, Vector.fill(10)(Item.Leaf(0))), Item.Leaf(9))(using counting)
    assert(!equal)
    assertEquals(counting.childrenCalls.get, 0, "children asked for")
    assertEquals(counting.reads.get, 0, "children read")
  }

  test("equal reads no child of two nodes whose child counts differ") {
    val counting = Counting(summon[Tree[Item]])
    val equal = Tree.equal(
      Item.Branch(1, Vector.fill(1000)(Item.Leaf(0))),
      Item.Branch(1, Vector.fill(999)(Item.Leaf(0)))
    )(using counting)
    assert(!equal)
    assertEquals(counting.reads.get, 0, "children read")
  }

  test("equal stops at the first pair of children that differ") {
    val counting = Counting(summon[Tree[Item]])
    val kids = Vector.fill(1000)(Item.Leaf(0))
    val equal =
      Tree.equal(Item.Branch(1, Item.Leaf(1) +: kids), Item.Branch(1, Item.Leaf(2) +: kids))(using
        counting
      )
    assert(!equal)
    assertEquals(counting.reads.get, 2, "children read: the first of each node")
  }

  test("a map's children are a view: flatten copies none of its entries") {
    val (children, bytes) = allocated(TreeFold.flatten(wide))
    assertEquals(children.size, 2 * wide.size)
    assert(bytes < Small, s"flatten allocated $bytes bytes")
  }

  test("comparing a large map with a leaf or a differing map allocates almost nothing") {
    val map = Item.Pairs(wide)
    val (againstLeaf, leafBytes) = allocated(Tree.equal(map, Item.Leaf(9)))
    val (againstEmpty, emptyBytes) = allocated(Tree.equal(map, Item.Pairs(Vector.empty)))
    val other = Item.Pairs(wide.updated(0, (Item.Leaf(7), Item.Leaf(0))))
    val (againstOther, otherBytes) = allocated(Tree.equal(map, other))
    assert(!againstLeaf && !againstEmpty && !againstOther)
    assert(leafBytes < Small, s"against a leaf: $leafBytes bytes")
    assert(emptyBytes < Small, s"against an empty map: $emptyBytes bytes")
    assert(otherBytes < Small, s"against a map whose first key differs: $otherBytes bytes")
  }

  test("flatten is the keys and values in turn, and refuses an index out of bounds") {
    val ps = Vector((Item.Leaf(1), Item.Leaf(2)), (Item.Leaf(3), Item.Leaf(4)))
    val flat = TreeFold.flatten(ps)
    assertEquals(flat.toVector, Vector(1, 2, 3, 4).map(Item.Leaf(_)))
    assertEquals(flat.lift(-1), None)
    assertEquals(flat.lift(4), None)
    intercept[IndexOutOfBoundsException](flat(-1))
    intercept[IndexOutOfBoundsException](flat(4))
  }

  property("equal agrees with the walk it replaced") {
    Prop.forAll(pairs) { (x, y) =>
      val expected = previousEqual(x, y)
      assertEquals(Tree.equal(x, y), expected, s"$x against $y")
      assertEquals(Tree.equal(y, x), expected, s"$y against $x")
    }
  }

  property("hash is the hash of the walk it replaced") {
    Prop.forAll(items) { x =>
      assertEquals(Tree.hash(x), previousHash(x), x.toString)
    }
  }

  property("equal items hash alike") {
    Prop.forAll(items) { x =>
      val copy = rebuilt(x)
      assert(Tree.equal(x, copy), s"$x and its copy")
      assertEquals(Tree.hash(x), Tree.hash(copy), x.toString)
    }
  }

object TreeSuite:
  enum Item:
    case Leaf(n: Int)
    case Branch(label: Int, kids: Vector[Item])
    case Pairs(kvs: Vector[(Item, Item)])

  given Tree[Item] with
    def children(a: Item): IndexedSeq[Item] = a match
      case Item.Leaf(_)         => Vector.empty
      case Item.Branch(_, kids) => kids
      case Item.Pairs(kvs)      => TreeFold.flatten(kvs)
    def sameLabel(x: Item, y: Item): Boolean = (x, y) match
      case (Item.Leaf(a), Item.Leaf(b))           => a == b
      case (Item.Branch(a, _), Item.Branch(b, _)) => a == b
      case (Item.Pairs(_), Item.Pairs(_))         => true
      case _                                      => false
    def labelHash(a: Item): Int = a match
      case Item.Leaf(n)      => n
      case Item.Branch(l, _) => 1000 + l
      case Item.Pairs(_)     => 7
    def name(a: Item): String = a.toString

  /** A [[Tree]] that counts the calls of `children` and the children read from what it gives. */
  final class Counting[A <: AnyRef](inner: Tree[A]) extends Tree[A]:
    val childrenCalls = new AtomicInteger
    val reads = new AtomicInteger
    def children(a: A): IndexedSeq[A] =
      childrenCalls.incrementAndGet()
      val kids = inner.children(a)
      new scala.collection.immutable.AbstractSeq[A] with IndexedSeq[A]:
        def length: Int = kids.length
        def apply(i: Int): A =
          reads.incrementAndGet()
          kids(i)
    def sameLabel(x: A, y: A): Boolean = inner.sameLabel(x, y)
    def labelHash(a: A): Int = inner.labelHash(a)
    def name(a: A): String = inner.name(a)

  /** The walk [[Tree.equal]] replaced: both nodes' children listed before the labels compared. */
  def previousEqual[A <: AnyRef](x: A, y: A)(using t: Tree[A]): Boolean =
    @tailrec def go(pending: List[(A, A)]): Boolean = pending match
      case Nil                      => true
      case (a, b) :: rest if a eq b => go(rest)
      case (a, b) :: rest =>
        val (as, bs) = (t.children(a).toVector, t.children(b).toVector)
        if as.sizeIs != bs.size || !t.sameLabel(a, b) then false
        else go(as.iterator.zip(bs.iterator).toList ::: rest)
    go(List((x, y)))

  /** The walk [[Tree.hash]] replaced. */
  def previousHash[A <: AnyRef](x: A)(using t: Tree[A]): Int =
    @tailrec def go(pending: List[A], h: Int): Int = pending match
      case Nil => h
      case a :: rest =>
        val kids = t.children(a).toVector
        go(kids.toList ::: rest, 31 * (31 * h + t.labelHash(a)) + kids.size)
    go(List(x), 17)

  /** A copy that shares no node with `x`. */
  def rebuilt(x: Item): Item = x match
    case Item.Leaf(n)         => Item.Leaf(n)
    case Item.Branch(l, kids) => Item.Branch(l, kids.map(rebuilt))
    case Item.Pairs(kvs)      => Item.Pairs(kvs.map((k, v) => (rebuilt(k), rebuilt(v))))

  private def item(depth: Int): Gen[Item] =
    val leaf = Gen.choose(0, 3).map(Item.Leaf(_))
    if depth <= 0 then leaf
    else
      val inner = item(depth - 1)
      Gen.frequency(
        2 -> leaf,
        1 -> Gen
          .zip(Gen.choose(0, 2), Gen.choose(0, 3).flatMap(Gen.listOfN(_, inner)))
          .map((l, ks) => Item.Branch(l, ks.toVector)),
        1 -> Gen
          .choose(0, 3)
          .flatMap(Gen.listOfN(_, Gen.zip(inner, inner)))
          .map(kvs => Item.Pairs(kvs.toVector))
      )

  val items: Gen[Item] = Gen.choose(0, 4).flatMap(item)

  /** Pairs that are often equal or nearly so: an item and its copy with one leaf changed. */
  private def changed(x: Item, seed: Int): Item = x match
    case Item.Leaf(n)                         => Item.Leaf(n + 1 + seed % 2)
    case Item.Branch(l, kids) if kids.isEmpty => Item.Branch(l + 1, kids)
    case Item.Branch(l, kids) =>
      val i = seed % kids.size
      Item.Branch(l, kids.updated(i, changed(kids(i), seed / 2)))
    case Item.Pairs(kvs) if kvs.isEmpty => Item.Pairs(Vector((Item.Leaf(0), Item.Leaf(0))))
    case Item.Pairs(kvs) =>
      val i = seed % kvs.size
      val (k, v) = kvs(i)
      Item.Pairs(
        kvs.updated(
          i,
          if seed % 2 == 0 then (changed(k, seed / 2), v) else (k, changed(v, seed / 2))
        )
      )

  val pairs: Gen[(Item, Item)] = Gen.oneOf(
    Gen.zip(items, items),
    items.map(x => (x, rebuilt(x))),
    Gen.zip(items, Gen.choose(0, 1000)).map((x, s) => (x, changed(x, s)))
  )
