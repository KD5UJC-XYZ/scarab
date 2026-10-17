// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import munit.FunSuite

class CborOutSuite extends FunSuite:
  private def integer(n: BigInt): String = CborOut.build(_.integer(n)).hex

  test("an Integer up to 64 bits is a CBOR integer, as cborg writes it") {
    assertEquals(integer(0), "00")
    assertEquals(integer(BigInt(2).pow(32)), "1b0000000100000000")
    assertEquals(integer(BigInt(2).pow(64) - 1), "1bffffffffffffffff")
  }

  test("beyond 64 bits it is a tag-2 big number: the bytes big-endian without leading zeros") {
    assertEquals(integer(BigInt(2).pow(64)), "c249010000000000000000")
    assertEquals(integer(BigInt("101952000000000000000")), "c2490586de43d5cca00000")
  }
