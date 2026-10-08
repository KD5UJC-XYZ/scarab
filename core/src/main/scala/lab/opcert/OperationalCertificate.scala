// SPDX-License-Identifier: Apache-2.0
package lab.opcert

import lab.cbor.Bytes
import lab.witness.{PublicKey32, Signature64, StrictEd25519, VerificationError, VerificationResult}
import scala.util.control.NonFatal

/** Public operational-certificate signature predicate only. No pool registration, counter-state,
  * KES, header validity or chain-authenticity claim. Uses the existing experimental strict profile.
  */
object OperationalCertificate:
  enum Failure:
    case Malformed(reason: String)
    case InternalFailure(kind: String)

  final class UInt64 private (val value: BigInt)
  object UInt64:
    val MaxValue: BigInt = (BigInt(1) << 64) - 1
    def fromBigInt(value: BigInt): Either[Failure, UInt64] =
      if value == null || value < 0 || value > MaxValue then
        Left(Failure.Malformed("value must be unsigned Word64"))
      else Right(new UInt64(value))

  final class HotKesKey32 private (val bytes: Bytes)
  object HotKesKey32:
    def fromBytes(bytes: Bytes): Either[Failure, HotKesKey32] = protect {
      if bytes == null || bytes.size != 32 then
        Left(Failure.Malformed("hot KES key must be 32 bytes"))
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
      if hotKey == null || counter == null || startPeriod == null || signature == null then
        Left(Failure.Malformed("null certificate component"))
      else Right(new Certificate(hotKey, counter, startPeriod, signature))

    def fromBytes(
        hotKey: Bytes,
        counter: BigInt,
        startPeriod: BigInt,
        signature: Bytes
    ): Either[Failure, Certificate] = protect {
      for
        hot <- HotKesKey32.fromBytes(hotKey)
        n <- UInt64.fromBigInt(counter)
        start <- UInt64.fromBigInt(startPeriod)
        sig <-
          if signature == null then Left(Failure.Malformed("null signature"))
          else Signature64.create(signature).left.map(e => Failure.Malformed(e.toString))
        cert <- create(hot, n, start, sig)
      yield cert
    }

  enum Result:
    case SignatureRejected
    case OperationalCertificateSignatureVerified

  private def protect[A](body: => Either[Failure, A]): Either[Failure, A] =
    try body
    catch case NonFatal(e) => Left(Failure.InternalFailure(e.getClass.getName))

  /** Exactly rawHotKey32 || counterBE8 || startPeriodBE8. No CBOR, hash, prefix or prehash. */
  def signableBytes(cert: Certificate): Either[Failure, Bytes] = protect {
    if cert == null then Left(Failure.Malformed("null certificate"))
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
        if coldKey == null then Left(Failure.Malformed("null cold key"))
        else PublicKey32.create(coldKey).left.map(e => Failure.Malformed(e.toString))
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
    if coldKey == null then Left(Failure.Malformed("null cold key"))
    else
      signableBytes(cert).flatMap { message =>
        primitive(coldKey, cert.signature, message) match
          case Right(VerificationResult.SignatureVerified) =>
            Right(Result.OperationalCertificateSignatureVerified)
          case Right(VerificationResult.SignatureRejected) => Right(Result.SignatureRejected)
          case Left(VerificationError.ImplementationFailure(kind)) =>
            Left(Failure.InternalFailure(kind))
          case _ => Left(Failure.InternalFailure("primitive-contract"))
      }
  }
