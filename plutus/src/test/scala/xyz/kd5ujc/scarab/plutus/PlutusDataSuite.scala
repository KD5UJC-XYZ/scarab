// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import munit.FunSuite

import PlutusData.*

class PlutusDataSuite extends FunSuite:
  private def decode(hex: String) =
    Bytes.fromHex(hex).flatMap(PlutusData.decode)

  test("integers, byte strings, lists and maps") {
    assertEquals(decode("182a"), Right(I(42)))
    assertEquals(decode("29"), Right(I(-10)))
    assertEquals(decode("4401020304"), Right(B(Bytes(Vector(1, 2, 3, 4).map(_.toByte)))))
    assertEquals(decode("83010203"), Right(List(Vector(I(1), I(2), I(3)))))
    assertEquals(decode("9f0102ff"), Right(List(Vector(I(1), I(2)))))
    assertEquals(decode("a1010a"), Right(Map(Vector(I(1) -> I(10)))))
  }

  test("indefinite byte strings are concatenated") {
    assertEquals(decode("5f42010243030405ff"), Right(B(Bytes(Vector(1, 2, 3, 4, 5).map(_.toByte)))))
  }

  test("big numbers are tag 2 and 3 byte strings") {
    // 2^64 and -1 - 2^64
    assertEquals(decode("c249010000000000000000"), Right(I(BigInt(1) << 64)))
    assertEquals(decode("c349010000000000000000"), Right(I(-1 - (BigInt(1) << 64))))
  }

  test("constructors: tags 121 to 127, 1280 to 1400 and 102") {
    assertEquals(decode("d87980"), Right(Constr(0, Vector.empty)))
    assertEquals(decode("d87a9f01ff"), Right(Constr(1, Vector(I(1)))))
    assertEquals(decode("d87f80"), Right(Constr(6, Vector.empty)))
    assertEquals(decode("d9050080"), Right(Constr(7, Vector.empty)))
    assertEquals(decode("d9057880"), Right(Constr(127, Vector.empty)))
    assertEquals(decode("d866820980"), Right(Constr(9, Vector.empty)))
  }

  test("unknown tags and floats are refused") {
    assertEquals(decode("c100"), Left(DecodeError.UnexpectedTag(1)))
    assertEquals(decode("f93c00"), Left(DecodeError.Syntax(Cbor.Error.UnsupportedSimpleValue(25))))
  }

  test("invalid constructor and big-number shapes preserve their typed CBOR values") {
    assertEquals(decode("d87900"), Left(DecodeError.ConstructorFields(Value.UInt(0))))
    assertEquals(decode("c200"), Left(DecodeError.BigNumberBytes(Value.UInt(0))))
    assertEquals(decode("d866822080"), Left(DecodeError.ConstructorIndex(Value.NInt(-1))))
    assertEquals(decode("d86600"), Left(DecodeError.ConstructorEnvelope(Value.UInt(0))))
    assertEquals(decode("f5"), Left(DecodeError.UnexpectedItem(Value.Bool(true))))
  }

  test("a big number needs the short head the reference reads, constructors do not") {
    assertEquals(decode("d8024101"), Left(DecodeError.BigNumberWideHead(2)))
    assertEquals(decode("d8034101"), Left(DecodeError.BigNumberWideHead(3)))
    assertEquals(decode("d9000200"), Left(DecodeError.BigNumberWideHead(2)))
    assertEquals(decode("c24101"), Right(I(1)))
    assertEquals(decode("c34101"), Right(I(-2)))
    assertEquals(decode("da0000007980"), Right(Constr(0, Vector.empty)))
    assertEquals(decode("db0000000000000066820080"), Right(Constr(0, Vector.empty)))
  }

  test("a big number built by hand, without its bytes, reads as written with the short head") {
    // `Node.apply`: no bytes stand for the canonical form; reading the head once threw instead
    def built(t: Long) =
      Node(
        Value.Tag(t, Node(Value.ByteString(Bytes.fromArray(Array[Byte](1))), Bytes.empty)),
        Bytes.empty
      )
    assertEquals(PlutusData.fromNode(built(2)), Right(I(1)))
    assertEquals(PlutusData.fromNode(built(3)), Right(I(-2)))
  }

  test("the unit, Maybe and Bool shapes") {
    assertEquals(PlutusData.unit, Constr(0, Vector.empty))
    assertEquals(PlutusData.maybe(None), Constr(1, Vector.empty))
    assertEquals(PlutusData.maybe(Some(I(1))), Constr(0, Vector(I(1))))
    assertEquals(PlutusData.bool(true), Constr(1, Vector.empty))
  }
