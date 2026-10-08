// SPDX-License-Identifier: Apache-2.0
package lab

import lab.cbor.{Bytes, Cbor}

class HashingSuite extends munit.FunSuite:
  private def hex(s: String): Bytes = Bytes.fromHex(s).toOption.get
  test("Blake2b empty RFC7693 parameterization vectors") {
    assertEquals(
      Blake2b.hash256.hash(Bytes.empty).hex,
      "0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8"
    )
    assertEquals(
      Blake2b.hash224.hash(Bytes.empty).hex,
      "836cc68931c2e4e3e838602eca1902591d216837bafddfe6f0c8cb07"
    )
    assertNotEquals(
      Blake2b.hash224.hash(Bytes.empty).hex,
      Blake2b.hash256.hash(Bytes.empty).hex.take(56)
    )
  }
  test("transaction identity hashes original body not a normalized reencoding") {
    val definite = hex("a10000")
    val indefinite = hex("bf0000ff")
    assertEquals(
      Cbor.decode(definite).toOption.get.value,
      Cbor.decode(indefinite).toOption.get.value
    )
    assertNotEquals(TransactionId.fromBody(definite), TransactionId.fromBody(indefinite))
    assertEquals(TransactionId.bodyBytes(hex("84bf0000ffa0f5f6")), Right(indefinite))
    assertEquals(
      TransactionId.fromEnvelope(hex("84bf0000ffa0f5f6")),
      TransactionId.fromBody(indefinite)
    )
  }
  test("invalid envelopes and body shape return errors") {
    List("80", "8400a0f5f6", "83a0a0f5", "84a0a0f5f600").foreach { s =>
      assert(TransactionId.fromEnvelope(hex(s)).isLeft)
    }
    assert(TransactionId.fromBody(hex("80")).isLeft)
  }
