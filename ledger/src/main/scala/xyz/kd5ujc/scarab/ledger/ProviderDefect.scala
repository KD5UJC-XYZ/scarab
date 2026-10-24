// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.crypto.kes.Sum6Kes
import xyz.kd5ujc.scarab.crypto.opcert.OperationalCertificate
import xyz.kd5ujc.scarab.crypto.vrf.PraosVrfCertificate as PraosVrf
import xyz.kd5ujc.scarab.crypto.witness.{VerificationError, VerificationResult, WitnessInputError}

/** A crypto provider that failed, as opposed to a signature, proof or certificate that is invalid.
  * The two are different facts: an invalid input is a predicate failure and the peer that sent it
  * is at fault; a provider that threw, or broke its own contract, says nothing about the input.
  *
  * The reference separates them too. In cardano-base 9c964bec the verification primitives map the C
  * return code to a verdict (`Cardano.Crypto.DSIGN.Ed25519.verifyDSIGN`, `Right ()` or
  * `Left "Verification failed"`; `Cardano.Crypto.VRF.Praos.verify`, `Just output` or `Nothing`),
  * and a failure of the library itself is a Haskell exception (`cOrThrowError` and `throwOnErrno`
  * in `Ed25519.hs`, `error` in `Praos.hs`). Nothing in consensus turns such an exception into a
  * predicate failure. Only the cases below are defects; every other crypto failure case stays an
  * ordinary rejection of the input.
  */
enum ProviderDefect:
  case Ed25519(error: VerificationError)
  case VrfVerifier(result: PraosVrf.Result)
  case Kes(failure: Sum6Kes.Failure)
  case OpCertSignature(failure: OperationalCertificate.Failure)

  /** The original exception, where the provider threw one. */
  def cause: Option[Throwable] = this match
    case Ed25519(VerificationError.ImplementationFailure(e))                          => Some(e)
    case VrfVerifier(PraosVrf.Result.InternalFailure(e))                              => Some(e)
    case Kes(Sum6Kes.Failure.Defect(e))                                               => Some(e)
    case Kes(Sum6Kes.Failure.LeafFailure(VerificationError.ImplementationFailure(e))) => Some(e)
    case OpCertSignature(OperationalCertificate.Failure.Defect(e))                    => Some(e)
    case OpCertSignature(
          OperationalCertificate.Failure.PrimitiveFailure(
            VerificationError.ImplementationFailure(e)
          )
        ) =>
      Some(e)
    case _ => None

  def message: String = this match
    case Ed25519(e)         => s"Ed25519 provider defect: $e"
    case VrfVerifier(r)     => s"VRF provider defect: $r"
    case Kes(f)             => s"KES verifier defect: $f"
    case OpCertSignature(f) => s"certificate signature verifier defect: ${f.message}"

/** What a pure rule throws when a provider defect is met. A pure rule has no capability to raise
  * through, and the defect is not a failure of the input, so it leaves as an exception, as it does
  * in the reference. The session boundary ([[Fibers.scoped]] through `SessionEnd.scoped`, the
  * listener's handler boundary) turns it into `SessionEnd.Defect`, whose policy is `Fatal.Defect`
  * (exit 7): the node stops and no peer is blamed.
  */
final class ProviderDefectException(val defect: ProviderDefect)
    extends RuntimeException(defect.message, defect.cause.orNull)

object ProviderDefect:
  /** `result` unless it records a provider defect. */
  def checkVrf(result: PraosVrf.Result): PraosVrf.Result = result match
    case _: PraosVrf.Result.InternalFailure | _: PraosVrf.Result.PrimitiveContractViolation =>
      throw new ProviderDefectException(VrfVerifier(result))
    case PraosVrf.Result.Malformed(_) | PraosVrf.Result.ProofRejected(_) |
        PraosVrf.Result.OutputMismatch | PraosVrf.Result.VerifiedCertificate(_) =>
      result

  /** Whether a witness signature verifies. Malformed inputs and rejected signatures are `false`. */
  def witness(
      result: Either[WitnessInputError | VerificationError, VerificationResult]
  ): Boolean = result match
    case Right(VerificationResult.SignatureVerified) => true
    case Right(VerificationResult.SignatureRejected) => false
    case Left(_: WitnessInputError)                  => false
    case Left(error: VerificationError) => throw new ProviderDefectException(Ed25519(error))

  /** `result`, with a provider defect thrown and an input failure kept as the error. */
  def kes[A](result: Either[Sum6Kes.Failure, A]): Either[Sum6Kes.Failure, A] = result match
    case Left(
          f @ (Sum6Kes.Failure.Defect(_) | Sum6Kes.Failure.LeafFailure(_) |
          Sum6Kes.Failure.HashContractViolation(_) | Sum6Kes.Failure.LeafContractViolation |
          Sum6Kes.Failure.PeriodInvariant(_))
        ) =>
      throw new ProviderDefectException(Kes(f))
    case other => other

  /** As [[kes]] for the operational certificate's cold-key signature. */
  def opCert[A](
      result: Either[OperationalCertificate.Failure, A]
  ): Either[OperationalCertificate.Failure, A] = result match
    case Left(
          f @ (OperationalCertificate.Failure.PrimitiveFailure(_) |
          OperationalCertificate.Failure.PrimitiveContractViolation(_) |
          OperationalCertificate.Failure.Defect(_))
        ) =>
      throw new ProviderDefectException(OpCertSignature(f))
    case other => other
