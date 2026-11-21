// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import monocle.{Getter, Lens}
import scala.collection.immutable.SortedSet
import xyz.kd5ujc.scarab.ledger.Unsigned
import xyz.kd5ujc.scarab.ledger.era.conway.{DRepVotingThresholds, PoolVotingThresholds}
import xyz.kd5ujc.scarab.ledger.era.dijkstra.{DijkstraEraPParams, DijkstraEraPParamsInstance}
import xyz.kd5ujc.scarab.ledger.Unsigned.Width.{W16, W32}
import xyz.kd5ujc.scarab.ledger.state.{
  CompactCoin,
  Conway,
  Dijkstra,
  EpochInterval,
  EraTag,
  NonZero
}

/** The protocol parameters of an era (`class EraPParams era`, cardano-ledger f649f975
  * `libs/cardano-ledger-core/src/Cardano/Ledger/Core/PParams.hs:360-489`). Each field is a lens
  * over the era's record generic in its type constructor, so one lens reads the parameters
  * (`Identity`) and an update (`StrictMaybe`); `HKD f a` is `F[A]`, the `Identity` of `A` being
  * `A`.
  *
  * The reference's members confined to a range of eras (`hkdDL`, `hkdExtraEntropyL`,
  * `hkdProtocolVersionL`, `ppuProtocolVersionL` and `hkdMinUTxOValueCompactL`, `:459-484`) are not
  * here: each range is a class of its own with instances at the eras of the range (decided
  * 2026-11-15, #85), and none of those eras has its record yet. `ppDG` and `ppProtocolVersionL` are
  * members of every era. Not yet here: `emptyUpgradePParamsUpdate`, `upgradePParamsHKD` and
  * `downgradePParamsHKD`, which come with Babbage's record (#85).
  *
  * The class carries the union of the reference builds' members: `ppMaxPledgeLeverageG` and
  * `ppMinPoolMarginG` are members at the Leios build's pin (cardano-ledger 9df9f98c
  * `Core/PParams.hs:510-524`) and not at f649f975, where nothing reads them (decided 2026-11-15,
  * #85).
  */
trait EraPParams[E <: EraTag]:
  /** `applyPPUpdates`. */
  def applyPPUpdates(pp: PParams[E], ppu: PParamsUpdate[E]): PParams[E]

  def emptyPParamsIdentity: PParamsHKD[Identity, E]
  def emptyPParamsStrictMaybe: PParamsHKD[StrictMaybe, E]

  def hkdTxFeePerByteL[F[_]: HKDFunctor]: Lens[PParamsHKD[F, E], F[CoinPerByte]]
  def hkdTxFeeFixedCompactL[F[_]: HKDFunctor]: Lens[PParamsHKD[F, E], F[CompactCoin]]
  def hkdMaxBBSizeL[F[_]: HKDFunctor]: Lens[PParamsHKD[F, E], F[Unsigned[W32]]]
  def hkdMaxTxSizeL[F[_]: HKDFunctor]: Lens[PParamsHKD[F, E], F[Unsigned[W32]]]
  def hkdMaxBHSizeL[F[_]: HKDFunctor]: Lens[PParamsHKD[F, E], F[Unsigned[W16]]]
  def hkdKeyDepositCompactL[F[_]: HKDFunctor]: Lens[PParamsHKD[F, E], F[CompactCoin]]
  def hkdPoolDepositCompactL[F[_]: HKDFunctor]: Lens[PParamsHKD[F, E], F[CompactCoin]]
  def hkdEMaxL[F[_]: HKDFunctor]: Lens[PParamsHKD[F, E], F[EpochInterval]]
  def hkdNOptL[F[_]: HKDFunctor]: Lens[PParamsHKD[F, E], F[Unsigned[W16]]]
  def hkdA0L[F[_]: HKDFunctor]: Lens[PParamsHKD[F, E], F[NonNegativeInterval]]
  def hkdRhoL[F[_]: HKDFunctor]: Lens[PParamsHKD[F, E], F[UnitInterval]]
  def hkdTauL[F[_]: HKDFunctor]: Lens[PParamsHKD[F, E], F[UnitInterval]]
  def hkdMinPoolCostCompactL[F[_]: HKDFunctor]: Lens[PParamsHKD[F, E], F[CompactCoin]]

  /** `ppDG`: the decentralisation parameter, zero from Babbage. */
  def ppDG: Getter[PParams[E], UnitInterval]

  def ppProtocolVersionL: Lens[PParams[E], ProtVer]

  /** `ppMaxPledgeLeverageG`: the most a pool's stake may be over its pledge, or no limit. */
  def ppMaxPledgeLeverageG: Getter[PParams[E], MaxPledgeLeverage]

  /** `ppMinPoolMarginG`: the least margin a pool may declare. */
  def ppMinPoolMarginG: Getter[PParams[E], UnitInterval]

  /** `eraPParams` (`Core/PParams.hs:489`, cardano-ledger f649f975): the era's parameters, each
    * described ([[PParam]]), in the order of the record.
    */
  def eraPParams: Vector[PParam[E]]

  /** `emptyPParams`. */
  final def emptyPParams: PParams[E] = PParams[E](emptyPParamsIdentity)

  /** `emptyPParamsUpdate`. */
  final def emptyPParamsUpdate: PParamsUpdate[E] = PParamsUpdate[E](emptyPParamsStrictMaybe)

