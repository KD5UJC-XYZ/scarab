// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.ledger

import cats.syntax.all.*
import xyz.kd5ujc.scarab.uplc.{DefaultFun, PlutusLanguage, ProtocolMajor, SemanticsVariant}
import xyz.kd5ujc.scarab.uplc.builtin.{Builtins, Denotation, Denotations}
import xyz.kd5ujc.scarab.uplc.builtin.core.{CoreDenotations, CoreUnlifting, UnsupportedVariant}
import xyz.kd5ujc.scarab.uplc.builtin.extended.ExtendedDenotations
import xyz.kd5ujc.scarab.uplc.cek.{CaserBuiltin, MachineParameters}
import xyz.kd5ujc.scarab.uplc.cost.{
  CostModelApplyWarn,
  EvaluationContext,
  MachineCosts,
  VariantCosts
}

/** The machine of one semantics variant, the reference's `DefaultMachineVariantParameters`
  * (`PlutusCore/Evaluation/Machine/MachineParameters/Default.hs`, plutus 1.70.0.0 `507cab8c`): the
  * step and start-up costs and the builtins (unlifting, denotations and costing) of the variant.
  * The caser is chosen by protocol when the machine runs ([[LedgerContext.at]]).
  */
final case class VariantMachine(variant: SemanticsVariant, costs: MachineCosts, builtins: Builtins)

/** The reference's `EvaluationContext` (`PlutusLedgerApi/Common/Eval.hs:175-195`): a language's
  * machines, one per semantics variant of its context, made once from the ledger's cost-model list
  * and looked up by protocol at each evaluation.
  *
  * Every language's context takes `defaultCaserBuiltinFor` as its caser
  * (`V1/EvaluationContext.hs:46`, V2 the same, `V3/EvaluationContext.hs:44`), which is
  * [[CaserBuiltin.forProtocol]], and the variant of a protocol from [[SemanticsVariant.of]].
  *
  * @param language
  *   the language the context is for: the one a script is evaluated as
  *   ([[PlutusLanguage.evaluatedAs]])
  */
final case class LedgerContext(language: PlutusLanguage, machines: Vector[VariantMachine]):

  /** `toMachineParameters` (`Eval.hs:132-139`): the machine of the protocol's variant with the
    * protocol's caser. The reference stops with an internal error where the variant has no machine;
    * that cannot happen to a context made by [[LedgerContext.make]], whose variants are those of
    * [[SemanticsVariant.ofContext]].
    */
  def at(protocol: ProtocolMajor): Either[Unavailability, MachineParameters] =
    val variant = SemanticsVariant.of(language, protocol)
    machines
      .find(_.variant == variant)
      .map(m => MachineParameters(m.costs, m.builtins, CaserBuiltin.forProtocol(protocol)))
      .toRight(Unavailability.NoMachine(language, protocol))

object LedgerContext:

  /** `mkEvaluationContext` of V1, V2 and V3 (`V1/EvaluationContext.hs:40-58`, V2 the same,
    * `V3/EvaluationContext.hs:37-54`), with V4 as V3 ([[PlutusLanguage.evaluatedAs]]): the list
    * tagged with the language's parameter names, the costs of every variant of its context
    * ([[EvaluationContext.build]]), and then the builtins of each. The warnings are the reference's
    * (`CostModelApplyWarn`); a refusal is a defect here, as the reference refuses no list.
    */
  def make(
      language: PlutusLanguage,
      costModel: Vector[Long]
  ): (Vector[CostModelApplyWarn], Either[Unavailability, LedgerContext]) =
    val (warnings, costs) = EvaluationContext.build(language, costModel)
    (warnings, costs.leftMap(Unavailability.CostModel(_)).flatMap(of))

  /** The machines of a costing context: the builtins of each of its variants. */
  def of(context: EvaluationContext): Either[Unavailability, LedgerContext] =
    context.variants.traverse(machine).map(LedgerContext(context.language, _))

  /** The machine of one variant: its unlifting, the denotations of every builtin and its costing. A
    * builtin without a denotation here makes no machine: the reference evaluates every builtin a
    * program names, whatever its language and protocol (the decoder alone checks availability,
    * `Common/SerialisedScript.hs:216-225`).
    */
  def machine(costs: VariantCosts): Either[Unavailability, VariantMachine] =
    val variant = costs.variant
    def unsupported(e: UnsupportedVariant) = Unavailability.Variant(e.variant)
    for
      unlifting <- CoreUnlifting.of(variant).leftMap(unsupported)
      core <- CoreDenotations.of(variant).leftMap(unsupported)
      extended <- ExtendedDenotations.of(variant).leftMap(unsupported)
      denotations <- total(variant, core ++ extended)
    yield VariantMachine(
      variant,
      costs.machineCosts,
      Builtins(variant, unlifting, denotations, costs.costing)
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
