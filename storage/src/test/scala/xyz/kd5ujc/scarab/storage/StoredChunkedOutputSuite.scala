// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.protocol.Era

/** A block refuses a chunked byte string where the reference reads a definite one
  * ([[ByteStrings]]), and a value written as an indefinite list where the reference reads the
  * `RecD` record `MaryValue` (#668), but a build before that accepted such an output and stored its
  * original bytes (value tag 1). The store keeps reading what it wrote (ADR 0006), with the
  * original bytes, while a body that carries the same output is refused.
  */
class StoredChunkedOutputSuite extends FunSuite:
  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.toString), identity)

  private val address = "61" + "22" * 28

  /** `[address, coin]` with the address as one chunk of an indefinite byte string. */
  private val chunkedAddress = hex("82" + "5f581d" + address + "ff" + "1a001e8480")

  /** `{0: address, 1: coin, 2: [0, datum hash]}` with the datum hash as one chunk. */
  private val chunkedDatumHash =
    hex("a3" + "00581d" + address + "011a001e8480" + "02" + "82005f5820" + "58" * 32 + "ff")

  /** `[address, [coin, {policy: {"a": 1}}]]` with the value as an indefinite list. */
  private val indefiniteValue =
    hex("82" + "581d" + address + "9f" + "1a001e8480" + "a1581c" + "88" * 28 + "a1416101" + "ff")

  private def stored(raw: Bytes): Array[Byte] = (1.toByte +: raw.toArray.toVector).toArray

  private def body(output: Bytes): Bytes =
    hex("a3" + "008182" + "5820" + "11" * 32 + "00" + "0181" + output.hex + "021a00030d40")

  for (name, raw, site, eras) <- Vector(
      (
        "a chunked address",
        chunkedAddress,
        DecodeField.OutputAddress,
        Vector(Era.Babbage, Era.Conway)
      ),
      (
        "a chunked datum hash",
        chunkedDatumHash,
        DecodeField.DatumHash,
        Vector(Era.Babbage, Era.Conway)
      ),
      (
        "an indefinite value",
        indefiniteValue,
        DecodeField.OutputValue,
        Vector(Era.Mary, Era.Alonzo, Era.Babbage, Era.Conway)
      )
    )
  do
    test(s"an output stored with $name reads back with its original bytes") {
      val back = UtxoStore.Coding.decodeValue(stored(raw))
      assert(back.isRight, back.toString)
      assertEquals(back.map(_.raw), Right(raw))
      val again = back.map(UtxoStore.Coding.encodeValue(_).toVector)
      assertEquals(again, Right(stored(raw).toVector), "it is written again as the same bytes")
    }

    test(s"a body of ${eras.mkString(", ")} with $name in the same output is refused") {
      for era <- eras do
        assertEquals(
          TxBody.decode(body(raw), era).left.toOption,
          Some(LedgerDecodeError.IndefiniteLength(site)),
          era.toString
        )
    }
