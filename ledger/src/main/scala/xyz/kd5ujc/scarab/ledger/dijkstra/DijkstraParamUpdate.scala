// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.dijkstra

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Node, Rational}
import xyz.kd5ujc.scarab.ledger.{DecodeField, DecodeSite, LedgerDecodeError}

import V12.{Interval, Result}

/** The value of one field of a Dijkstra protocol parameter update, decoded. */
enum DijkstraParamValue:
  /** A `Word16`, `Word32`, `Word64`, coin, epoch interval, duration or slot interval. */
  case Number(value: BigInt)

  /** A `UnitInterval`, `NonNegativeInterval` or `PositiveInterval`. */
  case Ratio(value: Rational)

  /** `MaxPledgeLeverage`: a `NonNegativeInterval` or null. */
  case OptionalRatio(value: Option[Rational])

  /** The Peras bootstrap round: a `Word32` or null. */
  case OptionalNumber(value: Option[Long])

  /** `CostModels`: per language tag its parameters, in the order given. */
  case CostModels(models: Map[Int, Vector[Long]])

  /** `Prices` (memory, steps) or `ExUnits` (memory, steps). */
  case Pair(first: Rational, second: Rational)
  case ExUnits(memory: BigInt, steps: BigInt)

  /** `PoolVotingThresholds` (5) or `DRepVotingThresholds` (10). */
  case Thresholds(values: Vector[Rational])

/** A Dijkstra protocol parameter update: per update key, its value. */
final case class DijkstraParamUpdate(fields: Map[Int, DijkstraParamValue])

