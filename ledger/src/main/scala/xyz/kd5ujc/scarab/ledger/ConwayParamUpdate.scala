// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import scala.collection.immutable.SortedMap
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Node, Rational}
import xyz.kd5ujc.scarab.plutus.ExUnits

/** An unsigned integer of the width of its reference type: `Word16`, `Word32` (also
  * `EpochInterval`, cardano-slotting) or `Word64` (`CompactForm Coin` and `CoinPerByte`,
  * `Coin.hs:193-194,220-222` at cardano-ledger f649f975). The only ways in are [[Unsigned.from]]
  * and [[Unsigned.read]], which refuse a value outside the width.
  */
opaque type Unsigned[W <: Unsigned.Width] = BigInt

object Unsigned:
  /** The width of an unsigned integer, as a type. */
  sealed trait Width

  object Width:
    sealed trait W16 extends Width
    sealed trait W32 extends Width
    sealed trait W64 extends Width

  /** The largest value of the width `W`. */
  final class Bound[W <: Width] private[Unsigned] (val max: BigInt)

  object Bound:
    given w16: Bound[Width.W16] = new Bound(BigInt(0xffff))
    given w32: Bound[Width.W32] = new Bound((BigInt(1) << 32) - 1)
    given w64: Bound[Width.W64] = new Bound((BigInt(1) << 64) - 1)

  def from[W <: Width](n: BigInt)(using b: Bound[W]): Option[Unsigned[W]] =
    Option.when(n.signum >= 0 && n <= b.max)(n)

  /** `decodeWord16`, `decodeWord32` or `decodeWord64` (cardano-ledger-binary over cborg): an
    * unsigned integer item of any head width whose value fits the width.
    */
  private[ledger] def read[W <: Width](n: Node, site: DecodeSite)(using
      b: Bound[W]
  ): Either[LedgerDecodeError, Unsigned[W]] =
    CborRead
      .uint(n, site)
      .flatMap(v => from[W](v).toRight(LedgerDecodeError.NumberOutOfRange(site, v, 0, b.max)))

  extension [W <: Width](u: Unsigned[W]) def value: BigInt = u

import Unsigned.Width.{W16, W32, W64}

/** The DRep group of a Conway parameter (`DRepGroup`, `Conway/PParams.hs:538-548`, f649f975). */
enum DRepGroup:
  case Network, Economic, Technical, Gov

/** Whether the stake pools vote on a change of the parameter (`StakePoolGroup`,
  * `Conway/PParams.hs:550-555`).
  */
enum StakePoolGroup:
  case Security, NoStakePool

/** One field of a Conway `PParamsUpdate` with its value at the type of the reference: the fields of
  * `ConwayPParams` (`Conway/PParams.hs:643-709`, f649f975) that have an update key in `eraPParams`
  * (`Conway/PParams.hs:862-894`), each with the key and the groups of its `THKD` annotation. The
  * protocol version is in `eraPParams` without an update key (`ppGovProtocolVersion`,
  * `ppUpdate = Nothing`), and `d`, the extra entropy and the minimum UTxO value are not Conway
  * parameters, so keys 12 to 15 have no case.
  */
