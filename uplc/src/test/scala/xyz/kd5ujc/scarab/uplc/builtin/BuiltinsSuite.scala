// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.builtin

import xyz.kd5ujc.scarab.uplc.{Constant, DefaultFun, DefaultUni, SemanticsVariant, Signature}
import xyz.kd5ujc.scarab.uplc.cost.{CostStream, ExBudget}

/** `Builtins.prepare` keeps the reference's order: unlift, then cost, then denote. */
final class BuiltinsSuite extends munit.FunSuite:
  private given MachineValue[Constant] with
    def constantOf(value: Constant): Option[Constant] = Some(value)
    def ofConstant(constant: Constant): Constant = constant

  private val refusing: Unlifting = new Unlifting:
    def apply[V](function: DefaultFun, arguments: Vector[V])(using
        MachineValue[V]
    ): Either[UnliftingFailure, Vector[Argument[V]]] =
      Left(
        UnliftingFailure(
          ErrorClass.Operational,
          UnliftingCause.WrongType(0, Signature.Type.Integer, DefaultUni.Bool)
        )
      )

  private val accepting: Unlifting = new Unlifting:
    def apply[V](function: DefaultFun, arguments: Vector[V])(using
        mv: MachineValue[V]
    ): Either[UnliftingFailure, Vector[Argument[V]]] =
      Right(arguments.map(a => mv.constantOf(a).fold(Argument.Opaque(a))(Argument.Con(_))))

  private val sum: Denotations = _ =>
    new Denotation:
      def apply[V](arguments: Vector[Argument[V]])(using mv: MachineValue[V]): Outcome[V] =
        val total = arguments.collect { case Argument.Con(Constant.Integer(n)) => n }.sum
        Outcome.Success(mv.ofConstant(Constant.Integer(total)), Vector.empty)

  private val perArgument: Costing = _ =>
    new CostingFunction:
      def apply[V](arguments: Vector[Argument[V]])(using MachineValue[V]): CostStream =
        CostStream.single(ExBudget.of(100L * arguments.size, 1))

  private val args = Vector[Constant](Constant.Integer(2), Constant.Integer(3))

  test("an argument that does not unlift makes the application cost nothing and fail") {
    val prepared =
      Builtins(SemanticsVariant.E, refusing, sum, perArgument).prepare(DefaultFun.AddInteger, args)
    assertEquals(prepared.cost, CostStream.free)
    prepared.result() match
      case Outcome.Failure(BuiltinFailure.Unlifting(failure), logs) =>
        assertEquals(failure.errorClass, ErrorClass.Operational, "error class")
        assertEquals(logs, Vector.empty, "logs")
      case other => fail(s"expected an unlifting failure, got $other")
  }

  test("unlifted arguments are costed, and the denotation's result is the application's") {
    val prepared =
      Builtins(SemanticsVariant.E, accepting, sum, perArgument).prepare(DefaultFun.AddInteger, args)
    assertEquals(
      prepared.cost,
      CostStream.single(ExBudget.of(200, 1)),
      "cost from the unlifted arguments"
    )
    assertEquals(
      prepared.result(),
      Outcome.Success[Constant](Constant.Integer(5), Vector.empty),
      "result"
    )
  }
