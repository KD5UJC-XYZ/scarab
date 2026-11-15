// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.conway

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.ledger.{
  ConwayParam,
  ConwayParamUpdate,
  DRepGroup,
  DecodeField,
  ProtocolParams,
  StakePoolGroup,
  Unsigned
}
import xyz.kd5ujc.scarab.ledger.Unsigned.Width.{W16, W32, W64}
import xyz.kd5ujc.scarab.ledger.era.*
import xyz.kd5ujc.scarab.ledger.state.{CompactCoin, EpochInterval}

import ConwayPParams.{Eco, Gov, Net, NoPool, Sec, Tech}

/** Conway's protocol parameters as higher-kinded data (`data ConwayPParams f era`, cardano-ledger
  * f649f975 `eras/conway/impl/src/Cardano/Ledger/Conway/PParams.hs:643-709`): with `F` the
  * [[Identity]] they are the parameters, with `F` [[StrictMaybe]] an update. The fields are the
  * reference's, in its order, each tagged with its groups ([[THKD]]); the protocol version is the
  * one field an update cannot set ([[HKDNoUpdate]]). Equal as the reference derives `Eq`.
  */
final case class ConwayPParams[F[_]](
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
    minFeeRefScriptCostPerByte: THKD[Groups[Eco, Sec], F, NonNegativeInterval]
)

