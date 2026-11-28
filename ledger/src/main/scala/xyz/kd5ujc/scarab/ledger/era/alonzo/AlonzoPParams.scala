// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.alonzo

import monocle.{Getter, Lens}
import monocle.macros.GenLens
import xyz.kd5ujc.scarab.ledger.{DecodeField, Nonce, ProtocolParams, Unsigned}
import xyz.kd5ujc.scarab.ledger.Unsigned.Width.{W16, W32}
import xyz.kd5ujc.scarab.ledger.era.*
import xyz.kd5ujc.scarab.ledger.state.{Alonzo, Coin, CompactCoin, EpochInterval}

/** Alonzo's protocol parameters as higher-kinded data (`data AlonzoPParams f era`, cardano-ledger
  * f649f975 `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/PParams.hs:228-284`): with `F` the
  * [[Identity]] they are the parameters, with `F` [[StrictMaybe]] an update. The fields are the
  * reference's, in its order: Shelley's without the minimum UTxO value, and Alonzo's own from the
  * cost per UTxO word. An update can set every field, the protocol version among them. Equal as the
  * reference derives `Eq` (`:294, 304`).
  */
final case class AlonzoPParams[F[_]](
    txFeePerByte: F[CoinPerByte],
    txFeeFixed: F[CompactCoin],
    maxBBSize: F[Unsigned[W32]],
    maxTxSize: F[Unsigned[W32]],
    maxBHSize: F[Unsigned[W16]],
    keyDeposit: F[CompactCoin],
    poolDeposit: F[CompactCoin],
    eMax: F[EpochInterval],
    nOpt: F[Unsigned[W16]],
    a0: F[NonNegativeInterval],
    rho: F[UnitInterval],
    tau: F[UnitInterval],
    d: F[UnitInterval],
    extraEntropy: F[Nonce],
    protocolVersion: F[ProtVer],
    minPoolCost: F[CompactCoin],
    coinsPerUTxOWord: F[CoinPerWord],
    costModels: F[CostModels],
    prices: F[Prices],
    maxTxExUnits: F[OrdExUnits],
    maxBlockExUnits: F[OrdExUnits],
    maxValSize: F[Unsigned[W32]],
    collateralPercentage: F[Unsigned[W16]],
    maxCollateralInputs: F[Unsigned[W16]]
)

