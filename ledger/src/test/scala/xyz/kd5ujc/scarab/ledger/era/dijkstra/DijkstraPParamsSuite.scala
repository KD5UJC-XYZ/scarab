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
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Rational}
import xyz.kd5ujc.scarab.ledger.{DRepGroup, LedgerDecodeError, StakePoolGroup, Unsigned}
import xyz.kd5ujc.scarab.ledger.Unsigned.Width.{W16, W32, W64}
import xyz.kd5ujc.scarab.ledger.dijkstra.{DijkstraParamUpdate, DijkstraParamValue}
import xyz.kd5ujc.scarab.ledger.era.*
import xyz.kd5ujc.scarab.ledger.era.conway.{DRepVotingThresholds, PoolVotingThresholds}
import xyz.kd5ujc.scarab.ledger.state.{Coin, CompactCoin, Conway, Dijkstra, EpochInterval, NonZero}

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

  /** The typed refusal of each row the build refuses (`DeserialiseFailure` in the corpus): a zero
    * where a `NonZero` is built, a ratio outside `PositiveInterval`, and a key no field has.
    */
  private val refusals: Map[String, PartialFunction[LedgerDecodeError, Unit]] = Map(
    "refScriptCostStride zero" -> { case LedgerDecodeError.Zero(_) => () },
    "refScriptCostMultiplier zero" -> {
      case LedgerDecodeError.RationalOutOfBounds(_, n, d) if n == 0 && d == 1 => ()
    },
    "perasHealingFactor zero" -> {
      case LedgerDecodeError.RationalOutOfBounds(_, n, d) if n == 0 && d == 1 => ()
    },
    "an unknown key" -> { case LedgerDecodeError.UnknownKey(_, k) if k == 57 => () }
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

  test("the corpus is present: every key alone, zero and not, and the build's refusals"):
    assertEquals(rows.size, 113)
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
  }

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
