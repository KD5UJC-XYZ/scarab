// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.conway

import cats.syntax.all.*
import xyz.kd5ujc.scarab.ledger.TxIn
import xyz.kd5ujc.scarab.ledger.era.{
  AlonzoEraTx,
  BabbageEraTxOut,
  BabbageUTxO,
  EraTx,
  EraTxBody,
  EraTxError,
  EraTxOut,
  EraUTxO,
  MaryUTxO,
  MultiAssetValue,
  PParams,
  Tx,
  TxBody,
  TxLevel,
  UTxO,
  Val
}
import xyz.kd5ujc.scarab.ledger.state.{Coin, Conway, Credential, KeyHash, StakePool, Staking}

/** Conway's unspent outputs read from the outputs the node keeps. */
object ConwayUTxO:
  /** Each stored output read as a Conway output ([[ConwayTxOut.fromStored]]), the first one it
    * refuses refusing the whole: only a damaged store holds such an output.
    */
  def fromStored(
      stored: Map[TxIn, xyz.kd5ujc.scarab.ledger.TxOut]
  ): Either[EraTxError, UTxO[Conway]] =
    stored.toVector
      .traverse((in, out) => ConwayTxOut.fromStored(out).map(in -> _))
      .map(outs => UTxO[Conway](outs.toMap))

  /** `conwayProducedValue` (`Conway/UTxO.hs:107-117`, cardano-ledger f649f975): Mary's produced
    * value and the treasury donation.
    */
  def conwayProducedValue(
      pp: PParams[Conway],
      isStakePool: KeyHash[StakePool] => Boolean,
      body: TxBody[TxLevel.Top, Conway]
  ): MultiAssetValue =
    val b = EraTxBody.conway
    val ops = summon[Val[MultiAssetValue]]
    ops.plus(
      MaryUTxO.getProducedMaryValue[Conway](pp, isStakePool, body),
      ops.inject(b.treasuryDonation[TxLevel.Top](body)(using b.top))
    )

  /** `txNonDistinctRefScriptsSize` (`Conway/UTxO.hs:161-170`, cardano-ledger f649f975): the bytes
    * of the reference scripts of the spent and reference inputs, an input in both counted once and
    * a script as many times as outputs carry it, added up as the reference's 64-bit `Int`.
    */
  def txNonDistinctRefScriptsSize[L <: TxLevel](utxo: UTxO[Conway], tx: Tx[L, Conway])(using
      l: EraTxBody.conway.Level[L]
  ): Long =
    val b = EraTxBody.conway
    val body = EraTx.conway.body[L](tx)(using l)
    val inputs: Set[TxIn] = b.referenceInputs[L](body)(using l) ++ b.inputs[L](body)(using l)
    val o = summon[BabbageEraTxOut[Conway]]
    BabbageUTxO
      .getReferenceScriptsNonDistinct(utxo, inputs)
      .map((_, script) => o.scripts.originalBytes(script).size.toLong)
      .sum

  /** `getConwayMinFeeTxUtxo` (`Conway/UTxO.hs:146-158`, cardano-ledger f649f975): the minimum fee
    * with the size of the reference scripts the UTxO set gives the transaction.
    */
  def getConwayMinFeeTxUtxo[L <: TxLevel](
      pp: PParams[Conway],
      tx: Tx[L, Conway],
      utxo: UTxO[Conway]
  )(using
      l: EraTxBody.conway.Level[L]
  ): Coin =
    EraTx.conway.getMinFeeTx[L](pp, tx, txNonDistinctRefScriptsSize[L](utxo, tx))(using l)

  val instance: EraUTxO[Conway] = new EraUTxO[Conway]:
    override val txOf: EraTx.conway.type = EraTx.conway
    // `getConsumedValue = getConsumedMaryValue`, `Conway/UTxO.hs:122`, cardano-ledger f649f975
    override def getConsumedValue[L <: TxLevel](
        pp: PParams[Conway],
        lookupStakingDeposit: Credential[Staking] => Option[Coin],
        utxo: UTxO[Conway],
        body: TxBody[L, Conway]
    )(using l: txOf.bodyOf.Level[L]): MultiAssetValue =
      MaryUTxO.getConsumedMaryValue[Conway, L](pp, lookupStakingDeposit, utxo, body)(using
        txOf.bodyOf,
        l
      )
    // `getProducedValue = conwayProducedValue`, `Conway/UTxO.hs:124`, cardano-ledger f649f975
    override def getProducedValue(
        pp: PParams[Conway],
        isPoolRegistered: KeyHash[StakePool] => Boolean,
        body: TxBody[TxLevel.Top, Conway]
    ): MultiAssetValue = conwayProducedValue(pp, isPoolRegistered, body)
    // `getMinFeeTxUtxo = getConwayMinFeeTxUtxo`, `Conway/UTxO.hs:134`, cardano-ledger f649f975
    override def getMinFeeTxUtxo[L <: TxLevel](
        pp: PParams[Conway],
        tx: Tx[L, Conway],
        utxo: UTxO[Conway]
    )(using l: txOf.bodyOf.Level[L]): Coin = getConwayMinFeeTxUtxo[L](pp, tx, utxo)(using l)