enum ConwayParam(val key: Int, val drepGroup: DRepGroup, val poolGroup: StakePoolGroup):
  case TxFeePerByte(value: Unsigned[W64])
      extends ConwayParam(0, DRepGroup.Economic, StakePoolGroup.Security)
  case TxFeeFixed(value: Unsigned[W64])
      extends ConwayParam(1, DRepGroup.Economic, StakePoolGroup.Security)
  case MaxBBSize(value: Unsigned[W32])
      extends ConwayParam(2, DRepGroup.Network, StakePoolGroup.Security)
  case MaxTxSize(value: Unsigned[W32])
      extends ConwayParam(3, DRepGroup.Network, StakePoolGroup.Security)
  case MaxBHSize(value: Unsigned[W16])
      extends ConwayParam(4, DRepGroup.Network, StakePoolGroup.Security)
  case KeyDeposit(value: Unsigned[W64])
      extends ConwayParam(5, DRepGroup.Economic, StakePoolGroup.NoStakePool)
  case PoolDeposit(value: Unsigned[W64])
      extends ConwayParam(6, DRepGroup.Economic, StakePoolGroup.NoStakePool)
  case EMax(value: Unsigned[W32])
      extends ConwayParam(7, DRepGroup.Technical, StakePoolGroup.NoStakePool)
  case NOpt(value: Unsigned[W16])
      extends ConwayParam(8, DRepGroup.Technical, StakePoolGroup.NoStakePool)

  /** A `NonNegativeInterval`. */
  case A0(value: Rational) extends ConwayParam(9, DRepGroup.Technical, StakePoolGroup.NoStakePool)

  /** A `UnitInterval`. */
  case Rho(value: Rational) extends ConwayParam(10, DRepGroup.Economic, StakePoolGroup.NoStakePool)

  /** A `UnitInterval`. */
  case Tau(value: Rational) extends ConwayParam(11, DRepGroup.Economic, StakePoolGroup.NoStakePool)
  case MinPoolCost(value: Unsigned[W64])
      extends ConwayParam(16, DRepGroup.Economic, StakePoolGroup.NoStakePool)
  case CoinsPerUTxOByte(value: Unsigned[W64])
      extends ConwayParam(17, DRepGroup.Economic, StakePoolGroup.Security)

  /** The languages a `CostModels` names, each with its parameters in the order given. */
  case CostModels(models: Map[Int, Vector[BigInt]])
      extends ConwayParam(18, DRepGroup.Technical, StakePoolGroup.NoStakePool)

  /** `Prices`: two `NonNegativeInterval`s. */
  case Prices(memory: Rational, steps: Rational)
      extends ConwayParam(19, DRepGroup.Economic, StakePoolGroup.NoStakePool)
  case MaxTxExUnits(value: ExUnits)
      extends ConwayParam(20, DRepGroup.Network, StakePoolGroup.NoStakePool)
  case MaxBlockExUnits(value: ExUnits)
      extends ConwayParam(21, DRepGroup.Network, StakePoolGroup.Security)
  case MaxValSize(value: Unsigned[W32])
      extends ConwayParam(22, DRepGroup.Network, StakePoolGroup.Security)
  case CollateralPercentage(value: Unsigned[W16])
      extends ConwayParam(23, DRepGroup.Technical, StakePoolGroup.NoStakePool)
  case MaxCollateralInputs(value: Unsigned[W16])
      extends ConwayParam(24, DRepGroup.Network, StakePoolGroup.NoStakePool)
  case PoolThresholds(value: PoolVotingThresholds)
      extends ConwayParam(25, DRepGroup.Gov, StakePoolGroup.NoStakePool)
  case DRepThresholds(value: DRepVotingThresholds)
      extends ConwayParam(26, DRepGroup.Gov, StakePoolGroup.NoStakePool)
  case CommitteeMinSize(value: Unsigned[W16])
      extends ConwayParam(27, DRepGroup.Gov, StakePoolGroup.NoStakePool)
  case CommitteeMaxTermLength(value: Unsigned[W32])
      extends ConwayParam(28, DRepGroup.Gov, StakePoolGroup.NoStakePool)
  case GovActionLifetime(value: Unsigned[W32])
      extends ConwayParam(29, DRepGroup.Gov, StakePoolGroup.NoStakePool)
  case GovActionDeposit(value: Unsigned[W64])
      extends ConwayParam(30, DRepGroup.Gov, StakePoolGroup.Security)
  case DRepDeposit(value: Unsigned[W64])
      extends ConwayParam(31, DRepGroup.Gov, StakePoolGroup.NoStakePool)
  case DRepActivity(value: Unsigned[W32])
      extends ConwayParam(32, DRepGroup.Gov, StakePoolGroup.NoStakePool)

  /** A `NonNegativeInterval`. */
  case MinFeeRefScriptCostPerByte(value: Rational)
      extends ConwayParam(33, DRepGroup.Economic, StakePoolGroup.Security)

  /** The parameters with this field set. Total: the value was read at its field's type. Cost models
    * are merged into the current ones, the languages of the update replacing theirs.
    */
  def applyTo(p: ProtocolParams): ProtocolParams = this match
    case TxFeePerByte(v)               => p.copy(minFeeA = v.value)
    case TxFeeFixed(v)                 => p.copy(minFeeB = v.value)
    case MaxBBSize(v)                  => p.copy(maxBlockBodySize = v.value)
    case MaxTxSize(v)                  => p.copy(maxTxSize = v.value)
    case MaxBHSize(v)                  => p.copy(maxBlockHeaderSize = v.value)
    case KeyDeposit(v)                 => p.copy(keyDeposit = v.value)
    case PoolDeposit(v)                => p.copy(poolDeposit = v.value)
    case EMax(v)                       => p.copy(eMax = v.value)
    case NOpt(v)                       => p.copy(nOpt = v.value)
    case A0(v)                         => p.copy(a0 = v)
    case Rho(v)                        => p.copy(rho = v)
    case Tau(v)                        => p.copy(tau = v)
    case MinPoolCost(v)                => p.copy(minPoolCost = v.value)
    case CoinsPerUTxOByte(v)           => p.copy(coinsPerUTxOByte = v.value)
    case CostModels(m)                 => p.copy(costModels = p.costModels ++ m)
    case Prices(m, s)                  => p.copy(priceMem = m, priceSteps = s)
    case MaxTxExUnits(v)               => p.copy(maxTxExUnits = v)
    case MaxBlockExUnits(v)            => p.copy(maxBlockExUnits = v)
    case MaxValSize(v)                 => p.copy(maxValueSize = v.value)
    case CollateralPercentage(v)       => p.copy(collateralPercentage = v.value)
    case MaxCollateralInputs(v)        => p.copy(maxCollateralInputs = v.value)
    case PoolThresholds(v)             => p.copy(poolVotingThresholds = v)
    case DRepThresholds(v)             => p.copy(drepVotingThresholds = v)
    case CommitteeMinSize(v)           => p.copy(committeeMinSize = v.value)
    case CommitteeMaxTermLength(v)     => p.copy(committeeMaxTermLength = v.value)
    case GovActionLifetime(v)          => p.copy(govActionLifetime = v.value)
    case GovActionDeposit(v)           => p.copy(govActionDeposit = v.value)
    case DRepDeposit(v)                => p.copy(drepDeposit = v.value)
    case DRepActivity(v)               => p.copy(drepActivity = v.value)
    case MinFeeRefScriptCostPerByte(v) => p.copy(minFeeRefScriptCostPerByte = v)

