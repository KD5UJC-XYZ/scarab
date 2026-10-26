// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.plutus.ExUnits
import munit.FunSuite
import scala.util.Try
import scalus.cardano.ledger.MajorProtocolVersion
import scalus.uplc.{Constant, DeBruijnedProgram, DefaultFun, Program, Term}
import scalus.uplc.eval.{
  Log,
  NoBudgetSpender,
  OutOfExBudgetError,
  PlutusVM,
  RestrictingBudgetSpender
}

/** Dependency deviations retained as regressions for Scarab's decoder and builtin corrections.
  * [[PlutusConformanceSuite]] evaluates every original case without exclusions using the upstream
  * term/counting profile. An upgrade that fixes a dependency deviation fails here so its local
  * correction can be reviewed; `docs/plutus-conformance.md` records the boundary evidence.
  */
class ScalusConformanceReproducersSuite extends FunSuite:
  private lazy val vm = PlutusVM.makePlutusV3VM(MajorProtocolVersion(11))

  private def evaluate(text: String): Try[Term] =
    val program = Program.parseUplc(text).fold(e => sys.error(e.toString), identity)
    Try(vm.evaluateDeBruijnedTerm(program.deBruijnedProgram.term, NoBudgetSpender, Log(), false))

  private def bytes(term: Try[Term]): String = term.get match
    case Term.Const(Constant.ByteString(b), _) => b.toHex
    case other                                 => fail(s"not a byte string: $other")

  // plutus 1.70.0.0 (`term/constant-case/data`): `case` on a `Data.Constr` selects the branch by
  // the constructor tag and applies it to the list of fields, one argument. Scalus 1.3.0 fixed the
  // application (the fields only, 42) but enables it from protocol 12 (Dijkstra);
  // at protocol 11 the ledger refuses the case. The upstream conformance profile independently
  // enables caseBuiltin, including Data, and verifies data-01/data-02 without excluding them.
  test("case on a Data.Constr is refused below protocol 12 and applies the fields from 12") {
    val text = "(program 1.1.0 (case (con data (Constr 0 [])) (lam fields (con integer 42))))"
    val program = Program.parseUplc(text).fold(e => sys.error(e.toString), identity)
    def at(pv: Int) = Try(
      PlutusVM
        .makePlutusV3VM(MajorProtocolVersion(pv))
        .evaluateDeBruijnedTerm(program.deBruijnedProgram.term, NoBudgetSpender, Log(), false)
    )
    val below = at(11)
    assert(below.isFailure, below)
    assert(below.failed.get.getMessage.contains("not supported before protocol version 12"), below)
    assertEquals(at(12).get, Term.Const(Constant.Integer(42)))
  }

  // Plutus Core flat has no BLS12-381 constants (the reference cannot encode them; the suite
  // cases `builtin/parser/bls12-381/*/zero` and `on-curve-bit3-*` have no flat file). Scalus's
  // encoder writes an application of the uncompress builtin instead, so a flat round trip costs
  // 52996222 more steps than the constant: those cases cannot run through the evaluator.
  test("a BLS12-381 constant is flat-encoded as an application of uncompress") {
    val zero = "c0" + "00" * 47
    val program =
      Program
        .parseUplc(s"(program 1.0.0 (con bls12_381_G1_element 0x$zero))")
        .fold(e => sys.error(e.toString), identity)
    DeBruijnedProgram.fromCbor(program.cborEncoded).term match
      case Term.Apply(Term.Builtin(DefaultFun.Bls12_381_G1_uncompress, _), Term.Const(_, _), _) =>
        ()
      case other => fail(s"fixed upstream? now $other")
  }

  // `dropList` with a huge count costs the maximum of the cost type (saturating arithmetic in the
  // reference, `dropList-09` expects a counted budget of 9223372036854775807 steps). In restricting
  // mode the reference runs out of budget as well; so does Scalus, but its restricting spender
  // keeps the remaining budget in a wrapping `Long`, so the units it reports as spent wrapped
  // around to a negative number. The evaluator spends through `ReferenceBudget` instead
  // (`ReferenceBudgetSuite`), whose final state is the reference's: the limit minus a remaining
  // budget below zero, saturated.
  test("Scalus's restricting spender reports the units spent negative after an overflow") {
    val program = Program
      .parseUplc(
        "(program 1.0.0 [ [ (force (builtin dropList)) (con integer 10000000000000000000) ] (con (list integer) [1, 2]) ])"
      )
      .fold(e => sys.error(e.toString), identity)
    val limit = scalus.cardano.ledger.ExUnits(1L << 60, 1L << 60)
    val spender = RestrictingBudgetSpender(limit)
    val dependency = Try(
      vm.evaluateDeBruijnedTerm(program.deBruijnedProgram.term, spender, Log(), false)
    )
    assert(dependency.failed.toOption.exists(_.isInstanceOf[OutOfExBudgetError]), dependency)
    assert(spender.getSpentBudget.steps < 0, s"fixed upstream? now ${spender.getSpentBudget}")
    val result = ScalusEvaluator.pure.evaluate(
      EvalRequest(
        PlutusLanguage.V3,
        11,
        Bytes.fromArray(program.cborEncoded),
        Vector.empty,
        PlutusConformanceSuite.costModel,
        ExUnits(BigInt(1) << 60, BigInt(1) << 60)
      )
    )
    result match
      case EvalResult.Failure(EvalFailure.OutOfBudget, used, _) =>
        assertEquals(used.steps, BigInt(Long.MaxValue))
      case other => fail(s"the reference runs out of budget, got $other")
  }

  // `PlutusCore.Data.encodeData`: a constructor index in the Word64 range is written as an unsigned
  // integer after tag 102 (`d866 82 1bffffffffffffffff 80` for 2^64 - 1; the datum of preprod
  // transaction 2edd684f carries one). Scalus 1.2.0 wrote the index through a Long (2^64 - 1 became
  // -1, 2^64 + 200 became 200); 1.3.0 is fixed, and so is the codec of `Data` constants in flat.
  // The evaluator still installs `DataSerialiser` for nesting beyond Scalus's encoder limit;
  // `DataSerialiserSuite` covers that independent reason for retaining it.
  test("serialiseData and flat write a constructor index of 2^64 - 1 as the reference does") {
    def serialised(index: String) =
      bytes(
        evaluate(
          s"(program 1.0.0 [ (builtin serialiseData) [ [ (builtin constrData) (con integer $index) ] (con (list data) []) ] ])"
        )
      )
    assertEquals(serialised("200"), "d8668218c880")
    assertEquals(serialised("18446744073709551615"), "d866821bffffffffffffffff80")
    assertNotEquals(serialised("18446744073709551816"), serialised("200"))
    val program = Program
      .parseUplc("(program 1.0.0 (con data (Constr 18446744073709551615 [])))")
      .fold(e => sys.error(e.toString), identity)
    assertEquals(
      DeBruijnedProgram.fromCbor(program.cborEncoded).term,
      program.deBruijnedProgram.term
    )
  }
