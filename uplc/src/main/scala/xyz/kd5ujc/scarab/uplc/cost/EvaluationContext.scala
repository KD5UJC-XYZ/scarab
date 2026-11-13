// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.cost

import cats.Monad
import cats.mtl.{Raise, Tell}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.uplc.{ParamNames, PlutusLanguage, ProtocolMajor, SemanticsVariant}
import xyz.kd5ujc.scarab.uplc.builtin.Costing

/** A warning of the reference when the ledger gives a cost-model list of another length than the
  * language's parameter names (`CostModelApplyWarn`, `CostModelInterface.hs:255-259`, plutus
  * 1.70.0.0 `507cab8c`). Only a warning: the list is used all the same.
  */
enum CostModelApplyWarn:
  /** More values than names: the values beyond the last name are dropped. */
  case TooManyParameters(expected: Int, actual: Int)

  /** Fewer values than names: the names without a value get `maxBound`. */
  case TooFewParameters(expected: Int, actual: Int)

/** Why a cost-model list does not make an evaluation context. None of these depends on the values
  * of the list, only on the parameter names and the shapes of the variants, and at 1.70.0.0 none
  * occurs (`CostModelSuite`): the reference refuses no list of `Int64` values of any length
  * (`tagWithParamNames` never fails, `ParamName.hs:86-112`, and `applyParams` parses every
  * coefficient back as any `Int64`, `CostModelInterface.hs:290-317`).
  */
enum CostModelApplyError:
  /** A parameter name that the variant's model has no coefficient for: the reference's
    * `CMUnknownParamError` (`CostModelInterface.hs:244-246,315`).
    */
  case UnknownParameter(name: String)

  /** A coefficient of the variant's model that no parameter name gives. The reference would keep
    * the value of its default model (`Map.preserveMissing`, line 305); this evaluator carries the
    * shapes of the default models but not their values, since every coefficient is a parameter.
    */
  case UnsuppliedParameter(name: String)

  /** A variant that 1.70.0.0 defines no cost model for (F and G are 1.71.0.0's). */
  case NoModel(variant: SemanticsVariant)

/** The cost model of one variant once the ledger's parameters are applied: the machine's costs and
  * the builtins' costing functions (the reference's `CostModel CekMachineCosts BuiltinCostModel`,
  * `MachineParameters.hs`).
  */
final case class CostModel(machine: CekMachineCosts[SatInt], builtins: BuiltinCostModel[SatInt])

/** What the machine needs from the cost model to evaluate under one semantics variant, the costing
  * half of the reference's `DefaultMachineVariantParameters` (`MachineParameters/Default.hs`).
  */
final case class VariantCosts(
    variant: SemanticsVariant,
    model: CostModel,
    machineCosts: MachineCosts,
    costing: Costing
)

/** The costing part of a language's evaluation context (`EvaluationContext`,
  * `PlutusLedgerApi/Common/Eval.hs:175-195`): the costs of every variant the language evaluates
  * under, built once from the ledger's cost-model list and looked up by protocol version.
  */
final case class EvaluationContext(language: PlutusLanguage, variants: Vector[VariantCosts]):

  /** The costs at `protocol` (`toMachineParameters`, `Common/Eval.hs:132-139`). The reference stops
    * with an internal error when the variant of `protocol` has none; that cannot happen, as
    * [[SemanticsVariant.of]] gives a variant of [[SemanticsVariant.ofContext]].
    */
  def at(protocol: ProtocolMajor): Option[VariantCosts] =
    val variant = SemanticsVariant.of(language, protocol)
    variants.find(_.variant == variant)

