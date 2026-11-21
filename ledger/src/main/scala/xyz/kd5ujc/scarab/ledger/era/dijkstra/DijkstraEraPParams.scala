// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.dijkstra

import monocle.{Getter, Lens}
import monocle.macros.GenLens
import scala.collection.immutable.SortedSet
import xyz.kd5ujc.scarab.ledger.Unsigned
import xyz.kd5ujc.scarab.ledger.Unsigned.Width.{W16, W32}
import xyz.kd5ujc.scarab.ledger.era.*
import xyz.kd5ujc.scarab.ledger.era.conway.{DRepVotingThresholds, PoolVotingThresholds}
import xyz.kd5ujc.scarab.ledger.state.{Coin, CompactCoin, Dijkstra, EpochInterval, EraTag, NonZero}

/** `class ConwayEraPParams era => DijkstraEraPParams era` (cardano-ledger 9df9f98c
  * `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/PParams.hs:1318-1341`): the fields Dijkstra
  * adds.
  */
trait DijkstraEraPParams[E <: EraTag] extends ConwayEraPParams[E]:
  def hkdMaxRefScriptSizePerBlockL[F[_]: HKDFunctor]: Lens[PParamsHKD[F, E], F[Unsigned[W32]]]
  def hkdMaxRefScriptSizePerTxL[F[_]: HKDFunctor]: Lens[PParamsHKD[F, E], F[Unsigned[W32]]]
  def hkdRefScriptCostStrideL[F[_]: HKDFunctor]: Lens[PParamsHKD[F, E], F[NonZero[Unsigned[W32]]]]
  def hkdRefScriptCostMultiplierL[F[_]: HKDFunctor]: Lens[PParamsHKD[F, E], F[PositiveInterval]]
  def hkdMaxPledgeLeverageL[F[_]: HKDFunctor]: Lens[PParamsHKD[F, E], F[MaxPledgeLeverage]]
  def hkdMinPoolMarginL[F[_]: HKDFunctor]: Lens[PParamsHKD[F, E], F[UnitInterval]]
  def hkdLeiosAnnouncementPeriodLengthL[F[_]: HKDFunctor]: Lens[PParamsHKD[F, E], F[Milliseconds32]]
  def hkdLeiosVotePeriodLengthL[F[_]: HKDFunctor]: Lens[PParamsHKD[F, E], F[Milliseconds32]]
  def hkdLeiosDiffusionPeriodLengthL[F[_]: HKDFunctor]: Lens[PParamsHKD[F, E], F[Milliseconds32]]
  def hkdLeiosCommitteeSizeL[F[_]: HKDFunctor]: Lens[PParamsHKD[F, E], F[Unsigned[W16]]]
  def hkdLeiosQuorumStakeThresholdL[F[_]: HKDFunctor]: Lens[PParamsHKD[F, E], F[UnitInterval]]
  def hkdMaxEndorserBlockReferencesSizeL[F[_]: HKDFunctor]: Lens[PParamsHKD[F, E], F[Unsigned[W32]]]
  def hkdMaxEndorserBlockTxsSizeL[F[_]: HKDFunctor]: Lens[PParamsHKD[F, E], F[Unsigned[W32]]]
  def hkdMaxEndorserBlockExUnitsL[F[_]: HKDFunctor]: Lens[PParamsHKD[F, E], F[OrdExUnits]]
  def hkdMaxRefScriptSizePerEndorserBlockL[F[_]: HKDFunctor]
      : Lens[PParamsHKD[F, E], F[Unsigned[W32]]]
  def hkdPerasMinCandidateBlockAgeL[F[_]: HKDFunctor]: Lens[PParamsHKD[F, E], F[SlotInterval]]
  def hkdPerasHealingFactorL[F[_]: HKDFunctor]: Lens[PParamsHKD[F, E], F[PositiveInterval]]
  def hkdPerasCertBoostL[F[_]: HKDFunctor]: Lens[PParamsHKD[F, E], F[Unsigned[W16]]]
  def hkdPerasTargetCommitteeSizeL[F[_]: HKDFunctor]: Lens[PParamsHKD[F, E], F[Unsigned[W16]]]
  def hkdPerasBootstrapRoundL[F[_]: HKDFunctor]: Lens[PParamsHKD[F, E], F[Option[Unsigned[W32]]]]
  def hkdPerasQuorumThresholdSafetyMarginL[F[_]: HKDFunctor]
      : Lens[PParamsHKD[F, E], F[UnitInterval]]
  def hkdRefInputsCostPerMultiAssetPolicyL[F[_]: HKDFunctor]: Lens[PParamsHKD[F, E], F[Coin]]
  def hkdRefInputsCostPerDatumByteL[F[_]: HKDFunctor]: Lens[PParamsHKD[F, E], F[CoinPerByte]]

