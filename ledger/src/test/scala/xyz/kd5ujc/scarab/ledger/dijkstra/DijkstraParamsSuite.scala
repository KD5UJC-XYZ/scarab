// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.dijkstra

import io.circe.parser
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.ledger.{DecodeField, LedgerDecodeError, ParamUpdate, ProtocolParams}
import xyz.kd5ujc.scarab.plutus.ExUnits

/** The update of the parameters Dijkstra adds (`dijkstraApplyPPUpdates`, cardano-ledger 9df9f98c
  * `Dijkstra/PParams.hs:317-388`): each key present replaces its field, the others are kept, and
  * each value is read as [[DijkstraParamUpdate.decode]] reads it.
  */
class DijkstraParamsSuite extends FunSuite:
  private val genesis: ProtocolParams =
    parser
      .parse(Files.readString(Path.of("fixtures/leios/testnet/config/dijkstra-genesis.json")))
      .left
      .map(_.message)
      .flatMap(j => DijkstraGenesis.read(j).left.map(_.message))
      .fold(fail(_), _.upgrade(Params.base))

  private def update(fields: (Int, String)*): ParamUpdate =
    ParamUpdate(
      fields.map((k, hex) => k -> Bytes.fromHex(hex).fold(e => fail(e.toString), identity)).toMap
    )

  test("each key present replaces its field and the others are kept") {
    val committeeSize300 = 43 -> "19012c"
    val pledgeLeverageHalf = 38 -> "d81e820102"
    val endorserUnits5And6 = 47 -> "820506"
    val bootstrapRound5 = 53 -> "05"
    // a Conway key is applied by `ProtocolParams.update` and is left alone here
    val conwayMinFeeA = 0 -> "1901f4"
    val u = update(
      committeeSize300,
      pledgeLeverageHalf,
      endorserUnits5And6,
      bootstrapRound5,
      conwayMinFeeA
    )
    val updated = DijkstraParams.update(genesis, u).fold(e => fail(e.toString), identity)
    assertEquals(
      updated,
      genesis.copy(
        leiosCommitteeSize = 300,
        maxPledgeLeverage = Some(Rational(1, 2)),
        maxEndorserBlockExUnits = ExUnits(5, 6),
        perasBootstrapRound = Some(BigInt(5))
      )
    )
  }

  test("null clears the nullable fields") {
    val set =
      genesis.copy(maxPledgeLeverage = Some(Rational(2)), perasBootstrapRound = Some(BigInt(1)))
    val updated = DijkstraParams.update(set, update(38 -> "f6", 53 -> "f6"))
    assertEquals(updated, Right(genesis.copy(maxPledgeLeverage = None, perasBootstrapRound = None)))
  }

  test("an update with no Dijkstra key is the identity") {
    assertEquals(DijkstraParams.update(genesis, update(1 -> "00")), Right(genesis))
  }

  test("the values the field's type refuses are refused") {
    val what = DecodeField.ParameterUpdate
    assertEquals(
      DijkstraParams.update(genesis, update(36 -> "00")),
      Left(LedgerDecodeError.Zero(what))
    )
    assertEquals(
      DijkstraParams.update(genesis, update(43 -> "1a00010000")),
      Left(LedgerDecodeError.NumberOutOfRange(what, 65536, 0, 65535))
    )
    // a PositiveInterval is not zero
    val zeroMultiplier = 37 -> "d81e820001"
    assert(DijkstraParams.update(genesis, update(zeroMultiplier)).isLeft)
    assertEquals(
      DijkstraParams.update(genesis, update(57 -> "00")),
      Left(LedgerDecodeError.UnknownKey(what, 57))
    )
  }

private object Params:
  val base: ProtocolParams = ProtocolParams(
    44,
    155381,
    16384,
    65536,
    5000,
    4310,
    2000000,
    500000000,
    18,
    500,
    Rational(3, 10),
    Rational(3, 1000),
    Rational(1, 5),
    170000000,
    10,
    500000000,
    20,
    100000000000L,
    6,
    Rational(15)
  )
