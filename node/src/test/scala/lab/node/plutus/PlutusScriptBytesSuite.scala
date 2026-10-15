// SPDX-License-Identifier: Apache-2.0
package lab.node.plutus

import munit.FunSuite

class PlutusScriptBytesSuite extends FunSuite:
  private def b(xs: Int*): Array[Byte] = xs.map(_.toByte).toArray

  test("a definite byte string, with and without a remainder") {
    assertEquals(PlutusScriptBytes.cborItemLength(b(0x43, 1, 2, 3)), 4)
    assertEquals(PlutusScriptBytes.cborItemLength(b(0x43, 1, 2, 3, 9, 9)), 4)
    assertEquals(PlutusScriptBytes.cborItemLength(b(0x59, 0, 2, 7, 7, 1)), 5)
  }

  test("an indefinite byte string ends at its break") {
    assertEquals(PlutusScriptBytes.cborItemLength(b(0x5f, 0x42, 1, 2, 0x41, 3, 0xff, 8)), 7)
  }

  test("anything else is taken whole, for the decoder to reject") {
    assertEquals(PlutusScriptBytes.cborItemLength(b(0x43, 1, 2)), 3)
    assertEquals(PlutusScriptBytes.cborItemLength(b(0x01, 2)), 2)
    assertEquals(PlutusScriptBytes.cborItemLength(b()), 0)
    assertEquals(PlutusScriptBytes.cborItemLength(b(0x5f, 0x41, 1)), 3)
  }