object ConwayParam:
  private type Result[A] = Either[LedgerDecodeError, A]
  private type Read = Node => Result[ConwayParam]

  private def word[W <: Unsigned.Width: Unsigned.Bound](site: DecodeSite)(
      make: Unsigned[W] => ConwayParam
  ): Read =
    n => Unsigned.read[W](n, site).map(make)

  private def ratio(site: DecodeSite, bound: CborRead.Bound)(make: Rational => ConwayParam): Read =
    n => CborRead.rational(n, site, bound).map(make)

  private val Int64Max = BigInt(Long.MaxValue)

  /** `ExUnits` below decoder version 12 (`ExUnits.hs:195-215`, f649f975): a record of two `Word64`
    * s, each at most `maxBound :: Int64`.
    */
  private def exUnits(site: DecodeSite)(make: ExUnits => ConwayParam): Read =
    n =>
      CborRead
        .arrayN(n, 2, site)
        .flatMap(
          _.traverse(x =>
            Unsigned
              .read[W64](x, site)
              .flatMap(w =>
                Either.cond(
                  w.value <= Int64Max,
                  w.value,
                  LedgerDecodeError.NumberOutOfRange(site, w.value, 0, Int64Max)
                )
              )
          )
        )
        .map(xs => make(ExUnits(xs(0), xs(1))))

  private def thresholds[A](site: DecodeSite, size: Int)(
      build: Vector[Rational] => Result[A]
  )(make: A => ConwayParam): Read =
    n =>
      CborRead
        .arrayN(n, size, site)
        .flatMap(_.traverse(CborRead.rational(_, site, CborRead.Bound.Unit)))
        .flatMap(build)
        .map(make)

  /** The decoder of each update key: `decCBOR` of the field's type at the decoder version of Conway
    * (`mkField Nothing`, `Core/PParams.hs:284-293`).
    */
  private[ledger] val decoders: Map[BigInt, Read] = Map[Int, Read](
    0 -> word[W64](DecodeField.MinFeeA)(TxFeePerByte(_)),
    1 -> word[W64](DecodeField.MinFeeB)(TxFeeFixed(_)),
    2 -> word[W32](DecodeField.MaxBlockBodySize)(MaxBBSize(_)),
    3 -> word[W32](DecodeField.MaxTxSize)(MaxTxSize(_)),
    4 -> word[W16](DecodeField.MaxBlockHeaderSize)(MaxBHSize(_)),
    5 -> word[W64](DecodeField.KeyDeposit)(KeyDeposit(_)),
    6 -> word[W64](DecodeField.PoolDeposit)(PoolDeposit(_)),
    7 -> word[W32](DecodeField.EMax)(EMax(_)),
    8 -> word[W16](DecodeField.NOpt)(NOpt(_)),
    9 -> ratio(DecodeField.A0, CborRead.Bound.NonNegative)(A0(_)),
    10 -> ratio(DecodeField.Rho, CborRead.Bound.Unit)(Rho(_)),
    11 -> ratio(DecodeField.Tau, CborRead.Bound.Unit)(Tau(_)),
    16 -> word[W64](DecodeField.MinPoolCost)(MinPoolCost(_)),
    17 -> word[W64](DecodeField.CoinsPerUTxO)(CoinsPerUTxOByte(_)),
    18 -> (n => CostModelDecode.decode(n, CostModelDecode.Format.Open).map(CostModels(_))),
    19 -> (n =>
      CborRead
        .arrayN(n, 2, DecodeField.Prices)
        .flatMap(xs =>
          (
            CborRead.rational(xs(0), DecodeField.PriceMem, CborRead.Bound.NonNegative),
            CborRead.rational(xs(1), DecodeField.PriceSteps, CborRead.Bound.NonNegative)
          ).mapN(Prices(_, _))
        )
    ),
    20 -> exUnits(DecodeField.MaxTxExUnits)(MaxTxExUnits(_)),
    21 -> exUnits(DecodeField.MaxBlockExUnits)(MaxBlockExUnits(_)),
    22 -> word[W32](DecodeField.MaxValueSize)(MaxValSize(_)),
    23 -> word[W16](DecodeField.CollateralPercentage)(CollateralPercentage(_)),
    24 -> word[W16](DecodeField.MaxCollateralInputs)(MaxCollateralInputs(_)),
    25 -> thresholds(DecodeField.PoolVotingThresholds, 5)(PoolVotingThresholds.fromVector)(
      PoolThresholds(_)
    ),
    26 -> thresholds(DecodeField.DRepVotingThresholds, 10)(DRepVotingThresholds.fromVector)(
      DRepThresholds(_)
    ),
    27 -> word[W16](DecodeField.CommitteeMinSize)(CommitteeMinSize(_)),
    28 -> word[W32](DecodeField.CommitteeMaxTermLength)(CommitteeMaxTermLength(_)),
    29 -> word[W32](DecodeField.GovActionLifetime)(GovActionLifetime(_)),
    30 -> word[W64](DecodeField.GovActionDeposit)(GovActionDeposit(_)),
    31 -> word[W64](DecodeField.DrepDeposit)(DRepDeposit(_)),
    32 -> word[W32](DecodeField.DrepActivity)(DRepActivity(_)),
    33 -> ratio(DecodeField.MinFeeRefScriptCostPerByte, CborRead.Bound.NonNegative)(
      MinFeeRefScriptCostPerByte(_)
    )
  ).map((k, read) => BigInt(k) -> read)