object DijkstraEraPParams:
  given dijkstra: DijkstraEraPParams[Dijkstra] = DijkstraEraPParamsInstance

  // The descriptions of the parameters Dijkstra adds (`Dijkstra/PParams.hs:798-1123`, cardano-ledger
  // 9df9f98c).

  /** `ppMaxRefScriptSizePerBlock` (`Dijkstra/PParams.hs:798-810`, cardano-ledger 9df9f98c). */
  def ppMaxRefScriptSizePerBlock: PParam[Dijkstra] =
    PParam.field(
      "maxRefScriptSizePerBlock",
      34,
      dijkstra.hkdMaxRefScriptSizePerBlockL[Identity],
      dijkstra.hkdMaxRefScriptSizePerBlockL[StrictMaybe]
    )

  /** `ppMaxRefScriptSizePerTx` (`Dijkstra/PParams.hs:812-824`, cardano-ledger 9df9f98c). */
  def ppMaxRefScriptSizePerTx: PParam[Dijkstra] =
    PParam.field(
      "maxRefScriptSizePerTx",
      35,
      dijkstra.hkdMaxRefScriptSizePerTxL[Identity],
      dijkstra.hkdMaxRefScriptSizePerTxL[StrictMaybe]
    )

  /** `ppRefScriptCostStride` (`Dijkstra/PParams.hs:826-838`, cardano-ledger 9df9f98c). */
  def ppRefScriptCostStride: PParam[Dijkstra] =
    PParam.field(
      "refScriptCostStride",
      36,
      dijkstra.hkdRefScriptCostStrideL[Identity],
      dijkstra.hkdRefScriptCostStrideL[StrictMaybe]
    )

  /** `ppRefScriptCostMultiplier` (`Dijkstra/PParams.hs:840-852`, cardano-ledger 9df9f98c). */
  def ppRefScriptCostMultiplier: PParam[Dijkstra] =
    PParam.field(
      "refScriptCostMultiplier",
      37,
      dijkstra.hkdRefScriptCostMultiplierL[Identity],
      dijkstra.hkdRefScriptCostMultiplierL[StrictMaybe]
    )

  /** `ppMaxPledgeLeverage` (`Dijkstra/PParams.hs:854-866`, cardano-ledger 9df9f98c). */
  def ppMaxPledgeLeverage: PParam[Dijkstra] =
    PParam.field(
      "maxPledgeLeverage",
      38,
      dijkstra.hkdMaxPledgeLeverageL[Identity],
      dijkstra.hkdMaxPledgeLeverageL[StrictMaybe]
    )

  /** `ppMinPoolMargin` (`Dijkstra/PParams.hs:868-880`, cardano-ledger 9df9f98c). */
  def ppMinPoolMargin: PParam[Dijkstra] =
    PParam.field(
      "minPoolMargin",
      39,
      dijkstra.hkdMinPoolMarginL[Identity],
      dijkstra.hkdMinPoolMarginL[StrictMaybe]
    )

  /** `ppLeiosAnnouncementPeriodLength` (`Dijkstra/PParams.hs:882-894`, cardano-ledger 9df9f98c). */
  def ppLeiosAnnouncementPeriodLength: PParam[Dijkstra] =
    PParam.field(
      "leiosAnnouncementPeriodLength",
      40,
      dijkstra.hkdLeiosAnnouncementPeriodLengthL[Identity],
      dijkstra.hkdLeiosAnnouncementPeriodLengthL[StrictMaybe]
    )

  /** `ppLeiosVotePeriodLength` (`Dijkstra/PParams.hs:896-908`, cardano-ledger 9df9f98c). */
  def ppLeiosVotePeriodLength: PParam[Dijkstra] =
    PParam.field(
      "leiosVotePeriodLength",
      41,
      dijkstra.hkdLeiosVotePeriodLengthL[Identity],
      dijkstra.hkdLeiosVotePeriodLengthL[StrictMaybe]
    )

  /** `ppLeiosDiffusionPeriodLength` (`Dijkstra/PParams.hs:910-922`, cardano-ledger 9df9f98c). */
  def ppLeiosDiffusionPeriodLength: PParam[Dijkstra] =
    PParam.field(
      "leiosDiffusionPeriodLength",
      42,
      dijkstra.hkdLeiosDiffusionPeriodLengthL[Identity],
      dijkstra.hkdLeiosDiffusionPeriodLengthL[StrictMaybe]
    )

  /** `ppLeiosCommitteeSize` (`Dijkstra/PParams.hs:924-936`, cardano-ledger 9df9f98c). */
  def ppLeiosCommitteeSize: PParam[Dijkstra] =
    PParam.field(
      "leiosCommitteeSize",
      43,
      dijkstra.hkdLeiosCommitteeSizeL[Identity],
      dijkstra.hkdLeiosCommitteeSizeL[StrictMaybe]
    )

  /** `ppLeiosQuorumStakeThreshold` (`Dijkstra/PParams.hs:938-950`, cardano-ledger 9df9f98c). */
  def ppLeiosQuorumStakeThreshold: PParam[Dijkstra] =
    PParam.field(
      "leiosQuorumStakeThreshold",
      44,
      dijkstra.hkdLeiosQuorumStakeThresholdL[Identity],
      dijkstra.hkdLeiosQuorumStakeThresholdL[StrictMaybe]
    )

  /** `ppMaxEndorserBlockReferencesSize` (`Dijkstra/PParams.hs:952-964`, cardano-ledger 9df9f98c).
    */
  def ppMaxEndorserBlockReferencesSize: PParam[Dijkstra] =
    PParam.field(
      "maxEndorserBlockReferencesSize",
      45,
      dijkstra.hkdMaxEndorserBlockReferencesSizeL[Identity],
      dijkstra.hkdMaxEndorserBlockReferencesSizeL[StrictMaybe]
    )

  /** `ppMaxEndorserBlockTxsSize` (`Dijkstra/PParams.hs:966-978`, cardano-ledger 9df9f98c). */
  def ppMaxEndorserBlockTxsSize: PParam[Dijkstra] =
    PParam.field(
      "maxEndorserBlockTxsSize",
      46,
      dijkstra.hkdMaxEndorserBlockTxsSizeL[Identity],
      dijkstra.hkdMaxEndorserBlockTxsSizeL[StrictMaybe]
    )

  /** `ppMaxEndorserBlockExUnits` (`Dijkstra/PParams.hs:980-992`, cardano-ledger 9df9f98c). */
  def ppMaxEndorserBlockExUnits: PParam[Dijkstra] =
    PParam.field(
      "maxEndorserBlockExecutionUnits",
      47,
      dijkstra.hkdMaxEndorserBlockExUnitsL[Identity],
      dijkstra.hkdMaxEndorserBlockExUnitsL[StrictMaybe]
    )

  /** `ppMaxRefScriptSizePerEndorserBlock` (`Dijkstra/PParams.hs:994-1006`, cardano-ledger
    * 9df9f98c).
    */
  def ppMaxRefScriptSizePerEndorserBlock: PParam[Dijkstra] =
    PParam.field(
      "maxRefScriptSizePerEndorserBlock",
      48,
      dijkstra.hkdMaxRefScriptSizePerEndorserBlockL[Identity],
      dijkstra.hkdMaxRefScriptSizePerEndorserBlockL[StrictMaybe]
    )

  /** `ppPerasMinCandidateBlockAge` (`Dijkstra/PParams.hs:1008-1020`, cardano-ledger 9df9f98c). */
  def ppPerasMinCandidateBlockAge: PParam[Dijkstra] =
    PParam.field(
      "perasMinCandidateBlockAge",
      49,
      dijkstra.hkdPerasMinCandidateBlockAgeL[Identity],
      dijkstra.hkdPerasMinCandidateBlockAgeL[StrictMaybe]
    )

  /** `ppPerasHealingFactor` (`Dijkstra/PParams.hs:1022-1034`, cardano-ledger 9df9f98c). */
  def ppPerasHealingFactor: PParam[Dijkstra] =
    PParam.field(
      "perasHealingFactor",
      50,
      dijkstra.hkdPerasHealingFactorL[Identity],
      dijkstra.hkdPerasHealingFactorL[StrictMaybe]
    )

  /** `ppPerasCertBoost` (`Dijkstra/PParams.hs:1036-1048`, cardano-ledger 9df9f98c). */
  def ppPerasCertBoost: PParam[Dijkstra] =
    PParam.field(
      "perasCertBoost",
      51,
      dijkstra.hkdPerasCertBoostL[Identity],
      dijkstra.hkdPerasCertBoostL[StrictMaybe]
    )

  /** `ppPerasTargetCommitteeSize` (`Dijkstra/PParams.hs:1050-1062`, cardano-ledger 9df9f98c). */
  def ppPerasTargetCommitteeSize: PParam[Dijkstra] =
    PParam.field(
      "perasTargetCommitteeSize",
      52,
      dijkstra.hkdPerasTargetCommitteeSizeL[Identity],
      dijkstra.hkdPerasTargetCommitteeSizeL[StrictMaybe]
    )

  /** `ppPerasBootstrapRound` (`Dijkstra/PParams.hs:1064-1081`, cardano-ledger 9df9f98c): in an
    * update its era codec writes and reads the round `null` or the `Word32`
    * (`encodeNullStrictMaybe`, `decodeNullStrictMaybe`), where the parameters hold the value's own,
    * `[]` or `[round]`.
    */
  def ppPerasBootstrapRound: PParam[Dijkstra] =
    PParam.field(
      "perasBootstrapRound",
      53,
      dijkstra.hkdPerasBootstrapRoundL[Identity],
      dijkstra.hkdPerasBootstrapRoundL[StrictMaybe],
      Some(
        EraCodec[Dijkstra, Option[Unsigned[W32]]](
          Encoding.encodeNullStrictMaybe(EncCBOR[Unsigned[W32]].encCBOR),
          DecCBOR.nullStrictMaybe(DecCBOR[Unsigned[W32]])
        )
      )
    )

  /** `ppPerasQuorumThresholdSafetyMargin` (`Dijkstra/PParams.hs:1083-1095`, cardano-ledger
    * 9df9f98c).
    */
  def ppPerasQuorumThresholdSafetyMargin: PParam[Dijkstra] =
    PParam.field(
      "perasQuorumThresholdSafetyMargin",
      54,
      dijkstra.hkdPerasQuorumThresholdSafetyMarginL[Identity],
      dijkstra.hkdPerasQuorumThresholdSafetyMarginL[StrictMaybe]
    )

  /** `ppRefInputsCostPerMultiAssetPolicy` (`Dijkstra/PParams.hs:1097-1109`, cardano-ledger
    * 9df9f98c).
    */
  def ppRefInputsCostPerMultiAssetPolicy: PParam[Dijkstra] =
    PParam.field(
      "refInputsCostPerMultiAssetPolicy",
      55,
      dijkstra.hkdRefInputsCostPerMultiAssetPolicyL[Identity],
      dijkstra.hkdRefInputsCostPerMultiAssetPolicyL[StrictMaybe]
    )

  /** `ppRefInputsCostPerDatumByte` (`Dijkstra/PParams.hs:1111-1123`, cardano-ledger 9df9f98c). */
  def ppRefInputsCostPerDatumByte: PParam[Dijkstra] =
    PParam.field(
      "refInputsCostPerDatumByte",
      56,
      dijkstra.hkdRefInputsCostPerDatumByteL[Identity],
      dijkstra.hkdRefInputsCostPerDatumByteL[StrictMaybe]
    )

