// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import xyz.kd5ujc.scarab.codec.Bytes

/** A Plutus language (`data Language`, cardano-ledger f649f975
  * `libs/cardano-ledger-core/src/Cardano/Ledger/Plutus/Language.hs`), as a type, so a script's
  * language is in its type.
  */
sealed trait Language

object Language:
  sealed abstract class PlutusV1 extends Language
  sealed abstract class PlutusV2 extends Language
  sealed abstract class PlutusV3 extends Language

  /** Dijkstra's language (`PlutusV4`, `Plutus/Language.hs:276`, cardano-ledger f649f975). */
  sealed abstract class PlutusV4 extends Language

/** The language `L` at the value level (`PlutusLanguage l`, `Plutus/Language.hs:452`,
  * cardano-ledger f649f975).
  */
trait PlutusLanguage[L <: Language]:
  /** The language as a value (`plutusLanguage`), the reference's `Language` with its four languages
    * (`Plutus/Language.hs:272-277`, cardano-ledger f649f975).
    */
  def language: xyz.kd5ujc.scarab.plutus.PlutusLanguage

  /** The script's hash prefix (`plutusLanguageTag`, `Plutus/Language.hs:468`, cardano-ledger
    * f649f975): one more than the language's index.
    */
  final def tag: Byte = (language.id + 1).toByte

object PlutusLanguage:
  given v1: PlutusLanguage[Language.PlutusV1] with
    def language: xyz.kd5ujc.scarab.plutus.PlutusLanguage =
      xyz.kd5ujc.scarab.plutus.PlutusLanguage.V1
  given v2: PlutusLanguage[Language.PlutusV2] with
    def language: xyz.kd5ujc.scarab.plutus.PlutusLanguage =
      xyz.kd5ujc.scarab.plutus.PlutusLanguage.V2
  given v3: PlutusLanguage[Language.PlutusV3] with
    def language: xyz.kd5ujc.scarab.plutus.PlutusLanguage =
      xyz.kd5ujc.scarab.plutus.PlutusLanguage.V3
  given v4: PlutusLanguage[Language.PlutusV4] with
    def language: xyz.kd5ujc.scarab.plutus.PlutusLanguage =
      xyz.kd5ujc.scarab.plutus.PlutusLanguage.V4

/** A Plutus script of language `L`: its bytes (`newtype Plutus (l :: Language)`,
  * `Plutus/Language.hs:180-183`, cardano-ledger f649f975). The language is shared by the eras that
  * have it; which eras have which language is each era's own Plutus script type ([[PlutusScript]]).
  */
final case class Plutus[L <: Language](binary: Bytes)

object Plutus:
  given [L <: Language]: CanEqual[Plutus[L], Plutus[L]] = CanEqual.derived
