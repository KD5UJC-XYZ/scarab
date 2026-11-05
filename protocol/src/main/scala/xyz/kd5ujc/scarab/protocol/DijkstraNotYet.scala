// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import xyz.kd5ujc.scarab.codec.DomainError

/** A part of the Dijkstra era ([[Era.Dijkstra]]) that this build names but does not implement yet,
  * and so refuses (#54). The node does not enter Dijkstra: these are refusals of data of that era
  * from a peer, a client or a file, never a verdict on it. Each part is removed by the stage of #54
  * that implements it.
  */
enum DijkstraNotYet extends DomainError:
  /** A node-to-node header of index 7 in the closed decoder of the eras the node follows
    * ([[NtNHeader]]); [[HfcHeader]] keeps it as raw bytes. Block and header codecs.
    */
  case Header

  /** A block of tag 8 read by the ledger's block decoder. Block and header codecs. */
  case Block

  /** A transaction, body or witness set read in Dijkstra. Codecs and rules. */
  case Transaction

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
    * POOLREAP seat the Leios committee (cardano-ledger 9df9f98c `eras/dijkstra/impl/src/Cardano/
    * Ledger/Dijkstra/Rules`). Ledger rules.
    */
  case Rules

  def message: String = this match
    case Header      => "a Dijkstra header is not decoded by this build"
    case Block       => "a Dijkstra block is not decoded by this build"
    case Transaction => "a Dijkstra transaction is not decoded by this build"
    case LedgerState => "a Dijkstra ledger state is not read or written by this build"
    case Translation => "the translation of a Conway ledger state into Dijkstra is not implemented"
    case Genesis     => "a chain cannot start in Dijkstra from its genesis in this build"
    case Rules       => "the ledger rules of Dijkstra are not implemented"
