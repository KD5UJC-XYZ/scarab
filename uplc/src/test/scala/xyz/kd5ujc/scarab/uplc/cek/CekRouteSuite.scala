// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.cek

import xyz.kd5ujc.scarab.uplc.{Constant, DefaultFun, SemanticsVariant}
import xyz.kd5ujc.scarab.uplc.builtin.{
  Argument,
  Builtins,
  Denotations,
  MachineValue,
  Unlifting,
  UnliftingFailure
}
import xyz.kd5ujc.scarab.uplc.builtin.core.CoreUnlifting
import xyz.kd5ujc.scarab.uplc.cost.{CekConformance, DefaultCostModels}
import xyz.kd5ujc.scarab.uplc.cek.Terms.*

/** A saturated builtin application whose unlifting has only its vector form: the costing and the
  * denotation read the arguments that unlifting made, as before #1411, not the values the machine
  * holds.
  */
final class CekRouteSuite extends munit.FunSuite:
  private val variant = SemanticsVariant.E

  /** The core unlifting, with every integer it unlifts made `2^128` times larger. */
  private val widening: Unlifting = new Unlifting:
    def apply[V](function: DefaultFun, arguments: Vector[V])(using
        MachineValue[V]
    ): Either[UnliftingFailure, Vector[Argument[V]]] =
      CoreUnlifting
        .of(variant)(function, arguments)
        .map(_.map {
          case Argument.Con(Constant.Integer(n)) => Argument.Con(Constant.Integer(n << 128))
          case other                             => other
        })

  private val parameters: MachineParameters =
    val costs = DefaultCostModels.costs(variant)
    val meanings = CekConformance.denotations(variant)
    val denotations: Denotations = function => meanings(function)
    MachineParameters(
      costs.machineCosts,
      Builtins(variant, widening, denotations, costs.costing),
      CaserBuiltin.WithData
    )

  test("the costing and the denotation read what an unlifting of vectors alone made") {
    val term = app(builtin(DefaultFun.AddInteger), int(1), int(2))
    val now = CekMachine.run(parameters, BudgetMode.Counting, term)
    val before = PresentCekMachine.run(parameters, BudgetMode.Counting, term)
    assertEquals(
      now.result,
      CekResult.SuccessConstant(Constant.Integer(BigInt(3) << 128)),
      "the denotation adds the unlifted integers"
    )
    assertEquals(now.budget, before.budget, "the cost is that of the unlifted integers")
  }