object AlonzoPParams:
  /** `emptyAlonzoPParams` (`Alonzo/PParams.hs:481-509`, cardano-ledger f649f975): zero, but for a
    * 2048-byte transaction, 100 pools, the neutral nonce, a 150% collateral of at most 5 inputs,
    * and Alonzo's lowest version, 5 (`eraProtVerLow`).
    */
  val empty: AlonzoPParams[Identity] = AlonzoPParams[Identity](
    CoinPerByte(CompactCoin.zero),
    CompactCoin.zero,
    Unsigned.lit[W32](0),
    Unsigned.lit[W32](2048),
    Unsigned.lit[W16](0),
    CompactCoin.zero,
    CompactCoin.zero,
    EpochInterval.zero,
    Unsigned.lit[W16](100),
    NonNegativeInterval.zero,
    UnitInterval.zero,
    UnitInterval.zero,
    UnitInterval.zero,
    Nonce.Neutral,
    ProtVer(summon[Era[Alonzo]].eraProtVerLow, Unsigned.lit[W32](0)),
    CompactCoin.zero,
    CoinPerWord(Coin(0)),
    CostModels.empty,
    Prices.zero,
    OrdExUnits.zero,
    OrdExUnits.zero,
    Unsigned.lit[W32](0),
    Unsigned.lit[W16](150),
    Unsigned.lit[W16](5)
  )

  /** `emptyAlonzoPParamsUpdate` (`Alonzo/PParams.hs:511-539`, cardano-ledger f649f975): no field
    * set.
    */
  val emptyUpdate: AlonzoPParams[StrictMaybe] = AlonzoPParams[StrictMaybe](
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    None
  )

  /** The class's default `applyPPUpdates`, `genericApplyPPUpdates` (`Core/PParams.hs:322-358`,
    * cardano-ledger f649f975), which Alonzo does not override (`Alonzo/PParams.hs:314-369`): each
    * field the update sets replaces the parameter's whole, the cost models among them.
    */
  def applyUpdates(
      pp: AlonzoPParams[Identity],
      ppu: AlonzoPParams[StrictMaybe]
  ): AlonzoPParams[Identity] =
    AlonzoPParams[Identity](
      ppu.txFeePerByte.getOrElse(pp.txFeePerByte),
      ppu.txFeeFixed.getOrElse(pp.txFeeFixed),
      ppu.maxBBSize.getOrElse(pp.maxBBSize),
      ppu.maxTxSize.getOrElse(pp.maxTxSize),
      ppu.maxBHSize.getOrElse(pp.maxBHSize),
      ppu.keyDeposit.getOrElse(pp.keyDeposit),
      ppu.poolDeposit.getOrElse(pp.poolDeposit),
      ppu.eMax.getOrElse(pp.eMax),
      ppu.nOpt.getOrElse(pp.nOpt),
      ppu.a0.getOrElse(pp.a0),
      ppu.rho.getOrElse(pp.rho),
      ppu.tau.getOrElse(pp.tau),
      ppu.d.getOrElse(pp.d),
      ppu.extraEntropy.getOrElse(pp.extraEntropy),
      ppu.protocolVersion.getOrElse(pp.protocolVersion),
      ppu.minPoolCost.getOrElse(pp.minPoolCost),
      ppu.coinsPerUTxOWord.getOrElse(pp.coinsPerUTxOWord),
      ppu.costModels.getOrElse(pp.costModels),
      ppu.prices.getOrElse(pp.prices),
      ppu.maxTxExUnits.getOrElse(pp.maxTxExUnits),
      ppu.maxBlockExUnits.getOrElse(pp.maxBlockExUnits),
      ppu.maxValSize.getOrElse(pp.maxValSize),
      ppu.collateralPercentage.getOrElse(pp.collateralPercentage),
      ppu.maxCollateralInputs.getOrElse(pp.maxCollateralInputs)
    )

  // ---- the parameters the node keeps

  import StoredPParams.{compact, epochs, nonNegative, protVer, unit, units, unsigned}

  /** Alonzo's fields of the parameters the node keeps, each at the reference's type. The node's
    * record holds every era's fields; the others are not read. A field outside its type's range is
    * refused ([[StoredPParams]]); the cost per UTxO word is a `Coin`, of no range.
    */
  def fromStored(p: ProtocolParams): Either[PParamsError, AlonzoPParams[Identity]] =
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
      d <- unit(DecodeField.D, p.d)
      version <- protVer(p)
      minPoolCost <- compact(DecodeField.MinPoolCost, p.minPoolCost)
      costModels <- CostModels.from(p.costModels)
      priceMem <- nonNegative(DecodeField.PriceMem, p.priceMem)
      priceSteps <- nonNegative(DecodeField.PriceSteps, p.priceSteps)
      maxTxExUnits <- units(DecodeField.MaxTxExUnits, p.maxTxExUnits)
      maxBlockExUnits <- units(DecodeField.MaxBlockExUnits, p.maxBlockExUnits)
      maxValSize <- unsigned[W32](DecodeField.MaxValueSize, p.maxValueSize)
      collateralPercentage <- unsigned[W16](
        DecodeField.CollateralPercentage,
        p.collateralPercentage
      )
      maxCollateralInputs <- unsigned[W16](DecodeField.MaxCollateralInputs, p.maxCollateralInputs)
    yield AlonzoPParams[Identity](
      CoinPerByte(txFeePerByte),
      txFeeFixed,
      maxBBSize,
      maxTxSize,
      maxBHSize,
      keyDeposit,
      poolDeposit,
      eMax,
      nOpt,
      a0,
      rho,
      tau,
      d,
      p.extraEntropy,
      version,
      minPoolCost,
      CoinPerWord(Coin(p.coinsPerUTxOWord)),
      costModels,
      Prices(priceMem, priceSteps),
      maxTxExUnits,
      maxBlockExUnits,
      maxValSize,
      collateralPercentage,
      maxCollateralInputs
    )

  /** The parameters `base` with Alonzo's fields those of `pp`: the inverse of [[fromStored]] over
    * the node's record, whose other eras' fields `base` keeps.
    */
  def toStored(pp: AlonzoPParams[Identity], base: ProtocolParams): ProtocolParams =
    base.copy(
      minFeeA = pp.txFeePerByte.unCoinPerByte.toBigInt,
      minFeeB = pp.txFeeFixed.toBigInt,
      maxBlockBodySize = pp.maxBBSize.value,
      maxTxSize = pp.maxTxSize.value,
      maxBlockHeaderSize = pp.maxBHSize.value,
      keyDeposit = pp.keyDeposit.toBigInt,
      poolDeposit = pp.poolDeposit.toBigInt,
      eMax = BigInt(pp.eMax.toLong),
      nOpt = pp.nOpt.value,
      a0 = pp.a0.rational,
      rho = pp.rho.rational,
      tau = pp.tau.rational,
      d = pp.d.rational,
      extraEntropy = pp.extraEntropy,
      protocolMajor = BigInt(pp.protocolVersion.pvMajor.value),
      protocolMinor = pp.protocolVersion.pvMinor.value,
      minPoolCost = pp.minPoolCost.toBigInt,
      coinsPerUTxOWord = pp.coinsPerUTxOWord.unCoinPerWord.value,
      costModels = pp.costModels.models,
      priceMem = pp.prices.prMem.rational,
      priceSteps = pp.prices.prSteps.rational,
      maxTxExUnits = pp.maxTxExUnits.unOrdExUnits.units,
      maxBlockExUnits = pp.maxBlockExUnits.unOrdExUnits.units,
      maxValueSize = pp.maxValSize.value,
      collateralPercentage = pp.collateralPercentage.value,
      maxCollateralInputs = pp.maxCollateralInputs.value
    )

