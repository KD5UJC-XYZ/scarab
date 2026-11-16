// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.conway

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Rational}
import xyz.kd5ujc.scarab.ledger.{Block, DetachedRecords, LedgerDecodeError, Unsigned}
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.ledger.era.{
  AlonzoEraTx,
  AlonzoEraTxAuxData,
  AlonzoTx,
  ConwayEraPParams,
  AlonzoEraTxWits,
  EraTxAuxData,
  EraTxBody,
  EraTxError,
  EraTxWits,
  IsPhase2Valid,
  PParams,
  PositiveInterval,
  TopOnly,
  TxLevel
}
import xyz.kd5ujc.scarab.ledger.state.{Coin, Conway, EraTag, NonZero}

/** A Conway transaction: `AlonzoTx TopTx ConwayEra` (`data AlonzoTx l era`, cardano-ledger f649f975
  * `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/Tx.hs:205-212`): its body, witness set, phase-two
  * flag and auxiliary data, each Conway's. Made only by [[ConwayTx.decode]].
  */
final class ConwayTx private (
    val body: ConwayTxBody,
    val wits: ConwayTxWits,
    val isValid: IsPhase2Valid,
    val auxData: Option[ConwayTxAuxData]
):
  // Not a case class: its derived `Mirror` would make one from any fields, whatever its bytes.
  private def fields: Any = (body, wits, isValid, auxData)
  override def equals(that: Any): Boolean = that match
    case o: ConwayTx => fields == o.fields
    case _           => false
  override def hashCode(): Int = fields.hashCode
  override def toString: String = s"ConwayTx($fields)"

  /** `sizeTxForFeeCalculation`, as the flat transaction's size. */
  def sizeTxForFeeCalculation: Long =
    1L + body.raw.size + wits.raw.size + auxData.fold(1L)(_.raw.size.toLong)

