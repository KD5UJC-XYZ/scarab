// SPDX-License-Identifier: Apache-2.0
package lab.vrf

import lab.cbor.Bytes
import org.bouncycastle.crypto.digests.Blake2bDigest
import scala.util.control.NonFatal

/** Experimental Praos certificate predicate only. No TPraos, header validity, stake/key binding,
  * leader threshold, KES, operational certificates or epoch nonce evolution.
  */
object PraosVrfCertificate:
  enum Failure:
    case Malformed(reason: String)
    case InternalFailure(kind: String, detail: String)

  final class Slot private (val value: BigInt)
  object Slot:
    val MaxValue: BigInt = (BigInt(1) << 64) - 1
    def fromBigInt(value: BigInt): Either[Failure, Slot] =
      if value == null || value < 0 || value > MaxValue then
        Left(Failure.Malformed("slot must be unsigned Word64"))
      else Right(new Slot(value))

  sealed trait EpochNonce
  case object NeutralNonce extends EpochNonce
  final class Hash32 private (val bytes: Bytes) extends EpochNonce
  object Hash32:
    def fromBytes(bytes: Bytes): Either[Failure, Hash32] = protect {
      if bytes == null || bytes.size != 32 then
        Left(Failure.Malformed("nonce hash must be 32 bytes"))
      else Right(new Hash32(bytes))
    }

  final class Input private (val slot: Slot, val nonce: EpochNonce)
  object Input:
    def create(slot: Slot, nonce: EpochNonce): Either[Failure, Input] =
      if slot == null || nonce == null then Left(Failure.Malformed("null slot or nonce"))
      else Right(new Input(slot, nonce))

  enum Result:
    case Malformed(reason: String)
    case ProofRejected(reason: String)
    case OutputMismatch
    case InternalFailure(kind: String, detail: String)
    case VerifiedCertificate(output: Bytes)

  private def protect[A](body: => Either[Failure, A]): Either[Failure, A] =
    try body
    catch
      case NonFatal(e) =>
        Left(Failure.InternalFailure(e.getClass.getName, Option(e.getMessage).getOrElse("")))

  /** Raw Blake2b-256(BE8(slot) || nonce bytes); neutral contributes zero bytes, not 32 zeros. */
  def alpha(input: Input): Either[Failure, Bytes] = protect {
    if input == null then Left(Failure.Malformed("null Praos input"))
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
      if publicKey == null || publicKey.size != 32 then
        return Result.Malformed("public key must be 32 bytes")
      if proof == null || proof.size != 80 then return Result.Malformed("proof must be 80 bytes")
      if claimedOutput == null || claimedOutput.size != 64 then
        return Result.Malformed("claimed output must be 64 bytes")
      alpha(input) match
        case Left(Failure.Malformed(reason))             => Result.Malformed(reason)
        case Left(Failure.InternalFailure(kind, detail)) => Result.InternalFailure(kind, detail)
        case Right(message) =>
          primitive(publicKey, proof, message) match
            case StrictDraft03.Result.Verified(output) =>
              if output == null || output.size != 64 then
                Result.InternalFailure("primitive-contract", "verified output must be 64 bytes")
              else if output == claimedOutput then Result.VerifiedCertificate(output)
              else Result.OutputMismatch
            case StrictDraft03.Result.MalformedEnvelope(reason) => Result.Malformed(reason)
            case StrictDraft03.Result.Rejected(reason)          => Result.ProofRejected(reason)
            case StrictDraft03.Result.InternalFailure(kind, detail) =>
              Result.InternalFailure(kind, detail)
    catch
      case NonFatal(e) =>
        Result.InternalFailure(e.getClass.getName, Option(e.getMessage).getOrElse(""))
