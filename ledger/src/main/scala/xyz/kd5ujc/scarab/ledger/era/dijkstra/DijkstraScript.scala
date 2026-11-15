// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.dijkstra

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Node}
import xyz.kd5ujc.scarab.ledger.{DecodeField, LedgerDecodeError}
import xyz.kd5ujc.scarab.ledger.dijkstra.V12
import xyz.kd5ujc.scarab.ledger.era.{
  AlonzoEraScript,
  AlonzoScriptRep,
  EraTxError,
  Language,
  Plutus,
  PlutusLanguage
}
import xyz.kd5ujc.scarab.ledger.era.conway.{ConwayPlutusScript, ConwayScript}
import xyz.kd5ujc.scarab.ledger.state.Dijkstra

// Dijkstra's scripts, at cardano-ledger 9df9f98c, the ledger of the Leios testnet's build (the
// Dijkstra Scarab implements, #54): the opaque type is transparent in this file only.

/** Dijkstra's Plutus script: the languages Dijkstra has, V1 to V4 (`data PlutusScript DijkstraEra`,
  * `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Scripts.hs:476-481`, cardano-ledger 9df9f98c).
  */
enum DijkstraPlutusScript:
  case V1(plutus: Plutus[Language.PlutusV1])
  case V2(plutus: Plutus[Language.PlutusV2])
  case V3(plutus: Plutus[Language.PlutusV3])
  case V4(plutus: Plutus[Language.PlutusV4])

  /** The script's language (`plutusLanguage`). */
  def language: xyz.kd5ujc.scarab.plutus.PlutusLanguage = this match
    case V1(_) => summon[PlutusLanguage[Language.PlutusV1]].language
    case V2(_) => summon[PlutusLanguage[Language.PlutusV2]].language
    case V3(_) => summon[PlutusLanguage[Language.PlutusV3]].language
    case V4(_) => summon[PlutusLanguage[Language.PlutusV4]].language

  /** The prefix of its hash (`plutusLanguageTag`). */
  def tag: Byte = this match
    case V1(_) => summon[PlutusLanguage[Language.PlutusV1]].tag
    case V2(_) => summon[PlutusLanguage[Language.PlutusV2]].tag
    case V3(_) => summon[PlutusLanguage[Language.PlutusV3]].tag
    case V4(_) => summon[PlutusLanguage[Language.PlutusV4]].tag

  def binary: Bytes = this match
    case V1(p) => p.binary
    case V2(p) => p.binary
    case V3(p) => p.binary
    case V4(p) => p.binary

object DijkstraPlutusScript:
  given CanEqual[DijkstraPlutusScript, DijkstraPlutusScript] = CanEqual.derived

  /** A Conway Plutus script carried into Dijkstra (`upgradeScript`, `Scripts.hs:423-428`,
    * cardano-ledger 9df9f98c): the same program in the same language.
    */
  def translate(p: ConwayPlutusScript): DijkstraPlutusScript = p match
    case ConwayPlutusScript.V1(s) => V1(s)
    case ConwayPlutusScript.V2(s) => V2(s)
    case ConwayPlutusScript.V3(s) => V3(s)

/** Dijkstra's script: `AlonzoScript DijkstraEra` (`Scripts.hs:420`, cardano-ledger 9df9f98c), a
  * Dijkstra native script or a Dijkstra Plutus script.
  */
opaque type DijkstraScript = AlonzoScriptRep[DijkstraNativeScript, DijkstraPlutusScript]

