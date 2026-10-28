// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.kes.Sum6Kes
import xyz.kd5ujc.scarab.crypto.opcert.OperationalCertificate
import xyz.kd5ujc.scarab.crypto.vrf.PraosVrfCertificate as PraosVrf
import xyz.kd5ujc.scarab.crypto.witness.{VerificationError, VerificationResult, WitnessInputError}

/** A crypto provider that failed, as opposed to a signature, proof or certificate that is invalid.
  * The two are different facts: an invalid input is a predicate failure and the peer that sent it
  * is at fault; a provider that threw, or broke its own contract, says nothing about the input, and
  * this implementation has no verdict on it.
  *
  * The reference separates them too. Its verification primitives answer only accept or reject:
  * cardano-base 9c964bec `Cardano.Crypto.DSIGN.Ed25519.verifyDSIGN` (`Right ()` or
  * `Left "Verification failed"`) and `Cardano.Crypto.VRF.Praos.verify` (`Just output` or
  * `Nothing`); a failure of the library itself is a Haskell exception (`cOrThrowError`,
  * `throwOnErrno`, `error`). The Praos rules turn only the answer into a predicate failure:
  * ouroboros-consensus 82ecba32 `Ouroboros/Consensus/Protocol/Praos.hs` 587–592 (`verifyCertified
  * ?! VRFKeyBadProof`) and 637–640 (`verifySignedDSIGN ?!: InvalidSignatureOCERT`,
  * `verifySignedKES ?!: InvalidKesSignatureOCERT`). An exception passes `?!` untouched.
  *
  * Only the cases below are defects; every other crypto failure case stays an ordinary rejection of
  * the input. The rules return a defect as a value beside their refusals ([[ProviderDefect.vrf]],
  * [[ProviderDefect.kes]], …), never inside one, and the node does what the reference does with the
  * exception: on a block it stops (`Fatal.Unverifiable`), on a peer's header it closes that peer's
  * connection, and nothing is recorded as invalid or blamed. See `docs/design/domain-errors.md`.
  */
enum ProviderDefect:
  /** The Ed25519 verifier of a transaction witness failed. */
  case Ed25519(error: VerificationError)

  /** The VRF verifier threw or broke its contract: `InternalFailure`, `PrimitiveContractViolation`
    * or a `Malformed` whose cause is an internal failure.
    */
  case VrfVerifier(result: PraosVrf.Result)

  /** The VRF input of the slot could not be built because the provider threw. */
  case VrfInput(failure: PraosVrf.Failure)

  /** The KES verifier threw, or broke one of its own invariants. */
  case Kes(failure: Sum6Kes.Failure)

  /** The verifier of the operational certificate's cold-key signature failed. */
  case OpCertSignature(failure: OperationalCertificate.Failure)

  /** The original exception, where the provider threw one. */
  def cause: Option[Throwable] = this match
    case Ed25519(VerificationError.ImplementationFailure(e))                          => Some(e)
    case VrfVerifier(PraosVrf.Result.InternalFailure(e))                              => Some(e)
    case VrfVerifier(PraosVrf.Result.Malformed(PraosVrf.Failure.InternalFailure(e)))  => Some(e)
    case VrfInput(PraosVrf.Failure.InternalFailure(e))                                => Some(e)
    case Kes(Sum6Kes.Failure.Defect(e))                                               => Some(e)
    case Kes(Sum6Kes.Failure.LeafFailure(VerificationError.ImplementationFailure(e))) => Some(e)
    case OpCertSignature(OperationalCertificate.Failure.Defect(e))                    => Some(e)
    case OpCertSignature(
          OperationalCertificate.Failure.PrimitiveFailure(
            VerificationError.ImplementationFailure(e)
          )
        ) =>
      Some(e)
    case Ed25519(_) | VrfVerifier(_) | VrfInput(_) | Kes(_) | OpCertSignature(_) => None

  def message: String = this match
    case Ed25519(e)         => s"Ed25519 verifier defect: $e"
    case VrfVerifier(r)     => s"VRF verifier defect: $r"
    case VrfInput(f)        => s"VRF input defect: $f"
    case Kes(f)             => s"KES verifier defect: $f"
    case OpCertSignature(f) => s"certificate signature verifier defect: ${f.message}"