object EvaluationContext:

  /** The parameter names of the language whose evaluation context a script of `language` uses: a
    * PlutusV4 script is evaluated with PlutusV3's ([[PlutusLanguage.evaluatedAs]]).
    */
  def parameterNames(language: PlutusLanguage): Vector[String] = language.evaluatedAs match
    case PlutusLanguage.V1                     => ParamNames.v1
    case PlutusLanguage.V2                     => ParamNames.v2
    case PlutusLanguage.V3 | PlutusLanguage.V4 => ParamNames.v3

  /** `tagWithParamNames` (`PlutusLedgerApi/Common/ParamName.hs:86-112`): the values paired with the
    * names in order; the values beyond the last name are dropped, and a name without a value gets
    * `maxBound`, each with a warning.
    */
  def tagWithParamNames(
      names: Vector[String],
      values: Vector[Long]
  ): (Option[CostModelApplyWarn], Map[String, Long]) =
    val expected = names.size
    val actual = values.size
    val warning =
      if expected < actual then Some(CostModelApplyWarn.TooManyParameters(expected, actual))
      else if expected > actual then Some(CostModelApplyWarn.TooFewParameters(expected, actual))
      else None
    (warning, names.zip(values.padTo(expected, Long.MaxValue)).toMap)

  /** The evaluation context of `language` from the ledger's list (`mkEvaluationContext`,
    * `PlutusLedgerApi/V1/EvaluationContext.hs:40-58`, V2 the same, `V3/EvaluationContext.hs:37-54`;
    * `mkDynEvaluationContext`, `Common/Eval.hs:209-219`): the list tagged with the names, then the
    * model of every variant of the context made from the same parameters, in the order of the
    * variants, the first failure winning (`mkMachineVariantParametersFor`,
    * `MachineParameters/Default.hs:66-78`).
    */
  def build(
      language: PlutusLanguage,
      values: Vector[Long]
  ): (Vector[CostModelApplyWarn], Either[CostModelApplyError, EvaluationContext]) =
    val (warning, parameters) = tagWithParamNames(parameterNames(language), values)
    val context = SemanticsVariant
      .ofContext(language)
      .traverse(variant => costsOf(variant, parameters))
      .map(EvaluationContext(language.evaluatedAs, _))
    (warning.toVector, context)

  /** [[build]] with the reference's capabilities (`MonadError CostModelApplyError` and `MonadWriter
    * [CostModelApplyWarn]`): the warnings are told before a failure is raised.
    */
  def make[F[_]](language: PlutusLanguage, values: Vector[Long])(using
      F: Monad[F],
      warnings: Tell[F, Vector[CostModelApplyWarn]],
      failure: Raise[F, CostModelApplyError]
  ): F[EvaluationContext] =
    val (told, context) = build(language, values)
    warnings
      .tell(told) >> context.fold(failure.raise[CostModelApplyError, EvaluationContext], F.pure)

  /** The costs of `variant` from named parameters (`applyCostModelParams`,
    * `CostModelInterface.hs:352-382`): the parameters whose names start with `cek` are the
    * machine's and the others the builtins' (`splitParams`), each part applied to its model, the
    * machine's first (`applyParams`, lines 290-317: a name the model does not have fails, the
    * smallest such name first, as `Map.mergeA` visits the keys in order).
    */
  def costsOf(
      variant: SemanticsVariant,
      parameters: Map[String, Long]
  ): Either[CostModelApplyError, VariantCosts] =
    val (machineParameters, builtinParameters) =
      parameters.partition((name, _) => name.startsWith(CekMachineCosts.Prefix))
    for
      shapes <- BuiltinShapes.of(variant).toRight(CostModelApplyError.NoModel(variant))
      machine <- fill(machineParameters, CekMachineCosts.shape.leaves.map(_._1)) { lookup =>
        CekMachineCosts.shape.traverse((name, _) => lookup(name))
      }
      builtins <- fill(builtinParameters, shapes.leaves.map(_._1)) { lookup =>
        shapes.traverse((name, _) => lookup(name))
      }
      costing <- BuiltinCosting.of(variant, builtins).toRight(CostModelApplyError.NoModel(variant))
    yield VariantCosts(variant, CostModel(machine, builtins), machine.machineCosts, costing)

  private def fill[A](parameters: Map[String, Long], coefficients: Vector[String])(
      build: (String => Either[CostModelApplyError, SatInt]) => Either[CostModelApplyError, A]
  ): Either[CostModelApplyError, A] =
    val known = coefficients.toSet
    parameters.keySet.filterNot(known).minOption match
      case Some(unknown) => Left(CostModelApplyError.UnknownParameter(unknown))
      case None =>
        build(name =>
          parameters.get(name).map(SatInt(_)).toRight(CostModelApplyError.UnsuppliedParameter(name))
        )
