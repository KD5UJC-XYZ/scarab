// SPDX-License-Identifier: Apache-2.0
package lab.node.plutus

import lab.cbor.Bytes
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
    assert(decode("c100").isLeft)
    assert(decode("f93c00").isLeft)
  }

  test("the unit, Maybe and Bool shapes") {
    assertEquals(PlutusData.unit, Constr(0, Vector.empty))
    assertEquals(PlutusData.maybe(None), Constr(1, Vector.empty))
    assertEquals(PlutusData.maybe(Some(I(1))), Constr(0, Vector(I(1))))
    assertEquals(PlutusData.bool(true), Constr(1, Vector.empty))
  }
