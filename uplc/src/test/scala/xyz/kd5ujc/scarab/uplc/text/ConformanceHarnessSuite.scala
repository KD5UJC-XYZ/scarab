// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.text

import xyz.kd5ujc.scarab.uplc.{Program, ReferenceCorpus, Term}
import xyz.kd5ujc.scarab.uplc.ReferenceCorpus.{ConformanceCase, Expected}
import xyz.kd5ujc.scarab.uplc.cost.{ExBudget, SatInt}

/** The conformance harness against evaluators whose answers are known: one that returns what the
  * reference recorded conforms everywhere, and each kind of wrong answer is a mismatch named by its
  * case.
  */
final class ConformanceHarnessSuite extends munit.FunSuite:

  private lazy val cases: Vector[ConformanceCase] =
    ReferenceCorpus.conformance(ReferenceCorpus.Pinned).fold(p => fail(p.toString), identity)

  /** What the reference recorded for each closed program of the corpus. */
  private lazy val recorded: Map[Program, Outcome[Nothing]] =
    val pairs = cases.flatMap { c =>
      UplcParser.program(c.program).toOption.flatMap { p =>
        c.expected match
          case Expected.Evaluates(text, budget) =>
            UplcParser.program(text).toOption.map(r => p -> Outcome.Evaluated(r.term, budget))
          case Expected.EvaluationFailure => Some(p -> Outcome.Failed)
          case Expected.ParseError        => None
      }
    }
    val table = pairs.groupMap(_._1)(_._2)
    val conflicting = table.collect { case (p, outcomes) if outcomes.distinct.size > 1 => p }
    assert(conflicting.isEmpty, s"${conflicting.size} programs with two recorded outcomes")
    table.view.mapValues(_.head).toMap

  private def replay(change: Outcome[Nothing] => Outcome[Nothing]): Conformance.Evaluator[Nothing] =
    p => change(recorded.getOrElse(p, fail(s"no recorded outcome for ${UplcPrinter.program(p)}")))

  private val evaluated = 723
  private val failing = 222
  private val refused = 64

  test("an evaluator that answers what the reference recorded conforms on all 1,009 cases") {
    val report = Conformance.run(cases, replay(identity))
    assertEquals(report.render(_ => ""), "")
    assertEquals(report.conforming, 1009)
  }

  test("a budget one CPU unit short is a budget mismatch on each evaluated case, by name") {
    val report = Conformance.run(
      cases,
      replay {
        // one less, since five recorded budgets are saturated at maxBound (`dropList`)
        case Outcome.Evaluated(t, b) => Outcome.Evaluated(t, ExBudget(b.cpu - SatInt(1), b.memory))
        case other                   => other
      }
    )
    val budgets = report.mismatches.collect { case (name, _: Mismatch.Budget[?]) => name }
    assertEquals(budgets.size, evaluated)
    assertEquals(report.mismatches.size, evaluated)
    val first = cases.collectFirst { case ConformanceCase(n, _, _: Expected.Evaluates) => n }
    assert(
      first.exists(n => report.render(_ => "").linesIterator.exists(_.startsWith(s"$n: budget")))
    )
  }

  test("a wrong result is a result mismatch; a result where the reference fails is one too") {
    val report = Conformance.run(
      cases,
      replay {
        case Outcome.Evaluated(_, b) => Outcome.Evaluated(Term.error, b)
        case Outcome.Failed          => Outcome.Evaluated(Term.error, ExBudget.zero)
      }
    )
    val results = report.mismatches.count(_._2.isInstanceOf[Mismatch.Result[?]])
    val successes = report.mismatches.count(_._2.isInstanceOf[Mismatch.UnexpectedSuccess[?]])
    assertEquals(
      (results, successes, report.mismatches.size),
      (evaluated, failing, evaluated + failing)
    )
    assertEquals(report.conforming, refused)
  }

  test("a failing evaluator mismatches each evaluated case and conforms on the others") {
    val report = Conformance.run(cases, (_: Program) => Outcome.Failed)
    assertEquals(report.mismatches.map(_._2).distinct, Vector(Mismatch.UnexpectedFailure))
    assertEquals(report.mismatches.size, evaluated)
    assertEquals(report.conforming, failing + refused)
  }

  test("an evaluator that cannot evaluate is incomplete on every program it would evaluate") {
    val report = Conformance.run(cases, (_: Program) => Outcome.Unavailable("no machine"))
    assertEquals(report.mismatches.map(_._2).distinct, Vector(Mismatch.Unavailable("no machine")))
    assertEquals(report.mismatches.size, evaluated + failing)
    assert(report.render(identity).linesIterator.forall(_.endsWith("incomplete: no machine")))
  }

  test("a program read where the reference refuses it, and one refused where it reads it") {
    val readable =
      ConformanceCase("synthetic/read", "(program 1.0.0 (con integer 1))", Expected.ParseError)
    val open = ConformanceCase("synthetic/open", "(program 1.0.0 x)", Expected.EvaluationFailure)
    val never: Conformance.Evaluator[Nothing] = p => fail(s"evaluated ${UplcPrinter.program(p)}")
    assertEquals(Conformance.check(readable, never), List(Mismatch.Accepted))
    assertEquals(
      Conformance.check(open, never),
      List(Mismatch.Refused(ProgramError.Open(FreeVariable(Name("x", 0)))))
    )
  }
