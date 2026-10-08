// SPDX-License-Identifier: Apache-2.0
package lab.witness

import lab.cbor.Bytes
import com.weavechain.curve25519.{
  CompressedEdwardsY,
  Constants,
  EdwardsPoint,
  InvalidEncodingException,
  Scalar
}
import java.security.MessageDigest
import java.util.Arrays
import scala.util.control.NonFatal

enum WitnessInputError:
  case MalformedPublicKeyLength(actual: Int)
  case MalformedSignatureLength(actual: Int)
  case MalformedBodyHashLength(actual: Int)
  case MalformedCbor(detail: String)
  case UnsupportedShape(detail: String)

enum VerificationResult:
  case SignatureVerified, SignatureRejected

/** Provider/programming failures are never reported as an invalid signature. */
enum VerificationError:
  case ImplementationFailure(exceptionClass: String)

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
    try Right(evaluate(publicKey, signature, message))
    catch case NonFatal(e) => Left(VerificationError.ImplementationFailure(e.getClass.getName))

  private def point(bytes: Array[Byte]): Option[EdwardsPoint] =
    val decoded =
      try Some(new CompressedEdwardsY(bytes.clone()).decompress())
      catch case _: InvalidEncodingException => None
    decoded.filter(p => Arrays.equals(p.compress().toByteArray, bytes) && !p.isSmallOrder)

  private def scalar(bytes: Array[Byte]): Option[Scalar] =
    // The caller always supplies exactly 32 bytes. This constructor rejects S >= L.
    try Some(Scalar.fromCanonicalBytes(bytes))
    catch case _: IllegalArgumentException => None

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
      val check = Constants.ED25519_BASEPOINT.multiply(s).subtract(a.multiply(h))
      Arrays.equals(check.compress().toByteArray, rBytes)
    if valid.contains(true) then SignatureVerified else SignatureRejected
