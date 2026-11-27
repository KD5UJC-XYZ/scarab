// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.ledger.{DecodeField, Unsigned}
import xyz.kd5ujc.scarab.ledger.Unsigned.Width.W32
import xyz.kd5ujc.scarab.ledger.json.BoundedRatio
import xyz.kd5ujc.scarab.ledger.state.CompactCoin

// The types of the protocol parameters' fields that the tree had only as `BigInt` or `Rational`
// (cardano-ledger f649f975). Each is built only through a constructor that checks its bounds.

/** A field of the parameters out of its type's range, as read from the parameters the node keeps.
  */
enum PParamsError:
  case OutOfRange(field: DecodeField, value: BigInt)
  case RatioOutOfBounds(field: DecodeField, value: Rational)

  /** A cost model whose language tag is not a `Word8`, or one of whose parameters is not an
    * `Int64`.
    */
  case CostModelOutOfRange(tag: Int, value: BigInt)

  /** Execution units of which a component is negative. */
  case NegativeExUnits(field: DecodeField, units: xyz.kd5ujc.scarab.plutus.ExUnits)

  /** A key of an update that is not a key of the era's parameters. */
  case UnknownKey(key: Int)

  /** A value of an update whose form is not that of its key's field. */
  case ValueNotOfKey(key: Int)

/** `UnitInterval` (`BaseTypes.hs:543-545`, cardano-ledger f649f975), defined with the state's
  * vocabulary ([[xyz.kd5ujc.scarab.ledger.state.UnitInterval]]), which the pool parameters hold.
  */
type UnitInterval = xyz.kd5ujc.scarab.ledger.state.UnitInterval
val UnitInterval: xyz.kd5ujc.scarab.ledger.state.UnitInterval.type =
  xyz.kd5ujc.scarab.ledger.state.UnitInterval

/** `NonNegativeInterval` (`BaseTypes.hs:468-486`, cardano-ledger f649f975):
  * `[0, maxBound :: Word64]`.
  */
opaque type NonNegativeInterval = Rational

object NonNegativeInterval:
  /** `minBound`: zero. */
  val zero: NonNegativeInterval = Rational.zero

  def boundRational(r: Rational): Option[NonNegativeInterval] = BoundedRatio.NonNegative.bound(r)

  extension (n: NonNegativeInterval) def rational: Rational = n

/** `PositiveInterval` (`BaseTypes.hs:488-506`, cardano-ledger f649f975):
  * `[10^-19, maxBound :: Word64]`.
  */
opaque type PositiveInterval = Rational

object PositiveInterval:
  def boundRational(r: Rational): Option[PositiveInterval] = BoundedRatio.Positive.bound(r)

  /** `minBound = positiveIntervalEpsilon`, `1 % 10 ^ 19` (`BaseTypes.hs:505-510`, cardano-ledger
    * 9df9f98c): the least value.
    */
  val minBound: PositiveInterval = BoundedRatio.Positive.lower

  /** `boundRational 1.2` (`Conway/PParams.hs:983`, cardano-ledger f649f975), within the bounds. */
  private[era] val oneAndAFifth: PositiveInterval = Rational(6, 5)

  extension (p: PositiveInterval) def rational: Rational = p

/** `MaxPledgeLeverage` (`newtype MaxPledgeLeverage = MaxPledgeLeverage {unMaxPledgeLeverage ::
  * StrictMaybe NonNegativeInterval}`, cardano-ledger 9df9f98c `Core/PParams.hs:369-379`): the most
  * a pool's stake may be over its pledge, or no limit.
  */
final case class MaxPledgeLeverage(unMaxPledgeLeverage: Option[NonNegativeInterval])

object MaxPledgeLeverage:
  /** `MaxPledgeLeverage SNothing`: no limit. */
  val none: MaxPledgeLeverage = MaxPledgeLeverage(None)

/** `CoinPerByte` (`newtype CoinPerByte = CoinPerByte {unCoinPerByte :: CompactForm Coin}`,
  * `libs/cardano-ledger-core/src/Cardano/Ledger/Coin.hs:220-222`, cardano-ledger f649f975).
  */
opaque type CoinPerByte = CompactCoin

object CoinPerByte:
  def apply(c: CompactCoin): CoinPerByte = c

  extension (c: CoinPerByte) def unCoinPerByte: CompactCoin = c

/** `Version` (`newtype Version = Version Word32`,
  * `libs/cardano-ledger-binary/src/Cardano/Ledger/Binary/Version.hs:55-63`, cardano-ledger
  * f649f975): a major protocol version, `MinVersion` 0 to `MaxVersion` 13 (`mkVersion32`,
  * `:111-125`).
  */
opaque type Version = Int

object Version:
  val Max: Int = xyz.kd5ujc.scarab.ledger.DecoderVersion.MaxVersion

  /** `mkVersion`. */
  def mkVersion(n: BigInt): Option[Version] = Option.when(n >= 0 && n <= Max)(n.toInt)

  /** A constant version, checked when it is compiled. */
  inline def lit(inline n: Int): Version =
    inline if n < 0 || n > 13 then scala.compiletime.error("not a version") else n

  extension (v: Version)
    def value: Int = v

    /** `getVersion32` (`libs/cardano-ledger-binary/src/Cardano/Ledger/Binary/Version.hs:143-145`,
      * cardano-ledger f649f975, the same at 9df9f98c): the version's `Word32`, exact, as a version
      * is at most [[Max]].
      */
    def getVersion32: Unsigned[W32] = Unsigned.wrapping[W32](BigInt(v))

  given Ordering[Version] = Ordering.Int

