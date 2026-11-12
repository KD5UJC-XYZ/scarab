// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.cost

/** A budget of CPU steps and memory units, `ExBudget`
  * (`PlutusCore/Evaluation/Machine/ExBudget.hs:175`, plutus 1.70.0.0): both components are
  * [[SatInt]], and combining two adds each component with saturation (`Semigroup ExBudget`, line
  * 197; `Semigroup ExCPU` /`ExMemory`, `ExMemory.hs:100,127`).
  */
final case class ExBudget(cpu: SatInt, memory: SatInt):
  def +(that: ExBudget): ExBudget = ExBudget(cpu + that.cpu, memory + that.memory)

  /** The budget repeated `times` times, as `stimes` does on the step costs of the machine. */
  def times(count: Long): ExBudget = ExBudget(cpu * SatInt(count), memory * SatInt(count))

object ExBudget:
  val zero: ExBudget = ExBudget(SatInt.zero, SatInt.zero)

  def of(cpu: Long, memory: Long): ExBudget = ExBudget(SatInt(cpu), SatInt(memory))

/** The cost of one builtin application as the machine spends it, `ExBudgetStream`
  * (`PlutusCore/Evaluation/Machine/ExBudgetStream.hs:27-29`): a non-empty sequence of budgets, each
  * spent, and checked against the limit, in order (`evalBuiltinApp`, `Cek/Internal.hs:1059-1063`).
  * The reference's tail is lazy; this one is strict, which changes when a cost is computed, not its
  * value.
  */
final case class CostStream(head: ExBudget, tail: Vector[ExBudget]):
  def budgets: Vector[ExBudget] = head +: tail

  /** `sumExBudgetStream`. */
  def total: ExBudget = tail.foldLeft(head)(_ + _)

object CostStream:
  def single(budget: ExBudget): CostStream = CostStream(budget, Vector.empty)

  /** The zero cost of an application whose unlifting failed (`builtinRuntimeFailure`,
    * `PlutusCore/Builtin/Runtime.hs:111`).
    */
  val free: CostStream = single(ExBudget.zero)

/** A step of the CEK machine, charged at its own cost (`StepKind`, `Cek/Internal.hs:208-217`). */
enum StepKind:
  case Constant, Variable, LamAbs, Apply, Delay, Force, Builtin, Constr, Case

/** The machine's costs per step and its start-up cost (`CekMachineCostsBase`,
  * `Cek/CekMachineCosts.hs:46-60`), read from the cost model by the costing.
  *
  * @param step
  *   the cost of one step of each kind (`cekStepCost`, `Cek/Internal.hs:221`)
  */
final case class MachineCosts(startup: ExBudget, step: StepKind => ExBudget)
