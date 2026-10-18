// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import munit.FunSuite
import scala.util.Try
import scalus.cardano.ledger.MajorProtocolVersion
import scalus.uplc.{Constant, Program, Term as UplcTerm}
import scalus.uplc.eval.{Log, NoBudgetSpender, PlutusVM}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.plutus.FlatProgram.*

/** What of the original 22 conformance exceptions (and of the protocol-11 admission rules) a
  * transaction can reach, through the evaluator the ledger rules use. Scripts the reference would
  * refuse are assembled byte by byte ([[FlatProgram]]); the verdicts are those of plutus 1.70.0.0
  * (`docs/plutus-conformance.md` cites the lines). The reference node itself was not run on them.
  */
class PlutusReachabilitySuite extends FunSuite:
  private val budget = ExUnits(BigInt(14000000), BigInt(10000000000L))
  private val v110 = (1, 1, 0)
  private val v100 = (1, 0, 0)

  private def ascii(s: String): Array[Byte] = s.getBytes("US-ASCII")

  private def decodes(language: PlutusLanguage, pv: Int, script: Bytes): Boolean =
    ScalusEvaluator.pureDecoder.validate(language, pv, script).isRight

  private def evaluate(pv: Int, script: Bytes): EvalResult =
    ScalusEvaluator.pure.evaluate(
      EvalRequest(
        PlutusLanguage.V3,
        pv,
        script,
        Vector.empty,
        PlutusConformanceSuite.costModel,
        budget
      )
    )

  // ---- the six value cases: reachable at every protocol version and in every language ----------

  private val a = ascii("a")
  private val b = ascii("b")
  private def valueScript(entries: Vector[(Array[Byte], Vector[(Array[Byte], BigInt)])]) =
    script(v100, value(entries))

  private val malformedValues: Vector[(String, Bytes)] = Vector(
    "currencyIDs-unordered" -> valueScript(
      Vector(b -> Vector(a -> BigInt(1)), a -> Vector(a -> BigInt(1)))
    ),
    "duplicate-currencyIDs" -> valueScript(
      Vector(a -> Vector(a -> BigInt(1)), a -> Vector(b -> BigInt(1)))
    ),
    "duplicate-tokenIDs" -> valueScript(Vector(a -> Vector(a -> BigInt(1), a -> BigInt(2)))),
    "empty-tokens" -> valueScript(Vector(a -> Vector.empty)),
    "tokenIDs-unordered" -> valueScript(Vector(a -> Vector(b -> BigInt(1), a -> BigInt(1)))),
    "zero-asset" -> valueScript(Vector(a -> Vector(a -> BigInt(0))))
  )

  test("a well-formed value constant decodes, the six malformed ones do not, in every language") {
    val control = valueScript(Vector(a -> Vector(a -> BigInt(1), b -> BigInt(-2))))
    for (language, pv) <- Vector(
        PlutusLanguage.V1 -> 5,
        PlutusLanguage.V1 -> 11,
        PlutusLanguage.V2 -> 7,
        PlutusLanguage.V2 -> 10,
        PlutusLanguage.V3 -> 9,
        PlutusLanguage.V3 -> 11
      )
    do
      assert(decodes(language, pv, control), s"control $language $pv")
      for (name, malformed) <- malformedValues do
        assert(!decodes(language, pv, malformed), s"$name $language $pv")
  }

  test("a value key over 32 bytes or a quantity over 127 bits is refused as well") {
    val long = Array.fill[Byte](33)(1)
    assert(!decodes(PlutusLanguage.V3, 11, valueScript(Vector(long -> Vector(a -> BigInt(1))))))
    assert(!decodes(PlutusLanguage.V3, 11, valueScript(Vector(a -> Vector(long -> BigInt(1))))))
    val big = (BigInt(1) << 127)
    assert(!decodes(PlutusLanguage.V3, 11, valueScript(Vector(a -> Vector(a -> big)))))
    assert(decodes(PlutusLanguage.V3, 11, valueScript(Vector(a -> Vector(a -> (big - 1))))))
    assert(decodes(PlutusLanguage.V3, 11, valueScript(Vector(a -> Vector(a -> -big)))))
  }

  test("a malformed value inside a list or pair constant is refused too") {
    val inner = value(Vector(a -> Vector(a -> BigInt(0))))
    val listOf = Const(
      Vector(7, 5, 13),
      bits =>
        bits.bit(1)
        inner match
          case Const(_, w) => w(bits)
          case _           => ()
        bits.bit(0)
    )
    assert(!decodes(PlutusLanguage.V3, 11, script(v100, listOf)))
  }

  // ---- the six BLS12-381 parser cases: both sides refuse a flat constant ------------------------

  test("a BLS12-381 constant has no flat encoding for the reference and for Scalus") {
    val compressed = Array.fill[Byte](48)(0).updated(0, 0xc0.toByte)
    assert(!decodes(PlutusLanguage.V3, 11, script(v100, blsG1(compressed))))
  }

  // ---- the two case-on-Data cases: the ledger refuses them below protocol 12 --------------------

  private def evalText(pv: Int, text: String): Try[UplcTerm] =
    val program = Program.parseUplc(text).fold(e => sys.error(e.toString), identity)
    Try(
      PlutusVM
        .makePlutusV3VM(MajorProtocolVersion(pv))
        .evaluateDeBruijnedTerm(program.deBruijnedProgram.term, NoBudgetSpender, Log(), false)
    )

  test("case on a constant: refused below 11, allowed from 11 except on Data, which waits for 12") {
    // defaultCaserBuiltinFor: pv < 11 unavailable, pv < 12 caseBuiltinNoData, else caseBuiltin
    val onInteger = "(program 1.1.0 (case (con integer 0) (con integer 42)))"
    val onData = "(program 1.1.0 (case (con data (Constr 0 [])) (lam fields (con integer 42))))"
    for pv <- Seq(9, 10) do assert(evalText(pv, onInteger).isFailure, s"integer at $pv")
    assertEquals(evalText(11, onInteger).get, UplcTerm.Const(Constant.Integer(42)))
    assert(evalText(11, onData).isFailure)
    assertEquals(evalText(12, onData).get, UplcTerm.Const(Constant.Integer(42)))
  }

  // ---- the five dropList cases: the verdict is the reference's, only the reported units differ --

  test("dropList costs its count, saturating: never a small wrapped cost, never a success") {
    def dropped(n: BigInt) =
      val text =
        s"(program 1.0.0 [ [ (force (builtin dropList)) (con integer $n) ] (con (list integer) [1, 2]) ])"
      val program = Program.parseUplc(text).fold(e => sys.error(e.toString), identity)
      ScalusEvaluator.pure.evaluate(
        EvalRequest(
          PlutusLanguage.V3,
          11,
          Bytes.fromArray(program.cborEncoded),
          Vector.empty,
          PlutusConformanceSuite.costModel,
          budget
        )
      )
    // 1957 steps per element on top of 116711: 5,000,000 elements cost about 9.785e9 steps
    val steps = dropped(5000000).spent.steps
    assert(steps > BigInt(9785000000L) && steps < BigInt(9786000000L), steps)
    val two64 = BigInt(1) << 64
    val inverse = BigInt(1957).modInverse(two64)
    // counts whose product with the slope wraps to a small number in 64-bit arithmetic
    val wrapping = (1 to 40).map(k => (inverse * k).mod(two64)).filter(_ < (BigInt(1) << 63))
    assert(wrapping.nonEmpty)
    for n <- wrapping ++ Seq(BigInt(1) << 62, (BigInt(1) << 63) - 1, BigInt(1) << 63) do
      dropped(n) match
        case EvalResult.Failure(EvalFailure.OutOfBudget, _, _) => ()
        case other                                             => fail(s"count $n: $other")
  }

  // ---- the three hashToGroup cases: answered wrongly by Scalus for a non-ASCII tag --------------

  private def hashed(dstHex: String): EvalResult =
    val text =
      s"(program 1.0.0 [ (builtin bls12_381_G1_compress) [ [ (builtin bls12_381_G1_hashToGroup) (con bytestring #3f) ] (con bytestring #$dstHex) ] ])"
    val program = Program.parseUplc(text).fold(e => sys.error(e.toString), identity)
    ScalusEvaluator.pure.evaluate(
      EvalRequest(
        PlutusLanguage.V3,
        11,
        Bytes.fromArray(program.cborEncoded),
        Vector.empty,
        PlutusConformanceSuite.costModel,
        budget
      )
    )

  test(
    "hashToGroup evaluates original ASCII, high-bit and zero-containing byte-array tags"
  ) {
    // the tag is data; the other branch of the result is a non-unit value, hence a script failure
    def verdict(hex: String): EvalFailure = hashed(hex) match
      case EvalResult.Failure(f, _, _) => f
      case other                       => fail(s"unexpected $other")
    // a conformance case's tag (`hash-dst-len-255`: bytes of 0x90 and above) and a plain one
    assert(verdict("90").isInstanceOf[EvalFailure.Script])
    assert(verdict("4100").isInstanceOf[EvalFailure.Script])
    assert(
      verdict("41").isInstanceOf[EvalFailure.Script],
      "ASCII tag: evaluated, a bytestring is no unit"
    )
  }

  // ---- protocol 11 admission limits (#166) -------------------------------------------------------

  private def withConstant(t: Tm): Bytes =
    script(v110, Ap(Lam(unit), t))

  test("a constant type header of 31 tags decodes, one of 33 only below protocol 11") {
    // list^15 integer has 31 tags, list^16 integer 33 (a header is always odd)
    val at31 = withConstant(nestedEmptyList(15))
    val at33 = withConstant(nestedEmptyList(16))
    for pv <- 9 to 11 do assert(decodes(PlutusLanguage.V3, pv, at31), s"31 at $pv")
    for pv <- 9 to 10 do assert(decodes(PlutusLanguage.V3, pv, at33), s"33 at $pv")
    assert(!decodes(PlutusLanguage.V3, 11, at33))
    assert(!decodes(PlutusLanguage.V3, 12, at33))
  }

  test("a constr of 1024 fields decodes, one of 1025 only below protocol 11") {
    def constr(n: Int) = script(v110, Ap(Lam(unit), Constr(0, Vector.fill(n)(unit))))
    for pv <- 9 to 11 do assert(decodes(PlutusLanguage.V3, pv, constr(1024)), s"1024 at $pv")
    assert(decodes(PlutusLanguage.V3, 10, constr(1025)))
    assert(!decodes(PlutusLanguage.V3, 11, constr(1025)))
    // evaluation refuses it the same way
    assert(evaluate(11, constr(1025)).spent == ExUnits.zero)
    assert(evaluate(11, constr(1025)).isInstanceOf[EvalResult.Failure])
  }

  // ---- closedness of the applied term (#167) -----------------------------------------------------

  private def run(pv: Int, t: Tm): EvalResult = evaluate(pv, script(v110, t))

  test("a free variable under an unforced Delay fails at evaluation setup, not at decoding") {
    val free = Ap(Lam(unit), Delay(Var(5)))
    assert(decodes(PlutusLanguage.V3, 11, script(v110, free)))
    assertEquals(
      run(11, free),
      EvalResult.Failure(EvalFailure.OpenTerm(5), ExUnits.zero, Vector.empty)
    )
    val bound = Ap(Lam(unit), Delay(Lam(Var(1))))
    assert(run(11, bound).isSuccess)
    // index 0 is never a variable
    assertEquals(run(11, Ap(Lam(unit), Delay(Lam(Var(0))))).isSuccess, false)
  }

  test("a 64-bit index is not narrowed into a bound one") {
    // 2^32 + 2 narrows to 2, which is bound under two lambdas
    val wide = Ap(Lam(Ap(Lam(unit), Delay(Var(BigInt(4294967298L))))), unit)
    assert(run(11, wide) match
      case EvalResult.Failure(EvalFailure.OpenTerm(i), _, _) => i == BigInt(4294967298L)
      case _                                                 => false)
    // a word of 65 bits is no Word64 for the reference either
    assert(
      !decodes(PlutusLanguage.V3, 11, script(v110, Ap(Lam(unit), Delay(Var(BigInt(1) << 64)))))
    )
  }

  test("the scope check does not descend into constr fields, a case scrutinee or its branches") {
    // `checkScope` handles Var, LamAbs, Apply, Force and Delay and ignores every other term
    val inBranch = Case(integer(0), Vector(unit, Var(9)))
    assert(run(11, inBranch).isSuccess)
    val inField = Ap(Lam(unit), Delay(Constr(0, Vector(Var(9)))))
    assert(run(11, inField).isSuccess)
  }

  test("wide indices below skipped scope subterms fail only when the VM evaluates them") {
    // Actual plutus-1.70.0.0 verdicts; Cek.lookupVarName rejects -1 before environment indexing.
    val wide = BigInt(4294967298L)
    for pv <- Vector(9, 10, 11) do
      assert(run(pv, Ap(Lam(unit), Delay(Constr(0, Vector(Var(wide)))))).isSuccess)
    assert(run(11, Case(integer(0), Vector(unit, Var(wide)))).isSuccess)
    run(11, Ap(Lam(unit), Constr(0, Vector(Var(wide))))) match
      case EvalResult.Failure(EvalFailure.Script(_), _, _) => ()
      case other => fail(s"evaluated wide variable: $other")
  }

  test("the internal wide-index repair walks 20000 levels on a small stack") {
    import scalus.uplc.NamedDeBruijn
    val leaf = UplcTerm.Var(NamedDeBruijn("wide", 2))
    val deep = (0 until 20000).foldLeft[UplcTerm](leaf)((term, _) => UplcTerm.Delay(term))
    val result = new java.util.concurrent.atomic.AtomicReference[Either[Throwable, Boolean]](
      Right(false)
    )
    val worker = new Thread(
      null,
      () =>
        result.set(
          try
            val repaired = WideIndices.repair(deep, Set(0))
            val inner = (0 until 20000).foldLeft(repaired) { (term, _) =>
              term match
                case UplcTerm.Delay(body, _) => body
                case _                       => term
            }
            Right(inner match
              case UplcTerm.Var(name, _) => name.index == -1
              case _                     => false)
          catch case error: Throwable => Left(error)
        ),
      "wide-index-small-stack",
      256 * 1024
    )
    worker.start()
    worker.join()
    result.get() match
      case Right(correct) => assert(correct)
      case Left(error)    => throw error
  }
