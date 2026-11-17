// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.dijkstra

import cats.syntax.all.*
import scala.collection.immutable.SortedSet
import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.ledger.{DecodeField, ProtocolParams, Unsigned}
import xyz.kd5ujc.scarab.ledger.Unsigned.Width.{W16, W32, W64}
import xyz.kd5ujc.scarab.ledger.dijkstra.{DijkstraParamUpdate, DijkstraParamValue}
import xyz.kd5ujc.scarab.ledger.era.*
import xyz.kd5ujc.scarab.ledger.era.conway.{
  ConwayPParams,
  DRepVotingThresholds,
  PoolVotingThresholds
}
import xyz.kd5ujc.scarab.ledger.state.{Coin, CompactCoin, EpochInterval, HasZero, NonZero}
import xyz.kd5ujc.scarab.ledger.era.conway.ConwayPParams.{Eco, Gov, Net, NoPool, Sec, Tech}

/** Dijkstra's protocol parameters as higher-kinded data (`data DijkstraPParams f era`,
  * cardano-ledger 9df9f98c `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/PParams.hs:161-300`):
  * Conway's fields, then the reference scripts' limits and costs, the pledge leverage and pool
  * margin, Leios's and Peras's parameters, and the reference inputs' costs. With `F` the
  * [[Identity]] they are the parameters, with `F` [[StrictMaybe]] an update. The fields are the
  * reference's, in its order, each tagged with its groups ([[THKD]]); the protocol version is the
  * one field an update cannot set ([[HKDNoUpdate]]). Equal as the reference derives `Eq`.
  */
final case class DijkstraPParams[F[_]](
    txFeePerByte: THKD[Groups[Eco, Sec], F, CoinPerByte],
    txFeeFixed: THKD[Groups[Eco, Sec], F, CompactCoin],
    maxBBSize: THKD[Groups[Net, Sec], F, Unsigned[W32]],
    maxTxSize: THKD[Groups[Net, Sec], F, Unsigned[W32]],
    maxBHSize: THKD[Groups[Net, Sec], F, Unsigned[W16]],
    keyDeposit: THKD[Groups[Eco, NoPool], F, CompactCoin],
    poolDeposit: THKD[Groups[Eco, NoPool], F, CompactCoin],
    eMax: THKD[Groups[Tech, NoPool], F, EpochInterval],
    nOpt: THKD[Groups[Tech, NoPool], F, Unsigned[W16]],
    a0: THKD[Groups[Tech, NoPool], F, NonNegativeInterval],
    rho: THKD[Groups[Eco, NoPool], F, UnitInterval],
    tau: THKD[Groups[Eco, NoPool], F, UnitInterval],
    protocolVersion: HKDNoUpdate[F, ProtVer],
    minPoolCost: THKD[Groups[Eco, NoPool], F, CompactCoin],
    coinsPerUTxOByte: THKD[Groups[Eco, Sec], F, CoinPerByte],
    costModels: THKD[Groups[Tech, NoPool], F, CostModels],
    prices: THKD[Groups[Eco, NoPool], F, Prices],
    maxTxExUnits: THKD[Groups[Net, NoPool], F, OrdExUnits],
    maxBlockExUnits: THKD[Groups[Net, Sec], F, OrdExUnits],
    maxValSize: THKD[Groups[Net, Sec], F, Unsigned[W32]],
    collateralPercentage: THKD[Groups[Tech, NoPool], F, Unsigned[W16]],
    maxCollateralInputs: THKD[Groups[Net, NoPool], F, Unsigned[W16]],
    poolVotingThresholds: THKD[Groups[Gov, NoPool], F, PoolVotingThresholds],
    drepVotingThresholds: THKD[Groups[Gov, NoPool], F, DRepVotingThresholds],
    committeeMinSize: THKD[Groups[Gov, NoPool], F, Unsigned[W16]],
    committeeMaxTermLength: THKD[Groups[Gov, NoPool], F, EpochInterval],
    govActionLifetime: THKD[Groups[Gov, NoPool], F, EpochInterval],
    govActionDeposit: THKD[Groups[Gov, Sec], F, CompactCoin],
    drepDeposit: THKD[Groups[Gov, NoPool], F, CompactCoin],
    drepActivity: THKD[Groups[Gov, NoPool], F, EpochInterval],
    minFeeRefScriptCostPerByte: THKD[Groups[Eco, Sec], F, NonNegativeInterval],
    maxRefScriptSizePerBlock: THKD[Groups[Net, Sec], F, Unsigned[W32]],
    maxRefScriptSizePerTx: THKD[Groups[Net, Sec], F, Unsigned[W32]],
    refScriptCostStride: THKD[Groups[Net, Sec], F, NonZero[Unsigned[W32]]],
    refScriptCostMultiplier: THKD[Groups[Net, Sec], F, PositiveInterval],
    maxPledgeLeverage: THKD[Groups[Tech, NoPool], F, MaxPledgeLeverage],
    minPoolMargin: THKD[Groups[Eco, NoPool], F, UnitInterval],
    leiosAnnouncementPeriodLength: THKD[Groups[Net, Sec], F, Milliseconds32],
    leiosVotePeriodLength: THKD[Groups[Net, Sec], F, Milliseconds32],
    leiosDiffusionPeriodLength: THKD[Groups[Net, Sec], F, Milliseconds32],
    leiosCommitteeSize: THKD[Groups[Net, Sec], F, Unsigned[W16]],
    leiosQuorumStakeThreshold: THKD[Groups[Net, Sec], F, UnitInterval],
    maxEndorserBlockReferencesSize: THKD[Groups[Net, Sec], F, Unsigned[W32]],
    maxEndorserBlockTxsSize: THKD[Groups[Net, Sec], F, Unsigned[W32]],
    maxEndorserBlockExUnits: THKD[Groups[Net, Sec], F, OrdExUnits],
    maxRefScriptSizePerEndorserBlock: THKD[Groups[Net, Sec], F, Unsigned[W32]],
    perasMinCandidateBlockAge: THKD[Groups[Net, Sec], F, SlotInterval],
    perasHealingFactor: THKD[Groups[Net, Sec], F, PositiveInterval],
    perasCertBoost: THKD[Groups[Net, Sec], F, Unsigned[W16]],
    perasTargetCommitteeSize: THKD[Groups[Net, Sec], F, Unsigned[W16]],
    perasBootstrapRound: THKD[Groups[Net, Sec], F, Option[Unsigned[W32]]],
    perasQuorumThresholdSafetyMargin: THKD[Groups[Net, Sec], F, UnitInterval],
    refInputsCostPerMultiAssetPolicy: THKD[Groups[Eco, Sec], F, Coin],
    refInputsCostPerDatumByte: THKD[Groups[Eco, Sec], F, CoinPerByte]
)

