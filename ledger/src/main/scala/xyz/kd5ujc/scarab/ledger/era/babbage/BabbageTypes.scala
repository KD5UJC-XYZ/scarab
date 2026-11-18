// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.babbage

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Node}
import xyz.kd5ujc.scarab.ledger.{Address, ByteStrings, LedgerDecodeError, ScriptKind}
import xyz.kd5ujc.scarab.ledger.era.{
  AlonzoEraScript,
  AlonzoScriptRep,
  BabbageEraTxOut,
  BabbageTxOutRep,
  BabbageTxOuts,
  Data,
  Datum,
  EraTxError,
  Language,
  MultiAssetValue,
  NativeScriptRep,
  Plutus,
  PlutusLanguage,
  Timelock,
  TimelockScripts,
  Val
}
import xyz.kd5ujc.scarab.ledger.state.Babbage
import xyz.kd5ujc.scarab.plutus.PlutusData

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
    case ScriptKind.PlutusV1                                           => Some(b => V1(Plutus(b)))
    case ScriptKind.PlutusV2                                           => Some(b => V2(Plutus(b)))
    case ScriptKind.PlutusV3 | ScriptKind.PlutusV4 | ScriptKind.Native => None

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
    override def eraMaxLanguage: xyz.kd5ujc.scarab.plutus.PlutusLanguage =
      xyz.kd5ujc.scarab.plutus.PlutusLanguage.V2
    override def toPlutusScript(script: BabbageScript): Option[BabbagePlutusScript] = script match
      case AlonzoScriptRep.PlutusScript(p) => Some(p)
      case AlonzoScriptRep.NativeScript(_) => None
    override def fromPlutusScript(plutus: BabbagePlutusScript): BabbageScript =
      AlonzoScriptRep.PlutusScript(plutus)
    // `withPlutusScript`, `Babbage/Scripts.hs:71-72`, cardano-ledger f649f975
    override def withPlutusScript[A](plutus: BabbagePlutusScript)(
        f: [L <: Language] => (Plutus[L], PlutusLanguage[L]) => A
    ): A = plutus match
      case BabbagePlutusScript.V1(p) => f(p, PlutusLanguage.v1)
      case BabbagePlutusScript.V2(p) => f(p, PlutusLanguage.v2)

/** A Babbage output: `BabbageTxOut BabbageEra`
  * (`eras/babbage/impl/src/Cardano/Ledger/Babbage/TxOut.hs:133-158`, cardano-ledger f649f975), its
  * reference script a Babbage script.
  */
opaque type BabbageTxOut = BabbageTxOutRep[BabbageScript]

object BabbageTxOut:
  /** A stored output read as a Babbage output (`ejectHardForkTxOut` of the current era,
    * ouroboros-consensus 82ecba329 `Cardano/Ledger.hs:137-162`): its inline datum taken unread, as
    * the reference's `MemPack` of `BinaryData` takes it, and its reference script read as a Babbage
    * script.
    */
  private[ledger] def fromStored(
      out: xyz.kd5ujc.scarab.ledger.TxOut
  ): Either[EraTxError, BabbageTxOut] =
    BabbageTxOutRep
      .fromStored(out)(n => BabbageScript.read(n))
      .leftMap(EraTxError.Decode(_))
      // the output written back is the one read, so `toStored` is its inverse
      .flatMap(o => Either.cond(toStored(o) == out, o, EraTxError.StoredOutputDiffers))

  /** The output in the form the store keeps, the inverse of [[fromStored]] (which refuses a stored
    * output this does not give back): its bytes, fields and the value's size as they arrived, and
    * its reference script as the flat output keeps one, by its hash, with the tree of a native
    * script and without a Plutus script's bytes.
    */
  private[ledger] def toStored(out: BabbageTxOut): xyz.kd5ujc.scarab.ledger.TxOut =
    xyz.kd5ujc.scarab.ledger.TxOut(
      out.raw,
      out.address,
      out.value.stored,
      Datum.toStored(out.datum),
      out.scriptRef.map(storedScript),
      out.valueSize
    )

  private def storedScript(s: BabbageScript): xyz.kd5ujc.scarab.ledger.Script =
    val hash = BabbageScript.instance.hashScript(s)
    s match
      case AlonzoScriptRep.NativeScript(t) =>
        xyz.kd5ujc.scarab.ledger.Script(ScriptKind.Native, hash, Some(t.script))
      case AlonzoScriptRep.PlutusScript(p) => xyz.kd5ujc.scarab.ledger.Script(p.kind, hash, None)

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
    override def dataHash(out: BabbageTxOut): Option[Bytes] = BabbageTxOuts.dataHash(out.datum)
    override def datum(out: BabbageTxOut): Datum = out.datum
    override def data(out: BabbageTxOut): Either[PlutusData.DecodeError, Option[Data]] =
      BabbageTxOuts.data(out.datum)
    override def referenceScript(out: BabbageTxOut): Option[BabbageScript] = out.scriptRef
