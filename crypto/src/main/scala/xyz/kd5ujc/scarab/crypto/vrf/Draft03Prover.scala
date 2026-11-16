// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.crypto.vrf

import com.weavechain.curve25519.{CompressedEdwardsY, Constants, EdwardsPoint, Scalar}
import java.security.MessageDigest
import java.util.Arrays
import xyz.kd5ujc.scarab.codec.Bytes
import cats.syntax.either.*

/** ECVRF-ED25519-SHA512-Elligator2 (draft-03) proving, as libsodium's
  * `crypto_vrf_ietfdraft03_prove` and the Cardano `PraosVRF` signing key: a 64-byte key
  * `seed || publicKey`. Proofs are deterministic, so they are checked against known vectors and by
  * [[StrictDraft03.verify]].
  *
  * Secret scalars only meet the constant-time curve arithmetic of `curve25519-elisabeth` (base
  * point and arbitrary point multiplication, scalar multiply-and-add). The hash-to-curve step takes
  * only public inputs (public key and alpha) and reuses the variable-time verifier arithmetic.
  */
object Draft03Prover:
  enum Failure:
    case MissingSeed, MissingSigningKey, PublicKeyMismatch
    case SeedLength(actual: Int)
    case SigningKeyLength(actual: Int)
    case InternalFailure(cause: Throwable)

  /** A proof and the 64-byte VRF output it certifies. */
  final case class Proved(proof: Bytes, output: Bytes)

  /** The secret key: the 32-byte seed and its public key. The seed is zeroised by [[forget]]. */
  final class SigningKey private (seed: Array[Byte], val publicKey: Bytes):
    private val x: Scalar =
      Scalar.fromBytesModOrder(Draft03Prover.clamp(Draft03Prover.sha512(seed).take(32)))
    private val nonceKey: Array[Byte] = Draft03Prover.sha512(seed).slice(32, 64)

    /** `seed || publicKey`, the layout of `vrf.skey`. */
    def toBytes: Bytes = Bytes.fromArray(seed ++ publicKey.toArray)

    def forget(): Unit =
      Arrays.fill(seed, 0.toByte)
      Arrays.fill(nonceKey, 0.toByte)

    def prove(alpha: Bytes): Either[Failure, Proved] =
      evaluated(alpha) { g =>
        val k = Scalar.fromBytesModOrderWide(Draft03Prover.sha512(nonceKey, g.hString))
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
          val hPoint = Draft03Prover.toEdwards(hString)
          rest(Gamma(h, hString, hPoint, hPoint.multiply(x).compress().toByteArray))
        }
        .leftMap(Failure.InternalFailure(_))

  object SigningKey:
    def fromSeed(seed: Bytes): Either[Failure, SigningKey] =
      if seed == null then Left(Failure.MissingSeed)
      else if seed.size != 32 then Left(Failure.SeedLength(seed.size))
      else
        Either
          .catchNonFatal {
            val s = seed.toArray
            val x = Scalar.fromBytesModOrder(clamp(sha512(s).take(32)))
            val pk = Constants.ED25519_BASEPOINT_TABLE.multiply(x).compress().toByteArray
            new SigningKey(s, Bytes.fromArray(pk))
          }
          .leftMap(Failure.InternalFailure(_))

    /** The 64-byte `vrf.skey` payload; the stored public key must be the seed's. */
    def fromBytes(bytes: Bytes): Either[Failure, SigningKey] =
      if bytes == null then Left(Failure.MissingSigningKey)
      else if bytes.size != 64 then Left(Failure.SigningKeyLength(bytes.size))
      else
        fromSeed(Bytes.fromArray(bytes.toArray.take(32))).flatMap { key =>
          if key.publicKey.toArray.sameElements(bytes.toArray.drop(32)) then Right(key)
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

  private def clamp(h: Array[Byte]): Array[Byte] =
    val r = h.clone()
    r(0) = (r(0) & 248).toByte
    r(31) = ((r(31) & 127) | 64).toByte
    r

  private def toEdwards(encoded: Array[Byte]): EdwardsPoint =
    new CompressedEdwardsY(encoded).decompress()
