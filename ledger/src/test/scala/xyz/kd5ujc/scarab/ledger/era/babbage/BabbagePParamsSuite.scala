// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.babbage

import munit.ScalaCheckSuite
import org.scalacheck.Prop.forAll
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{
  CborRead,
  DecodeField,
  DecoderVersion,
  ParamUpdate,
  ProtocolParams,
  TestLedger,
  Unsigned
}
import xyz.kd5ujc.scarab.ledger.Unsigned.Width.{W16, W32}
import xyz.kd5ujc.scarab.ledger.era.*
import xyz.kd5ujc.scarab.ledger.era.conway.{ConwayPParams, UpgradeConwayPParams}
import xyz.kd5ujc.scarab.ledger.state.{Babbage, Coin, CompactCoin, ShelleyBasedEra}
import xyz.kd5ujc.scarab.protocol.Era as ProtocolEra

import BabbageCbor.*

/** Babbage's protocol parameters as higher-kinded data, against what the node keeps and applies
  * now: the parameters projected from the stored record and back, an update applied as the flat
  * rule applies it, the record's and an update's CBOR, the descriptions, and Conway's upgrade from
  * Babbage and downgrade to it.
  */
class BabbagePParamsSuite extends ScalaCheckSuite:
  private val era = summon[BabbageEraPParams[Babbage]]
  private val version = DecoderVersion.of(ProtocolEra.Babbage)
  private given DecodeAt = DecodeAt(version, DecodeField.ParameterUpdate)

  private def right[E, A](what: String)(e: Either[E, A]): A =
    e.fold(x => fail(s"$what: $x"), identity)

  private def hex(s: String): Bytes = right("hex")(Bytes.fromHex(s))

  private def babbage(p: ProtocolParams): BabbagePParams[Identity] =
    right("Babbage's parameters")(BabbagePParams.fromStored(p))

  /** The update of a map of fields, read by Babbage's decoder. */
  private def updateOf(fields: Vector[(Int, String)]): PParamsUpdate[Babbage] =
    right("update")(
      CborRead
        .decode(hex(map(fields.map((k, v) => uint(k) -> v))))
        .flatMap(PParamsUpdate.decodePParamsUpdate[Babbage](_))
    )

  // ---- the parameters

  test("the stored parameters come back as they were"):
    val p = TestLedger.params(8)
    assertEquals(BabbagePParams.toStored(babbage(p), p), p)

  property("an update applied is the flat rule's, but for the cost models"):
    forAll(genFields(babbageKeys.filterNot(_ == 18))) { fields =>
      val p = TestLedger.params(8)
      val applied = era.applyPPUpdates(PParams[Babbage](babbage(p)), updateOf(fields))
      val flat = ParamUpdate(fields.map((k, v) => k -> hex(v)).toMap)
      val expected = ProtocolParams.update(p, flat)(using ShelleyBasedEra.SBabbage)
      assertEquals(Right(BabbagePParams.toStored(applied.hkd, p)), expected)
    }

  test("an update's cost models replace the parameters' whole, as the reference's generic apply"):
    val pp = babbage(TestLedger.params(8))
    val v2 = right("models")(CostModels.from(Map(1 -> Vector(BigInt(9)))))
    val ppu = PParamsUpdate[Babbage](BabbagePParams.emptyUpdate.copy(costModels = Some(v2)))
    assertEquals(era.applyPPUpdates(PParams[Babbage](pp), ppu).hkd.costModels, v2)

  test("the record and an update are their CBOR's, at Babbage's decoder version"):
    // the legacy cost-model reader takes each language's full model only, so none here
    val pp = PParams[Babbage](babbage(TestLedger.params(8).copy(costModels = Map.empty)))
    assertEquals(
      PParams.decodePParams[Babbage](
        right("cbor")(CborRead.decode(PParams.encodePParams(pp).bytes))
      ),
      Right(pp)
    )
    val ppu = PParamsUpdate[Babbage](
      BabbagePParams.emptyUpdate.copy(
        protocolVersion = Some(ProtVer(Version.lit(8), Unsigned.lit[W32](1))),
        nOpt = Some(Unsigned.lit[W16](500))
      )
    )
    val bytes = PParamsUpdate.encodePParamsUpdate(ppu).bytes
    assertEquals(bytes.hex, "a2" + "08" + "1901f4" + "0e" + "820801")
    assertEquals(
      PParamsUpdate.decodePParamsUpdate[Babbage](right("cbor")(CborRead.decode(bytes))),
      Right(ppu)
    )

  test("the descriptions are the reference's names and tags, in its order"):
    assertEquals(
      era.eraPParams.map(d => (d.ppName, d.ppUpdate.map(_.ppuTag.value.toInt))),
      Vector(
        "txFeePerByte" -> 0,
        "txFeeFixed" -> 1,
        "maxBlockBodySize" -> 2,
        "maxTxSize" -> 3,
        "maxBlockHeaderSize" -> 4,
        "stakeAddressDeposit" -> 5,
        "stakePoolDeposit" -> 6,
        "poolRetireMaxEpoch" -> 7,
        "stakePoolTargetNum" -> 8,
        "poolPledgeInfluence" -> 9,
        "monetaryExpansion" -> 10,
        "treasuryCut" -> 11,
        "protocolVersion" -> 14,
        "minPoolCost" -> 16,
        "utxoCostPerByte" -> 17,
        "costModels" -> 18,
        "executionUnitPrices" -> 19,
        "maxTxExecutionUnits" -> 20,
        "maxBlockExecutionUnits" -> 21,
        "maxValueSize" -> 22,
        "collateralPercentage" -> 23,
        "maxCollateralInputs" -> 24
      ).map((n, t) => n -> Some(t))
    )

  test("the empty parameters are the reference's, at Babbage's lowest version"):
    assertEquals(
      era.ppProtocolVersionL.get(era.emptyPParams),
      ProtVer(Version.lit(7), Unsigned.lit[W32](0))
    )
    assertEquals(era.ppDG.get(era.emptyPParams), UnitInterval.zero)

  test("the ranged classes have exactly their eras"):
    assertEquals(compileErrors("summon[EraPParamsToBabbage[Babbage]]"), "")
    assert(
      compileErrors("summon[EraPParamsToBabbage[xyz.kd5ujc.scarab.ledger.state.Conway]]").nonEmpty
    )
    assert(compileErrors("summon[ConwayEraPParams[Babbage]]").nonEmpty)

  // ---- Conway's upgrade from Babbage and downgrade to it

  private val v3 = CostModel.mkCostModel[Language.PlutusV3](Vector(1L, 2L, 3L))

  private def upgradeOf(govDeposit: BigInt): UpgradeConwayPParams[Identity] =
    UpgradeConwayPParams[Identity](
      conway.PoolVotingThresholds.zero,
      conway.DRepVotingThresholds.zero,
      Unsigned.lit[W16](7),
      xyz.kd5ujc.scarab.ledger.state.EpochInterval.zero,
      xyz.kd5ujc.scarab.ledger.state.EpochInterval.zero,
      Coin(govDeposit),
      Coin(BigInt(5)),
      xyz.kd5ujc.scarab.ledger.state.EpochInterval.zero,
      NonNegativeInterval.zero,
      v3
    )

  test("the upgrade keeps Babbage's fields, adds the PlutusV3 model and the governance fields"):
    val b = babbage(TestLedger.params(8))
    val c = right("upgrade")(ConwayPParams.upgrade(upgradeOf(100), b))
    assertEquals(c.costModels.unTHKD, b.costModels.updated(CostModels.singleton(v3)))
    assertEquals(c.costModels.unTHKD.models.keySet, Set(0, 1, 2))
    assertEquals(c.govActionDeposit.unTHKD.toBigInt, BigInt(100))
    assertEquals(c.committeeMinSize.unTHKD, Unsigned.lit[W16](7))
    assertEquals(c.protocolVersion, b.protocolVersion)
    assertEquals(ConwayPParams.downgrade(c), b.copy(costModels = c.costModels.unTHKD))

  test(
    "an upgrade's deposit outside Word64 is refused, as the reference's compactCoinOrError aborts"
  ):
    val big = BigInt(1) << 64
    assertEquals(
      ConwayPParams.upgrade(upgradeOf(big), babbage(TestLedger.params(8))).left.toOption,
      CompactCoin.fromBigInt(big).left.toOption
    )
    assert(CompactCoin.fromBigInt(big).isLeft)

  property(
    "the empty upgrade of an update and the downgrade give the update back, but its version and models"
  ):
    forAll(genFields(babbageKeys)) { fields =>
      val ppu = updateOf(fields)
      val up = right("upgrade")(ConwayPParams.upgrade(UpgradeConwayPParams.emptyUpdate, ppu.hkd))
      // the version is out of a Conway update's reach, and an update's cost models are kept
      // only with an upgrade's PlutusV3 model (`hkdLiftA2` of `StrictMaybe`, cardano-ledger
      // f649f975 `Conway/PParams.hs:1120-1130`)
      assertEquals(
        ConwayPParams.downgrade(up),
        ppu.hkd.copy(protocolVersion = None, costModels = None)
      )
    }

  test(
    "an update's upgrade merges the PlutusV3 model into the update's models only when both are set"
  ):
    val base = right("models")(CostModels.from(Map(0 -> Vector(BigInt(4)), 1 -> Vector(BigInt(5)))))
    val withModels = BabbagePParams.emptyUpdate.copy(costModels = Some(base))
    val withV3 = UpgradeConwayPParams.emptyUpdate.copy(plutusV3CostModel = Some(v3))
    def models(u: UpgradeConwayPParams[StrictMaybe], b: BabbagePParams[StrictMaybe]) =
      right("upgrade")(ConwayPParams.upgrade(u, b)).costModels.unTHKD
    assertEquals(models(withV3, withModels), Some(base.updated(CostModels.singleton(v3))))
    assertEquals(models(withV3, withModels).map(_.models.keySet), Some(Set(0, 1, 2)))
    assertEquals(models(withV3, BabbagePParams.emptyUpdate), None)
    assertEquals(models(UpgradeConwayPParams.emptyUpdate, withModels), None)

  test(
    "the upgrade written over the stored parameters sets the governance fields and the PlutusV3 model"
  ):
    val p = TestLedger.params(8)
    val c = right("upgrade")(ConwayPParams.upgrade(upgradeOf(100), babbage(p)))
    val stored = ConwayPParams.toStored(c, p)
    assertEquals(
      stored,
      p.copy(
        govActionDeposit = 100,
        drepDeposit = 5,
        committeeMinSize = 7,
        costModels = p.costModels.updated(2, Vector(1, 2, 3))
      )
    )
