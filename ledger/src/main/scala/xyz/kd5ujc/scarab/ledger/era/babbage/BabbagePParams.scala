// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.babbage

import monocle.{Getter, Lens}
import monocle.macros.GenLens
import xyz.kd5ujc.scarab.ledger.{DecodeField, Nonce, ProtocolParams, Unsigned}
import xyz.kd5ujc.scarab.ledger.Unsigned.Width.{W16, W32}
import xyz.kd5ujc.scarab.ledger.era.*
import xyz.kd5ujc.scarab.ledger.era.alonzo.AlonzoPParams
import xyz.kd5ujc.scarab.ledger.state.{Babbage, Coin, CompactCoin, EpochInterval, VocabError}

/** Babbage's protocol parameters as higher-kinded data (`data BabbagePParams f era`, cardano-ledger
  * f649f975 `eras/babbage/impl/src/Cardano/Ledger/Babbage/PParams.hs:89-136`): with `F` the
  * [[Identity]] they are the parameters, with `F` [[StrictMaybe]] an update. The fields are the
  * reference's, in its order: Alonzo's without the decentralisation, the extra entropy and the cost
  * per UTxO word, and with the cost per UTxO byte. An update can set every field, the protocol
  * version among them. Equal as the reference derives `Eq` (`:147, 157`).
  */
final case class BabbagePParams[F[_]](
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
    protocolVersion: F[ProtVer],
    minPoolCost: F[CompactCoin],
    coinsPerUTxOByte: F[CoinPerByte],
    costModels: F[CostModels],
    prices: F[Prices],
    maxTxExUnits: F[OrdExUnits],
    maxBlockExUnits: F[OrdExUnits],
    maxValSize: F[Unsigned[W32]],
    collateralPercentage: F[Unsigned[W16]],
    maxCollateralInputs: F[Unsigned[W16]]
)

/** What a downgrade to Alonzo gives back (`data DowngradeBabbagePParams f`,
  * `Babbage/PParams.hs:167-170`, cardano-ledger f649f975): the decentralisation and the extra
  * entropy, which Babbage dropped.
  */
final case class DowngradeBabbagePParams[F[_]](d: F[UnitInterval], extraEntropy: F[Nonce])

