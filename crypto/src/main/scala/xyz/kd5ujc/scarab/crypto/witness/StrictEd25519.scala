// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.crypto.witness

import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Value}
import com.weavechain.curve25519.{
  CompressedEdwardsY,
  EdwardsPoint,
  InvalidEncodingException,
  Scalar
}
import java.security.MessageDigest
import java.util.Arrays
import cats.syntax.either.*

enum WitnessInputError:
  case MalformedPublicKeyLength(actual: Int)
  case MalformedSignatureLength(actual: Int)
  case MalformedBodyHashLength(actual: Int)
  case MalformedCbor(cause: Cbor.Error)
  case UnsupportedShape(actual: Value)

enum VerificationResult:
  case SignatureVerified, SignatureRejected

/** Provider/programming failures are never reported as an invalid signature. */
enum VerificationError:
  case ImplementationFailure(cause: Throwable)

/** Owned immutable public input. No secret-key operations are exposed. */
final class PublicKey32 private (val bytes: Bytes):
  def toArray: Array[Byte] = bytes.toArray
  override def equals(other: Any): Boolean = other match
    case that: PublicKey32 => bytes == that.bytes
    case _                 => false
  override def hashCode(): Int = bytes.hashCode()
object PublicKey32:
  def create(bytes: Bytes): Either[WitnessInputError, PublicKey32] =
    if bytes.size == 32 then Right(new PublicKey32(bytes))
    else Left(WitnessInputError.MalformedPublicKeyLength(bytes.size))
  def fromArray(bytes: Array[Byte]): Either[WitnessInputError, PublicKey32] = create(
    Bytes.fromArray(bytes)
  )

final class Signature64 private (val bytes: Bytes):
  def toArray: Array[Byte] = bytes.toArray
  override def equals(other: Any): Boolean = other match
    case that: Signature64 => bytes == that.bytes
    case _                 => false
  override def hashCode(): Int = bytes.hashCode()
object Signature64:
  def create(bytes: Bytes): Either[WitnessInputError, Signature64] =
    if bytes.size == 64 then Right(new Signature64(bytes))
    else Left(WitnessInputError.MalformedSignatureLength(bytes.size))
  def fromArray(bytes: Array[Byte]): Either[WitnessInputError, Signature64] = create(
    Bytes.fromArray(bytes)
  )

final class BodyHash32 private (val bytes: Bytes):
  def toArray: Array[Byte] = bytes.toArray
  override def equals(other: Any): Boolean = other match
    case that: BodyHash32 => bytes == that.bytes
    case _                => false
  override def hashCode(): Int = bytes.hashCode()
object BodyHash32:
  def create(bytes: Bytes): Either[WitnessInputError, BodyHash32] =
    if bytes.size == 32 then Right(new BodyHash32(bytes))
    else Left(WitnessInputError.MalformedBodyHashLength(bytes.size))
  def fromArray(bytes: Array[Byte]): Either[WitnessInputError, BodyHash32] = create(
    Bytes.fromArray(bytes)
  )

/** Experimental public-input predicate for the pinned non-COMPAT sodium source profile. Not a
  * security audit, release-binary equivalence, or complete ledger validation. No constant-time
  * claim: all inputs are public. Never add a cofactor to this equation, or replace small-order
  * rejection with prime-subgroup-only validation.
  */
object StrictEd25519:
  import VerificationResult.*

  def verify(
      publicKey: Bytes,
      signature: Bytes,
      message: Bytes
  ): Either[WitnessInputError | VerificationError, VerificationResult] =
    for
      key <- PublicKey32.create(publicKey)
      sig <- Signature64.create(signature)
      result <- verifyEd25519(key, sig, message)
    yield result

  def verifyEd25519(
      publicKey: PublicKey32,
      signature: Signature64,
      message: Bytes
  ): Either[VerificationError, VerificationResult] =
    // Only the two narrowly scoped decoding sites below classify invalid input.
    // Any unexpected failure elsewhere remains a typed implementation failure.
    Either
      .catchNonFatal(evaluate(publicKey, signature, message))
      .leftMap(VerificationError.ImplementationFailure(_))

  /** Whether the 255-bit little-endian `y` of a point encoding is below p = 2^255 - 19, the
    * encoding being canonical (bit 255, the sign of x, is ignored).
    */
  private[witness] def canonicalY(bytes: Array[Byte]): Boolean =
    // y >= p only for the 19 values 2^255 - 19 .. 2^255 - 1: byte 31 is 0x7f (bit 255 aside),
    // bytes 1 to 30 are 0xff and byte 0 is at least 0xed
    !((bytes(31) & 0x7f) == 0x7f && (bytes(0) & 0xff) >= 0xed &&
      (1 until 31).forall(i => bytes(i) == (-1).toByte))

  /** The point of a canonical encoding that is on the curve and not of small order.
    *
    * The encoding must be the one `compress` gives back. That used to be tested by compressing the
    * decompressed point (a field inversion, 5% of a verification). It is the same test as "y < p"
    * on the bytes: `decompress` reads y modulo p, so the two differ only for the 19 non-canonical
    * y; and it takes the x whose sign is the encoded bit, so they differ in the sign only when x =
    * 0, that is y = 1 or y = -1, which are the points of order 1 and 2, refused by the small-order
    * test in any case. `StrictEd25519DifferentialSuite` compares the two over the blacklist
    * encodings, their neighbours and random encodings, on and off the curve.
    */
  private[witness] def point(bytes: Array[Byte]): Option[EdwardsPoint] =
    if !canonicalY(bytes) then None
    else
      Either
        .catchOnly[InvalidEncodingException](new CompressedEdwardsY(bytes.clone()).decompress())
        .toOption
        .filter(p => !p.isSmallOrder)

  private def scalar(bytes: Array[Byte]): Option[Scalar] =
    // The caller always supplies exactly 32 bytes. This constructor rejects S >= L.
    Either.catchOnly[IllegalArgumentException](Scalar.fromCanonicalBytes(bytes)).toOption

  private def evaluate(
      publicKey: PublicKey32,
      signature: Signature64,
      message: Bytes
  ): VerificationResult =
    val keyBytes = publicKey.toArray
    val signatureBytes = signature.toArray
    val rBytes = Arrays.copyOfRange(signatureBytes, 0, 32)
    val sBytes = Arrays.copyOfRange(signatureBytes, 32, 64)
    val valid = for
      a <- point(keyBytes)
      _ <- point(rBytes)
      s <- scalar(sBytes)
    yield
      val digest = MessageDigest.getInstance("SHA-512")
      digest.update(rBytes)
      digest.update(keyBytes)
      digest.update(message.toArray)
      val h = Scalar.fromBytesModOrderWide(digest.digest())
      // [s]B + [h](-A) in one interleaved double-scalar multiplication (the inputs are public, so
      // the variable-time form is fine): about half the work of two separate multiplications. The
      // point is negated, not the scalar: -h mod L would change the result for a key with a
      // torsion component, which this profile accepts (mixed-order speccheck case 3).
      val check = EdwardsPoint.vartimeDoubleScalarMultiplyBasepoint(h, a.negate(), s)
      // (`EdwardsPoint.ctEquals` compresses both points, so it would cost a second inversion)
      Arrays.equals(check.compress().toByteArray, rBytes)
    if valid.contains(true) then SignatureVerified else SignatureRejected
