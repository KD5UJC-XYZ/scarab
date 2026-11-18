// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc

import java.lang.management.ManagementFactory
import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
import xyz.kd5ujc.scarab.codec.{Bytes, TreeSuite}

/** `equalsData` is `PlutusData` 's equality, Haskell's derived `(==)` (`PlutusCore/Data.hs:48`,
  * `Default/Builtins.hs:1861-1866`, plutus 1.71.0.0): it stops at the first constructor that
  * differs, so comparing a large map with a small value reads nothing of the map's entries. The
  * cost of `equalsData` is charged on the smaller argument.
  */
class PlutusDataEqualitySuite extends ScalaCheckSuite:
  import PlutusData.*

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

  /** Far below what a copy of the entries of the map these tests build allocates (megabytes). */
  private val Small = 64 * 1024L

  private val large: PlutusData = Map(Vector.tabulate(100_000)(i => (I(i), I(0))))
  private val firstKeyDiffers: PlutusData =
    Map(Vector.tabulate(100_000)(i => (I(if i == 0 then -1 else i), I(0))))

  test("equalsData of a large map and a small value reads none of the map's entries") {
    val smaller = Vector(I(9), Map(Vector.empty), Map(Vector((I(9), I(9)))), List(Vector.empty))
    smaller.foreach { s =>
      val (forward, forwardBytes) = allocated(large == s)
      val (backward, backwardBytes) = allocated(s == large)
      assert(!forward && !backward, s"$s")
      assert(forwardBytes < Small, s"map against $s: $forwardBytes bytes")
      assert(backwardBytes < Small, s"$s against map: $backwardBytes bytes")
    }
  }

  test("equalsData of two large maps stops at their first entry that differs") {
    val (equal, bytes) = allocated(large == firstKeyDiffers)
    assert(!equal)
    assert(bytes < Small, s"$bytes bytes")
  }

  test("equalsData of a list holding a large map and one holding a leaf stops at the leaf") {
    val (equal, bytes) = allocated(List(Vector(large)) == List(Vector(I(9))))
    assert(!equal)
    assert(bytes < Small, s"$bytes bytes")
  }

  test("a large map equals its copy, and its hash is its copy's") {
    val copy = Map(Vector.tabulate(100_000)(i => (I(i), I(0))))
    assert(large == copy)
    assertEquals(large.hashCode, copy.hashCode)
  }

  /** Haskell's derived `(==)` on `Data`, clause by clause; it recurses, so only for small data. */
  private def derived(x: PlutusData, y: PlutusData): Boolean = (x, y) match
    case (Constr(i, fs), Constr(j, gs)) => i == j && list(fs, gs)
    case (Map(es), Map(fs)) =>
      es.size == fs.size && es.zip(fs).forall((a, b) => derived(a._1, b._1) && derived(a._2, b._2))
    case (List(xs), List(ys)) => list(xs, ys)
    case (I(a), I(b))         => a == b
    case (B(a), B(b))         => a == b
    case _                    => false
  private def list(xs: Vector[PlutusData], ys: Vector[PlutusData]): Boolean =
    xs.size == ys.size && xs.zip(ys).forall(derived)

  private def data(depth: Int): Gen[PlutusData] =
    val leaf = Gen.oneOf(
      Gen.choose(-2, 2).map(i => I(BigInt(i))),
      Gen.choose(0, 2).map(n => B(Bytes.wrap(Array.fill(n)(1.toByte))))
    )
    if depth <= 0 then leaf
    else
      val inner = data(depth - 1)
      Gen.frequency(
        3 -> leaf,
        1 -> Gen
          .zip(Gen.choose(0, 1), Gen.choose(0, 3).flatMap(Gen.listOfN(_, inner)))
          .map((t, fs) => Constr(BigInt(t), fs.toVector)),
        1 -> Gen
          .choose(0, 3)
          .flatMap(Gen.listOfN(_, Gen.zip(inner, inner)))
          .map(kvs => Map(kvs.toVector)),
        1 -> Gen.choose(0, 3).flatMap(Gen.listOfN(_, inner)).map(xs => List(xs.toVector))
      )

  /** A copy that shares no node with `d`. */
  private def rebuilt(d: PlutusData): PlutusData = d match
    case Constr(t, fs) => Constr(t, fs.map(rebuilt))
    case Map(kvs)      => Map(kvs.map((k, v) => (rebuilt(k), rebuilt(v))))
    case List(xs)      => List(xs.map(rebuilt))
    case I(i)          => I(i)
    case B(b)          => B(Bytes.wrap(b.toArray))

  private val pairs: Gen[(PlutusData, PlutusData)] =
    val one = Gen.choose(0, 3).flatMap(data)
    Gen.oneOf(Gen.zip(one, one), one.map(d => (d, rebuilt(d))))

  property("equalsData is Haskell's derived equality, and the walk it replaced") {
    Prop.forAll(pairs) { (x, y) =>
      val expected = derived(x, y)
      assertEquals(x == y, expected, s"$x against $y")
      assertEquals(TreeSuite.previousEqual(x, y), expected, s"previous walk: $x against $y")
    }
  }

  property("equal data hash alike, as the walk it replaced hashed them") {
    Prop.forAll(pairs) { (x, y) =>
      assertEquals(x.hashCode, TreeSuite.previousHash(x), s"$x")
      if x == y then assertEquals(x.hashCode, y.hashCode, s"$x and $y")
    }
  }