/** `ProtVer` (`data ProtVer = ProtVer {pvMajor :: !Version, pvMinor :: !Word32}`,
  * `BaseTypes.hs:211-214`, cardano-ledger f649f975).
  */
final case class ProtVer(pvMajor: Version, pvMinor: Unsigned[W32])

/** `Prices` (`data Prices = Prices {prMem, prSteps :: !NonNegativeInterval}`,
  * `libs/cardano-ledger-core/src/Cardano/Ledger/Plutus/ExUnits.hs:159-163`, cardano-ledger
  * f649f975).
  */
final case class Prices(prMem: NonNegativeInterval, prSteps: NonNegativeInterval)

object Prices:
  /** `Prices minBound minBound`. */
  val zero: Prices = Prices(NonNegativeInterval.zero, NonNegativeInterval.zero)

/** `CostModels` (`libs/cardano-ledger-core/src/Cardano/Ledger/Plutus/CostModels.hs`): the cost
  * model of each language by its tag (0 is PlutusV1), the parameters in their canonical order, as
  * the node keeps them: the reference's valid models (known languages) and unknown ones (unknown
  * tags) in one map, a tag being in only one of the two (`CostModels.hs:378-382`, cardano-ledger
  * f649f975).
  */
opaque type CostModels = Map[Int, Vector[BigInt]]

object CostModels:
  /** `emptyCostModels`. */
  val empty: CostModels = Map.empty

  private val Int64 = (BigInt(Long.MinValue), BigInt(Long.MaxValue))

  /** The models by tag, each tag a `Word8` and each parameter an `Int64` (`Map Word8 [Int64]`, and
    * `cmValues :: [Int64]`, `CostModels.hs:105-111,378-382`, cardano-ledger f649f975). Whether a
    * known language's parameters make an evaluation context (`mkCostModel`, `:278-291`) is the
    * cost-model decoder's to check.
    */
  def from(models: Map[Int, Vector[BigInt]]): Either[PParamsError, CostModels] =
    def outside(v: BigInt): Boolean = v < Int64._1 || v > Int64._2
    val badTag = models.keys.find(tag => tag < 0 || tag > 0xff).map(tag => (tag, BigInt(tag)))
    val badValue =
      models.toVector.flatMap((tag, ps) => ps.find(outside).map(v => (tag, v))).headOption
    badTag.orElse(badValue).map(PParamsError.CostModelOutOfRange(_, _)).toLeft(models)

  extension (c: CostModels)
    def models: Map[Int, Vector[BigInt]] = c

    /** The model under `tag`, each parameter as the `Int64` it is: every model was made through
      * [[from]], which holds only parameters within the range of an `Int64`, or is the union of two
      * that were.
      */
    def int64Model(tag: Int): Option[Vector[Long]] = c.get(tag).map(_.map(_.toLong))

    /** `updateCostModels` (`CostModels.hs:404-415`, cardano-ledger f649f975): the languages of
      * `update` replace those of `c`. Since a tag is valid or unknown in both, the union of each
      * part is the union of the map.
      */
    def updated(update: CostModels): CostModels = c ++ update

  /** `mkCostModels . Map.singleton lang` (`mkCostModels`, `CostModels.hs:418-419`, cardano-ledger
    * f649f975): the one model, a valid model of its language, under the language's tag.
    */
  def singleton[L <: Language](model: CostModel[L]): CostModels =
    Map(model.cmLanguage.id -> model.cmValues.map(BigInt(_)))

/** `ExUnits` (`newtype ExUnits = WrapExUnits {unWrapExUnits :: ExUnits' Natural}`,
  * `libs/cardano-ledger-core/src/Cardano/Ledger/Plutus/ExUnits.hs:100-104`, cardano-ledger
  * f649f975): memory and steps, each a `Natural`. Over the node's units, whose components are any
  * integers, holding only those with no negative component.
  */
opaque type ExUnits = xyz.kd5ujc.scarab.plutus.ExUnits

object ExUnits:
  val zero: ExUnits = xyz.kd5ujc.scarab.plutus.ExUnits.zero

  def from(units: xyz.kd5ujc.scarab.plutus.ExUnits): Option[ExUnits] =
    Option.when(units.mem.signum >= 0 && units.steps.signum >= 0)(units)

  extension (e: ExUnits)
    /** The units as the node's evaluator takes them. */
    def units: xyz.kd5ujc.scarab.plutus.ExUnits = e

/** `OrdExUnits` (`newtype OrdExUnits = OrdExUnits {unOrdExUnits :: ExUnits}`,
  * `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/PParams.hs:413-418`, cardano-ledger f649f975): the
  * largest units of a transaction or block, as the record holds them.
  */
final case class OrdExUnits(unOrdExUnits: ExUnits)

object OrdExUnits:
  val zero: OrdExUnits = OrdExUnits(ExUnits.zero)

  /** `hkdMap unOrdExUnits` over a field, and `hkdMap OrdExUnits` back: the units lens of each
    * record that holds them as `OrdExUnits` (`Babbage/PParams.hs:232-239`,
    * `Conway/PParams.hs:915-923`, cardano-ledger f649f975).
    */
  def hkdUnits[F[_]](using f: HKDFunctor[F]): monocle.Lens[F[OrdExUnits], F[ExUnits]] =
    monocle.Lens[F[OrdExUnits], F[ExUnits]](f.hkdMap(_.unOrdExUnits))(u =>
      _ => f.hkdMap(OrdExUnits(_))(u)
    )