object BabbagePParams:
  /** `emptyBabbagePParams` (`Babbage/PParams.hs:261-287`, cardano-ledger f649f975): zero, but for a
    * 2048-byte transaction, 100 pools, a 150% collateral of at most 5 inputs, and Babbage's lowest
    * version, 7 (`eraProtVerLow`).
    */
  val empty: BabbagePParams[Identity] = BabbagePParams[Identity](
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
    ProtVer(summon[Era[Babbage]].eraProtVerLow, Unsigned.lit[W32](0)),
    CompactCoin.zero,
    CoinPerByte(CompactCoin.zero),
    CostModels.empty,
    Prices.zero,
    OrdExUnits.zero,
    OrdExUnits.zero,
    Unsigned.lit[W32](0),
    Unsigned.lit[W16](150),
    Unsigned.lit[W16](5)
  )

  /** `emptyBabbagePParamsUpdate` (`Babbage/PParams.hs:289-314`, cardano-ledger f649f975): no field
    * set.
    */
  val emptyUpdate: BabbagePParams[StrictMaybe] = BabbagePParams[StrictMaybe](
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
    * cardano-ledger f649f975), which Babbage does not override (`Babbage/PParams.hs:172-226`): each
    * field the update sets replaces the parameter's whole, the cost models among them, which are
    * replaced, not merged per language as Conway's are (`conwayApplyPPUpdates`).
    */
  def applyUpdates(
      pp: BabbagePParams[Identity],
      ppu: BabbagePParams[StrictMaybe]
  ): BabbagePParams[Identity] =
    BabbagePParams[Identity](
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
      ppu.protocolVersion.getOrElse(pp.protocolVersion),
      ppu.minPoolCost.getOrElse(pp.minPoolCost),
      ppu.coinsPerUTxOByte.getOrElse(pp.coinsPerUTxOByte),
      ppu.costModels.getOrElse(pp.costModels),
      ppu.prices.getOrElse(pp.prices),
      ppu.maxTxExUnits.getOrElse(pp.maxTxExUnits),
      ppu.maxBlockExUnits.getOrElse(pp.maxBlockExUnits),
      ppu.maxValSize.getOrElse(pp.maxValSize),
      ppu.collateralPercentage.getOrElse(pp.collateralPercentage),
      ppu.maxCollateralInputs.getOrElse(pp.maxCollateralInputs)
    )

  // ---- the upgrade from Alonzo and the downgrade to it

  /** `upgradeBabbagePParams True` (`Babbage/PParams.hs:316-353`, cardano-ledger f649f975), the
    * class's `upgradePParamsHKD ()` at Babbage (`:180`): Alonzo's fields but the decentralisation
    * and the extra entropy, and the cost per UTxO word made the cost per byte
    * (`coinsPerUTxOWordToCoinsPerUTxOByte`, `:389-391`).
    */
  def upgrade[F[_]](a: AlonzoPParams[F])(using f: HKDFunctor[F]): BabbagePParams[F] =
    upgraded(a, f.hkdMap(coinsPerUTxOWordToCoinsPerUTxOByte)(a.coinsPerUTxOWord))

  /** `upgradeBabbagePParams False` (`Babbage/PParams.hs:316-353`, cardano-ledger f649f975), which
    * the translation of a transaction's update proposal into Babbage uses: the cost per UTxO word
    * taken as the cost per byte as it is (`coinsPerUTxOWordToCoinsPerUTxOByteInTx`, `:397-403`),
    * through `toCompactPartial`, which throws on a coin outside `Word64`: here that is the refusal.
    */
  def upgradeInTx[F[_]](a: AlonzoPParams[F])(using
      f: HKDApplicative[F]
  ): Either[VocabError, BabbagePParams[F]] =
    f.hkdSequence(
      f.hkdMap((w: CoinPerWord) => CompactCoin.fromCoin(w.unCoinPerWord).map(CoinPerByte(_)))(
        a.coinsPerUTxOWord
      )
    ).map(upgraded(a, _))

  private def upgraded[F[_]](a: AlonzoPParams[F], coinsPerUTxOByte: F[CoinPerByte]) =
    BabbagePParams[F](
      a.txFeePerByte,
      a.txFeeFixed,
      a.maxBBSize,
      a.maxTxSize,
      a.maxBHSize,
      a.keyDeposit,
      a.poolDeposit,
      a.eMax,
      a.nOpt,
      a.a0,
      a.rho,
      a.tau,
      a.protocolVersion,
      a.minPoolCost,
      coinsPerUTxOByte,
      a.costModels,
      a.prices,
      a.maxTxExUnits,
      a.maxBlockExUnits,
      a.maxValSize,
      a.collateralPercentage,
      a.maxCollateralInputs
    )

  /** `downgradeBabbagePParams` (`Babbage/PParams.hs:355-387`, cardano-ledger f649f975), the class's
    * `downgradePParamsHKD` at Babbage (`:181`): Babbage's fields, the decentralisation and the
    * extra entropy given back, and the cost per byte made the cost per word
    * (`coinsPerUTxOByteToCoinsPerUTxOWord`, `:393-395`).
    */
  def downgrade[F[_]](d: DowngradeBabbagePParams[F], b: BabbagePParams[F])(using
      f: HKDFunctor[F]
  ): AlonzoPParams[F] =
    AlonzoPParams[F](
      b.txFeePerByte,
      b.txFeeFixed,
      b.maxBBSize,
      b.maxTxSize,
      b.maxBHSize,
      b.keyDeposit,
      b.poolDeposit,
      b.eMax,
      b.nOpt,
      b.a0,
      b.rho,
      b.tau,
      d.d,
      d.extraEntropy,
      b.protocolVersion,
      b.minPoolCost,
      f.hkdMap(coinsPerUTxOByteToCoinsPerUTxOWord)(b.coinsPerUTxOByte),
      b.costModels,
      b.prices,
      b.maxTxExUnits,
      b.maxBlockExUnits,
      b.maxValSize,
      b.collateralPercentage,
      b.maxCollateralInputs
    )

  /** `coinsPerUTxOWordToCoinsPerUTxOByte` (`Babbage/PParams.hs:389-391`, cardano-ledger f649f975):
    * the coin divided by 8, rounded down (`div`), and made a `Word64` by `fromIntegral`, which
    * keeps the low 64 bits of the quotient, as here. A cost per word read by the update's or the
    * genesis's decoder is a `Word64`, so its eighth fits and nothing wraps; only a coin the
    * reference's type holds and no decoder gives (negative, or 2^67 and over) would.
    */
  private[era] def coinsPerUTxOWordToCoinsPerUTxOByte(w: CoinPerWord): CoinPerByte =
    val c = w.unCoinPerWord.value
    CoinPerByte(CompactCoin.fromUnsigned(((c - c.mod(8)) / 8).toLong))

  /** `coinsPerUTxOByteToCoinsPerUTxOWord` (`Babbage/PParams.hs:393-395`, cardano-ledger f649f975):
    * the `Word64` times 8, as an `Integer`.
    */
  private[era] def coinsPerUTxOByteToCoinsPerUTxOWord(b: CoinPerByte): CoinPerWord =
    CoinPerWord(Coin(b.unCoinPerByte.toBigInt * 8))

  // ---- the parameters the node keeps

  import StoredPParams.{compact, epochs, nonNegative, protVer, unit, units, unsigned}

  /** Babbage's fields of the parameters the node keeps, each at the reference's type. The node's
    * record holds every era's fields; the others are not read. A field outside its type's range is
    * refused ([[StoredPParams]]).
    */
  def fromStored(p: ProtocolParams): Either[PParamsError, BabbagePParams[Identity]] =
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
    yield BabbagePParams[Identity](
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
      version,
      minPoolCost,
      CoinPerByte(coinsPerUTxOByte),
      costModels,
      Prices(priceMem, priceSteps),
      maxTxExUnits,
      maxBlockExUnits,
      maxValSize,
      collateralPercentage,
      maxCollateralInputs
    )

  /** The parameters `base` with Babbage's fields those of `pp`: the inverse of [[fromStored]] over
    * the node's record, whose other eras' fields `base` keeps.
    */
  def toStored(pp: BabbagePParams[Identity], base: ProtocolParams): ProtocolParams =
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
      protocolMajor = BigInt(pp.protocolVersion.pvMajor.value),
      protocolMinor = pp.protocolVersion.pvMinor.value,
      minPoolCost = pp.minPoolCost.toBigInt,
      coinsPerUTxOByte = pp.coinsPerUTxOByte.unCoinPerByte.toBigInt,
      costModels = pp.costModels.models,
      priceMem = pp.prices.prMem.rational,
      priceSteps = pp.prices.prSteps.rational,
      maxTxExUnits = pp.maxTxExUnits.unOrdExUnits.units,
      maxBlockExUnits = pp.maxBlockExUnits.unOrdExUnits.units,
      maxValueSize = pp.maxValSize.value,
      collateralPercentage = pp.collateralPercentage.value,
      maxCollateralInputs = pp.maxCollateralInputs.value
    )