object DijkstraScript:
  /** Dijkstra's script decoder: `decodeAlonzoScript` at `eraMaxLanguage = PlutusV4`
    * (`eras/alonzo/impl/src/Cardano/Ledger/Alonzo/Scripts.hs:728-758`, `Scripts.hs:485`,
    * cardano-ledger 9df9f98c) at decoder version 12, as the Leios build's block decoder reads it
    * ([[xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraScript.script]]): `decodeRecordSum` of a native
    * script `[0, script]` or a Plutus script `[1..4, bytes]` of Plutus V1 to V4, its bytes a byte
    * string, chunked from version 12, the program not deserialised.
    */
  def decode(n: Node): Either[EraTxError, DijkstraScript] = read(n).leftMap(EraTxError.Decode(_))

  private[dijkstra] def read(n: Node): Either[LedgerDecodeError, DijkstraScript] =
    def plutus(make: Bytes => DijkstraPlutusScript)(xs: Vector[Node]) =
      V12
        .bytes(xs(1), DecodeField.PlutusScript)
        .map(b => AlonzoScriptRep.PlutusScript(make(b)): DijkstraScript)
    V12.sum[DijkstraScript](n, DecodeField.Script) {
      case t if t == 0 =>
        (2, xs => DijkstraNativeScript.read(xs(1)).map(AlonzoScriptRep.NativeScript(_)))
      case t if t == 1 => (2, plutus(b => DijkstraPlutusScript.V1(Plutus(b))))
      case t if t == 2 => (2, plutus(b => DijkstraPlutusScript.V2(Plutus(b))))
      case t if t == 3 => (2, plutus(b => DijkstraPlutusScript.V3(Plutus(b))))
      case t if t == 4 => (2, plutus(b => DijkstraPlutusScript.V4(Plutus(b))))
    }

  /** A Conway script carried into Dijkstra (`upgradeScript`, `Scripts.hs:423-428`, cardano-ledger
    * 9df9f98c): a native script rebuilt in Dijkstra's encoding
    * ([[DijkstraNativeScript.translate]]), a Plutus script in the same language.
    */
  def translate(s: ConwayScript): Either[EraTxError, DijkstraScript] = s.rep match
    case AlonzoScriptRep.NativeScript(native) =>
      DijkstraNativeScript.translate(native).map(AlonzoScriptRep.NativeScript(_))
    case AlonzoScriptRep.PlutusScript(p) =>
      Right(AlonzoScriptRep.PlutusScript(DijkstraPlutusScript.translate(p)))

  extension (s: DijkstraScript)
    /** The shared representation, read only. */
    def rep: AlonzoScriptRep[DijkstraNativeScript, DijkstraPlutusScript] = s

  given CanEqual[DijkstraScript, DijkstraScript] = CanEqual.derived

  /** `AlonzoEraScript DijkstraEra` (`Scripts.hs:475-534`, cardano-ledger 9df9f98c), with the
    * native-script readers of `ShelleyEraScript` and `AllegraEraScript` (`Scripts.hs:536-554`). The
    * purposes, and `ConwayEraScript` and `DijkstraEraScript` with them, are not here yet (#85); the
    * guard's reader is on the native script ([[DijkstraNativeScript.getRequireGuard]]).
    */
  val instance: AlonzoEraScript[Dijkstra] = new AlonzoEraScript[Dijkstra]:
    override def scriptPrefixTag(script: DijkstraScript): Byte = script match
      case AlonzoScriptRep.NativeScript(_) => 0
      case AlonzoScriptRep.PlutusScript(p) => p.tag
    override def originalBytes(script: DijkstraScript): Bytes = script match
      case AlonzoScriptRep.NativeScript(t) => t.raw
      case AlonzoScriptRep.PlutusScript(p) => p.binary
    override def getNativeScript(script: DijkstraScript): Option[DijkstraNativeScript] =
      script match
        case AlonzoScriptRep.NativeScript(t) => Some(t)
        case AlonzoScriptRep.PlutusScript(_) => None
    override def fromNativeScript(native: DijkstraNativeScript): DijkstraScript =
      AlonzoScriptRep.NativeScript(native)
    override def getRequireSignature(native: DijkstraNativeScript): Option[Bytes] =
      native.getRequireSignature
    override def getRequireAllOf(
        native: DijkstraNativeScript
    ): Option[Vector[DijkstraNativeScript]] =
      native.getRequireAllOf
    override def getRequireAnyOf(
        native: DijkstraNativeScript
    ): Option[Vector[DijkstraNativeScript]] =
      native.getRequireAnyOf
    override def getRequireMOf(
        native: DijkstraNativeScript
    ): Option[(BigInt, Vector[DijkstraNativeScript])] = native.getRequireMOf
    override def getTimeStart(native: DijkstraNativeScript): Option[BigInt] = native.getTimeStart
    override def getTimeExpire(native: DijkstraNativeScript): Option[BigInt] = native.getTimeExpire
    override def eraMaxLanguage: xyz.kd5ujc.scarab.plutus.PlutusLanguage =
      xyz.kd5ujc.scarab.plutus.PlutusLanguage.V4
    override def toPlutusScript(script: DijkstraScript): Option[DijkstraPlutusScript] =
      script match
        case AlonzoScriptRep.PlutusScript(p) => Some(p)
        case AlonzoScriptRep.NativeScript(_) => None
    override def fromPlutusScript(plutus: DijkstraPlutusScript): DijkstraScript =
      AlonzoScriptRep.PlutusScript(plutus)
