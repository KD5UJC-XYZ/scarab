// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import xyz.kd5ujc.scarab.ledger.state.{Conway, EraTag}

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
  * body, witnesses and auxiliary data, at the levels its body has. The superclasses of other
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

  /** `sizeTxForFeeCalculation` (`Core.hs:203-210`, cardano-ledger f649f975): the bytes of the body,
    * the witnesses and the auxiliary data (one byte for none) as they arrived, and one for the
    * transaction's own list.
    */
  def sizeTxForFeeCalculation[L <: TxLevel](tx: Tx[L, E])(using bodyOf.Level[L]): Long

/** The phase-two flag of a transaction, from Alonzo (`AlonzoEraTx`,
  * `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/Tx.hs:266-281`, cardano-ledger f649f975), of a
  * top-level transaction only.
  */
trait AlonzoEraTx[E <: EraTag] extends EraTx[E]:
  /** `isValidTxL`. */
  def isValid(tx: Tx[TxLevel.Top, E]): IsPhase2Valid

/** The instances of each era, the one place that lists the eras having each class. */
object EraTx:
  given conway: AlonzoEraTx[Conway] = xyz.kd5ujc.scarab.ledger.era.conway.ConwayTx.instance
