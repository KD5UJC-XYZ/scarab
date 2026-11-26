// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.crypto.witness

import cats.syntax.bifunctor.*
import cats.syntax.either.*
import com.weavechain.curve25519.{CompressedEdwardsY, EdwardsPoint, Scalar}
import java.security.MessageDigest
import java.util.Arrays
import scala.jdk.OptionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes

/** `StrictEd25519` and `ByronEd25519` as they computed before `VartimeEd25519`, over the vendored
  * curve25519-elisabeth objects (`CompressedEdwardsY.decompress`, `EdwardsPoint.isSmallOrder`,
  * `Scalar.fromCanonicalBytes`, `EdwardsPoint.vartimeDoubleScalarMultiplyBasepoint`), copied from
  * their main sources as they were, `verify` and `verifyEd25519` included: the oracle of
  * `VartimeEd25519DifferentialSuite`, whose result values, refusals and failures included, must be
  * the new ones on every input, and the "before" of `VerifyAllocationBench`.
  */
object VendoredVerifiers:
  import VerificationResult.*

  private def canonicalY(bytes: Array[Byte]): Boolean =
    !((bytes(31) & 0x7f) == 0x7f && (bytes(0) & 0xff) >= 0xed &&
      (1 until 31).forall(i => bytes(i) == (-1).toByte))

  /** The point of a canonical encoding that is on the curve and not of small order. */
  def strictPoint(bytes: Array[Byte]): Option[EdwardsPoint] =
    if !canonicalY(bytes) then None
    else new CompressedEdwardsY(bytes.clone()).decompress().toScala.filter(p => !p.isSmallOrder)

  private def strictScalar(bytes: Array[Byte]): Option[Scalar] =
    Scalar.fromCanonicalBytes(bytes).toScala

  def strictVerify(
      publicKey: Bytes,
      signature: Bytes,
      message: Bytes
  ): Either[WitnessInputError | VerificationError, VerificationResult] =
    for
      key <- PublicKey32.create(publicKey)
      sig <- Signature64.create(signature)
      result <- strictVerifyEd25519(key, sig, message)
        .leftWiden[WitnessInputError | VerificationError]
    yield result

  def strictVerifyEd25519(
      publicKey: PublicKey32,
      signature: Signature64,
      message: Bytes
  ): Either[VerificationError, VerificationResult] =
    Either
      .catchNonFatal(strictEvaluate(publicKey, signature, message))
      .leftMap(VerificationError.ImplementationFailure(_))

  private def strictEvaluate(
      publicKey: PublicKey32,
      signature: Signature64,
      message: Bytes
  ): VerificationResult =
    val keyBytes = publicKey.toArray
    val signatureBytes = signature.toArray
    val rBytes = Arrays.copyOfRange(signatureBytes, 0, 32)
    val sBytes = Arrays.copyOfRange(signatureBytes, 32, 64)
    val valid = for
      a <- strictPoint(keyBytes)
      _ <- strictPoint(rBytes)
      s <- strictScalar(sBytes)
    yield
      val digest = MessageDigest.getInstance("SHA-512")
      digest.update(rBytes)
      digest.update(keyBytes)
      digest.update(message.toArray)
      val h = Scalar.fromBytesModOrderWide(digest.digest())
      val check = EdwardsPoint.vartimeDoubleScalarMultiplyBasepoint(h, a.negate(), s)
      Arrays.equals(check.compress().toByteArray, rBytes)
    if valid.contains(true) then SignatureVerified else SignatureRejected

  def byronVerify(
      publicKey: Bytes,
      signature: Bytes,
      message: Bytes
  ): Either[WitnessInputError | VerificationError, VerificationResult] =
    for
      key <- PublicKey32.create(publicKey)
      sig <- Signature64.create(signature)
      result <- byronVerifyEd25519(key, sig, message)
        .leftWiden[WitnessInputError | VerificationError]
    yield result

  def byronVerifyEd25519(
      publicKey: PublicKey32,
      signature: Signature64,
      message: Bytes
  ): Either[VerificationError, VerificationResult] =
    Either
      .catchNonFatal(byronEvaluate(publicKey, signature, message))
      .leftMap(VerificationError.ImplementationFailure(_))

  private def byronPoint(bytes: Array[Byte]): Option[EdwardsPoint] =
    new CompressedEdwardsY(bytes.clone()).decompress().toScala

  private def byronEvaluate(
      publicKey: PublicKey32,
      signature: Signature64,
      message: Bytes
  ): VerificationResult =
    val keyBytes = publicKey.toArray
    val signatureBytes = signature.toArray
    val rBytes = Arrays.copyOfRange(signatureBytes, 0, 32)
    val valid =
      if (signatureBytes(63) & 0xe0) != 0 then None
      else
        byronPoint(keyBytes).map { a =>
          val s = Scalar.fromBytesModOrderWide(Arrays.copyOfRange(signatureBytes, 32, 96))
          val digest = MessageDigest.getInstance("SHA-512")
          digest.update(rBytes)
          digest.update(keyBytes)
          digest.update(message.toArray)
          val h = Scalar.fromBytesModOrderWide(digest.digest())
          val check = EdwardsPoint.vartimeDoubleScalarMultiplyBasepoint(h, a.negate(), s)
          Arrays.equals(check.compress().toByteArray, rBytes)
        }
    if valid.contains(true) then SignatureVerified else SignatureRejected

  /** Whether the old strict path accepts the arrays. */
  def strict(key: Array[Byte], signature: Array[Byte], message: Array[Byte]): Boolean =
    strictVerify(Bytes.fromArray(key), Bytes.fromArray(signature), Bytes.fromArray(message)) ==
      Right(SignatureVerified)

  /** Whether the old Byron path accepts the arrays. */
  def byron(key: Array[Byte], signature: Array[Byte], message: Array[Byte]): Boolean =
    byronVerify(Bytes.fromArray(key), Bytes.fromArray(signature), Bytes.fromArray(message)) ==
      Right(SignatureVerified)
