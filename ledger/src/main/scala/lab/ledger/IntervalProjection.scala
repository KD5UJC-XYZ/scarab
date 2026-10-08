// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import lab.cbor.{Bytes, Cbor, Value as V}
import lab.witness.ExactBodyCbor

/** Internal semantic projection only. The stripped envelope is NEVER identity, signature, or
  * memo-size evidence. Preserve pinned historical decoders and reuse their closed checks.
  */
private[ledger] object IntervalProjection:
  private def stripped(original: Bytes): Either[String, Bytes] =
    for
      _ <- ValidityInterval.decode(original)
      root <- Cbor.decode(original)
      result <- root.value match
        case V.Arr(Vector(body, witnesses, _, _)) =>
          body.value match
            case V.Map(fields) =>
              val retained = fields.filter { (key, _) =>
                key.value != V.UInt(3) && key.value != V.UInt(8)
              }
              // Three entries, copied verbatim, including original nested output spans.
              val bytes = Vector(0x84.toByte, 0xa3.toByte) ++
                retained.flatMap((k, v) => k.original.value ++ v.original.value) ++
                witnesses.original.value ++ Vector(0xf5.toByte, 0xf6.toByte)
              Right(Bytes(bytes))
            case _ => Left("body map required")
        case _ => Left("transaction envelope required")
    yield result

  def coverage(original: Bytes): Either[CoverageError, Coverage.Projection] =
    stripped(original).left.map(CoverageError.Malformed.apply).flatMap(Coverage.decode)

  def balance(original: Bytes): Either[LedgerError, Balance.TransferMintBody] =
    stripped(original).left.map(LedgerError.Malformed.apply).flatMap(Balance.decode)

  def body(original: Bytes): Either[String, ExactBodyCbor] =
    ValidityInterval
      .decode(original)
      .flatMap(i => ExactBodyCbor.create(i.originalBody).left.map(_.toString))

  def feeSize(
      p: FeeSize.Parameters,
      originalBody: Bytes,
      projected: Coverage.Projection
  ): Either[FeeSizeError, FeeSizeResult] =
    FeeSize
      .componentSize(BigInt(originalBody.size), BigInt(projected.originalWitnessMap.size))
      .map { size =>
        val minimum = size * p.feePerByte + p.feeFixed
        FeeSizeResult(
          if projected.fee >= minimum then FeePredicate.Satisfied(projected.fee, minimum)
          else FeePredicate.FeeTooSmall(projected.fee, minimum),
          if size <= p.maxTxSize then SizePredicate.Satisfied(size, p.maxTxSize)
          else SizePredicate.MaxTxSize(size, p.maxTxSize)
        )
      }