/** Alonzo's instances (`instance EraPParams AlonzoEra` and `instance AlonzoEraPParams AlonzoEra`,
  * `Alonzo/PParams.hs:314-387`, cardano-ledger f649f975), with the members of the ranges Alonzo is
  * in: the cost per UTxO word ([[AlonzoEraPParamsExactAlonzo]]), the decentralisation and the extra
  * entropy ([[EraPParamsToAlonzo]]) and the protocol version ([[EraPParamsToBabbage]]). Not here:
  * the class's upgrade from Mary and downgrade to it (`upgradeAlonzoPParams`,
  * `downgradeAlonzoPParams`, `:322-324, 599-656`), which need Mary's record (#85).
  */
private[era] object AlonzoEraPParamsInstance
    extends AlonzoEraPParamsExactAlonzo[Alonzo]
    with EraPParamsToAlonzo[Alonzo]
    with EraPParamsToBabbage[Alonzo]:
  private type R[F[_]] = AlonzoPParams[F]

  def applyPPUpdates(pp: PParams[Alonzo], ppu: PParamsUpdate[Alonzo]): PParams[Alonzo] =
    PParams[Alonzo](AlonzoPParams.applyUpdates(pp.hkd, ppu.hkd))

  def emptyPParamsIdentity: AlonzoPParams[Identity] = AlonzoPParams.empty

  def emptyPParamsStrictMaybe: AlonzoPParams[StrictMaybe] = AlonzoPParams.emptyUpdate

  def hkdTxFeePerByteL[F[_]: HKDFunctor]: Lens[R[F], F[CoinPerByte]] =
    GenLens[R[F]](_.txFeePerByte)
  def hkdTxFeeFixedCompactL[F[_]: HKDFunctor]: Lens[R[F], F[CompactCoin]] =
    GenLens[R[F]](_.txFeeFixed)
  def hkdMaxBBSizeL[F[_]: HKDFunctor]: Lens[R[F], F[Unsigned[W32]]] = GenLens[R[F]](_.maxBBSize)
  def hkdMaxTxSizeL[F[_]: HKDFunctor]: Lens[R[F], F[Unsigned[W32]]] = GenLens[R[F]](_.maxTxSize)
  def hkdMaxBHSizeL[F[_]: HKDFunctor]: Lens[R[F], F[Unsigned[W16]]] = GenLens[R[F]](_.maxBHSize)
  def hkdKeyDepositCompactL[F[_]: HKDFunctor]: Lens[R[F], F[CompactCoin]] =
    GenLens[R[F]](_.keyDeposit)
  def hkdPoolDepositCompactL[F[_]: HKDFunctor]: Lens[R[F], F[CompactCoin]] =
    GenLens[R[F]](_.poolDeposit)
  def hkdEMaxL[F[_]: HKDFunctor]: Lens[R[F], F[EpochInterval]] = GenLens[R[F]](_.eMax)
  def hkdNOptL[F[_]: HKDFunctor]: Lens[R[F], F[Unsigned[W16]]] = GenLens[R[F]](_.nOpt)
  def hkdA0L[F[_]: HKDFunctor]: Lens[R[F], F[NonNegativeInterval]] = GenLens[R[F]](_.a0)
  def hkdRhoL[F[_]: HKDFunctor]: Lens[R[F], F[UnitInterval]] = GenLens[R[F]](_.rho)
  def hkdTauL[F[_]: HKDFunctor]: Lens[R[F], F[UnitInterval]] = GenLens[R[F]](_.tau)
  def hkdDL[F[_]: HKDFunctor]: Lens[R[F], F[UnitInterval]] = GenLens[R[F]](_.d)
  def hkdExtraEntropyL[F[_]: HKDFunctor]: Lens[R[F], F[Nonce]] = GenLens[R[F]](_.extraEntropy)
  def hkdProtocolVersionL[F[_]: HKDFunctor]: Lens[R[F], F[ProtVer]] =
    GenLens[R[F]](_.protocolVersion)
  def hkdMinPoolCostCompactL[F[_]: HKDFunctor]: Lens[R[F], F[CompactCoin]] =
    GenLens[R[F]](_.minPoolCost)

  /** The class's default up to Conway, `L.to (const (MaxPledgeLeverage SNothing))`
    * (`Core/PParams.hs:510-516`, cardano-ledger 9df9f98c): no limit.
    */
  val ppMaxPledgeLeverageG: Getter[PParams[Alonzo], MaxPledgeLeverage] =
    Getter(_ => MaxPledgeLeverage.none)

  /** The class's default up to Conway, `L.to (const minBound)` (`Core/PParams.hs:518-524`,
    * cardano-ledger 9df9f98c).
    */
  val ppMinPoolMarginG: Getter[PParams[Alonzo], UnitInterval] = Getter(_ => UnitInterval.zero)

  /** `eraPParams` (`Alonzo/PParams.hs:344-369`, cardano-ledger f649f975). */
  lazy val eraPParams: Vector[PParam[Alonzo]] =
    import EraPParams.*, AlonzoEraPParams.*, AlonzoEraPParamsExactAlonzo.*, EraPParamsToAlonzo.*,
      EraPParamsToBabbage.*
    given AlonzoEraPParamsInstance.type = this
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
      ppD,
      ppExtraEntropy,
      ppProtocolVersion,
      ppMinPoolCost,
      ppCoinsPerUTxOWord,
      ppCostModels,
      ppPrices,
      ppMaxTxExUnits,
      ppMaxBlockExUnits,
      ppMaxValSize,
      ppCollateralPercentage,
      ppMaxCollateralInputs
    )

  def hkdCoinsPerUTxOWordL[F[_]: HKDFunctor]: Lens[R[F], F[CoinPerWord]] =
    GenLens[R[F]](_.coinsPerUTxOWord)
  def hkdCostModelsL[F[_]: HKDFunctor]: Lens[R[F], F[CostModels]] = GenLens[R[F]](_.costModels)
  def hkdPricesL[F[_]: HKDFunctor]: Lens[R[F], F[Prices]] = GenLens[R[F]](_.prices)

  /** `hkdMap unOrdExUnits` over the field, and `hkdMap OrdExUnits` back
    * (`Alonzo/PParams.hs:375-382`, cardano-ledger f649f975).
    */
  def hkdMaxTxExUnitsL[F[_]: HKDFunctor]: Lens[R[F], F[ExUnits]] =
    GenLens[R[F]](_.maxTxExUnits).andThen(OrdExUnits.hkdUnits[F])
  def hkdMaxBlockExUnitsL[F[_]: HKDFunctor]: Lens[R[F], F[ExUnits]] =
    GenLens[R[F]](_.maxBlockExUnits).andThen(OrdExUnits.hkdUnits[F])
  def hkdMaxValSizeL[F[_]: HKDFunctor]: Lens[R[F], F[Unsigned[W32]]] =
    GenLens[R[F]](_.maxValSize)
  def hkdCollateralPercentageL[F[_]: HKDFunctor]: Lens[R[F], F[Unsigned[W16]]] =
    GenLens[R[F]](_.collateralPercentage)
  def hkdMaxCollateralInputsL[F[_]: HKDFunctor]: Lens[R[F], F[Unsigned[W16]]] =
    GenLens[R[F]](_.maxCollateralInputs)
