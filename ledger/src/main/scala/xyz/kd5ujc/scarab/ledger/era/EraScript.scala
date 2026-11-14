// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger.{NativeScript as FlatNativeScript, ScriptKind}
import xyz.kd5ujc.scarab.ledger.state.{Allegra, Babbage, Conway, EraTag, Mary}

/** The scripts of era `E` (`EraScript`, cardano-ledger f649f975
  * `libs/cardano-ledger-core/src/Cardano/Ledger/Core.hs:557-597`): the native scripts among them,
  * and the bytes and prefix their hash is over. A script of the previous era is carried in by the
  * era's own translation (the reference's `upgradeScript`), not by this class.
  */
trait EraScript[E <: EraTag]:
  /** The prefix of the hash (`scriptPrefixTag`): `0` for a native script, the language's tag for a
    * Plutus script (`alonzoScriptPrefixTag`,
    * `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/Scripts.hs:537-543`, cardano-ledger f649f975).
    */
  def scriptPrefixTag(script: Script[E]): Byte

  /** The bytes the hash is over (`originalBytes`): a native script's encoding, a Plutus script's
    * bytes.
    */
  def originalBytes(script: Script[E]): Bytes

  def getNativeScript(script: Script[E]): Option[NativeScript[E]]
  def fromNativeScript(native: NativeScript[E]): Script[E]

  /** `hashScript` (`Core.hs:603-608`, cardano-ledger f649f975): blake2b-224 of the prefix and the
    * bytes.
    */
  final def hashScript(script: Script[E]): Bytes =
    Blake2b.hash224.hash(Bytes(Vector(scriptPrefixTag(script))) ++ originalBytes(script))

/** The native scripts of Shelley's language, by constructor (`ShelleyEraScript`,
  * `eras/shelley/impl/src/Cardano/Ledger/Shelley/Scripts.hs:107-118`, cardano-ledger f649f975). The
  * reference's builders (`mkRequireSignature` and the others) make a script and its encoding; they
  * are not here, as no native-script encoder is.
  */
trait ShelleyEraScript[E <: EraTag] extends EraScript[E]:
  def getRequireSignature(native: NativeScript[E]): Option[Bytes]
  def getRequireAllOf(native: NativeScript[E]): Option[Vector[NativeScript[E]]]
  def getRequireAnyOf(native: NativeScript[E]): Option[Vector[NativeScript[E]]]

  /** The required count is an `Int` in the reference, a 64-bit signed value the decoder bounds
    * ([[FlatNativeScript.DecodeError.RequiredOutOfRange]]).
    */
  def getRequireMOf(native: NativeScript[E]): Option[(BigInt, Vector[NativeScript[E]])]

/** The time locks from Allegra (`AllegraEraScript`,
  * `eras/allegra/impl/src/Cardano/Ledger/Allegra/Scripts.hs:189-195`, cardano-ledger f649f975).
  */
trait AllegraEraScript[E <: EraTag] extends ShelleyEraScript[E]:
  def getTimeStart(native: NativeScript[E]): Option[BigInt]
  def getTimeExpire(native: NativeScript[E]): Option[BigInt]

/** The Plutus scripts from Alonzo (`AlonzoEraScript`,
  * `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/Scripts.hs:139-188`, cardano-ledger f649f975). Its
  * Plutus purposes come with the purpose family.
  */
trait AlonzoEraScript[E <: EraTag] extends AllegraEraScript[E]:
  /** The newest language of the era (`eraMaxLanguage`). */
  def eraMaxLanguage: ScriptKind

  def toPlutusScript(script: Script[E]): Option[PlutusScript[E]]
  def fromPlutusScript(plutus: PlutusScript[E]): Script[E]

/** The native-script readers of every era whose native script is a [[Timelock]], written once over
  * the representation as the reference writes them once over `Timelock era`
  * (`getRequireSignatureTimelock` to `getTimeExpireTimelock`,
  * `eras/allegra/impl/src/Cardano/Ledger/Allegra/Scripts.hs:425-462`, cardano-ledger f649f975). An
  * era's instance names the two conversions between its native script and the representation, which
  * only its own file can write.
  */
abstract class TimelockScripts[E <: EraTag] extends AllegraEraScript[E]:
  protected def timelock(native: NativeScript[E]): Timelock
  protected def ofTimelock(t: Timelock): NativeScript[E]

  final override def getRequireSignature(native: NativeScript[E]): Option[Bytes] =
    timelock(native).script match
      case FlatNativeScript.Sig(h) => Some(h)
      case _                       => None
  final override def getRequireAllOf(native: NativeScript[E]): Option[Vector[NativeScript[E]]] =
    val t = timelock(native)
    t.script match
      case FlatNativeScript.AllOf(_) => Some(t.children.map(ofTimelock))
      case _                         => None
  final override def getRequireAnyOf(native: NativeScript[E]): Option[Vector[NativeScript[E]]] =
    val t = timelock(native)
    t.script match
      case FlatNativeScript.AnyOf(_) => Some(t.children.map(ofTimelock))
      case _                         => None
  final override def getRequireMOf(
      native: NativeScript[E]
  ): Option[(BigInt, Vector[NativeScript[E]])] =
    val t = timelock(native)
    t.script match
      case FlatNativeScript.NOfK(n, _) => Some((n, t.children.map(ofTimelock)))
      case _                           => None
  final override def getTimeStart(native: NativeScript[E]): Option[BigInt] =
    timelock(native).script match
      case FlatNativeScript.InvalidBefore(slot) => Some(slot)
      case _                                    => None
  final override def getTimeExpire(native: NativeScript[E]): Option[BigInt] =
    timelock(native).script match
      case FlatNativeScript.InvalidHereafter(slot) => Some(slot)
      case _                                       => None

/** The instances of each era, the one place that lists the eras having each script class. */
object EraScript:
  given allegra: AllegraEraScript[Allegra] =
    xyz.kd5ujc.scarab.ledger.era.allegra.AllegraTimelock.instance
  given mary: AllegraEraScript[Mary] = xyz.kd5ujc.scarab.ledger.era.mary.MaryTimelock.instance
  given babbage: AlonzoEraScript[Babbage] =
    xyz.kd5ujc.scarab.ledger.era.babbage.BabbageScript.instance
  given conway: AlonzoEraScript[Conway] = xyz.kd5ujc.scarab.ledger.era.conway.ConwayScript.instance
