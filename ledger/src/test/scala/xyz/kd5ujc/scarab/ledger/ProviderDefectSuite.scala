// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.kes.Sum6Kes
import xyz.kd5ujc.scarab.crypto.opcert.OperationalCertificate
import xyz.kd5ujc.scarab.crypto.vrf.{PraosVrfCertificate as Vrf, StrictDraft03}
import xyz.kd5ujc.scarab.crypto.witness.{VerificationError, VerificationResult, WitnessInputError}
import xyz.kd5ujc.scarab.ledger.rules.Test

/** A provider defect is never a predicate failure (issue 393): an injected failing provider makes
  * the rule return the [[ProviderDefect]] beside its refusals, and an invalid signature, proof or
  * certificate is still the rule's own rejection.
  */
class ProviderDefectSuite extends FunSuite:
  private val boom = new IllegalStateException("provider broke")
  private val SlotsPerKesPeriod = BigInt(129600)
  private val MaxKesEvolutions = BigInt(62)

  private val header: Header.Shelley =
    Files
      .readAllLines(Path.of("fixtures/preview/alonzo-first-20.hex"))
      .asScala
      .find(_.nonEmpty)
      .flatMap(l => Bytes.fromHex(l).toOption)
      .flatMap(b => Block.decodeShelley(b).toOption)
      .collect { case b: Block.Shelley => b.header }
      .getOrElse(fail("fixture header"))

  // Ed25519 (witnesses)

  test("an Ed25519 provider failure is a defect, not an invalid witness") {
    val failing = Left(VerificationError.ImplementationFailure(boom))
    assertEquals(ProviderDefect.witness(failing), Left(ProviderDefect.Ed25519(failing.value)))
    assertEquals(ProviderDefect.Ed25519(failing.value).cause, Some(boom))
  }

  test("a transaction whose witness verifier fails has no verdict, not InvalidWitnesses") {
    val signer = TestLedger.key()
    val body = TestLedger.body("provider-defect", Vector.empty, Vector.empty, 0)
    val tx = TestLedger.tx(body, Vector(signer), Witnesses.empty)
    val failing = VerificationError.ImplementationFailure(boom)
    val verdicts = new Verdicts:
      export Verdicts.Direct.{keyHash, opCert, praosVrf, takePrepared, tpraosVrf}
      def witness(vkey: Bytes, signature: Bytes, message: Bytes): Either[ProviderDefect, Boolean] =
        Left(ProviderDefect.Ed25519(failing))
    def check(rules: Verdicts) = TxChecks.verifiedWits(tx, rules)
    assertEquals(
      check(verdicts),
      Left(LedgerFailure.Unverifiable(body.id, ProviderDefect.Ed25519(failing)))
    )
    assertEquals(check(Verdicts.Direct), Right(Test.pass))
  }

  test("a rejected or malformed witness is false, never a defect") {
    assertEquals(ProviderDefect.witness(Right(VerificationResult.SignatureRejected)), Right(false))
    assertEquals(
      ProviderDefect.witness(Left(WitnessInputError.MalformedSignatureLength(3))),
      Right(false)
    )
    assertEquals(ProviderDefect.witness(Right(VerificationResult.SignatureVerified)), Right(true))
    val key = Bytes.fromArray(Array.fill(32)(7.toByte))
    val sig = Bytes.fromArray(Array.fill(64)(9.toByte))
    assertEquals(
      Verdicts.Direct.witness(key, sig, Bytes.fromArray(Array[Byte](1, 2))),
      Right(false)
    )
  }

  // VRF

  test("a VRF provider failure or broken contract is a defect") {
    val internal = Vrf.Result.InternalFailure(boom)
    assertEquals(vrf(internal), Left(ProviderDefect.VrfVerifier(internal)))
    assertEquals(ProviderDefect.VrfVerifier(internal).cause, Some(boom))
    val contract = Vrf.Result.PrimitiveContractViolation(Some(3))
    assertEquals(vrf(contract), Left(ProviderDefect.VrfVerifier(contract)))
    val malformed = Vrf.Result.Malformed(Vrf.Failure.InternalFailure(boom))
    assertEquals(vrf(malformed), Left(ProviderDefect.VrfVerifier(malformed)))
    val input = Vrf.Failure.InternalFailure(boom)
    assertEquals(
      ProviderDefect.vrfInput(Left(input))(PraosError.VrfInput(_)),
      Left(ProviderDefect.VrfInput(input))
    )
    assertEquals(
      ProviderDefect.vrfInput(Left(Vrf.Failure.MissingSlot))(PraosError.VrfInput(_)),
      Left(PraosError.VrfInput(Vrf.Failure.MissingSlot))
    )
  }

  private def vrf(result: Vrf.Result) = ProviderDefect.vrf(result)(PraosError.VrfRejected(_))

  test("an invalid VRF proof is still a verdict") {
    List(
      Vrf.Result.OutputMismatch,
      Vrf.Result.ProofRejected(StrictDraft03.Rejection.ChallengeMismatch),
      Vrf.Result.Malformed(Vrf.Failure.MissingClaimedOutput)
    ).foreach(r => assertEquals(vrf(r), Left(PraosError.VrfRejected(r))))
    val output = Bytes.fromArray(Array.fill(64)(1.toByte))
    assertEquals(vrf(Vrf.Result.VerifiedCertificate(output)), Right(output))
    val real = header.vrf match
      case HeaderVrf.TPraos(_, leader) =>
        Verdicts.Direct.tpraosVrf(
          Bytes.fromArray(Array[Byte](1)),
          header.vrfVkey,
          leader.proof,
          leader.output
        )
      case other => fail(s"unexpected $other")
    assertEquals(vrf(real), Left(PraosError.VrfRejected(real)))
    assert(!real.isInstanceOf[Vrf.Result.VerifiedCertificate], real.toString)
  }

  // KES and the certificate signature

  private val okKes: (Bytes, Int, Bytes, Bytes) => Either[Sum6Kes.Failure, Sum6Kes.Result] =
    (_, _, _, _) => Right(Sum6Kes.Result.SuppliedMessageSignatureVerified)
  private val okCert: (
      Bytes,
      Bytes,
      BigInt,
      BigInt,
      Bytes
  ) => Either[OperationalCertificate.Failure, OperationalCertificate.Result] =
    (_, _, _, _, _) => Right(OperationalCertificate.Result.OperationalCertificateSignatureVerified)

  private def withKes(f: Sum6Kes.Failure) =
    OCertRules.verifySignaturesWith(
      header,
      SlotsPerKesPeriod,
      MaxKesEvolutions,
      (_, _, _, _) => Left(f),
      okCert
    )

  private def withCert(f: OperationalCertificate.Failure) =
    OCertRules.verifySignaturesWith(
      header,
      SlotsPerKesPeriod,
      MaxKesEvolutions,
      okKes,
      (_, _, _, _, _) => Left(f)
    )

  test("a KES provider failure is a defect, not a rejected header") {
    val leaf = VerificationError.ImplementationFailure(boom)
    List(
      Sum6Kes.Failure.Defect(boom),
      Sum6Kes.Failure.LeafFailure(leaf),
      Sum6Kes.Failure.HashContractViolation(None),
      Sum6Kes.Failure.LeafContractViolation,
      Sum6Kes.Failure.PeriodInvariant(1)
    ).foreach(f => assertEquals(withKes(f), Left(ProviderDefect.Kes(f))))
    assertEquals(ProviderDefect.Kes(Sum6Kes.Failure.Defect(boom)).cause, Some(boom))
    assertEquals(ProviderDefect.Kes(Sum6Kes.Failure.LeafFailure(leaf)).cause, Some(boom))
  }

  test("a certificate-signature provider failure is a defect, not a rejected header") {
    List(
      OperationalCertificate.Failure.Defect(boom),
      OperationalCertificate.Failure.PrimitiveFailure(
        VerificationError.ImplementationFailure(boom)
      ),
      OperationalCertificate.Failure.PrimitiveContractViolation(
        OperationalCertificate.PrimitiveReply.MissingVerdict
      )
    ).foreach(f => assertEquals(withCert(f), Left(ProviderDefect.OpCertSignature(f))))
  }

  test("an invalid KES input or signature is still an ordinary rejection") {
    val bad = Sum6Kes.Failure.InvalidLength(Sum6Kes.Component.Signature, 3, 448)
    assertEquals(withKes(bad), Left(OCertError.KesFailure(bad)))
    assertEquals(
      OCertRules.verifySignaturesWith(
        header,
        SlotsPerKesPeriod,
        MaxKesEvolutions,
        (_, _, _, _) => Right(Sum6Kes.Result.SignatureRejected),
        okCert
      ),
      Left(OCertError.KesSignatureRejected)
    )
    val cert = OperationalCertificate.Failure.InvalidHotKeyLength(3)
    assertEquals(withCert(cert), Left(OCertError.OpCertFailure(cert)))
    assertEquals(
      OCertRules.verifySignaturesWith(
        header,
        SlotsPerKesPeriod,
        MaxKesEvolutions,
        okKes,
        (_, _, _, _, _) => Right(OperationalCertificate.Result.SignatureRejected)
      ),
      Left(OCertError.OpCertSignatureRejected)
    )
  }

  private enum Verifier:
    case Cert, Kes

  /** The OCERT signature checks over a certificate verifier answering `cert` and a KES verifier
    * answering `kes`, with the verifiers called, in order.
    */
  private def ordered(
      cert: Either[OperationalCertificate.Failure, OperationalCertificate.Result],
      kes: Either[Sum6Kes.Failure, Sum6Kes.Result]
  ): (Either[OCertError | ProviderDefect, Unit], Vector[Verifier]) =
    val calls = Vector.newBuilder[Verifier]
    val result = OCertRules.verifySignaturesWith(
      header,
      SlotsPerKesPeriod,
      MaxKesEvolutions,
      (_, _, _, _) =>
        calls += Verifier.Kes
        kes
      ,
      (_, _, _, _, _) =>
        calls += Verifier.Cert
        cert
    )
    (result, calls.result())

  test("the cold-key signature is verified before KES, as in the reference (Praos.hs 637-640)") {
    val certDefect = OperationalCertificate.Failure.Defect(boom)
    val kesDefect = Sum6Kes.Failure.Defect(boom)
    // a rejected KES signature does not mask a defect of the certificate verifier: the defect is
    // met first, and KES is not asked
    assertEquals(
      ordered(Left(certDefect), Right(Sum6Kes.Result.SignatureRejected)),
      (Left(ProviderDefect.OpCertSignature(certDefect)), Vector(Verifier.Cert))
    )
    // a KES defect cannot stop processing of a header whose certificate the reference rejects
    // without asking KES
    assertEquals(
      ordered(Right(OperationalCertificate.Result.SignatureRejected), Left(kesDefect)),
      (Left(OCertError.OpCertSignatureRejected), Vector(Verifier.Cert))
    )
    // a valid certificate reaches KES, whose defect is then the answer
    assertEquals(
      ordered(
        Right(OperationalCertificate.Result.OperationalCertificateSignatureVerified),
        Left(kesDefect)
      ),
      (Left(ProviderDefect.Kes(kesDefect)), Vector(Verifier.Cert, Verifier.Kes))
    )
  }

  test("the real verifiers accept the real header and reject a tampered KES signature") {
    assertEquals(
      OCertRules.verifySignatures(header, SlotsPerKesPeriod, MaxKesEvolutions),
      Right(())
    )
    val flipped = header.kesSignature.toArray
    flipped(10) = (flipped(10) ^ 1).toByte
    val bad = header.copy(kesSignature = Bytes.fromArray(flipped))
    assertEquals(
      OCertRules.verifySignatures(bad, SlotsPerKesPeriod, MaxKesEvolutions),
      Left(OCertError.KesSignatureRejected)
    )
  }