/** A Conway `PParamsUpdate` (`Core/PParams.hs:224`, the `ConwayPParams StrictMaybe` of
  * `Conway/PParams.hs:643-709`, f649f975): the fields it sets, by update key, each with its typed
  * value and the original encoding of that value. Only [[ConwayParamUpdate.decode]] and
  * [[ConwayParamUpdate.fromEncoded]] build one, so every field is a Conway field read at its type
  * and applying the update cannot fail. The original encodings are what the ledger-state snapshot
  * writes back; the meaning of the update (the comparison of proposals, the rules) is [[params]].
  */
final case class ConwayParamUpdate private (entries: SortedMap[Int, ConwayParamUpdate.Entry]):
  /** The fields set, in key order. */
  def params: Vector[ConwayParam] = entries.valuesIterator.map(_.param).toVector

  /** The fields set by key: what the reference's `Ord PParamsUpdate` compares. */
  def byKey: SortedMap[Int, ConwayParam] = entries.map((k, e) => k -> e.param)

  def keys: Set[Int] = entries.keySet

  def isEmpty: Boolean = entries.isEmpty

  /** The original encoding of each field's value, in key order. */
  def encodings: Vector[(Int, Bytes)] = entries.toVector.map((k, e) => k -> e.original)

  /** The first field `select` picks. */
  def collectFirst[A](select: PartialFunction[ConwayParam, A]): Option[A] =
    params.collectFirst(select)

  /** The parameters with every field of the update set (`applyPPUpdates`). Total. */
  def applyTo(p: ProtocolParams): ProtocolParams = params.foldLeft(p)((acc, f) => f.applyTo(acc))

