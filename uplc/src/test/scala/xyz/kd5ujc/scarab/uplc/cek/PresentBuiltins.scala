// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.cek

import xyz.kd5ujc.scarab.uplc.SemanticsVariant
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
import xyz.kd5ujc.scarab.uplc.builtin.core.{CoreUnlifting, PresentCoreDenotations}
import xyz.kd5ujc.scarab.uplc.builtin.extended.ExtendedDenotations
import xyz.kd5ujc.scarab.uplc.cost.{BuiltinCostModel, PresentBuiltinCosting, SatInt, VariantCosts}

/** The builtins of the machine before #1411 ([[PresentCekMachine]]): the core denotations and the
  * costing as they were at `929f794a6` ([[PresentCoreDenotations]], [[PresentBuiltinCosting]]),
  * with the unlifting and the other denotations, which #1411 did not change.
  */
object PresentBuiltins:
  /** The denotations of `variant` before #1411. */
  def denotations(variant: SemanticsVariant): Denotations =
    val meanings = PresentCoreDenotations.of(variant) ++ ExtendedDenotations.of(variant)
    function => meanings.getOrElse(function, missing)

  /** The builtins of `variant` costed by `model`, before #1411. */
  def of(variant: SemanticsVariant, model: BuiltinCostModel[SatInt]): Builtins =
    Builtins(
      variant,
      CoreUnlifting.of(variant),
      denotations(variant),
      PresentBuiltinCosting.of(variant, model)
    )

  /** The machine of `costs` before #1411, with `caser`. */
  def machine(costs: VariantCosts, caser: CaserBuiltin): MachineParameters =
    MachineParameters(costs.machineCosts, of(costs.variant, costs.model.builtins), caser)

  // every builtin has a denotation in each variant the suites use; this one is never applied
  private val missing: Denotation = new Denotation:
    def apply[V](arguments: Vector[Argument[V]])(using MachineValue[V]): Outcome[V] =
      Outcome.Failure(BuiltinFailure.Evaluation(EvaluationFailure.EmptyList), Vector.empty)
