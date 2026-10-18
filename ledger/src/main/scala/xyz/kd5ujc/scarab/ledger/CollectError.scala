// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.plutus.PlutusLanguage

/** `CollectError` (`Alonzo/Plutus/Context.hs:357-383`): a required script cannot be prepared. A
  * missing redeemer names the required purpose; an invalid pointer inside a context is instead
  * [[TranslationError.RedeemerPointerPointsToNothing]].
  */
enum CollectError:
  case NoRedeemer(purpose: Purpose)
  case NoWitness(script: Bytes)
  case NoCostModel(language: PlutusLanguage)
  case BadTranslation(error: TranslationError)

  def message: String = this match
    case NoRedeemer(purpose)   => s"no redeemer for $purpose"
    case NoWitness(script)     => s"script ${script.hex} is missing"
    case NoCostModel(language) => s"no cost model for $language"
    case BadTranslation(error) => error.message
