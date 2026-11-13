// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc

import cats.Eq
import cats.laws.discipline.TraverseTests
import munit.DisciplineSuite
import org.scalacheck.{Arbitrary, Gen}

/** `Traverse[TermF]` is lawful, and visits subterms in the order of the Flat encoding. */
final class TermFLawsSuite extends DisciplineSuite:
  import TermFLawsSuite.given

  checkAll("TermF", TraverseTests[TermF].traverse[Int, Int, Int, Int, Option, Option])

  test("subterms in Flat order: function before argument, scrutinee before branches") {
    def children(t: TermF[Int]): List[Int] = cats.Traverse[TermF].toList(t)
    assertEquals(children(TermF.Apply(1, 2)), List(1, 2))
    assertEquals(children(TermF.Case(0, Vector(1, 2, 3))), List(0, 1, 2, 3))
    assertEquals(children(TermF.Constr(Word64.Zero, Vector(4, 5))), List(4, 5))
    assertEquals(children(TermF.Error), Nil)
  }

object TermFLawsSuite:
  private val constants: Gen[Constant] = Gen.oneOf(
    Gen.long.map(n => Constant.Integer(BigInt(n))),
    Gen.oneOf(true, false).map(Constant.Bool(_)),
    Gen.const(Constant.Unit)
  )

  given [A](using a: Arbitrary[A]): Arbitrary[TermF[A]] = Arbitrary(
    Gen.oneOf(
      Gen.choose(0, 20).map(i => TermF.Var(DeBruijn(Word64.fromBits(i.toLong)))),
      a.arbitrary.map(TermF.LamAbs(_)),
      Gen.zip(a.arbitrary, a.arbitrary).map(TermF.Apply(_, _)),
      a.arbitrary.map(TermF.Force(_)),
      a.arbitrary.map(TermF.Delay(_)),
      constants.map(TermF.Constant(_)),
      Gen.oneOf(DefaultFun.values.toSeq).map(TermF.Builtin(_)),
      Gen.const(TermF.Error),
      Gen
        .zip(Gen.long, Gen.choose(0, 4).flatMap(Gen.listOfN(_, a.arbitrary)))
        .map((t, xs) => TermF.Constr(Word64.fromBits(t), xs.toVector)),
      Gen
        .zip(a.arbitrary, Gen.choose(0, 3).flatMap(Gen.listOfN(_, a.arbitrary)))
        .map((s, xs) => TermF.Case(s, xs.toVector))
    )
  )

  given [A]: Eq[TermF[A]] = Eq.fromUniversalEquals
