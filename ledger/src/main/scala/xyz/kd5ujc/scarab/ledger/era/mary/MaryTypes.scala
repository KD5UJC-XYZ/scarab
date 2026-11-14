// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.mary

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{Address, Datum}
import xyz.kd5ujc.scarab.ledger.era.{
  EraField,
  EraTxError,
  EraTxOut,
  MultiAssetValue,
  ShelleyTxOutRep,
  Val
}
import xyz.kd5ujc.scarab.ledger.era.allegra.AllegraTxOut
import xyz.kd5ujc.scarab.ledger.state.Mary

/** A Mary output: `ShelleyTxOut MaryEra` (cardano-ledger f649f975
  * `eras/mary/impl/src/Cardano/Ledger/Mary/TxOut.hs:20-21`), an address and a multi-asset value,
  * the representation Allegra's has with another value. Only this file makes one.
  */
opaque type MaryTxOut = ShelleyTxOutRep[MultiAssetValue]

object MaryTxOut:
  /** A stored output read as a Mary output: refused if it holds a datum or a reference script. */
  private[ledger] def fromStored(
      out: xyz.kd5ujc.scarab.ledger.TxOut
  ): Either[EraTxError, MaryTxOut] =
    for
      _ <- Either.cond(out.datum == Datum.NoDatum, (), EraTxError.NotInEra(EraField.Datum))
      _ <- Either.cond(out.scriptRef.isEmpty, (), EraTxError.NotInEra(EraField.ReferenceScript))
    yield ShelleyTxOutRep(out.raw, out.address, MultiAssetValue.of(out.value))

  /** An Allegra output carried into Mary (`TranslateEra MaryEra TxOut`): the same address and
    * bytes, its lovelace as a multi-asset value.
    */
  def translate(out: AllegraTxOut): MaryTxOut =
    val r = out.rep
    ShelleyTxOutRep(r.raw, r.address, r.value.toMultiAsset)

  val instance: EraTxOut[Mary] = new EraTxOut[Mary]:
    type EraValue = MultiAssetValue
    override def valueOps: Val[MultiAssetValue] = summon[Val[MultiAssetValue]]
    override def address(out: MaryTxOut): Address = out.address
    override def value(out: MaryTxOut): MultiAssetValue = out.value
    override def raw(out: MaryTxOut): Bytes = out.raw
