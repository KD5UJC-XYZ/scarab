// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import io.circe.Json
import io.circe.parser.parse
import java.nio.file.{Files, Path}
import munit.FunSuite

/** Cost models in the genesis files. */
class ProtocolParamsSuite extends FunSuite:
  private val shelley =
    parse(Files.readString(Path.of("fixtures/preview/genesis/shelley-genesis.json")))
      .fold(e => fail(e.getMessage), identity)

  private def genesis(path: String): Json =
    parse(Files.readString(Path.of(path))).fold(e => fail(e.getMessage), identity)

  private val previewAlonzo = genesis("fixtures/preview/genesis/alonzo-genesis.json")
  private val previewConway = genesis("fixtures/preview/genesis/conway-genesis.json")
  private val erasAlonzo = genesis("fixtures/eras/genesis/alonzo-genesis.json")

  private def costModels(alonzo: Json, conway: Option[Json]) =
    ProtocolParams
      .fromGenesis(shelley, alonzo, conway)
      .fold(e => fail(e.message), _.costModels)

  private def withCostModels(alonzo: Json, models: Json): Json =
    alonzo.mapObject(_.add("costModels", models))

  test("a genesis cost model given by name is ordered by the parameter list, not by the file") {
    val named = previewAlonzo.hcursor.downField("costModels").downField("PlutusV1").focus
    val object1 =
      named.flatMap(_.asObject).getOrElse(fail("the preview genesis names its parameters"))
    val reversed = Json.obj(
      "PlutusV1" -> Json.fromJsonObject(
        io.circe.JsonObject.fromIterable(object1.toVector.reverse)
      )
    )
    val models = costModels(previewAlonzo, None)
    assertEquals(costModels(withCostModels(previewAlonzo, reversed), None), models)
    assertEquals(models.keySet, Set(0))
    assertEquals(models(0).size, 166)
  }

  test(
    "array cost models keep their order; the extra config and the Conway genesis add languages"
  ) {
    val models = costModels(erasAlonzo, Some(previewConway))
    assertEquals(models.keySet, Set(0, 1, 2))
    assertEquals(models.view.mapValues(_.size).toMap, Map(0 -> 166, 1 -> 175, 2 -> 251))
    val v3 = previewConway.hcursor.get[Vector[BigInt]]("plutusV3CostModel")
    assertEquals(Right(models(2)), v3)
    val v1 = erasAlonzo.hcursor.downField("costModels").get[Vector[BigInt]]("PlutusV1")
    assertEquals(Right(models(0)), v1)
  }

  test("a genesis the reference refuses is refused: a cost model of the wrong length") {
    val short = Json.obj("PlutusV1" -> Json.arr(Json.fromInt(3), Json.fromInt(1), Json.fromInt(2)))
    val refused = ProtocolParams.fromGenesis(shelley, withCostModels(erasAlonzo, short), None)
    assertEquals(
      refused.left.map(_.problem),
      Left(
        ConfigProblem.GenesisCostModel(
          CostModelProblem.ParameterCount(CostModelLanguage.PlutusV1, 3, 166)
        )
      )
    )
  }

  test("overlay: what an update changed over the snapshot's fields goes onto the whole set") {
    val whole = TestLedger.params(10).copy(minFeeA = 44, maxTxSize = 16384, nOpt = 500)
    // the snapshot has the fields of the era only: the others are zero
    val read = whole.copy(nOpt = 0, maxTxSize = 0)
    // an update of the snapshot's parameters changes the fee and the size
    val changed = read.copy(minFeeA = 45, maxTxSize = 20000)
    val laid = ProtocolParams.overlay(whole, read, changed)
    assertEquals(laid, whole.copy(minFeeA = 45, maxTxSize = 20000))
    // no update: the whole set, untouched
    assertEquals(ProtocolParams.overlay(whole, read, read), whole)
  }

  test("Utxo.removeKnown deletes entries the value does not see, with their output known") {
    val in = TestLedger.txIn(1)
    val out = TestLedger.out(TestLedger.key().address(), 5000000)
    // an entry the backing store holds: neither in the diff nor in the read set
    val unseen = Utxo.empty.at(7).copy(coin = 5000000, count = 1)
    assertEquals(unseen.get(in), None)
    assertEquals(unseen.--(Seq(in)).count, 1L)
    val removed = unseen.removeKnown(Map(in -> out))
    assertEquals(removed.count, 0L)
    assertEquals(removed.coin, BigInt(0))
    // the deletion is a change that the store gets, stamped with the block
    assertEquals(removed.changesAfter(6L).toVector, Vector(in -> None))
    // an entry the diff already deleted stays as it is
    assertEquals(removed.removeKnown(Map(in -> out)), removed)
    // one the value holds is removed as `--` removes it
    val held = Utxo.fromMap(Map(in -> out))
    assertEquals(held.removeKnown(Map(in -> out)).toMap, held.--(Seq(in)).toMap)
  }
