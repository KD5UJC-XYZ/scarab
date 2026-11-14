// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import scalus.uplc.Program

/** All original Plutus 1.71.0.0 evaluation cases (`261766ad`, the plutus of the Leios testnet
  * build), under the upstream executable's term profile (`CekEvaluator.hs:47-61`): the default
  * variant G, saturating counting and `defaultCaserBuiltinFor newestPV`, where `newestPV` is
  * Dijkstra, 12. That caser is the ledger's from protocol 12, so `case` on `Data` follows the
  * production rule ([[PlutusAvailability.caseOnData]]): it is allowed in programs of version 1.2.0.
  * The cases run as PlutusV4 at protocol 12, on the machine production builds for it.
  */
class PlutusConformance171Suite
    extends PlutusConformanceCorpus(PlutusConformanceSuite.Release.V171):

  test("the Data cases are 1.2.0 programs, which case on Data at protocol 12; 1.1.0 ones cannot") {
    val data = cases.filter(_.id.startsWith("term/constant-case/data/"))
    assertEquals(data.size, 5, "original Data case cases")
    val withBudget = data.filter(_.budget.nonEmpty)
    assert(withBudget.nonEmpty)
    withBudget.foreach { c =>
      val program = Program.parseUplc(c.program).fold(e => fail(e), identity)
      assertEquals(program.version, (1, 2, 0), c.id)
      assertEquals(check(c), None, s"${c.id}: exact original result and budget")
      val older = Program(PlutusAvailability.V110, program.term)
      assert(run(older).isInstanceOf[Outcome.Failed], s"${c.id}: refused at version 1.1.0")
    }
  }
