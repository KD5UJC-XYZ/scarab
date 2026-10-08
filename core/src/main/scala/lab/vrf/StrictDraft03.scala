// SPDX-License-Identifier: Apache-2.0
package lab.vrf

import java.util.Arrays
import lab.cbor.Bytes
import scala.util.control.NonFatal

/** Experimental public-only Cardano draft03 source-profile verifier. Variable-time, unaudited,
  * finite-corpus evidence only. Not a protocol-alpha builder or consensus validator.
  */
object StrictDraft03:
  val MaxAlphaBytes: Int = 1024 * 1024
  enum Result:
    case Verified(output: Bytes)
    case MalformedEnvelope(reason: String)
    case Rejected(reason: String)
    case InternalFailure(kind: String, detail: String)
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
      if publicKey == null || publicKey.size != 32 then
        return MalformedEnvelope("public key must be 32 bytes")
      if proof == null || proof.size != 80 then return MalformedEnvelope("proof must be 80 bytes")
      if alpha == null then return MalformedEnvelope("alpha must not be null")
      if alpha.size > MaxAlphaBytes then
        return MalformedEnvelope("alpha exceeds 1 MiB research limit")
      val pk = publicKey.toArray
      val pi = proof.toArray
      val msg = alpha.toArray
      val y = Ed25519Point.decode(pk)
      if y == null then return Rejected("public key encoding")
      if y.multiplyByCofactor() == Ed25519Point.NEUTRAL then
        return Rejected("small-order public key")
      val gb = Arrays.copyOfRange(pi, 0, 32)
      val gamma = Ed25519Point.decode(gb)
      if gamma == null then return Rejected("Gamma encoding")
      val c = Arrays.copyOfRange(pi, 32, 48)
      val s = Arrays.copyOfRange(pi, 48, 80)
      if Draft03Math.integer(s).compareTo(Draft03Math.L) >= 0 then
        return Rejected("noncanonical scalar")
      val h = map(y.encode(), msg)
      val u = Draft03Math.equation(y, Ed25519Point.BASE_POINT, c, s)
      val v = Draft03Math.equation(gamma, h, c, s)
      if !Arrays.equals(c, Draft03Math.challenge(h, gb, u, v)) then Rejected("challenge mismatch")
      else Verified(Bytes.fromArray(Draft03Math.output(gamma)))
    catch
      case NonFatal(e) => InternalFailure(e.getClass.getName, Option(e.getMessage).getOrElse(""))
