// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import munit.FunSuite

import FlatProgram.*

/** `dWord64` of the reference reads at most ten groups of seven bits and the tenth holds bit 63
  * alone, so the decoder checks the number of groups as well as the value
  * (`fixtures/reference/plutus-flat-decode`, probes `var-*-groups-*`).
  */
class FlatScanWord64Suite extends FunSuite:
  private def decode(groups: Vector[Int]) =
    FlatScan.decode(flat((1, 0, 0), Delay(VarGroups(groups)))).map(_.scan.openVariable)

  private def padded(count: Int, last: Int): Vector[Int] =
    0x81 +: Vector.fill(count - 2)(0x80) :+ last

  test("ten groups, padded or holding bit 63, are read") {
    assertEquals(decode(padded(10, 0x00)), Right(Some(BigInt(1))))
    assertEquals(decode(Vector.fill(9)(0x80) :+ 0x01), Right(Some(BigInt(1) << 63)))
  }

  test("a tenth group holding bit 64 is refused as an overflow") {
    assertEquals(decode(Vector.fill(9)(0x80) :+ 0x02), Left(FlatScan.Failure.WordOverflow))
  }

  test("an eleventh group is refused however small the value") {
    assertEquals(decode(padded(11, 0x00)), Left(FlatScan.Failure.WordTooLong))
    assertEquals(decode(Vector.fill(9)(0x80) :+ 0x81 :+ 0x00), Left(FlatScan.Failure.WordTooLong))
  }
