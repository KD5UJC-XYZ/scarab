// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.crypto.witness

import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Value}
import com.weavechain.curve25519.Scalar
import java.security.MessageDigest
import cats.syntax.bifunctor.*
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

/** Ed25519 verification with the acceptance rules of libsodium's `crypto_sign_verify_detached`
  * built without `ED25519_COMPAT`: the verifier of transaction witnesses from Shelley on, of
  * operational certificates and of the KES leaf (Byron signatures are [[ByronEd25519]]'s).
  *
  * A signature predicate over public inputs: no constant-time claim, and not independently audited
  * (#230). Its agreement with the reference is the finite corpus of `fixtures/witness`, compared
  * with a source build of the pinned libsodium, not with a node's shipped binary. Never add a
  * cofactor to this equation, or replace small-order rejection with prime-subgroup-only validation:
  * either changes the accepted set.
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
      result <- verifyEd25519(key, sig, message).leftWiden[WitnessInputError | VerificationError]
    yield result

  def verifyEd25519(
      publicKey: PublicKey32,
      signature: Signature64,
      message: Bytes
  ): Either[VerificationError, VerificationResult] =
    // The arithmetic refuses an invalid input with a verdict and, given a key of 32 bytes and a
    // signature of 64, throws nothing. What can throw is the JDK's SHA-512, when no provider
    // supplies it, and a null argument: a typed implementation failure.
    Either
      .catchNonFatal(evaluate(publicKey, signature, message))
      .leftMap(VerificationError.ImplementationFailure(_))

  /** Whether the 255-bit little-endian `y` of a point encoding is below p = 2^255 - 19, the
    * encoding being canonical (bit 255, the sign of x, is ignored). Bytes of another length than 32
    * are no encoding, and not canonical.
    */
  private[witness] def canonicalY(bytes: Array[Byte]): Boolean =
    PublicKey32
      .fromArray(bytes)
      .exists(_.bytes.withRange((encoding, at, _) => VartimeEd25519.canonicalY(encoding, at)))

  /** Whether a 32-byte encoding is accepted as a key: canonical, on the curve and not of small
    * order.
    *
    * The encoding must be the one `compress` gives back. That used to be tested by compressing the
    * decompressed point (a field inversion, 5% of a verification). It is the same test as "y < p"
    * on the bytes: `decompress` reads y modulo p, so the two differ only for the 19 non-canonical
    * y; and it takes the x whose sign is the encoded bit, so they differ in the sign only when x =
    * 0, that is y = 1 or y = -1, which are the points of order 1 and 2, refused by the small-order
    * test in any case. `StrictEd25519DifferentialSuite` compares the two over the blacklist
    * encodings, their neighbours and random encodings, on and off the curve. Bytes of another
    * length than 32 are no key, and not accepted.
    */
  private[witness] def acceptsKey(bytes: Array[Byte]): Boolean =
    PublicKey32
      .fromArray(bytes)
      .exists(_.bytes.withRange((key, at, _) => VartimeEd25519.strictKey(key, at)))

  private def evaluate(
      publicKey: PublicKey32,
      signature: Signature64,
      message: Bytes
  ): VerificationResult =
    // A, R and S checked first, then the hash, only of a signature that passes them, and [S]B + [h](-A)
    // in one interleaved double-scalar multiplication over the public inputs (`VartimeEd25519.strict`)
    val accepted = publicKey.bytes.withRange { (key, keyAt, _) =>
      signature.bytes.withRange { (sig, sigAt, _) =>
        VartimeEd25519.strict(
          key,
          keyAt,
          sig,
          sigAt,
          () => Ed25519Challenge(signature.bytes, publicKey.bytes, message)
        )
      }
    }
    if accepted then SignatureVerified else SignatureRejected

/** h = SHA-512(R || A || M) modulo L, little-endian in 32 bytes: the challenge of [[StrictEd25519]]
  * and [[ByronEd25519]], over the encodings as given, R being the first 32 bytes of the signature.
  * The bytes are fed to the digest where they are, without copies.
  */
private[witness] object Ed25519Challenge:
  def apply(signature: Bytes, key: Bytes, message: Bytes): Array[Byte] =
    val digest = MessageDigest.getInstance("SHA-512")
    signature.withRange((bytes, at, _) => digest.update(bytes, at, 32))
    key.withRange((bytes, at, size) => digest.update(bytes, at, size))
    message.withRange((bytes, at, size) => digest.update(bytes, at, size))
    Scalar.fromBytesModOrderWide(digest.digest()).toByteArray
