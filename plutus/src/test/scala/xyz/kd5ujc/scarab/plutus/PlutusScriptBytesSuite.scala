// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import munit.FunSuite

class PlutusScriptBytesSuite extends FunSuite:
  private def b(xs: Int*): Array[Byte] = xs.map(_.toByte).toArray

  test("a definite byte string, with and without a remainder") {
    assertEquals(PlutusScriptBytes.cborItemLength(b(0x43, 1, 2, 3)), 4)
    assertEquals(PlutusScriptBytes.cborItemLength(b(0x43, 1, 2, 3, 9, 9)), 4)
    assertEquals(PlutusScriptBytes.cborItemLength(b(0x59, 0, 2, 7, 7, 1)), 5)
  }

  test("a definite length of eight bytes is read") {
    assertEquals(PlutusScriptBytes.cborItemLength(b(0x5b, 0, 0, 0, 0, 0, 0, 0, 2, 7, 7, 1)), 11)
    assertEquals(
      PlutusScriptBytes.flatOf(b(0x5b, 0, 0, 0, 0, 0, 0, 0, 2, 7, 7, 1)).map(_.toVector),
      Some(Vector(7, 7).map(_.toByte))
    )
  }

  test("an indefinite byte string is not the script bytes the reference reads") {
    val chunked = b(0x5f, 0x42, 1, 2, 0x41, 3, 0xff, 8)
    assertEquals(PlutusScriptBytes.cborItemLength(chunked), chunked.length)
    assertEquals(PlutusScriptBytes.flatOf(chunked), None)
  }

  test("anything else is taken whole, for the decoder to reject") {
    assertEquals(PlutusScriptBytes.cborItemLength(b(0x43, 1, 2)), 3)
    assertEquals(PlutusScriptBytes.cborItemLength(b(0x01, 2)), 2)
    assertEquals(PlutusScriptBytes.cborItemLength(b()), 0)
    assertEquals(PlutusScriptBytes.cborItemLength(b(0x5f, 0x41, 1)), 3)
    assertEquals(PlutusScriptBytes.flatOf(b(0x43, 1, 2)), None)
  }
