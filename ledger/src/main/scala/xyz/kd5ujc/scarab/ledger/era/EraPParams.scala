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
  * `downgradePParamsHKD`, which come with Babbage's record, and `eraPParams`, the parameters'
  * descriptions (#85).
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

object AlonzoEraPParams:
  given conway: AlonzoEraPParams[Conway] = ConwayEraPParamsInstance
  given dijkstra: AlonzoEraPParams[Dijkstra] = DijkstraEraPParamsInstance

object BabbageEraPParams:
  given conway: BabbageEraPParams[Conway] = ConwayEraPParamsInstance
  given dijkstra: BabbageEraPParams[Dijkstra] = DijkstraEraPParamsInstance

object ConwayEraPParams:
  given conway: ConwayEraPParams[Conway] = ConwayEraPParamsInstance
  given dijkstra: ConwayEraPParams[Dijkstra] = DijkstraEraPParamsInstance

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
