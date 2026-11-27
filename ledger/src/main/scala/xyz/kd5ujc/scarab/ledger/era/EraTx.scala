// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.ledger.state.{Babbage, Coin, Conway, Dijkstra, EraTag}

/** Whether a transaction's scripts are expected to validate (`data IsPhase2Valid`, cardano-ledger
  * f649f975 `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/Tx.hs:157-159`): the flag a block gives
  * each transaction from Alonzo.
  */
enum IsPhase2Valid:
  case Phase2Invalid, Phase2Valid

object IsPhase2Valid:
  given CanEqual[IsPhase2Valid, IsPhase2Valid] = CanEqual.derived

  def of(valid: Boolean): IsPhase2Valid = if valid then Phase2Valid else Phase2Invalid

/** A transaction of era `E` (`EraTx`,
  * `libs/cardano-ledger-core/src/Cardano/Ledger/Core.hs:150-222`, cardano-ledger f649f975): its
  * body, witnesses and auxiliary data, at the levels its body has, and its annotated form's
  * transaction and cache (the families [[StAnnTx]] and [[StAnnTxCache]]). The superclasses of other
  * families (`EraTxBody era`, `EraTxWits era`, `EraTxAuxData era`) are members.
  */
trait EraTx[E <: EraTag]:
  /** The bodies' class, whose levels are the transaction's (`HasEraTxLevel Tx era`). */
  val bodyOf: EraTxBody[E]

  /** The witness sets' class. */
  def witsOf: EraTxWits[E]

  /** The auxiliary data's class. */
  def auxDataOf: EraTxAuxData[E]

  /** `bodyTxL`. */
  def body[L <: TxLevel](tx: Tx[L, E])(using bodyOf.Level[L]): TxBody[L, E]

  /** `witsTxL`. */
  def wits[L <: TxLevel](tx: Tx[L, E])(using bodyOf.Level[L]): TxWits[E]

  /** `auxDataTxL`. */
  def auxData[L <: TxLevel](tx: Tx[L, E])(using bodyOf.Level[L]): Option[TxAuxData[E]]

  /** `txStAnnTxG` (`Core.hs:185`, cardano-ledger f649f975): the annotated transaction's
    * transaction.
    */
  def txStAnnTx[L <: TxLevel](stAnnTx: StAnnTx[L, E])(using bodyOf.Level[L]): Tx[L, E]

  /** `cacheStAnnTxG` (`Core.hs:188`, cardano-ledger f649f975): the cache of the annotated
    * transaction.
    */
  def cacheStAnnTx[L <: TxLevel](stAnnTx: StAnnTx[L, E])(using bodyOf.Level[L]): StAnnTxCache[E]

  /** `sizeTxForFeeCalculation` (`Core.hs:203-210`, cardano-ledger f649f975): the bytes of the body,
    * the witnesses and the auxiliary data (one byte for none) as they arrived, and one for the
    * transaction's own list.
    */
  def sizeTxForFeeCalculation[L <: TxLevel](tx: Tx[L, E])(using bodyOf.Level[L]): Long

  /** `sizeTxF` (`Core.hs:198-199`, cardano-ledger f649f975): the bytes of the transaction as its
    * size is computed for its fee and its limit (from Alonzo `sizeAlonzoTxF`, a three-element list
    * of the body, the witnesses and the auxiliary data or null, `Alonzo/Tx.hs:323-332,432-443`).
    */
  def sizeTxF[L <: TxLevel](tx: Tx[L, E])(using bodyOf.Level[L]): Long

  /** `validateNativeScript` (`Core.hs:213`, cardano-ledger f649f975): whether the native script
    * holds for the transaction: Allegra's `validateTimelock` from Allegra to Conway
    * ([[AllegraTx.validateTimelock]]), Dijkstra's `validateDijkstraNativeScript` (cardano-ledger
    * 9df9f98c).
    */
  def validateNativeScript[L <: TxLevel](tx: Tx[L, E], native: NativeScript[E])(using
      bodyOf.Level[L]
  ): Boolean

  /** `getMinFeeTx` (`Core.hs:215-221`, cardano-ledger f649f975): the minimum fee of the
    * transaction, its witnesses excluded, given the size of the reference scripts it uses.
    */
  def getMinFeeTx[L <: TxLevel](pp: PParams[E], tx: Tx[L, E], refScriptsSize: Long)(using
      bodyOf.Level[L]
  ): Coin

/** The phase-two flag of a transaction, from Alonzo (`AlonzoEraTx`,
  * `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/Tx.hs:266-281`, cardano-ledger f649f975), of a
  * top-level transaction only.
  */
