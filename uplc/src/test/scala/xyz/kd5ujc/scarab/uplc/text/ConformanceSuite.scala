// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.text

import xyz.kd5ujc.scarab.uplc.ReferenceCorpus

/** The 1,009 conformance cases of plutus 1.70.0.0 through an evaluator of this module, with no
  * exclusion: each result equal up to names and each budget exact.
  *
  * The evaluator runs the reference runner's profile (`PlutusConformance/CekEvaluator.hs:46-61`,
  * `Common.hs:509-542`, `507cab8c`): the default semantics variant (`def = maxBound`, E,
  * `PlutusCore/Default/Builtins.hs:2546-2547`), the cost model `defaultCostModelParamsForTesting`,
  * the counting budget, and the default caser `caseBuiltin`, which allows `case` on every built-in
  * type, `data` included (`PlutusCore/Builtin/Case.hs:60-61`). That profile is not the ledger's.
  *
  * A suite plugs an evaluator in by extending this class with `Some(backend)`. With none, the test
  * says so as an assumption that does not hold: munit reports it skipped, an incomplete gate, never
  * a pass.
  *
  * @param backend
  *   the evaluator under test, none until there is one
  */
abstract class ConformanceGate(backend: Option[Conformance.Backend[?]]) extends munit.FunSuite:

  private def conforms[R](b: Conformance.Backend[R]): Unit =
    val cases =
      ReferenceCorpus.conformance(ReferenceCorpus.Pinned).fold(p => fail(p.toString), identity)
    assertEquals(cases.size, 1009)
    val report = Conformance.run(cases, b.evaluate)
    println(s"plutus 1.70.0.0 conformance: ${report.conforming} of ${cases.size} cases conform")
    assert(report.mismatches.isEmpty, report.render(b.show))

  test("the 1,009 cases of plutus 1.70.0.0 through the evaluator: results and budgets") {
    backend.fold(
      assume(
        false,
        "incomplete: no evaluator yet (issue 43: the CEK machine, builtins and costing); " +
          "none of the 1,009 cases is evaluated"
      )
    )(conforms(_))
  }

/** The conformance gate of the own evaluator: the CEK machine with every builtin's denotation and
  * the cost model, in the reference runner's profile
  * ([[xyz.kd5ujc.scarab.uplc.cost.CekConformance]]). The parse-level checks of the corpus run in
  * [[CorpusTextSuite]].
  */
final class ConformanceSuite
    extends ConformanceGate(Some(xyz.kd5ujc.scarab.uplc.cost.CekConformance.backend))