object DijkstraPParams:

  /** `emptyDijkstraPParams` (`Dijkstra/PParams.hs:1200-1257`, cardano-ledger 9df9f98c): zero, or
    * each type's least value, but for a 2048-byte transaction, 100 pools, a 150% collateral of at
    * most 5 inputs, a reference-script cost stride of 1 (`knownNonZeroBounded @1`), no pledge
    * leverage limit, no Peras bootstrap round, and Dijkstra's lowest version, 12.
    */
  val empty: DijkstraPParams[Identity] = DijkstraPParams[Identity](
    THKD(CoinPerByte(CompactCoin.zero)),
    THKD(CompactCoin.zero),
    THKD(Unsigned.lit[W32](0)),
    THKD(Unsigned.lit[W32](2048)),
    THKD(Unsigned.lit[W16](0)),
    THKD(CompactCoin.zero),
    THKD(CompactCoin.zero),
    THKD(EpochInterval.zero),
    THKD(Unsigned.lit[W16](100)),
    THKD(NonNegativeInterval.zero),
    THKD(UnitInterval.zero),
    THKD(UnitInterval.zero),
    ProtVer(Version.lit(12), Unsigned.lit[W32](0)),
    THKD(CompactCoin.zero),
    THKD(CoinPerByte(CompactCoin.zero)),
    THKD(CostModels.empty),
    THKD(Prices.zero),
    THKD(OrdExUnits.zero),
    THKD(OrdExUnits.zero),
    THKD(Unsigned.lit[W32](0)),
    THKD(Unsigned.lit[W16](150)),
    THKD(Unsigned.lit[W16](5)),
    THKD(PoolVotingThresholds.zero),
    THKD(DRepVotingThresholds.zero),
    THKD(Unsigned.lit[W16](0)),
    THKD(EpochInterval.zero),
    THKD(EpochInterval.zero),
    THKD(CompactCoin.zero),
    THKD(CompactCoin.zero),
    THKD(EpochInterval.zero),
    THKD(NonNegativeInterval.zero),
    THKD(Unsigned.lit[W32](0)),
    THKD(Unsigned.lit[W32](0)),
    THKD(NonZero.unsafe(Unsigned.lit[W32](1))),
    THKD(PositiveInterval.minBound),
    THKD(MaxPledgeLeverage.none),
    THKD(UnitInterval.zero),
    THKD(Milliseconds32.zero),
    THKD(Milliseconds32.zero),
    THKD(Milliseconds32.zero),
    THKD(Unsigned.lit[W16](0)),
    THKD(UnitInterval.zero),
    THKD(Unsigned.lit[W32](0)),
    THKD(Unsigned.lit[W32](0)),
    THKD(OrdExUnits.zero),
    THKD(Unsigned.lit[W32](0)),
    THKD(SlotInterval.zero),
    THKD(PositiveInterval.minBound),
    THKD(Unsigned.lit[W16](0)),
    THKD(Unsigned.lit[W16](0)),
    THKD(None),
    THKD(UnitInterval.zero),
    THKD(Coin.zero),
    THKD(CoinPerByte(CompactCoin.zero))
  )

  /** `emptyDijkstraPParamsUpdate` (`Dijkstra/PParams.hs:1259-1316`, cardano-ledger 9df9f98c): no
    * field set.
    */
  val emptyUpdate: DijkstraPParams[StrictMaybe] = DijkstraPParams[StrictMaybe](
    THKD(None),
    THKD(None),
    THKD(None),
    THKD(None),
    THKD(None),
    THKD(None),
    THKD(None),
    THKD(None),
    THKD(None),
    THKD(None),
    THKD(None),
    THKD(None),
    NoUpdate(),
    THKD(None),
    THKD(None),
    THKD(None),
    THKD(None),
    THKD(None),
    THKD(None),
    THKD(None),
    THKD(None),
    THKD(None),
    THKD(None),
    THKD(None),
    THKD(None),
    THKD(None),
    THKD(None),
    THKD(None),
    THKD(None),
    THKD(None),
    THKD(None),
    THKD(None),
    THKD(None),
    THKD(None),
    THKD(None),
    THKD(None),
    THKD(None),
    THKD(None),
    THKD(None),
    THKD(None),
    THKD(None),
    THKD(None),
    THKD(None),
    THKD(None),
    THKD(None),
    THKD(None),
    THKD(None),
    THKD(None),
    THKD(None),
    THKD(None),
    THKD(None),
    THKD(None),
    THKD(None),
    THKD(None)
  )

  /** `dijkstraApplyPPUpdates` (`Dijkstra/PParams.hs:317-388`, cardano-ledger 9df9f98c): each field
    * the update sets replaces the parameter's, the protocol version is kept, and the update's cost
    * models replace those of their languages (`updateCostModels`).
    */
  def applyUpdates(
      pp: DijkstraPParams[Identity],
      ppu: DijkstraPParams[StrictMaybe]
  ): DijkstraPParams[Identity] =
    def set[G, A](
        old: THKD[G, Identity, A],
        update: THKD[G, StrictMaybe, A]
    ): THKD[G, Identity, A] =
      THKD(update.unTHKD.getOrElse(old.unTHKD))
    DijkstraPParams[Identity](
      set(pp.txFeePerByte, ppu.txFeePerByte),
      set(pp.txFeeFixed, ppu.txFeeFixed),
      set(pp.maxBBSize, ppu.maxBBSize),
      set(pp.maxTxSize, ppu.maxTxSize),
      set(pp.maxBHSize, ppu.maxBHSize),
      set(pp.keyDeposit, ppu.keyDeposit),
      set(pp.poolDeposit, ppu.poolDeposit),
      set(pp.eMax, ppu.eMax),
      set(pp.nOpt, ppu.nOpt),
      set(pp.a0, ppu.a0),
      set(pp.rho, ppu.rho),
      set(pp.tau, ppu.tau),
      pp.protocolVersion,
      set(pp.minPoolCost, ppu.minPoolCost),
      set(pp.coinsPerUTxOByte, ppu.coinsPerUTxOByte),
      THKD(ppu.costModels.unTHKD.fold(pp.costModels.unTHKD)(pp.costModels.unTHKD.updated(_))),
      set(pp.prices, ppu.prices),
      set(pp.maxTxExUnits, ppu.maxTxExUnits),
      set(pp.maxBlockExUnits, ppu.maxBlockExUnits),
      set(pp.maxValSize, ppu.maxValSize),
      set(pp.collateralPercentage, ppu.collateralPercentage),
      set(pp.maxCollateralInputs, ppu.maxCollateralInputs),
      set(pp.poolVotingThresholds, ppu.poolVotingThresholds),
      set(pp.drepVotingThresholds, ppu.drepVotingThresholds),
      set(pp.committeeMinSize, ppu.committeeMinSize),
      set(pp.committeeMaxTermLength, ppu.committeeMaxTermLength),
      set(pp.govActionLifetime, ppu.govActionLifetime),
      set(pp.govActionDeposit, ppu.govActionDeposit),
      set(pp.drepDeposit, ppu.drepDeposit),
      set(pp.drepActivity, ppu.drepActivity),
      set(pp.minFeeRefScriptCostPerByte, ppu.minFeeRefScriptCostPerByte),
      set(pp.maxRefScriptSizePerBlock, ppu.maxRefScriptSizePerBlock),
      set(pp.maxRefScriptSizePerTx, ppu.maxRefScriptSizePerTx),
      set(pp.refScriptCostStride, ppu.refScriptCostStride),
      set(pp.refScriptCostMultiplier, ppu.refScriptCostMultiplier),
      set(pp.maxPledgeLeverage, ppu.maxPledgeLeverage),
      set(pp.minPoolMargin, ppu.minPoolMargin),
      set(pp.leiosAnnouncementPeriodLength, ppu.leiosAnnouncementPeriodLength),
      set(pp.leiosVotePeriodLength, ppu.leiosVotePeriodLength),
      set(pp.leiosDiffusionPeriodLength, ppu.leiosDiffusionPeriodLength),
      set(pp.leiosCommitteeSize, ppu.leiosCommitteeSize),
      set(pp.leiosQuorumStakeThreshold, ppu.leiosQuorumStakeThreshold),
      set(pp.maxEndorserBlockReferencesSize, ppu.maxEndorserBlockReferencesSize),
      set(pp.maxEndorserBlockTxsSize, ppu.maxEndorserBlockTxsSize),
      set(pp.maxEndorserBlockExUnits, ppu.maxEndorserBlockExUnits),
      set(pp.maxRefScriptSizePerEndorserBlock, ppu.maxRefScriptSizePerEndorserBlock),
      set(pp.perasMinCandidateBlockAge, ppu.perasMinCandidateBlockAge),
      set(pp.perasHealingFactor, ppu.perasHealingFactor),
      set(pp.perasCertBoost, ppu.perasCertBoost),
      set(pp.perasTargetCommitteeSize, ppu.perasTargetCommitteeSize),
      set(pp.perasBootstrapRound, ppu.perasBootstrapRound),
      set(pp.perasQuorumThresholdSafetyMargin, ppu.perasQuorumThresholdSafetyMargin),
      set(pp.refInputsCostPerMultiAssetPolicy, ppu.refInputsCostPerMultiAssetPolicy),
      set(pp.refInputsCostPerDatumByte, ppu.refInputsCostPerDatumByte)
    )

  /** The groups of the fields an update sets (`modifiedPPGroups`, the generic default of
    * `Conway/PParams.hs:174-182`, cardano-ledger 9df9f98c, over `ppGroup`), the protocol version
    * having none.
    */
  def modifiedGroups(ppu: DijkstraPParams[StrictMaybe]): SortedSet[PPGroups] =
    import THKD.ppGroup
    Vector(
      ppGroup(ppu.txFeePerByte),
      ppGroup(ppu.txFeeFixed),
      ppGroup(ppu.maxBBSize),
      ppGroup(ppu.maxTxSize),
      ppGroup(ppu.maxBHSize),
      ppGroup(ppu.keyDeposit),
      ppGroup(ppu.poolDeposit),
      ppGroup(ppu.eMax),
      ppGroup(ppu.nOpt),
      ppGroup(ppu.a0),
      ppGroup(ppu.rho),
      ppGroup(ppu.tau),
      ppGroup(ppu.minPoolCost),
      ppGroup(ppu.coinsPerUTxOByte),
      ppGroup(ppu.costModels),
      ppGroup(ppu.prices),
      ppGroup(ppu.maxTxExUnits),
      ppGroup(ppu.maxBlockExUnits),
      ppGroup(ppu.maxValSize),
      ppGroup(ppu.collateralPercentage),
      ppGroup(ppu.maxCollateralInputs),
      ppGroup(ppu.poolVotingThresholds),
      ppGroup(ppu.drepVotingThresholds),
      ppGroup(ppu.committeeMinSize),
      ppGroup(ppu.committeeMaxTermLength),
      ppGroup(ppu.govActionLifetime),
      ppGroup(ppu.govActionDeposit),
      ppGroup(ppu.drepDeposit),
      ppGroup(ppu.drepActivity),
      ppGroup(ppu.minFeeRefScriptCostPerByte),
      ppGroup(ppu.maxRefScriptSizePerBlock),
      ppGroup(ppu.maxRefScriptSizePerTx),
      ppGroup(ppu.refScriptCostStride),
      ppGroup(ppu.refScriptCostMultiplier),
      ppGroup(ppu.maxPledgeLeverage),
      ppGroup(ppu.minPoolMargin),
      ppGroup(ppu.leiosAnnouncementPeriodLength),
      ppGroup(ppu.leiosVotePeriodLength),
      ppGroup(ppu.leiosDiffusionPeriodLength),
      ppGroup(ppu.leiosCommitteeSize),
      ppGroup(ppu.leiosQuorumStakeThreshold),
      ppGroup(ppu.maxEndorserBlockReferencesSize),
      ppGroup(ppu.maxEndorserBlockTxsSize),
      ppGroup(ppu.maxEndorserBlockExUnits),
      ppGroup(ppu.maxRefScriptSizePerEndorserBlock),
      ppGroup(ppu.perasMinCandidateBlockAge),
      ppGroup(ppu.perasHealingFactor),
      ppGroup(ppu.perasCertBoost),
      ppGroup(ppu.perasTargetCommitteeSize),
      ppGroup(ppu.perasBootstrapRound),
      ppGroup(ppu.perasQuorumThresholdSafetyMargin),
      ppGroup(ppu.refInputsCostPerMultiAssetPolicy),
      ppGroup(ppu.refInputsCostPerDatumByte)
    ).foldLeft(SortedSet.empty[PPGroups])(_ ++ _)

  /** `downgradeDijkstraPParams` (`Dijkstra/PParams.hs:667-701`, cardano-ledger 9df9f98c): Conway's
    * fields of the record, in any form `F`, as Conway's record.
    */
  def downgrade[F[_]](pp: DijkstraPParams[F]): ConwayPParams[F] =
    ConwayPParams[F](
      txFeePerByte = pp.txFeePerByte,
      txFeeFixed = pp.txFeeFixed,
      maxBBSize = pp.maxBBSize,
      maxTxSize = pp.maxTxSize,
      maxBHSize = pp.maxBHSize,
      keyDeposit = pp.keyDeposit,
      poolDeposit = pp.poolDeposit,
      eMax = pp.eMax,
      nOpt = pp.nOpt,
      a0 = pp.a0,
      rho = pp.rho,
      tau = pp.tau,
      protocolVersion = pp.protocolVersion,
      minPoolCost = pp.minPoolCost,
      coinsPerUTxOByte = pp.coinsPerUTxOByte,
      costModels = pp.costModels,
      prices = pp.prices,
      maxTxExUnits = pp.maxTxExUnits,
      maxBlockExUnits = pp.maxBlockExUnits,
      maxValSize = pp.maxValSize,
      collateralPercentage = pp.collateralPercentage,
      maxCollateralInputs = pp.maxCollateralInputs,
      poolVotingThresholds = pp.poolVotingThresholds,
      drepVotingThresholds = pp.drepVotingThresholds,
      committeeMinSize = pp.committeeMinSize,
      committeeMaxTermLength = pp.committeeMaxTermLength,
      govActionLifetime = pp.govActionLifetime,
      govActionDeposit = pp.govActionDeposit,
      drepDeposit = pp.drepDeposit,
      drepActivity = pp.drepActivity,
      minFeeRefScriptCostPerByte = pp.minFeeRefScriptCostPerByte
    )

  // ---- the parameters the node keeps

  private type Read[A] = Either[PParamsError, A]

  private given HasZero[Unsigned[W32]] = HasZero.instance(_.value.signum == 0)

  private def unsigned[W <: Unsigned.Width: Unsigned.Bound](
      field: DecodeField,
      n: BigInt
  ): Read[Unsigned[W]] =
    Unsigned.from[W](n).toRight(PParamsError.OutOfRange(field, n))

  private def compact(field: DecodeField, n: BigInt): Read[CompactCoin] =
    unsigned[W64](field, n).map(w => CompactCoin.fromUnsigned(w.value.toLong))

  private def epochs(field: DecodeField, n: BigInt): Read[EpochInterval] =
    unsigned[W32](field, n).map(EpochInterval.fromWord32)

  /** `NonZero Word32`: a `Word32` other than zero. */
  private def stride(field: DecodeField, n: BigInt): Read[NonZero[Unsigned[W32]]] =
    unsigned[W32](field, n).flatMap(w =>
      NonZero.from(w).left.map(_ => PParamsError.OutOfRange(field, n))
    )

  private def unit(field: DecodeField, r: Rational): Read[UnitInterval] =
    UnitInterval.boundRational(r).toRight(PParamsError.RatioOutOfBounds(field, r))

  private def nonNegative(field: DecodeField, r: Rational): Read[NonNegativeInterval] =
    NonNegativeInterval.boundRational(r).toRight(PParamsError.RatioOutOfBounds(field, r))

  private def positive(field: DecodeField, r: Rational): Read[PositiveInterval] =
    PositiveInterval.boundRational(r).toRight(PParamsError.RatioOutOfBounds(field, r))

  private def units(field: DecodeField, u: xyz.kd5ujc.scarab.plutus.ExUnits): Read[OrdExUnits] =
    ExUnits.from(u).map(OrdExUnits(_)).toRight(PParamsError.NegativeExUnits(field, u))

  private def millis(field: DecodeField, n: BigInt): Read[Milliseconds32] =
    unsigned[W32](field, n).map(Milliseconds32(_))

  private def leverage(field: DecodeField, r: Option[Rational]): Read[MaxPledgeLeverage] =
    r.traverse(nonNegative(field, _)).map(MaxPledgeLeverage(_))

  /** Dijkstra's fields of the parameters the node keeps, each at the reference's type: Conway's as
    * [[ConwayPParams.fromStored]] reads them, then the fields Dijkstra adds. A field outside its
    * type's range is refused, among them the zero that the node's record holds for a field Dijkstra
    * adds before Dijkstra: the reference-script cost stride (`NonZero Word32`), and the multiplier
    * and Peras healing factor (`PositiveInterval`).
    */
  def fromStored(p: ProtocolParams): Either[PParamsError, DijkstraPParams[Identity]] =
    import DecodeField as F
    for
      c <- ConwayPParams.fromStored(p)
      maxRefScriptSizePerBlock <- unsigned[W32](
        F.MaxRefScriptSizePerBlock,
        p.maxRefScriptSizePerBlock
      )
      maxRefScriptSizePerTx <- unsigned[W32](F.MaxRefScriptSizePerTx, p.maxRefScriptSizePerTx)
      refScriptCostStride <- stride(F.RefScriptCostStride, p.refScriptCostStride)
      refScriptCostMultiplier <- positive(F.RefScriptCostMultiplier, p.refScriptCostMultiplier)
      maxPledgeLeverage <- leverage(F.MaxPledgeLeverage, p.maxPledgeLeverage)
      minPoolMargin <- unit(F.MinPoolMargin, p.minPoolMargin)
      leiosAnnouncementPeriodLength <- millis(
        F.LeiosAnnouncementPeriodLength,
        p.leiosAnnouncementPeriodLength
      )
      leiosVotePeriodLength <- millis(F.LeiosVotePeriodLength, p.leiosVotePeriodLength)
      leiosDiffusionPeriodLength <- millis(
        F.LeiosDiffusionPeriodLength,
        p.leiosDiffusionPeriodLength
      )
      leiosCommitteeSize <- unsigned[W16](F.LeiosCommitteeSize, p.leiosCommitteeSize)
      leiosQuorumStakeThreshold <- unit(F.LeiosQuorumStakeThreshold, p.leiosQuorumStakeThreshold)
      maxEndorserBlockReferencesSize <- unsigned[W32](
        F.MaxEndorserBlockReferencesSize,
        p.maxEndorserBlockReferencesSize
      )
      maxEndorserBlockTxsSize <- unsigned[W32](F.MaxEndorserBlockTxsSize, p.maxEndorserBlockTxsSize)
      maxEndorserBlockExUnits <- units(F.MaxEndorserBlockExUnits, p.maxEndorserBlockExUnits)
      maxRefScriptSizePerEndorserBlock <- unsigned[W32](
        F.MaxRefScriptSizePerEndorserBlock,
        p.maxRefScriptSizePerEndorserBlock
      )
      perasMinCandidateBlockAge <- unsigned[W32](
        F.PerasMinCandidateBlockAge,
        p.perasMinCandidateBlockAge
      )
      perasHealingFactor <- positive(F.PerasHealingFactor, p.perasHealingFactor)
      perasCertBoost <- unsigned[W16](F.PerasCertBoost, p.perasCertBoost)
      perasTargetCommitteeSize <- unsigned[W16](
        F.PerasTargetCommitteeSize,
        p.perasTargetCommitteeSize
      )
      perasBootstrapRound <- p.perasBootstrapRound.traverse(unsigned[W32](F.PerasBootstrapRound, _))
      perasQuorumThresholdSafetyMargin <- unit(
        F.PerasQuorumThresholdSafetyMargin,
        p.perasQuorumThresholdSafetyMargin
      )
      refInputsCostPerDatumByte <- compact(F.RefInputsCostPerDatumByte, p.refInputsCostPerDatumByte)
    yield DijkstraPParams[Identity](
      txFeePerByte = c.txFeePerByte,
      txFeeFixed = c.txFeeFixed,
      maxBBSize = c.maxBBSize,
      maxTxSize = c.maxTxSize,
      maxBHSize = c.maxBHSize,
      keyDeposit = c.keyDeposit,
      poolDeposit = c.poolDeposit,
      eMax = c.eMax,
      nOpt = c.nOpt,
      a0 = c.a0,
      rho = c.rho,
      tau = c.tau,
      protocolVersion = c.protocolVersion,
      minPoolCost = c.minPoolCost,
      coinsPerUTxOByte = c.coinsPerUTxOByte,
      costModels = c.costModels,
      prices = c.prices,
      maxTxExUnits = c.maxTxExUnits,
      maxBlockExUnits = c.maxBlockExUnits,
      maxValSize = c.maxValSize,
      collateralPercentage = c.collateralPercentage,
      maxCollateralInputs = c.maxCollateralInputs,
      poolVotingThresholds = c.poolVotingThresholds,
      drepVotingThresholds = c.drepVotingThresholds,
      committeeMinSize = c.committeeMinSize,
      committeeMaxTermLength = c.committeeMaxTermLength,
      govActionLifetime = c.govActionLifetime,
      govActionDeposit = c.govActionDeposit,
      drepDeposit = c.drepDeposit,
      drepActivity = c.drepActivity,
      minFeeRefScriptCostPerByte = c.minFeeRefScriptCostPerByte,
      maxRefScriptSizePerBlock = THKD(maxRefScriptSizePerBlock),
      maxRefScriptSizePerTx = THKD(maxRefScriptSizePerTx),
      refScriptCostStride = THKD(refScriptCostStride),
      refScriptCostMultiplier = THKD(refScriptCostMultiplier),
      maxPledgeLeverage = THKD(maxPledgeLeverage),
      minPoolMargin = THKD(minPoolMargin),
      leiosAnnouncementPeriodLength = THKD(leiosAnnouncementPeriodLength),
      leiosVotePeriodLength = THKD(leiosVotePeriodLength),
      leiosDiffusionPeriodLength = THKD(leiosDiffusionPeriodLength),
      leiosCommitteeSize = THKD(leiosCommitteeSize),
      leiosQuorumStakeThreshold = THKD(leiosQuorumStakeThreshold),
      maxEndorserBlockReferencesSize = THKD(maxEndorserBlockReferencesSize),
      maxEndorserBlockTxsSize = THKD(maxEndorserBlockTxsSize),
      maxEndorserBlockExUnits = THKD(maxEndorserBlockExUnits),
      maxRefScriptSizePerEndorserBlock = THKD(maxRefScriptSizePerEndorserBlock),
      perasMinCandidateBlockAge = THKD(SlotInterval(perasMinCandidateBlockAge)),
      perasHealingFactor = THKD(perasHealingFactor),
      perasCertBoost = THKD(perasCertBoost),
      perasTargetCommitteeSize = THKD(perasTargetCommitteeSize),
      perasBootstrapRound = THKD(perasBootstrapRound),
      perasQuorumThresholdSafetyMargin = THKD(perasQuorumThresholdSafetyMargin),
      refInputsCostPerMultiAssetPolicy = THKD(Coin(p.refInputsCostPerMultiAssetPolicy)),
      refInputsCostPerDatumByte = THKD(CoinPerByte(refInputsCostPerDatumByte))
    )

  /** The parameters `base` with Dijkstra's fields those of `pp`: Conway's as
    * [[ConwayPParams.toStored]] writes the record's [[downgrade]], then the fields Dijkstra adds.
    * The inverse of [[fromStored]] over the node's record, whose other eras' fields `base` keeps.
    */
  def toStored(pp: DijkstraPParams[Identity], base: ProtocolParams): ProtocolParams =
    ConwayPParams
      .toStored(downgrade(pp), base)
      .copy(
        maxRefScriptSizePerBlock = pp.maxRefScriptSizePerBlock.unTHKD.value,
        maxRefScriptSizePerTx = pp.maxRefScriptSizePerTx.unTHKD.value,
        refScriptCostStride = pp.refScriptCostStride.unTHKD.value.value,
        refScriptCostMultiplier = pp.refScriptCostMultiplier.unTHKD.rational,
        maxPledgeLeverage = pp.maxPledgeLeverage.unTHKD.unMaxPledgeLeverage.map(_.rational),
        minPoolMargin = pp.minPoolMargin.unTHKD.rational,
        leiosAnnouncementPeriodLength =
          pp.leiosAnnouncementPeriodLength.unTHKD.unMilliseconds32.value,
        leiosVotePeriodLength = pp.leiosVotePeriodLength.unTHKD.unMilliseconds32.value,
        leiosDiffusionPeriodLength = pp.leiosDiffusionPeriodLength.unTHKD.unMilliseconds32.value,
        leiosCommitteeSize = pp.leiosCommitteeSize.unTHKD.value,
        leiosQuorumStakeThreshold = pp.leiosQuorumStakeThreshold.unTHKD.rational,
        maxEndorserBlockReferencesSize = pp.maxEndorserBlockReferencesSize.unTHKD.value,
        maxEndorserBlockTxsSize = pp.maxEndorserBlockTxsSize.unTHKD.value,
        maxEndorserBlockExUnits = pp.maxEndorserBlockExUnits.unTHKD.unOrdExUnits.units,
        maxRefScriptSizePerEndorserBlock = pp.maxRefScriptSizePerEndorserBlock.unTHKD.value,
        perasMinCandidateBlockAge = pp.perasMinCandidateBlockAge.unTHKD.unSlotInterval.value,
        perasHealingFactor = pp.perasHealingFactor.unTHKD.rational,
        perasCertBoost = pp.perasCertBoost.unTHKD.value,
        perasTargetCommitteeSize = pp.perasTargetCommitteeSize.unTHKD.value,
        perasBootstrapRound = pp.perasBootstrapRound.unTHKD.map(_.value),
        perasQuorumThresholdSafetyMargin = pp.perasQuorumThresholdSafetyMargin.unTHKD.rational,
        refInputsCostPerMultiAssetPolicy = pp.refInputsCostPerMultiAssetPolicy.unTHKD.value,
        refInputsCostPerDatumByte = pp.refInputsCostPerDatumByte.unTHKD.unCoinPerByte.toBigInt
      )

  // ---- an update

  /** The update keys of Dijkstra's parameters (`eraPParams`, `Dijkstra/PParams.hs:741-796`,
    * cardano-ledger 9df9f98c, and the `ppUpdate` of each): Conway's 0 to 11 and 16 to 33, then 34
    * to 56 for the fields Dijkstra adds. The protocol version has none.
    */
  private val updateKeys: Set[Int] = ((0 to 11) ++ (16 to 56)).toSet

  /** The update a decoded Dijkstra update sets, as the record (`DijkstraPParams StrictMaybe`). Its
    * fields were read at their types by [[DijkstraParamUpdate.decode]]; a key that is not one of
    * the parameters', a value not of its key's form, a ratio outside its interval, a word outside
    * its width, a zero stride or negative units are refused, which the decoder already refused.
    */
  def fromUpdate(u: DijkstraParamUpdate): Either[PParamsError, DijkstraPParams[StrictMaybe]] =
    u.fields.toVector.sortBy(_._1).foldLeftM(emptyUpdate)((acc, kv) => set(acc, kv._1, kv._2))

  private def set(
      acc: DijkstraPParams[StrictMaybe],
      key: Int,
      v: DijkstraParamValue
  ): Either[PParamsError, DijkstraPParams[StrictMaybe]] =
    import DecodeField as F
    import DijkstraParamValue.{CostModels as Models, ExUnits as Units, *}
    def w16(f: DecodeField, n: BigInt) = unsigned[W16](f, n).map(Some(_))
    def w32(f: DecodeField, n: BigInt) = unsigned[W32](f, n).map(Some(_))
    (key, v) match
      case (0, Number(n)) =>
        compact(F.MinFeeA, n).map(c => acc.copy(txFeePerByte = THKD(Some(CoinPerByte(c)))))
      case (1, Number(n)) => compact(F.MinFeeB, n).map(c => acc.copy(txFeeFixed = THKD(Some(c))))
      case (2, Number(n)) => w32(F.MaxBlockBodySize, n).map(w => acc.copy(maxBBSize = THKD(w)))
      case (3, Number(n)) => w32(F.MaxTxSize, n).map(w => acc.copy(maxTxSize = THKD(w)))
      case (4, Number(n)) => w16(F.MaxBlockHeaderSize, n).map(w => acc.copy(maxBHSize = THKD(w)))
      case (5, Number(n)) => compact(F.KeyDeposit, n).map(c => acc.copy(keyDeposit = THKD(Some(c))))
      case (6, Number(n)) =>
        compact(F.PoolDeposit, n).map(c => acc.copy(poolDeposit = THKD(Some(c))))
      case (7, Number(n)) => epochs(F.EMax, n).map(e => acc.copy(eMax = THKD(Some(e))))
      case (8, Number(n)) => w16(F.NOpt, n).map(w => acc.copy(nOpt = THKD(w)))
      case (9, Ratio(r))  => nonNegative(F.A0, r).map(x => acc.copy(a0 = THKD(Some(x))))
      case (10, Ratio(r)) => unit(F.Rho, r).map(x => acc.copy(rho = THKD(Some(x))))
      case (11, Ratio(r)) => unit(F.Tau, r).map(x => acc.copy(tau = THKD(Some(x))))
      case (16, Number(n)) =>
        compact(F.MinPoolCost, n).map(c => acc.copy(minPoolCost = THKD(Some(c))))
      case (17, Number(n)) =>
        compact(F.CoinsPerUTxO, n).map(c => acc.copy(coinsPerUTxOByte = THKD(Some(CoinPerByte(c)))))
      case (18, Models(m)) =>
        CostModels
          .from(m.view.mapValues(_.map(BigInt(_))).toMap)
          .map(c => acc.copy(costModels = THKD(Some(c))))
      case (19, Pair(mem, steps)) =>
        (nonNegative(F.PriceMem, mem), nonNegative(F.PriceSteps, steps))
          .mapN((m, s) => acc.copy(prices = THKD(Some(Prices(m, s)))))
      case (20, Units(m, s)) =>
        units(F.MaxTxExUnits, xyz.kd5ujc.scarab.plutus.ExUnits(m, s))
          .map(x => acc.copy(maxTxExUnits = THKD(Some(x))))
      case (21, Units(m, s)) =>
        units(F.MaxBlockExUnits, xyz.kd5ujc.scarab.plutus.ExUnits(m, s))
          .map(x => acc.copy(maxBlockExUnits = THKD(Some(x))))
      case (22, Number(n)) => w32(F.MaxValueSize, n).map(w => acc.copy(maxValSize = THKD(w)))
      case (23, Number(n)) =>
        w16(F.CollateralPercentage, n).map(w => acc.copy(collateralPercentage = THKD(w)))
      case (24, Number(n)) =>
        w16(F.MaxCollateralInputs, n).map(w => acc.copy(maxCollateralInputs = THKD(w)))
      case (25, Thresholds(Vector(a, b, c, d, e))) =>
        PoolVotingThresholds
          .fromStored(xyz.kd5ujc.scarab.ledger.PoolVotingThresholds(a, b, c, d, e))
          .map(t => acc.copy(poolVotingThresholds = THKD(Some(t))))
      case (26, Thresholds(Vector(a, b, c, d, e, f, g, h, i, j))) =>
        DRepVotingThresholds
          .fromStored(xyz.kd5ujc.scarab.ledger.DRepVotingThresholds(a, b, c, d, e, f, g, h, i, j))
          .map(t => acc.copy(drepVotingThresholds = THKD(Some(t))))
      case (27, Number(n)) =>
        w16(F.CommitteeMinSize, n).map(w => acc.copy(committeeMinSize = THKD(w)))
      case (28, Number(n)) =>
        epochs(F.CommitteeMaxTermLength, n).map(e =>
          acc.copy(committeeMaxTermLength = THKD(Some(e)))
        )
      case (29, Number(n)) =>
        epochs(F.GovActionLifetime, n).map(e => acc.copy(govActionLifetime = THKD(Some(e))))
      case (30, Number(n)) =>
        compact(F.GovActionDeposit, n).map(c => acc.copy(govActionDeposit = THKD(Some(c))))
      case (31, Number(n)) =>
        compact(F.DrepDeposit, n).map(c => acc.copy(drepDeposit = THKD(Some(c))))
      case (32, Number(n)) =>
        epochs(F.DrepActivity, n).map(e => acc.copy(drepActivity = THKD(Some(e))))
      case (33, Ratio(r)) =>
        nonNegative(F.MinFeeRefScriptCostPerByte, r)
          .map(x => acc.copy(minFeeRefScriptCostPerByte = THKD(Some(x))))
      case (34, Number(n)) =>
        w32(F.MaxRefScriptSizePerBlock, n).map(w => acc.copy(maxRefScriptSizePerBlock = THKD(w)))
      case (35, Number(n)) =>
        w32(F.MaxRefScriptSizePerTx, n).map(w => acc.copy(maxRefScriptSizePerTx = THKD(w)))
      case (36, Number(n)) =>
        stride(F.RefScriptCostStride, n).map(s => acc.copy(refScriptCostStride = THKD(Some(s))))
      case (37, Ratio(r)) =>
        positive(F.RefScriptCostMultiplier, r)
          .map(x => acc.copy(refScriptCostMultiplier = THKD(Some(x))))
      case (38, OptionalRatio(r)) =>
        leverage(F.MaxPledgeLeverage, r).map(l => acc.copy(maxPledgeLeverage = THKD(Some(l))))
      case (39, Ratio(r)) =>
        unit(F.MinPoolMargin, r).map(x => acc.copy(minPoolMargin = THKD(Some(x))))
      case (40, Number(n)) =>
        millis(F.LeiosAnnouncementPeriodLength, n)
          .map(m => acc.copy(leiosAnnouncementPeriodLength = THKD(Some(m))))
      case (41, Number(n)) =>
        millis(F.LeiosVotePeriodLength, n).map(m => acc.copy(leiosVotePeriodLength = THKD(Some(m))))
      case (42, Number(n)) =>
        millis(F.LeiosDiffusionPeriodLength, n)
          .map(m => acc.copy(leiosDiffusionPeriodLength = THKD(Some(m))))
      case (43, Number(n)) =>
        w16(F.LeiosCommitteeSize, n).map(w => acc.copy(leiosCommitteeSize = THKD(w)))
      case (44, Ratio(r)) =>
        unit(F.LeiosQuorumStakeThreshold, r)
          .map(x => acc.copy(leiosQuorumStakeThreshold = THKD(Some(x))))
      case (45, Number(n)) =>
        w32(F.MaxEndorserBlockReferencesSize, n)
          .map(w => acc.copy(maxEndorserBlockReferencesSize = THKD(w)))
      case (46, Number(n)) =>
        w32(F.MaxEndorserBlockTxsSize, n).map(w => acc.copy(maxEndorserBlockTxsSize = THKD(w)))
      case (47, Units(m, s)) =>
        units(F.MaxEndorserBlockExUnits, xyz.kd5ujc.scarab.plutus.ExUnits(m, s))
          .map(x => acc.copy(maxEndorserBlockExUnits = THKD(Some(x))))
      case (48, Number(n)) =>
        w32(F.MaxRefScriptSizePerEndorserBlock, n)
          .map(w => acc.copy(maxRefScriptSizePerEndorserBlock = THKD(w)))
      case (49, Number(n)) =>
        unsigned[W32](F.PerasMinCandidateBlockAge, n)
          .map(w => acc.copy(perasMinCandidateBlockAge = THKD(Some(SlotInterval(w)))))
      case (50, Ratio(r)) =>
        positive(F.PerasHealingFactor, r).map(x => acc.copy(perasHealingFactor = THKD(Some(x))))
      case (51, Number(n)) => w16(F.PerasCertBoost, n).map(w => acc.copy(perasCertBoost = THKD(w)))
      case (52, Number(n)) =>
        w16(F.PerasTargetCommitteeSize, n).map(w => acc.copy(perasTargetCommitteeSize = THKD(w)))
      case (53, OptionalNumber(r)) =>
        r.traverse(n => unsigned[W32](F.PerasBootstrapRound, BigInt(n)))
          .map(o => acc.copy(perasBootstrapRound = THKD(Some(o))))
      case (54, Ratio(r)) =>
        unit(F.PerasQuorumThresholdSafetyMargin, r)
          .map(x => acc.copy(perasQuorumThresholdSafetyMargin = THKD(Some(x))))
      case (55, Number(n)) =>
        Right(acc.copy(refInputsCostPerMultiAssetPolicy = THKD(Some(Coin(n)))))
      case (56, Number(n)) =>
        compact(F.RefInputsCostPerDatumByte, n)
          .map(c => acc.copy(refInputsCostPerDatumByte = THKD(Some(CoinPerByte(c)))))
      case (k, _) if updateKeys.contains(k) => Left(PParamsError.ValueNotOfKey(k))
      case (k, _)                           => Left(PParamsError.UnknownKey(k))
