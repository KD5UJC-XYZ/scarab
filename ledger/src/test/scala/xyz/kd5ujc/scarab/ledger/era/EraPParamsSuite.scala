// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.ledger.{
  Block,
  ConwayParamUpdate,
  ScriptContexts,
  DecodeField,
  GovAction,
  Tx,
  DRepGroup,
  GovChecks,
  ProtocolParams,
  Ratify,
  StakePoolGroup,
  TestLedger,
  Unsigned
}
import xyz.kd5ujc.scarab.ledger.era.conway.ConwayPParams
import xyz.kd5ujc.scarab.ledger.state.Conway

/** Conway's protocol parameters as higher-kinded data, against the flat parameters and update the
  * rules use now: the same parameters back, the same update applied, the same well-formedness and
  * the same groups.
  */
class EraPParamsSuite extends ScalaCheckSuite:
  private val era = summon[ConwayEraPParams[Conway]]

  private def conwayParams(p: ProtocolParams): PParams[Conway] =
    PParams[Conway](ConwayPParams.fromStored(p).fold(e => fail(s"parameters: $e"), identity))

  private def conwayUpdate(u: ConwayParamUpdate): PParamsUpdate[Conway] =
    PParamsUpdate[Conway](ConwayPParams.fromUpdate(u).fold(e => fail(s"update: $e"), identity))

  /** The unsigned fields of a Conway update, by key. */
  private val wordKeys =
    Vector(0, 1, 2, 3, 4, 5, 6, 7, 8, 16, 17, 22, 23, 24, 27, 28, 29, 30, 31, 32)

  private def widthOf(key: Int): BigInt = key match
    case 4 | 8 | 23 | 24 | 27          => 0xffff
    case 2 | 3 | 7 | 22 | 28 | 29 | 32 => (BigInt(1) << 32) - 1
    case _                             => (BigInt(1) << 64) - 1

  private val genUpdate: Gen[ConwayParamUpdate] =
    for
      keys <- Gen.someOf(wordKeys)
      values <- Gen.listOfN(
        keys.size,
        Gen.oneOf(Gen.const(BigInt(0)), Gen.choose(1L, 65535L).map(BigInt(_)))
      )
    yield TestLedger.conwayUpdate(keys.toSeq.zip(values).map((k, v) => k -> v.min(widthOf(k)))*)

  test("the parameters come back as they were"):
    val p = TestLedger.params(10)
    assertEquals(ConwayPParams.toStored(conwayParams(p).hkd, p), p)

  property("applying an update is the flat rule's"):
    forAll(genUpdate) { u =>
      val p = TestLedger.params(10)
      val applied = era.applyPPUpdates(conwayParams(p), conwayUpdate(u))
      assertEquals(ConwayPParams.toStored(applied.hkd, p), u.applyTo(p))
    }

  property("well-formedness is the flat check's, at versions 9 to 11"):
    forAll(genUpdate, Gen.choose(9, 11)) { (u, major) =>
      val p = TestLedger.params(major)
      val pv = era.ppProtocolVersionL.get(conwayParams(p))
      assertEquals(era.ppuWellFormed(pv, conwayUpdate(u)), GovChecks.wellFormed(p, u))
    }

  property("the modified groups are the flat rule's"):
    forAll(genUpdate) { u =>
      val groups = era.modifiedPPGroups(conwayUpdate(u))
      val flat = Ratify.modifiedGroups(u)
      assertEquals(
        (groups.unsorted.map(_.drep), groups.exists(_.pool == StakePoolGroup.Security)),
        flat
      )
    }

  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.toString), identity)

  private val half = "d81e820102"
  private val third = "d81e820103"

  /** Every field that is not a word: the intervals, the prices, the units, the cost models and the
    * thresholds.
    */
  private val ratios = TestLedger.conwayUpdateOf(
    9 -> hex(half),
    10 -> hex(third),
    11 -> hex(half),
    18 -> hex("a10083010203"),
    19 -> hex("82" + half + third),
    20 -> hex("820a0b"),
    21 -> hex("820c0d"),
    25 -> hex("85" + half * 5),
    26 -> hex("8a" + third * 10),
    33 -> hex(half)
  )

  test("an update of every field that is not a word is applied as the flat rule applies it"):
    val p =
      TestLedger.params(10).copy(costModels = Map(1 -> Vector(BigInt(7)), 0 -> Vector(BigInt(9))))
    val applied = era.applyPPUpdates(conwayParams(p), conwayUpdate(ratios))
    assertEquals(ConwayPParams.toStored(applied.hkd, p), ratios.applyTo(p))
    assertEquals(
      ConwayPParams.toStored(applied.hkd, p).costModels,
      Map(0 -> Vector(BigInt(1), BigInt(2), BigInt(3)), 1 -> Vector(BigInt(7)))
    )
    assertEquals(
      era.modifiedPPGroups(conwayUpdate(ratios)).unsorted.map(_.drep),
      Set(DRepGroup.Technical, DRepGroup.Economic, DRepGroup.Network, DRepGroup.Gov)
    )

  /** The parameter-change proposals of the recorded Preview governance blocks, read strictly. */
  private val previewUpdates: Vector[ConwayParamUpdate] =
    Files
      .readAllLines(Path.of("fixtures/preview/conway-governance.hex"))
      .asScala
      .toVector
      .filter(_.nonEmpty)
      .map(l => hex(l))
      .map(Block.decodeShelley(_).fold(e => fail(e.message), identity))
      .flatMap {
        case b: Block.Shelley =>
          b.transactions.map(t =>
            Tx.fromBlock(t, b.era).fold(e => fail(s"tx ${t.id.hex}: $e"), identity)
          )
        case other => fail(s"not a Shelley-based block: $other")
      }
      .flatMap(_.body.proposals)
      .map(_.action)
      .collect { case GovAction.ParameterChange(_, update, _) => update }

  test(
    "the recorded Preview parameter changes: applied, well-formed and grouped as the flat rules"
  ):
    assert(clue(previewUpdates.size) > 0)
    val p = TestLedger.params(10)
    previewUpdates.foreach { u =>
      val ppu = conwayUpdate(u)
      assertEquals(
        ConwayPParams.toStored(era.applyPPUpdates(conwayParams(p), ppu).hkd, p),
        u.applyTo(p)
      )
      assertEquals(
        era.ppuWellFormed(era.ppProtocolVersionL.get(conwayParams(p)), ppu),
        GovChecks.wellFormed(p, u)
      )
      val groups = era.modifiedPPGroups(ppu)
      assertEquals(
        (groups.unsorted.map(_.drep), groups.exists(_.pool == StakePoolGroup.Security)),
        Ratify.modifiedGroups(u)
      )
    }

  test("one lens reads the parameters and an update"):
    val p = TestLedger.params(10)
    val u = TestLedger.conwayUpdate(3 -> BigInt(4096))
    val pp = conwayParams(p)
    val ppu = conwayUpdate(u)
    assertEquals(
      PParams.lensHKD[Conway].andThen(era.hkdMaxTxSizeL[Identity]).get(pp).value,
      p.maxTxSize
    )
    assertEquals(
      PParamsUpdate.lensHKD[Conway].andThen(era.hkdMaxTxSizeL[StrictMaybe]).get(ppu).map(_.value),
      Some(BigInt(4096))
    )
    assertEquals(
      PParamsUpdate.lensHKD[Conway].andThen(era.hkdNOptL[StrictMaybe]).get(ppu),
      None
    )
    assertEquals(
      era.modifiedPPGroups(ppu).toList,
      List(PPGroups(DRepGroup.Network, StakePoolGroup.Security))
    )

  test("the decentralisation parameter is zero and the version is the parameters'"):
    val pp = conwayParams(TestLedger.params(10).copy(d = Rational(1, 2)))
    assertEquals(era.ppDG.get(pp).rational, Rational.zero)
    assertEquals(era.ppProtocolVersionL.get(pp).pvMajor.value, 10)

  test("the empty parameters are the reference's"):
    val empty = era.emptyPParams.hkd
    assertEquals(empty.maxTxSize.unTHKD.value, BigInt(2048))
    assertEquals(empty.nOpt.unTHKD.value, BigInt(100))
    assertEquals(empty.collateralPercentage.unTHKD.value, BigInt(150))
    assertEquals(empty.maxCollateralInputs.unTHKD.value, BigInt(5))
    assertEquals(empty.protocolVersion.pvMajor.value, 9)
    assert(!era.ppuWellFormed(empty.protocolVersion, era.emptyPParamsUpdate))

  test("a field outside its type is refused, with the field and the value"):
    val p = TestLedger.params(10)
    def refusal(q: ProtocolParams): Option[PParamsError] = ConwayPParams.fromStored(q).left.toOption
    val pool = p.poolVotingThresholds.copy(motionNoConfidence = Rational(2))
    val drep = p.drepVotingThresholds.copy(treasuryWithdrawal = Rational(-1, 2))
    val negative = xyz.kd5ujc.scarab.plutus.ExUnits(-1, 0)
    assertEquals(
      refusal(p.copy(maxTxSize = BigInt(1) << 32)),
      Some(PParamsError.OutOfRange(DecodeField.MaxTxSize, BigInt(1) << 32))
    )
    assertEquals(
      refusal(p.copy(poolVotingThresholds = pool)),
      Some(PParamsError.RatioOutOfBounds(DecodeField.PoolVotingThresholds, Rational(2)))
    )
    assertEquals(
      refusal(p.copy(drepVotingThresholds = drep)),
      Some(PParamsError.RatioOutOfBounds(DecodeField.DRepVotingThresholds, Rational(-1, 2)))
    )
    assertEquals(
      refusal(p.copy(maxTxExUnits = negative)),
      Some(PParamsError.NegativeExUnits(DecodeField.MaxTxExUnits, negative))
    )
    assertEquals(
      refusal(p.copy(costModels = Map(256 -> Vector(BigInt(1))))),
      Some(PParamsError.CostModelOutOfRange(256, BigInt(256)))
    )
    assertEquals(
      refusal(p.copy(costModels = Map(0 -> Vector(BigInt(1) << 63)))),
      Some(PParamsError.CostModelOutOfRange(0, BigInt(1) << 63))
    )
    assert(ConwayPParams.fromStored(p.copy(costModels = Map(255 -> Vector(BigInt(-1))))).isRight)

  test("the units lens reads and sets the units through the record's OrdExUnits"):
    val pp = conwayParams(TestLedger.params(10))
    val l = PParams.lensHKD[Conway].andThen(era.hkdMaxTxExUnitsL[Identity])
    val units = ExUnits.from(xyz.kd5ujc.scarab.plutus.ExUnits(7, 9)).getOrElse(fail("units"))
    assertEquals(l.get(l.replace(units)(pp)).units, xyz.kd5ujc.scarab.plutus.ExUnits(7, 9))

  test("HKDNoUpdate is F[A] at another type constructor, and does not take Option's case at Some"):
    assertEquals(compileErrors("summon[HKDNoUpdate[List, ProtVer] =:= List[ProtVer]]"), "")
    val some = compileErrors("summon[HKDNoUpdate[Some, ProtVer] =:= NoUpdate[ProtVer]]")
    assert(some.contains("could not be fully reduced"), some)
    assertEquals(compileErrors("summon[HKDNoUpdate[Option, ProtVer] =:= NoUpdate[ProtVer]]"), "")
    assertEquals(compileErrors("summon[HKDNoUpdate[Identity, ProtVer] =:= ProtVer]"), "")

  test("the reference-script constants"):
    val pp = conwayParams(TestLedger.params(10))
    assertEquals(era.ppMaxRefScriptSizePerTxG.get(pp).value, BigInt(200 * 1024))
    assertEquals(era.ppMaxRefScriptSizePerBlockG.get(pp).value, BigInt(1024 * 1024))
    assertEquals(era.ppRefScriptCostMultiplierG.get(pp).rational, Rational(6, 5))
    assertEquals(era.ppRefScriptCostStrideG.get(pp).value.value, BigInt(25600))

  test("a Conway update cannot set the protocol version"):
    val withVersion = compileErrors(
      "ConwayPParams.emptyUpdate.copy(protocolVersion = ConwayPParams.empty.protocolVersion)"
    )
    assert(withVersion.contains("Found:"), withVersion)
    assertEquals(
      compileErrors("ConwayPParams.emptyUpdate.copy(protocolVersion = NoUpdate[ProtVer]())"),
      ""
    )

  test("a word constant outside its width does not compile"):
    assert(compileErrors("Unsigned.lit[Unsigned.Width.W32](4294967296L)").contains("beyond Word32"))
    assertEquals(compileErrors("Unsigned.lit[Unsigned.Width.W32](4294967295L)"), "")

  test("Conway has no instance of the eras' parameters it does not have"):
    assert(compileErrors("summon[EraPParams[xyz.kd5ujc.scarab.ledger.state.Babbage]]").nonEmpty)
    assertEquals(compileErrors("summon[EraPParams[Conway]]"), "")

  // The update as Plutus data (`ToPlutusData (PParamsUpdate era)`) against the flat path's, which
  // builds the V3 script contexts now.

  private def plutus(u: ConwayParamUpdate): xyz.kd5ujc.scarab.plutus.PlutusData =
    ToPlutusData[PParamsUpdate[Conway]].toPlutusData(conwayUpdate(u))

  property("an update as Plutus data is the flat path's"):
    forAll(genUpdate)(u => assertEquals(plutus(u), ScriptContexts.changedParameters(u)))

  test("an update of every field that is not a word is the flat path's Plutus data"):
    assertEquals(plutus(ratios), ScriptContexts.changedParameters(ratios))

  test("the recorded Preview parameter changes are the flat path's Plutus data"):
    assert(clue(previewUpdates.size) > 0)
    previewUpdates.foreach(u => assertEquals(plutus(u), ScriptContexts.changedParameters(u)))

  test(
    "an update's fields are in the order of their tags, whatever the order they are given in"
  ):
    val u = TestLedger.conwayUpdate(30 -> BigInt(5), 0 -> BigInt(1), 8 -> BigInt(7))
    val keys = plutus(u) match
      case xyz.kd5ujc.scarab.plutus.PlutusData.Map(kvs) => kvs.map(_._1)
      case other                                        => fail(s"not a map: $other")
    assertEquals(keys, Vector(0, 8, 30).map(k => xyz.kd5ujc.scarab.plutus.PlutusData.I(BigInt(k))))
    assertEquals(plutus(u), ScriptContexts.changedParameters(u))
    assertEquals(
      ToPlutusData[PParamsUpdate[Conway]].toPlutusData(era.emptyPParamsUpdate),
      xyz.kd5ujc.scarab.plutus.PlutusData.Map(Vector.empty)
    )

  test("Conway's descriptions are the reference's names and tags, in its order"):
    val described = era.eraPParams.map(p => (p.ppName, p.ppUpdate.map(_.ppuTag.value.toInt)))
    assertEquals(
      described,
      Vector(
        ("txFeePerByte", Some(0)),
        ("txFeeFixed", Some(1)),
        ("maxBlockBodySize", Some(2)),
        ("maxTxSize", Some(3)),
        ("maxBlockHeaderSize", Some(4)),
        ("stakeAddressDeposit", Some(5)),
        ("stakePoolDeposit", Some(6)),
        ("poolRetireMaxEpoch", Some(7)),
        ("stakePoolTargetNum", Some(8)),
        ("poolPledgeInfluence", Some(9)),
        ("monetaryExpansion", Some(10)),
        ("treasuryCut", Some(11)),
        ("protocolVersion", None),
        ("minPoolCost", Some(16)),
        ("utxoCostPerByte", Some(17)),
        ("costModels", Some(18)),
        ("executionUnitPrices", Some(19)),
        ("maxTxExecutionUnits", Some(20)),
        ("maxBlockExecutionUnits", Some(21)),
        ("maxValueSize", Some(22)),
        ("collateralPercentage", Some(23)),
        ("maxCollateralInputs", Some(24)),
        ("poolVotingThresholds", Some(25)),
        ("dRepVotingThresholds", Some(26)),
        ("committeeMinSize", Some(27)),
        ("committeeMaxTermLength", Some(28)),
        ("govActionLifetime", Some(29)),
        ("govActionDeposit", Some(30)),
        ("dRepDeposit", Some(31)),
        ("dRepActivity", Some(32)),
        ("minFeeRefScriptCostPerByte", Some(33))
      )
    )

  test("a description's lenses read the parameters and the update of the record's field"):
    val p = TestLedger.params(10)
    val pp = conwayParams(p)
    val ppu = conwayUpdate(TestLedger.conwayUpdate(3 -> BigInt(4096)))
    era.eraPParams.foreach { d =>
      d.ppUpdate.foreach { u =>
        val set = u.ppuLens.get(ppu)
        assertEquals(set.isDefined, u.ppuTag.value == 3, d.ppName)
      }
    }
    val maxTx = era.eraPParams(3)
    assertEquals(
      maxTx.plutusData.toPlutusData(maxTx.ppLens.get(pp)),
      xyz.kd5ujc.scarab.plutus.PlutusData.I(p.maxTxSize)
    )
