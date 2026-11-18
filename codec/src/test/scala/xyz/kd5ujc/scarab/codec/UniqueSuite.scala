// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.codec

import java.util.concurrent.atomic.AtomicLong
import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}

/** [[Unique]] answers as `distinct`, `distinctBy` and a size comparison do, and its cost is that of
  * a sort when the elements share one hash code.
  */
class UniqueSuite extends ScalaCheckSuite:
  // few distinct values, so that repeats are common
  private val small: Gen[Bytes] =
    Gen
      .choose(0, 3)
      .flatMap(n => Gen.listOfN(n, Gen.choose(0, 2).map(_.toByte)))
      .map(b => Bytes(b.toVector))
  private val smalls: Gen[Vector[Bytes]] = Gen.listOf(small).map(_.toVector)

  property("repeats is a size comparison of distinct") {
    Prop.forAll(smalls)(xs => assertEquals(Unique.repeats(xs), xs.distinct.sizeIs != xs.size))
  }

  property("firsts is distinct, in the order of the input") {
    Prop.forAll(smalls)(xs => assertEquals(Unique.firsts(xs), xs.distinct))
  }

  property("firstsBy is distinctBy") {
    Prop.forAll(Gen.listOf(Gen.zip(small, Gen.choose(0, 9))).map(_.toVector)) { xs =>
      assertEquals(Unique.firstsBy(xs)(_._1), xs.distinctBy(_._1))
      assertEquals(Unique.firstsBy(xs)(_._2), xs.distinctBy(_._2))
    }
  }

  test("the colliding strings are distinct and share one hash code") {
    val strings = CollidingBytes.of(20000, 7)
    assertEquals(strings.distinct.size, 20000)
    assertEquals(strings.map(_.hashCode).distinct, Vector(strings.head.hashCode))
    val prefixed = strings.map(Bytes(Vector.fill(21)(7.toByte)) ++ _)
    assertEquals(prefixed.map(_.hashCode).distinct.size, 1)
  }

  // Comparisons counted through the order: a sort of n makes at most about n log2 n of them, where
  // `distinct` over strings of one hash code compares each with every one before it.
  test("over strings of one hash code each makes the comparisons of a sort") {
    val n = 20000
    val strings = CollidingBytes.of(n, 7)
    val compared = AtomicLong(0)
    given Ordering[Bytes] = (a, b) =>
      compared.incrementAndGet()
      a.compareTo(b)
    val repeated = strings ++ strings.take(10)
    assertEquals(Unique.repeats(strings), false)
    assertEquals(Unique.repeats(repeated), true)
    assertEquals(Unique.firsts(repeated), strings)
    // each call: a merge sort's n log2 n comparisons, twice over for slack, and a pass of n
    val m = repeated.size.toLong
    val perCall = 2 * m * (32 - Integer.numberOfLeadingZeros(repeated.size)) + 2 * m
    assert(compared.get <= 3 * perCall, s"${compared.get} comparisons, more than ${3 * perCall}")
  }
