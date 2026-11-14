// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.cek

import io.circe.parser
import java.nio.file.Files
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.uplc.{Constant, DefaultUni, PlutusData, ReferenceCorpus, Term}
import xyz.kd5ujc.scarab.uplc.cost.{ExBudget, MachineCosts, StepKind}
import xyz.kd5ujc.scarab.uplc.cek.Terms.*

/** The evaluation cases of the conformance corpus of plutus 1.70.0.0 under `term/` that use no
  * builtin, transcribed by hand to de Bruijn terms (the text parser and the corpus harness are
  * another package's), against the corpus's own expected results and budgets: the budgets are read
  * from the `.budget.expected` files, the machine costs from `cekMachineCostsE.json`, and the caser
  * is the conformance executable's, with `Data` (`CekEvaluator.hs:46-61`). Cases that the parser or
  * decoder refuses (`parse/decode error`) are not the machine's and are not here; the test fails if
  * a builtin-free evaluation case of the corpus is missing from the table.
  */
final class CekTermCorpusSuite extends munit.FunSuite:
  private val corpus = ReferenceCorpus.view("1.70.0.0")
  private def read(name: String): String = Files.readString(corpus(name))

  private val costs: MachineCosts =
    val json =
      parser.parse(read("cost-model/cekMachineCostsE.json")).fold(e => fail(e.toString), identity)
    def budget(field: String): ExBudget =
      val c = json.hcursor.downField(field)
      ExBudget.of(
        c.get[Long]("exBudgetCPU").fold(e => fail(e.toString), identity),
        c.get[Long]("exBudgetMemory").fold(e => fail(e.toString), identity)
      )
    val fields = Map(
      StepKind.Constant -> "cekConstCost",
      StepKind.Variable -> "cekVarCost",
      StepKind.LamAbs -> "cekLamCost",
      StepKind.Apply -> "cekApplyCost",
      StepKind.Delay -> "cekDelayCost",
      StepKind.Force -> "cekForceCost",
      StepKind.Builtin -> "cekBuiltinCost",
      StepKind.Constr -> "cekConstrCost",
      StepKind.Case -> "cekCaseCost"
    ).view.mapValues(budget).toMap
    MachineCosts(budget("cekStartupCost"), fields)

  private val parameters = MachineParameters(costs, StubBuiltins.builtins, CaserBuiltin.WithData)

  /** The expected value, or none for `evaluation failure`. */
  private final case class Case(stem: String, term: Term, expected: Option[Term])

  private def list(elementType: DefaultUni, items: Constant*): Constant =
    Constant.List.of(elementType, items.toVector).fold(e => fail(e.toString), identity)
  private val numbers = con(list(DefaultUni.Integer, (1 to 4).map(Constant.Integer(_))*))
  private val noNumbers = con(list(DefaultUni.Integer))
  private val pair = con(Constant.Pair(Constant.Integer(42), Constant.Bool(false)))
  private val abcd = Bytes.fromHex("abcd").fold(e => fail(e.toString), identity)
  private val dataFields =
    Vector(PlutusData.I(42), PlutusData.B(abcd), PlutusData.List(Vector(PlutusData.I(7))))
  private val identity1 = lam(v(1))
  private val first = lam(lam(v(2)))
  private val second = lam(lam(v(1)))

  private val cases: Vector[Case] = Vector(
    Case("app/app-1/app-1", app(identity1, unit), Some(unit)),
    Case("app/app-2/app-2", app(identity1, int(0)), Some(int(0))),
    Case("app/app-3/app-3", app(lam(bool(false)), int(42)), Some(bool(false))),
    Case("app/app-4/app-4", app(identity1, int(42)), Some(int(42))),
    Case("app/app-5/app-5", app(app(identity1, identity1), int(42)), Some(int(42))),
    Case("app/app-6/app-6", app(identity1, identity1), Some(identity1)),
    Case("app/app-7/app-7", app(first, int(42)), Some(lam(int(42)))),
    Case("app/app-8/app-8", app(first, int(42), bool(false)), Some(int(42))),
    Case(
      "app/app-9/app-9",
      app(lam(lam(lam(app(v(3), v(2), v(1))))), first, bool(false), bool(true)),
      Some(bool(false))
    ),
    Case("case/case-01/case-01", caseOf(constr(0, int(0)), lam(int(1)), lam(int(2))), Some(int(1))),
    Case("case/case-02/case-02", caseOf(constr(1, int(0)), lam(int(1)), lam(int(2))), Some(int(2))),
    Case("case/case-05/case-05", caseOf(identity1, identity1, identity1), None),
    Case("case/case-06/case-06", caseOf(constr(0, int(0)), int(1), lam(int(2))), None),
    Case("case/case-08/case-08", caseOf(constr(0), int(1), int(2)), Some(int(1))),
    Case("case/case-09/case-09", caseOf(constr(0)), None),
    Case("case/case-10/case-10", lam(caseOf(v(1))), Some(lam(caseOf(v(1))))),
    Case("closure/closure", app(first, int(1)), Some(lam(int(1)))),
    Case("constr/constr-01/constr-01", constr(0), Some(constr(0))),
    Case("constr/constr-02/constr-02", constr(0, int(1)), Some(constr(0, int(1)))),
    Case(
      "constr/constr-03/constr-03",
      constr(1, int(1), identity1, constr(0, int(1))),
      Some(constr(1, int(1), identity1, constr(0, int(1))))
    ),
    Case("constr/constr-04/constr-04", constr(0, error), None),
    Case("constr/constr-05/constr-05", constr(50000, int(1), error), None),
    Case(
      "constr/constr-07/constr-07",
      constr(-1, con(StubBuiltins.string("Maximum tag = 2^64-1")), int(999)),
      Some(constr(-1, con(StubBuiltins.string("Maximum tag = 2^64-1")), int(999)))
    ),
    Case("delay/delay-error-1/delay-error-1", app(lam(int(4)), delay(error)), Some(int(4))),
    Case("delay/delay-error-2/delay-error-2", app(identity1, delay(error)), Some(delay(error))),
    Case("delay/delay-lam/delay-lam", lam(delay(v(1))), Some(lam(delay(v(1))))),
    Case("force/force-1/force-1", force(int(5)), None),
    Case("force/force-2/force-2", app(lam(force(v(1))), delay(int(4))), Some(int(4))),
    Case(
      "force/force-3/force-3",
      app(lam(force(app(identity1, v(1)))), delay(int(4))),
      Some(int(4))
    ),
    Case("force/force-4/force-4", force(identity1), None),
    Case("lam/lam-1/lam-1", identity1, Some(identity1)),
    Case("lam/lam-2/lam-2", lam(int(23)), Some(lam(int(23)))),
    Case("nonFunctionalApplication/nonFunctionalApplication", app(int(3), int(4)), None),
    Case("constant-case/bool/bool-01/bool-01", caseOf(bool(false), int(0), int(1)), Some(int(0))),
    Case("constant-case/bool/bool-02/bool-02", caseOf(bool(true), int(0), int(1)), Some(int(1))),
    Case("constant-case/bool/bool-03/bool-03", caseOf(bool(false), int(0)), Some(int(0))),
    Case("constant-case/bool/bool-04/bool-04", caseOf(bool(true), int(0)), None),
    Case("constant-case/bool/bool-05/bool-05", caseOf(bool(false), int(0), int(1), int(2)), None),
    Case("constant-case/bool/bool-06/bool-06", caseOf(bool(true), int(0), int(1), int(2)), None),
    Case("constant-case/bool/bool-07/bool-07", caseOf(bool(false)), None),
    Case(
      "constant-case/data/data-01/data-01",
      caseOf(data(PlutusData.Constr(0, Vector.empty)), lam(int(42))),
      Some(int(42))
    ),
    Case(
      "constant-case/data/data-02/data-02",
      caseOf(data(PlutusData.Constr(1, dataFields)), lam(int(0)), identity1),
      Some(con(list(DefaultUni.Data, dataFields.map(Constant.Data(_))*)))
    ),
    Case(
      "constant-case/data/data-03/data-03",
      caseOf(data(PlutusData.Constr(2, Vector.empty)), identity1, identity1),
      None
    ),
    Case(
      "constant-case/data/data-04/data-04",
      caseOf(data(PlutusData.Constr(0, Vector.empty)), int(42)),
      None
    ),
    Case("constant-case/data/data-05/data-05", caseOf(data(PlutusData.I(42)), identity1), None),
    Case("constant-case/integer/integer-01/integer-01", caseOf(int(0)), None),
    Case(
      "constant-case/integer/integer-02/integer-02",
      caseOf(int(1), int(42), int(43), int(443)),
      Some(int(43))
    ),
    Case(
      "constant-case/integer/integer-03/integer-03",
      caseOf(int(3), int(42), int(43), int(44)),
      None
    ),
    Case(
      "constant-case/integer/integer-04/integer-04",
      caseOf(int(-1), int(42), int(43), int(44)),
      None
    ),
    Case("constant-case/list/list-01/list-01", caseOf(numbers, first, int(-1)), Some(int(1))),
    Case("constant-case/list/list-02/list-02", caseOf(noNumbers, first, int(-1)), Some(int(-1))),
    Case("constant-case/list/list-03/list-03", caseOf(numbers, first), Some(int(1))),
    Case("constant-case/list/list-04/list-04", caseOf(noNumbers, first), None),
    Case("constant-case/list/list-05/list-05", caseOf(numbers, int(-1)), None),
    Case("constant-case/list/list-06/list-06", caseOf(numbers, int(-1), int(-1)), None),
    Case("constant-case/list/list-07/list-07", caseOf(numbers, int(-1), first), None),
    Case("constant-case/pair/pair-01/pair-01", caseOf(pair, first), Some(int(42))),
    Case("constant-case/pair/pair-02/pair-02", caseOf(pair, second), Some(bool(false))),
    Case("constant-case/pair/pair-03/pair-03", caseOf(pair), None),
    Case("constant-case/pair/pair-04/pair-04", caseOf(pair, second, second), None),
    Case("constant-case/pair/pair-05/pair-05", caseOf(pair, identity1), None),
    Case("constant-case/unit/unit-01/unit-01", caseOf(unit, int(42)), Some(int(42))),
    Case("constant-case/unit/unit-02/unit-02", caseOf(unit), None),
    Case("constant-case/unit/unit-03/unit-03", caseOf(unit, int(42), int(43)), None)
  )

  private val Budget = raw"\(\{cpu:\s*(\d+)\s*\|\s*mem:\s*(\d+)\}\)".r

  test("every builtin-free evaluation case under term/ is in the table") {
    val evaluation = corpus.keySet
      .filter(name => name.startsWith("conformance/term/") && name.endsWith(".uplc"))
      .filterNot(name => read(name).contains("(builtin"))
      .map(_.stripPrefix("conformance/term/").stripSuffix(".uplc"))
      .filterNot(stem => read(s"conformance/term/$stem.uplc.expected").trim == "parse/decode error")
    assertEquals(cases.map(_.stem).toSet, evaluation)
  }

  cases.foreach { c =>
    test(s"term/${c.stem}") {
      val expectedText = read(s"conformance/term/${c.stem}.uplc.expected").trim
      val budgetText = read(s"conformance/term/${c.stem}.budget.expected").trim
      val report = CekMachine.run(parameters, BudgetMode.Counting, c.term)
      (c.expected, report.result) match
        case (None, CekResult.Failure(_)) =>
          assertEquals(expectedText, "evaluation failure", "the corpus expects a failure")
          assertEquals(budgetText, "evaluation failure", "no budget for a failure")
        case (Some(term), success: CekResult.Success) =>
          assertNotEquals(expectedText, "evaluation failure", "the corpus expects a value")
          assertEquals(success.term, term, s"result; the corpus has $expectedText")
          budgetText match
            case Budget(cpu, mem) =>
              assertEquals(report.budget.spent, ExBudget.of(cpu.toLong, mem.toLong), "budget")
            case other => fail(s"budget file: $other")
        case (expected, result) =>
          fail(s"expected ${expected.fold("a failure")(_.toString)}, got $result")
    }
  }
