// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.text

import xyz.kd5ujc.scarab.uplc.ReferenceCorpus

/** The 1,009 conformance cases of plutus 1.70.0.0 through the own evaluator, with no exclusion:
  * each result equal up to names and each budget exact.
  *
  * The evaluator is the reference runner's profile (`PlutusConformance/CekEvaluator.hs:46-61`,
  * `Common.hs:509-542`, `507cab8c`): the default semantics variant (`def = maxBound`, E,
  * `PlutusCore/Default/Builtins.hs:2546-2547`), the cost model `defaultCostModelParamsForTesting`,
  * the counting budget, and the default caser `caseBuiltin`, which allows `case` on every built-in
  * type, `data` included (`PlutusCore/Builtin/Case.hs:60-61`). That differs from the ledger's
  * profile.
  *
  * Until the machine exists (issue 43: the CEK machine, builtins and costing) there is no
  * evaluator, and the test says so as an assumption that does not hold: munit reports it skipped,
  * an incomplete gate, never a pass. The parse-level checks of the corpus run in
  * [[CorpusTextSuite]].
  */
final class ConformanceSuite extends munit.FunSuite:

  /** An evaluator with a way to show why it cannot evaluate a program. */
  final case class Backend[R](evaluate: Conformance.Evaluator[R], show: R => String)

  /** The own evaluator under test; none yet. */
  private val backend: Option[Backend[?]] = None

  private def conforms[R](b: Backend[R]): Unit =
    val cases =
      ReferenceCorpus.conformance(ReferenceCorpus.Pinned).fold(p => fail(p.toString), identity)
    assertEquals(cases.size, 1009)
    val report = Conformance.run(cases, b.evaluate)
    println(s"plutus 1.70.0.0 conformance: ${report.conforming} of ${cases.size} cases conform")
    assert(report.mismatches.isEmpty, report.render(b.show))

  test("the 1,009 cases of plutus 1.70.0.0 through the own evaluator: results and budgets") {
    backend.fold(
      assume(
        false,
        "incomplete: no evaluator yet (issue 43: the CEK machine, builtins and costing); " +
          "none of the 1,009 cases is evaluated"
      )
    )(conforms(_))
  }
