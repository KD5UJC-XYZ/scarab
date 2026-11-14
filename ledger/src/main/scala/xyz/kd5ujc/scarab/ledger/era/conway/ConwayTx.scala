// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.conway

import cats.syntax.all.*
import xyz.kd5ujc.scarab.ledger.Block
import xyz.kd5ujc.scarab.ledger.era.{
  AlonzoEraTx,
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
final case class ConwayTx private (
    body: ConwayTxBody,
    wits: ConwayTxWits,
    isValid: IsPhase2Valid,
    auxData: Option[ConwayTxAuxData]
):
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

  val instance: AlonzoEraTx[Conway] = new AlonzoEraTx[Conway]:
    override val bodyOf: EraTxBody.conway.type = EraTxBody.conway
    override def witsOf: EraTxWits[Conway] = EraTxWits.conway
    override def auxDataOf: EraTxAuxData[Conway] = EraTxAuxData.conway
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
