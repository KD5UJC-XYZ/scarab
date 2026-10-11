// SPDX-License-Identifier: Apache-2.0
package lab.node.ledger

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
