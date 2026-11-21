// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.dijkstra

import cats.Eq
import io.circe.Json
import io.circe.parser.parse
import java.nio.file.{Files, Path}
import monocle.Lens
import monocle.law.discipline.LensTests
import munit.DisciplineSuite
import org.scalacheck.{Arbitrary, Cogen, Gen}
import org.scalacheck.Prop.forAll
import scala.collection.immutable.SortedSet
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Rational, Value as CborValue}
import xyz.kd5ujc.scarab.ledger.{
  DecodeField,
  DRepGroup,
  LedgerDecodeError,
  ParamUpdate,
  ProtocolParams,
  StakePoolGroup,
  Unsigned
}
import xyz.kd5ujc.scarab.ledger.Unsigned.Width.{W16, W32, W64}
import xyz.kd5ujc.scarab.ledger.dijkstra.{
  DijkstraGenesis,
  DijkstraScriptContexts,
  DijkstraParamUpdate,
  DijkstraParamValue,
  DijkstraParams,
  Params
}
import xyz.kd5ujc.scarab.ledger.era.*
import xyz.kd5ujc.scarab.ledger.era.conway.{
  ConwayPParams,
  DRepVotingThresholds,
  PoolVotingThresholds
}
import xyz.kd5ujc.scarab.ledger.state.{
  Coin,
  CompactCoin,
  Conway,
  Dijkstra,
  EpochInterval,
  NonZero,
  ShelleyBasedEra
}

/** Dijkstra's protocol parameters (`DijkstraPParams`, cardano-ledger 9df9f98c) against the build
  * the Leios testnet runs: each update of `fixtures/reference/dijkstra-pparams` is decoded as the
  * build decodes it at version 12, and where it decodes, `ppuWellFormed` at versions 12.0 and 10.0
  * and `modifiedPPGroups` are the build's.
  */
