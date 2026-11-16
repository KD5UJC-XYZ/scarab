// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.text

import xyz.kd5ujc.scarab.uplc.ReferenceCorpus
import xyz.kd5ujc.scarab.uplc.cost.CekConformance

/** The 1,009 conformance cases of a plutus release through an evaluator of this module, with no
  * exclusion: each result equal up to names and each budget exact.
  *
  * The evaluator runs the release's runner profile ([[CekConformance]]): for plutus 1.70.0.0
  * (`PlutusConformance/CekEvaluator.hs:46-61`, `Common.hs:509-542`, `507cab8c`) the default
  * semantics variant (`def = maxBound`, E, `PlutusCore/Default/Builtins.hs:2546-2547`), the cost
  * model `defaultCostModelParamsForTesting`, the counting budget, and the default caser
  * `caseBuiltin`, which allows `case` on every built-in type, `data` included
  * (`PlutusCore/Builtin/Case.hs:60-61`); for 1.71.0.0 (`CekEvaluator.hs:47-62`, `261766ad`) variant
  * G with E's parameters, and the ledger's caser at protocol 12, which allows `case` on `data` in a
  * program of version 1.2.0 or later only. Neither profile is the ledger's.
  *
  * A suite plugs an evaluator in by extending this class with `Some(backend)`. With none, the test
  * says so as an assumption that does not hold: munit reports it skipped, an incomplete gate, never
  * a pass.
  *
  * @param tag
  *   the release whose cases run
  * @param backend
  *   the evaluator under test, none until there is one
  */
abstract class ConformanceGate(tag: String, backend: Option[Conformance.Backend[?]])
    extends munit.FunSuite:

  private def conforms[R](b: Conformance.Backend[R]): Unit =
    val cases = ReferenceCorpus.conformance(tag).fold(p => fail(p.toString), identity)
    assertEquals(cases.size, 1009)
    val report = Conformance.run(cases, b.evaluate)
    println(s"plutus $tag conformance: ${report.conforming} of ${cases.size} cases conform")
    assert(report.mismatches.isEmpty, report.render(b.show))

  test(s"the 1,009 cases of plutus $tag through the evaluator: results and budgets") {
    backend.fold(
      assume(false, s"incomplete: no evaluator; none of the 1,009 cases of $tag is evaluated")
    )(conforms(_))
  }

/** The conformance gate of the own evaluator at plutus 1.70.0.0: the CEK machine with every
  * builtin's denotation and the cost model, in the reference runner's profile
  * ([[CekConformance.Plutus170]]). The parse-level checks of the corpus run in [[CorpusTextSuite]].
  */
final class ConformanceSuite
    extends ConformanceGate(ReferenceCorpus.Plutus170, Some(CekConformance.Plutus170.backend))

/** The conformance gate of the own evaluator at plutus 1.71.0.0, in that release's runner profile
  * ([[CekConformance.Plutus171]]).
  */
final class Conformance171Suite
    extends ConformanceGate(ReferenceCorpus.Plutus171, Some(CekConformance.Plutus171.backend))
