// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.cost

import cats.syntax.all.*
import xyz.kd5ujc.scarab.uplc.builtin.extended.ExtendedDenotations
import xyz.kd5ujc.scarab.uplc.{
  DefaultFun,
  Program,
  ProgramVersion,
  ProtocolMajor,
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
import xyz.kd5ujc.scarab.uplc.builtin.core.{CoreDenotations, CoreUnlifting}
import xyz.kd5ujc.scarab.uplc.cek.{
  BudgetMode,
  CaserBuiltin,
  CekMachine,
  CekResult,
  MachineParameters
}
import xyz.kd5ujc.scarab.uplc.text.{Conformance, Evaluation, Mismatch, Report}

/** The CEK machine with every builtin and this cost model in the reference conformance runner's
  * profile of a release: its default semantics variant with the default parameters
  * (`defaultCostModelParamsForTesting`), the counting budget, and the caser of a program
  * (`PlutusConformance/CekEvaluator.hs`). A program that names a builtin with no denotation is
  * [[Evaluation.Unavailable]] with those builtins.
  *
  * @param tag
  *   the release, a tag of [[ReferenceCorpus.Releases]]
  * @param caser
  *   the caser the runner gives a program of a version
  */
final class CekConformance(
    val tag: String,
    variant: SemanticsVariant,
    defaults: => VariantCosts,
    caser: ProgramVersion => CaserBuiltin
):
  private lazy val costs: VariantCosts = defaults

  private lazy val meanings: Map[DefaultFun, Denotation] = CekConformance.denotations(variant)

  def evaluate(program: Program): Evaluation[Set[DefaultFun]] =
    val absent = CekConformance.builtins(program).filterNot(meanings.contains)
    if absent.nonEmpty then Evaluation.Unavailable(absent)
    else
      val parameters = CekConformance.machine(costs, caser(program.version))
      val report = CekMachine.run(parameters, BudgetMode.Counting, program.term)
      report.result match
        case success: CekResult.Success => Evaluation.Evaluated(success.term, report.budget.spent)
        case CekResult.Failure(_)       => Evaluation.Failed

  lazy val backend: Conformance.Backend[Set[DefaultFun]] =
    Conformance.Backend(
      evaluate,
      absent => s"no denotation yet for ${absent.map(_.name).toVector.sorted.mkString(", ")}"
    )

object CekConformance:

  /** The runner of plutus 1.70.0.0 (`CekEvaluator.hs:46-61`, `507cab8c`): variant E (`def =
    * maxBound`, `Builtins.hs:2546-2547`) with its default parameters
    * (`ExBudgetingDefaults.hs:281-282`), and the default caser `caseBuiltin`, which allows `case`
    * on every built-in type, `data` included (`PlutusCore/Builtin/Case.hs:60-61`), whatever the
    * program's version.
    */
  val Plutus170: CekConformance =
    new CekConformance(
      ReferenceCorpus.Plutus170,
      SemanticsVariant.E,
      DefaultCostModels.Plutus170.costs(SemanticsVariant.E),
      _ => CaserBuiltin.WithData
    )

  /** The runner of plutus 1.71.0.0 (`CekEvaluator.hs:47-62`, `261766ad`): variant G (`def =
    * maxBound`, `Builtins.hs:2564-2565`) with the default parameters of E
    * (`defaultCostModelParamsForTesting`, `ExBudgetingDefaults.hs:301-302`), which G reads
    * (`cekCostModelForVariant`, lines 158-159), and the ledger's caser at protocol 12 for the
    * program's version (`defaultCaserBuiltinFor newestPV v`, `CekEvaluator.hs:54`; `newestPV =
    * dijkstraPV`, `Common/ProtocolVersions.hs:114-115`).
    */
  val Plutus171: CekConformance =
    new CekConformance(
      ReferenceCorpus.Plutus171,
      SemanticsVariant.G,
      DefaultCostModels.costs(SemanticsVariant.G),
      CaserBuiltin.forProtocol(ProtocolMajor.Dijkstra, _)
    )

  /** The runner of each release. */
  def of(tag: String): CekConformance =
    if tag == ReferenceCorpus.Plutus171 then Plutus171 else Plutus170

  /** The denotations of `variant`: the core ones and the others. */
  def denotations(variant: SemanticsVariant): Map[DefaultFun, Denotation] =
    CoreDenotations.of(variant) ++ ExtendedDenotations.of(variant)

  // Every program is checked for builtins without a denotation before it runs, so this one is never
  // applied; it fails as a builtin of the program would.
  private val missing: Denotation = new Denotation:
    def apply[V](arguments: Vector[Argument[V]])(using MachineValue[V]): Outcome[V] =
      Outcome.Failure(BuiltinFailure.Evaluation(EvaluationFailure.EmptyList), Vector.empty)

  /** The machine of `costs` with the builtins of its variant and `caser`. */
  def machine(costs: VariantCosts, caser: CaserBuiltin): MachineParameters =
    val meanings = denotations(costs.variant)
    val total: Denotations = function => meanings.getOrElse(function, missing)
    MachineParameters(
      costs.machineCosts,
      Builtins(costs.variant, CoreUnlifting.of(costs.variant), total, costs.costing),
      caser
    )

  /** The builtins a program names. */
  def builtins(program: Program): Set[DefaultFun] =
    Schemes.cata[TermF, Set[DefaultFun]](program.term) {
      case TermF.Builtin(function) => Set(function)
      case layer                   => layer.foldLeft(Set.empty[DefaultFun])(_ ++ _)
    }

/** The conformance cases of a plutus release through the machine, every builtin and this cost model
  * in that release's runner profile ([[CekConformance]]): every case must give the recorded result
  * and the exact recorded budget, with no exclusion; a case naming a builtin with no denotation
  * would be counted as incomplete, never as passed.
  *
  * @param size
  *   the number of cases of the release's corpus
  */
abstract class CekConformanceGate(runner: CekConformance, size: Int) extends munit.FunSuite:
  private lazy val cases =
    ReferenceCorpus.conformance(runner.tag).fold(p => fail(p.toString), identity)

  private lazy val report = Conformance.run(cases, runner.backend.evaluate)

  private def unavailable(m: Mismatch[Set[DefaultFun]]): Boolean = m match
    case Mismatch.Unavailable(_) => true
    case _                       => false

  test(s"plutus ${runner.tag}: every case gives the reference's result and budget exactly") {
    assertEquals(cases.size, size)
    val incomplete = report.verdicts.count(_._2.exists(unavailable))
    val wrong = report.verdicts.filter(_._2.exists(m => !unavailable(m)))
    println(
      s"plutus ${runner.tag} conformance, machine + builtins + cost model: " +
        s"${report.conforming} conform, ${wrong.size} disagree, $incomplete incomplete"
    )
    assert(wrong.isEmpty, Report(wrong).render(runner.backend.show))
    assert(report.conforming + incomplete == cases.size)
  }

  test(s"plutus ${runner.tag}: all $size cases have every builtin they name") {
    val incomplete = report.verdicts.filter(_._2.exists(unavailable))
    val absent =
      incomplete.flatMap(_._2.collect { case Mismatch.Unavailable(fs) => fs }).flatten.distinct
    assert(
      incomplete.isEmpty,
      s"${incomplete.size} cases name builtins with no denotation: " +
        absent.map(_.name).sorted.mkString(", ")
    )
  }

/** The 1,009 cases of plutus 1.70.0.0 in its runner's profile. */
final class CekConformanceSuite extends CekConformanceGate(CekConformance.Plutus170, 1009)

/** The 1,009 cases of plutus 1.71.0.0 in its runner's profile: variant G, the caser of protocol 12
  * for each program's version.
  */
final class CekConformance171Suite extends CekConformanceGate(CekConformance.Plutus171, 1009)
