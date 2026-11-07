// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.dijkstra

import io.circe.{Decoder, Json, parser}
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.ledger.{ConfigProblem, GenesisFile}
import xyz.kd5ujc.scarab.plutus.ExUnits

/** The Dijkstra genesis files against the unchanged reference parser of the Leios testnet build.
  *
  * `fixtures/reference/dijkstra-genesis/cases.jsonl` holds one case per line: a Dijkstra genesis
  * file of the repository, whole or with one field removed, given another JSON type or another
  * value, and the verdict of `FromJSON DijkstraGenesis` of cardano-ledger 9df9f98c on it, with the
  * genesis the reference read when it accepts. Each case is rebuilt from its base file and read by
  * [[DijkstraGenesis.read]], which must accept exactly what the reference accepts, read the same
  * values, and refuse a field removed or of the wrong JSON type at that field.
  */
class DijkstraGenesisSuite extends FunSuite:
  private def parse(text: String): Json = parser.parse(text).fold(e => fail(e.message), identity)
  private def field[A: Decoder](row: Json, key: String): A =
    row.hcursor.get[A](key).fold(e => fail(e.message), identity)

  private val rows: Vector[Json] =
    Files
      .readString(Path.of("fixtures/reference/dijkstra-genesis/cases.jsonl"))
      .linesIterator
      .map(parse)
      .toVector

  private def edit(json: Json, path: List[String], value: Option[Json]): Json = path match
    case Nil        => value.getOrElse(json)
    case key :: Nil => json.mapObject(o => value.fold(o.remove(key))(o.add(key, _)))
    case key :: rest =>
      json.mapObject(o => o.add(key, edit(o(key).getOrElse(Json.obj()), rest, value)))

  private def input(row: Json): Json =
    val base = parse(Files.readString(Path.of(field[String](row, "base"))))
    val path = field[List[String]](row, "path")
    field[String](row, "op") match
      case "none"    => base
      case "remove"  => edit(base, path, None)
      case "replace" => edit(base, path, Some(field[Json](row, "value")))
      case other     => fail(s"unknown change $other")

  private def reference(row: Json): Json = field[Json](row, "reference")
  private def accepted(row: Json): Boolean = field[Boolean](reference(row), "accepted")

  /** A ratio as the reference writes it back: a decimal, or the rational form. */
  private def ratio(json: Json): Rational =
    json.asObject match
      case Some(_) => Rational(field[BigInt](json, "numerator"), field[BigInt](json, "denominator"))
      case None    => Rational.fromDecimal(json.as[BigDecimal].fold(e => fail(e.message), identity))

  private def optional[A](json: Json, key: String)(f: Json => A): Option[A] =
    json.hcursor.downField(key).focus.filterNot(_.isNull).map(f)

  private def same(g: DijkstraGenesis, r: Json): Unit =
    val p = g.params
    def int(key: String) = field[BigInt](r, key)
    def rat(key: String) = ratio(field[Json](r, key))
    assertEquals(p.maxRefScriptSizePerBlock, int("maxRefScriptSizePerBlock"))
    assertEquals(p.maxRefScriptSizePerTx, int("maxRefScriptSizePerTx"))
    assertEquals(p.refScriptCostStride, int("refScriptCostStride"))
    assertEquals(p.refScriptCostMultiplier, rat("refScriptCostMultiplier"))
    assertEquals(p.maxPledgeLeverage, optional(r, "maxPledgeLeverage")(ratio))
    assertEquals(p.minPoolMargin, rat("minPoolMargin"))
    assertEquals(g.plutusV4CostModel, field[Vector[Long]](r, "plutusV4CostModel"))
    assertEquals(p.leiosAnnouncementPeriodLength, int("leiosAnnouncementPeriodLength"))
    assertEquals(p.leiosVotePeriodLength, int("leiosVotePeriodLength"))
    assertEquals(p.leiosDiffusionPeriodLength, int("leiosDiffusionPeriodLength"))
    assertEquals(p.leiosCommitteeSize, int("leiosCommitteeSize"))
    assertEquals(p.leiosQuorumStakeThreshold, rat("leiosQuorumStakeThreshold"))
    assertEquals(p.maxEndorserBlockReferencesSize, int("maxEndorserBlockReferencesSize"))
    assertEquals(p.maxEndorserBlockTxsSize, int("maxEndorserBlockTxsSize"))
    val units = field[Json](r, "maxEndorserBlockExecutionUnits")
    assertEquals(
      p.maxEndorserBlockExUnits,
      ExUnits(field[BigInt](units, "memory"), field[BigInt](units, "steps"))
    )
    assertEquals(p.maxRefScriptSizePerEndorserBlock, int("maxRefScriptSizePerEndorserBlock"))
    assertEquals(p.perasMinCandidateBlockAge, int("perasMinCandidateBlockAge"))
    assertEquals(p.perasHealingFactor, rat("perasHealingFactor"))
    assertEquals(p.perasCertBoost, int("perasCertBoost"))
    assertEquals(p.perasTargetCommitteeSize, int("perasTargetCommitteeSize"))
    assertEquals(
      p.perasBootstrapRound,
      optional(r, "perasBootstrapRound")(_.as[BigInt].fold(e => fail(e.message), identity))
    )
    assertEquals(p.perasQuorumThresholdSafetyMargin, rat("perasQuorumThresholdSafetyMargin"))
    assertEquals(p.refInputsCostPerMultiAssetPolicy, int("refInputsCostPerMultiAssetPolicy"))
    assertEquals(p.refInputsCostPerDatumByte, int("refInputsCostPerDatumByte"))

  test("the fixture holds both files whole, refusals and acceptances") {
    assertEquals(rows.size, 133)
    assertEquals(rows.count(r => field[String](r, "op") == "none" && accepted(r)), 2)
    assert(rows.exists(r => !accepted(r)))
    assert(rows.exists(r => field[String](r, "op") == "replace" && accepted(r)))
  }

  for row <- rows do
    test(s"${field[String](row, "name")}: as the reference") {
      val scarab = DijkstraGenesis.read(input(row))
      val failure = reference(row).hcursor.get[String]("failure").getOrElse("accepted")
      assertEquals(
        scarab.isRight,
        accepted(row),
        s"${scarab.fold(_.message, _ => "accepted")} | reference: $failure"
      )
      val name = field[String](row, "name")
      val path = field[List[String]](row, "path")
      // a field removed or of another JSON type is refused at that field: a unit of the execution
      // units under the second of its names (`<|>`), the cost model by its own refusal
      if name.contains("/remove:") || name.contains("/wrongType:") then
        scarab.left.foreach(e =>
          e.problem match
            case ConfigProblem.GenesisField(f, at, _) =>
              assertEquals(f, GenesisFile.Dijkstra)
              assertEquals(at.take(1).toList, path.take(1), e.message)
            case _ => fail(s"not a genesis field refusal: ${e.message}")
        )
      scarab.foreach(same(_, field[Json](reference(row), "result")))
    }

  test("the upgrade lays the PlutusV4 model over the cost models and keeps the others") {
    val g = DijkstraGenesis
      .read(parse(Files.readString(Path.of("fixtures/leios/testnet/config/dijkstra-genesis.json"))))
      .fold(e => fail(e.message), identity)
    val conway = xyz.kd5ujc.scarab.ledger.ProtocolParams(
      1,
      2,
      3,
      4,
      5,
      6,
      7,
      8,
      9,
      10,
      Rational.zero,
      Rational.zero,
      Rational.zero,
      11,
      10,
      12,
      13,
      14,
      15,
      Rational.zero,
      costModels = Map(0 -> Vector(BigInt(1)), 2 -> Vector(BigInt(2)), 3 -> Vector(BigInt(9)))
    )
    val upgraded = g.upgrade(conway)
    assertEquals(upgraded.costModels(3), g.plutusV4CostModel.map(BigInt(_)))
    assertEquals(upgraded.costModels - 3, conway.costModels - 3)
    assertEquals(upgraded.copy(costModels = conway.costModels), conway)
  }
