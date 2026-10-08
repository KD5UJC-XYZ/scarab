// SPDX-License-Identifier: Apache-2.0
package lab

import lab.cbor.Bytes

class MinimumOutputCommandSuite extends munit.FunSuite:
  private def parameters(text: String) =
    MinimumOutputCommand.parameters(ReferenceJson.parse(Bytes.fromArray(text.getBytes("UTF-8"))))
  private def json(cost: String, major: String = "9", minor: String = "0") =
    s"""{"protocolVersion":{"major":$major,"minor":$minor},"utxoCostPerByte":$cost}"""
  test("uses exact exported cost rather than a hardcoded constant") {
    assertEquals(parameters(json("4310")).coinsPerUTxOByte, BigInt(4310))
    assertEquals(parameters(json("5000")).coinsPerUTxOByte, BigInt(5000))
  }
  test("rejects malformed, noninteger, duplicate and out-of-range parameters") {
    for value <- Vector("-1", "0", "4310.0", "4.31e3", "\"4310\"", "18446744073709551616") do
      intercept[IllegalArgumentException](parameters(json(value)))
    intercept[IllegalArgumentException](parameters(json("4310", "11")))
    intercept[IllegalArgumentException](parameters(json("4310", "9", "1")))
    intercept[IllegalArgumentException](parameters(json("4310, \"utxoCostPerByte\": 5000")))
  }
