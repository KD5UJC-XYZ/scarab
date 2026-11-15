// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.state.Coin

/** The arithmetic of an era's value (`Val`, cardano-ledger f649f975
  * `libs/cardano-ledger-core/src/Cardano/Ledger/Val.hs:30-92`): a rule that adds values up asks for
  * it, whatever the era's value is. The group is the reference's, signed: a value may be negative
  * in a calculation, and only an output's value is bounded, by its decoder. The size and
  * compact-form members (`size`, `isAdaOnlyCompact`, `coinCompact`, `injectCompact`,
  * `modifyCompactCoin`) come with the compact forms (tracked on #85).
  */
trait Val[V]:
  def zero: V
  def plus(a: V, b: V): V

  /** `<->`: `a` less `b`. */
  def minus(a: V, b: V): V

  /** `<×>`: every quantity of `v` times `n`. */
  def scale(n: BigInt, v: V): V

  def isZero(v: V): Boolean

  /** The lovelace of the value (`coin`, a `Coin`). */
  def coin(v: V): Coin

  def modifyCoin(f: Coin => Coin, v: V): V

  /** `pointwise`: `p` holds of every pair of quantities, a quantity one side lacks being zero. */
  def pointwise(p: (BigInt, BigInt) => Boolean, a: V, b: V): Boolean

  /** Whether the value is lovelace only (`isAdaOnly`). */
  def isAdaOnly(v: V): Boolean

/** A value that is lovelace only: `Coin`, the value of Shelley and Allegra (`Value ShelleyEra`,
  * `Value AllegraEra`), an `Integer` of either sign (`Coin.hs:93-107`, cardano-ledger f649f975).
  */
opaque type AdaValue = BigInt

object AdaValue:
  def apply(lovelace: BigInt): AdaValue = lovelace

  extension (v: AdaValue)
    def lovelace: BigInt = v

    /** The same lovelace as a multi-asset value, as an output's value is carried into Mary. */
    def toMultiAsset: MultiAssetValue = MultiAssetValue.ofAda(v)

  // `instance Val Coin`, `Val.hs:109-120`, cardano-ledger f649f975
  given Val[AdaValue] with
    def zero: AdaValue = BigInt(0)
    def plus(a: AdaValue, b: AdaValue): AdaValue = a + b
    def minus(a: AdaValue, b: AdaValue): AdaValue = a - b
    def scale(n: BigInt, v: AdaValue): AdaValue = n * v
    def isZero(v: AdaValue): Boolean = v.signum == 0
    def coin(v: AdaValue): Coin = Coin(v)
    def modifyCoin(f: Coin => Coin, v: AdaValue): AdaValue = f(Coin(v)).value
    def pointwise(p: (BigInt, BigInt) => Boolean, a: AdaValue, b: AdaValue): Boolean = p(a, b)
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

    /** The value in the form the store keeps. */
    private[ledger] def stored: xyz.kd5ujc.scarab.ledger.Value = v

  // `instance Val MaryValue`, `eras/mary/impl/src/Cardano/Ledger/Mary/Value.hs:214-262`,
  // cardano-ledger f649f975: the assets a canonical map, which holds no zero quantity and no empty
  // policy (`canonicalMap`, `Data/CanonicalMaps.hs`)
  given Val[MultiAssetValue] with
    def zero: MultiAssetValue = xyz.kd5ujc.scarab.ledger.Value(0, Map.empty)
    def plus(a: MultiAssetValue, b: MultiAssetValue): MultiAssetValue = a + b
    def minus(a: MultiAssetValue, b: MultiAssetValue): MultiAssetValue = a - b
    def scale(n: BigInt, v: MultiAssetValue): MultiAssetValue =
      xyz.kd5ujc.scarab.ledger.Value(
        n * v.coin,
        v.assets
          .map((policy, names) => policy -> names.map((name, q) => name -> n * q).filter(_._2 != 0))
          .filter(_._2.nonEmpty)
      )
    def isZero(v: MultiAssetValue): Boolean = v.isZero
    def coin(v: MultiAssetValue): Coin = Coin(v.coin)
    def modifyCoin(f: Coin => Coin, v: MultiAssetValue): MultiAssetValue =
      v.copy(coin = f(Coin(v.coin)).value)
    def pointwise(
        p: (BigInt, BigInt) => Boolean,
        a: MultiAssetValue,
        b: MultiAssetValue
    ): Boolean =
      val zero = Map.empty[Bytes, BigInt]
      p(a.coin, b.coin) && (a.assets.keySet ++ b.assets.keySet).forall { policy =>
        val (x, y) = (a.assets.getOrElse(policy, zero), b.assets.getOrElse(policy, zero))
        (x.keySet ++ y.keySet).forall(name => p(x.getOrElse(name, 0), y.getOrElse(name, 0)))
      }
    def isAdaOnly(v: MultiAssetValue): Boolean = v.assets.isEmpty
