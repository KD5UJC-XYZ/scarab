// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import munit.FunSuite
import scala.collection.mutable.ArrayBuffer
import scalus.cardano.ledger.ExUnits as ScalusExUnits
import scalus.cardano.onchain.plutus.prelude.List as PList
import scalus.uplc.{BuiltinSemanticsVariant, Constant, DefaultFun, Program}
import scalus.uplc.builtin.Data
import scalus.uplc.eval.{
  BudgetSpender,
  CekValEnv,
  CekValue,
  CostingInteger,
  ExBudgetCategory,
  ExpModCostingFunction,
  OutOfExBudgetError,
  RestrictingBudgetSpender,
  StepKind,
  TwoArguments,
  TwoVariableLinearFunction
}
import xyz.kd5ujc.scarab.codec.Bytes

/** Minimal reproducers of where Scalus 1.4.0's budget arithmetic leaves the reference's (plutus
  * 1.70.0.0, `507cab8c`), and the corrections the evaluator installs ([[ReferenceCosting]],
  * [[ReferenceBudget]]; issue 485). The reproducers assert Scalus's behaviour as it is, so an
  * upgrade that changes it fails here. The expected values of the corrections are derived from the
  * cited reference definitions; `CostModelLengthSuite` compares the whole evaluator with the
  * executed reference under extreme and mixed-sign cost models.
  */