class DijkstraPParamsSuite extends DisciplineSuite:
  private val instance = summon[DijkstraEraPParams[Dijkstra]]

  private final case class Row(id: String, update: Bytes, reference: Json)

  private val rows: Vector[Row] =
    Files
      .readAllLines(Path.of("fixtures/reference/dijkstra-pparams/cases.jsonl"))
      .asScala
      .toVector
      .filter(_.nonEmpty)
      .map { line =>
        val json = parse(line).fold(e => fail(e.message), identity)
        val c = json.hcursor
        Row(
          c.get[String]("id").fold(e => fail(e.message), identity),
          Bytes
            .fromHex(c.get[String]("update").fold(e => fail(e.message), identity))
            .fold(e => fail(e.toString), identity),
          c.downField("reference").focus.getOrElse(fail("no reference"))
        )
      }

  /** The update decoded at version 12; the corpus's bytes are well-formed CBOR, so only the
    * ledger's decoder can refuse them.
    */
  private def decoded(bytes: Bytes): Either[LedgerDecodeError, DijkstraParamUpdate] =
    DijkstraParamUpdate.decode(Cbor.decode(bytes).fold(e => fail(s"not CBOR: $e"), identity))

  private type Refusal = PartialFunction[LedgerDecodeError, Unit]

  private val zeroRatio: Refusal = {
    case LedgerDecodeError.RationalOutOfBounds(_, LedgerDecodeError.OutOfBounds.Reduced(r))
        if r == Rational.zero =>
      ()
  }
  private val key57: Refusal = { case LedgerDecodeError.UnknownKey(_, k) if k == 57 => () }
  private val shape: Refusal = {
    case LedgerDecodeError.Expected(_, _, _) | LedgerDecodeError.ExpectedTag(_, _, _) |
        LedgerDecodeError.ArrayLength(_, _, _) | LedgerDecodeError.UnknownKey(_, _) =>
      ()
  }
  private val length: Refusal = { case LedgerDecodeError.ArrayLength(_, _, _) => () }
  private val range: Refusal = { case LedgerDecodeError.NumberOutOfRange(_, _, _, _) => () }
  private val duplicate: Refusal = { case LedgerDecodeError.DuplicateElement(_) => () }

  /** The typed refusal of each row the build refuses (`DeserialiseFailure` in the corpus): a zero
    * where a `NonZero` is built, a ratio outside `PositiveInterval`, a key no field has, and the
    * malformed values of the corpus. Of an update with two faults this decoder's verdict is
    * compared and not which fault it names (`V12`); the era codec's suite (`EraPParamsCborSuite`)
    * compares that with the build.
    */
  private val refusals: Map[String, Refusal] = Map(
    "refScriptCostStride zero" -> { case LedgerDecodeError.Zero(_) => () },
    "refScriptCostMultiplier zero" -> zeroRatio,
    "perasHealingFactor zero" -> zeroRatio,
    "an unknown key" -> key57,
    "a ratio of three items, the third null" -> shape,
    "a ratio of one null item" -> shape,
    "an indefinite ratio of three items, the third null" -> shape,
    "a ratio of a negative item" -> shape,
    "a negative cost-model language" -> shape,
    "a cost-model parameter as a bignum" -> shape,
    "cost models of a repeated language, its second list bad" -> shape,
    "execution units of null" -> shape,
    "prices of three items, the first null" -> shape,
    "indefinite DRep thresholds of ten, the tenth null" -> shape,
    "execution units of three items, the first null" -> shape,
    "a bad value before an unknown key" -> shape,
    "a negative word" -> shape,
    "a ratio of three items, a zero denominator" -> length,
    "indefinite execution units of one item" -> length,
    "indefinite execution units of three items" -> length,
    "execution units of one item, a key after it" -> length,
    "execution units of one item, last" -> length,
    "indefinite prices of one item" -> length,
    "indefinite pool thresholds of four" -> length,
    "a cost-model language past Word8, its list bad" -> range,
    "a cost-model parameter past Int64" -> range,
    "a Word32 past its width" -> range,
    "a ratio of a zero denominator" -> { case LedgerDecodeError.ZeroDenominator(_) => () },
    "cost models of a repeated language" -> duplicate,
    "a repeated key, its second value bad" -> duplicate,
    "an unknown key after a good one" -> key57
  )

  private def word[W <: Unsigned.Width: Unsigned.Bound](id: String, n: BigInt): Unsigned[W] =
    Unsigned.from[W](n).getOrElse(fail(s"$id: $n out of range"))

  private def ratio[A](id: String, r: Rational)(bound: Rational => Option[A]): A =
    bound(r).getOrElse(fail(s"$id: $r out of bounds"))

  /** The update as the record holds it: each decoded field at the type of its key's field
    * (`Dijkstra/PParams.hs:161-300` and the keys of `eraPParams`, cardano-ledger 9df9f98c).
    */
  private def record(id: String, u: DijkstraParamUpdate): DijkstraPParams[StrictMaybe] =
    import DijkstraParamValue.*
    def number(v: DijkstraParamValue): BigInt = v match
      case Number(n) => n
      case other     => fail(s"$id: not a number: $other")
    def rational(v: DijkstraParamValue): Rational = v match
      case Ratio(r) => r
      case other    => fail(s"$id: not a ratio: $other")
    def w16(v: DijkstraParamValue) = Some(word[W16](id, number(v)))
    def w32(v: DijkstraParamValue) = Some(word[W32](id, number(v)))
    def compact(v: DijkstraParamValue) =
      Some(CompactCoin.fromUnsigned(word[W64](id, number(v)).value.toLong))
    def perByte(v: DijkstraParamValue) = compact(v).map(CoinPerByte(_))
    def epochs(v: DijkstraParamValue) = Some(EpochInterval.fromWord32(word[W32](id, number(v))))
    def unit(v: DijkstraParamValue) = Some(ratio(id, rational(v))(UnitInterval.boundRational))
    def nonNegative(v: DijkstraParamValue) =
      Some(ratio(id, rational(v))(NonNegativeInterval.boundRational))
    def positive(v: DijkstraParamValue) = Some(
      ratio(id, rational(v))(PositiveInterval.boundRational)
    )
    def units(v: DijkstraParamValue) = v match
      case ExUnits(m, s) =>
        Some(
          OrdExUnits(
            xyz.kd5ujc.scarab.ledger.era.ExUnits
              .from(xyz.kd5ujc.scarab.plutus.ExUnits(m, s))
              .getOrElse(fail(s"$id: negative units"))
          )
        )
      case other => fail(s"$id: not units: $other")
    def thresholds(v: DijkstraParamValue): Vector[Rational] = v match
      case Thresholds(values) => values
      case other              => fail(s"$id: not thresholds: $other")
    u.fields.toVector.sortBy(_._1).foldLeft(DijkstraPParams.emptyUpdate) { case (acc, (key, v)) =>
      key match
        case 0  => acc.copy(txFeePerByte = THKD(perByte(v)))
        case 1  => acc.copy(txFeeFixed = THKD(compact(v)))
        case 2  => acc.copy(maxBBSize = THKD(w32(v)))
        case 3  => acc.copy(maxTxSize = THKD(w32(v)))
        case 4  => acc.copy(maxBHSize = THKD(w16(v)))
        case 5  => acc.copy(keyDeposit = THKD(compact(v)))
        case 6  => acc.copy(poolDeposit = THKD(compact(v)))
        case 7  => acc.copy(eMax = THKD(epochs(v)))
        case 8  => acc.copy(nOpt = THKD(w16(v)))
        case 9  => acc.copy(a0 = THKD(nonNegative(v)))
        case 10 => acc.copy(rho = THKD(unit(v)))
        case 11 => acc.copy(tau = THKD(unit(v)))
        case 16 => acc.copy(minPoolCost = THKD(compact(v)))
        case 17 => acc.copy(coinsPerUTxOByte = THKD(perByte(v)))
        case 18 =>
          v match
            case CostModels(m) =>
              acc.copy(costModels =
                THKD(
                  Some(
                    xyz.kd5ujc.scarab.ledger.era.CostModels
                      .from(m.view.mapValues(_.map(BigInt(_))).toMap)
                      .fold(e => fail(s"$id: $e"), identity)
                  )
                )
              )
            case other => fail(s"$id: not cost models: $other")
        case 19 =>
          v match
            case Pair(mem, steps) =>
              acc.copy(prices =
                THKD(
                  Some(
                    Prices(
                      ratio(id, mem)(NonNegativeInterval.boundRational),
                      ratio(id, steps)(NonNegativeInterval.boundRational)
                    )
                  )
                )
              )
            case other => fail(s"$id: not prices: $other")
        case 20 => acc.copy(maxTxExUnits = THKD(units(v)))
        case 21 => acc.copy(maxBlockExUnits = THKD(units(v)))
        case 22 => acc.copy(maxValSize = THKD(w32(v)))
        case 23 => acc.copy(collateralPercentage = THKD(w16(v)))
        case 24 => acc.copy(maxCollateralInputs = THKD(w16(v)))
        case 25 =>
          val t = thresholds(v)
          acc.copy(poolVotingThresholds =
            THKD(
              Some(
                PoolVotingThresholds
                  .fromStored(
                    xyz.kd5ujc.scarab.ledger.PoolVotingThresholds(t(0), t(1), t(2), t(3), t(4))
                  )
                  .fold(e => fail(s"$id: $e"), identity)
              )
            )
          )
        case 26 =>
          val t = thresholds(v)
          acc.copy(drepVotingThresholds =
            THKD(
              Some(
                DRepVotingThresholds
                  .fromStored(
                    xyz.kd5ujc.scarab.ledger
                      .DRepVotingThresholds(
                        t(0),
                        t(1),
                        t(2),
                        t(3),
                        t(4),
                        t(5),
                        t(6),
                        t(7),
                        t(8),
                        t(9)
                      )
                  )
                  .fold(e => fail(s"$id: $e"), identity)
              )
            )
          )
        case 27 => acc.copy(committeeMinSize = THKD(w16(v)))
        case 28 => acc.copy(committeeMaxTermLength = THKD(epochs(v)))
        case 29 => acc.copy(govActionLifetime = THKD(epochs(v)))
        case 30 => acc.copy(govActionDeposit = THKD(compact(v)))
        case 31 => acc.copy(drepDeposit = THKD(compact(v)))
        case 32 => acc.copy(drepActivity = THKD(epochs(v)))
        case 33 => acc.copy(minFeeRefScriptCostPerByte = THKD(nonNegative(v)))
        case 34 => acc.copy(maxRefScriptSizePerBlock = THKD(w32(v)))
        case 35 => acc.copy(maxRefScriptSizePerTx = THKD(w32(v)))
        case 36 =>
          val stride = word[W32](id, number(v))
          if stride.value == 0 then fail(s"$id: a zero stride decoded")
          acc.copy(refScriptCostStride = THKD(Some(NonZero.unsafe(stride))))
        case 37 => acc.copy(refScriptCostMultiplier = THKD(positive(v)))
        case 38 =>
          v match
            case OptionalRatio(r) =>
              acc.copy(maxPledgeLeverage =
                THKD(
                  Some(
                    MaxPledgeLeverage(r.map(x => ratio(id, x)(NonNegativeInterval.boundRational)))
                  )
                )
              )
            case other => fail(s"$id: not an optional ratio: $other")
        case 39 => acc.copy(minPoolMargin = THKD(unit(v)))
        case 40 => acc.copy(leiosAnnouncementPeriodLength = THKD(w32(v).map(Milliseconds32(_))))
        case 41 => acc.copy(leiosVotePeriodLength = THKD(w32(v).map(Milliseconds32(_))))
        case 42 => acc.copy(leiosDiffusionPeriodLength = THKD(w32(v).map(Milliseconds32(_))))
        case 43 => acc.copy(leiosCommitteeSize = THKD(w16(v)))
        case 44 => acc.copy(leiosQuorumStakeThreshold = THKD(unit(v)))
        case 45 => acc.copy(maxEndorserBlockReferencesSize = THKD(w32(v)))
        case 46 => acc.copy(maxEndorserBlockTxsSize = THKD(w32(v)))
        case 47 => acc.copy(maxEndorserBlockExUnits = THKD(units(v)))
        case 48 => acc.copy(maxRefScriptSizePerEndorserBlock = THKD(w32(v)))
        case 49 => acc.copy(perasMinCandidateBlockAge = THKD(w32(v).map(SlotInterval(_))))
        case 50 => acc.copy(perasHealingFactor = THKD(positive(v)))
        case 51 => acc.copy(perasCertBoost = THKD(w16(v)))
        case 52 => acc.copy(perasTargetCommitteeSize = THKD(w16(v)))
        case 53 =>
          v match
            case OptionalNumber(n) =>
              acc.copy(perasBootstrapRound = THKD(Some(n.map(x => word[W32](id, BigInt(x))))))
            case other => fail(s"$id: not an optional number: $other")
        case 54 => acc.copy(perasQuorumThresholdSafetyMargin = THKD(unit(v)))
        case 55 =>
          acc.copy(refInputsCostPerMultiAssetPolicy =
            THKD(Some(Coin(word[W64](id, number(v)).value)))
          )
        case 56    => acc.copy(refInputsCostPerDatumByte = THKD(perByte(v)))
        case other => fail(s"$id: no field has key $other")
    }

  private def groupName(g: PPGroups): List[String] =
    val drep = g.drep match
      case DRepGroup.Network   => "NetworkGroup"
      case DRepGroup.Economic  => "EconomicGroup"
      case DRepGroup.Technical => "TechnicalGroup"
      case DRepGroup.Gov       => "GovGroup"
    val pool = g.pool match
      case StakePoolGroup.Security    => "SecurityGroup"
      case StakePoolGroup.NoStakePool => "NoStakePoolGroup"
    List(drep, pool)

  private val v12 = ProtVer(Version.lit(12), Unsigned.lit[W32](0))
  private val v10 = ProtVer(Version.lit(10), Unsigned.lit[W32](0))

  test(
    "the corpus is present: every key alone, zero and not, long cost models, noncanonical and malformed updates, and the build's refusals"
  ):
    assertEquals(rows.size, 167)
    assertEquals(
      rows.filter(_.reference.hcursor.downField("refused").succeeded).map(_.id).toSet,
      refusals.keySet
    )

  rows.foreach { row =>
    test(s"${row.id}: decoded, well-formed and grouped as the Leios build does"):
      val c = row.reference.hcursor
      decoded(row.update) match
        case Left(e) =>
          assert(c.downField("refused").succeeded, s"refused ($e), the build decoded it")
          assert(refusals.get(row.id).exists(_.isDefinedAt(e)), s"refused with $e")
        case Right(u) =>
          assert(!c.downField("refused").succeeded, "decoded, the build refused it")
          val ppu = PParamsUpdate[Dijkstra](record(row.id, u))
          assertEquals(
            instance.ppuWellFormed(v12, ppu),
            c.get[Boolean]("wellFormed12").fold(e => fail(e.message), identity)
          )
          assertEquals(
            instance.ppuWellFormed(v10, ppu),
            c.get[Boolean]("wellFormed10").fold(e => fail(e.message), identity)
          )
          assertEquals(
            instance.modifiedPPGroups(ppu).toList.map(groupName),
            c.get[List[List[String]]]("groups").fold(e => fail(e.message), identity)
          )
          assertEquals(
            ToPlutusData[PParamsUpdate[Dijkstra]].toPlutusData(ppu),
            DijkstraScriptContexts.changedParameters(u),
            "as Plutus data"
          )
  }

  test("the descriptions are the Leios build's: Conway's, then Dijkstra's, every update tag once"):
    val updates = instance.eraPParams.flatMap(_.ppUpdate.map(_.ppuTag.value.toInt))
    assertEquals(instance.eraPParams.size, 54)
    assertEquals(updates, (0 to 11).toVector ++ (16 to 56).toVector)
    assertEquals(instance.eraPParams.count(_.ppUpdate.isEmpty), 1)
    assertEquals(instance.eraPParams.map(_.ppName).distinct.size, 54)
    assertEquals(
      instance.eraPParams.drop(31).map(_.ppName).take(2),
      Vector("maxRefScriptSizePerBlock", "maxRefScriptSizePerTx")
    )

  test("the empty parameters are the reference's (`emptyDijkstraPParams`)"):
    val e = instance.emptyPParams
    assertEquals(instance.ppProtocolVersionL.get(e), v12)
    assertEquals(instance.hkdMaxTxSizeL[Identity].get(e.hkd).value, BigInt(2048))
    assertEquals(instance.hkdNOptL[Identity].get(e.hkd).value, BigInt(100))
    assertEquals(instance.hkdCollateralPercentageL[Identity].get(e.hkd).value, BigInt(150))
    assertEquals(instance.hkdMaxCollateralInputsL[Identity].get(e.hkd).value, BigInt(5))
    assertEquals(instance.ppRefScriptCostStrideG.get(e).value.value, BigInt(1))
    assertEquals(
      instance.ppRefScriptCostMultiplierG.get(e).rational,
      Rational(1, BigInt(10).pow(19))
    )
    assertEquals(instance.hkdPerasHealingFactorL[Identity].get(e.hkd), PositiveInterval.minBound)
    assertEquals(instance.hkdMaxPledgeLeverageL[Identity].get(e.hkd), MaxPledgeLeverage.none)
    assertEquals(instance.hkdPerasBootstrapRoundL[Identity].get(e.hkd), None)
    assertEquals(instance.ppDG.get(e), UnitInterval.zero)

  test("applying the empty update keeps the parameters; an update sets its fields and no others"):
    val e = instance.emptyPParams
    assertEquals(instance.applyPPUpdates(e, instance.emptyPParamsUpdate), e)
    val ppu = PParamsUpdate[Dijkstra](
      DijkstraPParams.emptyUpdate.copy(
        leiosCommitteeSize = THKD(Some(Unsigned.lit[W16](600))),
        maxPledgeLeverage = THKD(Some(MaxPledgeLeverage(Some(NonNegativeInterval.zero)))),
        perasBootstrapRound = THKD(Some(Some(Unsigned.lit[W32](7))))
      )
    )
    val applied = instance.applyPPUpdates(e, ppu).hkd
    assertEquals(applied.leiosCommitteeSize.unTHKD.value, BigInt(600))
    assertEquals(
      applied.maxPledgeLeverage.unTHKD,
      MaxPledgeLeverage(Some(NonNegativeInterval.zero))
    )
    assertEquals(applied.perasBootstrapRound.unTHKD, Some(Unsigned.lit[W32](7)))
    assertEquals(
      applied.copy(
        leiosCommitteeSize = e.hkd.leiosCommitteeSize,
        maxPledgeLeverage = e.hkd.maxPledgeLeverage,
        perasBootstrapRound = e.hkd.perasBootstrapRound
      ),
      e.hkd
    )
    assertEquals(
      instance.modifiedPPGroups(ppu),
      SortedSet(
        PPGroups(DRepGroup.Network, StakePoolGroup.Security),
        PPGroups(DRepGroup.Technical, StakePoolGroup.NoStakePool)
      )
    )

  test("the reference-script getters read Dijkstra's fields, where Conway has constants"):
    val pp = PParams[Dijkstra](
      DijkstraPParams.empty.copy(
        maxRefScriptSizePerTx = THKD(Unsigned.lit[W32](1000)),
        maxRefScriptSizePerBlock = THKD(Unsigned.lit[W32](5000))
      )
    )
    assertEquals(instance.ppMaxRefScriptSizePerTxG.get(pp).value, BigInt(1000))
    assertEquals(instance.ppMaxRefScriptSizePerBlockG.get(pp).value, BigInt(5000))

  test(
    "the class's leverage and margin getters read Dijkstra's fields, where Conway has constants"
  ):
    val leverage = MaxPledgeLeverage(NonNegativeInterval.boundRational(Rational(7, 2)))
    val margin =
      UnitInterval.boundRational(Rational(1, 20)).fold(fail("1/20 out of range"))(identity)
    val pp = PParams[Dijkstra](
      DijkstraPParams.empty.copy(maxPledgeLeverage = THKD(leverage), minPoolMargin = THKD(margin))
    )
    val era = summon[EraPParams[Dijkstra]]
    assertEquals(era.ppMaxPledgeLeverageG.get(pp), leverage)
    assertEquals(era.ppMinPoolMarginG.get(pp), margin)
    val conway = summon[EraPParams[Conway]]
    assertEquals(conway.ppMaxPledgeLeverageG.get(conway.emptyPParams), MaxPledgeLeverage.none)
    assertEquals(conway.ppMinPoolMarginG.get(conway.emptyPParams), UnitInterval.zero)

  test("one lens reads the parameters and an update"):
    val ppu = DijkstraPParams.emptyUpdate.copy(perasCertBoost = THKD(Some(Unsigned.lit[W16](3))))
    assertEquals(instance.hkdPerasCertBoostL[StrictMaybe].get(ppu), Some(Unsigned.lit[W16](3)))
    val set =
      instance.hkdPerasCertBoostL[Identity].replace(Unsigned.lit[W16](9))(DijkstraPParams.empty)
    assertEquals(set.perasCertBoost.unTHKD.value, BigInt(9))

  test("an update's cost models replace those of their languages and keep the others"):
    def models(m: Map[Int, Vector[BigInt]]) =
      xyz.kd5ujc.scarab.ledger.era.CostModels.from(m).fold(e => fail(e.toString), identity)
    val pp = PParams[Dijkstra](
      DijkstraPParams.empty.copy(costModels = THKD(models(Map(0 -> Vector(BigInt(1), BigInt(2))))))
    )
    val ppu = PParamsUpdate[Dijkstra](
      DijkstraPParams.emptyUpdate.copy(costModels = THKD(Some(models(Map(1 -> Vector(BigInt(3)))))))
    )
    assertEquals(
      instance.applyPPUpdates(pp, ppu).hkd.costModels.unTHKD,
      models(Map(0 -> Vector(BigInt(1), BigInt(2)), 1 -> Vector(BigInt(3))))
    )
    val same = PParamsUpdate[Dijkstra](
      DijkstraPParams.emptyUpdate.copy(costModels = THKD(Some(models(Map(0 -> Vector(BigInt(9)))))))
    )
    assertEquals(
      instance.applyPPUpdates(pp, same).hkd.costModels.unTHKD,
      models(Map(0 -> Vector(BigInt(9))))
    )

  test("an update clears the optional fields: no bootstrap round, no pledge leverage limit"):
    val pp = PParams[Dijkstra](
      DijkstraPParams.empty.copy(
        perasBootstrapRound = THKD(Some(Unsigned.lit[W32](7))),
        maxPledgeLeverage = THKD(MaxPledgeLeverage(Some(NonNegativeInterval.zero)))
      )
    )
    val ppu = PParamsUpdate[Dijkstra](
      DijkstraPParams.emptyUpdate.copy(
        perasBootstrapRound = THKD(Some(None)),
        maxPledgeLeverage = THKD(Some(MaxPledgeLeverage.none))
      )
    )
    val applied = instance.applyPPUpdates(pp, ppu).hkd
    assertEquals(applied.perasBootstrapRound.unTHKD, None)
    assertEquals(applied.maxPledgeLeverage.unTHKD, MaxPledgeLeverage.none)

  /** The updates the build decodes, as records. */
  private val updates: Vector[DijkstraPParams[StrictMaybe]] =
    rows.flatMap(r => decoded(r.update).toOption.map(record(r.id, _)))

  private val genUpdate: Gen[DijkstraPParams[StrictMaybe]] = Gen.oneOf(updates)

  /** Parameters reached from the empty ones by a sequence of the corpus's updates. */
  private val genParams: Gen[DijkstraPParams[Identity]] =
    Gen.listOf(genUpdate).map(_.foldLeft(DijkstraPParams.empty)(DijkstraPParams.applyUpdates))

  private val costModelsField = DijkstraPParams.empty.productElementNames.indexOf("costModels")
  private val versionField = DijkstraPParams.empty.productElementNames.indexOf("protocolVersion")

  property("applying an update is the model: each set field replaced, the rest kept"):
    forAll(genParams, genUpdate) { (pp, u) =>
      val applied = DijkstraPParams.applyUpdates(pp, u)
      (0 until pp.productArity).foreach { i =>
        val expected =
          if i == versionField then pp.productElement(i)
          else if i == costModelsField then
            u.costModels.unTHKD.fold(pp.costModels.unTHKD.models)(n =>
              pp.costModels.unTHKD.models.filterNot((l, _) => n.models.contains(l)) ++ n.models
            )
          else
            u.productElement(i) match
              case Some(v) => v
              case _       => pp.productElement(i)
        val actual =
          if i == costModelsField then applied.costModels.unTHKD.models
          else applied.productElement(i)
        assertEquals(actual, expected, pp.productElementName(i))
      }
    }

  /** monocle's laws for a lens of the record, with states and values from generators of their own:
    * the states are parameters reached by the corpus's updates with the field set by the record's
    * `copy`, not by the lens under test, and the values are any of the field's type.
    */
  private def lensLaws[A](
      name: String,
      lens: Lens[DijkstraPParams[Identity], A],
      values: Gen[A],
      set: (DijkstraPParams[Identity], A) => DijkstraPParams[Identity]
  ): Unit =
    given Arbitrary[DijkstraPParams[Identity]] =
      Arbitrary(genParams.flatMap(pp => values.map(set(pp, _))))
    given Arbitrary[A] = Arbitrary(values)
    given Cogen[A] = Cogen[Int].contramap(_.hashCode)
    given Eq[DijkstraPParams[Identity]] = Eq.fromUniversalEquals
    given Eq[A] = Eq.fromUniversalEquals
    checkAll(name, LensTests(lens))

  private val genW16: Gen[Unsigned[W16]] = Gen.choose(0, 65535).map(n => word[W16]("gen", n))

  private val genLeverage: Gen[MaxPledgeLeverage] =
    Gen
      .option(
        Gen
          .zip(Gen.choose(0L, 10000L), Gen.choose(1L, 10000L))
          .map((n, d) =>
            ratio("gen", Rational(BigInt(n), BigInt(d)))(NonNegativeInterval.boundRational)
          )
      )
      .map(MaxPledgeLeverage(_))

  private val genUnits: Gen[ExUnits] =
    Gen
      .zip(Gen.choose(0L, Long.MaxValue), Gen.choose(0L, Long.MaxValue))
      .map((m, st) =>
        ExUnits
          .from(xyz.kd5ujc.scarab.plutus.ExUnits(BigInt(m), BigInt(st)))
          .getOrElse(fail(s"units $m $st"))
      )

  private val genCostModels: Gen[CostModels] =
    Gen
      .mapOf(Gen.zip(Gen.choose(0, 3), Gen.listOf(Gen.choose(-1000L, 1000L).map(BigInt(_)))))
      .map(m =>
        CostModels.from(m.view.mapValues(_.toVector).toMap).fold(e => fail(e.toString), identity)
      )

  lensLaws(
    "hkdPerasCertBoostL",
    instance.hkdPerasCertBoostL[Identity],
    genW16,
    (pp, a) => pp.copy(perasCertBoost = THKD(a))
  )
  lensLaws(
    "hkdMaxPledgeLeverageL",
    instance.hkdMaxPledgeLeverageL[Identity],
    genLeverage,
    (pp, a) => pp.copy(maxPledgeLeverage = THKD(a))
  )
  lensLaws(
    "hkdMaxTxExUnitsL, through OrdExUnits",
    instance.hkdMaxTxExUnitsL[Identity],
    genUnits,
    (pp, a) => pp.copy(maxTxExUnits = THKD(OrdExUnits(a)))
  )
  lensLaws(
    "hkdCostModelsL",
    instance.hkdCostModelsL[Identity],
    genCostModels,
    (pp, a) => pp.copy(costModels = THKD(a))
  )

  // ---- the parameters the node keeps

  /** The Leios testnet's parameters as the node keeps them: its Dijkstra genesis over a Conway
    * record.
    */
  private val testnet: ProtocolParams =
    io.circe.parser
      .parse(Files.readString(Path.of("fixtures/leios/testnet/config/dijkstra-genesis.json")))
      .left
      .map(_.message)
      .flatMap(j => DijkstraGenesis.read(j).left.map(_.message))
      .fold(fail(_), _.upgrade(Params.base))

  private def stored(p: ProtocolParams): DijkstraPParams[Identity] =
    DijkstraPParams.fromStored(p).fold(e => fail(e.toString), identity)

  /** The corpus's update with each key's value as the bytes it was written in. */
  private def raw(bytes: Bytes): ParamUpdate =
    Cbor.decode(bytes).fold(e => fail(s"not CBOR: $e"), identity).value match
      case CborValue.Map(entries) =>
        ParamUpdate(entries.map { (k, v) =>
          k.value match
            case CborValue.UInt(n) => n.toInt -> v.original
            case other             => fail(s"key $other")
        }.toMap)
      case other => fail(s"not a map: $other")

  test("the testnet's parameters are read and written back unchanged"):
    assertEquals(DijkstraPParams.toStored(stored(testnet), testnet), testnet)

  test("parameters without Dijkstra's fields are refused, not read as Dijkstra's"):
    // before Dijkstra the node's record holds zero in each field Dijkstra adds
    assertEquals(
      DijkstraPParams.fromStored(Params.base),
      Left(PParamsError.OutOfRange(DecodeField.RefScriptCostStride, BigInt(0)))
    )
    assertEquals(
      DijkstraPParams.fromStored(testnet.copy(refScriptCostMultiplier = Rational.zero)),
      Left(PParamsError.RatioOutOfBounds(DecodeField.RefScriptCostMultiplier, Rational.zero))
    )
    assertEquals(
      DijkstraPParams.fromStored(testnet.copy(perasHealingFactor = Rational.zero)),
      Left(PParamsError.RatioOutOfBounds(DecodeField.PerasHealingFactor, Rational.zero))
    )
    assertEquals(
      DijkstraPParams.fromStored(testnet.copy(leiosCommitteeSize = BigInt(65536))),
      Left(PParamsError.OutOfRange(DecodeField.LeiosCommitteeSize, BigInt(65536)))
    )

  rows.foreach { row =>
    decoded(row.update).toOption.foreach { u =>
      test(s"${row.id}: the record of the update, and applied as the node applies it"):
        val update = DijkstraPParams.fromUpdate(u).fold(e => fail(e.toString), identity)
        assertEquals(update, record(row.id, u))
        // the flat path: Conway's keys by `ProtocolParams.update`, Dijkstra's by its own table
        val flat = ProtocolParams
          .update(testnet, raw(row.update))(using ShelleyBasedEra.SDijkstra)
          .flatMap(DijkstraParams.update(_, raw(row.update)))
          .fold(e => fail(e.toString), identity)
        assertEquals(
          DijkstraPParams.toStored(DijkstraPParams.applyUpdates(stored(testnet), update), testnet),
          flat
        )
    }
  }

  test("an update's key outside the parameters, or a value not of its key's form, is refused"):
    import DijkstraParamValue.*
    def fromUpdate(fields: (Int, DijkstraParamValue)*) =
      DijkstraPParams.fromUpdate(DijkstraParamUpdate(fields.toMap))
    assertEquals(fromUpdate(57 -> Number(1)), Left(PParamsError.UnknownKey(57)))
    // `d`, a key of Shelley's parameters that Babbage dropped
    assertEquals(fromUpdate(12 -> Ratio(Rational.zero)), Left(PParamsError.UnknownKey(12)))
    assertEquals(fromUpdate(0 -> Ratio(Rational.zero)), Left(PParamsError.ValueNotOfKey(0)))
    assertEquals(
      fromUpdate(25 -> Thresholds(Vector.fill(4)(Rational.zero))),
      Left(PParamsError.ValueNotOfKey(25))
    )
    assertEquals(
      fromUpdate(36 -> Number(0)),
      Left(PParamsError.OutOfRange(DecodeField.RefScriptCostStride, BigInt(0)))
    )
    assertEquals(
      fromUpdate(37 -> Ratio(Rational.zero)),
      Left(PParamsError.RatioOutOfBounds(DecodeField.RefScriptCostMultiplier, Rational.zero))
    )

  private given xyz.kd5ujc.scarab.ledger.state.HasZero[Unsigned[W32]] =
    xyz.kd5ujc.scarab.ledger.state.HasZero.instance(_.value.signum == 0)

  private val genW32: Gen[Unsigned[W32]] =
    Gen.choose(0L, 0xffffffffL).map(n => word[W32]("gen", BigInt(n)))

  private def genRatio[A](bound: Rational => Option[A], lo: Long, hi: Long): Gen[A] =
    Gen
      .zip(Gen.choose(lo, hi), Gen.choose(1L, 1000L))
      .map((n, d) => ratio("gen", Rational(BigInt(n), BigInt(d)))(bound))

  private val genUnit = Gen
    .choose(0L, 1000L)
    .map(n => ratio("gen", Rational(BigInt(n), BigInt(1000)))(UnitInterval.boundRational))
  private val genPositive = genRatio(PositiveInterval.boundRational, 1L, 100000L)

  /** Parameters with each field Dijkstra adds drawn on its own, so that two fields of one type
    * rarely hold one value.
    */
  private val genStoredParams: Gen[DijkstraPParams[Identity]] =
    for
      pp <- genParams
      sizes <- Gen.listOfN(7, genW32)
      w16s <- Gen.listOfN(3, genW16)
      stride <- Gen.choose(1L, 0xffffffffL).map(n => NonZero.from(word[W32]("gen", BigInt(n))))
      multiplier <- genPositive
      healing <- genPositive
      leverage <- genLeverage
      units <- Gen.listOfN(4, genUnit)
      periods <- Gen.listOfN(3, genW32)
      age <- genW32
      round <- Gen.option(genW32)
      policy <- Gen.choose(BigInt(0), BigInt(2).pow(80)).map(Coin(_))
      perByte <- Gen.long.map(CompactCoin.fromUnsigned)
      endorser <- genUnits
    yield pp.copy(
      maxRefScriptSizePerBlock = THKD(sizes(0)),
      maxRefScriptSizePerTx = THKD(sizes(1)),
      refScriptCostStride = THKD(stride.fold(e => fail(e.toString), identity)),
      refScriptCostMultiplier = THKD(multiplier),
      maxPledgeLeverage = THKD(leverage),
      minPoolMargin = THKD(units(0)),
      leiosAnnouncementPeriodLength = THKD(Milliseconds32(periods(0))),
      leiosVotePeriodLength = THKD(Milliseconds32(periods(1))),
      leiosDiffusionPeriodLength = THKD(Milliseconds32(periods(2))),
      leiosCommitteeSize = THKD(w16s(0)),
      leiosQuorumStakeThreshold = THKD(units(1)),
      maxEndorserBlockReferencesSize = THKD(sizes(2)),
      maxEndorserBlockTxsSize = THKD(sizes(3)),
      maxEndorserBlockExUnits = THKD(OrdExUnits(endorser)),
      maxRefScriptSizePerEndorserBlock = THKD(sizes(4)),
      perasMinCandidateBlockAge = THKD(SlotInterval(age)),
      perasHealingFactor = THKD(healing),
      perasCertBoost = THKD(w16s(1)),
      perasTargetCommitteeSize = THKD(w16s(2)),
      perasBootstrapRound = THKD(round),
      perasQuorumThresholdSafetyMargin = THKD(units(2)),
      refInputsCostPerMultiAssetPolicy = THKD(policy),
      refInputsCostPerDatumByte = THKD(CoinPerByte(perByte))
    )

  property("parameters written to the node's record are read back unchanged"):
    forAll(genStoredParams) { pp =>
      assertEquals(DijkstraPParams.fromStored(DijkstraPParams.toStored(pp, testnet)), Right(pp))
    }

  test("the downgrade is Conway's record of the same stored parameters"):
    // each of Conway's fields distinct, so a field taken for another of its type is seen
    val distinct = testnet.copy(
      minFeeA = 1,
      minFeeB = 2,
      maxBlockBodySize = 3,
      maxTxSize = 4,
      maxBlockHeaderSize = 5,
      keyDeposit = 6,
      poolDeposit = 7,
      eMax = 8,
      nOpt = 9,
      a0 = Rational(1, 10),
      rho = Rational(1, 11),
      tau = Rational(1, 12),
      minPoolCost = 13,
      coinsPerUTxOByte = 14,
      priceMem = Rational(1, 15),
      priceSteps = Rational(1, 16),
      maxTxExUnits = xyz.kd5ujc.scarab.plutus.ExUnits(17, 18),
      maxBlockExUnits = xyz.kd5ujc.scarab.plutus.ExUnits(19, 20),
      maxValueSize = 21,
      collateralPercentage = 22,
      maxCollateralInputs = 23,
      committeeMinSize = 24,
      committeeMaxTermLength = 25,
      govActionLifetime = 26,
      govActionDeposit = 27,
      drepDeposit = 28,
      drepActivity = 29,
      minFeeRefScriptCostPerByte = Rational(1, 30)
    )
    assertEquals(
      Right(DijkstraPParams.downgrade(stored(distinct))),
      ConwayPParams.fromStored(distinct)
    )

  private def hex(h: String): Bytes = Bytes.fromHex(h).fold(e => fail(e.toString), identity)

  test("an update of unequal components and words at their bounds, through the record and flat"):
    val max64 = "1bffffffffffffffff"
    val half64 = "1b8000000000000000"
    def unit(d: Int) = f"d81e8201$d%02x"
    val minFeeA = "00" + max64
    val prices = "13" + "82d81e820102d81e820304" // 1/2 and 3/4
    val maxTxExUnits = "14" + "820506"
    val maxBlockExUnits = "15" + "820708"
    val poolThresholds = "1819" + "85" + (5 to 9).map(unit).mkString // 1/5 to 1/9
    val drepThresholds = "181a" + "8a" + (10 to 19).map(unit).mkString // 1/10 to 1/19
    val govActionDeposit = "181e" + half64
    val maxEndorserBlockExUnits = "182f" + "82090a"
    val refInputsCostPerMultiAssetPolicy = "1837" + max64
    val refInputsCostPerDatumByte = "1838" + half64
    val bytes = hex(
      "aa" + minFeeA + prices + maxTxExUnits + maxBlockExUnits + poolThresholds + drepThresholds +
        govActionDeposit + maxEndorserBlockExUnits + refInputsCostPerMultiAssetPolicy +
        refInputsCostPerDatumByte
    )
    val u = decoded(bytes).fold(e => fail(e.toString), identity)
    val update = DijkstraPParams.fromUpdate(u).fold(e => fail(e.toString), identity)
    val written =
      DijkstraPParams.toStored(DijkstraPParams.applyUpdates(stored(testnet), update), testnet)
    val flat = ProtocolParams
      .update(testnet, raw(bytes))(using ShelleyBasedEra.SDijkstra)
      .flatMap(DijkstraParams.update(_, raw(bytes)))
      .fold(e => fail(e.toString), identity)
    assertEquals(written, flat)
    val two64 = BigInt(2).pow(64)
    assertEquals(
      written,
      testnet.copy(
        minFeeA = two64 - 1,
        priceMem = Rational(1, 2),
        priceSteps = Rational(3, 4),
        maxTxExUnits = xyz.kd5ujc.scarab.plutus.ExUnits(5, 6),
        maxBlockExUnits = xyz.kd5ujc.scarab.plutus.ExUnits(7, 8),
        poolVotingThresholds = xyz.kd5ujc.scarab.ledger.PoolVotingThresholds(
          Rational(1, 5),
          Rational(1, 6),
          Rational(1, 7),
          Rational(1, 8),
          Rational(1, 9)
        ),
        drepVotingThresholds = xyz.kd5ujc.scarab.ledger.DRepVotingThresholds(
          Rational(1, 10),
          Rational(1, 11),
          Rational(1, 12),
          Rational(1, 13),
          Rational(1, 14),
          Rational(1, 15),
          Rational(1, 16),
          Rational(1, 17),
          Rational(1, 18),
          Rational(1, 19)
        ),
        govActionDeposit = two64 / 2,
        maxEndorserBlockExUnits = xyz.kd5ujc.scarab.plutus.ExUnits(9, 10),
        refInputsCostPerMultiAssetPolicy = two64 - 1,
        refInputsCostPerDatumByte = two64 / 2
      )
    )

  test("a stored word past its width is refused; the reference-input policy cost is any integer"):
    val two64 = BigInt(2).pow(64)
    assertEquals(
      DijkstraPParams.fromStored(testnet.copy(minFeeA = two64)),
      Left(PParamsError.OutOfRange(DecodeField.MinFeeA, two64))
    )
    assertEquals(
      DijkstraPParams.fromStored(testnet.copy(refInputsCostPerDatumByte = two64)),
      Left(PParamsError.OutOfRange(DecodeField.RefInputsCostPerDatumByte, two64))
    )
    val large = testnet.copy(refInputsCostPerMultiAssetPolicy = BigInt(2).pow(80))
    assertEquals(DijkstraPParams.toStored(stored(large), large), large)
