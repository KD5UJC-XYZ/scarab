// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import munit.FunSuite
import scalus.uplc.{BuiltinSemanticsVariant, DefaultFun, Program}
import xyz.kd5ujc.scarab.codec.Bytes

/** The `ensurable` variants' unlifting bounds (issue 498, [[UnliftingBounds]]): from protocol 11 an
  * integer argument outside `±2^262143` or a byte string argument longer than 65,536 bytes of the
  * builtins that take `CInteger` or `CByteString` fails the script, as in plutus 1.70.0.0; before
  * protocol 11 (variants A to C) it does not.
  */
class UnliftingBoundsSuite extends FunSuite:
  private val evaluator = ScalusEvaluator.pure
  private val budget = ExUnits(BigInt(1) << 40, BigInt(1) << 60)

  // the value is discarded (`(lam r (con unit ()))`), so a V3 script returns unit; version 1.0.0
  // is accepted by every language at every protocol here
  private def script(body: String): Bytes =
    Bytes.fromArray(
      Program
        .parseUplc(s"(program 1.0.0 [ (lam r (con unit ())) $body ])")
        .fold(e => fail(e.toString), _.cborEncoded)
    )

  private def run(language: PlutusLanguage, protocol: Int, body: String): EvalResult =
    evaluator.evaluate(
      EvalRequest(
        language,
        protocol,
        script(body),
        Vector.empty,
        PlutusBoundaryCorpus.costModel(language, protocol),
        budget
      )
    )

  private def succeeds(language: PlutusLanguage, protocol: Int, body: String): Unit =
    run(language, protocol, body) match
      case EvalResult.Success(_, _) => ()
      case other                    => fail(s"$language/$protocol: expected success, got $other")

  private def fails(language: PlutusLanguage, protocol: Int, body: String): Unit =
    run(language, protocol, body) match
      case EvalResult.Failure(EvalFailure.Script(_), _, _) => ()
      case other => fail(s"$language/$protocol: expected a script failure, got $other")

  private def bytes(n: Int): String = "#" + "ab" * n
  private def integer(n: BigInt): String = s"(con integer $n)"
  private def hash(arg: String): String = s"[ (builtin sha2_256) (con bytestring $arg) ]"

  // the reproducer of issue 498: the appended value is 80,000 bytes, the result is not bounded but
  // the argument of sha2_256 is
  private val appendThenHash =
    s"[ (builtin sha2_256) [ [ (builtin appendByteString) (con bytestring ${bytes(40000)}) ] " +
      s"(con bytestring ${bytes(40000)}) ] ]"

  test("the reproducer: refused from protocol 11 in V3 (variant E), evaluated before") {
    fails(PlutusLanguage.V3, 11, appendThenHash)
    succeeds(PlutusLanguage.V3, 10, appendThenHash)
  }

  test("a byte string argument of 65,536 bytes is taken, of 65,537 refused, in variants D and E") {
    for language <- Vector(PlutusLanguage.V2, PlutusLanguage.V3) do
      succeeds(language, 11, hash(bytes(65536)))
      fails(language, 11, hash(bytes(65537)))
      succeeds(language, 10, hash(bytes(65537)))
  }

  test("an integer argument at the bounds is taken, past them refused, in variants D and E") {
    val max = (BigInt(1) << 262143) - 1
    val min = -(BigInt(1) << 262143)
    def add(n: BigInt) = s"[ [ (builtin addInteger) ${integer(n)} ] ${integer(0)} ]"
    for language <- Vector(PlutusLanguage.V2, PlutusLanguage.V3) do
      succeeds(language, 11, add(max))
      succeeds(language, 11, add(min))
      fails(language, 11, add(max + 1))
      fails(language, 11, add(min - 1))
      succeeds(language, 10, add(max + 1))
  }

  test("a result is not bounded: appending to past the bound and taking the length succeeds") {
    val length =
      s"[ (builtin lengthOfByteString) [ [ (builtin appendByteString) (con bytestring ${bytes(40000)}) ] " +
        s"(con bytestring ${bytes(40000)}) ] ]"
    succeeds(PlutusLanguage.V3, 11, length)
  }

  test("consByteString bounds its integer only in variant D") {
    val cons =
      s"[ [ (builtin consByteString) ${integer(BigInt(1) << 262143)} ] (con bytestring #00) ]"
    fails(PlutusLanguage.V2, 11, cons)
    assertEquals(
      UnliftingBounds.of(DefaultFun.ConsByteString, BuiltinSemanticsVariant.E).map(_._1),
      Vector(1)
    )
  }

  test("35 builtins have a bounded argument, as the ensurable branches of Builtins.hs type them") {
    val bounded =
      DefaultFun.values.filter(f => UnliftingBounds.of(f, BuiltinSemanticsVariant.D).nonEmpty)
    assertEquals(bounded.size, 35)
    assert(!bounded.contains(DefaultFun.EqualsInteger))
  }
