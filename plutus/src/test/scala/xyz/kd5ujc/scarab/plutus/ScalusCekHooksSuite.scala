// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import io.circe.Json
import munit.FunSuite
import scala.collection.mutable.ArrayBuffer
import scalus.cardano.ledger.{ExUnits as ScalusUnits, Language, MajorProtocolVersion}
import scalus.uplc.{
  BuiltinRuntime,
  BuiltinSemanticsVariant,
  CardanoBuiltins,
  Constant,
  DefaultFun,
  Term
}
import scalus.uplc.builtin.platform
import scalus.uplc.eval.{
  BudgetSpender,
  CekMachine,
  CekValEnv,
  CekValue,
  ExBudgetCategory,
  Log,
  MachineError,
  MachineParams,
  NoBudgetSpender,
  NoLogger,
  OutOfExBudgetError,
  RestrictingBudgetSpender
}

/** These hooks expose native charging points; production budgeting is deliberately unchanged here.
  */
class ScalusCekHooksSuite extends FunSuite:
  import ScalusDefaultMachineControl.*

  test("the result hook runs before discharging an arbitrarily deep delayed result") {
    val body = (0 until 100000).foldLeft(integer(1))((term, _) => Term.Delay(term))
    val refusal = new OutOfExBudgetError(ScalusUnits(0, 0), Vector.empty)
    val machine = new CekMachine(params, NoBudgetSpender, NoLogger, builtins.getBuiltinRuntime):
      override protected def beforeResultDischarge(env: CekValEnv): Unit = throw refusal
    val observed = intercept[OutOfExBudgetError](machine.evaluateTerm(Term.Delay(body)))
    assert(observed eq refusal)
  }

  test("the builtin hook precedes costing and denotation and retains the original refusal") {
    val calls = ArrayBuffer.empty[DefaultFun]
    val refusal = new OutOfExBudgetError(ScalusUnits(0, 0), Vector.empty)
    val machine = new CekMachine(params, NoBudgetSpender, NoLogger, builtins.getBuiltinRuntime):
      override protected def spendBuiltinBudget(
          builtin: DefaultFun,
          runtime: BuiltinRuntime,
          args: List[CekValue],
          env: CekValEnv
      ): Unit =
        calls += builtin
        throw refusal
    val observed = intercept[OutOfExBudgetError](machine.evaluateTerm(divideByZero))
    assert(observed eq refusal)
    assertEquals(calls.toVector, Vector(DefaultFun.DivideInteger))
  }

  test("partial builtins do not invoke the saturated builtin hook") {
    var charges = 0
    var returns = 0
    val machine = new CekMachine(params, NoBudgetSpender, NoLogger, builtins.getBuiltinRuntime):
      override protected def beforeResultDischarge(env: CekValEnv): Unit = returns += 1
      override protected def spendBuiltinBudget(
          builtin: DefaultFun,
          runtime: BuiltinRuntime,
          args: List[CekValue],
          env: CekValEnv
      ): Unit =
        charges += 1
        super.spendBuiltinBudget(builtin, runtime, args, env)
    val term = Term.Apply(Term.Builtin(DefaultFun.AddInteger), integer(1))
    val result = machine.evaluateTerm(term)
    assertEquals(result, term)
    assertEquals(charges, 0)
    assertEquals(returns, 1)
  }

  test("default machine controls cover success, builtin failure, logs and budget refusal") {
    val observations = controls.map { case (name, term, limit) => observe(name, term, limit) }
    assertEquals(observations.size, 7)
    assert(observations.forall(_.hcursor.get[Vector[Json]]("charges").exists(_.nonEmpty)))
    assert(observations.exists(_.hcursor.get[Vector[String]]("logs").contains(Vector("control"))))
  }

/** Run unchanged against the patched and original 1.4.0 jars in separate JVMs and compare JSON.
  * This class uses only the original public API, so the original jar must link without the hooks.
  */
object ScalusDefaultMachineControl:
  private[plutus] val params =
    MachineParams.defaultParamsFor(Language.PlutusV3, MajorProtocolVersion(11))
  private val variant =
    BuiltinSemanticsVariant.fromProtocolAndPlutusVersion(
      MajorProtocolVersion(11),
      Language.PlutusV3
    )
  private[plutus] val builtins = CardanoBuiltins(params.builtinCostModel, platform, variant)
  private[plutus] def integer(value: Int): Term = Term.Const(Constant.Integer(BigInt(value)))
  private[plutus] val divideByZero =
    Term.Apply(Term.Apply(Term.Builtin(DefaultFun.DivideInteger), integer(1)), integer(0))
  private val ample = ScalusUnits(1L << 60, 1L << 60)
  private[plutus] val controls = Vector(
    ("constant", integer(42), ample),
    (
      "addition",
      Term.Apply(Term.Apply(Term.Builtin(DefaultFun.AddInteger), integer(5)), integer(7)),
      ample
    ),
    ("partial", Term.Apply(Term.Builtin(DefaultFun.AddInteger), integer(5)), ample),
    ("divide-zero", divideByZero, ample),
    ("error", Term.Error(), ample),
    (
      "trace",
      Term.Apply(
        Term.Apply(
          Term.Force(Term.Builtin(DefaultFun.Trace)),
          Term.Const(Constant.String("control"))
        ),
        integer(42)
      ),
      ample
    ),
    ("budget", integer(42), ScalusUnits(0, 0))
  )

  private[plutus] def observe(name: String, term: Term, limit: ScalusUnits): Json =
    val charges = ArrayBuffer.empty[Json]
    val mode = RestrictingBudgetSpender(limit)
    val spender = new BudgetSpender:
      def spendBudget(category: ExBudgetCategory, budget: ScalusUnits, env: CekValEnv): Unit =
        charges += Json.arr(
          Json.fromString(category.toString),
          Json.fromLong(budget.memory),
          Json.fromLong(budget.steps)
        )
        mode.spendBudget(category, budget, env)
      def getSpentBudget: ScalusUnits = mode.getSpentBudget
    val log = Log()
    val machine = CekMachine(params, spender, log, builtins.getBuiltinRuntime)
    val result =
      try Json.obj("term" -> Json.fromString(machine.evaluateTerm(term).toString))
      catch
        case error: MachineError =>
          Json.obj(
            "failure" -> Json.fromString(error.getClass.getSimpleName),
            "message" -> Option(error.getMessage).fold(Json.Null)(Json.fromString)
          )
    Json.obj(
      "name" -> Json.fromString(name),
      "result" -> result,
      "charges" -> Json.fromValues(charges),
      "logs" -> Json.fromValues(log.getLogs.toVector.map(Json.fromString)),
      "spent" -> Json.arr(
        Json.fromLong(mode.getSpentBudget.memory),
        Json.fromLong(mode.getSpentBudget.steps)
      )
    )

  def main(args: Array[String]): Unit =
    println(
      Json
        .fromValues(controls.map { case (name, term, limit) => observe(name, term, limit) })
        .noSpaces
    )
