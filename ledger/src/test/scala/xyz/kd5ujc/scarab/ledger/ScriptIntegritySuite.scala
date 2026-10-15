// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.plutus.ExUnits

import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.codec.Bytes
import munit.FunSuite

/** The language views and the hash layout of `script_data_hash`. */
class ScriptIntegritySuite extends FunSuite:
  private def bytes(hex: String): Vector[Byte] = Bytes.fromHex(hex).toOption.get.value

  private val pp = TestLedger
    .params(7)
    .copy(costModels =
      Map(0 -> Vector(1, 2, 3), 1 -> Vector(4, 5, 6, 7), 2 -> Vector(-1, 300, 70000))
    )

  test("PlutusV1 is a byte string key and a byte string wrapping an indefinite list") {
    // a1 41 00 45 9f 01 02 03 ff
    assertEquals(
      ScriptIntegrity.languageViews(pp, Set(ScriptKind.PlutusV1)),
      bytes("a1" + "4100" + "45" + "9f010203ff")
    )
  }

  test("PlutusV2 and V3 are plain keys and definite lists") {
    assertEquals(
      ScriptIntegrity.languageViews(pp, Set(ScriptKind.PlutusV2)),
      bytes("a1" + "01" + "84" + "04050607")
    )
    // -1 = 20, 300 = 19 012c, 70000 = 1a 00011170
    assertEquals(
      ScriptIntegrity.languageViews(pp, Set(ScriptKind.PlutusV3)),
      bytes("a1" + "02" + "83" + "20" + "19012c" + "1a00011170")
    )
  }

  test("keys are canonically ordered: V2 and V3 before V1, whatever the set order") {
    val all = Set[ScriptKind](ScriptKind.PlutusV1, ScriptKind.PlutusV2, ScriptKind.PlutusV3)
    assertEquals(
      ScriptIntegrity.languageViews(pp, all),
      bytes(
        "a3" + "01" + "84" + "04050607" + "02" + "83" + "20" + "19012c" + "1a00011170" +
          "4100" + "45" + "9f010203ff"
      )
    )
  }

  test("no languages is the empty map; native scripts do not count") {
    assertEquals(ScriptIntegrity.languageViews(pp, Set.empty), bytes("a0"))
    assertEquals(ScriptIntegrity.languageViews(pp, Set(ScriptKind.Native)), bytes("a0"))
  }

  test("a cost model above 255 parameters uses the two-byte array header") {
    val big = pp.copy(costModels = Map(1 -> Vector.fill(300)(BigInt(1))))
    val views = ScriptIntegrity.languageViews(big, Set(ScriptKind.PlutusV2))
    assertEquals(views.take(5), bytes("a1" + "01" + "99012c"))
    assertEquals(views.size, 5 + 300)
  }

  private val redeemers = Bytes(bytes("81" + "840000" + "01" + "820102"))
  private val datums = Bytes(bytes("8101"))
  private def tx(rawRedeemers: Option[Bytes], rawDatums: Option[Bytes]): Tx =
    val wits = Witnesses(
      Vector.empty,
      datums = rawDatums.map(_ => Bytes(Vector(1))).toVector,
      redeemers = rawRedeemers.map(_ => Redeemer(0, 0, Bytes(Vector(1)), ExUnits(1, 2))).toVector,
      redeemersRaw = rawRedeemers,
      datumsRaw = rawDatums
    )
    Tx(TestLedger.body("h", Vector.empty, Vector.empty, 0), wits, 0, true, None)

  test("the hash is blake2b-256 of the witness set's redeemers, datums and the language views") {
    val expected = Blake2b.hash256.hash(
      Bytes(redeemers.value ++ datums.value ++ bytes("a1" + "01" + "84" + "04050607"))
    )
    assertEquals(
      ScriptIntegrity.hash(tx(Some(redeemers), Some(datums)), pp, Set(ScriptKind.PlutusV2)),
      Some(expected)
    )
  }

  test("datums are omitted when there are none") {
    val expected = Blake2b.hash256.hash(
      Bytes(redeemers.value ++ bytes("a1" + "4100" + "45" + "9f010203ff"))
    )
    assertEquals(
      ScriptIntegrity.hash(tx(Some(redeemers), None), pp, Set(ScriptKind.PlutusV1)),
      Some(expected)
    )
  }

  test("nothing to hash: no redeemers, no datums, no Plutus language") {
    assertEquals(ScriptIntegrity.hash(tx(None, None), pp, Set.empty), None)
  }

  test("a cost model change changes the hash") {
    val other = pp.copy(costModels = pp.costModels.updated(1, Vector(4, 5, 6, 8)))
    assertNotEquals(
      ScriptIntegrity.hash(tx(Some(redeemers), None), pp, Set(ScriptKind.PlutusV2)),
      ScriptIntegrity.hash(tx(Some(redeemers), None), other, Set(ScriptKind.PlutusV2))
    )
  }
