// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.dijkstra

import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.plutus.PlutusData
import xyz.kd5ujc.scarab.plutus.PlutusData.I

/** A parameter update of the flat decoder as Plutus data, as the flat script contexts wrote it
  * before they went (#85): the expected value the suites compare the era types' `ToPlutusData
  * (PParamsUpdate DijkstraEra)` with on every update the repository holds. No recording of the
  * reference holds most of these updates as Plutus data; the recorded contexts hold the
  * `governance` case's (`DijkstraPlutusContextSuite`).
  *
  * `ToPlutusData (PParamsUpdate DijkstraEra)` (`Conway/PParams.hs:200-206`, cardano-ledger
  * 9df9f98c): the fields of the update by tag, in the order of `eraPParams`
  * (`Dijkstra/PParams.hs:741-796`, cardano-ledger 9df9f98c), which is the order of the tags; a
  * rational is the list of its numerator and denominator, and a `Maybe` `Just` constructor 0 and
  * `Nothing` constructor 1 (plutus-ledger-api 1.71.0.0).
  */
object DijkstraChangedParameters:
  private def rational(r: Rational): PlutusData =
    PlutusData.List(Vector(I(r.numerator), I(r.denominator)))

  private def encoded(v: DijkstraParamValue): PlutusData = v match
    case DijkstraParamValue.Number(n)         => I(n)
    case DijkstraParamValue.Ratio(r)          => rational(r)
    case DijkstraParamValue.OptionalRatio(r)  => PlutusData.maybe(r.map(rational))
    case DijkstraParamValue.OptionalNumber(n) => PlutusData.maybe(n.map(x => I(BigInt(x))))
    case DijkstraParamValue.CostModels(models) =>
      PlutusData.Map(models.toVector.sortBy(_._1).map { (language, values) =>
        (I(BigInt(language)): PlutusData) -> PlutusData.List(values.map(x => I(BigInt(x))))
      })
    case DijkstraParamValue.Pair(a, b)          => PlutusData.List(Vector(rational(a), rational(b)))
    case DijkstraParamValue.ExUnits(mem, steps) => PlutusData.List(Vector(I(mem), I(steps)))
    case DijkstraParamValue.Thresholds(rs)      => PlutusData.List(rs.map(rational))

  def of(update: DijkstraParamUpdate): PlutusData =
    PlutusData.Map(update.fields.toVector.sortBy(_._1).map { (tag, v) =>
      (I(BigInt(tag)): PlutusData) -> encoded(v)
    })
