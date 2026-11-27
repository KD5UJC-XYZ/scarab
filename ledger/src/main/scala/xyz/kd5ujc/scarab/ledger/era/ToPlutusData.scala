// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.ledger.Unsigned
import xyz.kd5ujc.scarab.ledger.Unsigned.Width.{W16, W32}
import xyz.kd5ujc.scarab.ledger.era.dijkstra.{Milliseconds32, SlotInterval}
import xyz.kd5ujc.scarab.ledger.state.{Coin, CompactCoin, EpochInterval, EraTag, NonZero}
import xyz.kd5ujc.scarab.plutus.PlutusData
import xyz.kd5ujc.scarab.plutus.PlutusData.I

/** A value as Plutus data (`class ToPlutusData x`, cardano-ledger f649f975
  * `libs/cardano-ledger-core/src/Cardano/Ledger/Plutus/ToPlutusData.hs:48-50`): how a script
  * context shows a parameter update. The reference's `fromPlutusData`, which nothing but its tests
  * calls, is not here yet (#85).
  */
trait ToPlutusData[A]:
  def toPlutusData(a: A): PlutusData

object ToPlutusData:
  def apply[A](using t: ToPlutusData[A]): ToPlutusData[A] = t

  extension [A](a: A)(using t: ToPlutusData[A]) def toPlutusData: PlutusData = t.toPlutusData(a)

  /** `instance ToPlutusData Version` (`ToPlutusData.hs:67-70`, cardano-ledger f649f975). */
  given version: ToPlutusData[Version] = v => I(BigInt(v.value))

  /** `instance ToPlutusData ProtVer` (`ToPlutusData.hs:72-75`, cardano-ledger f649f975). */
  given protVer: ToPlutusData[ProtVer] = pv =>
    PlutusData.List(Vector(pv.pvMajor.toPlutusData, pv.pvMinor.toPlutusData))

  /** `instance ToPlutusData Rational` (`ToPlutusData.hs:77-80`, cardano-ledger f649f975): the
    * numerator and the denominator of the reduced ratio.
    */
  given rational: ToPlutusData[Rational] = r =>
    PlutusData.List(Vector(I(r.numerator), I(r.denominator)))

  /** `instance ToPlutusData UnitInterval` (`ToPlutusData.hs:82-84`, cardano-ledger f649f975). */
  given unitInterval: ToPlutusData[UnitInterval] = u => u.rational.toPlutusData

  /** `instance ToPlutusData NonNegativeInterval` (`ToPlutusData.hs:86-88`, cardano-ledger
    * f649f975).
    */
  given nonNegativeInterval: ToPlutusData[NonNegativeInterval] = n => n.rational.toPlutusData

  /** `instance ToPlutusData PositiveInterval` (`ToPlutusData.hs:90-92`, cardano-ledger f649f975).
    */
  given positiveInterval: ToPlutusData[PositiveInterval] = p => p.rational.toPlutusData

  /** `instance ToPlutusData CostModels` (`ToPlutusData.hs:94-98`, cardano-ledger f649f975): the
    * flattened models (`flattenCostModels`) through the instances of `Map` and of lists (`:52-62`),
    * each language's parameters in their order, the languages ascending.
    */
  given costModels: ToPlutusData[CostModels] = c =>
    PlutusData.Map(c.models.toVector.sortBy(_._1).map { (language, values) =>
      (I(BigInt(language)): PlutusData) -> (PlutusData.List(values.map(I(_))): PlutusData)
    })

  /** `instance ToPlutusData ExUnits` (`ToPlutusData.hs:100-103`, cardano-ledger f649f975). */
  given exUnits: ToPlutusData[ExUnits] = e =>
    PlutusData.List(Vector(I(e.units.mem), I(e.units.steps)))

  /** `instance ToPlutusData Prices` (`ToPlutusData.hs:105-108`, cardano-ledger f649f975). */
  given prices: ToPlutusData[Prices] = p =>
    PlutusData.List(Vector(p.prMem.toPlutusData, p.prSteps.toPlutusData))

  /** `deriving instance ToPlutusData Coin` (`ToPlutusData.hs:110`, cardano-ledger f649f975), the
    * `Integer` 's.
    */
  given coin: ToPlutusData[Coin] = c => I(c.value)

  /** `instance ToPlutusData (CompactForm Coin)` (`ToPlutusData.hs:112-115`, cardano-ledger
    * f649f975): the coin's.
    */
  given compactCoin: ToPlutusData[CompactCoin] = c => I(c.toBigInt)

  /** `instance ToPlutusData CoinPerByte` (`ToPlutusData.hs:117-119`, cardano-ledger f649f975). */
  given coinPerByte: ToPlutusData[CoinPerByte] = c => c.unCoinPerByte.toPlutusData

  /** `instance ToPlutusData Word32` (`ToPlutusData.hs:121-126`, cardano-ledger f649f975). */
  given word32: ToPlutusData[Unsigned[W32]] = w => I(w.value)

  /** `instance ToPlutusData Word16` (`ToPlutusData.hs:128-131`, cardano-ledger f649f975). */
  given word16: ToPlutusData[Unsigned[W16]] = w => I(w.value)

  /** `deriving instance ToPlutusData EpochInterval` (`ToPlutusData.hs:138`, cardano-ledger
    * f649f975), the `Word32` 's.
    */
  given epochInterval: ToPlutusData[EpochInterval] = e => I(BigInt(e.toLong))

  /** `instance (ToPlutusData a, HasZero a) => ToPlutusData (NonZero a)` (`ToPlutusData.hs:155-157`,
    * cardano-ledger f649f975).
    */
  given nonZero[A](using t: ToPlutusData[A]): ToPlutusData[NonZero[A]] = z =>
    t.toPlutusData(z.value)

  /** `instance ToPlutusData a => ToPlutusData (StrictMaybe a)` (cardano-ledger 9df9f98c
    * `libs/cardano-ledger-core/src/Cardano/Ledger/Plutus/ToPlutusData.hs:60-65`): `Constr 0` of the
    * value, or `Constr 1` of nothing. The Leios build's alone.
    */
  given strictMaybe[A](using t: ToPlutusData[A]): ToPlutusData[StrictMaybe[A]] = m =>
    PlutusData.maybe(m.map(t.toPlutusData))

  /** `deriving instance ToPlutusData Milliseconds32` (`ToPlutusData.hs:148`, cardano-ledger
    * 9df9f98c), the `Word32` 's.
    */
  given milliseconds32: ToPlutusData[Milliseconds32] = m => m.unMilliseconds32.toPlutusData

  /** `deriving instance ToPlutusData SlotInterval` (`ToPlutusData.hs:150`, cardano-ledger
    * 9df9f98c), the `Word32` 's.
    */
  given slotInterval: ToPlutusData[SlotInterval] = s => s.unSlotInterval.toPlutusData

  /** `deriving instance ToPlutusData OrdExUnits` (`ToPlutusData.hs:152`, cardano-ledger 9df9f98c),
    * the units'.
    */
  given ordExUnits: ToPlutusData[OrdExUnits] = o => o.unOrdExUnits.toPlutusData

  /** `deriving newtype (..., ToPlutusData)` of `MaxPledgeLeverage` (`Core/PParams.hs:369-373`,
    * cardano-ledger 9df9f98c): the `StrictMaybe NonNegativeInterval` 's.
    */
  given maxPledgeLeverage: ToPlutusData[MaxPledgeLeverage] = m => m.unMaxPledgeLeverage.toPlutusData

  /** `instance ConwayEraPParams era => ToPlutusData (PParamsUpdate era)`
    * (`eras/conway/impl/src/Cardano/Ledger/Conway/PParams.hs:199-205`, cardano-ledger f649f975):
    * the fields the update sets, each under its tag, in the order of the era's descriptions
    * (`eraPParams`); a parameter an update cannot set has no tag and is left out.
    */
  // `instance ToPlutusData Nonce` and `instance ToPlutusData CoinPerWord` are `error
  // "unsupported"` (`libs/cardano-ledger-core/src/Cardano/Ledger/Plutus/ToPlutusData.hs:159-161`,
  // `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/PParams.hs:405-407`, cardano-ledger f649f975): they
  // exist only because a description packs its value's `ToPlutusData` (`PParam`,
  // `Core/PParams.hs:786-797`), and the one reader of it, an update shown as Plutus data
  // ([[pparamsUpdate]]), asks for `ConwayEraPParams`, which no era with the extra entropy or the
  // cost per UTxO word has. They are not givens: only the descriptions of those two parameters
  // (`ppExtraEntropy`, `ppCoinsPerUTxOWord`) are given them.

  /** `instance ToPlutusData Nonce` (`ToPlutusData.hs:159-161`, cardano-ledger f649f975). */
  // Kept (ERR-4): the reference's instance fails when called; nothing here calls it, as its one
  // reader asks for an era with `ConwayEraPParams` and the extra entropy is a parameter of the eras
  // up to Alonzo only, so reaching it is a defect of the tree, not a domain error
  // (`AlonzoPParamsSuite` holds that no update of Alonzo's can be shown).
  @SuppressWarnings(Array("xyz.kd5ujc.scarab.warts.SystemTimeAndUnsafeRun"))
  private[era] val unsupportedNonce: ToPlutusData[xyz.kd5ujc.scarab.ledger.Nonce] = _ =>
    sys.error("unsupported")

  /** `instance ToPlutusData CoinPerWord` (`Alonzo/PParams.hs:405-407`, cardano-ledger f649f975). */
  // Kept (ERR-4): as [[unsupportedNonce]]; the cost per UTxO word is a parameter of Alonzo alone.
  @SuppressWarnings(Array("xyz.kd5ujc.scarab.warts.SystemTimeAndUnsafeRun"))
  private[era] val unsupportedCoinPerWord: ToPlutusData[CoinPerWord] = _ => sys.error("unsupported")

  given pparamsUpdate[E <: EraTag](using p: ConwayEraPParams[E]): ToPlutusData[PParamsUpdate[E]] =
    ppu =>
      PlutusData.Map(p.eraPParams.flatMap { pp =>
        pp.ppUpdate.flatMap { u =>
          u.ppuLens
            .get(ppu)
            .map(t => (I(u.ppuTag.value): PlutusData) -> pp.plutusData.toPlutusData(t))
        }
      })
