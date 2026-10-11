// SPDX-License-Identifier: Apache-2.0
package lab.node.consensus

import lab.cbor.Bytes
import lab.kes.Sum6Kes
import lab.node.Header
import lab.opcert.OperationalCertificate

/** Why an operational certificate (or its KES signature) is rejected. */
enum OCertError:
  case KesPeriodOutsideWindow(period: BigInt, start: BigInt, evolutions: BigInt)
  case KesMalformed(reason: String)
  case KesSignatureRejected
  case OpCertMalformed(reason: String)
  case OpCertSignatureRejected
  case NoCounter(issuer: Bytes)
  case CounterTooSmall(current: BigInt, got: BigInt)
  case CounterOutOfRange(current: BigInt, got: BigInt)

  def message: String = this match
    case KesPeriodOutsideWindow(p, s, e) =>
      s"KES period $p outside opcert window [$s, ${s + e})"
    case KesMalformed(r)         => r
    case KesSignatureRejected    => "KES signature rejected"
    case OpCertMalformed(r)      => r
    case OpCertSignatureRejected => "opcert signature rejected"
    case NoCounter(i) => s"no opcert counter for issuer ${i.hex} (not a pool or genesis delegate)"
    case CounterTooSmall(m, n)   => s"opcert counter $n below current $m"
    case CounterOutOfRange(m, n) => s"opcert counter $n not in [$m, ${m + 1}]"

/** The OCERT rule shared by TPraos and Praos headers. */
object OCertRules:
  /** KES period window, the cold-key signature over the hot key, and the hot-key KES signature over
    * the header body.
    */
  def verifySignatures(
      header: Header.Shelley,
      slotsPerKesPeriod: BigInt,
      maxKesEvolutions: BigInt
  ): Either[OCertError, Unit] =
    val kesPeriod = header.slot / slotsPerKesPeriod
    val start = header.opCert.kesPeriod
    for
      _ <- Either.cond(
        start <= kesPeriod && kesPeriod < start + maxKesEvolutions,
        (),
        OCertError.KesPeriodOutsideWindow(kesPeriod, start, maxKesEvolutions)
      )
      kes <- Sum6Kes
        .verify(
          header.opCert.hotVkey,
          (kesPeriod - start).toInt,
          header.bodyRaw,
          header.kesSignature
        )
        .left
        .map(e => OCertError.KesMalformed(e.toString))
      _ <- Either.cond(
        kes == Sum6Kes.Result.SuppliedMessageSignatureVerified,
        (),
        OCertError.KesSignatureRejected
      )
      oc <- OperationalCertificate
        .verify(
          header.issuerVkey,
          header.opCert.hotVkey,
          header.opCert.sequence,
          start,
          header.opCert.sigma
        )
        .left
        .map(e => OCertError.OpCertMalformed(e.toString))
      _ <- Either.cond(
        oc == OperationalCertificate.Result.OperationalCertificateSignatureVerified,
        (),
        OCertError.OpCertSignatureRejected
      )
    yield ()

  /** The issue-number rule. `known`: the issuer may start a counter at 0 (a registered pool or a
    * genesis delegate). `bounded`: Praos also rejects `n > m + 1`; TPraos only `n < m`.
    */
  def checkCounter(
      counters: Map[Bytes, BigInt],
      issuer: Bytes,
      sequence: BigInt,
      known: Boolean,
      bounded: Boolean
  ): Either[OCertError, Unit] =
    counters.get(issuer).orElse(Option.when(known)(BigInt(0))) match
      case None => Left(OCertError.NoCounter(issuer))
      case Some(m) =>
        if bounded && (sequence < m || sequence > m + 1) then
          Left(OCertError.CounterOutOfRange(m, sequence))
        else if sequence < m then Left(OCertError.CounterTooSmall(m, sequence))
        else Right(())
