// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.ScriptKind

/** A Plutus language (`data Language`, cardano-ledger f649f975
  * `libs/cardano-ledger-core/src/Cardano/Ledger/Plutus/Language.hs`), as a type, so a script's
  * language is in its type.
  */
sealed trait Language

object Language:
  sealed abstract class PlutusV1 extends Language
  sealed abstract class PlutusV2 extends Language
  sealed abstract class PlutusV3 extends Language

/** The language `L` at the value level (`PlutusLanguage l`, `Plutus/Language.hs:452`,
  * cardano-ledger f649f975).
  */
trait PlutusLanguage[L <: Language]:
  /** The language's kind, whose tag is the script's hash prefix (`plutusLanguageTag`,
    * `Plutus/Language.hs:468`, cardano-ledger f649f975).
    */
  def kind: ScriptKind

object PlutusLanguage:
  given v1: PlutusLanguage[Language.PlutusV1] with
    def kind: ScriptKind = ScriptKind.PlutusV1
  given v2: PlutusLanguage[Language.PlutusV2] with
    def kind: ScriptKind = ScriptKind.PlutusV2
  given v3: PlutusLanguage[Language.PlutusV3] with
    def kind: ScriptKind = ScriptKind.PlutusV3

/** A Plutus script of language `L`: its bytes (`newtype Plutus (l :: Language)`,
  * `Plutus/Language.hs:180-183`, cardano-ledger f649f975). The language is shared by the eras that
  * have it; which eras have which language is each era's own Plutus script type ([[PlutusScript]]).
  */
final case class Plutus[L <: Language](binary: Bytes)

object Plutus:
  given [L <: Language]: CanEqual[Plutus[L], Plutus[L]] = CanEqual.derived
