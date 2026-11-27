// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.state.{Alonzo, Babbage, Conway, Dijkstra, EraTag, ShelleyBasedEra}

/** Key 17 of a protocol-parameter update is the lovelace per UTxO word before Babbage
  * (`Alonzo/PParams.hs:664`, cardano-ledger f649f975) and per byte from Babbage; an update that
  * raises the version is applied by the rules of the era it was proposed in, and the translation to
  * Babbage divides the per-word value by 8 (`Babbage/PParams.hs:389-391`). Key 18's cost models
  * replace those in force up to Babbage and are merged into them from Conway.
  */
class ProtocolParamsUpdateSuite extends FunSuite:
  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.toString), identity)

  private val alonzo = TestLedger
    .params(6)
    .copy(coinsPerUTxOWord = 34482, coinsPerUTxOByte = 34482 / 8)
  private val babbage = TestLedger.params(7).copy(coinsPerUTxOByte = 4310)

  private val key17 = hex("19" + "9c40") // 40000
  private val toBabbage = hex("820700") // [7, 0]

  private def applied[E <: EraTag](p: ProtocolParams, fields: (Int, Bytes)*)(using
      ShelleyBasedEra[E]
  ): ProtocolParams =
    ProtocolParams.update(p, ParamUpdate(fields.toMap)).fold(e => fail(e.toString), identity)

  test("in Alonzo key 17 sets the lovelace per word, and the per-byte value is not touched") {
    val after = applied[Alonzo](alonzo, 17 -> key17)
    assertEquals(after.coinsPerUTxOWord, BigInt(40000))
    assertEquals(after.coinsPerUTxOByte, alonzo.coinsPerUTxOByte)
  }

  test("an Alonzo update that raises the version and names key 17 sets only the word") {
    val after = applied[Alonzo](alonzo, 14 -> toBabbage, 17 -> key17)
    assertEquals(after.protocolMajor, BigInt(7))
    assertEquals(after.coinsPerUTxOWord, BigInt(40000))
    assertEquals(after.coinsPerUTxOByte, alonzo.coinsPerUTxOByte)
  }

  test("an Alonzo update that raises the version leaves translation to the era boundary") {
    val after = applied[Alonzo](alonzo, 14 -> toBabbage)
    assertEquals(after.coinsPerUTxOWord, alonzo.coinsPerUTxOWord)
    assertEquals(after.coinsPerUTxOByte, alonzo.coinsPerUTxOByte)
  }

  test("in Babbage key 17 sets the lovelace per byte") {
    val after = applied[Babbage](babbage, 17 -> key17)
    assertEquals(after.coinsPerUTxOByte, BigInt(40000))
    assertEquals(after.coinsPerUTxOWord, babbage.coinsPerUTxOWord)
  }

  // PlutusV1's cost model alone (key 18), applied to parameters holding PlutusV1 and PlutusV2
  private val onlyV1 = hex("a100" + "98a6" + "1864" * 166)
  private val v1 = Vector.fill(166)(BigInt(100))

  test("in Alonzo and Babbage key 18 replaces the cost models in force") {
    // the class default `applyPPUpdates` (`Alonzo/PParams.hs:314`, `Babbage/PParams.hs:172`) sets
    // each field the update names (`Core/PParams.hs:320-358, 379-392`, cardano-ledger f649f975)
    assertEquals(alonzo.costModels.keySet, Set(0, 1))
    assertEquals(applied[Alonzo](alonzo, 18 -> onlyV1).costModels.keySet, Set(0))
    assertEquals(applied[Alonzo](alonzo, 18 -> onlyV1).costModels, Map(0 -> v1))
    assertEquals(babbage.costModels.keySet, Set(0, 1))
    assertEquals(applied[Babbage](babbage, 18 -> onlyV1).costModels.keySet, Set(0))
    assertEquals(applied[Babbage](babbage, 18 -> onlyV1).costModels, Map(0 -> v1))
  }

  test("in Alonzo and Babbage an empty key 18 clears the cost models, and no key 18 keeps them") {
    // `SJust x -> x` and `SNothing -> x'` of the generic update (`Core/PParams.hs:340-343`,
    // cardano-ledger f649f975)
    val none = hex("a0")
    assertEquals(applied[Alonzo](alonzo, 18 -> none).costModels, Map.empty)
    assertEquals(applied[Babbage](babbage, 18 -> none).costModels, Map.empty)
    assertEquals(applied[Alonzo](alonzo, 0 -> hex("01")).costModels, alonzo.costModels)
    assertEquals(applied[Babbage](babbage, 0 -> hex("01")).costModels, babbage.costModels)
  }

  test("in Conway and Dijkstra key 18 merges into the cost models in force") {
    // `updateCostModels` in `conwayApplyPPUpdates` (`Conway/PParams.hs:830-831, 1209`, cardano-ledger
    // f649f975) and `dijkstraApplyPPUpdates` (`Dijkstra/PParams.hs:340-343, 711-712`,
    // cardano-ledger 9df9f98c)
    val conway = TestLedger.params(10)
    val merged = Map(0 -> v1, 1 -> conway.costModels(1))
    assertEquals(applied[Conway](conway, 18 -> onlyV1).costModels, merged)
    assertEquals(applied[Dijkstra](conway, 18 -> onlyV1).costModels, merged)
  }
