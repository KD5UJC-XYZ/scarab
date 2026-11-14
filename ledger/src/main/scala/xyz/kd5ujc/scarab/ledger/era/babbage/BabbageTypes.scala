// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.babbage

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Node}
import xyz.kd5ujc.scarab.ledger.{Address, ByteStrings, Datum, LedgerDecodeError, ScriptKind}
import xyz.kd5ujc.scarab.ledger.era.{
  AlonzoEraScript,
  AlonzoScriptRep,
  BabbageEraTxOut,
  BabbageTxOutRep,
  EraTxError,
  Language,
  MultiAssetValue,
  NativeScriptRep,
  Plutus,
  Timelock,
  TimelockScripts,
  Val
}
import xyz.kd5ujc.scarab.ledger.state.Babbage

// Babbage's types: the opaque types are transparent in this file only, so only this file makes a
// value of Babbage.

/** Babbage's native script: `Timelock BabbageEra` (cardano-ledger f649f975
  * `eras/babbage/impl/src/Cardano/Ledger/Babbage/Scripts.hs:42`).
  */
opaque type BabbageTimelock = Timelock

object BabbageTimelock:
  /** Babbage's native-script decoder: the time-lock language. */
  def decode(n: Node)(using ByteStrings): Either[EraTxError, BabbageTimelock] =
    read(n).leftMap(EraTxError.Decode(_))

  private[babbage] def read(
      n: Node
  )(using ByteStrings): Either[LedgerDecodeError, BabbageTimelock] =
    NativeScriptRep
      .decode[xyz.kd5ujc.scarab.ledger.era.NativeForm.Timelock](n)
      .leftMap(LedgerDecodeError.NativeScript(_))

  extension (t: BabbageTimelock)
    /** The shared representation, read only: the translation into Conway reads it. */
    def rep: Timelock = t

  given CanEqual[BabbageTimelock, BabbageTimelock] = CanEqual.derived

/** Babbage's Plutus script: the languages Babbage has, V1 and V2 (`data PlutusScript BabbageEra`,
  * `Babbage/Scripts.hs:57-59`, cardano-ledger f649f975).
  */
enum BabbagePlutusScript:
  case V1(plutus: Plutus[Language.PlutusV1])
  case V2(plutus: Plutus[Language.PlutusV2])

  def kind: ScriptKind = this match
    case V1(_) => ScriptKind.PlutusV1
    case V2(_) => ScriptKind.PlutusV2

  def binary: Bytes = this match
    case V1(p) => p.binary
    case V2(p) => p.binary

object BabbagePlutusScript:
  given CanEqual[BabbagePlutusScript, BabbagePlutusScript] = CanEqual.derived

  /** The Babbage script of `kind`, if Babbage has the language. */
  private[babbage] def of(kind: ScriptKind): Option[Bytes => BabbagePlutusScript] = kind match
    case ScriptKind.PlutusV1                     => Some(b => V1(Plutus(b)))
    case ScriptKind.PlutusV2                     => Some(b => V2(Plutus(b)))
    case ScriptKind.PlutusV3 | ScriptKind.Native => None

/** Babbage's script: `AlonzoScript BabbageEra` (`Babbage/Scripts.hs:41`, cardano-ledger f649f975),
  * a Babbage native script or a Babbage Plutus script.
  */
opaque type BabbageScript = AlonzoScriptRep[BabbageTimelock, BabbagePlutusScript]

