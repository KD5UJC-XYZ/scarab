// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.mary

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Node}
import xyz.kd5ujc.scarab.ledger.{Address, ByteStrings, Datum, LedgerDecodeError}
import xyz.kd5ujc.scarab.ledger.era.{
  AllegraEraScript,
  EraField,
  EraTxError,
  EraTxOut,
  MultiAssetValue,
  NativeScriptRep,
  ShelleyTxOutRep,
  Timelock,
  TimelockScripts,
  Val
}
import xyz.kd5ujc.scarab.ledger.era.allegra.{AllegraTimelock, AllegraTxOut}
import xyz.kd5ujc.scarab.ledger.state.Mary

// Mary's types: the opaque types are transparent in this file only, so only this file makes a
// value of Mary.

/** A Mary output: `ShelleyTxOut MaryEra` (cardano-ledger f649f975
  * `eras/mary/impl/src/Cardano/Ledger/Mary/TxOut.hs:20-21`), an address and a multi-asset value,
  * the representation Allegra's has with another value.
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
    yield ShelleyTxOutRep(out.raw, out.address, MultiAssetValue.of(out.value), out.valueSize)

  /** An Allegra output carried into Mary (`upgradeTxOut`): the same address and bytes, its lovelace
    * as a multi-asset value.
    */
  def translate(out: AllegraTxOut): MaryTxOut =
    val r = out.rep
    ShelleyTxOutRep(r.raw, r.address, r.value.toMultiAsset, r.valueSize)

  given CanEqual[MaryTxOut, MaryTxOut] = CanEqual.derived

  val instance: EraTxOut[Mary] = new EraTxOut[Mary]:
    override def valueOps: Val[MultiAssetValue] = summon[Val[MultiAssetValue]]
    override def address(out: MaryTxOut): Address = out.address
    override def value(out: MaryTxOut): MultiAssetValue = out.value
    override def raw(out: MaryTxOut): Bytes = out.raw

/** Mary's native script and script: `Timelock MaryEra`
  * (`eras/mary/impl/src/Cardano/Ledger/Mary/Scripts.hs:21-22`).
  */
opaque type MaryTimelock = Timelock

object MaryTimelock:
  /** Mary's native-script decoder: the time-lock language. */
  def decode(n: Node)(using ByteStrings): Either[EraTxError, MaryTimelock] =
    NativeScriptRep
      .decode[xyz.kd5ujc.scarab.ledger.era.NativeForm.Timelock](n)
      .leftMap(e => EraTxError.Decode(LedgerDecodeError.NativeScript(e)))

  /** An Allegra script carried into Mary with its bytes (`translateTimelock`,
    * `eras/allegra/impl/src/Cardano/Ledger/Allegra/Scripts.hs:202-217`).
    */
  def translate(t: AllegraTimelock): MaryTimelock = t.rep

  given CanEqual[MaryTimelock, MaryTimelock] = CanEqual.derived

  val instance: AllegraEraScript[Mary] = new TimelockScripts[Mary]:
    override protected def timelock(native: MaryTimelock): Timelock = native
    override protected def ofTimelock(t: Timelock): MaryTimelock = t
    override def scriptPrefixTag(script: MaryTimelock): Byte = 0
    override def originalBytes(script: MaryTimelock): Bytes = script.raw
    override def getNativeScript(script: MaryTimelock): Option[MaryTimelock] = Some(script)
    override def fromNativeScript(native: MaryTimelock): MaryTimelock = native
