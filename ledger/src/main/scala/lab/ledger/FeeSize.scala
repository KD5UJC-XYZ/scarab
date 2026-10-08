// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import lab.cbor.Bytes

enum FeeSizeError:
  case Scope(error: CoverageError)
  case UnsupportedEra(era: String)
  case UnsupportedProtocol(major: Int)
  case InvalidParameter(name: String, supplied: BigInt, maximum: BigInt)
  case TransactionSizeOverflow(supplied: BigInt)
  case InvalidComponentLength(supplied: BigInt)

enum FeePredicate:
  case Satisfied(supplied: BigInt, minimum: BigInt)
  case FeeTooSmall(supplied: BigInt, minimum: BigInt)

enum SizePredicate:
  case Satisfied(supplied: BigInt, maximum: BigInt)
  case MaxTxSize(supplied: BigInt, maximum: BigInt)

final case class FeeSizeResult(fee: FeePredicate, size: SizePredicate)

/** Exact memo-byte size and closed transfer predicates, not transaction validity. */
object FeeSize:
  val MaxWord64: BigInt = (BigInt(1) << 64) - 1
  val MaxWord32: BigInt = (BigInt(1) << 32) - 1

  final class Parameters private[FeeSize] (
      val feePerByte: BigInt,
      val feeFixed: BigInt,
      val maxTxSize: BigInt
  )
  object Parameters:
    def create(
        era: String,
        protocolMajor: Int,
        feePerByte: BigInt,
        feeFixed: BigInt,
        maxTxSize: BigInt
    ): Either[FeeSizeError, Parameters] =
      if era != "Conway" then Left(FeeSizeError.UnsupportedEra(era))
      else if protocolMajor != 9 then Left(FeeSizeError.UnsupportedProtocol(protocolMajor))
      else
        Vector(
          ("feePerByte", feePerByte, MaxWord64),
          ("feeFixed", feeFixed, MaxWord64),
          ("maxTxSize", maxTxSize, MaxWord32)
        ).find { case (_, n, max) => n < 0 || n > max } match
          case Some((name, n, max)) => Left(FeeSizeError.InvalidParameter(name, n, max))
          case None                 => Right(new Parameters(feePerByte, feeFixed, maxTxSize))

  final class Context private[FeeSize] (
      val parameters: Parameters,
      val resolved: Map[TxIn, Coverage.Output]
  )
  object Context:
    /** Every supplied output is reparsed and checked, including unspent entries. */
    def decode(parameters: Parameters, rawResolved: Bytes): Either[FeeSizeError, Context] =
      Coverage
        .decodeResolved(rawResolved)
        .left
        .map(FeeSizeError.Scope.apply)
        .map(new Context(parameters, _))

  def decode(raw: Bytes): Either[FeeSizeError, Coverage.Projection] =
    Coverage.decode(raw).left.map(FeeSizeError.Scope.apply)

  /** Numeric helper tests the Word32 limit without allocating multi-gigabyte transactions. Runtime
    * projections remain subject to the CBOR decoder's much smaller resource limits.
    */
  private[ledger] def componentSize(body: BigInt, witnesses: BigInt): Either[FeeSizeError, BigInt] =
    if body < 1 then Left(FeeSizeError.InvalidComponentLength(body))
    else if witnesses < 1 then Left(FeeSizeError.InvalidComponentLength(witnesses))
    else
      val size = BigInt(1) + body + witnesses + 1
      if size > MaxWord32 then Left(FeeSizeError.TransactionSizeOverflow(size)) else Right(size)

  /** Fresh 3-item envelope, retained body and witness map, canonical null; omit isValid. */
  def conwayLedgerSize(tx: Coverage.Projection): Either[FeeSizeError, BigInt] =
    componentSize(BigInt(tx.body.bytes.size), BigInt(tx.originalWitnessMap.size))

  def checkTransferFeeAndSize(
      context: Context,
      tx: Coverage.Projection
  ): Either[FeeSizeError, FeeSizeResult] =
    for
      // Membership coverage itself is deliberately independent. This establishes resolution
      // to checked script/datum-free spending outputs, necessary for zero reference cost.
      _ <- Coverage.check("Conway", 9, context.resolved, tx).left.map(FeeSizeError.Scope.apply)
      size <- conwayLedgerSize(tx)
    yield
      val p = context.parameters
      val minimum = size * p.feePerByte + p.feeFixed
      FeeSizeResult(
        if tx.fee >= minimum then FeePredicate.Satisfied(tx.fee, minimum)
        else FeePredicate.FeeTooSmall(tx.fee, minimum),
        if size <= p.maxTxSize then SizePredicate.Satisfied(size, p.maxTxSize)
        else SizePredicate.MaxTxSize(size, p.maxTxSize)
      )
