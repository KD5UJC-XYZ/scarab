// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

/** The types that moved to `uplc`, the module of Scarab's own evaluator that has no Scalus on its
  * class path (ADR 0027). They keep their names here so no caller of the `plutus` package changes.
  */
export xyz.kd5ujc.scarab.uplc.{ExUnits, ParamNames, PlutusData, PlutusLanguage}
