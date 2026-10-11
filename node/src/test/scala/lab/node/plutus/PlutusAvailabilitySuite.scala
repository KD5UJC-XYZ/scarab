// SPDX-License-Identifier: Apache-2.0
package lab.node.plutus

import lab.cbor.Bytes
import lab.node.ledger.ExUnits
import munit.FunSuite
import scalus.cardano.ledger.CardanoInfo
import scalus.uplc.{DefaultFun, Program, Term}

/** The builtin availability table against `plutus-ledger-api` 1.70.0.0 (`Spec.Versions`,
  * `testPermittedBuiltins`), and the decoder and the evaluator applying it to real scripts.
  */
class PlutusAvailabilitySuite extends FunSuite:
  import PlutusAvailability.*

  private val b1 = batch1.toSet
  private val b2 = batch2.toSet
  private val b3 = batch3.toSet
  private val b4a = batch4a.toSet
  private val b4b = batch4b.toSet
  private val b5 = batch5.toSet
  private val b6 = batch6.toSet
  private val all = b1 ++ b2 ++ b3 ++ b4a ++ b4b ++ b5 ++ b6

  /** Spec.Versions: the builtins allowed per language and protocol major version. */
  private val expected: Seq[(PlutusLanguage, Int, Set[DefaultFun])] = Seq(
    (PlutusLanguage.V1, 5, b1),
    (PlutusLanguage.V1, 7, b1),
    (PlutusLanguage.V1, 8, b1),
    (PlutusLanguage.V1, 9, b1),
    (PlutusLanguage.V1, 10, b1),
    (PlutusLanguage.V1, 11, all),
    (PlutusLanguage.V1, 12, all),
    (PlutusLanguage.V2, 7, b1 ++ b2),
    (PlutusLanguage.V2, 8, b1 ++ b2 ++ b3),
    (PlutusLanguage.V2, 9, b1 ++ b2 ++ b3),
    (PlutusLanguage.V2, 10, b1 ++ b2 ++ b3 ++ b4b),
    (PlutusLanguage.V2, 11, all),
    (PlutusLanguage.V2, 12, all),
    (PlutusLanguage.V3, 9, b1 ++ b2 ++ b3 ++ b4a ++ b4b),
    (PlutusLanguage.V3, 10, b1 ++ b2 ++ b3 ++ b4a ++ b4b ++ b5),
    (PlutusLanguage.V3, 11, all),
    (PlutusLanguage.V3, 12, all)
  )

  test("the batches are disjoint and together are every builtin Scalus knows") {
    val batches = Seq(batch1, batch2, batch3, batch4a, batch4b, batch5, batch6)
    assertEquals(batches.map(_.size).sum, all.size)
    assertEquals(all, DefaultFun.values.toSet)
  }

  test("the builtins allowed per language and protocol version equal plutus's table") {
    expected.foreach { (language, pv, builtins) =>
      assertEquals(builtinsAvailableIn(language, pv), builtins, s"$language @ protocol $pv")
    }
  }

  test("a language has no builtins before the protocol version that introduced it") {
    for
      (language, intro) <- Seq(
        PlutusLanguage.V1 -> 5,
        PlutusLanguage.V2 -> 7,
        PlutusLanguage.V3 -> 9
      )
      pv <- 2 until intro
    do assert(decodeFailure(language, pv, Set(DefaultFun.AddInteger)).isDefined, s"$language @ $pv")
  }

  test("availability only grows with the protocol version") {
    PlutusLanguage.values.foreach { language =>
      (5 to 14).foreach { pv =>
        assert(
          builtinsAvailableIn(language, pv).subsetOf(builtinsAvailableIn(language, pv + 1)),
          s"$language @ $pv"
        )
        assert(
          programVersionsAvailableIn(language, pv)
            .subsetOf(programVersionsAvailableIn(language, pv + 1))
        )
      }
    }
  }

  test("from protocol 11 every language allows every builtin") {
    PlutusLanguage.values.foreach { language =>
      assertEquals(builtinsAvailableIn(language, 11), all)
    }
  }

  test(
    "UPLC program versions: 1.1.0 (constr, case) is V3 from Chang and V1 and V2 from protocol 11"
  ) {
    assertEquals(programVersionsAvailableIn(PlutusLanguage.V1, 10), Set((1, 0, 0)))
    assertEquals(programVersionsAvailableIn(PlutusLanguage.V1, 11), Set((1, 0, 0), (1, 1, 0)))
    assertEquals(programVersionsAvailableIn(PlutusLanguage.V2, 8), Set((1, 0, 0)))
    assertEquals(programVersionsAvailableIn(PlutusLanguage.V2, 11), Set((1, 0, 0), (1, 1, 0)))
    assertEquals(programVersionsAvailableIn(PlutusLanguage.V3, 9), Set((1, 0, 0), (1, 1, 0)))
    assertEquals(programVersionsAvailableIn(PlutusLanguage.V3, 8), Set.empty)
  }

  // --- real scripts ---------------------------------------------------------------------------

  private val decoder = ScalusEvaluator.pureDecoder

  /** A script that is just the builtin (version 1.0.0, no `constr`/`case`). */
  private def scriptOf(f: DefaultFun, version: (Int, Int, Int) = (1, 0, 0)): Bytes =
    Bytes.fromArray(Program(version, Term.Builtin(f)).cborEncoded)

  test("the decoder rejects every builtin outside the table and accepts every builtin inside") {
    expected.foreach { (language, pv, builtins) =>
      all.foreach { f =>
        val result = decoder.validate(language, pv, scriptOf(f))
        if builtins(f) then assertEquals(result, Right(()), s"$f in $language @ $pv")
        else
          assert(
            result.left.exists(_.contains(s"Builtin function $f is not available")),
            s"$f in $language @ $pv: $result"
          )
      }
    }
  }

  test("a builtin nested in lambdas, applications, delays and forces is found") {
    val nested = Term.LamAbs(
      "x",
      Term.Delay(
        Term.Apply(
          Term.Force(Term.Builtin(DefaultFun.IfThenElse)),
          Term.Apply(
            Term.Builtin(DefaultFun.Keccak_256),
            Term.Var(scalus.uplc.NamedDeBruijn("x", 1))
          )
        )
      )
    )
    val bytes = Bytes.fromArray(Program((1, 0, 0), nested).cborEncoded)
    assert(decoder.validate(PlutusLanguage.V2, 8, bytes).isLeft)
    assertEquals(decoder.validate(PlutusLanguage.V2, 11, bytes), Right(()))
    assertEquals(decoder.validate(PlutusLanguage.V3, 9, bytes), Right(()))
  }

  test(
    "a script that is not a flat program, or a language before its protocol version, is rejected"
  ) {
    assert(
      decoder.validate(PlutusLanguage.V2, 8, Bytes(Vector(0x43, 1, 2, 3).map(_.toByte))).isLeft
    )
    val ok = scriptOf(DefaultFun.AddInteger)
    assert(decoder.validate(PlutusLanguage.V3, 8, ok).left.exists(_.contains("not available")))
    assertEquals(decoder.validate(PlutusLanguage.V3, 9, ok), Right(()))
  }

  private val budget = ExUnits(10000000, 10000000000L)

  test("the evaluator fails a script with an unavailable builtin as a decode failure") {
    val evaluator = ScalusEvaluator.pure
    def model(language: PlutusLanguage) =
      CardanoInfo.mainnet.protocolParams.costModels.models(language.id).toVector
    def run(language: PlutusLanguage, pv: Int, f: DefaultFun, version: (Int, Int, Int)) =
      evaluator.evaluate(
        EvalRequest(language, pv, scriptOf(f, version), Vector.empty, model(language), budget)
      )
    // Keccak-256 is not a PlutusV2 builtin before the van Rossem hard fork, protocol 11.
    run(PlutusLanguage.V2, 10, DefaultFun.Keccak_256, (1, 0, 0)) match
      case EvalResult.Failure(EvalFailure.Decode(reason), _, _) =>
        assert(reason.contains("Keccak_256"), reason)
      case other => fail(other.toString)
    // The program version is checked too: 1.1.0 is not available to PlutusV1 before protocol 11.
    run(PlutusLanguage.V1, 10, DefaultFun.AddInteger, (1, 1, 0)) match
      case EvalResult.Failure(EvalFailure.Decode(reason), _, _) =>
        assert(reason.contains("1.1.0"), reason)
      case other => fail(other.toString)
    // Where the table allows the program it is not a decode failure (it fails later: the script
    // is a bare builtin, not a function of the arguments).
    run(PlutusLanguage.V1, 11, DefaultFun.AddInteger, (1, 1, 0)) match
      case EvalResult.Failure(EvalFailure.Decode(reason), _, _) => fail(reason)
      case _                                                    => ()
  }
