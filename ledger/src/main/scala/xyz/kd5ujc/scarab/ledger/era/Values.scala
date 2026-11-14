// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import xyz.kd5ujc.scarab.codec.Bytes

/** The arithmetic of an era's value (`Val`, cardano-ledger f649f975
  * `libs/cardano-ledger-core/src/Cardano/Ledger/Val.hs`): a rule that adds values up asks for it,
  * whatever the era's value is.
  */
trait Val[V]:
  def zero: V
  def plus(a: V, b: V): V

  /** The lovelace of the value (`coin`). */
  def coin(v: V): BigInt

  /** Whether the value is lovelace only (`isAdaOnly`). */
  def isAdaOnly(v: V): Boolean

/** A value that is lovelace only: `Coin`, the value of Shelley and Allegra (`Value ShelleyEra`,
  * `Value AllegraEra`).
  */
opaque type AdaValue = BigInt

object AdaValue:
  /** `None` for a negative quantity, which no output holds. */
  def of(lovelace: BigInt): Option[AdaValue] = Option.when(lovelace >= 0)(lovelace)

  extension (v: AdaValue)
    def lovelace: BigInt = v

    /** The same lovelace as a multi-asset value, as an output's value is carried into Mary. */
    def toMultiAsset: MultiAssetValue = MultiAssetValue.ofAda(v)

  given Val[AdaValue] with
    def zero: AdaValue = BigInt(0)
    def plus(a: AdaValue, b: AdaValue): AdaValue = a + b
    def coin(v: AdaValue): BigInt = v
    def isAdaOnly(v: AdaValue): Boolean = true

/** Lovelace and native assets: `MaryValue`, the value from Mary on (`Value MaryEra` to `Value
  * DijkstraEra`). Over the flat [[xyz.kd5ujc.scarab.ledger.Value]], whose arithmetic it keeps.
  */
opaque type MultiAssetValue = xyz.kd5ujc.scarab.ledger.Value

object MultiAssetValue:
  def of(v: xyz.kd5ujc.scarab.ledger.Value): MultiAssetValue = v

  def ofAda(lovelace: BigInt): MultiAssetValue =
    xyz.kd5ujc.scarab.ledger.Value(lovelace, Map.empty)

  extension (v: MultiAssetValue)
    def lovelace: BigInt = v.coin
    def assets: Map[Bytes, Map[Bytes, BigInt]] = v.assets

  given Val[MultiAssetValue] with
    def zero: MultiAssetValue = xyz.kd5ujc.scarab.ledger.Value(0, Map.empty)
    def plus(a: MultiAssetValue, b: MultiAssetValue): MultiAssetValue = a + b
    def coin(v: MultiAssetValue): BigInt = v.coin
    def isAdaOnly(v: MultiAssetValue): Boolean = v.assets.isEmpty
