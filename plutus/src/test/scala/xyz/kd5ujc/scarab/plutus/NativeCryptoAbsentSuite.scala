// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import cats.effect.IO
import cats.mtl.Handle
import munit.CatsEffectSuite
import scalus.cardano.ledger.CardanoInfo
import scalus.uplc.{DefaultFun, Program}
import xyz.kd5ujc.scarab.codec.Bytes

/** Without libscalus_crypto (ADR 0020). `build.sbt` runs this suite alone in a JVM whose
  * `java.io.tmpdir` does not exist, so native-lib-loader cannot extract the library and `CryptoJni`
  * 's initialisation fails. The start-up check refuses with a typed cause, and every builtin the
  * library backs is a typed `BuiltinUnavailable`, never a script failure or an untyped defect;
  * scripts without them still evaluate.
  */
class NativeCryptoAbsentSuite extends CatsEffectSuite:
  private val evaluator = ScalusEvaluator.pure

  private val G1 =
    "97f1d3a73197d7942695638c4fa9ac0fc3688c4f9774b905a14e3a3f171bac586c55e83ff97a1aeffb3af00adb22c6bb"

  private def script(uplc: String): Bytes =
    Bytes.fromArray(
      Program.parseUplc(s"(program 1.0.0 $uplc)").fold(e => fail(e.toString), identity).cborEncoded
    )

  /** A V2 spending script `\datum redeemer context -> body`, or a V3 script `\context -> body`. */
  private def run(language: PlutusLanguage, body: String): EvalResult =
    val (uplc, args) = language match
      case PlutusLanguage.V3 => (s"(lam ctx $body)", Vector(PlutusData.unit))
      case _ => (s"(lam d (lam r (lam ctx $body)))", Vector.fill(3)(PlutusData.unit))
    evaluator.evaluate(
      EvalRequest(
        language,
        9,
        script(uplc),
        args,
        CardanoInfo.mainnet.protocolParams.costModels.models(language.id).toVector,
        ExUnits(10000000, 10000000000L)
      )
    )

  private def unavailable(fun: DefaultFun): EvalFailure =
    EvalFailure.Unsupported(BackendFailure.BuiltinUnavailable(fun))

  private def bytes(n: Int) = s"(con bytestring #${"00" * n})"

  test("the library is absent in this JVM") {
    assert(!NativeCrypto.available, "libscalus_crypto loaded although java.io.tmpdir is missing")
  }

  test("the start-up check refuses with the typed initialisation failure") {
    Handle
      .allow[NativeCryptoProblem](NativeCrypto.verify[IO].map(Right(_)))
      .rescue(problem => IO.pure(Left(problem)))
      .map {
        case Left(NativeCryptoProblem.NotLoaded(_, Some(_: LinkageError))) => ()
        case other => fail(s"expected NotLoaded with a LinkageError, got $other")
      }
  }

  test("verifyEd25519Signature in a V2 script is unavailable, not a script failure") {
    run(
      PlutusLanguage.V2,
      s"[ (builtin verifyEd25519Signature) ${bytes(32)} ${bytes(0)} ${bytes(64)} ]"
    ) match
      case EvalResult.Failure(reason, _, _) =>
        assertEquals(reason, unavailable(DefaultFun.VerifyEd25519Signature))
      case other => fail(s"expected unavailability, got $other")
  }

  test("the secp256k1 builtins are unavailable") {
    run(
      PlutusLanguage.V2,
      s"[ (builtin verifyEcdsaSecp256k1Signature) ${bytes(33)} ${bytes(32)} ${bytes(64)} ]"
    ) match
      case EvalResult.Failure(reason, _, _) =>
        assertEquals(reason, unavailable(DefaultFun.VerifyEcdsaSecp256k1Signature))
      case other => fail(s"expected unavailability, got $other")
    run(
      PlutusLanguage.V3,
      s"[ (builtin verifySchnorrSecp256k1Signature) ${bytes(32)} ${bytes(32)} ${bytes(64)} ]"
    ) match
      case EvalResult.Failure(reason, _, _) =>
        assertEquals(reason, unavailable(DefaultFun.VerifySchnorrSecp256k1Signature))
      case other => fail(s"expected unavailability, got $other")
    assertEquals(
      Secp256k1.verifyEcdsa(Array.fill(33)(2), Array.fill(32)(0), Array.fill(64)(0)),
      Left(Secp256k1.Error.NativeUnavailable)
    )
  }

  test("the BLS12-381 builtins are unavailable") {
    run(PlutusLanguage.V3, s"[ (builtin bls12_381_G1_uncompress) (con bytestring #$G1) ]") match
      case EvalResult.Failure(reason, _, _) =>
        assertEquals(reason, unavailable(DefaultFun.Bls12_381_G1_uncompress))
      case other => fail(s"expected unavailability, got $other")
  }

  test("a script without native builtins still evaluates") {
    run(
      PlutusLanguage.V2,
      "[ (builtin sha2_256) (con bytestring #00) ]"
    ) match
      case _: EvalResult.Success => ()
      case other                 => fail(s"expected success, got $other")
  }
