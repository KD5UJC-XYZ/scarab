// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Value as CborValue}

class LedgerDecodeErrorSuite extends FunSuite:
  private def bytes(hex: String): Bytes = Bytes.fromHex(hex).fold(e => fail(e.toString), identity)
  private def node(hex: String) = CborRead.decode(bytes(hex)).fold(e => fail(e.toString), identity)

  test("a primitive reader retains the expected type, field and actual value") {
    assertEquals(
      CborRead.uint(node("f5"), "transaction fee"),
      Left(
        LedgerDecodeError.Expected(
          "transaction fee",
          LedgerDecodeError.Shape.Unsigned,
          CborValue.Bool(true)
        )
      )
    )
  }

  test("byte length and array arity are separate cases with their measured sizes") {
    assertEquals(
      CborRead.bytesN(node("4100"), 28, "credential"),
      Left(LedgerDecodeError.ByteLength("credential", 28, 1))
    )
    assertEquals(
      CborRead.arrayN(node("80"), 2, "credential"),
      Left(LedgerDecodeError.ArrayLength("credential", 2, 0))
    )
  }

  test("malformed CBOR preserves the codec cause") {
    val malformed = bytes("81")
    assertEquals(
      CborRead.decode(malformed),
      Cbor.decode(malformed).left.map(LedgerDecodeError.Syntax(_))
    )
  }

  test("a missing transaction field is retained without parsing error text") {
    assertEquals(
      TxBody.decode(bytes("a0"), Address.Mode.Rigorous).map(_ => ()),
      Left(LedgerDecodeError.MissingField("transaction body", "inputs"))
    )
  }

  test("address absence and invalid header remain distinguishable") {
    assertEquals(Address.decode(Bytes.empty), Left(LedgerDecodeError.Empty("address")))
    assertEquals(Address.decode(bytes("f0")), Left(LedgerDecodeError.AddressHeader(240)))
  }

  test("unknown certificate and script tags preserve the numeric tag") {
    assertEquals(
      Certificate.decode(node("811863")),
      Left(LedgerDecodeError.TaggedArity("certificate", 99, 1))
    )
    assertEquals(
      Script.decode(node("82186300")),
      Left(LedgerDecodeError.UnknownTag("script type", 99))
    )
  }

  test("native script shape failures retain their structured ledger cause") {
    assertEquals(
      NativeScript.decode(node("8100")),
      Left(NativeScript.DecodeError.Malformed(LedgerDecodeError.TaggedArity("native script", 0, 1)))
    )
  }

  test("voting threshold arities are specific and carry actual counts") {
    assertEquals(
      PoolVotingThresholds.fromVector(Vector.empty),
      Left(LedgerDecodeError.ArrayLength("pool voting thresholds", 5, 0))
    )
    assertEquals(
      DRepVotingThresholds.fromVector(Vector.empty),
      Left(LedgerDecodeError.ArrayLength("DRep voting thresholds", 10, 0))
    )
  }

  test("native script Word64 tags cannot wrap into a recognized constructor") {
    for (tag, encodedTag) <- Vector(
        (BigInt(1) << 32) -> "1b0000000100000000",
        ((BigInt(1) << 32) + 4) -> "1b0000000100000004",
        ((BigInt(1) << 64) - 1) -> "1bffffffffffffffff"
      ) do
      assertEquals(
        NativeScript.decode(node("82" + encodedTag + "581c" + "00" * 28)),
        Left(NativeScript.DecodeError.Malformed(LedgerDecodeError.TaggedArity("native script", tag, 2)))
      )
  }
