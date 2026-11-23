// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.TxChecks
import xyz.kd5ujc.scarab.ledger.mempack.MultiAssetRep
import xyz.kd5ujc.scarab.ledger.state.{Coin, CompactCoin}

/** The arithmetic of an era's value (`Val`, cardano-ledger f649f975
  * `libs/cardano-ledger-core/src/Cardano/Ledger/Val.hs:30-92`): a rule that adds values up asks for
  * it, whatever the era's value is. The group is the reference's, signed: a value may be negative
  * in a calculation, and only an output's value is bounded, by its decoder. `pointwise` comes with
  * a mirror of `Data.Map` 's `pointWise`, whose result depends on the map's tree and on the order
  * of its recursive arguments (`Data/CanonicalMaps.hs:98-111`, cardano-ledger f649f975); it is
  * tracked on #85.
  */
trait Val[V]:
  /** `==`, of the superclass `Eq t` (`Val.hs:42`, cardano-ledger f649f975). */
  def eqv(a: V, b: V): Boolean

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

  /** Whether the value is lovelace only (`isAdaOnly`). */
  def isAdaOnly(v: V): Boolean

  /** `inject`, of the superclass `Inject Coin t` (`Val.hs:32`, `BaseTypes.hs:1032-1033`,
    * cardano-ledger f649f975): the value of lovelace `c` alone.
    */
  def inject(c: Coin): V

  /** `CompactForm t`, of the superclass `Compactible t` (`Val.hs:31`,
    * `libs/cardano-ledger-core/src/Cardano/Ledger/Compactible.hs:27-37`, cardano-ledger f649f975):
    * the value as an output keeps it.
    */
  type Compact

  /** `toCompact`, of the superclass `Compactible t` (`Compactible.hs:36`, cardano-ledger f649f975):
    * none where a quantity is outside a `Word64`.
    */
  def toCompact(v: V): Option[Compact]

  /** `fromCompact`, of the superclass `Compactible t` (`Compactible.hs:37`, cardano-ledger
    * f649f975).
    */
  def fromCompact(c: Compact): V

  /** `size` (`Val.hs:71`, cardano-ledger f649f975): the size of the value in `Word64` s, as the
    * entry size of an output before Babbage counts it.
    */
  def size(v: V): BigInt

  /** `isAdaOnlyCompact` (`Val.hs:82`, cardano-ledger f649f975). */
  def isAdaOnlyCompact(c: Compact): Boolean

  /** `coinCompact` (`Val.hs:84`, cardano-ledger f649f975): the lovelace of a compact value. */
  def coinCompact(c: Compact): CompactCoin

  /** `injectCompact` (`Val.hs:86`, cardano-ledger f649f975): the compact value of lovelace `c`
    * alone.
    */
  def injectCompact(c: CompactCoin): Compact

  /** `modifyCompactCoin` (`Val.hs:88`, cardano-ledger f649f975). */
  def modifyCompactCoin(f: CompactCoin => CompactCoin, c: Compact): Compact

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

  // `instance Val Coin`, `Val.hs:109-120`, cardano-ledger f649f975, and `instance Compactible Coin`
  // (`Coin.hs:147-154`): the compact form is a `Word64`
  given Val[AdaValue] with
    type Compact = CompactCoin
    def eqv(a: AdaValue, b: AdaValue): Boolean = a == b
    def zero: AdaValue = BigInt(0)
    def plus(a: AdaValue, b: AdaValue): AdaValue = a + b
    def minus(a: AdaValue, b: AdaValue): AdaValue = a - b
    def scale(n: BigInt, v: AdaValue): AdaValue = n * v
    def isZero(v: AdaValue): Boolean = v.signum == 0
    def coin(v: AdaValue): Coin = Coin(v)
    def modifyCoin(f: Coin => Coin, v: AdaValue): AdaValue = f(Coin(v)).value
    def isAdaOnly(v: AdaValue): Boolean = true
    def inject(c: Coin): AdaValue = c.value
    def toCompact(v: AdaValue): Option[CompactCoin] = CompactCoin.fromCoin(Coin(v)).toOption
    def fromCompact(c: CompactCoin): AdaValue = c.toBigInt
    def size(v: AdaValue): BigInt = BigInt(1)
    def isAdaOnlyCompact(c: CompactCoin): Boolean = true
    def coinCompact(c: CompactCoin): CompactCoin = c
    def injectCompact(c: CompactCoin): CompactCoin = c
    def modifyCompactCoin(f: CompactCoin => CompactCoin, c: CompactCoin): CompactCoin = f(c)

/** Lovelace and native assets: `MaryValue`, the value from Mary on (`Value MaryEra` to `Value
  * DijkstraEra`). Over the flat [[xyz.kd5ujc.scarab.ledger.Value]], whose arithmetic it keeps.
  */
opaque type MultiAssetValue = xyz.kd5ujc.scarab.ledger.Value

