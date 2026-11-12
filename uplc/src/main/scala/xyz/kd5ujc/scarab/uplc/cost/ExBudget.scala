// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.cost

import scala.annotation.tailrec
import cats.Eval

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
  * (`PlutusCore/Evaluation/Machine/ExBudgetStream.hs:15-29`): a non-empty stream of budgets with
  * strict elements and a lazy tail. The machine spends each element, checking it against the limit,
  * before it forces the rest (`evalBuiltinApp`, `Cek/Internal.hs:1059-1064`), so a budget that runs
  * out stops the costing as well: a measure that is long to compute (a shared `Data` graph unfolds
  * to exponentially many nodes) is computed only as far as the budget allows.
  */
enum CostStream:
  case Last(budget: ExBudget)
  case Cons(budget: ExBudget, rest: Eval[CostStream])

  def head: ExBudget = this match
    case Last(budget)    => budget
    case Cons(budget, _) => budget

  /** The first `count` budgets, forcing no more of the stream than that. */
  def prefix(count: Int): Vector[ExBudget] =
    @tailrec def go(stream: CostStream, left: Int, acc: Vector[ExBudget]): Vector[ExBudget] =
      if left <= 0 then acc
      else
        stream match
          case Last(budget)       => acc :+ budget
          case Cons(budget, rest) => go(rest.value, left - 1, acc :+ budget)
    go(this, count, Vector.empty)

  /** `sumExBudgetStream`: forces the whole stream, so only for a stream known to be short. */
  def total: ExBudget =
    @tailrec def go(stream: CostStream, acc: ExBudget): ExBudget = stream match
      case Last(budget)       => acc + budget
      case Cons(budget, rest) => go(rest.value, acc + budget)
    go(this, ExBudget.zero)

object CostStream:
  def single(budget: ExBudget): CostStream = Last(budget)

  /** A finite stream of the given budgets, in order. */
  def of(head: ExBudget, tail: ExBudget*): CostStream =
    (head +: tail.toVector).reverseIterator
      .foldLeft(Option.empty[CostStream]) { (rest, budget) =>
        Some(rest.fold(Last(budget): CostStream)(stream => Cons(budget, Eval.now(stream))))
      }
      .getOrElse(Last(head))

  /** The zero cost of an application whose unlifting failed (`builtinRuntimeFailure`,
    * `PlutusCore/Builtin/Runtime.hs:111`).
    */
  val free: CostStream = Last(ExBudget.zero)

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
