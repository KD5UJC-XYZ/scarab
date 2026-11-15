// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import org.wartremover.test.WartTestTraverser
import xyz.kd5ujc.scarab.warts.{FloatingPointInLedger, Rules}

/** A value with a floating-point field, as `codec.Value.Float` is. */
enum Number:
  case Whole(n: Int)
  case Floating(value: Double, width: Int)

// In the ledger package, as the wart reports only there. Each fixture is a local definition: the wart takes
// the package from the owner of a tree, which the test traverser sets to the fixture's own symbol.
class FloatingPointInLedgerSuite extends munit.FunSuite:

  private def kinds(r: WartTestTraverser.Result): List[String] =
    r.errors.map(_.stripPrefix("[wartremover:FloatingPointInLedger] ").takeWhile(_ != ':'))

  test("a declared type and each outermost floating-point term, each naming ARI-1"):
    val r = WartTestTraverser(FloatingPointInLedger) {
      def fixture =
        val x: Double = 1.0
        val y = x * 2
        (y, List(2.5f).sum)
      fixture
    }
    assertEquals(
      kinds(r),
      List("floating-type", "floating-term", "floating-type", "floating-term") ++
        List("floating-type", "floating-type", "floating-term", "floating-term")
    )
    assert(r.errors.forall(_.endsWith(Rules.Ari1)), r.errors)

  test("exact arithmetic is not a site"):
    val r = WartTestTraverser(FloatingPointInLedger) {
      def fixture =
        val a = BigDecimal(1) / BigDecimal(3)
        val b = 7L / 2L
        (a, b, BigInt(2).pow(10))
      fixture
    }
    assertEquals(r.errors, Nil)

  test("the binders of a pattern are not sites; what a case computes with them is"):
    val r = WartTestTraverser(FloatingPointInLedger) {
      def shape(n: Number): Int = n match
        case Number.Whole(k)       => k
        case Number.Floating(_, w) => w
      def twice(n: Number): Int = n match
        case Number.Floating(v, _) if v > 0 => (v * 2).toInt
        case _                              => 0
      (shape(Number.Whole(1)), twice(Number.Whole(1)))
    }
    // the guard's `v > 0` reads v (a term), and the body's `v * 2`
    assertEquals(kinds(r), List("floating-term", "floating-term"))

  test("a definition that carries the wart's name in @SuppressWarnings is skipped"):
    val r = WartTestTraverser(FloatingPointInLedger) {
      @SuppressWarnings(Array("xyz.kd5ujc.scarab.warts.FloatingPointInLedger"))
      def mirrored(x: Int): Double = x * 0.5
      def unmarked(x: Int): Long = (x * 0.5).toLong
      (mirrored(1), unmarked(1))
    }
    assertEquals(kinds(r), List("floating-term"))
