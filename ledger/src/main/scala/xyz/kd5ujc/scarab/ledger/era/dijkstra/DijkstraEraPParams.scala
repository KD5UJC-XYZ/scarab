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