/** `class EraPParams era => AlonzoEraPParams era` (`eras/alonzo/impl/src/Cardano/Ledger/Alonzo/
  * PParams.hs:132-150`, cardano-ledger f649f975), without `hkdCoinsPerUTxOWordL`, which is Alonzo's
  * alone (`ExactEra AlonzoEra`) and comes with Alonzo's record in a class of its own.
  */
trait AlonzoEraPParams[E <: EraTag] extends EraPParams[E]:
  def hkdCostModelsL[F[_]: HKDFunctor]: Lens[PParamsHKD[F, E], F[CostModels]]
  def hkdPricesL[F[_]: HKDFunctor]: Lens[PParamsHKD[F, E], F[Prices]]
  def hkdMaxTxExUnitsL[F[_]: HKDFunctor]: Lens[PParamsHKD[F, E], F[ExUnits]]
  def hkdMaxBlockExUnitsL[F[_]: HKDFunctor]: Lens[PParamsHKD[F, E], F[ExUnits]]
  def hkdMaxValSizeL[F[_]: HKDFunctor]: Lens[PParamsHKD[F, E], F[Unsigned[W32]]]
  def hkdCollateralPercentageL[F[_]: HKDFunctor]: Lens[PParamsHKD[F, E], F[Unsigned[W16]]]
  def hkdMaxCollateralInputsL[F[_]: HKDFunctor]: Lens[PParamsHKD[F, E], F[Unsigned[W16]]]

/** `class AlonzoEraPParams era => BabbageEraPParams era`
  * (`eras/babbage/impl/src/Cardano/Ledger/Babbage/PParams.hs:76-77`, cardano-ledger f649f975).
  */
trait BabbageEraPParams[E <: EraTag] extends AlonzoEraPParams[E]:
  def hkdCoinsPerUTxOByteL[F[_]: HKDFunctor]: Lens[PParamsHKD[F, E], F[CoinPerByte]]

/** `class BabbageEraPParams era => ConwayEraPParams era` (`eras/conway/impl/src/Cardano/Ledger/
  * Conway/PParams.hs:172-197`, cardano-ledger f649f975).
  */
trait ConwayEraPParams[E <: EraTag] extends BabbageEraPParams[E]:
  /** `modifiedPPGroups`: the groups of the fields the update sets. */
  def modifiedPPGroups(ppu: PParamsUpdate[E]): SortedSet[PPGroups]

  /** `ppuWellFormed`: whether GOV takes the update of a parameter-change proposal. */
  def ppuWellFormed(pv: ProtVer, ppu: PParamsUpdate[E]): Boolean

  def hkdPoolVotingThresholdsL[F[_]: HKDFunctor]: Lens[PParamsHKD[F, E], F[PoolVotingThresholds]]
  def hkdDRepVotingThresholdsL[F[_]: HKDFunctor]: Lens[PParamsHKD[F, E], F[DRepVotingThresholds]]
  def hkdCommitteeMinSizeL[F[_]: HKDFunctor]: Lens[PParamsHKD[F, E], F[Unsigned[W16]]]
  def hkdCommitteeMaxTermLengthL[F[_]: HKDFunctor]: Lens[PParamsHKD[F, E], F[EpochInterval]]
  def hkdGovActionLifetimeL[F[_]: HKDFunctor]: Lens[PParamsHKD[F, E], F[EpochInterval]]
  def hkdGovActionDepositCompactL[F[_]: HKDFunctor]: Lens[PParamsHKD[F, E], F[CompactCoin]]
  def hkdDRepDepositCompactL[F[_]: HKDFunctor]: Lens[PParamsHKD[F, E], F[CompactCoin]]
  def hkdDRepActivityL[F[_]: HKDFunctor]: Lens[PParamsHKD[F, E], F[EpochInterval]]
  def hkdMinFeeRefScriptCostPerByteL[F[_]: HKDFunctor]
      : Lens[PParamsHKD[F, E], F[NonNegativeInterval]]
  def ppMaxRefScriptSizePerTxG: Getter[PParams[E], Unsigned[W32]]
  def ppMaxRefScriptSizePerBlockG: Getter[PParams[E], Unsigned[W32]]
  def ppRefScriptCostMultiplierG: Getter[PParams[E], PositiveInterval]
  def ppRefScriptCostStrideG: Getter[PParams[E], NonZero[Unsigned[W32]]]