object ConwayParamUpdate:
  /** A field and the original encoding of its value. */
  final case class Entry(param: ConwayParam, original: Bytes)

  val empty: ConwayParamUpdate = ConwayParamUpdate(SortedMap.empty)

  /** `decCBOR` of a Conway `PParamsUpdate` below decoder version 12: `SparseKeyed` over the update
    * keys of `eraPParams` (`Core/PParams.hs:257-293`, f649f975), definite or indefinite. Entries
    * are read in order and the first bad one fails the update, as `applyField` does
    * (`Coders.hs:562-570`): the key is a `Word`, a key read before is `duplicateKey` (before its
    * value is read), a key without a field is `invalidKey`, and a value is read at the type of its
    * field. An empty update decodes; GOV refuses it (`ppuWellFormed`).
    */
  def decode(n: Node): Either[LedgerDecodeError, ConwayParamUpdate] =
    CborRead
      .map(n, DecodeField.ParameterUpdate)
      .flatMap(_.foldLeftM(empty) { case (acc, (k, v)) =>
        Unsigned
          .read[W64](k, DecodeField.ParameterIndex)
          .flatMap(key => acc.add(key.value, v))
      })

  /** The update of fields given by key and the original encoding of their values, read as
    * [[decode]] reads them: what a snapshot or another encoding of the same map holds.
    */
  def fromEncoded(fields: Vector[(BigInt, Bytes)]): Either[LedgerDecodeError, ConwayParamUpdate] =
    fields.foldLeftM(empty) { case (acc, (key, raw)) =>
      CborRead.decode(raw).flatMap(acc.add(key, _))
    }

  extension (u: ConwayParamUpdate)
    private def add(key: BigInt, value: Node): Either[LedgerDecodeError, ConwayParamUpdate] =
      for
        _ <- Either.cond(
          !(key.isValidInt && u.entries.contains(key.toInt)),
          (),
          LedgerDecodeError.DuplicateKey(DecodeField.ParameterUpdate)
        )
        read <- ConwayParam.decoders
          .get(key)
          .toRight(LedgerDecodeError.UnknownKey(DecodeField.ParameterUpdate, key))
        param <- read(value)
      yield ConwayParamUpdate(u.entries.updated(param.key, Entry(param, value.original)))
