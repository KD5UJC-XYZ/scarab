// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import munit.FunSuite
import scalus.uplc.Program
import xyz.kd5ujc.scarab.codec.Bytes

/** `case` on a `Data` value through the evaluator the ledger uses: from protocol 12 for programs of
  * version 1.2.0 (`defaultCaserBuiltinFor`, `PlutusLedgerApi/Common/Eval.hs:144-148`, plutus
  * 1.71.0.0), in every language; refused for older programs, and version 1.2.0 itself is not
  * available before protocol 12.
  */
class CaseOnDataSuite extends FunSuite:
  private val evaluator = ScalusEvaluator.pure
  private val budget = ExUnits(BigInt(10000000), BigInt(10000000000L))

  private def script(version: String): Bytes =
    Bytes.fromArray(
      Program
        .parseUplc(
          s"(program $version (case (con data (Constr 1 [I 7])) (error) (lam fs (con unit ()))))"
        )
        .fold(e => fail(e.toString), _.cborEncoded)
    )

  private def run(language: PlutusLanguage, protocol: Int, version: String): EvalResult =
    evaluator.evaluate(
      EvalRequest(
        language,
        protocol,
        script(version),
        Vector.empty,
        PlutusBoundaryCorpus.costModel(language, protocol),
        budget
      )
    )

  test("from protocol 12 a 1.2.0 program cases on Data, choosing the branch by the constructor") {
    PlutusLanguage.values.foreach { language =>
      assert(run(language, 12, "1.2.0").isSuccess, s"$language")
    }
  }

  test("at protocol 12 a 1.1.0 program cannot case on Data: the script fails") {
    PlutusLanguage.values.foreach { language =>
      run(language, 12, "1.1.0") match
        case EvalResult.Failure(EvalFailure.Script(_), _, _) => ()
        case other                                           => fail(s"$language: $other")
    }
  }

  test("before protocol 12 a 1.2.0 program is not available, and PlutusV4 not at all") {
    Seq(PlutusLanguage.V1, PlutusLanguage.V2, PlutusLanguage.V3).foreach { language =>
      assertEquals(
        run(language, 11, "1.2.0"),
        EvalResult.Failure(
          EvalFailure.Decode(
            ScriptProblem.Availability(
              PlutusAvailability.Failure.VersionUnavailable((1, 2, 0), language, 11)
            )
          ),
          ExUnits.zero,
          Vector.empty
        )
      )
    }
    run(PlutusLanguage.V4, 11, "1.1.0") match
      case EvalResult.Failure(EvalFailure.Decode(reason), _, _) =>
        assertEquals(
          reason,
          ScriptProblem.Availability(
            PlutusAvailability.Failure.LanguageUnavailable(PlutusLanguage.V4, 11)
          )
        )
      case other => fail(other.toString)
  }
