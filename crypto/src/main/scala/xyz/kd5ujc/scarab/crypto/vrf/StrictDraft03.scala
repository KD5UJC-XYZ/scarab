// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.crypto.vrf

import java.util.Arrays
import xyz.kd5ujc.scarab.codec.Bytes
import scala.util.control.NonFatal

/** Experimental public-only Cardano draft03 source-profile verifier. Variable-time, unaudited,
  * finite-corpus evidence only. Not a protocol-alpha builder or consensus validator.
  */
object StrictDraft03:
  val MaxAlphaBytes: Int = 1024 * 1024
  enum EnvelopeError:
    case MissingPublicKey, MissingProof, MissingAlpha
    case PublicKeyLength(actual: Int)
    case ProofLength(actual: Int)
    case AlphaTooLarge(actual: Int)
  enum Rejection:
    case PublicKeyEncoding, SmallOrderPublicKey, GammaEncoding, NoncanonicalScalar,
      ChallengeMismatch
  enum Result:
    case Verified(output: Bytes)
    case MalformedEnvelope(cause: EnvelopeError)
    case Rejected(cause: Rejection)
    case InternalFailure(cause: Throwable)
  import Result.*

  /** Immutable inputs and owned immutable output. No unchecked proof-to-hash operation is exposed.
    */
  def verify(publicKey: Bytes, proof: Bytes, alpha: Bytes): Result =
    verifyWith(publicKey, proof, alpha, (y, a) => Draft03Math.h2c(y, a))

  // Test-only package seam for provider failure; callers cannot substitute arithmetic publicly.
  private[vrf] def verifyWith(
      publicKey: Bytes,
      proof: Bytes,
      alpha: Bytes,
      map: (Array[Byte], Array[Byte]) => Ed25519Point
  ): Result =
    try
      if publicKey == null then return MalformedEnvelope(EnvelopeError.MissingPublicKey)
      if publicKey.size != 32 then
        return MalformedEnvelope(EnvelopeError.PublicKeyLength(publicKey.size))
      if proof == null then return MalformedEnvelope(EnvelopeError.MissingProof)
      if proof.size != 80 then return MalformedEnvelope(EnvelopeError.ProofLength(proof.size))
      if alpha == null then return MalformedEnvelope(EnvelopeError.MissingAlpha)
      if alpha.size > MaxAlphaBytes then
        return MalformedEnvelope(EnvelopeError.AlphaTooLarge(alpha.size))
      val pk = publicKey.toArray
      val pi = proof.toArray
      val msg = alpha.toArray
      val y = Ed25519Point.decode(pk)
      if y == null then return Rejected(Rejection.PublicKeyEncoding)
      if y.multiplyByCofactor() == Ed25519Point.NEUTRAL then
        return Rejected(Rejection.SmallOrderPublicKey)
      val gb = Arrays.copyOfRange(pi, 0, 32)
      val gamma = Ed25519Point.decode(gb)
      if gamma == null then return Rejected(Rejection.GammaEncoding)
      val c = Arrays.copyOfRange(pi, 32, 48)
      val s = Arrays.copyOfRange(pi, 48, 80)
      if Draft03Math.integer(s).compareTo(Draft03Math.L) >= 0 then
        return Rejected(Rejection.NoncanonicalScalar)
      val h = map(y.encode(), msg)
      val u = Draft03Math.equation(y, Ed25519Point.BASE_POINT, c, s)
      val v = Draft03Math.equation(gamma, h, c, s)
      if !Arrays.equals(c, Draft03Math.challenge(h, gb, u, v)) then
        Rejected(Rejection.ChallengeMismatch)
      else Verified(Bytes.fromArray(Draft03Math.output(gamma)))
    catch case NonFatal(e) => InternalFailure(e)
