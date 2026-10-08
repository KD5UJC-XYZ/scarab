// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import lab.cbor.Bytes

/** Synthetic predicate boundaries; reference admission is a separate opt-in live scenario. */
class MinimumOutputSuite extends munit.FunSuite:
  private def bytes(s: String) = Bytes.fromHex(s).toOption.get
  private val params = MinimumOutput.Parameters.checked("Conway", 9, 0, 4310).toOption.get
  private val address = "581d60" + "11" * 28
  private def envelope(outputs: String, count: Int = 1): Bytes =
    bytes("84a30081825820" + "22" * 32 + "0001" + f"${0x80 + count}%02x" + outputs + "0200a0f5f6")
  private def check(output: String) = MinimumOutput.check(params, envelope(output)).toOption.get
  test("37-byte original array: exact boundary and one below") {
    val good = check("82" + address + "1a000cf4ae")
    val bad = check("82" + address + "1a000cf4ad")
    assertEquals(good.outputs.head.coin, BigInt(849070))
    assertEquals(good.outputs.head.original.size, 37)
    assertEquals(good.outputs.head.required, BigInt(849070))
    assert(good.satisfied && !bad.satisfied)
  }
  test("map adds two bytes; no conversion to array") {
    val result = check("a200" + address + "011a000cf4ae")
    assertEquals(result.outputs.head.original.size, 39)
    assertEquals(result.outputs.head.required, BigInt(857690))
    assert(!result.satisfied)
  }
  test("original nonminimal coin span is measured without reserialization") {
    val result = check("82" + address + "1b00000000000cf4ae")
    assertEquals(result.outputs.head.original.size, 41)
    assertEquals(result.outputs.head.required, BigInt(866310))
    assert(!result.satisfied)
  }
  test("integer width boundaries affect required size without word rounding") {
    for (coin, width) <- Vector(
        "17" -> 1,
        "1818" -> 2,
        "18ff" -> 2,
        "190100" -> 3,
        "19ffff" -> 3,
        "1a00010000" -> 5,
        "1affffffff" -> 5,
        "1b0000000100000000" -> 9
      )
    do
      val result = check("82" + address + coin).outputs.head
      assertEquals(result.original.size, 32 + width)
      assertEquals(result.required, BigInt(192 + width) * 4310)
  }
  test("all outputs including change are checked") {
    val result = MinimumOutput
      .check(
        params,
        envelope(
          "82" + address + "1a000cf4ae" +
            "82" + address + "00",
          2
        )
      )
      .toOption
      .get
    assertEquals(result.outputs.size, 2)
    assert(result.outputs.head.satisfied && !result.satisfied)
  }
  test("unsupported version, parameter range, output shape and mainnet fail closed") {
    for (era, major, minor, cost) <- Vector(
        ("Babbage", 9, 0, BigInt(4310)),
        ("Conway", 10, 0, BigInt(4310)),
        ("Conway", 9, 1, BigInt(4310)),
        ("Conway", 9, 0, BigInt(0)),
        ("Conway", 9, 0, BigInt(1) << 64)
      )
    do assert(MinimumOutput.Parameters.checked(era, major, minor, cost).isLeft)
    assert(
      MinimumOutput
        .check(params, envelope("82" + address.replace("581d60", "581d61") + "00"))
        .isLeft
    )
    assert(MinimumOutput.check(params, envelope("83" + address + "00f6")).isLeft)
    assert(MinimumOutput.check(params, envelope("82" + address + "8200a0")).isLeft)
    assert(MinimumOutput.check(params, envelope("", 0)).isLeft)
    assert(MinimumOutput.check(params, Bytes.empty).isLeft)
  }
