// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import xyz.kd5ujc.scarab.ledger.state.{Allegra, Alonzo, Babbage, Conway, Dijkstra, EraTag, Mary}

/** The major protocol versions of era `E` (`class Era era`, cardano-ledger f649f975
  * `libs/cardano-ledger-core/internal/Cardano/Ledger/Internal/Definition/Era.hs:35-64`): its lowest
  * (`ProtVerLow`) and highest (`ProtVerHigh`, the lowest unless the era names it), as values
  * (`eraProtVerLow` and `eraProtVerHigh`,
  * `libs/cardano-ledger-core/src/Cardano/Ledger/Core/Era.hs:182-187`). The era's lowest is the
  * version its memoised values are encoded at (`mkMemoizedEra`).
  */
trait Era[E <: EraTag]:
  def eraProtVerLow: Version
  def eraProtVerHigh: Version

/** The instances of each era, the one place that lists the eras having the class: their versions at
  * `Era.hs:99-141` (cardano-ledger f649f975), Dijkstra's the same at cardano-ledger 9df9f98c
  * (`Era.hs:137-141`), the Leios testnet's build.
  */
object Era:
  private def of[E <: EraTag](low: Version, high: Version): Era[E] = new Era[E]:
    override def eraProtVerLow: Version = low
    override def eraProtVerHigh: Version = high

  given allegra: Era[Allegra] = of(Version.lit(3), Version.lit(3))
  given mary: Era[Mary] = of(Version.lit(4), Version.lit(4))
  given alonzo: Era[Alonzo] = of(Version.lit(5), Version.lit(6))
  given babbage: Era[Babbage] = of(Version.lit(7), Version.lit(8))
  given conway: Era[Conway] = of(Version.lit(9), Version.lit(11))
  given dijkstra: Era[Dijkstra] = of(Version.lit(12), Version.lit(12))