object ConwayPParams:
  type Net = DRepGroup.Network.type
  type Eco = DRepGroup.Economic.type
  type Tech = DRepGroup.Technical.type
  type Gov = DRepGroup.Gov.type
  type Sec = StakePoolGroup.Security.type
  type NoPool = StakePoolGroup.NoStakePool.type

  /** `emptyConwayPParams` (`Conway/PParams.hs:987-1022`, cardano-ledger f649f975): zero, but for a
    * 2048-byte transaction, 100 pools, a 150% collateral of at most 5 inputs, and Conway's lowest
    * version, 9.
    */
  val empty: ConwayPParams[Identity] = ConwayPParams[Identity](
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
    ProtVer(Version.lit(9), Unsigned.lit[W32](0)),
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
    THKD(NonNegativeInterval.zero)
  )

  /** `emptyConwayPParamsUpdate` (`Conway/PParams.hs:1024-1059`, cardano-ledger f649f975): no field
    * set.
    */
  val emptyUpdate: ConwayPParams[StrictMaybe] = ConwayPParams[StrictMaybe](
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
    THKD(None)
  )

  /** `conwayApplyPPUpdates` (`Conway/PParams.hs:1183-1231`, cardano-ledger f649f975): each field
    * the update sets replaces the parameter's, the protocol version is kept, and the update's cost
    * models replace those of their languages (`updateCostModels`).
    */
  def applyUpdates(
      pp: ConwayPParams[Identity],
      ppu: ConwayPParams[StrictMaybe]
  ): ConwayPParams[Identity] =
    def set[G, A](
        old: THKD[G, Identity, A],
        update: THKD[G, StrictMaybe, A]
    ): THKD[G, Identity, A] =
      THKD(update.unTHKD.getOrElse(old.unTHKD))
    ConwayPParams[Identity](
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
      set(pp.minFeeRefScriptCostPerByte, ppu.minFeeRefScriptCostPerByte)
    )

  /** The groups of the fields an update sets (`modifiedPPGroups`, the generic default of
    * `Conway/PParams.hs:173-181`, cardano-ledger f649f975 over `ppGroup`, `:625-632`), the protocol
    * version having none.
    */
  def modifiedGroups(
      ppu: ConwayPParams[StrictMaybe]
  ): scala.collection.immutable.SortedSet[PPGroups] =
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
      ppGroup(ppu.minFeeRefScriptCostPerByte)
    ).foldLeft(scala.collection.immutable.SortedSet.empty[PPGroups])(_ ++ _)

  // ---- the parameters the node keeps

  private type Read[A] = Either[PParamsError, A]

  private def unsigned[W <: Unsigned.Width: Unsigned.Bound](
      field: DecodeField,
      n: BigInt
  ): Read[Unsigned[W]] =
    Unsigned.from[W](n).toRight(PParamsError.OutOfRange(field, n))

  private def compact(field: DecodeField, n: BigInt): Read[CompactCoin] =
    unsigned[W64](field, n).map(w => CompactCoin.fromUnsigned(w.value.toLong))

  private def epochs(field: DecodeField, n: BigInt): Read[EpochInterval] =
    unsigned[W32](field, n).map(EpochInterval.fromWord32)

  private def unit(field: DecodeField, r: Rational): Read[UnitInterval] =
    UnitInterval.boundRational(r).toRight(PParamsError.RatioOutOfBounds(field, r))

  private def nonNegative(field: DecodeField, r: Rational): Read[NonNegativeInterval] =
    NonNegativeInterval.boundRational(r).toRight(PParamsError.RatioOutOfBounds(field, r))

  private def units(field: DecodeField, u: xyz.kd5ujc.scarab.plutus.ExUnits): Read[OrdExUnits] =
    ExUnits.from(u).map(OrdExUnits(_)).toRight(PParamsError.NegativeExUnits(field, u))

  private def protVer(p: ProtocolParams): Read[ProtVer] =
    (
      Version
        .mkVersion(p.protocolMajor)
        .toRight(
          PParamsError.OutOfRange(DecodeField.ProtocolMajor, p.protocolMajor)
        ),
      unsigned[W32](DecodeField.ProtocolVersion, p.protocolMinor)
    ).mapN(ProtVer(_, _))

  /** Conway's fields of the parameters the node keeps, each at the reference's type. The node's
    * record holds every era's fields; the others are not read. A field outside its type's range is
    * refused: the node read each at its type, so this does not happen to parameters it decoded.
    */
  def fromStored(p: ProtocolParams): Either[PParamsError, ConwayPParams[Identity]] =
    for
      txFeePerByte <- compact(DecodeField.MinFeeA, p.minFeeA)
      txFeeFixed <- compact(DecodeField.MinFeeB, p.minFeeB)
      maxBBSize <- unsigned[W32](DecodeField.MaxBlockBodySize, p.maxBlockBodySize)
      maxTxSize <- unsigned[W32](DecodeField.MaxTxSize, p.maxTxSize)
      maxBHSize <- unsigned[W16](DecodeField.MaxBlockHeaderSize, p.maxBlockHeaderSize)
      keyDeposit <- compact(DecodeField.KeyDeposit, p.keyDeposit)
      poolDeposit <- compact(DecodeField.PoolDeposit, p.poolDeposit)
      eMax <- epochs(DecodeField.EMax, p.eMax)
      nOpt <- unsigned[W16](DecodeField.NOpt, p.nOpt)
      a0 <- nonNegative(DecodeField.A0, p.a0)
      rho <- unit(DecodeField.Rho, p.rho)
      tau <- unit(DecodeField.Tau, p.tau)
      version <- protVer(p)
      minPoolCost <- compact(DecodeField.MinPoolCost, p.minPoolCost)
      coinsPerUTxOByte <- compact(DecodeField.CoinsPerUTxO, p.coinsPerUTxOByte)
      priceMem <- nonNegative(DecodeField.PriceMem, p.priceMem)
      priceSteps <- nonNegative(DecodeField.PriceSteps, p.priceSteps)
      maxValSize <- unsigned[W32](DecodeField.MaxValueSize, p.maxValueSize)
      collateralPercentage <- unsigned[W16](
        DecodeField.CollateralPercentage,
        p.collateralPercentage
      )
      maxCollateralInputs <- unsigned[W16](DecodeField.MaxCollateralInputs, p.maxCollateralInputs)
      committeeMinSize <- unsigned[W16](DecodeField.CommitteeMinSize, p.committeeMinSize)
      committeeMaxTermLength <- epochs(DecodeField.CommitteeMaxTermLength, p.committeeMaxTermLength)
      govActionLifetime <- epochs(DecodeField.GovActionLifetime, p.govActionLifetime)
      govActionDeposit <- compact(DecodeField.GovActionDeposit, p.govActionDeposit)
      drepDeposit <- compact(DecodeField.DrepDeposit, p.drepDeposit)
      drepActivity <- epochs(DecodeField.DrepActivity, p.drepActivity)
      costModels <- CostModels.from(p.costModels)
      maxTxExUnits <- units(DecodeField.MaxTxExUnits, p.maxTxExUnits)
      maxBlockExUnits <- units(DecodeField.MaxBlockExUnits, p.maxBlockExUnits)
      poolVotingThresholds <- PoolVotingThresholds.fromStored(p.poolVotingThresholds)
      drepVotingThresholds <- DRepVotingThresholds.fromStored(p.drepVotingThresholds)
      minFeeRefScriptCostPerByte <- nonNegative(
        DecodeField.MinFeeRefScriptCostPerByte,
        p.minFeeRefScriptCostPerByte
      )
    yield ConwayPParams[Identity](
      THKD(CoinPerByte(txFeePerByte)),
      THKD(txFeeFixed),
      THKD(maxBBSize),
      THKD(maxTxSize),
      THKD(maxBHSize),
      THKD(keyDeposit),
      THKD(poolDeposit),
      THKD(eMax),
      THKD(nOpt),
      THKD(a0),
      THKD(rho),
      THKD(tau),
      version,
      THKD(minPoolCost),
      THKD(CoinPerByte(coinsPerUTxOByte)),
      THKD(costModels),
      THKD(Prices(priceMem, priceSteps)),
      THKD(maxTxExUnits),
      THKD(maxBlockExUnits),
      THKD(maxValSize),
      THKD(collateralPercentage),
      THKD(maxCollateralInputs),
      THKD(poolVotingThresholds),
      THKD(drepVotingThresholds),
      THKD(committeeMinSize),
      THKD(committeeMaxTermLength),
      THKD(govActionLifetime),
      THKD(govActionDeposit),
      THKD(drepDeposit),
      THKD(drepActivity),
      THKD(minFeeRefScriptCostPerByte)
    )

  /** The parameters `base` with Conway's fields those of `pp`: the inverse of [[fromStored]] over
    * the node's record, whose other eras' fields `base` keeps.
    */
  def toStored(pp: ConwayPParams[Identity], base: ProtocolParams): ProtocolParams =
    base.copy(
      minFeeA = pp.txFeePerByte.unTHKD.unCoinPerByte.toBigInt,
      minFeeB = pp.txFeeFixed.unTHKD.toBigInt,
      maxBlockBodySize = pp.maxBBSize.unTHKD.value,
      maxTxSize = pp.maxTxSize.unTHKD.value,
      maxBlockHeaderSize = pp.maxBHSize.unTHKD.value,
      keyDeposit = pp.keyDeposit.unTHKD.toBigInt,
      poolDeposit = pp.poolDeposit.unTHKD.toBigInt,
      eMax = BigInt(pp.eMax.unTHKD.toLong),
      nOpt = pp.nOpt.unTHKD.value,
      a0 = pp.a0.unTHKD.rational,
      rho = pp.rho.unTHKD.rational,
      tau = pp.tau.unTHKD.rational,
      protocolMajor = BigInt(pp.protocolVersion.pvMajor.value),
      protocolMinor = pp.protocolVersion.pvMinor.value,
      minPoolCost = pp.minPoolCost.unTHKD.toBigInt,
      coinsPerUTxOByte = pp.coinsPerUTxOByte.unTHKD.unCoinPerByte.toBigInt,
      costModels = pp.costModels.unTHKD.models,
      priceMem = pp.prices.unTHKD.prMem.rational,
      priceSteps = pp.prices.unTHKD.prSteps.rational,
      maxTxExUnits = pp.maxTxExUnits.unTHKD.unOrdExUnits.units,
      maxBlockExUnits = pp.maxBlockExUnits.unTHKD.unOrdExUnits.units,
      maxValueSize = pp.maxValSize.unTHKD.value,
      collateralPercentage = pp.collateralPercentage.unTHKD.value,
      maxCollateralInputs = pp.maxCollateralInputs.unTHKD.value,
      poolVotingThresholds = pp.poolVotingThresholds.unTHKD.toStored,
      drepVotingThresholds = pp.drepVotingThresholds.unTHKD.toStored,
      committeeMinSize = pp.committeeMinSize.unTHKD.value,
      committeeMaxTermLength = BigInt(pp.committeeMaxTermLength.unTHKD.toLong),
      govActionLifetime = BigInt(pp.govActionLifetime.unTHKD.toLong),
      govActionDeposit = pp.govActionDeposit.unTHKD.toBigInt,
      drepDeposit = pp.drepDeposit.unTHKD.toBigInt,
      drepActivity = BigInt(pp.drepActivity.unTHKD.toLong),
      minFeeRefScriptCostPerByte = pp.minFeeRefScriptCostPerByte.unTHKD.rational
    )

  // ---- an update

  private def w64(u: Unsigned[W64]): CompactCoin = CompactCoin.fromUnsigned(u.value.toLong)

  /** The update a decoded Conway update sets, as the record (`ConwayPParams StrictMaybe`). Its
    * fields were read at their types; a ratio outside its interval, a cost model outside its words
    * or negative units are refused, which the decoder already refused.
    */
  def fromUpdate(u: ConwayParamUpdate): Either[PParamsError, ConwayPParams[StrictMaybe]] =
    u.params.foldLeftM(emptyUpdate)((acc, p) => set(acc, p))

  private def set(
      acc: ConwayPParams[StrictMaybe],
      p: ConwayParam
  ): Either[PParamsError, ConwayPParams[StrictMaybe]] = p match
    case ConwayParam.TxFeePerByte(v) =>
      Right(acc.copy(txFeePerByte = THKD(Some(CoinPerByte(w64(v))))))
    case ConwayParam.TxFeeFixed(v)  => Right(acc.copy(txFeeFixed = THKD(Some(w64(v)))))
    case ConwayParam.MaxBBSize(v)   => Right(acc.copy(maxBBSize = THKD(Some(v))))
    case ConwayParam.MaxTxSize(v)   => Right(acc.copy(maxTxSize = THKD(Some(v))))
    case ConwayParam.MaxBHSize(v)   => Right(acc.copy(maxBHSize = THKD(Some(v))))
    case ConwayParam.KeyDeposit(v)  => Right(acc.copy(keyDeposit = THKD(Some(w64(v)))))
    case ConwayParam.PoolDeposit(v) => Right(acc.copy(poolDeposit = THKD(Some(w64(v)))))
    case ConwayParam.EMax(v) => Right(acc.copy(eMax = THKD(Some(EpochInterval.fromWord32(v)))))
    case ConwayParam.NOpt(v) => Right(acc.copy(nOpt = THKD(Some(v))))
    case ConwayParam.A0(v)  => nonNegative(DecodeField.A0, v).map(r => acc.copy(a0 = THKD(Some(r))))
    case ConwayParam.Rho(v) => unit(DecodeField.Rho, v).map(r => acc.copy(rho = THKD(Some(r))))
    case ConwayParam.Tau(v) => unit(DecodeField.Tau, v).map(r => acc.copy(tau = THKD(Some(r))))
    case ConwayParam.MinPoolCost(v) => Right(acc.copy(minPoolCost = THKD(Some(w64(v)))))
    case ConwayParam.CoinsPerUTxOByte(v) =>
      Right(acc.copy(coinsPerUTxOByte = THKD(Some(CoinPerByte(w64(v))))))
    case ConwayParam.CostModels(m) =>
      CostModels.from(m).map(c => acc.copy(costModels = THKD(Some(c))))
    case ConwayParam.Prices(m, s) =>
      (nonNegative(DecodeField.PriceMem, m), nonNegative(DecodeField.PriceSteps, s))
        .mapN((mem, steps) => acc.copy(prices = THKD(Some(Prices(mem, steps)))))
    case ConwayParam.MaxTxExUnits(v) =>
      units(DecodeField.MaxTxExUnits, v).map(u => acc.copy(maxTxExUnits = THKD(Some(u))))
    case ConwayParam.MaxBlockExUnits(v) =>
      units(DecodeField.MaxBlockExUnits, v).map(u => acc.copy(maxBlockExUnits = THKD(Some(u))))
    case ConwayParam.MaxValSize(v) => Right(acc.copy(maxValSize = THKD(Some(v))))
    case ConwayParam.CollateralPercentage(v) =>
      Right(acc.copy(collateralPercentage = THKD(Some(v))))
    case ConwayParam.MaxCollateralInputs(v) =>
      Right(acc.copy(maxCollateralInputs = THKD(Some(v))))
    case ConwayParam.PoolThresholds(v) =>
      PoolVotingThresholds.fromStored(v).map(t => acc.copy(poolVotingThresholds = THKD(Some(t))))
    case ConwayParam.DRepThresholds(v) =>
      DRepVotingThresholds.fromStored(v).map(t => acc.copy(drepVotingThresholds = THKD(Some(t))))
    case ConwayParam.CommitteeMinSize(v) => Right(acc.copy(committeeMinSize = THKD(Some(v))))
    case ConwayParam.CommitteeMaxTermLength(v) =>
      Right(acc.copy(committeeMaxTermLength = THKD(Some(EpochInterval.fromWord32(v)))))
    case ConwayParam.GovActionLifetime(v) =>
      Right(acc.copy(govActionLifetime = THKD(Some(EpochInterval.fromWord32(v)))))
    case ConwayParam.GovActionDeposit(v) => Right(acc.copy(govActionDeposit = THKD(Some(w64(v)))))
    case ConwayParam.DRepDeposit(v)      => Right(acc.copy(drepDeposit = THKD(Some(w64(v)))))
    case ConwayParam.DRepActivity(v) =>
      Right(acc.copy(drepActivity = THKD(Some(EpochInterval.fromWord32(v)))))
    case ConwayParam.MinFeeRefScriptCostPerByte(v) =>
      nonNegative(DecodeField.MinFeeRefScriptCostPerByte, v)
        .map(r => acc.copy(minFeeRefScriptCostPerByte = THKD(Some(r))))