object EraPParams:
  given conway: ConwayEraPParams[Conway] = ConwayEraPParamsInstance
  given dijkstra: DijkstraEraPParams[Dijkstra] = DijkstraEraPParamsInstance

  // The reference's lenses into the parameters, each `ppLensHKD` and the class's field at
  // `Identity`. Those reading a coin are the compact ones: the reference's `Coin` lenses add
  // `partialCompactCoinL`, whose setter is partial; a reader takes `toCoin` of the compact coin,
  // which is its getter.

  /** `ppTxFeePerByteL` (`Core/PParams.hs:516-517`, cardano-ledger f649f975). */
  def ppTxFeePerByteL[E <: EraTag](using p: EraPParams[E]): Lens[PParams[E], CoinPerByte] =
    PParams.lensHKD[E].andThen(p.hkdTxFeePerByteL[Identity])

  /** `ppTxFeeFixedCompactL` (`Core/PParams.hs:532-533`, cardano-ledger f649f975). */
  def ppTxFeeFixedCompactL[E <: EraTag](using p: EraPParams[E]): Lens[PParams[E], CompactCoin] =
    PParams.lensHKD[E].andThen(p.hkdTxFeeFixedCompactL[Identity])

  /** `ppMaxTxSizeL` (`Core/PParams.hs:540-541`, cardano-ledger f649f975). */
  def ppMaxTxSizeL[E <: EraTag](using p: EraPParams[E]): Lens[PParams[E], Unsigned[W32]] =
    PParams.lensHKD[E].andThen(p.hkdMaxTxSizeL[Identity])

  /** `ppKeyDepositCompactL` (`Core/PParams.hs:552-553`, cardano-ledger f649f975). */
  def ppKeyDepositCompactL[E <: EraTag](using p: EraPParams[E]): Lens[PParams[E], CompactCoin] =
    PParams.lensHKD[E].andThen(p.hkdKeyDepositCompactL[Identity])

  /** `ppPoolDepositCompactL` (`Core/PParams.hs:560-561`, cardano-ledger f649f975). */
  def ppPoolDepositCompactL[E <: EraTag](using p: EraPParams[E]): Lens[PParams[E], CompactCoin] =
    PParams.lensHKD[E].andThen(p.hkdPoolDepositCompactL[Identity])

  // The descriptions of the parameters every era has (`Shelley/PParams.hs:338-497`, cardano-ledger
  // f649f975), but those the reference confines to a range of eras (`ppD`, `ppExtraEntropy`,
  // `ppProtocolVersion` and `ppMinUTxOValue`), whose classes are not here.

  /** `ppTxFeePerByte` (`Shelley/PParams.hs:338-345`, cardano-ledger f649f975). */
  def ppTxFeePerByte[E <: EraTag](using p: EraPParams[E]): PParam[E] =
    PParam.field("txFeePerByte", 0, p.hkdTxFeePerByteL[Identity], p.hkdTxFeePerByteL[StrictMaybe])

  /** `ppTxFeeFixed` (`Shelley/PParams.hs:351-358`, cardano-ledger f649f975). */
  def ppTxFeeFixed[E <: EraTag](using p: EraPParams[E]): PParam[E] =
    PParam.field(
      "txFeeFixed",
      1,
      p.hkdTxFeeFixedCompactL[Identity],
      p.hkdTxFeeFixedCompactL[StrictMaybe]
    )

  /** `ppMaxBBSize` (`Shelley/PParams.hs:364-371`, cardano-ledger f649f975). */
  def ppMaxBBSize[E <: EraTag](using p: EraPParams[E]): PParam[E] =
    PParam.field("maxBlockBodySize", 2, p.hkdMaxBBSizeL[Identity], p.hkdMaxBBSizeL[StrictMaybe])

  /** `ppMaxTxSize` (`Shelley/PParams.hs:373-380`, cardano-ledger f649f975). */
  def ppMaxTxSize[E <: EraTag](using p: EraPParams[E]): PParam[E] =
    PParam.field("maxTxSize", 3, p.hkdMaxTxSizeL[Identity], p.hkdMaxTxSizeL[StrictMaybe])

  /** `ppMaxBHSize` (`Shelley/PParams.hs:382-389`, cardano-ledger f649f975). */
  def ppMaxBHSize[E <: EraTag](using p: EraPParams[E]): PParam[E] =
    PParam.field("maxBlockHeaderSize", 4, p.hkdMaxBHSizeL[Identity], p.hkdMaxBHSizeL[StrictMaybe])

  /** `ppKeyDeposit` (`Shelley/PParams.hs:391-398`, cardano-ledger f649f975). */
  def ppKeyDeposit[E <: EraTag](using p: EraPParams[E]): PParam[E] =
    PParam.field(
      "stakeAddressDeposit",
      5,
      p.hkdKeyDepositCompactL[Identity],
      p.hkdKeyDepositCompactL[StrictMaybe]
    )

  /** `ppPoolDeposit` (`Shelley/PParams.hs:400-407`, cardano-ledger f649f975). */
  def ppPoolDeposit[E <: EraTag](using p: EraPParams[E]): PParam[E] =
    PParam.field(
      "stakePoolDeposit",
      6,
      p.hkdPoolDepositCompactL[Identity],
      p.hkdPoolDepositCompactL[StrictMaybe]
    )

  /** `ppEMax` (`Shelley/PParams.hs:409-416`, cardano-ledger f649f975). */
  def ppEMax[E <: EraTag](using p: EraPParams[E]): PParam[E] =
    PParam.field("poolRetireMaxEpoch", 7, p.hkdEMaxL[Identity], p.hkdEMaxL[StrictMaybe])

  /** `ppNOpt` (`Shelley/PParams.hs:418-425`, cardano-ledger f649f975). */
  def ppNOpt[E <: EraTag](using p: EraPParams[E]): PParam[E] =
    PParam.field("stakePoolTargetNum", 8, p.hkdNOptL[Identity], p.hkdNOptL[StrictMaybe])

  /** `ppA0` (`Shelley/PParams.hs:426-433`, cardano-ledger f649f975). */
  def ppA0[E <: EraTag](using p: EraPParams[E]): PParam[E] =
    PParam.field("poolPledgeInfluence", 9, p.hkdA0L[Identity], p.hkdA0L[StrictMaybe])

  /** `ppRho` (`Shelley/PParams.hs:436-443`, cardano-ledger f649f975). */
  def ppRho[E <: EraTag](using p: EraPParams[E]): PParam[E] =
    PParam.field("monetaryExpansion", 10, p.hkdRhoL[Identity], p.hkdRhoL[StrictMaybe])

  /** `ppTau` (`Shelley/PParams.hs:445-452`, cardano-ledger f649f975). */
  def ppTau[E <: EraTag](using p: EraPParams[E]): PParam[E] =
    PParam.field("treasuryCut", 11, p.hkdTauL[Identity], p.hkdTauL[StrictMaybe])

  /** `ppMinPoolCost` (`Shelley/PParams.hs:490-497`, cardano-ledger f649f975). */
  def ppMinPoolCost[E <: EraTag](using p: EraPParams[E]): PParam[E] =
    PParam.field(
      "minPoolCost",
      16,
      p.hkdMinPoolCostCompactL[Identity],
      p.hkdMinPoolCostCompactL[StrictMaybe]
    )

