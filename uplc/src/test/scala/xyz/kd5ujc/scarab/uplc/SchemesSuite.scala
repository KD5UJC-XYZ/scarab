// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc

import cats.data.State
import cats.syntax.all.*

/** The recursion schemes agree with their plain recursive definitions, run their effects in the
  * same order, and run in constant stack on a term two million levels deep.
  */
final class SchemesSuite extends munit.FunSuite:

  private def v(i: Int): Term = Term.variable(DeBruijn(Word64.fromBits(i.toLong)))

  private val small: Term =
    Term.lam(
      Term.app(
        Term.force(Term.delay(v(1))),
        Term.caseOf(
          Term.constr(Word64.Zero, Vector(v(1), Term.constant(Constant.Integer(7)))),
          Vector(Term.builtin(DefaultFun.AddInteger), Term.error)
        )
      )
    )

  // The plain recursive fold, for comparison on small terms only.
  private def naiveCata[A](t: Term)(alg: TermF[A] => A): A = alg(t.unfix.map(naiveCata(_)(alg)))

  private val size: TermF[Int] => Int = _.foldLeft(1)(_ + _)

  test("cata agrees with the recursive fold, and cata(Fix) is the identity") {
    assertEquals(Schemes.cata(small)(size), naiveCata(small)(size))
    assertEquals(Schemes.cata(small)(size), 11)
    assertEquals(Schemes.cata[TermF, Term](small)(Fix(_)), small)
  }

  test("ana of the projection rebuilds the term; hylo is cata after ana") {
    assertEquals(Schemes.ana[TermF, Term](small)(_.unfix), small)
    assertEquals(Schemes.hylo[TermF, Term, Int](small)(_.unfix, size), 11)
  }

  test(
    "the coalgebra runs in pre-order and the algebra in post-order, interleaved as in recursion"
  ) {
    // Record each call in State: the coalgebra's seed as "u<label>", the algebra's as "f<label>".
    def label(t: Term): String = t.unfix match
      case TermF.Var(i)      => s"v${i.index.show}"
      case TermF.Constant(_) => "k"
      case TermF.Builtin(_)  => "b"
      case TermF.Error       => "e"
      case other             => other.void.toString.takeWhile(_ != '(')
    def naive(t: Term): State[Vector[String], Unit] =
      State.modify[Vector[String]](_ :+ s"u${label(t)}") *>
        t.unfix.traverse(naive) *>
        State.modify[Vector[String]](_ :+ s"f${label(t)}")
    val machine = Schemes.hyloM[TermF, [x] =>> State[Vector[String], x], Term, String](small)(
      t => State.modify[Vector[String]](_ :+ s"u${label(t)}").as(t.unfix),
      layer =>
        State.modify[Vector[String]](_ :+ s"f${label(Fix(layer.map(_ => Term.error)))}").as("")
    )
    val expected = naive(small).runS(Vector.empty).value
    assertEquals(machine.runS(Vector.empty).value, expected)
    assertEquals(expected.take(4), Vector("uLamAbs", "uApply", "uForce", "uDelay"))
  }

  test("cataM stops at the algebra's first failure, in post-order") {
    val firstBuiltin = Schemes.cataM[TermF, [x] =>> Either[DefaultFun, x], Unit](small) {
      case TermF.Builtin(f) => Left(f)
      case _                => Right(())
    }
    assertEquals(firstBuiltin, Left(DefaultFun.AddInteger))
  }

  // A chain mostly of one-child layers, with a two-child layer every 1,000 levels, from seed `n`
  // down to 0; a negative seed is a leaf.
  private def spine(n: Int): TermF[Int] =
    if n < 0 then TermF.Var(DeBruijn(Word64.fromBits(1L)))
    else if n == 0 then TermF.Error
    else if n % 1000 == 0 then TermF.Apply(-1, n - 1)
    else if n % 1000 == 1 then TermF.Case(n - 1, Vector(-1))
    else if n % 3 == 0 then TermF.LamAbs(n - 1)
    else if n % 3 == 1 then TermF.Delay(n - 1)
    else TermF.Force(n - 1)

  private val Depth = 2000000

  test(
    "two million levels: ana, cata, cataM, anaM, equality, hash and rendering on a small stack"
  ) {
    SmallStack.run {
      val deep = Schemes.ana[TermF, Int](Depth)(spine)
      val depth = Schemes.cata[TermF, Int](deep)(_.foldLeft(0)(_ max _) + 1)
      assertEquals(depth, Depth + 1)
      val leaves = Schemes.cataM[TermF, [x] =>> Either[DefaultFun, x], Int](deep) {
        case TermF.Var(_) | TermF.Error => Right(1)
        case layer                      => Right(layer.foldLeft(0)(_ + _))
      }
      assertEquals(leaves, Right(1 + 2 * (Depth / 1000)))
      // The unfold with the effects of a cursor: State counts the coalgebra's calls.
      val (calls, again) =
        Schemes
          .anaM[TermF, [x] =>> State[Int, x], Int](Depth)(n =>
            State.modify[Int](_ + 1).as(spine(n))
          )
          .run(0)
          .value
      assertEquals(calls, Depth + 1 + 2 * (Depth / 1000))
      val same = deep == again
      assert(same, "two unfolds of one seed are equal")
      assertEquals(deep.hashCode, again.hashCode)
      val other = Schemes.ana[TermF, Int](Depth)(n =>
        if n == 0 then TermF.Var(DeBruijn(Word64.fromBits(2L))) else spine(n)
      )
      val differ = deep == other
      assert(!differ, "terms that differ at the bottom are not equal")
      assert(deep.toString.length <= 1024 + 3, "the rendering is truncated")
    }
  }