object DijkstraParamUpdate:
  import DijkstraParamValue.*

  private val what = DecodeField.ParameterUpdate

  private def word(max: BigInt)(n: Node): Result[DijkstraParamValue] =
    V12.word(n, what, max).map(Number(_))
  private val w16 = word(V12.Word16Max)
  private val w32 = word(V12.Word32Max)
  private val w64 = word(V12.Word64Max)
  private def ratio(interval: Interval)(n: Node): Result[DijkstraParamValue] =
    V12.ratio(n, what, interval).map(Ratio(_))

  /** `ExUnits` from version 12 (`libs/cardano-ledger-core/src/Cardano/Ledger/Plutus/ExUnits.hs:
    * 208-230`, cardano-ledger 9df9f98c): `decodeRecordNamed` of two `Word64` no larger than the
    * `Int64` maximum.
    */
  private def exUnits(n: Node): Result[DijkstraParamValue] =
    def part(x: Node) = V12.word(x, DecodeField.ExUnits, V12.Int64Max)
    V12
      .record(n, 2, DecodeField.ExUnits)
      .flatMap(xs => (part(xs(0)), part(xs(1))).mapN(ExUnits(_, _)))

  /** `Prices` from version 12 (`ExUnits.hs:235-243`): two `NonNegativeInterval`. */
  private def prices(n: Node): Result[DijkstraParamValue] =
    V12
      .record(n, 2, DecodeField.Prices)
      .flatMap(xs =>
        (
          V12.ratio(xs(0), DecodeField.PriceMem, Interval.NonNegative),
          V12.ratio(xs(1), DecodeField.PriceSteps, Interval.NonNegative)
        )
          .mapN(Pair(_, _))
      )

  /** `PoolVotingThresholds` and `DRepVotingThresholds`
    * (`eras/conway/impl/src/Cardano/Ledger/Conway/ PParams.hs:367-375, 520-533`):
    * `decodeRecordNamed` of `size` `UnitInterval`.
    */
  private def thresholds(size: Int, site: DecodeSite)(n: Node): Result[DijkstraParamValue] =
    V12
      .record(n, size, site)
      .flatMap(_.traverse(V12.ratio(_, site, Interval.Unit)))
      .map(Thresholds(_))

  /** `CostModels` from version 9 (`decodeCostModelsLenient`, `libs/cardano-ledger-core/src/Cardano/
    * Ledger/Plutus/CostModels.hs:302-316, 426-440`): a map of `Word8` language tags, none twice, to
    * `decodeList` of `Int64`. A known language's list goes to Plutus's `mkEvaluationContext`, which
    * takes a list of any length (`CostModels.hs:278-291`); an unknown one is kept.
    */
  private def costModels(n: Node): Result[DijkstraParamValue] =
    V12
      .map(n, DecodeField.CostModels)(V12.word8(_, DecodeField.CostModelLanguage))((_, v) =>
        V12.list(v, DecodeField.CostModel).flatMap(_.traverse(V12.int64(_, DecodeField.Cost)))
      )
      .map(models => CostModels(models.toMap))

  /** The update keys of Dijkstra's `eraPParams` and the decoder of each (`eras/dijkstra/impl/src/
    * Cardano/Ledger/Dijkstra/PParams.hs`, `DijkstraPParams` and `eraPParams`; the shared fields in
    * `Shelley/PParams.hs`, `Alonzo/PParams.hs`, `Babbage/PParams.hs` and `Conway/PParams.hs`): each
    * value `decCBOR` of the field's type, the Peras bootstrap round null or a `Word32`
    * (`ppPerasBootstrapRound`'s `EraCodec`). The protocol version has no update key.
    */
  private val fieldDecoders: Map[Int, Node => Result[DijkstraParamValue]] = Map(
    0 -> w64,
    1 -> w64,
    2 -> w32,
    3 -> w32,
    4 -> w16,
    5 -> w64,
    6 -> w64,
    7 -> w32,
    8 -> w16,
    9 -> ratio(Interval.NonNegative),
    10 -> ratio(Interval.Unit),
    11 -> ratio(Interval.Unit),
    16 -> w64,
    17 -> w64,
    18 -> costModels,
    19 -> prices,
    20 -> exUnits,
    21 -> exUnits,
    22 -> w32,
    23 -> w16,
    24 -> w16,
    25 -> thresholds(5, DecodeField.PoolVotingThresholds),
    26 -> thresholds(10, DecodeField.DRepVotingThresholds),
    27 -> w16,
    28 -> w32,
    29 -> w32,
    30 -> w64,
    31 -> w64,
    32 -> w32,
    33 -> ratio(Interval.NonNegative),
    34 -> w32,
    35 -> w32,
    // `NonZero Word32` (`BaseTypes/NonZero.hs:102-103`)
    36 -> (n =>
      V12.word32(n, what).flatMap(v => Either.cond(v != 0, Number(v), LedgerDecodeError.Zero(what)))
    ),
    37 -> ratio(Interval.Positive),
    // `MaxPledgeLeverage`, `decodeNullStrictMaybe` of a `NonNegativeInterval` (`Core/PParams.hs:378-379`)
    38 -> (n => V12.nullable(n)(V12.ratio(_, what, Interval.NonNegative)).map(OptionalRatio(_))),
    39 -> ratio(Interval.Unit),
    40 -> w32,
    41 -> w32,
    42 -> w32,
    43 -> w16,
    44 -> ratio(Interval.Unit),
    45 -> w32,
    46 -> w32,
    47 -> exUnits,
    48 -> w32,
    49 -> w32,
    50 -> ratio(Interval.Positive),
    51 -> w16,
    52 -> w16,
    53 -> (n => V12.nullable(n)(V12.word32(_, what)).map(OptionalNumber(_))),
    54 -> ratio(Interval.Unit),
    55 -> w64,
    56 -> w64
  )

  /** `PParamsUpdate` from version 12 (`libs/cardano-ledger-core/src/Cardano/Ledger/Core/PParams.hs:
    * 265-300`): `decodeSparseKeyed` with no required key over the update keys, each value read by
    * its field's decoder.
    */
  def decode(n: Node): Result[DijkstraParamUpdate] =
    Sparse
      .read(n, what, required = Set.empty)(fieldDecoders.keySet)
      .flatMap(
        _.toVector.traverse((k, v) =>
          fieldDecoders
            .get(k)
            .toRight(LedgerDecodeError.UnknownKey(what, k))
            .flatMap(_(v))
            .tupleLeft(k)
        )
      )
      .map(fields => DijkstraParamUpdate(fields.toMap))
