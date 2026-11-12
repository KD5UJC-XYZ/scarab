// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.cost

import cats.syntax.all.*
import xyz.kd5ujc.scarab.uplc.{
  DefaultFun,
  Program,
  ReferenceCorpus,
  Schemes,
  SemanticsVariant,
  TermF
}
import xyz.kd5ujc.scarab.uplc.builtin.{
  Argument,
  BuiltinFailure,
  Builtins,
  Denotation,
  Denotations,
  EvaluationFailure,
  MachineValue,
  Outcome
}
import xyz.kd5ujc.scarab.uplc.builtin.core.{CoreDenotations, CoreUnlifting, CoreVariant}
import xyz.kd5ujc.scarab.uplc.cek.{
  BudgetMode,
  CaserBuiltin,
  CekMachine,
  CekResult,
  MachineParameters
}
import xyz.kd5ujc.scarab.uplc.text.{Conformance, Evaluation, Mismatch, Report}

/** The CEK machine with the core builtins and this cost model, in the reference conformance
  * runner's profile (`PlutusConformance/CekEvaluator.hs:46-61`, plutus 1.70.0.0): variant E with
  * its default parameters (`defaultCostModelParamsForTesting`, `ExBudgetingDefaults.hs:281-282`),
  * the counting budget and the caser with `data` (`CaserBuiltin.WithData`). A program that names a
  * builtin with no denotation yet is [[Evaluation.Unavailable]] with those builtins.
  */
object CekConformance:
  private val variant = SemanticsVariant.E

  private lazy val costs: VariantCosts = DefaultCostModels.costs(variant)

  private lazy val meanings: Map[DefaultFun, Denotation] =
    CoreDenotations.of(variant).fold(e => throw new IllegalStateException(e.toString), identity)

  // Every program is checked for builtins without a denotation before it runs, so this one is never
  // applied; it fails as a builtin of the program would.
  private val missing: Denotation = new Denotation:
    def apply[V](arguments: Vector[Argument[V]])(using MachineValue[V]): Outcome[V] =
      Outcome.Failure(BuiltinFailure.Evaluation(EvaluationFailure.EmptyList), Vector.empty)

  private lazy val parameters: MachineParameters =
    val core =
      CoreVariant.of(variant).fold(e => throw new IllegalStateException(e.toString), identity)
    val denotations: Denotations = function => meanings.getOrElse(function, missing)
    MachineParameters(
      costs.machineCosts,
      Builtins(variant, CoreUnlifting(core), denotations, costs.costing),
      CaserBuiltin.WithData
    )

  /** The builtins a program names. */
  def builtins(program: Program): Set[DefaultFun] =
    Schemes.cata[TermF, Set[DefaultFun]](program.term) {
      case TermF.Builtin(function) => Set(function)
      case layer                   => layer.foldLeft(Set.empty[DefaultFun])(_ ++ _)
    }

  def evaluate(program: Program): Evaluation[Set[DefaultFun]] =
    val absent = builtins(program).filterNot(meanings.contains)
    if absent.nonEmpty then Evaluation.Unavailable(absent)
    else
      val report = CekMachine.run(parameters, BudgetMode.Counting, program.term)
      report.result match
        case success: CekResult.Success => Evaluation.Evaluated(success.term, report.budget.spent)
        case CekResult.Failure(_)       => Evaluation.Failed

  val backend: Conformance.Backend[Set[DefaultFun]] =
    Conformance.Backend(
      evaluate,
      absent => s"no denotation yet for ${absent.map(_.name).toVector.sorted.mkString(", ")}"
    )

/** The 1,009 conformance cases of plutus 1.70.0.0 through the machine, the core builtins and this
  * cost model: every case whose builtins have denotations must give the recorded result and the
  * exact recorded budget, with no exclusion; the others (the cryptographic, bitwise, `Value` and
  * array builtins of work package 6) are counted as incomplete, never as passed.
  */
final class CekConformanceSuite extends munit.FunSuite:
  private lazy val cases =
    ReferenceCorpus.conformance(ReferenceCorpus.Pinned).fold(p => fail(p.toString), identity)

  private lazy val report = Conformance.run(cases, CekConformance.backend.evaluate)

  private def unavailable(m: Mismatch[Set[DefaultFun]]): Boolean = m match
    case Mismatch.Unavailable(_) => true
    case _                       => false

  test("every case whose builtins exist gives the reference's result and budget exactly") {
    assertEquals(cases.size, 1009)
    val incomplete = report.verdicts.count(_._2.exists(unavailable))
    val wrong = report.verdicts.filter(_._2.exists(m => !unavailable(m)))
    val absent = report.verdicts
      .flatMap(_._2.collect { case Mismatch.Unavailable(fs) => fs })
      .flatten
      .distinct
      .map(_.name)
      .sorted
    println(
      s"plutus 1.70.0.0 conformance, machine + core builtins + cost model: ${report.conforming} conform, " +
        s"${wrong.size} disagree, $incomplete incomplete (no denotation yet: ${absent.mkString(", ")})"
    )
    assert(wrong.isEmpty, Report(wrong).render(CekConformance.backend.show))
    assert(report.conforming + incomplete == cases.size)
  }

  test("all 1,009 cases (incomplete until every builtin has a denotation)") {
    val incomplete = report.verdicts.filter(_._2.exists(unavailable))
    val absent =
      incomplete.flatMap(_._2.collect { case Mismatch.Unavailable(fs) => fs }).flatten.distinct
    assume(
      incomplete.isEmpty,
      s"incomplete: ${incomplete.size} cases name builtins with no denotation yet: " +
        absent.map(_.name).sorted.mkString(", ")
    )
  }
