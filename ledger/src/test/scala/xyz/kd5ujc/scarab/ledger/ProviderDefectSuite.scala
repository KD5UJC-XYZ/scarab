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

/** A provider defect is never a predicate failure (issue 393): an injected failing provider makes
  * the rule throw [[ProviderDefectException]], and an invalid signature, proof or certificate is
  * still the rule's own rejection.
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

  private def defect(body: => Any): ProviderDefect =
    intercept[ProviderDefectException](body).defect

  // Ed25519 (witnesses)

  test("an Ed25519 provider failure is a defect, not an invalid witness") {
    val failing = Left(VerificationError.ImplementationFailure(boom))
    assertEquals(defect(ProviderDefect.witness(failing)), ProviderDefect.Ed25519(failing.value))
    assertEquals(ProviderDefect.Ed25519(failing.value).cause, Some(boom))
  }

  test("a rejected or malformed witness is false, never a defect") {
    assertEquals(ProviderDefect.witness(Right(VerificationResult.SignatureRejected)), false)
    assertEquals(
      ProviderDefect.witness(Left(WitnessInputError.MalformedSignatureLength(3))),
      false
    )
    assertEquals(ProviderDefect.witness(Right(VerificationResult.SignatureVerified)), true)
    val key = Bytes.fromArray(Array.fill(32)(7.toByte))
    val sig = Bytes.fromArray(Array.fill(64)(9.toByte))
    assertEquals(Verdicts.Direct.witness(key, sig, Bytes.fromArray(Array[Byte](1, 2))), false)
  }

  // VRF

  test("a VRF provider failure or broken contract is a defect") {
    val internal = Vrf.Result.InternalFailure(boom)
    assertEquals(defect(ProviderDefect.checkVrf(internal)), ProviderDefect.VrfVerifier(internal))
    assertEquals(ProviderDefect.VrfVerifier(internal).cause, Some(boom))
    val contract = Vrf.Result.PrimitiveContractViolation(Some(3))
    assertEquals(defect(ProviderDefect.checkVrf(contract)), ProviderDefect.VrfVerifier(contract))
  }

  test("an invalid VRF proof is still a verdict") {
    List(
      Vrf.Result.OutputMismatch,
      Vrf.Result.ProofRejected(StrictDraft03.Rejection.ChallengeMismatch),
      Vrf.Result.VerifiedCertificate(Bytes.fromArray(Array.fill(64)(1.toByte)))
    ).foreach(r => assertEquals(ProviderDefect.checkVrf(r), r))
    val real = header.vrf match
      case HeaderVrf.TPraos(_, leader) =>
        Verdicts.Direct.tpraosVrf(
          Bytes.fromArray(Array[Byte](1)),
          header.vrfVkey,
          leader.proof,
          leader.output
        )
      case other => fail(s"unexpected $other")
    assertEquals(ProviderDefect.checkVrf(real), real)
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
    ).foreach(f => assertEquals(defect(withKes(f)), ProviderDefect.Kes(f)))
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
    ).foreach(f => assertEquals(defect(withCert(f)), ProviderDefect.OpCertSignature(f)))
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
