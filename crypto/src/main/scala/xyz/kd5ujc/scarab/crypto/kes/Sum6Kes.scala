// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.crypto.kes

import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.witness.{
  PublicKey32,
  Signature64,
  StrictEd25519,
  VerificationError,
  VerificationResult,
  WitnessInputError
}
import cats.syntax.either.*

/** Experimental public-only predicate for a supplied message. Not a header serializer, lifetime
  * check, issuer authorization or chain validation. Six SumKES levels over the existing strict
  * leaf.
  */
object Sum6Kes:
  val MaxMessageBytes: Int = 65536 // Local resource policy, not a Cardano protocol limit.

  enum Component:
    case Root, Signature, Message, Period
  enum Failure:
    case MissingComponent(component: Component)
    case InvalidLength(component: Component, actual: Int, expected: Int)
    case PeriodOutOfRange(actual: Int)
    case MessageTooLarge(actual: Int)
    case HashContractViolation(actual: Option[Int])
    case PeriodInvariant(residual: Int)
    case LeafInput(cause: WitnessInputError)
    case LeafFailure(cause: VerificationError)
    case LeafContractViolation
    case Defect(cause: Throwable)
  enum Result:
    case SignatureRejected, SuppliedMessageSignatureVerified

  final class Root32 private (val bytes: Bytes):
    def toArray: Array[Byte] = bytes.toArray
  object Root32:
    def fromBytes(bytes: Bytes): Either[Failure, Root32] =
      checked(bytes, 32, Component.Root).map(new Root32(_))
    def fromArray(bytes: Array[Byte]): Either[Failure, Root32] =
      array(bytes, 32, Component.Root).flatMap(fromBytes)

  final class Signature448 private (val bytes: Bytes):
    def toArray: Array[Byte] = bytes.toArray
  object Signature448:
    def fromBytes(bytes: Bytes): Either[Failure, Signature448] =
      checked(bytes, 448, Component.Signature).map(new Signature448(_))
    def fromArray(bytes: Array[Byte]): Either[Failure, Signature448] =
      array(bytes, 448, Component.Signature).flatMap(fromBytes)

  final class RelativePeriod private (val value: Int)
  object RelativePeriod:
    def create(value: Int): Either[Failure, RelativePeriod] =
      if value < 0 || value > 63 then Left(Failure.PeriodOutOfRange(value))
      else Right(new RelativePeriod(value))

  final class SuppliedMessage private (val bytes: Bytes):
    def toArray: Array[Byte] = bytes.toArray
  object SuppliedMessage:
    def fromBytes(bytes: Bytes): Either[Failure, SuppliedMessage] =
      if bytes == null then Left(Failure.MissingComponent(Component.Message))
      else if bytes.size > MaxMessageBytes then Left(Failure.MessageTooLarge(bytes.size))
      else Right(new SuppliedMessage(bytes))
    def fromArray(bytes: Array[Byte]): Either[Failure, SuppliedMessage] =
      if bytes == null then Left(Failure.MissingComponent(Component.Message))
      else if bytes.length > MaxMessageBytes then Left(Failure.MessageTooLarge(bytes.length))
      else fromBytes(Bytes.fromArray(bytes))

  private def checked(bytes: Bytes, size: Int, component: Component): Either[Failure, Bytes] =
    if bytes == null then Left(Failure.MissingComponent(component))
    else if bytes.size != size then Left(Failure.InvalidLength(component, bytes.size, size))
    else Right(bytes)
  private def array(bytes: Array[Byte], size: Int, component: Component): Either[Failure, Bytes] =
    if bytes == null then Left(Failure.MissingComponent(component))
    else if bytes.length != size then Left(Failure.InvalidLength(component, bytes.length, size))
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
    if root == null then Left(Failure.MissingComponent(Component.Root))
    else if period == null then Left(Failure.MissingComponent(Component.Period))
    else if message == null then Left(Failure.MissingComponent(Component.Message))
    else if signature == null then Left(Failure.MissingComponent(Component.Signature))
    else
      Either
        .catchNonFatal {
          final case class Level(current: Bytes, residual: Int, rejected: Boolean)
          val descended = (6 to 1 by -1).foldLeft[Either[Failure, Level]](
            Right(Level(root.bytes, period.value, false))
          ) { (acc, depth) =>
            acc.flatMap { state =>
              if state.rejected then Right(state)
              else
                val offset = 64 + 64 * (depth - 1)
                val pair = signature.bytes.slice(offset, offset + 64)
                val digest = hash(pair)
                if digest == null then Left(Failure.HashContractViolation(None))
                else if digest.size != 32 then
                  Left(Failure.HashContractViolation(Some(digest.size)))
                else if digest != state.current then Right(state.copy(rejected = true))
                else
                  val half = 1 << (depth - 1)
                  val selected = if state.residual < half then 0 else 32
                  val residual = if selected == 32 then state.residual - half else state.residual
                  Right(Level(pair.slice(selected, selected + 32), residual, false))
            }
          }
          descended.flatMap { state =>
            if state.rejected then Right(Result.SignatureRejected)
            else if state.residual != 0 then Left(Failure.PeriodInvariant(state.residual))
            else
              for
                key <- PublicKey32.create(state.current).left.map(Failure.LeafInput.apply)
                sig <- Signature64
                  .create(signature.bytes.take(64))
                  .left
                  .map(Failure.LeafInput.apply)
                result <- Option(leaf(key, sig, message.bytes))
                  .toRight(Failure.LeafContractViolation)
                  .flatMap {
                    case Right(VerificationResult.SignatureVerified) =>
                      Right(Result.SuppliedMessageSignatureVerified)
                    case Right(VerificationResult.SignatureRejected) =>
                      Right(Result.SignatureRejected)
                    case Left(error) if error != null => Left(Failure.LeafFailure(error))
                    case _                            => Left(Failure.LeafContractViolation)
                  }
              yield result
          }
        }
        .fold(e => Left(Failure.Defect(e)), identity)
