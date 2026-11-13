// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc

/** Plutus language versions; `id` is the ledger's `Language` enumeration (its CBOR encoding). */
enum PlutusLanguage(val id: Int):
  case V1 extends PlutusLanguage(0)
  case V2 extends PlutusLanguage(1)
  case V3 extends PlutusLanguage(2)
  case V4 extends PlutusLanguage(3)

  /** The plutus ledger language the reference deserialises, admits and evaluates a script of this
    * language as. cardano-ledger 9df9f98c runs a PlutusV4 script through plutus's PlutusV3
    * functions (`PV3.deserialiseScript`, `PV3.evaluateScriptRestricting`, `mkTermToEvaluate
    * P.PlutusV3` and `PV3.mkEvaluationContext`, `libs/cardano-ledger-core/src/Cardano/Ledger/
    * Plutus/Language.hs:582-605` and `CostModels.hs:283-290`); only its script context, the tag of
    * its hash and the protocol version from which it decodes (`guardPlutus`, `Language.hs:643-651`)
    * are its own.
    */
  def evaluatedAs: PlutusLanguage = this match
    case V4    => V3
    case other => other
