// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import xyz.kd5ujc.scarab.ledger.TxIn
import xyz.kd5ujc.scarab.ledger.state.{
  Coin,
  Conway,
  Credential,
  EraTag,
  KeyHash,
  StakePool,
  Staking
}

/** What a transaction of era `E` consumes and produces, and its minimum fee given the UTxO set
  * (`class EraTx era => EraUTxO era`, cardano-ledger f649f975
  * `libs/cardano-ledger-core/src/Cardano/Ledger/State/UTxO.hs:242-285`). The superclass `EraTx era`
  * is a member ([[txOf]]), as the other classes' superclasses of other families are.
  *
  * The members that find the scripts a transaction needs and provides and the keys that must sign
  * it (`ScriptsNeeded`, `getScriptsProvided`, `getScriptsNeeded`, `getScriptsHashesNeeded`,
  * `getWitsVKeyNeeded`) are UTXOW's, and come with it (#85).
  */
trait EraUTxO[E <: EraTag]:
  /** The transactions' class (the superclass `EraTx era`). */
  val txOf: EraTx[E]

  /** `getConsumedValue`: what the body consumes, given the deposit of a registered staking
    * credential.
    */
  def getConsumedValue[L <: TxLevel](
      pp: PParams[E],
      lookupStakingDeposit: Credential[Staking] => Option[Coin],
      utxo: UTxO[E],
      body: TxBody[L, E]
  )(using txOf.bodyOf.Level[L]): Value[E]

  /** `getProducedValue`: what a top-level body produces, given whether a pool is registered. */
  def getProducedValue(
      pp: PParams[E],
      isPoolRegistered: KeyHash[StakePool] => Boolean,
      body: TxBody[TxLevel.Top, E]
  ): Value[E]

  /** `getMinFeeTxUtxo`: the minimum fee, its witnesses excluded, the reference scripts the UTxO set
    * gives it counted.
    */
  def getMinFeeTxUtxo[L <: TxLevel](pp: PParams[E], tx: Tx[L, E], utxo: UTxO[E])(using
      txOf.bodyOf.Level[L]
  ): Coin

/** The core's functions of the UTxO set (`State/UTxO.hs`, cardano-ledger f649f975). */
object UTxOs:
  /** `txInsFilter` (`State/UTxO.hs:168-174`, cardano-ledger f649f975): the outputs of `txIns`. */
  def txInsFilter[E <: EraTag](utxo: UTxO[E], txIns: Set[TxIn]): UTxO[E] =
    UTxO(utxo.unUTxO.filter((in, _) => txIns.contains(in)))

  /** `sumAllValue` (`State/UTxO.hs:199-200`, cardano-ledger f649f975). */
  def sumAllValue[E <: EraTag](outs: Iterable[TxOut[E]])(using o: EraTxOut[E]): Value[E] =
    outs.foldLeft(o.valueOps.zero)((total, out) => o.valueOps.plus(total, o.value(out)))

  /** `sumUTxO` (`State/UTxO.hs:185-186`, cardano-ledger f649f975). */
  def sumUTxO[E <: EraTag](utxo: UTxO[E])(using EraTxOut[E]): Value[E] =
    sumAllValue(utxo.unUTxO.values)

/** Shelley's functions of the UTxO set (`eras/shelley/impl/src/Cardano/Ledger/Shelley/UTxO.hs`,
  * cardano-ledger f649f975).
  */
object ShelleyUTxO:
  /** `shelleyProducedValue` (`Shelley/UTxO.hs:131-143`, cardano-ledger f649f975): the outputs, the
    * fee and the deposits.
    */
  def shelleyProducedValue[E <: EraTag](
      pp: PParams[E],
      isRegPoolId: KeyHash[StakePool] => Boolean,
      body: TxBody[TxLevel.Top, E]
  )(using b: EraTxBody[E]): Value[E] =
    given EraTxOut[E] = b.outputsOf
    val v = b.outputsOf.valueOps
    v.plus(
      UTxOs.sumAllValue(b.outputs[TxLevel.Top](body)(using b.top)),
      v.inject(Coin(b.fee(body).value + b.getTotalDepositsTxBody(pp, isRegPoolId, body).value))
    )