trait AlonzoEraTx[E <: EraTag] extends EraTx[E]:
  // the superclasses `AlonzoEraTxBody era`, `AlonzoEraTxWits era` (with `AlonzoEraScript era`) and
  // `AlonzoEraTxAuxData era` (`Alonzo/Tx.hs:266-275`, cardano-ledger f649f975)
  override val bodyOf: AlonzoEraTxBody[E]
  override def witsOf: AlonzoEraTxWits[E]
  override def auxDataOf: AlonzoEraTxAuxData[E]

  /** `isValidTxL`. */
  def isValid(tx: Tx[TxLevel.Top, E]): IsPhase2Valid

  /** `getTotalExUnits` (`Alonzo/Tx.hs:330-331`, cardano-ledger 9df9f98c): the units of every
    * redeemer of the witnesses, added up, the class's default, which Dijkstra's instance overrides
    * to add its sub-transactions' units. The units are the witness decoder's, whose components it
    * reads as unsigned integers. At f649f975 it is the function `totExUnits`
    * (`Alonzo/Tx.hs:460-464`), which the default is.
    */
  def getTotalExUnits[L <: TxLevel](
      tx: Tx[L, E]
  )(using bodyOf.Level[L]): xyz.kd5ujc.scarab.plutus.ExUnits =
    witsOf
      .rdmrsTxWits(wits[L](tx))
      .entries
      .valuesIterator
      .map(_._2)
      .foldLeft(xyz.kd5ujc.scarab.plutus.ExUnits.zero)(_ + _)

/** The functions of Alonzo's transactions (`eras/alonzo/impl/src/Cardano/Ledger/Alonzo/Tx.hs`,
  * cardano-ledger f649f975).
  */
object AlonzoTx:
  /** `totExUnits` (`Alonzo/Tx.hs:460-464`, cardano-ledger f649f975; `:510-514` at 9df9f98c, where
    * it is the deprecated name of `getTotalExUnits`): the transaction's units, the class member
    * [[AlonzoEraTx.getTotalExUnits]].
    */
  def totExUnits[E <: EraTag, L <: TxLevel](tx: Tx[L, E])(using
      t: AlonzoEraTx[E],
      l: t.bodyOf.Level[L]
  ): xyz.kd5ujc.scarab.plutus.ExUnits =
    t.getTotalExUnits[L](tx)

  /** `txscriptfee` (`libs/cardano-ledger-core/src/Cardano/Ledger/Plutus/ExUnits.hs:185-190`,
    * cardano-ledger f649f975): the price of the units, rounded up.
    */
  def txscriptfee(prices: Prices, units: xyz.kd5ujc.scarab.plutus.ExUnits): Coin =
    Coin(
      (Rational(units.mem) * prices.prMem.rational + Rational(
        units.steps
      ) * prices.prSteps.rational).ceil
    )

  /** `alonzoMinFeeTx` (`Alonzo/Tx.hs:445-458`, cardano-ledger f649f975; `:498-508` at 9df9f98c,
    * where the units are `getTotalExUnits`): the fee per byte of the transaction's size, the fixed
    * fee, and the price of its units.
    */
  def alonzoMinFeeTx[E <: EraTag, L <: TxLevel](pp: PParams[E], tx: Tx[L, E])(using
      t: AlonzoEraTx[E],
      l: t.bodyOf.Level[L],
      p: AlonzoEraPParams[E]
  ): Coin =
    Coin(
      BigInt(t.sizeTxF(tx)) * EraPParams.ppTxFeePerByteL[E].get(pp).unCoinPerByte.toBigInt +
        EraPParams.ppTxFeeFixedCompactL[E].get(pp).toBigInt +
        txscriptfee(AlonzoEraPParams.ppPricesL[E].get(pp), t.getTotalExUnits[L](tx)).value
    )

/** The instances of each era, the one place that lists the eras having each class. */
object EraTx:
  given babbage: (AlonzoEraTx[Babbage] { val bodyOf: EraTxBody.babbage.type }) =
    xyz.kd5ujc.scarab.ledger.era.babbage.BabbageTx.instance
  given conway: (AlonzoEraTx[Conway] { val bodyOf: EraTxBody.conway.type }) =
    xyz.kd5ujc.scarab.ledger.era.conway.ConwayTx.instance
  given dijkstra: (AlonzoEraTx[Dijkstra] { val bodyOf: EraTxBody.dijkstra.type }) =
    xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraTx.instance
