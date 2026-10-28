// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import scalus.cardano.ledger.ExUnits as ScalusExUnits
import scalus.uplc.eval.{BudgetSpender, CekValEnv, ExBudgetCategory, OutOfExBudgetError, StepKind}

/** How plutus 1.70.0.0 (`507cab8c`) spends a budget, in place of Scalus 1.4.0's spenders.
  * References are to `untyped-plutus-core/src/UntypedPlutusCore/Evaluation/Machine/Cek/`.
  *
  * Scalus charges every machine step as it happens and keeps the remaining budget in a `Long` that
  * wraps (`RestrictingBudgetSpender`, `Cek.scala` of Scalus 1.4.0, lines 758-775). The reference
  * counts steps and charges them in batches (`stepAndMaybeSpend` and `spendAccumulatedBudget`,
  * `Internal.hs` lines 1011-1043, with a slippage of 200 steps, lines 416-421), charges a builtin's
  * budget stream element by element (`evalBuiltinApp`, lines 1053-1064) and keeps the remaining
  * budget in [[SatInt]], comparing it with zero after every charge (`restricting`,
  * `ExBudgetMode.hs` lines 126-172). With non-negative costs the two agree on the verdict and the
  * budget; with negative costs or costs near `Int64` 's bounds they do not (issue 485: with every
  * parameter at `Int64` 's minimum the startup cost wraps Scalus's remaining budget below zero,
  * while the reference's saturates at `maxBound`).
  *
  * Both classes implement Scalus's `BudgetSpender` callback, whose protocol is mutable state and an
  * exception (`OutOfExBudgetError`) for an exhausted budget, as the reference's spender is a
  * mutable counter array and a thrown machine error. One instance serves one evaluation on one
  * thread.
  */
private[plutus] object ReferenceBudget:
  /** `defaultSlippage` (`Internal.hs` lines 416-421). */
  val Slippage = 200

  /** `StepKind` 's constructors in the reference's order (`Internal.hs` lines 207-218), which is
    * the order `spendAccumulatedBudget` charges them in.
    */
  private val kinds: Vector[StepKind] = Vector(
    StepKind.Const,
    StepKind.Var,
    StepKind.LamAbs,
    StepKind.Apply,
    StepKind.Delay,
    StepKind.Force,
    StepKind.Builtin,
    StepKind.Constr,
    StepKind.Case
  )

  private def index(kind: StepKind): Int = kind match
    case StepKind.Const   => 0
    case StepKind.Var     => 1
    case StepKind.LamAbs  => 2
    case StepKind.Apply   => 3
    case StepKind.Delay   => 4
    case StepKind.Force   => 5
    case StepKind.Builtin => 6
    case StepKind.Constr  => 7
    case StepKind.Case    => 8

  /** The machine's side of the spending: what Scalus's CEK machine reports is passed on to `mode`
    * as the reference's machine would charge it. A step is counted; the counted steps are charged
    * per kind, `count * cost` in [[SatInt]] (`stimes`, `PlutusCore/Evaluation/Machine/ExBudget.hs`
    * lines 201-203), when 200 have accumulated and when the machine returns its result ([[settle]];
    * `returnCek NoFrame`, `Internal.hs` lines 891-894). Steps counted before a failure are never
    * charged, as in the reference. The startup cost and each element of a builtin's budget
    * ([[ReferenceCosting.BudgetStream]]) are charged at once.
    */
  final class Steps(mode: BudgetSpender) extends BudgetSpender:
    private val counts = new Array[Int](kinds.size)
    private val costs = new Array[ScalusExUnits](kinds.size)
    private var unbudgeted = 0

    def spendBudget(category: ExBudgetCategory, budget: ScalusExUnits, env: CekValEnv): Unit =
      category match
        case ExBudgetCategory.Step(kind) =>
          val at = index(kind)
          counts(at) += 1
          costs(at) = budget
          unbudgeted += 1
          if unbudgeted >= Slippage then settle(env)
        case ExBudgetCategory.BuiltinApp(_) =>
          budget match
            case stream: ReferenceCosting.BudgetStream =>
              stream.elements.foreach((cpu, memory) =>
                mode.spendBudget(category, ScalusExUnits(memory.toLong, cpu.toLong), env)
              )
            case single => mode.spendBudget(category, single, env)
        case ExBudgetCategory.Startup => mode.spendBudget(category, budget, env)

    /** `spendAccumulatedBudget`: every kind counted since the last charge, in the reference's
      * order. A kind not counted would be charged zero, which changes nothing.
      */
    def settle(env: CekValEnv): Unit =
      kinds.indices.foreach { at =>
        if counts(at) > 0 then
          val times = SatInt(counts(at).toLong)
          val cost = costs(at)
          mode.spendBudget(
            ExBudgetCategory.Step(kinds(at)),
            ScalusExUnits(
              (times * SatInt(cost.memory)).toLong,
              (times * SatInt(cost.steps)).toLong
            ),
            env
          )
      }
      java.util.Arrays.fill(counts, 0)
      unbudgeted = 0

    def getSpentBudget: ScalusExUnits = mode.getSpentBudget

  /** `restricting` (`ExBudgetMode.hs` lines 126-172): the remaining budget in [[SatInt]], updated
    * before it is compared with zero, so a refusal keeps the final state; the budget used is the
    * limit minus what remains (`evaluateScriptRestricting`, `PlutusLedgerApi/Common/Eval.hs` of
    * plutus-ledger-api 1.70.0.0, lines 269-274), which is negative when the costs were.
    */
  final class Restricting(limit: ScalusExUnits) extends BudgetSpender:
    private var cpuLeft = SatInt(limit.steps)
    private var memoryLeft = SatInt(limit.memory)

    def spendBudget(category: ExBudgetCategory, budget: ScalusExUnits, env: CekValEnv): Unit =
      cpuLeft = cpuLeft - SatInt(budget.steps)
      memoryLeft = memoryLeft - SatInt(budget.memory)
      if cpuLeft < SatInt.Zero || memoryLeft < SatInt.Zero then
        throw new OutOfExBudgetError(limit, env)

    def getSpentBudget: ScalusExUnits =
      ScalusExUnits(
        (SatInt(limit.memory) - memoryLeft).toLong,
        (SatInt(limit.steps) - cpuLeft).toLong
      )
