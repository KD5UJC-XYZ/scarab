// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.conway

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor}
import xyz.kd5ujc.scarab.ledger.{Block, DetachedRecords, LedgerDecodeError}
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.ledger.era.{
  AlonzoEraTx,
  AlonzoEraTxAuxData,
  AlonzoEraTxWits,
  EraTxAuxData,
  EraTxBody,
  EraTxError,
  EraTxWits,
  IsPhase2Valid,
  TopOnly,
  TxLevel
}
import xyz.kd5ujc.scarab.ledger.state.Conway

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

  val instance: AlonzoEraTx[Conway] = new AlonzoEraTx[Conway]:
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
    override def isValid(tx: ConwayTx): IsPhase2Valid = tx.isValid