object ConwayTx:
  given CanEqual[ConwayTx, ConwayTx] = CanEqual.derived

  /** A transaction of a Conway block, its parts read by Conway's decoders. */
  def decode(t: Block.Transaction): Either[EraTxError, ConwayTx] =
    (
      ConwayTxBody.decode(t.body),
      ConwayTxWits.decode(t.witnesses),
      t.auxiliaryData.traverse(ConwayTxAuxData.decode)
    ).mapN((body, wits, aux) => ConwayTx(body, wits, IsPhase2Valid.of(t.isValid), aux))

  /** A whole Conway transaction as a client or a peer submits it: its parts as the flat decoder
    * splits them ([[xyz.kd5ujc.scarab.ledger.Tx.envelopeParts]]), each read by Conway's decoders,
    * as [[xyz.kd5ujc.scarab.ledger.Tx.fromEnvelope]] reads them.
    */
  def fromEnvelope(bytes: Bytes): Either[EraTxError, ConwayTx] =
    DetachedRecords
      .read(bytes, Cbor.Limits.Unbounded, DetachedRecords.Shapes.transaction(Era.Conway))(
        LedgerDecodeError.Syntax(_)
      )(xyz.kd5ujc.scarab.ledger.Tx.envelopeParts(_, Era.Conway))
      .leftMap(EraTxError.Decode(_))
      .flatMap(decode)

  val instance: AlonzoEraTx[Conway] { val bodyOf: EraTxBody.conway.type } =
    new AlonzoEraTx[Conway]:
      override val bodyOf: EraTxBody.conway.type = EraTxBody.conway
      override def witsOf: AlonzoEraTxWits[Conway] = EraTxWits.conway
      override def auxDataOf: AlonzoEraTxAuxData[Conway] = EraTxAuxData.conway
      override def body[L <: TxLevel](tx: xyz.kd5ujc.scarab.ledger.era.Tx[L, Conway])(using
          l: TopOnly[L]
      ): xyz.kd5ujc.scarab.ledger.era.TxBody[L, Conway] =
        l match
          case TopOnly.Top => tx.body
      override def wits[L <: TxLevel](tx: xyz.kd5ujc.scarab.ledger.era.Tx[L, Conway])(using
          l: TopOnly[L]
      ): ConwayTxWits =
        l match
          case TopOnly.Top => tx.wits
      override def auxData[L <: TxLevel](tx: xyz.kd5ujc.scarab.ledger.era.Tx[L, Conway])(using
          l: TopOnly[L]
      ): Option[ConwayTxAuxData] =
        l match
          case TopOnly.Top => tx.auxData
      override def sizeTxForFeeCalculation[L <: TxLevel](
          tx: xyz.kd5ujc.scarab.ledger.era.Tx[L, Conway]
      )(using l: TopOnly[L]): Long =
        l match
          case TopOnly.Top => tx.sizeTxForFeeCalculation
      // `sizeTxF = conwayTxL . sizeAlonzoTxF`, `Conway/Tx.hs:86`, cardano-ledger f649f975: a list
      // header of one byte and the parts' bytes, the count `sizeTxForFeeCalculation` makes
      override def sizeTxF[L <: TxLevel](tx: xyz.kd5ujc.scarab.ledger.era.Tx[L, Conway])(using
          l: TopOnly[L]
      ): Long =
        l match
          case TopOnly.Top => tx.sizeTxForFeeCalculation
      // `getMinFeeTx = getConwayMinFeeTx`, `Conway/Tx.hs:92`, cardano-ledger f649f975
      override def getMinFeeTx[L <: TxLevel](
          pp: PParams[Conway],
          tx: xyz.kd5ujc.scarab.ledger.era.Tx[L, Conway],
          refScriptsSize: Long
      )(using l: TopOnly[L]): Coin =
        getConwayMinFeeTx[Conway, L](pp, tx, refScriptsSize)(using this, l)
      override def isValid(tx: ConwayTx): IsPhase2Valid = tx.isValid

  /** `getConwayMinFeeTx` (`Conway/Tx.hs:100-118`, cardano-ledger f649f975): Alonzo's minimum fee
    * and the fee of the reference scripts.
    */
  def getConwayMinFeeTx[E <: EraTag, L <: TxLevel](
      pp: PParams[E],
      tx: xyz.kd5ujc.scarab.ledger.era.Tx[L, E],
      refScriptsSize: Long
  )(using t: AlonzoEraTx[E], l: t.bodyOf.Level[L], p: ConwayEraPParams[E]): Coin =
    Coin(
      AlonzoTx.alonzoMinFeeTx[E, L](pp, tx).value +
        tierRefScriptFee(
          p.ppRefScriptCostMultiplierG.get(pp),
          p.ppRefScriptCostStrideG.get(pp),
          ConwayEraPParams.ppMinFeeRefScriptCostPerByteL[E].get(pp).rational,
          refScriptsSize
        ).value
    )

  /** `tierRefScriptFee` (`Conway/Tx.hs:120-142`, cardano-ledger f649f975): the price per byte
    * starts at `baseFee` and is multiplied by `multiplier` for each `sizeIncrement` bytes. The
    * reference refuses a multiplier or an increment that is not positive with an error; their types
    * here have no such value. The size is a 64-bit `Int`, as the reference's.
    */
  def tierRefScriptFee(
      multiplier: PositiveInterval,
      sizeIncrement: NonZero[Unsigned[Unsigned.Width.W32]],
      baseFee: Rational,
      totalSize: Long
  ): Coin =
    val increment = sizeIncrement.value.value.toLong
    val sizeIncrementRational = Rational(BigInt(increment))
    @annotation.tailrec
    def go(acc: Rational, curTierPrice: Rational, n: Long): Coin =
      if n < increment then Coin((acc + Rational(BigInt(n)) * curTierPrice).floor)
      else
        go(
          acc + sizeIncrementRational * curTierPrice,
          multiplier.rational * curTierPrice,
          n - increment
        )
    go(Rational(BigInt(0)), baseFee, totalSize)
