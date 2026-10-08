// SPDX-License-Identifier: Apache-2.0
package lab.kes

import lab.Blake2b
import lab.cbor.Bytes
import lab.witness.{PublicKey32, Signature64, StrictEd25519, VerificationError, VerificationResult}
import scala.util.control.NonFatal

/** Experimental public-only predicate for a supplied message. Not a header serializer, lifetime
  * check, issuer authorization or chain validation. Six SumKES levels over the existing strict
  * leaf.
  */
object Sum6Kes:
  val MaxMessageBytes: Int = 65536 // Local resource policy, not a Cardano protocol limit.

  enum Failure:
    case Malformed(reason: String)
    case InternalFailure(kind: String)
  enum Result:
    case SignatureRejected, SuppliedMessageSignatureVerified

  final class Root32 private (val bytes: Bytes):
    def toArray: Array[Byte] = bytes.toArray
  object Root32:
    def fromBytes(bytes: Bytes): Either[Failure, Root32] =
      checked(bytes, 32, "root").map(new Root32(_))
    def fromArray(bytes: Array[Byte]): Either[Failure, Root32] =
      array(bytes, 32, "root").flatMap(fromBytes)

  final class Signature448 private (val bytes: Bytes):
    def toArray: Array[Byte] = bytes.toArray
  object Signature448:
    def fromBytes(bytes: Bytes): Either[Failure, Signature448] =
      checked(bytes, 448, "signature").map(new Signature448(_))
    def fromArray(bytes: Array[Byte]): Either[Failure, Signature448] =
      array(bytes, 448, "signature").flatMap(fromBytes)

  final class RelativePeriod private (val value: Int)
  object RelativePeriod:
    def create(value: Int): Either[Failure, RelativePeriod] =
      if value < 0 || value > 63 then Left(Failure.Malformed("relative period must be 0..63"))
      else Right(new RelativePeriod(value))

  final class SuppliedMessage private (val bytes: Bytes):
    def toArray: Array[Byte] = bytes.toArray
  object SuppliedMessage:
    def fromBytes(bytes: Bytes): Either[Failure, SuppliedMessage] =
      if bytes == null || bytes.value == null || bytes.size > MaxMessageBytes then
        Left(Failure.Malformed("supplied message must contain 0..65536 bytes"))
      else Right(new SuppliedMessage(bytes))
    def fromArray(bytes: Array[Byte]): Either[Failure, SuppliedMessage] =
      // Enforce the cap before copying caller-owned storage.
      if bytes == null || bytes.length > MaxMessageBytes then
        Left(Failure.Malformed("supplied message must contain 0..65536 bytes"))
      else fromBytes(Bytes.fromArray(bytes))

  private def checked(bytes: Bytes, size: Int, name: String): Either[Failure, Bytes] =
    if bytes == null || bytes.value == null || bytes.size != size then
      Left(Failure.Malformed(s"$name must contain $size bytes"))
    else Right(bytes)
  private def array(bytes: Array[Byte], size: Int, name: String): Either[Failure, Bytes] =
    if bytes == null || bytes.length != size then
      Left(Failure.Malformed(s"$name must contain $size bytes"))
    else Right(Bytes.fromArray(bytes))

  def verify(
      root: Bytes,
      period: Int,
      message: Bytes,
      signature: Bytes
  ): Either[Failure, Result] =
    for
      r <- Root32.fromBytes(root)
      p <- RelativePeriod.create(period)
      m <- SuppliedMessage.fromBytes(message)
      s <- Signature448.fromBytes(signature)
      result <- verifySignature(r, p, m, s)
    yield result

  def verifySignature(
      root: Root32,
      period: RelativePeriod,
      message: SuppliedMessage,
      signature: Signature448
  ): Either[Failure, Result] =
    verifyWith(root, period, message, signature, Blake2b.hash256.hash, StrictEd25519.verifyEd25519)

  // Non-public failure/work-count seam; callers cannot choose the production acceptance profile.
  private[kes] def verifyWith(
      root: Root32,
      period: RelativePeriod,
      message: SuppliedMessage,
      signature: Signature448,
      hash: Bytes => Bytes,
      leaf: (PublicKey32, Signature64, Bytes) => Either[VerificationError, VerificationResult]
  ): Either[Failure, Result] =
    if root == null || period == null || message == null || signature == null then
      Left(Failure.Malformed("null checked component"))
    else
      try
        var current = root.bytes
        var residual = period.value
        var depth = 6
        var mismatch = false
        while depth > 0 && !mismatch do
          val offset = 64 + 64 * (depth - 1)
          val pair = Bytes(signature.bytes.value.slice(offset, offset + 64))
          val digest = hash(pair)
          if digest == null || digest.value == null || digest.size != 32 then
            throw new IllegalStateException("hash-contract")
          mismatch = digest != current
          if !mismatch then
            val half = 1 << (depth - 1)
            val selected = if residual < half then 0 else 32
            if selected == 32 then residual -= half
            current = Bytes(pair.value.slice(selected, selected + 32))
          depth -= 1
        if mismatch then Right(Result.SignatureRejected)
        else if residual != 0 then Left(Failure.InternalFailure("period-invariant"))
        else
          val key = PublicKey32.create(current).toOption.get
          val sig = Signature64.create(Bytes(signature.bytes.value.take(64))).toOption.get
          leaf(key, sig, message.bytes) match
            case Right(VerificationResult.SignatureVerified) =>
              Right(Result.SuppliedMessageSignatureVerified)
            case Right(VerificationResult.SignatureRejected) => Right(Result.SignatureRejected)
            case Left(VerificationError.ImplementationFailure(kind)) =>
              Left(Failure.InternalFailure(kind))
            case _ => Left(Failure.InternalFailure("leaf-contract"))
      catch case NonFatal(e) => Left(Failure.InternalFailure(e.getClass.getName))