/** Babbage's instances (`instance EraPParams BabbageEra` to
  * `instance BabbageEraPParams BabbageEra`, `Babbage/PParams.hs:172-247`, cardano-ledger f649f975),
  * with the protocol version of the eras up to Babbage ([[EraPParamsToBabbage]]). The class's
  * upgrade from Alonzo and downgrade to it (`upgradePParamsHKD () = upgradeBabbagePParams True`,
  * `downgradePParamsHKD`, `:180-181`) are Babbage's functions ([[BabbagePParams.upgrade]],
  * [[BabbagePParams.downgrade]]): the class's members wait for Mary's record, without which
  * Alonzo's instance cannot give its own (#85).
  */
private[era] object BabbageEraPParamsInstance
    extends BabbageEraPParams[Babbage]
    with EraPParamsToBabbage[Babbage]:
  private type R[F[_]] = BabbagePParams[F]

  def applyPPUpdates(pp: PParams[Babbage], ppu: PParamsUpdate[Babbage]): PParams[Babbage] =
    PParams[Babbage](BabbagePParams.applyUpdates(pp.hkd, ppu.hkd))

  def emptyPParamsIdentity: BabbagePParams[Identity] = BabbagePParams.empty

  def emptyPParamsStrictMaybe: BabbagePParams[StrictMaybe] = BabbagePParams.emptyUpdate

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
  def hkdProtocolVersionL[F[_]: HKDFunctor]: Lens[R[F], F[ProtVer]] =
    GenLens[R[F]](_.protocolVersion)
  def hkdMinPoolCostCompactL[F[_]: HKDFunctor]: Lens[R[F], F[CompactCoin]] =
    GenLens[R[F]](_.minPoolCost)

  /** `ppDG = to (const minBound)` (`Babbage/PParams.hs:198`, cardano-ledger f649f975). */
  val ppDG: Getter[PParams[Babbage], UnitInterval] = Getter(_ => UnitInterval.zero)

  /** The class's default up to Conway, `L.to (const (MaxPledgeLeverage SNothing))`
    * (`Core/PParams.hs:510-516`, cardano-ledger 9df9f98c): no limit.
    */
  val ppMaxPledgeLeverageG: Getter[PParams[Babbage], MaxPledgeLeverage] =
    Getter(_ => MaxPledgeLeverage.none)

  /** The class's default up to Conway, `L.to (const minBound)` (`Core/PParams.hs:518-524`,
    * cardano-ledger 9df9f98c).
    */
  val ppMinPoolMarginG: Getter[PParams[Babbage], UnitInterval] = Getter(_ => UnitInterval.zero)

  /** `eraPParams` (`Babbage/PParams.hs:203-226`, cardano-ledger f649f975). */
  lazy val eraPParams: Vector[PParam[Babbage]] =
    import EraPParams.*, AlonzoEraPParams.*, BabbageEraPParams.*, EraPParamsToBabbage.*
    given BabbageEraPParamsInstance.type = this
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
      ppProtocolVersion,
      ppMinPoolCost,
      ppCoinsPerUTxOByte,
      ppCostModels,
      ppPrices,
      ppMaxTxExUnits,
      ppMaxBlockExUnits,
      ppMaxValSize,
      ppCollateralPercentage,
      ppMaxCollateralInputs
    )

  def hkdCostModelsL[F[_]: HKDFunctor]: Lens[R[F], F[CostModels]] = GenLens[R[F]](_.costModels)
  def hkdPricesL[F[_]: HKDFunctor]: Lens[R[F], F[Prices]] = GenLens[R[F]](_.prices)

  /** `hkdMap unOrdExUnits` over the field, and `hkdMap OrdExUnits` back
    * (`Babbage/PParams.hs:232-239`, cardano-ledger f649f975).
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

  def hkdCoinsPerUTxOByteL[F[_]: HKDFunctor]: Lens[R[F], F[CoinPerByte]] =
    GenLens[R[F]](_.coinsPerUTxOByte)
