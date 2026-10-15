// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import java.security.MessageDigest
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.plutus.ExUnits
import munit.FunSuite
import org.bouncycastle.asn1.x9.X9ECParameters
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.crypto.params.{ECDomainParameters, ECPrivateKeyParameters}
import org.bouncycastle.crypto.signers.{ECDSASigner, HMacDSAKCalculator}
import org.bouncycastle.crypto.digests.SHA256Digest
import scalus.cardano.ledger.CardanoInfo
import scalus.uplc.Program

/** The evaluator's native dependencies (BLS12-381 through blst, secp256k1 through JNI) load and
  * compute inside the JVM that runs the tests, and a script that spends more than its budget fails.
  */
class ScalusEvaluatorSuite extends FunSuite:
  private val evaluator = ScalusEvaluator.pure

  private val G1 =
    "97f1d3a73197d7942695638c4fa9ac0fc3688c4f9774b905a14e3a3f171bac586c55e83ff97a1aeffb3af00adb22c6bb"
  private val G2 =
    "93e02b6052719f607dacd3a088274f65596bd0d09920b61ab5da61bbdc7f5049334cf11213945d57e5ac7d055d042b7e024aa2b2f08f0a91260805272dc51051c6e47ad4fa403b02b4510b647ae3d1770bac0326a805bbefd48056c8c121bdb8"

  private def v3Model: Vector[Long] =
    CardanoInfo.mainnet.protocolParams.costModels.models(2).toVector

  private def v2Model: Vector[Long] =
    CardanoInfo.mainnet.protocolParams.costModels.models(1).toVector

  /** `assert (cond)`: unit when true, `error` when false (lazily, through `delay`). */
  private def assertion(cond: String): String =
    s"(force [ (force (builtin ifThenElse)) $cond (delay (con unit ())) (delay (error)) ])"

  /** Version 1.0.0 unless the program needs `constr` /`case`: V1 and V2 accept 1.1.0 only from
    * protocol 11.
    */
  private def script(uplc: String, version: String = "1.0.0"): Bytes =
    Bytes.fromArray(
      Program.parseUplc(s"(program $version $uplc)").fold(sys.error, identity).cborEncoded
    )

  private def run(
      uplc: String,
      language: PlutusLanguage = PlutusLanguage.V3,
      protocol: Int = 9,
      budget: ExUnits = ExUnits(10000000, 10000000000L)
  ) =
    evaluator.evaluate(
      EvalRequest(
        language,
        protocol,
        script(uplc),
        Vector(PlutusData.unit),
        if language == PlutusLanguage.V3 then v3Model else v2Model,
        budget
      )
    )

  test("BLS12-381 G1 arithmetic evaluates through blst") {
    val g = s"[ (builtin bls12_381_G1_uncompress) (con bytestring #$G1) ]"
    val body = assertion(
      s"[ (builtin bls12_381_G1_equal) [ (builtin bls12_381_G1_add) g g ] " +
        "[ (builtin bls12_381_G1_scalarMul) (con integer 2) g ] ]"
    )
    val result = run(s"(lam ctx [ (lam g $body) $g ])")
    assert(result.isSuccess, result.toString)
    assert(result.spent.steps > 0)
  }

  test("BLS12-381 pairing: e(3G1, 5G2) = e(15G1, G2)") {
    val g1 = s"[ (builtin bls12_381_G1_uncompress) (con bytestring #$G1) ]"
    val g2 = s"[ (builtin bls12_381_G2_uncompress) (con bytestring #$G2) ]"
    def mul1(k: Int) = s"[ (builtin bls12_381_G1_scalarMul) (con integer $k) g1 ]"
    def mul2(k: Int) = s"[ (builtin bls12_381_G2_scalarMul) (con integer $k) g2 ]"
    val body = assertion(
      "[ (builtin bls12_381_finalVerify) " +
        s"[ (builtin bls12_381_millerLoop) ${mul1(3)} ${mul2(5)} ] " +
        s"[ (builtin bls12_381_millerLoop) ${mul1(15)} g2 ] ]"
    )
    val result = run(s"(lam ctx [ (lam g1 [ (lam g2 $body) $g2 ]) $g1 ])")
    assert(result.isSuccess, result.toString)
  }

  test("BLS12-381 equation that does not hold fails the script") {
    val g = s"[ (builtin bls12_381_G1_uncompress) (con bytestring #$G1) ]"
    val body = assertion(
      "[ (builtin bls12_381_G1_equal) [ (builtin bls12_381_G1_add) g g ] " +
        "[ (builtin bls12_381_G1_scalarMul) (con integer 3) g ] ]"
    )
    val result = run(s"(lam ctx [ (lam g $body) $g ])")
    assert(!result.isSuccess, result.toString)
  }

  private def hex(b: Array[Byte]): String = b.map(x => f"${x & 0xff}%02x").mkString

  /** secp256k1 ECDSA key and a deterministic low-s signature over a 32-byte message. */
  private def ecdsa(message: Array[Byte]): (Array[Byte], Array[Byte]) =
    val params: X9ECParameters = CustomNamedCurves.getByName("secp256k1")
    val domain = ECDomainParameters(params.getCurve, params.getG, params.getN, params.getH)
    val d = java.math.BigInteger(1, MessageDigest.getInstance("SHA-256").digest("key".getBytes))
    val signer = ECDSASigner(HMacDSAKCalculator(SHA256Digest()))
    signer.init(true, ECPrivateKeyParameters(d, domain))
    val Array(r, s0) = signer.generateSignature(message)
    val s = if s0.compareTo(params.getN.shiftRight(1)) > 0 then params.getN.subtract(s0) else s0
    def fixed(x: java.math.BigInteger) =
      val raw = x.toByteArray.dropWhile(_ == 0)
      Array.fill(32 - raw.length)(0.toByte) ++ raw
    (params.getG.multiply(d).getEncoded(true), fixed(r) ++ fixed(s))

  test("secp256k1 ECDSA verification evaluates through the JNI library") {
    val message = MessageDigest.getInstance("SHA-256").digest("cardano".getBytes)
    val (pub, sig) = ecdsa(message)
    def verify(msg: Array[Byte]) = assertion(
      s"[ (builtin verifyEcdsaSecp256k1Signature) (con bytestring #${hex(pub)}) " +
        s"(con bytestring #${hex(msg)}) (con bytestring #${hex(sig)}) ]"
    )
    val ok = run(s"(lam ctx ${verify(message)})", PlutusLanguage.V2, 8)
    assert(ok.isSuccess, ok.toString)
    val tampered = run(s"(lam ctx ${verify(message.reverse)})", PlutusLanguage.V2, 8)
    assert(!tampered.isSuccess, tampered.toString)
  }

  test("a script that exceeds its budget fails with OutOfBudget") {
    val g = s"[ (builtin bls12_381_G1_uncompress) (con bytestring #$G1) ]"
    val result = run(
      s"(lam ctx [ (builtin bls12_381_G1_add) $g $g ])",
      budget = ExUnits(1000, 1000)
    )
    result match
      case EvalResult.Failure(EvalFailure.OutOfBudget, _, _) => ()
      case other                                             => fail(other.toString)
  }

  test("a script that is not a flat program is a decode failure") {
    val result = evaluator.evaluate(
      EvalRequest(
        PlutusLanguage.V2,
        8,
        Bytes(Vector(0x43, 0x01, 0x02, 0x03).map(_.toByte)),
        Vector(PlutusData.unit),
        v2Model,
        ExUnits(1000000, 1000000000)
      )
    )
    result match
      case EvalResult.Failure(EvalFailure.Decode(_), _, _) => ()
      case other                                           => fail(other.toString)
  }

  test("a failing script's trace output is part of the failure reason") {
    val result = run(
      """(lam ctx [ (lam _ (error)) [ [ (force (builtin trace)) (con string "H41") ] (con unit ()) ] ])""",
      PlutusLanguage.V2,
      protocol = 10
    )
    result match
      case EvalResult.Failure(EvalFailure.Script(reason), _, logs) =>
        assertEquals(logs, Vector("H41"))
        assert(reason.contains("trace: H41"), reason)
      case other => fail(other.toString)
  }
