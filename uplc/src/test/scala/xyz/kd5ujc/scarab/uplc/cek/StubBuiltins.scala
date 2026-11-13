// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.cek

import cats.Eval
import xyz.kd5ujc.scarab.uplc.{Constant, DefaultFun, SemanticsVariant, Signature, Text}
import xyz.kd5ujc.scarab.uplc.builtin.*
import xyz.kd5ujc.scarab.uplc.builtin.core.{CoreDenotations, CoreUnlifting, UnsupportedVariant}
import xyz.kd5ujc.scarab.uplc.cost.{CostStream, ExBudget, MachineCosts}

/** A handful of builtins with simple denotations and fixed costs, for testing the machine before
  * the real builtins and costing exist. Not the reference's builtins: the unlifting checks only the
  * types of the [[Signature]], and the costs below are invented so that a budget can be derived by
  * hand.
  */
object StubBuiltins:
  /** Step costs that tell the kinds apart: kind `k` (in `StepKind` order) costs `100(k+1)` CPU and
    * `k+1` memory; the start-up costs 50 of each.
    */
  val distinctCosts: MachineCosts =
    MachineCosts(
      ExBudget.of(50, 50),
      kind => ExBudget.of(100L * (kind.ordinal + 1), kind.ordinal + 1)
    )

  /** The step costs of the cost models on the chain (every step 16000 CPU and 100 memory, the
    * start-up 100 of each; `cekMachineCostsC.json` of plutus 1.70.0.0).
    */
  val chainCosts: MachineCosts = MachineCosts(ExBudget.of(100, 100), _ => ExBudget.of(16000, 100))

  /** Every step and the start-up cost one CPU unit and no memory, so the CPU spent counts steps
    * (the start-up excluded).
    */
  val stepCounting: MachineCosts = MachineCosts(ExBudget.zero, _ => ExBudget.of(1, 0))

  /** The cost of each stub builtin: a single element, except `trace`, whose stream has two, so that
    * a budget can run out between them.
    */
  def costOf(function: DefaultFun): CostStream = function match
    case DefaultFun.AddInteger            => CostStream.single(ExBudget.of(1000, 10))
    case DefaultFun.SubtractInteger       => CostStream.single(ExBudget.of(1000, 10))
    case DefaultFun.LessThanEqualsInteger => CostStream.single(ExBudget.of(2000, 1))
    case DefaultFun.IfThenElse            => CostStream.single(ExBudget.of(3000, 1))
    case DefaultFun.DivideInteger         => CostStream.single(ExBudget.of(4000, 10))
    case DefaultFun.Trace => CostStream.of(ExBudget.of(1000, 10), ExBudget.of(5000, 10))
    case _                => CostStream.single(ExBudget.zero)

  /** Unlifting by the signature alone: an opaque parameter takes any value, a constant one a
    * constant (structural `NotAConstant` otherwise) of its type when the type is ground (structural
    * `WrongType` otherwise), the first failure winning.
    */
  val unlifting: Unlifting = new Unlifting:
    def apply[V](function: DefaultFun, arguments: Vector[V])(using
        mv: MachineValue[V]
    ): Either[UnliftingFailure, Vector[Argument[V]]] =
      val parameters = function.signature.parameters
      arguments.zipWithIndex
        .foldLeft[Either[UnliftingFailure, Vector[Argument[V]]]](Right(Vector.empty)) {
          case (Left(failure), _) => Left(failure)
          case (Right(done), (value, i)) =>
            parameters(i) match
              case Signature.Parameter.Opaque(_) => Right(done :+ Argument.Opaque(value))
              case Signature.Parameter.Constant(tpe) =>
                mv.constantOf(value) match
                  case None =>
                    Left(UnliftingFailure(ErrorClass.Structural, UnliftingCause.NotAConstant(i)))
                  case Some(c) if tpe.ground.forall(_ == c.typeOf) => Right(done :+ Argument.Con(c))
                  case Some(c) =>
                    Left(
                      UnliftingFailure(
                        ErrorClass.Structural,
                        UnliftingCause.WrongType(i, tpe, c.typeOf)
                      )
                    )
        }

  val denotations: Denotations = function =>
    new Denotation:
      def apply[V](arguments: Vector[Argument[V]])(using mv: MachineValue[V]): Outcome[V] =
        def integer(n: BigInt) = Outcome.Success(mv.ofConstant(Constant.Integer(n)), Vector.empty)
        (function, arguments) match
          case (
                DefaultFun.AddInteger,
                Vector(Argument.Con(Constant.Integer(a)), Argument.Con(Constant.Integer(b)))
              ) =>
            integer(a + b)
          case (
                DefaultFun.SubtractInteger,
                Vector(Argument.Con(Constant.Integer(a)), Argument.Con(Constant.Integer(b)))
              ) =>
            integer(a - b)
          case (
                DefaultFun.LessThanEqualsInteger,
                Vector(Argument.Con(Constant.Integer(a)), Argument.Con(Constant.Integer(b)))
              ) =>
            Outcome.Success(mv.ofConstant(Constant.Bool(a <= b)), Vector.empty)
          case (
                DefaultFun.IfThenElse,
                Vector(Argument.Con(Constant.Bool(c)), Argument.Opaque(t), Argument.Opaque(e))
              ) =>
            Outcome.Success(if c then t else e, Vector.empty)
          case (
                DefaultFun.Trace,
                Vector(Argument.Con(Constant.String(message)), Argument.Opaque(v))
              ) =>
            Outcome.Success(v, Vector(message.string))
          case (
                DefaultFun.DivideInteger,
                Vector(Argument.Con(Constant.Integer(_)), Argument.Con(Constant.Integer(b)))
              ) if b == 0 =>
            Outcome.failed(EvaluationFailure.DivisionByZero)
          case (
                DefaultFun.DivideInteger,
                Vector(Argument.Con(Constant.Integer(a)), Argument.Con(Constant.Integer(b)))
              ) =>
            integer(a / b)
          case _ => Outcome.failed(EvaluationFailure.EmptyList)

  val costing: Costing = function =>
    new CostingFunction:
      def apply[V](arguments: Vector[Argument[V]])(using MachineValue[V]): CostStream = costOf(
        function
      )

  val builtins: Builtins = Builtins(SemanticsVariant.E, unlifting, denotations, costing)

  /** The core builtins (`builtin.core`: their unlifting and denotations) of `variant`, costed by
    * [[costing]] until the cost model is in; a builtin outside the core keeps its stub meaning.
    */
  def core(variant: SemanticsVariant): Either[UnsupportedVariant, Builtins] =
    for
      coreUnlifting <- CoreUnlifting.of(variant)
      meanings <- CoreDenotations.of(variant)
    yield Builtins(variant, coreUnlifting, f => meanings.getOrElse(f, denotations(f)), costing)

  /** The stub builtins with `function` costing `stream`, built afresh for each application. */
  def withCost(function: DefaultFun, stream: () => CostStream): Builtins =
    builtins.copy(costing =
      f =>
        if f == function then
          new CostingFunction:
            def apply[V](arguments: Vector[Argument[V]])(using MachineValue[V]): CostStream =
              stream()
        else costing(f)
    )

  /** A stream that never ends: `budget` again and again, each one computed only when the one before
    * it is spent.
    */
  def endless(budget: ExBudget): CostStream = CostStream.Cons(budget, Eval.later(endless(budget)))

  def parameters(
      costs: MachineCosts,
      caser: CaserBuiltin = CaserBuiltin.WithoutData
  ): MachineParameters =
    MachineParameters(costs, builtins, caser)

  def string(s: String): Constant = Constant.String(Text.fromString(s))
