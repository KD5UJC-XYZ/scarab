// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.byron.ByronAddress

/** An address of header kind 8 in an output is a bootstrap address, and the reference decodes the
  * whole byte string as the CRC-protected Byron `Address` in every era (`decodeBootstrapAddress`,
  * `decodeAddrStateLenientT`, `Cardano/Ledger/Address.hs`, cardano-ledger-core 1.21.0.0): a
  * malformed one fails the decoder, and with it the transaction.
  */
class ByronOutputAddressSuite extends FunSuite:
  private val valid: Bytes =
    ByronAddress.make(ByronAddress.VerKey, Bytes(Vector.fill(64)(0x42.toByte)), None)
  private val root: Bytes =
    ByronAddress.decode(valid).fold(e => fail(e.toString), _.root)

  private def flipLast(b: Bytes): Bytes =
    Bytes(b.value.updated(b.size - 1, (b.value.last ^ 1).toByte))

  /** `[address, 2 ada]`: the address as a byte string of the legacy output. */
  private def output(address: Bytes): Bytes =
    val head =
      if address.size < 24 then Vector((0x40 | address.size).toByte)
      else Vector(0x58, address.size).map(_.toByte)
    Bytes(
      Vector(0x82.toByte) ++ head ++ address.value ++ Vector(0x1a, 0x00, 0x1e, 0x84, 0x80).map(
        _.toByte
      )
    )

  test("a Byron address decodes to the root of its payload") {
    assertEquals(Address.decode(valid).map(_.kind), Right(Address.Kind.Byron(root)))
    assertEquals(root.size, 28)
  }

  test("a CRC that does not match the payload is a typed decode error") {
    assertEquals(
      Address.decode(flipLast(valid)).swap.toOption,
      ByronAddress.decode(flipLast(valid)).swap.toOption
    )
    assert(Address.decode(flipLast(valid)).isLeft)
    assert(
      Address.decode(flipLast(valid)).left.exists(_.isInstanceOf[LedgerDecodeError.CrcMismatch])
    )
  }

  test("bytes after the address, a wrong envelope and a wrong header are refused") {
    val refused = List(
      valid ++ Bytes(Vector(0.toByte)),
      Bytes(valid.value.updated(0, 0x83.toByte)),
      Bytes(Vector(0x82.toByte, 0.toByte)),
      Bytes(Vector(0x82.toByte)),
      Bytes(Vector(0x80.toByte))
    )
    refused.foreach(a => assert(Address.decode(a).isLeft, a.hex))
  }

  test("no mode of any era keeps an address the Byron decoder refuses") {
    Address.Mode.values.foreach { mode =>
      assert(Address.decode(flipLast(valid), mode).isLeft, mode.toString)
      assert(Address.decode(valid ++ Bytes(Vector(0.toByte)), mode).isLeft, mode.toString)
      assert(Address.decode(valid, mode).isRight, mode.toString)
    }
  }

  test("an output with a malformed Byron address is refused, as is its body") {
    assert(TxOut.fromBytes(output(valid)).isRight)
    assert(TxOut.fromBytes(output(flipLast(valid))).isLeft)
    Address.Mode.values.foreach { mode =>
      assert(TxOut.fromBytes(output(flipLast(valid)), mode).isLeft, mode.toString)
    }
  }
