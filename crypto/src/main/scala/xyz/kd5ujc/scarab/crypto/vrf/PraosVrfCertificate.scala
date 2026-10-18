// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.crypto.vrf

import xyz.kd5ujc.scarab.codec.Bytes
import org.bouncycastle.crypto.digests.Blake2bDigest
import scala.util.control.NonFatal

/** Experimental Praos certificate predicate only. No TPraos, header validity, stake/key binding,
  * leader threshold, KES, operational certificates or epoch nonce evolution.
  */
object PraosVrfCertificate:
  enum Failure:
    case MissingSlot, MissingNonce, MissingInput, MissingNonceHash
    case SlotOutOfRange(actual: BigInt)
    case NonceHashLength(actual: Int)
    case Envelope(cause: StrictDraft03.EnvelopeError)
    case MissingClaimedOutput
    case ClaimedOutputLength(actual: Int)
    case InternalFailure(cause: Throwable)

  final class Slot private (val value: BigInt)
  object Slot:
    val MaxValue: BigInt = (BigInt(1) << 64) - 1
    def fromBigInt(value: BigInt): Either[Failure, Slot] =
      if value == null then Left(Failure.MissingSlot)
      else if value < 0 || value > MaxValue then Left(Failure.SlotOutOfRange(value))
      else Right(new Slot(value))

  sealed trait EpochNonce
  case object NeutralNonce extends EpochNonce
  final class Hash32 private (val bytes: Bytes) extends EpochNonce
  object Hash32:
    def fromBytes(bytes: Bytes): Either[Failure, Hash32] = protect {
      if bytes == null then Left(Failure.MissingNonceHash)
      else if bytes.size != 32 then Left(Failure.NonceHashLength(bytes.size))
      else Right(new Hash32(bytes))
    }

  final class Input private (val slot: Slot, val nonce: EpochNonce)
  object Input:
    def create(slot: Slot, nonce: EpochNonce): Either[Failure, Input] =
      if slot == null then Left(Failure.MissingSlot)
      else if nonce == null then Left(Failure.MissingNonce)
      else Right(new Input(slot, nonce))

  enum Result:
    case Malformed(cause: Failure)
    case ProofRejected(cause: StrictDraft03.Rejection)
    case OutputMismatch
    case InternalFailure(cause: Throwable)
    case PrimitiveContractViolation(actual: Option[Int])
    case VerifiedCertificate(output: Bytes)

  private def protect[A](body: => Either[Failure, A]): Either[Failure, A] =
    try body
    catch
      case NonFatal(e) =>
        Left(Failure.InternalFailure(e))

  /** Raw Blake2b-256(BE8(slot) || nonce bytes); neutral contributes zero bytes, not 32 zeros. */
  def alpha(input: Input): Either[Failure, Bytes] = protect {
    if input == null then Left(Failure.MissingInput)
    else
      val slot = Array.tabulate[Byte](8)(i => ((input.slot.value >> (8 * (7 - i))) & 255).toByte)
      val nonce = input.nonce match
        case NeutralNonce => Array.emptyByteArray
        case hash: Hash32 => hash.bytes.toArray
      val digest = new Blake2bDigest(256)
      digest.update(slot, 0, slot.length)
      digest.update(nonce, 0, nonce.length)
      val output = new Array[Byte](32)
      digest.doFinal(output, 0)
      Right(Bytes.fromArray(output))
  }

  def verify(input: Input, publicKey: Bytes, proof: Bytes, claimedOutput: Bytes): Result =
    verifyWith(input, publicKey, proof, claimedOutput, StrictDraft03.verify)

  // Package-only failure seam; the public API cannot supply an unchecked output or arbitrary alpha.
  private[vrf] def verifyWith(
      input: Input,
      publicKey: Bytes,
      proof: Bytes,
      claimedOutput: Bytes,
      primitive: (Bytes, Bytes, Bytes) => StrictDraft03.Result
  ): Result =
    try
      validateEnvelope(publicKey, proof, claimedOutput) match
        case Left(error) => Result.Malformed(error)
        case Right(_) =>
          alpha(input) match
            case Left(Failure.InternalFailure(cause)) => Result.InternalFailure(cause)
            case Left(error)                          => Result.Malformed(error)
            case Right(message) => classify(primitive(publicKey, proof, message), claimedOutput)
    catch case NonFatal(e) => Result.InternalFailure(e)

  private def validateEnvelope(
      publicKey: Bytes,
      proof: Bytes,
      claimedOutput: Bytes
  ): Either[Failure, Unit] =
    import StrictDraft03.EnvelopeError
    if publicKey == null then Left(Failure.Envelope(EnvelopeError.MissingPublicKey))
    else if publicKey.size != 32 then
      Left(Failure.Envelope(EnvelopeError.PublicKeyLength(publicKey.size)))
    else if proof == null then Left(Failure.Envelope(EnvelopeError.MissingProof))
    else if proof.size != 80 then Left(Failure.Envelope(EnvelopeError.ProofLength(proof.size)))
    else if claimedOutput == null then Left(Failure.MissingClaimedOutput)
    else if claimedOutput.size != 64 then Left(Failure.ClaimedOutputLength(claimedOutput.size))
    else Right(())

  /** Verify a certificate over an arbitrary, already derived alpha string (TPraos builds its own
    * seeds). Same draft-03 verifier and output binding as [[verify]].
    */
  def verifyAlpha(alpha: Bytes, publicKey: Bytes, proof: Bytes, claimedOutput: Bytes): Result =
    validateEnvelope(publicKey, proof, claimedOutput).fold(
      Result.Malformed.apply,
      _ => classify(StrictDraft03.verify(publicKey, proof, alpha), claimedOutput)
    )

  private def classify(r: StrictDraft03.Result, claimedOutput: Bytes): Result = r match
    case StrictDraft03.Result.Verified(output) =>
      if output == null || output.size != 64 then
        Result.PrimitiveContractViolation(Option(output).map(_.size))
      else if output == claimedOutput then Result.VerifiedCertificate(output)
      else Result.OutputMismatch
    case StrictDraft03.Result.MalformedEnvelope(reason) =>
      Result.Malformed(Failure.Envelope(reason))
    case StrictDraft03.Result.Rejected(reason) => Result.ProofRejected(reason)
    case StrictDraft03.Result.InternalFailure(cause) =>
      Result.InternalFailure(cause)