object BabbageScript:
  /** Babbage's script decoder (`decodeAlonzoScript` at `eraMaxLanguage = PlutusV2`,
    * `Babbage/Scripts.hs:63`, cardano-ledger f649f975).
    */
  def decode(n: Node)(using ByteStrings): Either[EraTxError, BabbageScript] =
    read(n).leftMap(EraTxError.Decode(_))

  private[babbage] def read(n: Node)(using ByteStrings): Either[LedgerDecodeError, BabbageScript] =
    AlonzoScriptRep.decode(n, ScriptKind.PlutusV2)(BabbageTimelock.read(_), BabbagePlutusScript.of)

  extension (s: BabbageScript)
    /** The shared representation, read only: the translation into Conway reads it. */
    def rep: AlonzoScriptRep[BabbageTimelock, BabbagePlutusScript] = s

  given CanEqual[BabbageScript, BabbageScript] = CanEqual.derived

  val instance: AlonzoEraScript[Babbage] = new TimelockScripts[Babbage]
    with AlonzoEraScript[Babbage]:
    override protected def timelock(native: BabbageTimelock): Timelock = native
    override protected def ofTimelock(t: Timelock): BabbageTimelock = t
    override def scriptPrefixTag(script: BabbageScript): Byte = script match
      case AlonzoScriptRep.NativeScript(_) => 0
      case AlonzoScriptRep.PlutusScript(p) => p.kind.tag.toByte
    override def originalBytes(script: BabbageScript): Bytes = script match
      case AlonzoScriptRep.NativeScript(t) => t.raw
      case AlonzoScriptRep.PlutusScript(p) => p.binary
    override def getNativeScript(script: BabbageScript): Option[BabbageTimelock] = script match
      case AlonzoScriptRep.NativeScript(t) => Some(t)
      case AlonzoScriptRep.PlutusScript(_) => None
    override def fromNativeScript(native: BabbageTimelock): BabbageScript =
      AlonzoScriptRep.NativeScript(native)
    override def eraMaxLanguage: ScriptKind = ScriptKind.PlutusV2
    override def toPlutusScript(script: BabbageScript): Option[BabbagePlutusScript] = script match
      case AlonzoScriptRep.PlutusScript(p) => Some(p)
      case AlonzoScriptRep.NativeScript(_) => None
    override def fromPlutusScript(plutus: BabbagePlutusScript): BabbageScript =
      AlonzoScriptRep.PlutusScript(plutus)

/** A Babbage output: `BabbageTxOut BabbageEra`
  * (`eras/babbage/impl/src/Cardano/Ledger/Babbage/TxOut.hs:133-158`, cardano-ledger f649f975), its
  * reference script a Babbage script.
  */
opaque type BabbageTxOut = BabbageTxOutRep[BabbageScript]

object BabbageTxOut:
  /** A stored output read as a Babbage output (`ejectHardForkTxOut` of the current era,
    * ouroboros-consensus 82ecba329 `Cardano/Ledger.hs:137-162`): its reference script read as a
    * Babbage script.
    */
  private[ledger] def fromStored(
      out: xyz.kd5ujc.scarab.ledger.TxOut
  ): Either[EraTxError, BabbageTxOut] =
    BabbageTxOutRep.fromStored(out)(n => BabbageScript.read(n)).leftMap(EraTxError.Decode(_))

  extension (out: BabbageTxOut)
    /** The shared representation, read only: the translation into the next era reads it. */
    def rep: BabbageTxOutRep[BabbageScript] = out

  given CanEqual[BabbageTxOut, BabbageTxOut] = CanEqual.derived

  val instance: BabbageEraTxOut[Babbage] = new BabbageEraTxOut[Babbage]:
    override def scripts: AlonzoEraScript[Babbage] = BabbageScript.instance
    override def valueOps: Val[MultiAssetValue] = summon[Val[MultiAssetValue]]
    override def address(out: BabbageTxOut): Address = out.address
    override def value(out: BabbageTxOut): MultiAssetValue = out.value
    override def raw(out: BabbageTxOut): Bytes = out.raw
    override def dataHash(out: BabbageTxOut): Option[Bytes] = out.datum match
      case Datum.Hash(h)                   => Some(h)
      case Datum.NoDatum | Datum.Inline(_) => None
    override def datum(out: BabbageTxOut): Datum = out.datum
    override def data(out: BabbageTxOut): Option[Bytes] = out.datum match
      case Datum.Inline(d)               => Some(d)
      case Datum.NoDatum | Datum.Hash(_) => None
    override def referenceScript(out: BabbageTxOut): Option[BabbageScript] = out.scriptRef
