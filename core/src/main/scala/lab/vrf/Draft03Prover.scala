// SPDX-License-Identifier: Apache-2.0
package lab.vrf

import com.weavechain.curve25519.{CompressedEdwardsY, Constants, EdwardsPoint, Scalar}
import java.security.MessageDigest
import java.util.Arrays
import lab.cbor.Bytes
import scala.util.control.NonFatal

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
    case Malformed(reason: String)
    case InternalFailure(kind: String, detail: String)

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
      try
        val pk = publicKey.toArray
        val h = Draft03Math.h2c(pk, alpha.toArray)
        val hString = h.encode()
        val hPoint = Draft03Prover.toEdwards(hString)
        val gammaString = hPoint.multiply(x).compress().toByteArray
        val k = Scalar.fromBytesModOrderWide(Draft03Prover.sha512(nonceKey, hString))
        val kB = Constants.ED25519_BASEPOINT_TABLE.multiply(k).compress().toByteArray
        val kH = hPoint.multiply(k).compress().toByteArray
        val c = Draft03Math.challenge(
          h,
          gammaString,
          Ed25519Point.decode(kB),
          Ed25519Point.decode(kH)
        )
        val cScalar = Scalar.fromBits(Arrays.copyOf(c, 32))
        val s = cScalar.multiplyAndAdd(x, k).toByteArray
        val proof = gammaString ++ c ++ s
        val gamma = Ed25519Point.decode(gammaString)
        Right(Proved(Bytes.fromArray(proof), Bytes.fromArray(Draft03Math.output(gamma))))
      catch
        case NonFatal(e) =>
          Left(Failure.InternalFailure(e.getClass.getName, Option(e.getMessage).getOrElse("")))

  object SigningKey:
    def fromSeed(seed: Bytes): Either[Failure, SigningKey] =
      if seed == null || seed.size != 32 then Left(Failure.Malformed("VRF seed must be 32 bytes"))
      else
        try
          val s = seed.toArray
          val x = Scalar.fromBytesModOrder(clamp(sha512(s).take(32)))
          val pk = Constants.ED25519_BASEPOINT_TABLE.multiply(x).compress().toByteArray
          Right(new SigningKey(s, Bytes.fromArray(pk)))
        catch
          case NonFatal(e) =>
            Left(Failure.InternalFailure(e.getClass.getName, Option(e.getMessage).getOrElse("")))

    /** The 64-byte `vrf.skey` payload; the stored public key must be the seed's. */
    def fromBytes(bytes: Bytes): Either[Failure, SigningKey] =
      if bytes == null || bytes.size != 64 then
        Left(Failure.Malformed("VRF signing key must be 64 bytes"))
      else
        fromSeed(Bytes.fromArray(bytes.toArray.take(32))).flatMap { key =>
          if key.publicKey.toArray.sameElements(bytes.toArray.drop(32)) then Right(key)
          else
            key.forget()
            Left(Failure.Malformed("VRF signing key: public half does not match the seed"))
        }

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