object AlonzoEraPParams:
  given conway: AlonzoEraPParams[Conway] = ConwayEraPParamsInstance
  given dijkstra: AlonzoEraPParams[Dijkstra] = DijkstraEraPParamsInstance

  /** `ppPricesL` (`Alonzo/PParams.hs:160-161`, cardano-ledger f649f975). */
  def ppPricesL[E <: EraTag](using p: AlonzoEraPParams[E]): Lens[PParams[E], Prices] =
    PParams.lensHKD[E].andThen(p.hkdPricesL[Identity])

  /** `ppMaxTxExUnitsL` (`Alonzo/PParams.hs:163-164`, cardano-ledger f649f975). */
  def ppMaxTxExUnitsL[E <: EraTag](using p: AlonzoEraPParams[E]): Lens[PParams[E], ExUnits] =
    PParams.lensHKD[E].andThen(p.hkdMaxTxExUnitsL[Identity])

  /** `ppMaxBlockExUnitsL` (`Alonzo/PParams.hs:164-165`, cardano-ledger 9df9f98c). */
  def ppMaxBlockExUnitsL[E <: EraTag](using p: AlonzoEraPParams[E]): Lens[PParams[E], ExUnits] =
    PParams.lensHKD[E].andThen(p.hkdMaxBlockExUnitsL[Identity])

  /** `ppMaxValSizeL` (`Alonzo/PParams.hs:169-170`, cardano-ledger f649f975). */
  def ppMaxValSizeL[E <: EraTag](using p: AlonzoEraPParams[E]): Lens[PParams[E], Unsigned[W32]] =
    PParams.lensHKD[E].andThen(p.hkdMaxValSizeL[Identity])

  /** `ppCollateralPercentageL` (`Alonzo/PParams.hs:172-173`, cardano-ledger f649f975). */
  def ppCollateralPercentageL[E <: EraTag](using
      p: AlonzoEraPParams[E]
  ): Lens[PParams[E], Unsigned[W16]] =
    PParams.lensHKD[E].andThen(p.hkdCollateralPercentageL[Identity])

  // The descriptions of Alonzo's parameters (`Alonzo/PParams.hs:667-728`, cardano-ledger f649f975),
  // but `ppCoinsPerUTxOWord`, Alonzo's alone, whose class is not here.

  /** `ppCostModels` (`Alonzo/PParams.hs:667-674`, cardano-ledger f649f975). */
  def ppCostModels[E <: EraTag](using p: AlonzoEraPParams[E]): PParam[E] =
    PParam.field("costModels", 18, p.hkdCostModelsL[Identity], p.hkdCostModelsL[StrictMaybe])

  /** `ppPrices` (`Alonzo/PParams.hs:676-683`, cardano-ledger f649f975). */
  def ppPrices[E <: EraTag](using p: AlonzoEraPParams[E]): PParam[E] =
    PParam.field("executionUnitPrices", 19, p.hkdPricesL[Identity], p.hkdPricesL[StrictMaybe])

  /** `ppMaxTxExUnits` (`Alonzo/PParams.hs:685-692`, cardano-ledger f649f975). */
  def ppMaxTxExUnits[E <: EraTag](using p: AlonzoEraPParams[E]): PParam[E] =
    PParam.field(
      "maxTxExecutionUnits",
      20,
      p.hkdMaxTxExUnitsL[Identity],
      p.hkdMaxTxExUnitsL[StrictMaybe]
    )

  /** `ppMaxBlockExUnits` (`Alonzo/PParams.hs:694-701`, cardano-ledger f649f975). */
  def ppMaxBlockExUnits[E <: EraTag](using p: AlonzoEraPParams[E]): PParam[E] =
    PParam.field(
      "maxBlockExecutionUnits",
      21,
      p.hkdMaxBlockExUnitsL[Identity],
      p.hkdMaxBlockExUnitsL[StrictMaybe]
    )

  /** `ppMaxValSize` (`Alonzo/PParams.hs:703-710`, cardano-ledger f649f975). */
  def ppMaxValSize[E <: EraTag](using p: AlonzoEraPParams[E]): PParam[E] =
    PParam.field("maxValueSize", 22, p.hkdMaxValSizeL[Identity], p.hkdMaxValSizeL[StrictMaybe])

  /** `ppCollateralPercentage` (`Alonzo/PParams.hs:712-719`, cardano-ledger f649f975). */
  def ppCollateralPercentage[E <: EraTag](using p: AlonzoEraPParams[E]): PParam[E] =
    PParam.field(
      "collateralPercentage",
      23,
      p.hkdCollateralPercentageL[Identity],
      p.hkdCollateralPercentageL[StrictMaybe]
    )

  /** `ppMaxCollateralInputs` (`Alonzo/PParams.hs:721-728`, cardano-ledger f649f975). */
  def ppMaxCollateralInputs[E <: EraTag](using p: AlonzoEraPParams[E]): PParam[E] =
    PParam.field(
      "maxCollateralInputs",
      24,
      p.hkdMaxCollateralInputsL[Identity],
      p.hkdMaxCollateralInputsL[StrictMaybe]
    )

