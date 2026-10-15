// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.plutus.ExUnits
import munit.FunSuite
import scala.util.Try
import scalus.cardano.ledger.MajorProtocolVersion
import scalus.uplc.{Constant, DeBruijnedProgram, DefaultFun, Program, Term}
import scalus.uplc.eval.{Log, NoBudgetSpender, PlutusVM}

/** Minimal reproducers of the deviations of Scalus (1.3.0) from plutus 1.70.0.0 that the upstream
  * conformance suite ([[PlutusConformanceSuite]]) found. Like [[ScalusReproducersSuite]] they
  * assert the deviation as it is today: an upgrade that fixes one fails here, and says that the
  * case can leave the known-failure list of the conformance suite
  * (`plutus-conformance-1.70.0.0-known.txt`). The upstream issue drafts are in
  * `.workspace/notes/scalus-issues-conformance.md`.
  */
class ScalusConformanceReproducersSuite extends FunSuite:
  private lazy val vm = PlutusVM.makePlutusV3VM(MajorProtocolVersion(11))

  private def evaluate(text: String): Try[Term] =
    val program = Program.parseUplc(text).fold(sys.error, identity)
    Try(vm.evaluateDeBruijnedTerm(program.deBruijnedProgram.term, NoBudgetSpender, Log(), false))

  private def bytes(term: Try[Term]): String = term.get match
    case Term.Const(Constant.ByteString(b), _) => b.toHex
    case other                                 => fail(s"not a byte string: $other")

  // plutus-core PlutusCore.Value `buildValueWith`: the flat decoder (and the text parser) fail
  // unless currency symbols and token names are strictly ascending, there is no empty inner map and
  // no zero quantity. Scalus decodes them all. The conformance cases `builtin/parser/value/...`.
  test("flat decoding accepts a malformed value constant (reference: decode failure)") {
    def accepted(constant: String): Boolean =
      val program =
        Program.parseUplc(s"(program 1.0.0 (con value $constant))").fold(sys.error, identity)
      ScalusEvaluator.pureDecoder
        .validate(PlutusLanguage.V3, 11, Bytes.fromArray(program.cborEncoded))
        .isRight
    assert(accepted("[(#aa, [(#bb, 1)])]"), "control: a well-formed value")
    assert(accepted("[(#88, [(#aa, 1)]), (#12, [(#bb, 1)])]"), "currency symbols descending")
    assert(accepted("[(#12, [(#bb, 1)]), (#12, [(#cc, 1)])]"), "currency symbol twice")
    assert(accepted("[(#12, [(#bb, 1), (#aa, 1)])]"), "token names descending")
    assert(accepted("[(#12, [(#aa, 1), (#aa, 2)])]"), "token name twice")
    assert(accepted("[(#12, [])]"), "empty inner map")
    assert(accepted("[(#12, [(#aa, 0)])]"), "zero quantity")
  }

  // The reference computes `hash_to_curve` with a DST of 255 bytes as RFC 9380 does (a DST is only
  // reduced above 255 bytes); `hash-dst-len-255` expects the first value below. Scalus differs, and
  // the second case, with a 32 byte DST of arbitrary bytes, differs too.
  test("bls12_381 hashToGroup with a DST of 255 bytes differs from the reference") {
    val dst = "1234567890" * 51 // 255 bytes
    def hashed(g: String, compress: String) =
      bytes(
        evaluate(
          s"(program 1.0.0 [ (builtin $compress) [ [ (builtin $g) (con bytestring #3f) ] (con bytestring #$dst) ] ])"
        )
      )
    val g1 = hashed("bls12_381_G1_hashToGroup", "bls12_381_G1_compress")
    val g2 = hashed("bls12_381_G2_hashToGroup", "bls12_381_G2_compress")
    val referenceG1 =
      "931bd1f65dd2d34a55c93d82c20dcacd3a91afa5932fdd7fed06119f8574520c9609d337d680060b4bd2c59f0b60bb54"
    val referenceG2 =
      "9028b507444b4283faf2f85e7f7d3890b67e9bcf84c7de2f75fe603996ab1b12a25b4637d68f310b7bd6d47ec11e3fa60d0f8f9d1dc880746105b4d7e9b5bba86abfdef96dfda303b1fb00b5d866b5d7f67883efb39efca301ae44a7f1322a33"
    assertNotEquals(g1, referenceG1)
    assertNotEquals(g2, referenceG2)
    assertEquals(
      g1,
      "b842ac8cc7c022e4c59934611a452c38d49835af61b9e29bbefb3eb56ebbbb433ba675e836729a871e0c9e240520e1a4"
    )
    assertEquals(
      g2,
      "ab85034bbbffb6cc4af2ed9a1c717c4e98b2783547fb711c73fa747e12603d55f17aaef5a7e9d9846d17d8e8378ae45904793f155168d39087c400823ad443e256084b749749ba55cad01dd51e296403b38d9e486c90d6cf0eeeb1ec5438bdb9"
    )
  }

  test("bls12_381 hashToGroup with the hash of an oversize DST differs from the reference") {
    val in = getClass.getResourceAsStream(
      s"/${PlutusConformanceSuite.Directory}/builtin/semantics/bls12_381-cardano-crypto-tests/signature/large-dst/large-dst.uplc"
    )
    val text =
      try String(in.readAllBytes(), "UTF-8")
      finally in.close()
    // the reference answers true (the hash equals the expected, uncompressed point)
    assertEquals(evaluate(text).get, Term.Const(Constant.Bool(false)))
  }

  // plutus 1.70.0.0 (`term/constant-case/data`): `case` on a `Data.Constr` selects the branch by
  // the constructor tag and applies it to the list of fields, one argument. Scalus 1.3.0 fixed the
  // application (the fields only, 42) but enables it from protocol 12 (the Van Rossem hard fork);
  // at protocol 11, where the conformance suite runs, the case is refused. The cases `data-01` and
  // `data-02` stay in the known-failure list until the suite runs at protocol 12.
  test("case on a Data.Constr is refused below protocol 12 and applies the fields from 12") {
    val text = "(program 1.1.0 (case (con data (Constr 0 [])) (lam fields (con integer 42))))"
    val program = Program.parseUplc(text).fold(sys.error, identity)
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
        .fold(sys.error, identity)
    DeBruijnedProgram.fromCbor(program.cborEncoded).term match
      case Term.Apply(Term.Builtin(DefaultFun.Bls12_381_G1_uncompress, _), Term.Const(_, _), _) =>
        ()
      case other => fail(s"fixed upstream? now $other")
  }

  // `dropList` with a huge count costs the maximum of the cost type (saturating arithmetic in the
  // reference, `dropList-09` expects a counted budget of 9223372036854775807 steps). In restricting
  // mode the reference runs out of budget as well; Scalus too, but the units it reports as spent
  // wrapped around to a negative number.
  test("units spent are reported negative after an overflow") {
    val program = Program
      .parseUplc(
        "(program 1.0.0 [ [ (force (builtin dropList)) (con integer 10000000000000000000) ] (con (list integer) [1, 2]) ])"
      )
      .fold(sys.error, identity)
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
      case EvalResult.Failure(EvalFailure.OutOfBudget, used, _) => assert(used.steps < 0, used)
      case other => fail(s"fixed upstream? now $other")
  }

  // `PlutusCore.Data.encodeData`: a constructor index in the Word64 range is written as an unsigned
  // integer after tag 102 (`d866 82 1bffffffffffffffff 80` for 2^64 - 1; the datum of preprod
  // transaction 2edd684f carries one). Scalus 1.2.0 wrote the index through a Long (2^64 - 1 became
  // -1, 2^64 + 200 became 200); 1.3.0 is fixed, and so is the codec of `Data` constants in flat.
  // The evaluator's own `serialiseData` replacement was removed with the upgrade.
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
      .fold(sys.error, identity)
    assertEquals(
      DeBruijnedProgram.fromCbor(program.cborEncoded).term,
      program.deBruijnedProgram.term
    )
  }
