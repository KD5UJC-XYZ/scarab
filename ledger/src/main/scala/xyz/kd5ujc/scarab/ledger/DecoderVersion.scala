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
  /** `eraProtVerLow`. Byron has no transactions of this shape and reads as Shelley. */
  def of(era: Era): DecoderVersion = era match
    case Era.Byron | Era.Shelley => 2
    case Era.Allegra             => 3
    case Era.Mary                => 4
    case Era.Alonzo              => 5
    case Era.Babbage             => 7
    case Era.Conway              => 9

  extension (v: DecoderVersion)
    /** `ifDecoderVersionAtLeast (natVersion @n)`. */
    def atLeast(n: Int): Boolean = v >= n

    def value: Int = v
