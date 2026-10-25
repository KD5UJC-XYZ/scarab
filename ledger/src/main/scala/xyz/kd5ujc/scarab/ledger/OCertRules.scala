// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.kes.Sum6Kes
import xyz.kd5ujc.scarab.ledger.Header
import xyz.kd5ujc.scarab.crypto.opcert.OperationalCertificate

/** Why an operational certificate (or its KES signature) is rejected. */
enum OCertError:
  case KesPeriodOutsideWindow(period: BigInt, start: BigInt, evolutions: BigInt)
  case KesFailure(cause: Sum6Kes.Failure)
  case KesSignatureRejected
  case OpCertFailure(cause: OperationalCertificate.Failure)
  case OpCertSignatureRejected
  case NoCounter(issuer: Bytes)
  case CounterTooSmall(current: BigInt, got: BigInt)
  case CounterOutOfRange(current: BigInt, got: BigInt)

  def message: String = this match
    case KesPeriodOutsideWindow(p, s, e) =>
      s"KES period $p outside opcert window [$s, ${s + e})"
    case KesFailure(e)           => e.toString
    case KesSignatureRejected    => "KES signature rejected"
    case OpCertFailure(e)        => e.message
    case OpCertSignatureRejected => "opcert signature rejected"
    case NoCounter(i) => s"no opcert counter for issuer ${i.hex} (not a pool or genesis delegate)"
    case CounterTooSmall(m, n)   => s"opcert counter $n below current $m"
    case CounterOutOfRange(m, n) => s"opcert counter $n not in [$m, ${m + 1}]"

/** The OCERT rule shared by TPraos and Praos headers. */
object OCertRules:
  extension [A](result: Either[OCertError | ProviderDefect, A])
    /** The refusal as the enclosing rule's, a provider defect as itself. */
    def refusedAs[E](refusal: OCertError => E): Either[E | ProviderDefect, A] = result match
      case Right(a)                => Right(a)
      case Left(e: OCertError)     => Left(refusal(e))
      case Left(d: ProviderDefect) => Left(d)

  /** KES period window, the cold-key signature over the hot key, and the hot-key KES signature over
    * the header body. A verifier that fails is a [[ProviderDefect]], never a refusal of the header.
    */
  def verifySignatures(
      header: Header.Shelley,
      slotsPerKesPeriod: BigInt,
      maxKesEvolutions: BigInt
  ): Either[OCertError | ProviderDefect, Unit] =
    verifySignaturesWith(
      header,
      slotsPerKesPeriod,
      maxKesEvolutions,
      Sum6Kes.verify,
      OperationalCertificate.verify
    )

  /** [[verifySignatures]] over the given verifiers, so a test can inject a failing provider. */
  private[ledger] def verifySignaturesWith(
      header: Header.Shelley,
      slotsPerKesPeriod: BigInt,
      maxKesEvolutions: BigInt,
      kesVerifier: (Bytes, Int, Bytes, Bytes) => Either[Sum6Kes.Failure, Sum6Kes.Result],
      certVerifier: (
          Bytes,
          Bytes,
          BigInt,
          BigInt,
          Bytes
      ) => Either[OperationalCertificate.Failure, OperationalCertificate.Result]
  ): Either[OCertError | ProviderDefect, Unit] =
    val kesPeriod = header.slot / slotsPerKesPeriod
    val start = header.opCert.kesPeriod
    for
      _ <- Either.cond(
        start <= kesPeriod && kesPeriod < start + maxKesEvolutions,
        (),
        OCertError.KesPeriodOutsideWindow(kesPeriod, start, maxKesEvolutions)
      )
      kes <- ProviderDefect.kes(
        kesVerifier(
          header.opCert.hotVkey,
          (kesPeriod - start).toInt,
          header.bodyRaw,
          header.kesSignature
        )
      )(OCertError.KesFailure(_))
      _ <- Either.cond(
        kes == Sum6Kes.Result.SuppliedMessageSignatureVerified,
        (),
        OCertError.KesSignatureRejected
      )
      oc <- ProviderDefect.opCert(
        certVerifier(
          header.issuerVkey,
          header.opCert.hotVkey,
          header.opCert.sequence,
          start,
          header.opCert.sigma
        )
      )(OCertError.OpCertFailure(_))
      _ <- Either.cond(
        oc == OperationalCertificate.Result.OperationalCertificateSignatureVerified,
        (),
        OCertError.OpCertSignatureRejected
      )
    yield ()

  /** The issue-number rule. `current` is the counter the issuer has, if any. `known`: the issuer
    * may start a counter at 0 (a registered pool or a genesis delegate). `bounded`: Praos also
    * rejects `n > m + 1`; TPraos only `n < m`.
    */
  def checkCounter(
      current: Option[BigInt],
      issuer: Bytes,
      sequence: BigInt,
      known: Boolean,
      bounded: Boolean
  ): Either[OCertError, Unit] =
    current.orElse(Option.when(known)(BigInt(0))) match
      case None => Left(OCertError.NoCounter(issuer))
      case Some(m) =>
        if bounded && (sequence < m || sequence > m + 1) then
          Left(OCertError.CounterOutOfRange(m, sequence))
        else if sequence < m then Left(OCertError.CounterTooSmall(m, sequence))
        else Right(())
