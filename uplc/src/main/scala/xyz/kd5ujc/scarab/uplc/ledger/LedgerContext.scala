// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.ledger

import cats.syntax.all.*
import xyz.kd5ujc.scarab.uplc.{
  DefaultFun,
  PlutusLanguage,
  ProgramVersion,
  ProtocolMajor,
  SemanticsVariant
}
import xyz.kd5ujc.scarab.uplc.builtin.{Builtins, Denotation, Denotations}
import xyz.kd5ujc.scarab.uplc.builtin.core.{CoreDenotations, CoreUnlifting}
import xyz.kd5ujc.scarab.uplc.builtin.extended.ExtendedDenotations
import xyz.kd5ujc.scarab.uplc.cek.{CaserBuiltin, MachineParameters}
import xyz.kd5ujc.scarab.uplc.cost.{
  CostModelApplyError,
  CostModelApplyWarn,
  EvaluationContext,
  MachineCosts,
  VariantCosts
}

/** The machine of one semantics variant, the reference's `DefaultMachineVariantParameters`
  * (`PlutusCore/Evaluation/Machine/MachineParameters/Default.hs:19-21`, plutus 1.70.0.0
  * `507cab8c`): the step and start-up costs and the builtins (unlifting, denotations and costing)
  * of the variant. The caser is chosen by protocol and program version when the machine runs
  * ([[LedgerContext.at]]), as plutus 1.71.0.0 chooses it (`MachineParameters.hs:37-40` at
  * `261766ad`).
  */
final case class VariantMachine(variant: SemanticsVariant, costs: MachineCosts, builtins: Builtins)

/** The reference's `EvaluationContext` (`PlutusLedgerApi/Common/Eval.hs:177-195`, plutus 1.71.0.0
  * `261766ad`): a language's machines, one per semantics variant of its context, made once from the
  * ledger's cost-model list and looked up by protocol at each evaluation.
  *
  * Every language's context takes `defaultCaserBuiltinFor` as its caser
  * (`V1/EvaluationContext.hs:51`, V2 the same, `V3/EvaluationContext.hs:48`, and
  * `V4/EvaluationContext.hs:43`), which is [[CaserBuiltin.forProtocol]], and the variant of a
  * protocol from [[SemanticsVariant.of]].
  *
  * @param language
  *   the language the context is for: the one a script is evaluated as
  *   ([[PlutusLanguage.evaluatedAs]])
  */
final case class LedgerContext(language: PlutusLanguage, machines: Vector[VariantMachine]):

  /** `toMachineParameters` (`Eval.hs:133-141`, plutus 1.71.0.0): the machine of the protocol's
    * variant with the caser of the protocol and the program's version. The reference stops with an
    * internal error where the variant has no machine; that cannot happen to a context made by
    * [[LedgerContext.make]], whose variants are those of [[SemanticsVariant.ofContext]].
    */
  def at(
      protocol: ProtocolMajor,
      version: ProgramVersion
  ): Either[Unavailability, MachineParameters] =
    val variant = SemanticsVariant.of(language, protocol)
    machines
      .find(_.variant == variant)
      .map(m => MachineParameters(m.costs, m.builtins, CaserBuiltin.forProtocol(protocol, version)))
      .toRight(Unavailability.NoMachine(language, protocol))

object LedgerContext:

  /** `mkEvaluationContext` of V1, V2 and V3 (`V1/EvaluationContext.hs:41-64`, V2 the same,
    * `V3/EvaluationContext.hs:38-58`, plutus 1.71.0.0), with V4 as V3
    * ([[PlutusLanguage.evaluatedAs]]): the list tagged with the language's parameter names, the
    * costs of every variant of its context ([[EvaluationContext.build]]), and then the builtins of
    * each. The warnings are the reference's (`CostModelApplyWarn`); a refusal is a defect here, as
    * the reference refuses no list.
    */
  def make(
      language: PlutusLanguage,
      costModel: Vector[Long]
  ): (Vector[CostModelApplyWarn], Either[Unavailability, LedgerContext]) =
    val (warnings, costs) = EvaluationContext.build(language, costModel)
    (warnings, fromCosts(costs))

  /** The machines of a costing context, or no verdict when the costing context was refused (every
    * [[xyz.kd5ujc.scarab.uplc.cost.CostModelApplyError]] is a defect here).
    */
  def fromCosts(
      costs: Either[CostModelApplyError, EvaluationContext]
  ): Either[Unavailability, LedgerContext] =
    costs.leftMap(Unavailability.CostModel(_)).flatMap(of)

  /** The machines of a costing context: the builtins of each of its variants. */
  def of(context: EvaluationContext): Either[Unavailability, LedgerContext] =
    context.variants.traverse(machine).map(LedgerContext(context.language, _))

  /** The machine of one variant: its unlifting, the denotations of every builtin and its costing. A
    * builtin without a denotation here makes no machine: the reference evaluates every builtin a
    * program names, whatever its language and protocol (the decoder alone checks availability,
    * `Common/SerialisedScript.hs:216-225`, plutus 1.70.0.0).
    */
  def machine(costs: VariantCosts): Either[Unavailability, VariantMachine] =
    val variant = costs.variant
    total(variant, CoreDenotations.of(variant) ++ ExtendedDenotations.of(variant)).map(
      denotations =>
        VariantMachine(
          variant,
          costs.machineCosts,
          Builtins(variant, CoreUnlifting.of(variant), denotations, costs.costing)
        )
    )

  /** The denotations by builtin, when every builtin has one: a table in declaration order. */
  private def total(
      variant: SemanticsVariant,
      table: Map[DefaultFun, Denotation]
  ): Either[Unavailability, Denotations] =
    val missing = DefaultFun.values.toSet.filterNot(table.contains)
    DefaultFun.values.toVector
      .traverse(table.get)
      .toRight(Unavailability.NoDenotation(variant, missing))
      .map(byOrdinal => function => byOrdinal(function.ordinal))
