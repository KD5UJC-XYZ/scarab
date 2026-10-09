// SPDX-License-Identifier: Apache-2.0
package lab.submission

import lab.Blake2b
import lab.cbor.Bytes

class SignedTransactionSuite extends munit.FunSuite:
  private def raw(hex: String): Bytes = Bytes.fromHex(hex).toOption.get
  private def get(bytes: Bytes): SignedTransaction =
    SignedTransaction.checked(bytes).fold(e => fail(e.toString), identity)

  test("noncanonical original body bytes determine identity without re-encoding") {
    val canonical = get(raw("84a10000a0f5f6"))
    val noncanonical = get(raw("84a1001800a0f5f6"))
    assertEquals(noncanonical.original, raw("84a1001800a0f5f6"))
    assertEquals(noncanonical.originalBody, raw("a1001800"))
    assertEquals(noncanonical.transactionId, Blake2b.hash256.hash(raw("a1001800")))
    assertNotEquals(canonical.transactionId, noncanonical.transactionId)
    assertEquals(noncanonical.byteSize, 8)
  }
  test("witness variants keep body ID while preserving distinct signed originals") {
    val first = get(raw("84a10000a10000f5f6"))
    val second = get(raw("84a10000a10001f5f6"))
    assertEquals(first.transactionId, second.transactionId)
    assertNotEquals(first.envelopeSHA256, second.envelopeSHA256)
    assertEquals(first.originalWitnesses, raw("a10000"))
    assertEquals(second.originalWitnesses, raw("a10001"))
    assertEquals(first.originalAuxiliary, raw("f6"))
    assert(first.isValid)
  }
  test("structural checker retains future fields false flag and non-null auxiliary data") {
    val transaction = get(raw("84a1186300a1186400f4a0"))
    assert(!transaction.isValid)
    assertEquals(transaction.originalBody, raw("a1186300"))
    assertEquals(transaction.originalWitnesses, raw("a1186400"))
    assertEquals(transaction.originalAuxiliary, raw("a0"))
  }
  test("duplicate numeric keys reject even when their original integer encodings differ") {
    Vector("84a20000180000a0f5f6", "84a0a20000180000f5f6").foreach { hex =>
      assert(
        SignedTransaction
          .checked(raw(hex))
          .left
          .toOption
          .exists(_.isInstanceOf[SignedTransaction.Error.MalformedShape])
      )
    }
  }
  test("array map key and Boolean shape violations remain typed structural failures") {
    Vector(
      "83a0a0f5",
      "84a0a0f5f600",
      "8480a0f5f6",
      "84a080f5f6",
      "84a0a000f6",
      "84a12000a0f5f6",
      "84a0a16000f5f6"
    ).foreach { hex =>
      val failure = SignedTransaction.checked(raw(hex)).left.toOption.getOrElse(fail(hex))
      if hex == "84a0a0f5f600" then
        assert(failure.isInstanceOf[SignedTransaction.Error.DecodeRejected])
      else assert(failure.isInstanceOf[SignedTransaction.Error.MalformedShape])
    }
    assert(
      SignedTransaction
        .checked(null)
        .left
        .toOption
        .exists(_.isInstanceOf[SignedTransaction.Error.MalformedShape])
    )
    assert(
      SignedTransaction
        .checked(Bytes.empty)
        .left
        .toOption
        .exists(_.isInstanceOf[SignedTransaction.Error.DecodeRejected])
    )
  }
  test("exact byte ceiling is accepted and excess input fails before decoding") {
    val exact =
      Bytes(raw("84a0a10059fff7").value ++ Vector.fill(65527)(0.toByte) ++ raw("f5f6").value)
    assertEquals(exact.size, 65536)
    assertEquals(get(exact).byteSize, 65536)
    assertEquals(
      SignedTransaction.checked(Bytes(exact.value :+ 0.toByte)),
      Left(SignedTransaction.Error.InputLimit)
    )
  }
  test("nesting and decoded item work remain independently bounded below the byte limit") {
    val deep = Bytes(raw("84a100").value ++ Vector.fill(33)(0x81.toByte) ++ raw("00a0f5f6").value)
    val many =
      Bytes(raw("84a100994000").value ++ Vector.fill(16384)(0.toByte) ++ raw("a0f5f6").value)
    Vector(deep, many).foreach { bytes =>
      assert(bytes.size < SignedTransaction.MaxBytes)
      assert(
        SignedTransaction
          .checked(bytes)
          .left
          .toOption
          .exists(_.isInstanceOf[SignedTransaction.Error.DecodeRejected])
      )
    }
  }
