// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{Address, Datum, TxIn, TxOut, Value}
import xyz.kd5ujc.scarab.ledger.state.{ProtVer, Word64}

import NodeOrders.given

/** The UTxO codecs and the unordered-container combinators the node-to-client answers are written
  * with: the bytes are those the reference ledger writes (`Mary/Value.hs`, `Babbage/TxOut.hs`,
  * `encodeMap`, `encodeSet`), worked out by hand.
  */
class UtxoCodecsSuite extends FunSuite:
  private given ProtVer = ProtVer(Word64.fromUnsigned(9), Word64.zero)

  private def bytes(from: Int, size: Int): Bytes =
    Bytes(Vector.tabulate(size)(i => (from + i).toByte))

  private val address = bytes(0x61, 29)
  private val owner = Address.decode(address).fold(e => fail(e), identity)

  private def hex(expected: String)(actual: Bytes): Unit =
    assertEquals(actual.hex, expected.filterNot(_.isWhitespace))

  test("a value with no assets is the bare coin; with assets it is [coin, {policy: {asset: n}}]") {
    hex("1a000f4240")(
      UtxoCodecs.value.encodeUnchecked(Value(1000000, Map.empty))
    )
    val policy = Bytes(Vector.fill(28)(7.toByte))
    val multi = Value(
      5,
      Map(
        policy -> Map(Bytes(Vector(2.toByte)) -> BigInt(-3), Bytes(Vector(1.toByte)) -> BigInt(4))
      )
    )
    hex(s"8205 a1 581c${policy.hex} a2 4101 04 4102 22")(
      UtxoCodecs.value.encodeUnchecked(multi)
    )
    assertEquals(
      UtxoCodecs.value.decodeBytes(UtxoCodecs.value.encodeUnchecked(multi)),
      Right(multi)
    )
  }

  test("an output is [address, value] with no datum, and the UTxO is in input order") {
    val out = TxOut(owner, Value(2, Map.empty))
    hex(s"82 581d${address.hex} 02")(UtxoCodecs.txOut.encodeUnchecked(out))
    val low = TxIn(bytes(0, 32), 1)
    val high = TxIn(bytes(0x80, 32), 0)
    val encoded = UtxoCodecs.utxo.encodeUnchecked(Map(high -> out, low -> out))
    assert(encoded.hex.startsWith("a2825820" + low.id.hex + "01"), encoded.hex)
    assertEquals(out.datum, Datum.NoDatum)
  }

  test("a definite set or map stays definite above 23 entries, the others become indefinite") {
    val keys = (0 until 30).map(i => bytes(i, 28)).toSet
    val word = Wire.word(64)
    val definite = Wire.definiteSetOf(Wire.hash(28)).encodeUnchecked(keys)
    assert(definite.hex.startsWith("d90102981e"), definite.hex.take(12))
    val variable = Wire.sortedSetOf(Wire.hash(28)).encodeUnchecked(keys)
    assert(variable.hex.startsWith("d901029f"), variable.hex.take(12))
    val entries = keys.map(_ -> BigInt(1)).toMap
    assert(
      Wire
        .definiteMapOf(Wire.hash(28), word)
        .encodeUnchecked(entries)
        .hex
        .startsWith("b8 1e".filterNot(_.isWhitespace))
    )
    assert(Wire.sortedMapOf(Wire.hash(28), word).encodeUnchecked(entries).hex.startsWith("bf"))
  }

  test("a value out of the range of its Haskell type is refused, not written") {
    assert(UtxoCodecs.txIn.write(TxIn(bytes(0, 31), 0)).isLeft)
    assert(UtxoCodecs.txIn.write(TxIn(bytes(0, 32), 70000)).isLeft)
  }
