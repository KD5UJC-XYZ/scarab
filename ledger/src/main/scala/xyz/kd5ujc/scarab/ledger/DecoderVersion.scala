// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.protocol.Era

/** The decoder version a block of an era is read at: the reference decodes every part of an era's
  * blocks at `eraProtVerLow` (`Cardano/Ledger/Core/Internal/Definition/Era.hs` in cardano-ledger at
  * the pinned commit f649f975). Collection and field decoders branch on it with
  * `ifDecoderVersionAtLeast`, so a reader that takes this value cannot disagree with the era.
  */
opaque type DecoderVersion = Int

object DecoderVersion:
  /** `MaxVersion` (`libs/cardano-ledger-binary/src/Cardano/Ledger/Binary/Version.hs:63`): the
    * largest `Version` the reference constructs, and so the largest major protocol version it
    * decodes (`mkVersion32`, `:111-124`).
    */
  val MaxVersion: Int = 13

  /** `eraProtVerLow`. Byron has no transactions of this shape and reads as Shelley. */
  def of(era: Era): DecoderVersion = era match
    case Era.Byron | Era.Shelley => 2
    case Era.Allegra             => 3
    case Era.Mary                => 4
    case Era.Alonzo              => 5
    case Era.Babbage             => 7
    case Era.Conway              => 9

  /** Dijkstra's `eraProtVerLow`, 12 (`type ProtVerLow DijkstraEra = 12`,
    * `libs/cardano-ledger-core/internal/Cardano/Ledger/Internal/Definition/Era.hs:140`,
    * cardano-ledger f649f975). The node has no Dijkstra ledger: the arguments of a node-to-client
    * query of that era are read at it, as the reference reads them (#720), and the transactions of
    * a Leios block by the readers of [[dijkstra.V12]], which follow the Leios testnet's build
    * (#750).
    */
  val Dijkstra: DecoderVersion = 12

  extension (v: DecoderVersion)
    /** `ifDecoderVersionAtLeast (natVersion @n)`. */
    def atLeast(n: Int): Boolean = v >= n

    def value: Int = v
