// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus

import munit.FunSuite

/** The PBFT window threshold, `floor (threshold * fromIntegral winSize) :: Word64`
  * (`Ouroboros/Consensus/Protocol/PBFT.hs:403-412`, ouroboros-consensus 82ecba32). The expected
  * values are GHC 9.10.3's, measured at `-O0` and `-O1` alike (#734).
  */
class ByronWindowThresholdSuite extends FunSuite:
  private val max = (BigInt(1) << 64) - 1

  test("a threshold within a Word64 is the floor of the product") {
    assertEquals(ByronRules.windowThreshold(0.22, 432), BigInt(95))
    assertEquals(ByronRules.windowThreshold(1, 0), BigInt(0))
    assertEquals(ByronRules.windowThreshold(1, BigInt(1) << 63), BigInt("9223372036854775808"))
    assertEquals(
      ByronRules.windowThreshold(0.5, (BigInt(1) << 63) + 5),
      BigInt("4611686018427387904")
    )
    assertEquals(ByronRules.windowThreshold(1.5, BigInt(1) << 63), BigInt("13835058055282163712"))
    assertEquals(ByronRules.windowThreshold(0.9999999, max), BigInt("18446742229035145216"))
  }

  test("a product of 2^64 or more is taken modulo 2^64, as GHC's floor into a Word64 does") {
    assertEquals(ByronRules.windowThreshold(1, max), BigInt(0))
    assertEquals(ByronRules.windowThreshold(2, max), BigInt(0))
    assertEquals(ByronRules.windowThreshold(Double.PositiveInfinity, 5), BigInt(0))
    assertEquals(ByronRules.windowThreshold(Double.NaN, 5), BigInt(0))
    assertEquals(ByronRules.windowThreshold(-0.5, 3), (BigInt(1) << 64) - 2)
  }