/** Dijkstra's instances (`instance EraPParams DijkstraEra` to `instance DijkstraEraPParams
  * DijkstraEra`, `Dijkstra/PParams.hs:703-1197, 1343-1366`, cardano-ledger 9df9f98c).
  */
private[era] object DijkstraEraPParamsInstance extends DijkstraEraPParams[Dijkstra]:
  private type R[F[_]] = DijkstraPParams[F]

  def applyPPUpdates(pp: PParams[Dijkstra], ppu: PParamsUpdate[Dijkstra]): PParams[Dijkstra] =
    PParams[Dijkstra](DijkstraPParams.applyUpdates(pp.hkd, ppu.hkd))

  def emptyPParamsIdentity: DijkstraPParams[Identity] = DijkstraPParams.empty

  def emptyPParamsStrictMaybe: DijkstraPParams[StrictMaybe] = DijkstraPParams.emptyUpdate

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

  /** `ppDG = to (const minBound)` (`Dijkstra/PParams.hs:735`, cardano-ledger 9df9f98c). */
  val ppDG: Getter[PParams[Dijkstra], UnitInterval] = Getter(_ => UnitInterval.zero)

  /** `ppLensHKD . lens dppProtocolVersion ...` (`Dijkstra/PParams.hs:732`, cardano-ledger
    * 9df9f98c).
    */
  val ppProtocolVersionL: Lens[PParams[Dijkstra], ProtVer] =
    PParams.lensHKD[Dijkstra].andThen(GenLens[R[Identity]](_.protocolVersion))

  /** `ppMaxPledgeLeverageG = ppMaxPledgeLeverageL` and `ppMinPoolMarginG = ppLensHKD .
    * hkdMinPoolMarginL` (`Dijkstra/PParams.hs:733,740`, cardano-ledger 9df9f98c): Dijkstra's
    * fields, where the eras before it have constants.
    */
  val ppMaxPledgeLeverageG: Getter[PParams[Dijkstra], MaxPledgeLeverage] =
    field(hkdMaxPledgeLeverageL[Identity])
  val ppMinPoolMarginG: Getter[PParams[Dijkstra], UnitInterval] =
    field(hkdMinPoolMarginL[Identity])

  /** `eraPParams` (`Dijkstra/PParams.hs:741-796`, cardano-ledger 9df9f98c): Conway's, then the
    * parameters Dijkstra adds.
    */
  lazy val eraPParams: Vector[PParam[Dijkstra]] =
    import EraPParams.*, AlonzoEraPParams.*, BabbageEraPParams.*, ConwayEraPParams.*
    import DijkstraEraPParams.*
    given DijkstraEraPParams[Dijkstra] = this
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
      ppMinFeeRefScriptCostPerByte,
      ppMaxRefScriptSizePerBlock,
      ppMaxRefScriptSizePerTx,
      ppRefScriptCostStride,
      ppRefScriptCostMultiplier,
      ppMaxPledgeLeverage,
      ppMinPoolMargin,
      ppLeiosAnnouncementPeriodLength,
      ppLeiosVotePeriodLength,
      ppLeiosDiffusionPeriodLength,
      ppLeiosCommitteeSize,
      ppLeiosQuorumStakeThreshold,
      ppMaxEndorserBlockReferencesSize,
      ppMaxEndorserBlockTxsSize,
      ppMaxEndorserBlockExUnits,
      ppMaxRefScriptSizePerEndorserBlock,
      ppPerasMinCandidateBlockAge,
      ppPerasHealingFactor,
      ppPerasCertBoost,
      ppPerasTargetCommitteeSize,
      ppPerasBootstrapRound,
      ppPerasQuorumThresholdSafetyMargin,
      ppRefInputsCostPerMultiAssetPolicy,
      ppRefInputsCostPerDatumByte
    )

  def hkdCostModelsL[F[_]: HKDFunctor]: Lens[R[F], F[CostModels]] =
    GenLens[R[F]](_.costModels).andThen(THKD.value)
  def hkdPricesL[F[_]: HKDFunctor]: Lens[R[F], F[Prices]] =
    GenLens[R[F]](_.prices).andThen(THKD.value)

  /** `hkdMap unOrdExUnits` over the field, and `hkdMap OrdExUnits` back
    * (`Dijkstra/PParams.hs:1130-1137`, cardano-ledger 9df9f98c).
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

  def modifiedPPGroups(ppu: PParamsUpdate[Dijkstra]): SortedSet[PPGroups] =
    DijkstraPParams.modifiedGroups(ppu.hkd)

  /** `ppuWellFormed` (`Dijkstra/PParams.hs:1149-1175`, cardano-ledger 9df9f98c): whatever the
    * version, no set field among the sizes, the collateral percentage, the committee's term, the
    * actions' lifetime, the deposits, the price of a byte of UTxO, the desired number of pools and
    * the latest retirement epoch is zero, and the update sets a field.
    */
  def ppuWellFormed(pv: ProtVer, ppu: PParamsUpdate[Dijkstra]): Boolean =
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
    nonZeroCoin(u.coinsPerUTxOByte.unTHKD.map(_.unCoinPerByte)) &&
    u != DijkstraPParams.emptyUpdate &&
    nonZeroWord(u.nOpt.unTHKD) &&
    nonZeroEpochs(u.eMax.unTHKD)

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

  private def field[A](l: Lens[R[Identity], A]): Getter[PParams[Dijkstra], A] =
    Getter(p => l.get(p.hkd))

  /** `ppLensHKD . hkdMaxRefScriptSizePerTxL` and the other three reference-script getters
    * (`Dijkstra/PParams.hs:1194-1197`, cardano-ledger 9df9f98c): Dijkstra's fields, where Conway
    * has constants.
    */
  val ppMaxRefScriptSizePerTxG: Getter[PParams[Dijkstra], Unsigned[W32]] =
    field(hkdMaxRefScriptSizePerTxL[Identity])
  val ppMaxRefScriptSizePerBlockG: Getter[PParams[Dijkstra], Unsigned[W32]] =
    field(hkdMaxRefScriptSizePerBlockL[Identity])
  val ppRefScriptCostMultiplierG: Getter[PParams[Dijkstra], PositiveInterval] =
    field(hkdRefScriptCostMultiplierL[Identity])
  val ppRefScriptCostStrideG: Getter[PParams[Dijkstra], NonZero[Unsigned[W32]]] =
    field(hkdRefScriptCostStrideL[Identity])

  def hkdMaxRefScriptSizePerBlockL[F[_]: HKDFunctor]: Lens[R[F], F[Unsigned[W32]]] =
    GenLens[R[F]](_.maxRefScriptSizePerBlock).andThen(THKD.value)
  def hkdMaxRefScriptSizePerTxL[F[_]: HKDFunctor]: Lens[R[F], F[Unsigned[W32]]] =
    GenLens[R[F]](_.maxRefScriptSizePerTx).andThen(THKD.value)
  def hkdRefScriptCostStrideL[F[_]: HKDFunctor]: Lens[R[F], F[NonZero[Unsigned[W32]]]] =
    GenLens[R[F]](_.refScriptCostStride).andThen(THKD.value)
  def hkdRefScriptCostMultiplierL[F[_]: HKDFunctor]: Lens[R[F], F[PositiveInterval]] =
    GenLens[R[F]](_.refScriptCostMultiplier).andThen(THKD.value)
  def hkdMaxPledgeLeverageL[F[_]: HKDFunctor]: Lens[R[F], F[MaxPledgeLeverage]] =
    GenLens[R[F]](_.maxPledgeLeverage).andThen(THKD.value)
  def hkdMinPoolMarginL[F[_]: HKDFunctor]: Lens[R[F], F[UnitInterval]] =
    GenLens[R[F]](_.minPoolMargin).andThen(THKD.value)
  def hkdLeiosAnnouncementPeriodLengthL[F[_]: HKDFunctor]: Lens[R[F], F[Milliseconds32]] =
    GenLens[R[F]](_.leiosAnnouncementPeriodLength).andThen(THKD.value)
  def hkdLeiosVotePeriodLengthL[F[_]: HKDFunctor]: Lens[R[F], F[Milliseconds32]] =
    GenLens[R[F]](_.leiosVotePeriodLength).andThen(THKD.value)
  def hkdLeiosDiffusionPeriodLengthL[F[_]: HKDFunctor]: Lens[R[F], F[Milliseconds32]] =
    GenLens[R[F]](_.leiosDiffusionPeriodLength).andThen(THKD.value)
  def hkdLeiosCommitteeSizeL[F[_]: HKDFunctor]: Lens[R[F], F[Unsigned[W16]]] =
    GenLens[R[F]](_.leiosCommitteeSize).andThen(THKD.value)
  def hkdLeiosQuorumStakeThresholdL[F[_]: HKDFunctor]: Lens[R[F], F[UnitInterval]] =
    GenLens[R[F]](_.leiosQuorumStakeThreshold).andThen(THKD.value)
  def hkdMaxEndorserBlockReferencesSizeL[F[_]: HKDFunctor]: Lens[R[F], F[Unsigned[W32]]] =
    GenLens[R[F]](_.maxEndorserBlockReferencesSize).andThen(THKD.value)
  def hkdMaxEndorserBlockTxsSizeL[F[_]: HKDFunctor]: Lens[R[F], F[Unsigned[W32]]] =
    GenLens[R[F]](_.maxEndorserBlockTxsSize).andThen(THKD.value)
  def hkdMaxEndorserBlockExUnitsL[F[_]: HKDFunctor]: Lens[R[F], F[OrdExUnits]] =
    GenLens[R[F]](_.maxEndorserBlockExUnits).andThen(THKD.value)
  def hkdMaxRefScriptSizePerEndorserBlockL[F[_]: HKDFunctor]: Lens[R[F], F[Unsigned[W32]]] =
    GenLens[R[F]](_.maxRefScriptSizePerEndorserBlock).andThen(THKD.value)
  def hkdPerasMinCandidateBlockAgeL[F[_]: HKDFunctor]: Lens[R[F], F[SlotInterval]] =
    GenLens[R[F]](_.perasMinCandidateBlockAge).andThen(THKD.value)
  def hkdPerasHealingFactorL[F[_]: HKDFunctor]: Lens[R[F], F[PositiveInterval]] =
    GenLens[R[F]](_.perasHealingFactor).andThen(THKD.value)
  def hkdPerasCertBoostL[F[_]: HKDFunctor]: Lens[R[F], F[Unsigned[W16]]] =
    GenLens[R[F]](_.perasCertBoost).andThen(THKD.value)
  def hkdPerasTargetCommitteeSizeL[F[_]: HKDFunctor]: Lens[R[F], F[Unsigned[W16]]] =
    GenLens[R[F]](_.perasTargetCommitteeSize).andThen(THKD.value)
  def hkdPerasBootstrapRoundL[F[_]: HKDFunctor]: Lens[R[F], F[Option[Unsigned[W32]]]] =
    GenLens[R[F]](_.perasBootstrapRound).andThen(THKD.value)
  def hkdPerasQuorumThresholdSafetyMarginL[F[_]: HKDFunctor]: Lens[R[F], F[UnitInterval]] =
    GenLens[R[F]](_.perasQuorumThresholdSafetyMargin).andThen(THKD.value)
  def hkdRefInputsCostPerMultiAssetPolicyL[F[_]: HKDFunctor]: Lens[R[F], F[Coin]] =
    GenLens[R[F]](_.refInputsCostPerMultiAssetPolicy).andThen(THKD.value)
  def hkdRefInputsCostPerDatumByteL[F[_]: HKDFunctor]: Lens[R[F], F[CoinPerByte]] =
    GenLens[R[F]](_.refInputsCostPerDatumByte).andThen(THKD.value)
