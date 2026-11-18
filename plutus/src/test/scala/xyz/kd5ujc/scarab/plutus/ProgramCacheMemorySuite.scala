// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import xyz.kd5ujc.scarab.uplc.ProtocolMajor
import xyz.kd5ujc.scarab.uplc.ledger.LedgerEval

/** What the evaluator's cache of decoded programs may hold in the heap. The build runs this suite
  * alone in a forked JVM with `-Xmx768m`, the heap of the shipped launchers (`plutus / Test /
  * testGrouping`), since a heap bound is only a bound if it is real.
  *
  * A 16 kB script of a list of pairs of empty lists ([[AmplifyingScripts.pairTree]]) decodes into
  * about 9 MB of heap. A cache bounded by its number of entries alone (512) kept the programs of 94
  * such scripts given to phase-1 well-formedness, and the 95th ran out of heap.
  */
class ProgramCacheMemorySuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(10, "min")

  private val Size = 16_000
  private val Scripts = 512

  test("the heap is the bound of the test") {
    assert(Runtime.getRuntime.maxMemory <= 800L * 1024 * 1024, "run in the forked JVM of the build")
  }

  test("phase-1 well-formedness of 512 distinct large programs keeps none of them") {
    val interpreter = ScarabEvaluator.Interpreter()
    val refused = (0 until Scripts).iterator
      .map(v =>
        v -> interpreter.validate(PlutusLanguage.V1, 11, AmplifyingScripts.pairTree(Size, v))
      )
      .collect { case (v, Left(failure)) => v -> failure }
      .take(3)
      .toVector
    assertEquals(refused, Vector.empty)
    assertEquals(interpreter.held, (0, Weight.zero))
  }

  test("evaluating 512 distinct large programs keeps them within the cache's weight bound") {
    val interpreter = ScarabEvaluator.Interpreter()
    val unavailable = (0 until Scripts).iterator
      .map { v =>
        val request = EvalRequest(
          PlutusLanguage.V1,
          11,
          AmplifyingScripts.pairTree(Size, v),
          Vector.fill(3)(PlutusData.unit),
          PlutusBoundaryCorpus.costModel(PlutusLanguage.V1, 11),
          PlutusBoundaryCorpus.budget
        )
        val result = interpreter.evaluate(request)
        val (_, weight) = interpreter.held
        assert(
          !weight.exceeds(ScarabEvaluator.Programs.weight),
          s"after script $v the cache weighs ${weight.toLong}"
        )
        v -> result
      }
      .collect { case (v, EvalResult.Failure(EvalFailure.Unsupported(why), _, _)) => v -> why }
      .take(3)
      .toVector
    assertEquals(unavailable, Vector.empty)
    val (kept, weight) = interpreter.held
    val first = AmplifyingScripts.pairTree(Size, 0)
    val each = LedgerEval
      .deserialiseScript(PlutusLanguage.V1, ProtocolMajor(11), first)
      .fold(e => fail(s"$e"), ProgramWeight.entry(first, _, Weight.unbounded))
    println(
      s"$Scripts large programs evaluated: $kept kept, weighing ${weight.toLong} bytes in all, " +
        s"${each.toLong} each"
    )
    assert(kept >= 1, "the most recent program is kept")
    assert(kept < Scripts, s"$kept programs kept")
    assert(
      each.toLong >= 9_079L * 1024,
      s"a program weighs ${each.toLong}, below the 9,079 KiB of heap it was measured to hold"
    )
  }
