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

  private def alonzo(costModels: Json): Json = Json.obj(
    "lovelacePerUTxOWord" -> Json.fromInt(34482),
    "executionPrices" -> Json.obj(
      "prSteps" -> Json
        .obj("numerator" -> Json.fromInt(721), "denominator" -> Json.fromInt(10000000)),
      "prMem" -> Json.obj("numerator" -> Json.fromInt(577), "denominator" -> Json.fromInt(10000))
    ),
    "maxTxExUnits" -> Json.obj("exUnitsMem" -> Json.fromInt(1), "exUnitsSteps" -> Json.fromInt(1)),
    "maxBlockExUnits" -> Json
      .obj("exUnitsMem" -> Json.fromInt(1), "exUnitsSteps" -> Json.fromInt(1)),
    "maxValueSize" -> Json.fromInt(5000),
    "collateralPercentage" -> Json.fromInt(150),
    "maxCollateralInputs" -> Json.fromInt(3),
    "costModels" -> costModels
  )

  test("a genesis cost model is ordered by parameter name, not by the order of the file") {
    val shuffled = Json.obj(
      "PlutusV1" -> Json.obj(
        "sha2_256-memory-arguments" -> Json.fromInt(4),
        "addInteger-cpu-arguments-slope" -> Json.fromInt(2),
        "cekDelayCost-exBudgetMemory" -> Json.fromInt(3),
        "addInteger-cpu-arguments-intercept" -> Json.fromInt(1)
      )
    )
    val params = ProtocolParams
      .fromGenesis(shelley, alonzo(shuffled), None)
      .fold(e => fail(e.message), identity)
    assertEquals(params.costModels, Map(0 -> Vector[BigInt](1, 2, 3, 4)))
  }

  test("array cost models keep their order; the Conway genesis adds PlutusV3") {
    val conway =
      Json.obj("plutusV3CostModel" -> Json.arr(Json.fromInt(9), Json.fromInt(8), Json.fromInt(7)))
    val arrays = Json.obj("PlutusV1" -> Json.arr(Json.fromInt(3), Json.fromInt(1), Json.fromInt(2)))
    val params = ProtocolParams
      .fromGenesis(shelley, alonzo(arrays), Some(conway))
      .fold(e => fail(e.message), identity)
    assertEquals(params.costModels, Map(0 -> Vector[BigInt](3, 1, 2), 2 -> Vector[BigInt](9, 8, 7)))
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