object BabbageEraPParams:
  given conway: BabbageEraPParams[Conway] = ConwayEraPParamsInstance
  given dijkstra: BabbageEraPParams[Dijkstra] = DijkstraEraPParamsInstance

  /** `ppCoinsPerUTxOByteL` (`Babbage/PParams.hs:79-81`, cardano-ledger f649f975). */
  def ppCoinsPerUTxOByteL[E <: EraTag](using
      p: BabbageEraPParams[E]
  ): Lens[PParams[E], CoinPerByte] =
    PParams.lensHKD[E].andThen(p.hkdCoinsPerUTxOByteL[Identity])

  /** `ppCoinsPerUTxOByte` (`Babbage/PParams.hs:405-412`, cardano-ledger f649f975). */
  def ppCoinsPerUTxOByte[E <: EraTag](using p: BabbageEraPParams[E]): PParam[E] =
    PParam.field(
      "utxoCostPerByte",
      17,
      p.hkdCoinsPerUTxOByteL[Identity],
      p.hkdCoinsPerUTxOByteL[StrictMaybe]
    )

object ConwayEraPParams:
  given conway: ConwayEraPParams[Conway] = ConwayEraPParamsInstance
  given dijkstra: ConwayEraPParams[Dijkstra] = DijkstraEraPParamsInstance

  /** `ppGovActionDepositCompactL` (`Conway/PParams.hs:240-242`, cardano-ledger f649f975). */
  def ppGovActionDepositCompactL[E <: EraTag](using
      p: ConwayEraPParams[E]
  ): Lens[PParams[E], CompactCoin] =
    PParams.lensHKD[E].andThen(p.hkdGovActionDepositCompactL[Identity])

  /** `ppGovActionLifetimeL` (`Conway/PParams.hs:237-238`, cardano-ledger f649f975). */
  def ppGovActionLifetimeL[E <: EraTag](using
      p: ConwayEraPParams[E]
  ): Lens[PParams[E], EpochInterval] =
    PParams.lensHKD[E].andThen(p.hkdGovActionLifetimeL[Identity])

  /** `ppDRepDepositCompactL` (`Conway/PParams.hs:247-248`, cardano-ledger f649f975). */
  def ppDRepDepositCompactL[E <: EraTag](using
      p: ConwayEraPParams[E]
  ): Lens[PParams[E], CompactCoin] =
    PParams.lensHKD[E].andThen(p.hkdDRepDepositCompactL[Identity])

  /** `ppMinFeeRefScriptCostPerByteL` (`Conway/PParams.hs:256-258`, cardano-ledger f649f975). */
  def ppMinFeeRefScriptCostPerByteL[E <: EraTag](using
      p: ConwayEraPParams[E]
  ): Lens[PParams[E], NonNegativeInterval] =
    PParams.lensHKD[E].andThen(p.hkdMinFeeRefScriptCostPerByteL[Identity])

  // The descriptions of Conway's parameters (`Conway/PParams.hs:1266-1354`, cardano-ledger
  // f649f975).

  /** `ppPoolVotingThresholds` (`Conway/PParams.hs:1266-1273`, cardano-ledger f649f975). */
  def ppPoolVotingThresholds[E <: EraTag](using p: ConwayEraPParams[E]): PParam[E] =
    PParam.field(
      "poolVotingThresholds",
      25,
      p.hkdPoolVotingThresholdsL[Identity],
      p.hkdPoolVotingThresholdsL[StrictMaybe]
    )

  /** `ppDRepVotingThresholds` (`Conway/PParams.hs:1275-1282`, cardano-ledger f649f975). */
  def ppDRepVotingThresholds[E <: EraTag](using p: ConwayEraPParams[E]): PParam[E] =
    PParam.field(
      "dRepVotingThresholds",
      26,
      p.hkdDRepVotingThresholdsL[Identity],
      p.hkdDRepVotingThresholdsL[StrictMaybe]
    )

  /** `ppCommitteeMinSize` (`Conway/PParams.hs:1284-1291`, cardano-ledger f649f975). */
  def ppCommitteeMinSize[E <: EraTag](using p: ConwayEraPParams[E]): PParam[E] =
    PParam.field(
      "committeeMinSize",
      27,
      p.hkdCommitteeMinSizeL[Identity],
      p.hkdCommitteeMinSizeL[StrictMaybe]
    )

  /** `ppCommitteeMaxTermLength` (`Conway/PParams.hs:1293-1300`, cardano-ledger f649f975). */
  def ppCommitteeMaxTermLength[E <: EraTag](using p: ConwayEraPParams[E]): PParam[E] =
    PParam.field(
      "committeeMaxTermLength",
      28,
      p.hkdCommitteeMaxTermLengthL[Identity],
      p.hkdCommitteeMaxTermLengthL[StrictMaybe]
    )

  /** `ppGovActionLifetime` (`Conway/PParams.hs:1302-1309`, cardano-ledger f649f975). */
  def ppGovActionLifetime[E <: EraTag](using p: ConwayEraPParams[E]): PParam[E] =
    PParam.field(
      "govActionLifetime",
      29,
      p.hkdGovActionLifetimeL[Identity],
      p.hkdGovActionLifetimeL[StrictMaybe]
    )

  /** `ppGovActionDeposit` (`Conway/PParams.hs:1311-1318`, cardano-ledger f649f975). */
  def ppGovActionDeposit[E <: EraTag](using p: ConwayEraPParams[E]): PParam[E] =
    PParam.field(
      "govActionDeposit",
      30,
      p.hkdGovActionDepositCompactL[Identity],
      p.hkdGovActionDepositCompactL[StrictMaybe]
    )

  /** `ppDRepDeposit` (`Conway/PParams.hs:1320-1327`, cardano-ledger f649f975). */
  def ppDRepDeposit[E <: EraTag](using p: ConwayEraPParams[E]): PParam[E] =
    PParam.field(
      "dRepDeposit",
      31,
      p.hkdDRepDepositCompactL[Identity],
      p.hkdDRepDepositCompactL[StrictMaybe]
    )

  /** `ppDRepActivity` (`Conway/PParams.hs:1329-1336`, cardano-ledger f649f975). */
  def ppDRepActivity[E <: EraTag](using p: ConwayEraPParams[E]): PParam[E] =
    PParam.field("dRepActivity", 32, p.hkdDRepActivityL[Identity], p.hkdDRepActivityL[StrictMaybe])

  /** `ppMinFeeRefScriptCostPerByte` (`Conway/PParams.hs:1338-1345`, cardano-ledger f649f975). */
  def ppMinFeeRefScriptCostPerByte[E <: EraTag](using p: ConwayEraPParams[E]): PParam[E] =
    PParam.field(
      "minFeeRefScriptCostPerByte",
      33,
      p.hkdMinFeeRefScriptCostPerByteL[Identity],
      p.hkdMinFeeRefScriptCostPerByteL[StrictMaybe]
    )

  /** `ppGovProtocolVersion` (`Conway/PParams.hs:1347-1354`, cardano-ledger f649f975): the protocol
    * version, which an update cannot set.
    */
  def ppGovProtocolVersion[E <: EraTag](using p: ConwayEraPParams[E]): PParam[E] =
    PParam[E, ProtVer]("protocolVersion", p.ppProtocolVersionL, None)

