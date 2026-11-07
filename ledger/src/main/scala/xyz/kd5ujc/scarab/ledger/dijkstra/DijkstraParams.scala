// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.dijkstra

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Node, Rational}
import xyz.kd5ujc.scarab.ledger.{CborRead, DecodeField, LedgerDecodeError, ParamUpdate}
import xyz.kd5ujc.scarab.plutus.ExUnits

import V12.{Interval, Result}

/** The protocol parameters Dijkstra adds to Conway's (`DijkstraPParams`, cardano-ledger 9df9f98c
  * `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/PParams.hs:228-299`), in the order of the record
  * and of their update keys 34 to 56 (`eraPParams`, `PParams.hs:773-795`). Conway's fields stay in
  * the node's flat parameters; the PlutusV4 cost model is the entry of language 3 of their cost
  * models.
  *
  * @param maxPledgeLeverage
  *   `MaxPledgeLeverage`, a `NonNegativeInterval` or none (`Core/PParams.hs:369-373`).
  * @param perasBootstrapRound
  *   a `Word32` or none.
  */
final case class DijkstraParams(
    maxRefScriptSizePerBlock: BigInt,
    maxRefScriptSizePerTx: BigInt,
    refScriptCostStride: BigInt,
    refScriptCostMultiplier: Rational,
    maxPledgeLeverage: Option[Rational],
    minPoolMargin: Rational,
    leiosAnnouncementPeriodLength: BigInt,
    leiosVotePeriodLength: BigInt,
    leiosDiffusionPeriodLength: BigInt,
    leiosCommitteeSize: BigInt,
    leiosQuorumStakeThreshold: Rational,
    maxEndorserBlockReferencesSize: BigInt,
    maxEndorserBlockTxsSize: BigInt,
    maxEndorserBlockExUnits: ExUnits,
    maxRefScriptSizePerEndorserBlock: BigInt,
    perasMinCandidateBlockAge: BigInt,
    perasHealingFactor: Rational,
    perasCertBoost: BigInt,
    perasTargetCommitteeSize: BigInt,
    perasBootstrapRound: Option[BigInt],
    perasQuorumThresholdSafetyMargin: Rational,
    refInputsCostPerMultiAssetPolicy: BigInt,
    refInputsCostPerDatumByte: BigInt
)

object DijkstraParams:
  private val what = DecodeField.ParameterUpdate

  /** The first update key of a field Dijkstra adds (`ppMaxRefScriptSizePerBlock`). */
  val FirstKey: Int = 34

  /** The last (`ppRefInputsCostPerDatumByte`). */
  val LastKey: Int = 56

  private type Set = (DijkstraParams, Node) => Result[DijkstraParams]

  private def word(max: BigInt)(set: (DijkstraParams, BigInt) => DijkstraParams): Set =
    (p, n) => V12.word(n, what, max).map(set(p, _))

  private def ratio(interval: Interval)(set: (DijkstraParams, Rational) => DijkstraParams): Set =
    (p, n) => V12.ratio(n, what, interval).map(set(p, _))

  private val w16 = word(V12.Word16Max)
  private val w32 = word(V12.Word32Max)
  private val w64 = word(V12.Word64Max)

  /** `NonZero Word32` (`BaseTypes/NonZero.hs:102-103`). */
  private val stride: Set = (p, n) =>
    V12
      .word32(n, what)
      .flatMap(v =>
        Either.cond(v != 0, p.copy(refScriptCostStride = v), LedgerDecodeError.Zero(what))
      )

  /** `ExUnits` from version 12 (`Plutus/ExUnits.hs:208-230`): two `Word64` within `Int64`. */
  private val endorserUnits: Set = (p, n) =>
    def part(x: Node) = V12.word(x, DecodeField.ExUnits, V12.Int64Max)
    V12
      .record(n, 2, DecodeField.ExUnits)
      .flatMap(xs => (part(xs(0)), part(xs(1))).mapN(ExUnits(_, _)))
      .map(v => p.copy(maxEndorserBlockExUnits = v))

  /** The setter of each update key, its value read by the decoder of [[DijkstraParamUpdate]]:
    * `decCBOR` of the field's type, the Peras bootstrap round null or a `Word32`
    * (`ppPerasBootstrapRound`'s `EraCodec`, `PParams.hs:1074-1078`), a `Coin` by `decodeWord64`
    * (`Coin.hs:107-112`) and a `CoinPerByte` as a compact coin, a `Word64`.
    */
  private val setters: Map[Int, Set] = Map(
    34 -> w32((p, v) => p.copy(maxRefScriptSizePerBlock = v)),
    35 -> w32((p, v) => p.copy(maxRefScriptSizePerTx = v)),
    36 -> stride,
    37 -> ratio(Interval.Positive)((p, v) => p.copy(refScriptCostMultiplier = v)),
    38 -> ((p, n) =>
      V12
        .nullable(n)(V12.ratio(_, what, Interval.NonNegative))
        .map(v => p.copy(maxPledgeLeverage = v))
    ),
    39 -> ratio(Interval.Unit)((p, v) => p.copy(minPoolMargin = v)),
    40 -> w32((p, v) => p.copy(leiosAnnouncementPeriodLength = v)),
    41 -> w32((p, v) => p.copy(leiosVotePeriodLength = v)),
    42 -> w32((p, v) => p.copy(leiosDiffusionPeriodLength = v)),
    43 -> w16((p, v) => p.copy(leiosCommitteeSize = v)),
    44 -> ratio(Interval.Unit)((p, v) => p.copy(leiosQuorumStakeThreshold = v)),
    45 -> w32((p, v) => p.copy(maxEndorserBlockReferencesSize = v)),
    46 -> w32((p, v) => p.copy(maxEndorserBlockTxsSize = v)),
    47 -> endorserUnits,
    48 -> w32((p, v) => p.copy(maxRefScriptSizePerEndorserBlock = v)),
    49 -> w32((p, v) => p.copy(perasMinCandidateBlockAge = v)),
    50 -> ratio(Interval.Positive)((p, v) => p.copy(perasHealingFactor = v)),
    51 -> w16((p, v) => p.copy(perasCertBoost = v)),
    52 -> w16((p, v) => p.copy(perasTargetCommitteeSize = v)),
    53 -> ((p, n) =>
      V12.nullable(n)(V12.word(_, what, V12.Word32Max)).map(v => p.copy(perasBootstrapRound = v))
    ),
    54 -> ratio(Interval.Unit)((p, v) => p.copy(perasQuorumThresholdSafetyMargin = v)),
    55 -> w64((p, v) => p.copy(refInputsCostPerMultiAssetPolicy = v)),
    56 -> w64((p, v) => p.copy(refInputsCostPerDatumByte = v))
  )

  /** The fields of `u` that Dijkstra adds, applied to `p` (`dijkstraApplyPPUpdates`,
    * `PParams.hs:317-388`: each field present replaces the value). Conway's fields, keys 0 to 33,
    * are applied to the flat parameters ([[xyz.kd5ujc.scarab.ledger.ProtocolParams.update]]); a key
    * past 56 is not of this record and refused. The fields are the original bytes of an update read
    * before by [[DijkstraParamUpdate.decode]].
    */
  def update(p: DijkstraParams, u: ParamUpdate): Result[DijkstraParams] =
    u.fields.toVector
      .filter((key, _) => key >= FirstKey)
      .sortBy(_._1)
      .foldLeftM(p) { case (acc, (key, raw)) =>
        setters
          .get(key)
          .toRight(LedgerDecodeError.UnknownKey(what, key))
          .flatMap(set => CborRead.decode(raw).flatMap(set(acc, _)))
      }
