// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.dijkstra

import xyz.kd5ujc.scarab.ledger.era.EraTxError

/** The part of a transaction that a translation through CBOR names when it fails: the labels
  * `translateEraThroughCBOR` is given (`eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/
  * Translation.hs:60-62`, cardano-ledger 9df9f98c).
  */
enum DijkstraTxPart derives CanEqual:
  case TxBody
  case TxWits
  case TxAuxData

/** Why a Conway transaction does not carry into Dijkstra ([[DijkstraTx.translate]]). */
enum DijkstraTxTranslationError derives CanEqual:
  /** A part does not read at Dijkstra's version: the `DecoderError` of `TranslateEra DijkstraEra
    * (Tx TopTx)`, labelled with the part (`Translation.hs:54-62`, cardano-ledger 9df9f98c).
    */
  case Undecodable(part: DijkstraTxPart, cause: EraTxError)