object MultiAssetValue:
  /** `v` with its assets canonical, as the reference's `MultiAsset` always is (`canonicalMap`,
    * `Data/CanonicalMaps.hs`, cardano-ledger f649f975): no zero quantity and no empty policy. The
    * empty value is added to `v`, and the flat sum drops what is zero: of assets with none, it
    * keeps them as they are, where adding `v` to the empty value builds each policy again (#85).
    */
  def of(v: xyz.kd5ujc.scarab.ledger.Value): MultiAssetValue =
    v + xyz.kd5ujc.scarab.ledger.Value.zero

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
    /** `instance Eq MaryValue` (`Mary/Value.hs:185-186`, cardano-ledger f649f975):
      * `pointwise (==)`, which on two canonical values is the equality of their coins and maps,
      * since a key on one side only has a nonzero quantity or a non-empty policy there
      * (`pointWise`, `Data/CanonicalMaps.hs:98-111`). Every value here is canonical:
      * [[MultiAssetValue.of]] makes it so, and the arithmetic keeps it (`Value.merge` drops what
      * reaches zero).
      */
    def eqv(a: MultiAssetValue, b: MultiAssetValue): Boolean = a == b
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
    def isAdaOnly(v: MultiAssetValue): Boolean = v.assets.isEmpty
    def inject(c: Coin): MultiAssetValue = ofAda(c.value)

    // `instance Compactible MaryValue` (`Mary/Value.hs:402-406`, cardano-ledger f649f975)
    type Compact = CompactValue
    def toCompact(v: MultiAssetValue): Option[CompactValue] = CompactValue.to(v)
    def fromCompact(c: CompactValue): MultiAssetValue = CompactValue.from(c)

    /** `size` (`Mary/Value.hs:225-244`, with `repOverhead` and `roundupBytesToWords` at 264-282 and
      * `representationSize` at 714-728, cardano-ledger f649f975): 2 for lovelace alone, which the
      * reference marks right for Alonzo's entry size only, and otherwise the words of the packed
      * representation and its overhead. The value is canonical, so its policies are the distinct
      * policies of its triples.
      */
    def size(v: MultiAssetValue): BigInt = TxChecks.valueWords(v)

    def isAdaOnlyCompact(c: CompactValue): Boolean = c match
      case CompactValue.AdaOnly(_)          => true
      case CompactValue.MultiAsset(_, _, _) => false

    def coinCompact(c: CompactValue): CompactCoin = c match
      case CompactValue.AdaOnly(cc)          => cc
      case CompactValue.MultiAsset(cc, _, _) => cc

    def injectCompact(c: CompactCoin): CompactValue = CompactValue.AdaOnly(c)

    def modifyCompactCoin(f: CompactCoin => CompactCoin, c: CompactValue): CompactValue =
      CompactValue.modifyCoin(f, c)

/** `CompactForm MaryValue`, a newtype of `CompactValue` (`Mary/Value.hs:402-406,420-426`,
  * cardano-ledger f649f975): a multi-asset value as an output keeps it.
  */
sealed trait CompactValue

object CompactValue:
  /** `CompactValueAdaOnly`: lovelace alone. */
  final case class AdaOnly(coin: CompactCoin) extends CompactValue

  /** `CompactValueMultiAsset`: the lovelace, the number of assets (a `Word32` there, an `Int` here,
    * which the bound on what is small enough to compact keeps below 1,490) and the packed
    * representation of the assets ([[xyz.kd5ujc.scarab.ledger.mempack.MultiAssetRep]], the layout
    * of `Mary/Value.hs:475-578`, cardano-ledger f649f975). Only [[to]] builds one, and
    * [[modifyCoin]] changes only its lovelace, so its representation is one that [[from]] reads
    * back.
    */
  final case class MultiAsset private[CompactValue] (coin: CompactCoin, count: Int, rep: Bytes)
      extends CompactValue

  /** `to` (`Mary/Value.hs:580-700`, cardano-ledger f649f975): none where the lovelace or a quantity
    * is outside a `Word64` (`integerToWord64`). Assets too many to compact are none here too; the
    * reference only asserts they never come (`isMultiAssetSmallEnough`, 588), and its decoder
    * refuses them (329). A representation too long for its `Word16` offsets has none either: the
    * reference's bound counts a name as at most 32 bytes, which its decoder enforces, so only names
    * longer than that, which no decoded value holds, reach it. A policy id is a script hash, 28
    * bytes, in the reference's type (`PolicyID`); a value whose policy is not has no counterpart
    * there, and no compact form here.
    */
  def to(v: MultiAssetValue): Option[CompactValue] =
    CompactCoin.fromCoin(Coin(v.lovelace)).toOption.flatMap { coin =>
      if v.assets.isEmpty then Some(AdaOnly(coin))
      else if v.assets.keysIterator.exists(_.size != PolicyIdSize) then None
      else MultiAssetRep.build(v.assets).toOption.map((n, rep) => MultiAsset(coin, n, rep))
    }

  /** The size of a policy id, a script hash (`ScriptHash`, Blake2b-224). */
  private val PolicyIdSize: Int = 28

  /** `modifyCompactCoin` of MaryValue (`Mary/Value.hs:258-262`, cardano-ledger f649f975): the
    * lovelace changed, the assets kept.
    */
  def modifyCoin(f: CompactCoin => CompactCoin, c: CompactValue): CompactValue = c match
    case AdaOnly(cc)          => AdaOnly(f(cc))
    case MultiAsset(cc, n, r) => MultiAsset(f(cc), n, r)

  /** `from` (`Mary/Value.hs:730-785`, cardano-ledger f649f975): the value again, canonical as
    * `valueFromList` makes it.
    */
  // Kept (ERR-5): a `MultiAsset` holds what `MultiAssetRep.build` wrote, as only `to` builds one
  // and `modifyCoin` keeps its representation, and `read` takes back whatever `build` writes, so
  // the read is always there (`EraValueSuite`).
  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  def from(c: CompactValue): MultiAssetValue = c match
    case AdaOnly(coin) => MultiAssetValue.ofAda(coin.toBigInt)
    case MultiAsset(coin, n, rep) =>
      MultiAssetValue.of(
        xyz.kd5ujc.scarab.ledger.Value(coin.toBigInt, MultiAssetRep.read(n, rep).toOption.get)
      )