/** Conway's instances (`instance EraPParams ConwayEra` to `instance ConwayEraPParams ConwayEra`,
  * `Conway/PParams.hs:825-984`, cardano-ledger f649f975).
  */
private object ConwayEraPParamsInstance extends ConwayEraPParams[Conway]:
  import conway.ConwayPParams
  import monocle.macros.GenLens

  private type R[F[_]] = ConwayPParams[F]

  def applyPPUpdates(pp: PParams[Conway], ppu: PParamsUpdate[Conway]): PParams[Conway] =
    PParams[Conway](ConwayPParams.applyUpdates(pp.hkd, ppu.hkd))

  def emptyPParamsIdentity: ConwayPParams[Identity] = ConwayPParams.empty

  def emptyPParamsStrictMaybe: ConwayPParams[StrictMaybe] = ConwayPParams.emptyUpdate

  def hkdTxFeePerByteL[F[_]: HKDFunctor]: Lens[R[F], F[CoinPerByte]] =
    GenLens[R[F]](_.txFeePerByte).andThen(THKD.value)
  def hkdTxFeeFixedCompactL[F[_]: HKDFunctor]: Lens[R[F], F[CompactCoin]] =
    GenLens[R[F]](_.txFeeFixed).andThen(THKD.value)
  def hkdMaxBBSizeL[F[_]: HKDFunctor]: Lens[R[F], F[Unsigned[W32]]] =
    GenLens[R[F]](_.maxBBSize).andThen(THKD.value)
  def hkdMaxTxSizeL[F[_]: HKDFunctor]: Lens[R[F], F[Unsigned[W32]]] =
    GenLens[R[F]](_.maxTxSize).andThen(THKD.value)
  def hkdMaxBHSizeL[F[_]: HKDFunctor]: Lens[R[F], F[Unsigned[W16]]] =
    GenLens[R[F]](_.maxBHSize).andThen(THKD.value)
  def hkdKeyDepositCompactL[F[_]: HKDFunctor]: Lens[R[F], F[CompactCoin]] =
    GenLens[R[F]](_.keyDeposit).andThen(THKD.value)
  def hkdPoolDepositCompactL[F[_]: HKDFunctor]: Lens[R[F], F[CompactCoin]] =
    GenLens[R[F]](_.poolDeposit).andThen(THKD.value)
  def hkdEMaxL[F[_]: HKDFunctor]: Lens[R[F], F[EpochInterval]] =
    GenLens[R[F]](_.eMax).andThen(THKD.value)
  def hkdNOptL[F[_]: HKDFunctor]: Lens[R[F], F[Unsigned[W16]]] =
    GenLens[R[F]](_.nOpt).andThen(THKD.value)
  def hkdA0L[F[_]: HKDFunctor]: Lens[R[F], F[NonNegativeInterval]] =
    GenLens[R[F]](_.a0).andThen(THKD.value)
  def hkdRhoL[F[_]: HKDFunctor]: Lens[R[F], F[UnitInterval]] =
    GenLens[R[F]](_.rho).andThen(THKD.value)
  def hkdTauL[F[_]: HKDFunctor]: Lens[R[F], F[UnitInterval]] =
    GenLens[R[F]](_.tau).andThen(THKD.value)
  def hkdMinPoolCostCompactL[F[_]: HKDFunctor]: Lens[R[F], F[CompactCoin]] =
    GenLens[R[F]](_.minPoolCost).andThen(THKD.value)

  /** `ppDG = L.to (const minBound)` (`Conway/PParams.hs:856`, cardano-ledger f649f975). */
  val ppDG: Getter[PParams[Conway], UnitInterval] = Getter(_ => UnitInterval.zero)

  /** `ppLensHKD . lens cppProtocolVersion ...` (`Conway/PParams.hs:854`, cardano-ledger f649f975).
    */
  val ppProtocolVersionL: Lens[PParams[Conway], ProtVer] =
    PParams.lensHKD[Conway].andThen(GenLens[R[Identity]](_.protocolVersion))

  /** The class's default up to Conway, `L.to (const (MaxPledgeLeverage SNothing))`
    * (`Core/PParams.hs:510-516`, cardano-ledger 9df9f98c): no limit.
    */
  val ppMaxPledgeLeverageG: Getter[PParams[Conway], MaxPledgeLeverage] =
    Getter(_ => MaxPledgeLeverage.none)

  /** The class's default up to Conway, `L.to (const minBound)` (`Core/PParams.hs:518-524`,
    * cardano-ledger 9df9f98c).
    */
  val ppMinPoolMarginG: Getter[PParams[Conway], UnitInterval] = Getter(_ => UnitInterval.zero)

  /** `eraPParams` (`Conway/PParams.hs:862-894`, cardano-ledger f649f975). */
  lazy val eraPParams: Vector[PParam[Conway]] =
    import EraPParams.*, AlonzoEraPParams.*, BabbageEraPParams.*, ConwayEraPParams.*
    given ConwayEraPParams[Conway] = this
    Vector(
      ppTxFeePerByte,
      ppTxFeeFixed,
      ppMaxBBSize,
      ppMaxTxSize,
      ppMaxBHSize,
      ppKeyDeposit,
      ppPoolDeposit,
      ppEMax,
      ppNOpt,
      ppA0,
      ppRho,
      ppTau,
      ppGovProtocolVersion,
      ppMinPoolCost,
      ppCoinsPerUTxOByte,
      ppCostModels,
      ppPrices,
      ppMaxTxExUnits,
      ppMaxBlockExUnits,
      ppMaxValSize,
      ppCollateralPercentage,
      ppMaxCollateralInputs,
      ppPoolVotingThresholds,
      ppDRepVotingThresholds,
      ppCommitteeMinSize,
      ppCommitteeMaxTermLength,
      ppGovActionLifetime,
      ppGovActionDeposit,
      ppDRepDeposit,
      ppDRepActivity,
      ppMinFeeRefScriptCostPerByte
    )

  def hkdCostModelsL[F[_]: HKDFunctor]: Lens[R[F], F[CostModels]] =
    GenLens[R[F]](_.costModels).andThen(THKD.value)
  def hkdPricesL[F[_]: HKDFunctor]: Lens[R[F], F[Prices]] =
    GenLens[R[F]](_.prices).andThen(THKD.value)

  /** `hkdMap unOrdExUnits` over the field, and `hkdMap OrdExUnits` back
    * (`Conway/PParams.hs:915-923`, cardano-ledger f649f975).
    */
  def hkdMaxTxExUnitsL[F[_]: HKDFunctor]: Lens[R[F], F[ExUnits]] =
    GenLens[R[F]](_.maxTxExUnits).andThen(THKD.value).andThen(ordExUnits[F])
  def hkdMaxBlockExUnitsL[F[_]: HKDFunctor]: Lens[R[F], F[ExUnits]] =
    GenLens[R[F]](_.maxBlockExUnits).andThen(THKD.value).andThen(ordExUnits[F])

  private def ordExUnits[F[_]](using f: HKDFunctor[F]): Lens[F[OrdExUnits], F[ExUnits]] =
    Lens[F[OrdExUnits], F[ExUnits]](f.hkdMap(_.unOrdExUnits))(u => _ => f.hkdMap(OrdExUnits(_))(u))
  def hkdMaxValSizeL[F[_]: HKDFunctor]: Lens[R[F], F[Unsigned[W32]]] =
    GenLens[R[F]](_.maxValSize).andThen(THKD.value)
  def hkdCollateralPercentageL[F[_]: HKDFunctor]: Lens[R[F], F[Unsigned[W16]]] =
    GenLens[R[F]](_.collateralPercentage).andThen(THKD.value)
  def hkdMaxCollateralInputsL[F[_]: HKDFunctor]: Lens[R[F], F[Unsigned[W16]]] =
    GenLens[R[F]](_.maxCollateralInputs).andThen(THKD.value)

  def hkdCoinsPerUTxOByteL[F[_]: HKDFunctor]: Lens[R[F], F[CoinPerByte]] =
    GenLens[R[F]](_.coinsPerUTxOByte).andThen(THKD.value)

  def modifiedPPGroups(ppu: PParamsUpdate[Conway]): SortedSet[PPGroups] =
    ConwayPParams.modifiedGroups(ppu.hkd)

  /** `ppuWellFormed` (`Conway/PParams.hs:935-962`, cardano-ledger f649f975): no set field among the
    * sizes, the collateral percentage, the committee's term, the actions' lifetime and the deposits
    * is zero; the price of a byte of UTxO is not zero after the bootstrap phase (version 9); the
    * update sets a field; and from version 11 the desired number of pools is not zero.
    */
  def ppuWellFormed(pv: ProtVer, ppu: PParamsUpdate[Conway]): Boolean =
    val u = ppu.hkd
    def valid[A](field: Option[A])(p: A => Boolean): Boolean = field.forall(p)
    def nonZeroWord[W <: Unsigned.Width](field: Option[Unsigned[W]]): Boolean =
      valid(field)(_.value != 0)
    def nonZeroEpochs(field: Option[EpochInterval]): Boolean = valid(field)(_.toLong != 0L)
    def nonZeroCoin(field: Option[CompactCoin]): Boolean = valid(field)(_.toBigInt != 0)
    nonZeroWord(u.maxBBSize.unTHKD) &&
    nonZeroWord(u.maxTxSize.unTHKD) &&
    nonZeroWord(u.maxBHSize.unTHKD) &&
    nonZeroWord(u.maxValSize.unTHKD) &&
    nonZeroWord(u.collateralPercentage.unTHKD) &&
    nonZeroEpochs(u.committeeMaxTermLength.unTHKD) &&
    nonZeroEpochs(u.govActionLifetime.unTHKD) &&
    nonZeroCoin(u.poolDeposit.unTHKD) &&
    nonZeroCoin(u.govActionDeposit.unTHKD) &&
    nonZeroCoin(u.drepDeposit.unTHKD) &&
    (pv.pvMajor.value == 9 || nonZeroCoin(u.coinsPerUTxOByte.unTHKD.map(_.unCoinPerByte))) &&
    u != ConwayPParams.emptyUpdate &&
    (pv.pvMajor.value < 11 || nonZeroWord(u.nOpt.unTHKD))

  def hkdPoolVotingThresholdsL[F[_]: HKDFunctor]: Lens[R[F], F[PoolVotingThresholds]] =
    GenLens[R[F]](_.poolVotingThresholds).andThen(THKD.value)
  def hkdDRepVotingThresholdsL[F[_]: HKDFunctor]: Lens[R[F], F[DRepVotingThresholds]] =
    GenLens[R[F]](_.drepVotingThresholds).andThen(THKD.value)
  def hkdCommitteeMinSizeL[F[_]: HKDFunctor]: Lens[R[F], F[Unsigned[W16]]] =
    GenLens[R[F]](_.committeeMinSize).andThen(THKD.value)
  def hkdCommitteeMaxTermLengthL[F[_]: HKDFunctor]: Lens[R[F], F[EpochInterval]] =
    GenLens[R[F]](_.committeeMaxTermLength).andThen(THKD.value)
  def hkdGovActionLifetimeL[F[_]: HKDFunctor]: Lens[R[F], F[EpochInterval]] =
    GenLens[R[F]](_.govActionLifetime).andThen(THKD.value)
  def hkdGovActionDepositCompactL[F[_]: HKDFunctor]: Lens[R[F], F[CompactCoin]] =
    GenLens[R[F]](_.govActionDeposit).andThen(THKD.value)
  def hkdDRepDepositCompactL[F[_]: HKDFunctor]: Lens[R[F], F[CompactCoin]] =
    GenLens[R[F]](_.drepDeposit).andThen(THKD.value)
  def hkdDRepActivityL[F[_]: HKDFunctor]: Lens[R[F], F[EpochInterval]] =
    GenLens[R[F]](_.drepActivity).andThen(THKD.value)
  def hkdMinFeeRefScriptCostPerByteL[F[_]: HKDFunctor]: Lens[R[F], F[NonNegativeInterval]] =
    GenLens[R[F]](_.minFeeRefScriptCostPerByte).andThen(THKD.value)

  /** `200 * 1024` (`Conway/PParams.hs:981`, cardano-ledger f649f975). */
  val ppMaxRefScriptSizePerTxG: Getter[PParams[Conway], Unsigned[W32]] =
    Getter(_ => Unsigned.lit[W32](200 * 1024))

  /** `1024 * 1024` (`Conway/PParams.hs:982`, cardano-ledger f649f975). */
  val ppMaxRefScriptSizePerBlockG: Getter[PParams[Conway], Unsigned[W32]] =
    Getter(_ => Unsigned.lit[W32](1024 * 1024))

  /** `fromJust $ boundRational 1.2` (`Conway/PParams.hs:983`, cardano-ledger f649f975). */
  val ppRefScriptCostMultiplierG: Getter[PParams[Conway], PositiveInterval] =
    Getter(_ => PositiveInterval.oneAndAFifth)

  /** `knownNonZeroBounded @25_600` (`Conway/PParams.hs:984`, cardano-ledger f649f975). */
  val ppRefScriptCostStrideG: Getter[PParams[Conway], NonZero[Unsigned[W32]]] =
    Getter(_ => NonZero.unsafe(Unsigned.lit[W32](25_600)))