class ReferenceBudgetSuite extends FunSuite:
  private val limit = ScalusExUnits(1L << 60, 1L << 60)
  private val none: CekValEnv = Vector.empty

  test("reproducer: Scalus's restricting spender wraps where the reference saturates") {
    // `restricting` (ExBudgetMode.hs lines 126-172) keeps the remaining budget in SatInt: spending
    // minBound from 2^60 leaves maxBound, which is not below zero.
    val scalus = RestrictingBudgetSpender(limit)
    intercept[OutOfExBudgetError](
      scalus.spendBudget(
        ExBudgetCategory.Startup,
        ScalusExUnits(Long.MinValue, Long.MinValue),
        none
      )
    )
    val reference = ReferenceBudget.Restricting(limit)
    reference.spendBudget(
      ExBudgetCategory.Startup,
      ScalusExUnits(Long.MinValue, Long.MinValue),
      none
    )
    val left = (1L << 60) - Long.MaxValue
    assertEquals(reference.getSpentBudget, ScalusExUnits(left, left))
  }

  test("the reference's remaining budget is compared after each charge and keeps its final state") {
    val reference = ReferenceBudget.Restricting(ScalusExUnits(10, 10))
    reference.spendBudget(ExBudgetCategory.Startup, ScalusExUnits(10, 4), none)
    intercept[OutOfExBudgetError](
      reference.spendBudget(ExBudgetCategory.Startup, ScalusExUnits(1, 1), none)
    )
    assertEquals(reference.getSpentBudget, ScalusExUnits(11, 5))
  }

  test("reproducer: Scalus's saturating product of -1 and Int64's minimum is the minimum") {
    // `timesSI` (Data/SatInt.hs lines 175-195): two negative factors saturate at maxBound.
    assertEquals(CostingInteger.satMul(-1L, Long.MinValue), Long.MinValue, "fixed upstream?")
    assertEquals((SatInt(-1) * SatInt.MinBound).toLong, Long.MaxValue)
    assertEquals((SatInt(Long.MinValue) * SatInt(-1)).toLong, Long.MaxValue)
    assertEquals((SatInt(3) * SatInt(Long.MinValue / 2)).toLong, Long.MinValue)
    assertEquals((SatInt(0) - SatInt.MinBound).toLong, Long.MaxValue)
    assertEquals((SatInt(-2) - SatInt.MaxBound).toLong, Long.MinValue)
    assertEquals((SatInt(-1) - SatInt.MaxBound).toLong, Long.MinValue)
    assertEquals((SatInt.MaxBound + SatInt(1)).toLong, Long.MaxValue)
    assertEquals((SatInt.MinBound + SatInt(-1)).toLong, Long.MinValue)
    assertEquals((SatInt.MinBound + SatInt.MaxBound).toLong, -1L)
    assertEquals(SatInt.fromBigInt(BigInt(2).pow(70)).toLong, Long.MaxValue)
    assertEquals(SatInt.fromBigInt(-BigInt(2).pow(70)).toLong, Long.MinValue)
  }

  test("reproducer: Scalus halves expModInteger's penalty with Java's division") {
    // `evaluateExpModCostingFunction` (Core.hs lines 460-479) adds `cost0 dividedBy 2`, Haskell's
    // `div`, which rounds -3 / 2 down to -2 (Data/SatInt.hs lines 104-108).
    val scalus = ExpModCostingFunction(
      CostingInteger(-3L),
      CostingInteger(0L),
      CostingInteger(0L)
    )(CostingInteger(2L), CostingInteger(1L), CostingInteger(1L))
    assertEquals(scalus.toLong, -4L, "fixed upstream?")
    val reference = ReferenceCosting.run(
      ReferenceCosting.Model.ExpMod(SatInt(-3), SatInt.Zero, SatInt.Zero),
      Vector(2L, 1L, 1L).map(n => ReferenceCosting.CostStream.last(SatInt(n)))
    )
    assertEquals(reference.sum.toLong, -5L)
    assertEquals(SatInt.MaxBound.dividedBy(2).toLong, Long.MaxValue)
    assertEquals(SatInt.MinBound.dividedBy(2).toLong, Long.MinValue)
  }

  test("reproducer: Scalus adds a two-variable linear cost in another order than the reference") {
    // `scaleLinearlyTwoVariables` (Core.hs lines 617-625): intercept + (slope1 x + slope2 y).
    val f = TwoVariableLinearFunction(
      CostingInteger(Long.MaxValue),
      CostingInteger(1L),
      CostingInteger(-1L)
    )
    val scalus = TwoArguments.LinearInXAndY(f)(CostingInteger(1L), CostingInteger(1L))
    assertEquals(scalus.toLong, Long.MaxValue - 1, "fixed upstream?")
    val reference = ReferenceCosting.run(
      ReferenceCosting.Model.LinearInTwo(0, 1, SatInt.MaxBound, SatInt(1), SatInt(-1)),
      Vector.fill(2)(ReferenceCosting.CostStream.last(SatInt(1)))
    )
    assertEquals(reference.sum.toLong, Long.MaxValue)
  }

  private def i(n: Int): Data = Data.I(BigInt(n))
  private def data(d: Data): CekValue = CekValue.VCon(Constant.Data(d))

  test("a Data argument is a stream of node costs, and equalsData charges the reference's stream") {
    // ExMemoryUsage Data (ExMemoryUsage.hs lines 382-397) flattened in pre-order (lines 132-159):
    // List [I 1, I 2] is 4, 5, 5 and I 7 is 5; `minCostStream` (CostStream.hs lines 110-132) of the
    // two is 4, 1; `scaleLinearly` (Core.hs lines 318-321) puts the intercept first, and the memory
    // stream, one constant, is continued with zeros (ExBudgetStream.hs lines 58-77).
    val costing = ReferenceCosting.BuiltinCosting(
      ReferenceCosting.signature(DefaultFun.EqualsData, BuiltinSemanticsVariant.E),
      ReferenceCosting.Model.MinSize(SatInt(1000), SatInt(-7)),
      ReferenceCosting.Model.ConstantCost(SatInt(1))
    )
    val budget =
      costing.calculateCost(data(Data.List(PList.from(List(i(1), i(2))))), data(i(7)))
    budget match
      case stream: ReferenceCosting.BudgetStream =>
        assertEquals(
          stream.elements.toVector.map((cpu, memory) => (cpu.toLong, memory.toLong)),
          Vector((1000L, 1L), (-28L, 0L), (-7L, 0L))
        )
      case other => fail(s"one budget: $other")
  }

  test("an argument the reference cannot unlift costs nothing") {
    // `toMonoF` (Builtin/Meaning.hs lines 245-262): an unlifting failure costs `mempty`.
    val costing = ReferenceCosting.BuiltinCosting(
      ReferenceCosting.signature(DefaultFun.IndexByteString, BuiltinSemanticsVariant.E),
      ReferenceCosting.Model.ConstantCost(SatInt(57667)),
      ReferenceCosting.Model.ConstantCost(SatInt(4))
    )
    val bytes = CekValue.VCon(Constant.ByteString(scalus.uplc.builtin.ByteString.empty))
    def index(n: BigInt) = CekValue.VCon(Constant.Integer(n))
    assertEquals(costing.calculateCost(bytes, index(0)), ScalusExUnits(4, 57667))
    assertEquals(costing.calculateCost(bytes, index(BigInt(2).pow(63))), ScalusExUnits(0, 0))
    assertEquals(costing.calculateCost(bytes, bytes), ScalusExUnits(0, 0))
  }

  test("a list measures in batches of 100 and a text in chunks of up to 100 code points") {
    // ExMemoryUsage.hs lines 221-231 (`dropN @100`) and 326-334 (`T.drop 100`).
    def list(n: Int) = CekValue.VCon(
      Constant.List(scalus.uplc.DefaultUni.Integer, List.fill(n)(Constant.Integer(1)))
    )
    def text(s: String) = CekValue.VCon(Constant.String(s))
    val linear = ReferenceCosting.BuiltinCosting(
      Vector(ReferenceCosting.Arg.ListOf),
      ReferenceCosting.Model.Linear(0, SatInt.Zero, SatInt(1)),
      ReferenceCosting.Model.ConstantCost(SatInt.Zero)
    )
    def cpu(value: CekValue) = linear.calculateCost(value) match
      case stream: ReferenceCosting.BudgetStream => stream.elements.toVector.map(_._1.toLong)
      case one                                   => Vector(one.steps)
    assertEquals(cpu(list(0)), Vector(0L))
    assertEquals(cpu(list(99)), Vector(99L))
    assertEquals(cpu(list(100)), Vector(0L, 100L, 0L))
    assertEquals(cpu(list(250)), Vector(0L, 100L, 100L, 50L))
    val texts = linear.copy(args = Vector(ReferenceCosting.Arg.Text))
    def textCpu(value: CekValue) = texts.calculateCost(value) match
      case stream: ReferenceCosting.BudgetStream => stream.elements.toVector.map(_._1.toLong)
      case one                                   => Vector(one.steps)
    assertEquals(textCpu(text("")), Vector(0L))
    assertEquals(textCpu(text("a" * 100)), Vector(100L))
    assertEquals(textCpu(text("a" * 201)), Vector(0L, 100L, 100L, 1L))
    // `T.length` counts code points: one astral character is one, not two UTF-16 units
    assertEquals(textCpu(text("😀")), Vector(1L))
  }

  /** A spender that records every charge. */
  private final class Recording extends BudgetSpender:
    val charges = ArrayBuffer.empty[(ExBudgetCategory, ScalusExUnits)]
    def spendBudget(category: ExBudgetCategory, budget: ScalusExUnits, env: CekValEnv): Unit =
      charges += category -> budget
    def getSpentBudget: ScalusExUnits = ScalusExUnits(0, 0)

  test("steps are charged per kind in the reference's order, 200 at a time, builtins at once") {
    // `stepAndMaybeSpend` and `spendAccumulatedBudget` (Internal.hs lines 1011-1043), slippage 200.
    val recording = Recording()
    val steps = ReferenceBudget.Steps(recording)
    val step = ScalusExUnits(100, 16000)
    steps.spendBudget(ExBudgetCategory.Startup, ScalusExUnits(100, 100), none)
    (1 to 150).foreach(_ => steps.spendBudget(ExBudgetCategory.Step(StepKind.Var), step, none))
    steps.spendBudget(
      ExBudgetCategory.BuiltinApp(DefaultFun.AddInteger),
      ScalusExUnits(1, 2),
      none
    )
    (1 to 49).foreach(_ => steps.spendBudget(ExBudgetCategory.Step(StepKind.Apply), step, none))
    assertEquals(recording.charges.size, 2)
    steps.spendBudget(ExBudgetCategory.Step(StepKind.Const), step, none)
    steps.spendBudget(ExBudgetCategory.Step(StepKind.Case), step, none)
    steps.settle(none)
    assertEquals(
      recording.charges.toVector,
      Vector(
        ExBudgetCategory.Startup -> ScalusExUnits(100, 100),
        ExBudgetCategory.BuiltinApp(DefaultFun.AddInteger) -> ScalusExUnits(1, 2),
        ExBudgetCategory.Step(StepKind.Const) -> ScalusExUnits(100, 16000),
        ExBudgetCategory.Step(StepKind.Var) -> ScalusExUnits(15000, 2400000),
        ExBudgetCategory.Step(StepKind.Apply) -> ScalusExUnits(4900, 784000),
        ExBudgetCategory.Step(StepKind.Case) -> ScalusExUnits(100, 16000)
      )
    )
  }

  test("a script failure before its steps are charged is the script's, as in the reference") {
    // The reference charges the counted steps only every 200 steps and on return; `error` throws
    // before that (`computeCek`, Internal.hs lines 878-880), so a budget that the steps exceed
    // still ends in the script's failure.
    val program = Program
      .parseUplc("(program 1.1.0 [ (lam x (error)) (con unit ()) ])")
      .fold(e => fail(e.toString), identity)
    val result = ScalusEvaluator.pure.evaluate(
      EvalRequest(
        PlutusLanguage.V3,
        11,
        Bytes.fromArray(program.cborEncoded),
        Vector.empty,
        PlutusConformanceSuite.costModel,
        ExUnits(BigInt(1000), BigInt(1000))
      )
    )
    result match
      case EvalResult.Failure(EvalFailure.Script(_), used, _) =>
        assertEquals(used, ExUnits(BigInt(100), BigInt(100)))
      case other => fail(s"the reference's script failure, got $other")
  }

  private def onSmallStack[A](body: => A): A =
    var result: Option[Either[Throwable, A]] = None
    val worker = Thread(
      null,
      () =>
        result = Some(
          try Right(body)
          catch case e: Throwable => Left(e)
        ),
      "small-stack",
      256L * 1024
    )
    worker.start()
    worker.join()
    result.getOrElse(fail("worker did not finish")).fold(throw _, identity)

  test("a Data argument 200,000 levels deep is measured on a small stack") {
    val deep = (1 to 200000).foldLeft(i(1))((inner, _) => Data.List(PList.from(List(inner))))
    val costing = ReferenceCosting.BuiltinCosting(
      ReferenceCosting.signature(DefaultFun.SerialiseData, BuiltinSemanticsVariant.E),
      ReferenceCosting.Model.Linear(0, SatInt.Zero, SatInt(1)),
      ReferenceCosting.Model.ConstantCost(SatInt.Zero)
    )
    val budget = onSmallStack(costing.calculateCost(data(deep)))
    assertEquals(budget.steps, 200000L * 4 + 5)
  }
