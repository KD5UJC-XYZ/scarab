// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.cost

import cats.Applicative
import cats.syntax.all.*

/** The cost of one machine step or of the start-up, written in the cost model as `exBudgetCPU` and
  * `exBudgetMemory` (`instance ToJSON ExBudget`,
  * `PlutusCore/Evaluation/Machine/ExBudget.hs:180-186`).
  */
final case class StepBudget[A](cpu: A, memory: A):
  def traverse[G[_]: Applicative, B](path: String)(f: (String, A) => G[B]): G[StepBudget[B]] =
    (f(s"$path-exBudgetCPU", cpu), f(s"$path-exBudgetMemory", memory)).mapN(StepBudget(_, _))

/** The machine's step and start-up costs in the cost model, the reference's `CekMachineCostsBase`
  * (`UntypedPlutusCore/Evaluation/Machine/Cek/CekMachineCosts.hs:46-61`, plutus 1.70.0.0
  * `507cab8c`), each field under its own name (`cekMachineCostsOptions`, line 66). Every name
  * starts with `cek`, the prefix that splits the ledger's parameters between the machine and the
  * builtins (`cekMachineCostsPrefix`, lines 40-41; `splitParams`, `CostModelInterface.hs:328-331`).
  * Its shape is the same in every variant; the leaf type `A` is `Unit` for the shape, [[SatInt]]
  * for costs.
  */
final case class CekMachineCosts[A](
    cekStartupCost: StepBudget[A],
    cekVarCost: StepBudget[A],
    cekConstCost: StepBudget[A],
    cekLamCost: StepBudget[A],
    cekDelayCost: StepBudget[A],
    cekForceCost: StepBudget[A],
    cekApplyCost: StepBudget[A],
    cekBuiltinCost: StepBudget[A],
    cekConstrCost: StepBudget[A],
    cekCaseCost: StepBudget[A]
):
  def traverse[G[_]: Applicative, B](f: (String, A) => G[B]): G[CekMachineCosts[B]] =
    (
      cekStartupCost.traverse("cekStartupCost")(f),
      cekVarCost.traverse("cekVarCost")(f),
      cekConstCost.traverse("cekConstCost")(f),
      cekLamCost.traverse("cekLamCost")(f),
      cekDelayCost.traverse("cekDelayCost")(f),
      cekForceCost.traverse("cekForceCost")(f),
      cekApplyCost.traverse("cekApplyCost")(f),
      cekBuiltinCost.traverse("cekBuiltinCost")(f),
      cekConstrCost.traverse("cekConstrCost")(f),
      cekCaseCost.traverse("cekCaseCost")(f)
    ).mapN(CekMachineCosts(_, _, _, _, _, _, _, _, _, _))

  /** Every cost with its parameter name. */
  def leaves: Vector[(String, A)] =
    traverse[[x] =>> cats.data.Const[Vector[(String, A)], x], Unit]((name, a) =>
      cats.data.Const(Vector(name -> a))
    ).getConst

object CekMachineCosts:
  /** The prefix of every machine parameter's name (`cekMachineCostsPrefix`). */
  val Prefix: String = "cek"

  private val unit = StepBudget((), ())

  /** The shape, the same in every variant (`cekMachineCosts{A..E}.json`). */
  val shape: CekMachineCosts[Unit] =
    CekMachineCosts(unit, unit, unit, unit, unit, unit, unit, unit, unit, unit)

  extension (costs: CekMachineCosts[SatInt])
    /** The machine's costs as it charges them (`cekStepCost`, `Cek/Internal.hs:221-232`). */
    def machineCosts: MachineCosts =
      def budget(b: StepBudget[SatInt]): ExBudget = ExBudget(b.cpu, b.memory)
      val constant = budget(costs.cekConstCost)
      val variable = budget(costs.cekVarCost)
      val lamAbs = budget(costs.cekLamCost)
      val apply = budget(costs.cekApplyCost)
      val delay = budget(costs.cekDelayCost)
      val force = budget(costs.cekForceCost)
      val builtin = budget(costs.cekBuiltinCost)
      val constr = budget(costs.cekConstrCost)
      val caseStep = budget(costs.cekCaseCost)
      MachineCosts(
        budget(costs.cekStartupCost),
        {
          case StepKind.Constant => constant
          case StepKind.Variable => variable
          case StepKind.LamAbs   => lamAbs
          case StepKind.Apply    => apply
          case StepKind.Delay    => delay
          case StepKind.Force    => force
          case StepKind.Builtin  => builtin
          case StepKind.Constr   => constr
          case StepKind.Case     => caseStep
        }
      )
