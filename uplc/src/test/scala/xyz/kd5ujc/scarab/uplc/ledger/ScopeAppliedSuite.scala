// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.ledger

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import xyz.kd5ujc.scarab.uplc.{DeBruijn, DefaultFun, Program, ProgramVersion, Term, Word64}
import xyz.kd5ujc.scarab.uplc.flat.FlatGen

/** The scope check a program keeps ([[Scope.applied]], #1499) against the check of the term
  * `mkTermToEvaluate` builds, the program's term applied to constants ([[Scope.check]]): the same
  * verdict and the same first free index, over open and closed terms, index 0, and variables under
  * `constr` and `case`, which the check does not enter.
  */
final class ScopeAppliedSuite extends munit.ScalaCheckSuite:
  override def scalaCheckTestParameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(2000)

  /** Terms whose variables are mostly near their binders, so that many are closed. */
  private def terms(depth: Int): Gen[Term] =
    val leaves = Gen.oneOf(
      Gen
        .frequency(6 -> Gen.choose(0L, 4L), 1 -> Gen.choose(5L, 300L), 1 -> Gen.long)
        .map(i => Term.variable(DeBruijn(Word64.fromBits(i)))),
      FlatGen.constants.map(Term.constant),
      Gen.oneOf(DefaultFun.values.toSeq).map(Term.builtin),
      Gen.const(Term.error)
    )
    if depth <= 0 then leaves
    else
      val sub = terms(depth - 1)
      Gen.frequency(
        1 -> leaves,
        3 -> sub.map(Term.lam),
        1 -> sub.map(Term.delay),
        1 -> sub.map(Term.force),
        2 -> Gen.zip(sub, sub).map(Term.app),
        1 -> Gen
          .zip(Gen.choose(0L, 3L), Gen.choose(0, 3).flatMap(Gen.listOfN(_, sub)))
          .map((t, fs) => Term.constr(Word64.fromBits(t), fs.toVector)),
        1 -> Gen
          .zip(sub, Gen.choose(0, 3).flatMap(Gen.listOfN(_, sub)))
          .map((s, bs) => Term.caseOf(s, bs.toVector))
      )

  property("the kept check is the check of the term applied to its arguments") {
    val cases =
      for
        term <- Gen.oneOf(terms(5), FlatGen.terms(4))
        args <- Gen.choose(0, 3).flatMap(Gen.listOfN(_, FlatGen.constants))
      yield (term, args)
    forAll(cases) { (term, args) =>
      val applied =
        args.foldLeft(term)((t, c) => Term.app(t, Term.constant(c)))
      val program = Program(ProgramVersion.V1_1_0, term)
      assertEquals(Scope.applied(program), Scope.check(applied), s"$term applied to $args")
      // asked again, the kept verdict is the same
      assertEquals(Scope.applied(program), Scope.check(applied))
    }
  }

  test("the generated terms hold both verdicts") {
    val verdicts = Gen
      .listOfN(500, terms(5))
      .apply(Gen.Parameters.default, org.scalacheck.rng.Seed(1499L))
      .getOrElse(Nil)
      .map(t => Scope.check(t).isRight)
    assert(verdicts.contains(true) && verdicts.contains(false), "closed and open terms")
  }
