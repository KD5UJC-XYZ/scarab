// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.crypto.vrf

import java.util.Arrays
import xyz.kd5ujc.scarab.codec.Bytes
import cats.syntax.either.*

/** ECVRF-ED25519-SHA512-Elligator2 (draft-03) verification with the acceptance rules of the Cardano
  * fork of libsodium (`crypto_vrf_ietfdraft03_verify`, IntersectMBO/libsodium dbb48cce): the proof
  * verifier under the Praos and TPraos header rules, reached through [[PraosVrfCertificate]]. It
  * verifies a proof over a given alpha and nothing more: building alpha, binding the claimed output,
  * the leader threshold and the key's registration are its callers'.
  *
  * Public inputs only, variable-time, and not independently audited (#230). Its agreement with the
  * reference is the finite corpus of `fixtures/vrf` (`StrictDraft03Suite`), compared with a source
  * build of that fork, not with a node's shipped binary; the corpus holds no accepted proof under a
  * mixed-order public key (`fixtures/vrf/README.md`).
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
    // each check in the order of the reference's verifier; the first that fails is the answer
    Either
      .catchNonFatal {
        val checked: Either[Result, Result] =
          for
            pkBytes <- Option(publicKey).toRight(MalformedEnvelope(EnvelopeError.MissingPublicKey))
            _ <- Either.cond(
              pkBytes.size == 32,
              (),
              MalformedEnvelope(EnvelopeError.PublicKeyLength(pkBytes.size))
            )
            proofBytes <- Option(proof).toRight(MalformedEnvelope(EnvelopeError.MissingProof))
            _ <- Either.cond(
              proofBytes.size == 80,
              (),
              MalformedEnvelope(EnvelopeError.ProofLength(proofBytes.size))
            )
            alphaBytes <- Option(alpha).toRight(MalformedEnvelope(EnvelopeError.MissingAlpha))
            _ <- Either.cond(
              alphaBytes.size <= MaxAlphaBytes,
              (),
              MalformedEnvelope(EnvelopeError.AlphaTooLarge(alphaBytes.size))
            )
            pi = proofBytes.toArray
            msg = alphaBytes.toArray
            y <- Option(Ed25519Point.decode(pkBytes.toArray))
              .toRight(Rejected(Rejection.PublicKeyEncoding))
            _ <- Either.cond(
              y.multiplyByCofactor() != Ed25519Point.NEUTRAL,
              (),
              Rejected(Rejection.SmallOrderPublicKey)
            )
            gb = Arrays.copyOfRange(pi, 0, 32)
            gamma <- Option(Ed25519Point.decode(gb)).toRight(Rejected(Rejection.GammaEncoding))
            c = Arrays.copyOfRange(pi, 32, 48)
            s = Arrays.copyOfRange(pi, 48, 80)
            _ <- Either.cond(
              Draft03Math.integer(s).compareTo(Draft03Math.L) < 0,
              (),
              Rejected(Rejection.NoncanonicalScalar)
            )
          yield
            val h = map(y.encode(), msg)
            val u = Draft03Math.equation(y, Ed25519Point.BASE_POINT, c, s)
            val v = Draft03Math.equation(gamma, h, c, s)
            if !Arrays.equals(c, Draft03Math.challenge(h, gb, u, v)) then
              Rejected(Rejection.ChallengeMismatch)
            else Verified(Bytes.fromArray(Draft03Math.output(gamma)))
        checked.merge
      }
      .fold(InternalFailure(_), identity)
