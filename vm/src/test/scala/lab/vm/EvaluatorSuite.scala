// SPDX-License-Identifier: Apache-2.0
package lab.vm

import java.nio.file.{Files, Path}
import scalus.uplc.builtin.ByteString

class EvaluatorSuite extends munit.FunSuite:
  private val root =
    if Files.exists(Path.of("fixtures/plutus")) then Path.of(".") else Path.of("..")
  private def read(name: String): String = Files.readString(root.resolve(s"fixtures/plutus/$name"))
  private def resource(name: String): String =
    val stream = getClass.getResourceAsStream(s"/plutus-reference-e/$name")
    try new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
    finally stream.close()
  private val evaluator = new Evaluator(
    ReferenceParameters
      .parse(resource("cekMachineCostsE.json"), resource("builtinCostModelE.json"))
      .toOption
      .get
  )
  private val names = FixtureRegistry.names
  private def fixture(name: String): VectorInput = VectorInput(
    name,
    read(s"$name.uplc"),
    read(s"$name.uplc.expected"),
    read(s"$name.uplc.budget.expected")
  )

  names.foreach { name =>
    test(s"official $name result/budget and all budget boundaries") {
      val check = Conformance.check(fixture(name), evaluator).toOption.get
      assert(check.matched, check.detail)
    }
  }
  test("strict ifThenElse evaluates the unselected error argument") {
    evaluator.evaluate(fixture("ifThenElse-04").source) match
      case Outcome.EvaluationFailure("explicit-error", _) => ()
      case other                                          => fail(other.toString)
    assert(evaluator.evaluate(fixture("ifThenElse-03").source).isInstanceOf[Outcome.Success])
  }
  test("closure result and Data equality mismatches cannot pass") {
    for (source, other) <- Seq(
        ("ifThenElse-01", "ifThenElse-02"),
        ("equalsData-01", "equalsData-02"),
        ("chooseDataConstr", "chooseDataMap")
      )
    do
      val input = fixture(source).copy(expected = fixture(other).expected)
      assertEquals(Conformance.check(input, evaluator).map(_.matched), Right(false))
  }
  test("every registered term rejects byte mutation before parsing") {
    names.foreach { name =>
      assert(evaluator.evaluate(fixture(name).source + " ").isInstanceOf[Outcome.Unsupported])
    }
  }
  test("failure is arithmetic, never a Unit-return or ledger validation check") {
    evaluator.evaluate(fixture("divideInteger-zero").source) match
      case Outcome.EvaluationFailure("divideInteger-zero", _) => ()
      case other                                              => fail(other.toString)
    assert(evaluator.evaluate(fixture("addInteger-01").source).isInstanceOf[Outcome.Success])
  }
  test(
    "modified bytes, crypto, BLS constant, unreachable crypto and malformed input never reach parser"
  ) {
    val unknown = Vector(
      fixture("addInteger-01").source + " ",
      "(program 1.0.0 [(builtin sha2_256) (con bytestring #)])",
      "(program 1.0.0 (con bls12_381_G1_element 0x00))",
      "(program 1.0.0 (delay (builtin verifyEd25519Signature)))",
      "(program 1.0.0 [(lam x (con integer 1)) (delay (builtin bls12_381_G1_add))])",
      "not even UPLC",
      ""
    )
    unknown.foreach(s => assert(evaluator.evaluate(s).isInstanceOf[Outcome.Unsupported], s))
  }
  test("unsupported cannot satisfy an expected evaluation failure") {
    val input = fixture("divideInteger-zero").copy(source = "(program 1.0.0 (builtin sha2_256))")
    assertEquals(Conformance.check(input, evaluator).map(_.matched), Right(false))
  }
  test("mismatched result or budget fails conformance") {
    val input = fixture("addInteger-01")
    assertEquals(
      Conformance
        .check(input.copy(expected = fixture("addInteger-uncurried").expected), evaluator)
        .map(_.matched),
      Right(false)
    )
    assertEquals(
      Conformance
        .check(input.copy(budget = "({cpu: 181309\n| mem: 602})"), evaluator)
        .map(_.matched),
      Right(false)
    )
  }
  test("bad expectations and budget limits are structured errors") {
    assert(Conformance.expectedBudget("({cpu: 99999999999999999999| mem: 1})").isLeft)
    assert(Conformance.expectedBudget("oops").isLeft)
    assert(
      Conformance
        .check(fixture("addInteger-01").copy(budget = "evaluation failure"), evaluator)
        .isLeft
    )
    assert(
      Conformance
        .check(fixture("divideInteger-zero").copy(budget = "({cpu: 1| mem: 1})"), evaluator)
        .isLeft
    )
    for budget <- Seq(
        Budget(-1, 1),
        Budget(1, -1),
        Budget(Long.MaxValue, 1),
        Budget(1, Long.MaxValue)
      )
    do
      assert(
        evaluator
          .evaluate(fixture("addInteger-01").source, budget)
          .isInstanceOf[Outcome.InvalidInput]
      )
    assert(evaluator.evaluate("x" * 65537).isInstanceOf[Outcome.InvalidInput])
  }
  test("hash provider faults, unsupported capabilities and exhausted budgets remain distinct") {
    val params = ReferenceParameters
      .parse(resource("cekMachineCostsE.json"), resource("builtinCostModelE.json"))
      .toOption
      .get
    val source = fixture("blake2b_256-empty")
    val unsupported = new Evaluator(params, UnsupportedPlatform)
    assert(unsupported.evaluate(source.source).isInstanceOf[Outcome.Unsupported])
    val defective = new Evaluator(
      params,
      new RejectingPlatform {
        override def blake2b_256(input: ByteString): ByteString =
          throw new IllegalStateException("provider defect")
      }
    )
    assert(defective.evaluate(source.source).isInstanceOf[Outcome.InternalError])
    assert(defective.evaluate(source.source, Budget(0, 0)).isInstanceOf[Outcome.BudgetExhausted])
    val rejection = source.copy(expected = "evaluation failure", budget = "evaluation failure")
    assertEquals(Conformance.check(rejection, defective).map(_.matched), Right(false))
    assertEquals(Conformance.check(rejection, unsupported).map(_.matched), Right(false))
  }
  test("backend guard is typed and never returns false or dummy hashes") {
    intercept[UnsupportedBackend](UnsupportedPlatform.sha2_256(ByteString.empty))
    intercept[UnsupportedBackend](
      UnsupportedPlatform.verifyEd25519Signature(
        ByteString.empty,
        ByteString.empty,
        ByteString.empty
      )
    )
    intercept[UnsupportedBackend](UnsupportedPlatform.modPow(2, 3, 5))
  }
  test("runtime classpath excludes native artifacts and native libraries") {
    val loader = getClass.getClassLoader
    assertEquals(Option(loader.getResource("supranational/blst/P1.class")), None)
    assert(!System.getProperty("java.class.path").contains("blst-java"))
    assert(!System.getProperty("java.class.path").contains("scalus-secp256k1-jni"))
  }

  test("reference parameter bytes are pinned before deserialization") {
    val machine = resource("cekMachineCostsE.json")
    val builtin = resource("builtinCostModelE.json")
    assert(ReferenceParameters.parse(machine + " ", builtin).isLeft)
    assert(ReferenceParameters.parse(machine, builtin + " ").isLeft)
    assert(ReferenceParameters.parse("{}", "{}").isLeft)
    assert(ReferenceParameters.parse(machine, builtin).isRight)
  }
