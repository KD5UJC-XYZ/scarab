// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes

/** Key 17 of a protocol-parameter update is the lovelace per UTxO word before Babbage
  * (`Alonzo/PParams.hs:664`) and per byte from Babbage; an update that raises the version is
  * applied by the rules of the era it was proposed in, and the translation to Babbage divides the
  * per-word value by 8 (`Babbage/PParams.hs:389-391`).
  */
class ProtocolParamsUpdateSuite extends FunSuite:
  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.toString), identity)

  private val alonzo = TestLedger
    .params(6)
    .copy(coinsPerUTxOWord = 34482, coinsPerUTxOByte = 34482 / 8)
  private val babbage = TestLedger.params(7).copy(coinsPerUTxOByte = 4310)

  private val key17 = hex("19" + "9c40") // 40000
  private val toBabbage = hex("820700") // [7, 0]

  private def applied(p: ProtocolParams, fields: (Int, Bytes)*): ProtocolParams =
    ProtocolParams.update(p, ParamUpdate(fields.toMap)).fold(e => fail(e.toString), identity)

  test("in Alonzo key 17 sets the lovelace per word, and the per-byte value is not touched") {
    val after = applied(alonzo, 17 -> key17)
    assertEquals(after.coinsPerUTxOWord, BigInt(40000))
    assertEquals(after.coinsPerUTxOByte, alonzo.coinsPerUTxOByte)
  }

  test("an Alonzo update that raises the version and names key 17 sets the word, then divides") {
    val after = applied(alonzo, 14 -> toBabbage, 17 -> key17)
    assertEquals(after.protocolMajor, BigInt(7))
    assertEquals(after.coinsPerUTxOWord, BigInt(40000))
    assertEquals(after.coinsPerUTxOByte, BigInt(5000))
  }

  test("an Alonzo update that raises the version without key 17 derives the per-byte value") {
    val after = applied(alonzo, 14 -> toBabbage)
    assertEquals(after.coinsPerUTxOWord, alonzo.coinsPerUTxOWord)
    assertEquals(after.coinsPerUTxOByte, BigInt(4310))
  }

  test("in Babbage key 17 sets the lovelace per byte") {
    val after = applied(babbage, 17 -> key17)
    assertEquals(after.coinsPerUTxOByte, BigInt(40000))
    assertEquals(after.coinsPerUTxOWord, babbage.coinsPerUTxOWord)
  }
