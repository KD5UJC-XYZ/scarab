// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.allegra

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Node}
import xyz.kd5ujc.scarab.ledger.{Address, ByteStrings, Datum, LedgerDecodeError}
import xyz.kd5ujc.scarab.ledger.era.{
  AdaValue,
  AllegraEraScript,
  EraField,
  EraTxError,
  EraTxOut,
  NativeScriptRep,
  ShelleyTxOutRep,
  Timelock,
  TimelockScripts,
  Val
}
import xyz.kd5ujc.scarab.ledger.state.Allegra

// Allegra's types: the opaque types are transparent in this file only, so only this file makes a
// value of Allegra.

/** An Allegra output: `ShelleyTxOut AllegraEra` (cardano-ledger f649f975
  * `eras/allegra/impl/src/Cardano/Ledger/Allegra/TxOut.hs:18-19`), an address and lovelace.
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
    yield ShelleyTxOutRep(out.raw, out.address, ada, out.valueSize)

  extension (out: AllegraTxOut)
    /** The shared representation, read only: the translation into Mary reads it. */
    def rep: ShelleyTxOutRep[AdaValue] = out

  given CanEqual[AllegraTxOut, AllegraTxOut] = CanEqual.derived

  val instance: EraTxOut[Allegra] = new EraTxOut[Allegra]:
    override def valueOps: Val[AdaValue] = summon[Val[AdaValue]]
    override def address(out: AllegraTxOut): Address = out.address
    override def value(out: AllegraTxOut): AdaValue = out.value
    override def raw(out: AllegraTxOut): Bytes = out.raw

/** Allegra's native script and script: `Timelock AllegraEra`, both its `NativeScript` and its
  * `Script` (`eras/allegra/impl/src/Cardano/Ledger/Allegra/Scripts.hs:331-332`, cardano-ledger
  * f649f975).
  */
opaque type AllegraTimelock = Timelock

object AllegraTimelock:
  /** Allegra's native-script decoder: the time-lock language. */
  def decode(n: Node)(using ByteStrings): Either[EraTxError, AllegraTimelock] =
    NativeScriptRep
      .decode[xyz.kd5ujc.scarab.ledger.era.NativeForm.Timelock](n)
      .leftMap(e => EraTxError.Decode(LedgerDecodeError.NativeScript(e)))

  extension (t: AllegraTimelock)
    /** The shared representation, read only: the translation into Mary reads it. */
    def rep: Timelock = t

  given CanEqual[AllegraTimelock, AllegraTimelock] = CanEqual.derived

  val instance: AllegraEraScript[Allegra] = new TimelockScripts[Allegra]:
    override protected def timelock(native: AllegraTimelock): Timelock = native
    override protected def ofTimelock(t: Timelock): AllegraTimelock = t
    override def scriptPrefixTag(script: AllegraTimelock): Byte = 0
    override def originalBytes(script: AllegraTimelock): Bytes = script.raw
    override def getNativeScript(script: AllegraTimelock): Option[AllegraTimelock] = Some(script)
    override def fromNativeScript(native: AllegraTimelock): AllegraTimelock = native