object ProviderDefect:
  /** The defect a VRF answer records, if it records one. */
  def ofVrf(result: PraosVrf.Result): Option[ProviderDefect] = result match
    case _: PraosVrf.Result.InternalFailure | _: PraosVrf.Result.PrimitiveContractViolation |
        PraosVrf.Result.Malformed(_: PraosVrf.Failure.InternalFailure) =>
      Some(VrfVerifier(result))
    case PraosVrf.Result.Malformed(_) | PraosVrf.Result.ProofRejected(_) |
        PraosVrf.Result.OutputMismatch | PraosVrf.Result.VerifiedCertificate(_) =>
      None

  /** The output of a verified VRF certificate; a provider defect as itself, and any other answer
    * (malformed, rejected, mismatched) as the refusal `refused` makes of it.
    */
  def vrf[E](result: PraosVrf.Result)(
      refused: PraosVrf.Result => E
  ): Either[E | ProviderDefect, Bytes] =
    result match
      case PraosVrf.Result.VerifiedCertificate(output) => Right(output)
      case other => Left(ofVrf(other).getOrElse(refused(other)))

  /** The VRF input; a provider defect as itself, an input outside the domain as `refused`. */
  def vrfInput[E, A](
      result: Either[PraosVrf.Failure, A]
  )(refused: PraosVrf.Failure => E): Either[E | ProviderDefect, A] = result match
    case Right(a)                                  => Right(a)
    case Left(f: PraosVrf.Failure.InternalFailure) => Left(VrfInput(f))
    case Left(f)                                   => Left(refused(f))

  /** Whether a witness signature verifies. Malformed inputs and rejected signatures are `false`; a
    * verifier failure is the defect.
    */
  def witness(
      result: Either[WitnessInputError | VerificationError, VerificationResult]
  ): Either[ProviderDefect, Boolean] = result match
    case Right(VerificationResult.SignatureVerified) => Right(true)
    case Right(VerificationResult.SignatureRejected) => Right(false)
    case Left(_: WitnessInputError)                  => Right(false)
    case Left(error: VerificationError)              => Left(Ed25519(error))

  /** The KES verdict; a provider defect as itself, an input failure as `refused`. */
  def kes[E, A](
      result: Either[Sum6Kes.Failure, A]
  )(refused: Sum6Kes.Failure => E): Either[E | ProviderDefect, A] = result match
    case Right(a) => Right(a)
    case Left(
          f @ (Sum6Kes.Failure.Defect(_) | Sum6Kes.Failure.LeafFailure(_) |
          Sum6Kes.Failure.HashContractViolation(_) | Sum6Kes.Failure.LeafContractViolation |
          Sum6Kes.Failure.PeriodInvariant(_))
        ) =>
      Left(Kes(f))
    case Left(
          f @ (Sum6Kes.Failure.MissingComponent(_) | Sum6Kes.Failure.InvalidLength(_, _, _) |
          Sum6Kes.Failure.PeriodOutOfRange(_) | Sum6Kes.Failure.MessageTooLarge(_) |
          Sum6Kes.Failure.LeafInput(_))
        ) =>
      Left(refused(f))

  /** As [[kes]] for the operational certificate's cold-key signature. */
  def opCert[E, A](
      result: Either[OperationalCertificate.Failure, A]
  )(refused: OperationalCertificate.Failure => E): Either[E | ProviderDefect, A] = result match
    case Right(a) => Right(a)
    case Left(
          f @ (OperationalCertificate.Failure.PrimitiveFailure(_) |
          OperationalCertificate.Failure.PrimitiveContractViolation(_) |
          OperationalCertificate.Failure.Defect(_))
        ) =>
      Left(OpCertSignature(f))
    case Left(
          f @ (OperationalCertificate.Failure.MissingColdKey |
          OperationalCertificate.Failure.MissingHotKey |
          OperationalCertificate.Failure.MissingSignature |
          OperationalCertificate.Failure.MissingCertificate |
          OperationalCertificate.Failure.InvalidHotKeyLength(_) |
          OperationalCertificate.Failure.InvalidCounter(_) |
          OperationalCertificate.Failure.InvalidStartPeriod(_) |
          OperationalCertificate.Failure.ColdKey(_) | OperationalCertificate.Failure.Signature(_))
        ) =>
      Left(refused(f))
