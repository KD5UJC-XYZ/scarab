// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.crypto.opcert

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.witness.{
  PublicKey32,
  Signature64,
  StrictEd25519,
  VerificationError,
  WitnessInputError,
  VerificationResult
}
import cats.syntax.either.*

/** Public operational-certificate signature predicate only. No pool registration, counter-state,
  * KES, header validity or chain-authenticity claim. Uses the existing experimental strict profile.
  */
object OperationalCertificate:
  enum Failure:
    case MissingColdKey, MissingHotKey, MissingSignature, MissingCertificate
    case InvalidHotKeyLength(actual: Int)
    case InvalidCounter(cause: UInt64.Error)
    case InvalidStartPeriod(cause: UInt64.Error)
    case ColdKey(cause: WitnessInputError)
    case Signature(cause: WitnessInputError)
    case PrimitiveFailure(cause: VerificationError)
    case PrimitiveContractViolation(reply: PrimitiveReply)
    case Defect(cause: Throwable)

    def message: String = this match
      case MissingColdKey                => "null cold key"
      case MissingHotKey                 => "null hot KES key"
      case MissingSignature              => "null signature"
      case MissingCertificate            => "null certificate"
      case InvalidHotKeyLength(n)        => s"hot KES key must be 32 bytes (got $n)"
      case InvalidCounter(e)             => s"certificate counter: ${e.message}"
      case InvalidStartPeriod(e)         => s"certificate start period: ${e.message}"
      case ColdKey(e)                    => s"cold key: $e"
      case Signature(e)                  => s"certificate signature: $e"
      case PrimitiveFailure(e)           => s"certificate signature verifier failed: $e"
      case PrimitiveContractViolation(r) => s"certificate signature verifier broke its contract: $r"
      case Defect(e)                     => s"certificate verifier defect: ${e.getClass.getName}"

  enum PrimitiveReply:
    case MissingResult, MissingVerdict, MissingError

  final class UInt64 private (val value: BigInt)
  object UInt64:
    val MaxValue: BigInt = (BigInt(1) << 64) - 1
    enum Error:
      case MissingValue
      case OutOfRange(value: BigInt)

      def message: String = this match
        case MissingValue  => "missing unsigned Word64"
        case OutOfRange(n) => s"value $n must be unsigned Word64"

    def fromBigInt(value: BigInt): Either[Error, UInt64] =
      if value == null then Left(Error.MissingValue)
      else if value < 0 || value > MaxValue then Left(Error.OutOfRange(value))
      else Right(new UInt64(value))

  final class HotKesKey32 private (val bytes: Bytes)
  object HotKesKey32:
    def fromBytes(bytes: Bytes): Either[Failure, HotKesKey32] = protect {
      if bytes == null then Left(Failure.MissingHotKey)
      else if bytes.size != 32 then Left(Failure.InvalidHotKeyLength(bytes.size))
      else Right(new HotKesKey32(bytes))
    }

  final class Certificate private (
      val hotKey: HotKesKey32,
      val counter: UInt64,
      val startPeriod: UInt64,
      val signature: Signature64
  )
  object Certificate:
    def create(
        hotKey: HotKesKey32,
        counter: UInt64,
        startPeriod: UInt64,
        signature: Signature64
    ): Either[Failure, Certificate] =
      if hotKey == null then Left(Failure.MissingHotKey)
      else if counter == null then Left(Failure.InvalidCounter(UInt64.Error.MissingValue))
      else if startPeriod == null then Left(Failure.InvalidStartPeriod(UInt64.Error.MissingValue))
      else if signature == null then Left(Failure.MissingSignature)
      else Right(new Certificate(hotKey, counter, startPeriod, signature))

    def fromBytes(
        hotKey: Bytes,
        counter: BigInt,
        startPeriod: BigInt,
        signature: Bytes
    ): Either[Failure, Certificate] = protect {
      for
        hot <- HotKesKey32.fromBytes(hotKey)
        n <- UInt64.fromBigInt(counter).left.map(Failure.InvalidCounter(_))
        start <- UInt64.fromBigInt(startPeriod).left.map(Failure.InvalidStartPeriod(_))
        sig <-
          if signature == null then Left(Failure.MissingSignature)
          else Signature64.create(signature).left.map(Failure.Signature(_))
        cert <- create(hot, n, start, sig)
      yield cert
    }

  enum Result:
    case SignatureRejected
    case OperationalCertificateSignatureVerified

  private def protect[A](body: => Either[Failure, A]): Either[Failure, A] =
    Either.catchNonFatal(body).fold(e => Left(Failure.Defect(e)), identity)

  /** Exactly rawHotKey32 || counterBE8 || startPeriodBE8. No CBOR, hash, prefix or prehash. */
  def signableBytes(cert: Certificate): Either[Failure, Bytes] = protect {
    if cert == null then Left(Failure.MissingCertificate)
    else
      def be8(n: UInt64): Array[Byte] =
        Array.tabulate[Byte](8)(i => ((n.value >> (8 * (7 - i))) & 255).toByte)
      Right(
        Bytes.fromArray(cert.hotKey.bytes.toArray ++ be8(cert.counter) ++ be8(cert.startPeriod))
      )
  }

  def verifySignature(coldKey: PublicKey32, cert: Certificate): Either[Failure, Result] =
    verifyWith(coldKey, cert, StrictEd25519.verifyEd25519)

  /** Raw envelope convenience entry point; checked constructors own all byte inputs. */
  def verify(
      coldKey: Bytes,
      hotKey: Bytes,
      counter: BigInt,
      startPeriod: BigInt,
      signature: Bytes
  ): Either[Failure, Result] = protect {
    for
      key <-
        if coldKey == null then Left(Failure.MissingColdKey)
        else PublicKey32.create(coldKey).left.map(Failure.ColdKey(_))
      cert <- Certificate.fromBytes(hotKey, counter, startPeriod, signature)
      result <- verifySignature(key, cert)
    yield result
  }

  // Package-only failure/call-count seam. Production callers cannot change the acceptance profile.
  private[opcert] def verifyWith(
      coldKey: PublicKey32,
      cert: Certificate,
      primitive: (PublicKey32, Signature64, Bytes) => Either[VerificationError, VerificationResult]
  ): Either[Failure, Result] = protect {
    if coldKey == null then Left(Failure.MissingColdKey)
    else
      signableBytes(cert).flatMap { message =>
        // the primitive is a function value: a reply, a verdict or an error it leaves out (a null
        // from a Java implementation) is its contract broken, not a verdict
        Option(primitive(coldKey, cert.signature, message)) match
          case None => Left(Failure.PrimitiveContractViolation(PrimitiveReply.MissingResult))
          case Some(Right(verdict)) =>
            Option(verdict) match
              case Some(VerificationResult.SignatureVerified) =>
                Right(Result.OperationalCertificateSignatureVerified)
              case Some(VerificationResult.SignatureRejected) => Right(Result.SignatureRejected)
              case None =>
                Left(Failure.PrimitiveContractViolation(PrimitiveReply.MissingVerdict))
          case Some(Left(error)) =>
            Option(error) match
              case Some(e) => Left(Failure.PrimitiveFailure(e))
              case None    => Left(Failure.PrimitiveContractViolation(PrimitiveReply.MissingError))
      }
  }
