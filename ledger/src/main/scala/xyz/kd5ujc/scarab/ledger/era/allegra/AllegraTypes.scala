// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.allegra

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{Address, Datum}
import xyz.kd5ujc.scarab.ledger.era.{AdaValue, EraField, EraTxError, EraTxOut, ShelleyTxOutRep, Val}
import xyz.kd5ujc.scarab.ledger.state.Allegra

/** An Allegra output: `ShelleyTxOut AllegraEra` (cardano-ledger f649f975
  * `eras/allegra/impl/src/Cardano/Ledger/Allegra/TxOut.hs:18-19`), an address and lovelace. Only
  * this file makes one.
  */
opaque type AllegraTxOut = ShelleyTxOutRep[AdaValue]

object AllegraTxOut:
  /** A stored output read as an Allegra output (the reference's `indexedUnpackM` reads it in the
    * era of the ledger state): refused if it holds what Allegra's outputs cannot, native assets, a
    * datum or a reference script.
    */
  private[ledger] def fromStored(
      out: xyz.kd5ujc.scarab.ledger.TxOut
  ): Either[EraTxError, AllegraTxOut] =
    for
      _ <- Either.cond(out.value.assets.isEmpty, (), EraTxError.NotInEra(EraField.MultiAsset))
      _ <- Either.cond(out.datum == Datum.NoDatum, (), EraTxError.NotInEra(EraField.Datum))
      _ <- Either.cond(out.scriptRef.isEmpty, (), EraTxError.NotInEra(EraField.ReferenceScript))
      ada <- AdaValue.of(out.value.coin).toRight(EraTxError.NotInEra(EraField.NegativeValue))
    yield ShelleyTxOutRep(out.raw, out.address, ada)

  extension (out: AllegraTxOut)
    /** The shared representation, read only: the translation into Mary reads it. */
    def rep: ShelleyTxOutRep[AdaValue] = out

  val instance: EraTxOut[Allegra] = new EraTxOut[Allegra]:
    type EraValue = AdaValue
    override def valueOps: Val[AdaValue] = summon[Val[AdaValue]]
    override def address(out: AllegraTxOut): Address = out.address
    override def value(out: AllegraTxOut): AdaValue = out.value
    override def raw(out: AllegraTxOut): Bytes = out.raw
