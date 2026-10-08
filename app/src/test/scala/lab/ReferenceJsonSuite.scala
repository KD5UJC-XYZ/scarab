// SPDX-License-Identifier: Apache-2.0
package lab

import lab.cbor.Bytes

class ReferenceJsonSuite extends munit.FunSuite:
  def parse(s: String) = ReferenceJson.parse(Bytes.fromArray(s.getBytes("UTF-8")))
  test("exact integers remain distinct above double precision") {
    assertEquals(ReferenceJson.uint(parse("9007199254740993")), BigInt("9007199254740993"))
  }
  test("nested valid JSON and escaped equivalent keys") {
    val j = parse("{\"a\":[true,false,null,{\"n\":42}],\"b\":-2.5e+2}")
    assertEquals(ReferenceJson.array(ReferenceJson.field(j, "a")).size, 4)
    intercept[IllegalArgumentException](parse("{\"a\":1,\"\\u0061\":2}"))
  }
  test("reject duplicate names throughout objects") {
    for s <- Vector("{\"x\":1,\"x\":1}", "{\"unused\":{\"x\":0,\"x\":1}}") do
      intercept[IllegalArgumentException](parse(s))
  }
  test("numeric projection rejects floats exponent strings negative and oversized values") {
    for s <- Vector("1.0", "1e0", "-0", "-1", "\"1\"", "1" * 40) do
      intercept[IllegalArgumentException](ReferenceJson.uint(parse(s)))
  }
  test("reject malformed grammar and trailing content") {
    for s <- Vector(
        "01",
        "1.",
        "1e",
        "+1",
        "[1,]",
        "{\"a\":1,}",
        "true false",
        "\"\\q\"",
        "\"\\ud800\""
      )
    do intercept[IllegalArgumentException](parse(s))
  }
  test("reject excessive nesting and invalid UTF8") {
    intercept[IllegalArgumentException](parse("[" * 66 + "0" + "]" * 66))
    intercept[java.nio.charset.CharacterCodingException](
      ReferenceJson.parse(Bytes(Vector(0xc0.toByte, 0x80.toByte)))
    )
  }
