// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import xyz.kd5ujc.scarab.codec.DomainError

/** A part of the Dijkstra era ([[Era.Dijkstra]]) that this build names but does not implement yet,
  * and so refuses (#54). The node does not enter Dijkstra: these are refusals of data of that era
  * from a peer, a client or a file, never a verdict on it. Each part is removed by the stage of #54
  * that implements it.
  */
enum DijkstraNotYet extends DomainError:
  /** A Dijkstra ledger state in the Haskell `state` file layout. Persisted formats and the
    * translation.
    */
  case LedgerState

  /** The translation of a Conway ledger state into Dijkstra (`TranslateEra DijkstraEra`,
    * cardano-ledger 9df9f98c `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Translation.hs`).
    */
  case Translation

  /** A chain started from its genesis in Dijkstra: the era's `injectIntoTestState` also seats the
    * Leios committee (9df9f98c `Dijkstra/Transition.hs:59-62`). Genesis and parameters.
    */
  case Genesis

  /** A ledger rule of Dijkstra whose reference differs from Conway's: EPOCH, SNAP, POOL and
    * POOLREAP seat the Leios committee, and BBODY, LEDGER and UTXOW read Dijkstra's block body and
    * transactions (cardano-ledger 9df9f98c `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Rules`).
    * A decoded Dijkstra block or transaction reaches no rule. Ledger rules.
    */
  case Rules

  def message: String = this match
    case LedgerState => "a Dijkstra ledger state is not read or written by this build"
    case Translation => "the translation of a Conway ledger state into Dijkstra is not implemented"
    case Genesis     => "a chain cannot start in Dijkstra from its genesis in this build"
    case Rules       => "the ledger rules of Dijkstra are not implemented"
