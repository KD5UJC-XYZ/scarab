// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.crypto.vrf

import com.weavechain.curve25519.{CompressedEdwardsY, Constants, EdwardsPoint, Scalar}
import java.security.MessageDigest
import java.util.Arrays
import xyz.kd5ujc.scarab.codec.Bytes
import cats.syntax.either.*

/** The VRF prover as it was before its secret steps moved to `secret.Draft03Key` (main at
  * 384e390dd), kept unchanged in test scope as the reference of `Draft03ProverDifferentialSuite`:
  * proofs are deterministic, so the new prover must give the same bytes for every key and input. It
  * reduces the scalar, decodes Gamma, U and V with the variable-time decoder, and keeps the
  * library's scalars; none of that is the node's any more.
  */
object LegacyDraft03Prover:
  enum Failure:
    case MissingSeed, MissingSigningKey, PublicKeyMismatch
    case SeedLength(actual: Int)
    case SigningKeyLength(actual: Int)
    case InternalFailure(cause: Throwable)

  /** A proof and the 64-byte VRF output it certifies. */
  final case class Proved(proof: Bytes, output: Bytes)

  /** The secret key: the 32-byte seed, the scalar and the nonce key expanded from it, and its
    * public key.
    */
  final class SigningKey private (
      seed: Array[Byte],
      x: Scalar,
      nonceKey: Array[Byte],
      val publicKey: Bytes
  ):
    /** `seed || publicKey`, the layout of `vrf.skey`: a copy its caller owns. */
    def toBytes: Bytes =
      val payload = new Array[Byte](64)
      System.arraycopy(seed, 0, payload, 0, 32)
      publicKey.copyTo(payload, 32)
      Bytes.wrap(payload)

    /** Zero the seed and the nonce key. This is not an erasure of the key: the scalar is an
      * immutable value of the curve library that cannot be overwritten and lives as long as this
      * object (and in the heap after it until its memory is reused), and it alone computes the
      * output and a proof for any input. Copies given out by [[toBytes]] are their owners'.
      *
      * A forgotten key proves nothing more ([[prove]] refuses): its nonce key is zero, so the nonce
      * of a proof would be computable by anyone, and one proof would give the scalar away.
      */
    def forget(): Unit =
      Arrays.fill(seed, 0.toByte)
      Arrays.fill(nonceKey, 0.toByte)

    def prove(alpha: Bytes): Either[Failure, Proved] =
      if nonceKey.forall(_ == 0) then Left(Failure.MissingSigningKey)
      else proved(alpha)

    private def proved(alpha: Bytes): Either[Failure, Proved] =
      evaluated(alpha) { g =>
        val nonce = LegacyDraft03Prover.sha512(nonceKey, g.hString)
        val k = Scalar.fromBytesModOrderWide(nonce)
        Arrays.fill(nonce, 0.toByte)
        val kB = Constants.ED25519_BASEPOINT_TABLE.multiply(k).compress().toByteArray
        val kH = g.hPoint.multiply(k).compress().toByteArray
        val c = Draft03Math.challenge(
          g.h,
          g.gammaString,
          Ed25519Point.decode(kB),
          Ed25519Point.decode(kH)
        )
        val cScalar = Scalar.fromBits(Arrays.copyOf(c, 32))
        val s = cScalar.multiplyAndAdd(x, k).toByteArray
        val proof = g.gammaString ++ c ++ s
        Proved(Bytes.fromArray(proof), g.output)
      }

    /** The 64-byte output of `alpha` alone: the output [[prove]] certifies, from the same steps up
      * to Gamma, without the nonce, the commitments, the challenge and the response that only the
      * proof needs.
      */
    def output(alpha: Bytes): Either[Failure, Bytes] =
      evaluated(alpha)(_.output)

    /** Hash to curve and `Gamma = x*H`, the steps [[prove]] and [[output]] share, then `rest`;
      * whatever throws in either is the one [[Failure.InternalFailure]].
      */
    private def evaluated[A](alpha: Bytes)(rest: Gamma => A): Either[Failure, A] =
      Either
        .catchNonFatal {
          val h = Draft03Math.h2c(publicKey.toArray, alpha.toArray)
          val hString = h.encode()
          val hPoint = LegacyDraft03Prover.toEdwards(hString)
          rest(Gamma(h, hString, hPoint, hPoint.multiply(x).compress().toByteArray))
        }
        .leftMap(Failure.InternalFailure(_))

  object SigningKey:
    def fromSeed(seed: Bytes): Either[Failure, SigningKey] =
      if seed == null then Left(Failure.MissingSeed)
      else if seed.size != 32 then Left(Failure.SeedLength(seed.size))
      else Either.catchNonFatal(expanded(seed.toArray)).leftMap(Failure.InternalFailure(_))

    /** The key of `seed`, which becomes the key's own array. SHA-512 of the seed is the scalar's
      * bytes (clamped) and the nonce key; the arrays it passes through are zeroed here.
      */
    private def expanded(seed: Array[Byte]): SigningKey =
      val digest = sha512(seed)
      val scalarBytes = Arrays.copyOfRange(digest, 0, 32)
      scalarBytes(0) = (scalarBytes(0) & 248).toByte
      scalarBytes(31) = ((scalarBytes(31) & 127) | 64).toByte
      val nonceKey = Arrays.copyOfRange(digest, 32, 64)
      Arrays.fill(digest, 0.toByte)
      val x = Scalar.fromBytesModOrder(scalarBytes)
      Arrays.fill(scalarBytes, 0.toByte)
      val pk = Constants.ED25519_BASEPOINT_TABLE.multiply(x).compress().toByteArray
      new SigningKey(seed, x, nonceKey, Bytes.wrap(pk))

    /** The 64-byte `vrf.skey` payload; the stored public key must be the seed's. */
    def fromBytes(bytes: Bytes): Either[Failure, SigningKey] =
      if bytes == null then Left(Failure.MissingSigningKey)
      else if bytes.size != 64 then Left(Failure.SigningKeyLength(bytes.size))
      else
        // the seed half is copied once, into the array that becomes the key's
        Either
          .catchNonFatal(expanded(bytes.take(32).toArray))
          .leftMap(Failure.InternalFailure(_))
          .flatMap { key =>
            if key.publicKey == bytes.drop(32) then Right(key)
            else
              key.forget()
              Left(Failure.PublicKeyMismatch)
          }

  /** An input's hash-to-curve point H (variable-time, encoded, and constant-time) and the encoding
    * of `Gamma = x*H`: what the output and the proof share.
    */
  private final class Gamma(
      val h: Ed25519Point,
      val hString: Array[Byte],
      val hPoint: EdwardsPoint,
      val gammaString: Array[Byte]
  ):
    /** The output from the encoding of Gamma, decoded as a verifier decodes the proof's. */
    def output: Bytes = Bytes.fromArray(Draft03Math.output(Ed25519Point.decode(gammaString)))

  private def sha512(pieces: Array[Byte]*): Array[Byte] =
    val d = MessageDigest.getInstance("SHA-512")
    pieces.foreach(d.update)
    d.digest()

  private def toEdwards(encoded: Array[Byte]): EdwardsPoint =
    new CompressedEdwardsY(encoded).decompress()