/** Mary's functions of the UTxO set (`eras/mary/impl/src/Cardano/Ledger/Mary/UTxO.hs`,
  * cardano-ledger f649f975), of the eras whose value is `MaryValue` (`Value era ~ MaryValue`, given
  * as `v`).
  */
object MaryUTxO:
  /** `getConsumedMaryValue` (`Mary/UTxO.hs:65-81`, cardano-ledger f649f975): the spent outputs, the
    * refunds, the withdrawals and the assets the body mints.
    */
  def getConsumedMaryValue[E <: EraTag, L <: TxLevel](
      pp: PParams[E],
      lookupStakingDeposit: Credential[Staking] => Option[Coin],
      utxo: UTxO[E],
      body: TxBody[L, E]
  )(using b: MaryEraTxBody[E], l: b.Level[L], v: Value[E] =:= MultiAssetValue): MultiAssetValue =
    given EraTxOut[E] = b.outputsOf
    val ops = summon[Val[MultiAssetValue]]
    val mintedMultiAsset =
      MultiAssetValue.of(xyz.kd5ujc.scarab.ledger.Value.minted(b.mint(body)))
    val refunds = b.getTotalRefundsTxBody(pp, lookupStakingDeposit, body)
    val withdrawals = b.withdrawals(body).valuesIterator.map(_.value).sum
    val consumedValue = ops.plus(
      v(UTxOs.sumUTxO(UTxOs.txInsFilter(utxo, b.inputs(body)))),
      ops.inject(Coin(refunds.value + withdrawals))
    )
    ops.plus(consumedValue, mintedMultiAsset)

  /** `getProducedMaryValue` (`Mary/UTxO.hs:83-91`, cardano-ledger f649f975): Shelley's produced
    * value and the assets the body burns.
    */
  def getProducedMaryValue[E <: EraTag](
      pp: PParams[E],
      isPoolRegistered: KeyHash[StakePool] => Boolean,
      body: TxBody[TxLevel.Top, E]
  )(using b: MaryEraTxBody[E], v: Value[E] =:= MultiAssetValue): MultiAssetValue =
    summon[Val[MultiAssetValue]].plus(
      v(ShelleyUTxO.shelleyProducedValue(pp, isPoolRegistered, body)),
      burnedMultiAssets[E, TxLevel.Top](body)(using b, b.top)
    )

  /** `burnedMultiAssets` (`Mary/UTxO.hs:93-97`, cardano-ledger f649f975): the assets the body mints
    * a negative quantity of, negated.
    */
  def burnedMultiAssets[E <: EraTag, L <: TxLevel](body: TxBody[L, E])(using
      b: MaryEraTxBody[E],
      l: b.Level[L]
  ): MultiAssetValue =
    MultiAssetValue.of(xyz.kd5ujc.scarab.ledger.Value.burned(b.mint(body)))

/** Babbage's functions of the UTxO set (`eras/babbage/impl/src/Cardano/Ledger/Babbage/UTxO.hs`,
  * cardano-ledger f649f975).
  */
object BabbageUTxO:
  /** `getReferenceScriptsNonDistinct` (`Babbage/UTxO.hs:157-166`, cardano-ledger f649f975): the
    * reference script of each output of `inputs` that has one, a script as many times as outputs
    * carry it, with its hash, in the order of the inputs (`Map.elems`).
    */
  def getReferenceScriptsNonDistinct[E <: EraTag](utxo: UTxO[E], inputs: Set[TxIn])(using
      o: BabbageEraTxOut[E]
  ): Vector[(xyz.kd5ujc.scarab.codec.Bytes, Script[E])] =
    UTxOs
      .txInsFilter(utxo, inputs)
      .unUTxO
      .toVector
      .sortBy(_._1)
      .flatMap((_, out) => o.referenceScript(out))
      .map(script => (o.scripts.hashScript(script), script))

/** Conway's instance (`instance EraUTxO ConwayEra`, `eras/conway/impl/src/Cardano/Ledger/Conway/
  * UTxO.hs:119-137`, cardano-ledger f649f975).
  */
object EraUTxO:
  given conway: EraUTxO[Conway] = xyz.kd5ujc.scarab.ledger.era.conway.ConwayUTxO.instance
