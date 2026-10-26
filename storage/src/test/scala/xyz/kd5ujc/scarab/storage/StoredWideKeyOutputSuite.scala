// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.protocol.Era

/** A block refuses a map key beyond 32 bits, as the reference does (`invalidField` reads a full
  * `Word`, cardano-ledger f649f975 `Binary/Coders.hs`), but the builds before #379 read such a key
  * as its low 32 bits, so `2^32` was the address, and stored the output's original bytes (value tag
  * 1). The store keeps reading what they wrote (ADR 0006, [[FieldKeys.Stored]]), while a body that
  * carries the same output is refused.
  */
class StoredWideKeyOutputSuite extends FunSuite:
  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.toString), identity)

  private val address = "61" + "22" * 28

  /** `{2^32: address, 1: coin}`: the key of the address is 2^32, whose low 32 bits are 0. */
  private val wide = hex("a2" + "1b0000000100000000" + "581d" + address + "01" + "1a001e8480")

  /** The value a build before #379 stored for that output: tag 1, then its original bytes, since it
    * read them back as the same output (`UtxoStore.Coding.encodeValue`).
    */
  private val previous: Array[Byte] = hex("01" + wide.hex).toArray

  private def body(output: Bytes): Bytes =
    hex("a3" + "008182" + "5820" + "11" * 32 + "00" + "0181" + output.hex + "021a00030d40")

  test("an output a previous build stored with a key beyond 32 bits reads back as it did then") {
    val back = UtxoStore.Coding.decodeValue(previous)
    assert(back.isRight, back.toString)
    assertEquals(back.map(_.raw), Right(wide))
    assertEquals(back.map(_.address.raw.hex), Right(address))
    assertEquals(back.map(_.value.coin), Right(BigInt(2000000)))
    val again = back.map(UtxoStore.Coding.encodeValue(_).toVector)
    assertEquals(again, Right(previous.toVector), "it is written again as the same bytes")
  }

  test("a Babbage or Conway body with the same output is refused for the key") {
    for era <- Vector(Era.Babbage, Era.Conway) do
      assertEquals(
        TxBody.decode(body(wide), era).left.toOption,
        Some(LedgerDecodeError.UnknownKey(DecodeField.Output, BigInt(1) << 32)),
        era.toString
      )
  }
