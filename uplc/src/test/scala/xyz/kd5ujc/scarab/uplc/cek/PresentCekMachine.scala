// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.cek

import scala.annotation.tailrec
import xyz.kd5ujc.scarab.uplc.{Fix, Term, TermF}
import xyz.kd5ujc.scarab.uplc.builtin.{Builtins, Outcome}
import xyz.kd5ujc.scarab.uplc.cost.{CostStream, ExBudget, SatInt, StepKind}
import xyz.kd5ujc.scarab.uplc.cek.CekValue.{VBuiltin, VCon, VConstr, VDelay, VLamAbs}

/** The machine as it was before a saturated builtin application read its arguments as the machine
  * holds them (#1411): [[CekMachine]] at `929f794a6`, kept whole as the oracle of
  * [[CekApplicationSuite]]. Each saturated application goes through [[Builtins.prepare]]: the
  * arguments in order as a vector, the unlifted ones behind an `Either`, the cost stream and the
  * result as a closure.
  */
object PresentCekMachine:
  /** `defaultSlippage` (`Cek/Internal.hs:420-421`, plutus 1.70.0.0), which `runCekM` sets as
    * `?cekSlippage` (`Cek/Internal.hs:798`): the machine spends its counted steps once they reach
    * this many.
    */
  val Slippage: Int = CekMachine.Slippage

  /** `runCekDeBruijn` (`Cek/Internal.hs:1093-1103`, plutus 1.70.0.0): spend the start-up cost, then
    * compute the term in the empty environment with no frame. Logs are always kept (`logEmitter`).
    */
  def run(parameters: MachineParameters, mode: BudgetMode, term: Term): CekReport =
    Run(parameters, mode, Stop.never).start(term)

  /** What a transition outside the loop gives back: the next state, or the end. */
  private enum Step:
    case Next(
        ctx: Context,
        env: Env[CekValue],
        control: Term | CekValue,
        budget: ExBudget,
        logs: List[String]
    )
    case Halt(report: CekReport)

  /** The step counter (`Cek/StepCounter.hs`; Note [Structure of the step counter],
    * `Cek/Internal.hs:381-388`, plutus 1.70.0.0): one counter per [[StepKind]] and the total, each
    * below [[Slippage]], so a byte each. The counters of the first eight kinds are the bytes of a
    * `Long` (kind `k` at bits `8k`); that of the ninth, `Case`, is the low byte of an `Int` whose
    * second byte is the total.
    */
  private val LowKinds = 8
  private val TotalIncrement = 1 << 8

  /** One evaluation: the parameters every transition reads. */
  private final class Run[S](parameters: MachineParameters, mode: BudgetMode, stop: Stop[S]):
    private val builtins: Builtins = parameters.builtins
    private val caser: CaserBuiltin = parameters.caser
    private val stepCosts: Vector[ExBudget] = StepKind.values.toVector.map(parameters.costs.step)
    private val restricting: Boolean = mode match
      case BudgetMode.Restricting(_) => true
      case BudgetMode.Counting       => false

    def start(term: Term): CekReport | S =
      val initial = mode match
        case BudgetMode.Restricting(limit) => limit
        case BudgetMode.Counting           => ExBudget.zero
      // `unCekBudgetSpender ?cekBudgetSpender BStartup` (`Cek/Internal.hs:1102`, plutus 1.70.0.0)
      val started = spend(initial, parameters.costs.startup)
      if exhausted(started) then outOfBudget(started, Nil)
      else loop(Context.NoFrame, Env.empty, term, started, 0L, 0, Nil)

    /** The transitions. `control` is the term being computed (`computeCek`) or the value being
      * returned (`returnCek`); `low` and `high` are the step counter; `logs` the logs so far, the
      * last one first.
      */
    @tailrec
    private def loop(
        ctx: Context,
        env: Env[CekValue],
        control: Term | CekValue,
        budget: ExBudget,
        low: Long,
        high: Int,
        logs: List[String]
    ): CekReport | S =
      control match
        case value: CekValue =>
          ctx match
            // `returnCek NoFrame` (`Cek/Internal.hs:892-894`, plutus 1.70.0.0)
            case Context.NoFrame => finish(value, budget, low, high, logs)

            // `forceEvaluate` (`Cek/Internal.hs:958-977`, plutus 1.70.0.0)
            case Context.Force(next) =>
              value match
                case VDelay(body, closure) => loop(next, closure, body, budget, low, high, logs)
                case b: VBuiltin if b.expectsForce && !b.saturatedByForce =>
                  loop(next, env, b.forced, budget, low, high, logs)
                case _ =>
                  forceOther(next, value, budget, logs) match
                    case Step.Next(c, e, x, b, l) => loop(c, e, x, b, low, high, l)
                    case Step.Halt(report)        => report

            // `returnCek (FrameAwaitFunTerm ..)` (`Cek/Internal.hs:898-899`, plutus 1.70.0.0)
            case Context.AwaitFunTerm(argEnv, argument, next) =>
              loop(Context.AwaitArg(value, next), argEnv, argument, budget, low, high, logs)

            // `returnCek (FrameAwaitArg ..)`, `applyEvaluate` (`Cek/Internal.hs:903-904,986-1008`,
            // plutus 1.70.0.0)
            case Context.AwaitArg(function, next) =>
              function match
                case VLamAbs(body, closure) =>
                  loop(next, closure.cons(value), body, budget, low, high, logs)
                case b: VBuiltin if b.expectsArgument && !b.saturatedByArgument =>
                  loop(next, env, b.applied(value), budget, low, high, logs)
                case _ =>
                  applyOther(next, function, value, budget, logs) match
                    case Step.Next(c, e, x, b, l) => loop(c, e, x, b, low, high, l)
                    case Step.Halt(report)        => report

            // `returnCek (FrameAwaitFunValueN ..)` and `(FrameAwaitFunConN ..)`
            // (`Cek/Internal.hs:906-921`, plutus 1.70.0.0): the first argument is applied, the rest
            // wait.
            case Context.AwaitFunValues(first, rest, after) =>
              val next = rest match
                case Nil       => after
                case h :: tail => Context.AwaitFunValues(h, tail, after)
              value match
                case VLamAbs(body, closure) =>
                  loop(next, closure.cons(first), body, budget, low, high, logs)
                case _ =>
                  applyOther(next, value, first, budget, logs) match
                    case Step.Next(c, e, x, b, l) => loop(c, e, x, b, low, high, l)
                    case Step.Halt(report)        => report

            // `returnCek (FrameConstr ..)` (`Cek/Internal.hs:923-929`, plutus 1.70.0.0)
            case Context.Constr(fieldEnv, tag, fields, todo, done, next) =>
              if todo < fields.size then
                loop(
                  Context.Constr(fieldEnv, tag, fields, todo + 1, value :: done, next),
                  fieldEnv,
                  fields(todo),
                  budget,
                  low,
                  high,
                  logs
                )
              else loop(next, env, VConstr(tag, (value :: done).reverse), budget, low, high, logs)

            // `returnCek (FrameCases ..)` (`Cek/Internal.hs:931-950`, plutus 1.70.0.0)
            case Context.Cases(branchEnv, branches, next) =>
              value match
                case VConstr(tag, fields) =>
                  // A tag of 2^63 or more is a negative `Long`, past every branch
                  // (`Cek/Internal.hs:936-938`, plutus 1.70.0.0).
                  val i = tag.bits
                  if i >= 0L && i < branches.size then
                    fields match
                      case Nil =>
                        loop(next, branchEnv, branches(i.toInt), budget, low, high, logs)
                      case h :: tail =>
                        loop(
                          Context.AwaitFunValues(h, tail, next),
                          branchEnv,
                          branches(i.toInt),
                          budget,
                          low,
                          high,
                          logs
                        )
                  else fail(CekError.MissingCaseBranch(tag), Some(Cause.of(value)), budget, logs)
                case VCon(constant) =>
                  caser.select(constant, branches) match
                    case CaserBuiltin.Selection.Branch(branch) =>
                      loop(next, branchEnv, branch, budget, low, high, logs)
                    case CaserBuiltin.Selection.Applied(branch, h :: tail) =>
                      loop(
                        Context.AwaitFunValues(VCon(h), tail.map(VCon(_)), next),
                        branchEnv,
                        branch,
                        budget,
                        low,
                        high,
                        logs
                      )
                    // `headSpine h [] = HeadOnly h` (`PlutusCore/Builtin/KnownType.hs:355`,
                    // plutus 1.70.0.0)
                    case CaserBuiltin.Selection.Applied(branch, Nil) =>
                      loop(next, branchEnv, branch, budget, low, high, logs)
                    case CaserBuiltin.Selection.Refused(error) =>
                      fail(CekError.CaseBuiltin(error), Some(Cause.of(value)), budget, logs)
                case _ =>
                  fail(CekError.NonConstrScrutinized, Some(Cause.of(value)), budget, logs)

        case term: Fix[TermF] =>
          val layer = term.unfix
          val kind = kindOf(layer)
          // `computeCek` on `error` throws before it counts a step (`Cek/Internal.hs:874-875`,
          // plutus 1.70.0.0).
          if kind < 0 then fail(CekError.ErrorTerm, Some(Cause(Term.error)), budget, logs)
          else
            // `stepAndMaybeSpend` (`Cek/Internal.hs:1030-1043`, plutus 1.70.0.0): count the step
            // and, once the total reaches the slippage, spend every counter before the step acts.
            val counted = if kind < LowKinds then low + (1L << (kind << 3)) else low
            val tallied =
              if kind < LowKinds then high + TotalIncrement else high + TotalIncrement + 1
            val due = (tallied >>> 8) >= Slippage
            val spent = if due then flush(budget, counted, tallied) else budget
            if due && exhausted(spent) then outOfBudget(spent, logs)
            else
              // a stop is asked for once the counted steps are spent, so at most every `Slippage`
              // steps; it changes no charge
              (if due then stop() else None) match
                case Some(reason) => reason
                case None =>
                  val low1 = if due then 0L else counted
                  val high1 = if due then 0 else tallied
                  layer match
                    // `Cek/Internal.hs:833-836` of plutus 1.70.0.0, `lookupVarName` (`1082-1088`)
                    case TermF.Var(index) =>
                      env.indexOne(index.index.bits) match
                        case found: Env.Tree[CekValue] =>
                          loop(ctx, env, found.value, spent, low1, high1, logs)
                        case Env.Absent =>
                          fail(
                            CekError.OpenTermEvaluated(index),
                            Some(Cause(Term.variable(index))),
                            spent,
                            logs
                          )
                    // `Cek/Internal.hs:838-840` of plutus 1.70.0.0
                    case TermF.Constant(c) => loop(ctx, env, VCon(c), spent, low1, high1, logs)
                    // `Cek/Internal.hs:842-844` of plutus 1.70.0.0
                    case TermF.LamAbs(body) =>
                      loop(ctx, env, VLamAbs(body, env), spent, low1, high1, logs)
                    // `Cek/Internal.hs:846-848` of plutus 1.70.0.0
                    case TermF.Delay(body) =>
                      loop(ctx, env, VDelay(body, env), spent, low1, high1, logs)
                    // `Cek/Internal.hs:850-852` of plutus 1.70.0.0
                    case TermF.Force(body) =>
                      loop(Context.Force(ctx), env, body, spent, low1, high1, logs)
                    // `Cek/Internal.hs:854-856` of plutus 1.70.0.0
                    case TermF.Apply(function, argument) =>
                      loop(
                        Context.AwaitFunTerm(env, argument, ctx),
                        env,
                        function,
                        spent,
                        low1,
                        high1,
                        logs
                      )
                    // `Cek/Internal.hs:858-862` of plutus 1.70.0.0
                    case TermF.Builtin(function) =>
                      loop(ctx, env, VBuiltin.of(function), spent, low1, high1, logs)
                    // `Cek/Internal.hs:864-868` of plutus 1.70.0.0
                    case TermF.Constr(tag, fields) =>
                      if fields.isEmpty then
                        loop(ctx, env, VConstr(tag, Nil), spent, low1, high1, logs)
                      else
                        loop(
                          Context.Constr(env, tag, fields, 1, Nil, ctx),
                          env,
                          fields(0),
                          spent,
                          low1,
                          high1,
                          logs
                        )
                    // `Cek/Internal.hs:870-872` of plutus 1.70.0.0
                    case TermF.Case(scrutinee, branches) =>
                      loop(
                        Context.Cases(env, branches, ctx),
                        env,
                        scrutinee,
                        spent,
                        low1,
                        high1,
                        logs
                      )
                    case TermF.Error =>
                      fail(CekError.ErrorTerm, Some(Cause(Term.error)), budget, logs)

    /** The step kind of a term (`StepKind`, `Cek/Internal.hs:208-217`, plutus 1.70.0.0), -1 for
      * `error`, which is not a step.
      */
    private def kindOf(layer: TermF[Term]): Int = layer match
      case TermF.Var(_)       => StepKind.Variable.ordinal
      case TermF.Apply(_, _)  => StepKind.Apply.ordinal
      case TermF.LamAbs(_)    => StepKind.LamAbs.ordinal
      case TermF.Force(_)     => StepKind.Force.ordinal
      case TermF.Delay(_)     => StepKind.Delay.ordinal
      case TermF.Constant(_)  => StepKind.Constant.ordinal
      case TermF.Builtin(_)   => StepKind.Builtin.ordinal
      case TermF.Case(_, _)   => StepKind.Case.ordinal
      case TermF.Constr(_, _) => StepKind.Constr.ordinal
      case TermF.Error        => -1

    /** `forceEvaluate` of anything but a `delay` and a builtin that takes the `force` unsaturated
      * (`Cek/Internal.hs:963-977`, plutus 1.70.0.0).
      */
    private def forceOther(
        next: Context,
        value: CekValue,
        budget: ExBudget,
        logs: List[String]
    ): Step = value match
      case b: VBuiltin if b.expectsForce => evalBuiltinApp(next, b.forced, budget, logs)
      case b: VBuiltin =>
        Step.Halt(
          fail(
            CekError.BuiltinTermArgumentExpected,
            Some(Cause(Term.force(CekValue.discharge(b)))),
            budget,
            logs
          )
        )
      case _ =>
        Step.Halt(fail(CekError.NonPolymorphicInstantiation, Some(Cause.of(value)), budget, logs))

    /** `applyEvaluate` of anything but a `lam` (`Cek/Internal.hs:995-1008`, plutus 1.70.0.0). */
    private def applyOther(
        next: Context,
        function: CekValue,
        argument: CekValue,
        budget: ExBudget,
        logs: List[String]
    ): Step = function match
      case b: VBuiltin if b.expectsArgument =>
        evalBuiltinApp(next, b.applied(argument), budget, logs)
      case b: VBuiltin =>
        Step.Halt(
          fail(
            CekError.UnexpectedBuiltinTermArgument,
            Some(Cause(CekValue.dischargeApplied(b, argument))),
            budget,
            logs
          )
        )
      case _ =>
        Step.Halt(fail(CekError.NonFunctionalApplication, Some(Cause.of(function)), budget, logs))

    /** `evalBuiltinApp` (`Cek/Internal.hs:1052-1075`, plutus 1.70.0.0): a partial application is a
      * value; a saturated one spends its cost, element by element, before its result is computed,
      * then emits the result's logs and returns its value or fails.
      */
    private def evalBuiltinApp(
        next: Context,
        b: VBuiltin,
        budget: ExBudget,
        logs: List[String]
    ): Step =
      if !b.saturated then Step.Next(next, Env.empty, b, budget, logs)
      else
        val prepared = builtins.prepare(b.function, b.inOrder)
        val spent = spendStream(budget, prepared.cost)
        if exhausted(spent) then Step.Halt(outOfBudget(spent, logs))
        else
          prepared.result() match
            case Outcome.Success(value, emitted) =>
              Step.Next(next, Env.empty, value, spent, emit(logs, emitted))
            case Outcome.Failure(failure, emitted) =>
              Step.Halt(
                fail(
                  CekError.Builtin(b.function, failure),
                  Some(Cause.of(b)),
                  spent,
                  emit(logs, emitted)
                )
              )

    /** `spendBudgets` (`Cek/Internal.hs:1061-1064`, plutus 1.70.0.0): each element in order, the
      * rest of the stream forced only once the element before it is spent, stopping at the element
      * that overspends; the rest of the stream after it is never computed.
      */
    private def spendStream(budget: ExBudget, cost: CostStream): ExBudget =
      @tailrec def go(b: ExBudget, stream: CostStream): ExBudget = stream match
        case CostStream.Last(element) => spend(b, element)
        case CostStream.Cons(element, rest) =>
          val after = spend(b, element)
          if exhausted(after) then after else go(after, rest.value)
      go(budget, cost)

    /** `spendAccumulatedBudget` (`Cek/Internal.hs:1011-1027`, plutus 1.70.0.0): for each
      * [[StepKind]] in order, its count times its cost, stopping at the kind that overspends.
      */
    private def flush(budget: ExBudget, low: Long, high: Int): ExBudget =
      @tailrec def go(b: ExBudget, kind: Int): ExBudget =
        if kind >= stepCosts.size then b
        else
          val count =
            if kind < LowKinds then (low >>> (kind << 3)) & 0xffL else (high & 0xff).toLong
          val after = spend(b, stepCosts(kind).times(count))
          if exhausted(after) then after else go(after, kind + 1)
      go(budget, 0)

    /** The spender of the mode (`ExBudgetMode.hs:55-57,146-165`, plutus 1.70.0.0). */
    private def spend(budget: ExBudget, cost: ExBudget): ExBudget =
      if restricting then ExBudget(budget.cpu - cost.cpu, budget.memory - cost.memory)
      else budget + cost

    /** A restricting budget is negative only once a charge has overspent it, which fails the
      * evaluation (`ExBudgetMode.hs:155-165`, plutus 1.70.0.0).
      */
    private def exhausted(budget: ExBudget): Boolean =
      restricting && (budget.cpu < SatInt.zero || budget.memory < SatInt.zero)

    /** `returnCek NoFrame` (`Cek/Internal.hs:892-894`, plutus 1.70.0.0): spend the counted steps,
      * then the value is the result.
      */
    private def finish(
        value: CekValue,
        budget: ExBudget,
        low: Long,
        high: Int,
        logs: List[String]
    ): CekReport =
      val settled = flush(budget, low, high)
      if exhausted(settled) then outOfBudget(settled, logs)
      else
        val result = value match
          case VCon(constant) => CekResult.SuccessConstant(constant)
          case other          => CekResult.SuccessNonConstant(other)
        CekReport(result, state(settled), logs.reverse.toVector)

    private def fail(
        error: CekError,
        cause: Option[Cause],
        budget: ExBudget,
        logs: List[String]
    ): CekReport =
      CekReport(CekResult.Failure(CekFailure(error, cause)), state(budget), logs.reverse.toVector)

    private def outOfBudget(budget: ExBudget, logs: List[String]): CekReport =
      fail(CekError.OutOfBudget(budget), None, budget, logs)

    private def state(budget: ExBudget): BudgetState = mode match
      case BudgetMode.Restricting(limit) => BudgetState.Restricted(limit, budget)
      case BudgetMode.Counting           => BudgetState.Counted(budget)

    private def emit(logs: List[String], emitted: Vector[String]): List[String] =
      emitted.foldLeft(logs)((acc, line) => line :: acc)
