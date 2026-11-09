// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus

import munit.FunSuite
import xyz.kd5ujc.scarab.ledger.{BlockFailure, ByronChainError}
import xyz.kd5ujc.scarab.ledger.state.VocabError

/** The PBFT window threshold, `floor (threshold * fromIntegral winSize) :: Word64`
  * (`Ouroboros/Consensus/Protocol/PBFT.hs:403-412`, ouroboros-consensus 82ecba32). The expected
  * values within a `Word64` are GHC 9.10.3's, measured at `-O0` and `-O1` alike (#734). A floor
  * outside `0 .. 2^64 - 1`, which GHC takes modulo 2^64, is refused instead: the configuration
  * refuses it, and the rules give no verdict on a block with it
  * (overflow-improvements.md#byron-pbft-window-threshold).
  */
class ByronWindowThresholdSuite extends FunSuite:
  private val max = (BigInt(1) << 64) - 1

  private def outOfRange(n: BigInt) =
    if n.signum < 0 then Left(VocabError.Negative(VocabError.Kind.BlockCount, n))
    else Left(VocabError.OutOfRange(VocabError.Kind.BlockCount, n))

  test("a threshold within a Word64 is the floor of the product") {
    assertEquals(ByronRules.windowThreshold(0.22, 432), Right(BigInt(95)))
    assertEquals(ByronRules.windowThreshold(1, 0), Right(BigInt(0)))
    assertEquals(
      ByronRules.windowThreshold(1, BigInt(1) << 63),
      Right(BigInt("9223372036854775808"))
    )
    assertEquals(
      ByronRules.windowThreshold(0.5, (BigInt(1) << 63) + 5),
      Right(BigInt("4611686018427387904"))
    )
    assertEquals(
      ByronRules.windowThreshold(1.5, BigInt(1) << 63),
      Right(BigInt("13835058055282163712"))
    )
    assertEquals(
      ByronRules.windowThreshold(0.9999999, max),
      Right(BigInt("18446742229035145216"))
    )
  }

  test("a product of 2^64 or more, below 0 or not finite is refused, where GHC wraps it") {
    // `fromIntegral max :: Double` is 2^64, so a threshold of 1 floors to 2^64, which GHC makes 0
    assertEquals(ByronRules.windowThreshold(1, max), outOfRange(BigInt(1) << 64))
    assertEquals(ByronRules.windowThreshold(2, max), outOfRange(BigInt(1) << 65))
    assertEquals(
      ByronRules.windowThreshold(Double.PositiveInfinity, 5),
      outOfRange(BigInt(1) << 1024)
    )
    assertEquals(ByronRules.windowThreshold(Double.NaN, 5), outOfRange(-(BigInt(3) << 1023)))
    assertEquals(ByronRules.windowThreshold(-0.5, 3), outOfRange(-2))
  }

  test("a threshold out of range is no verdict on the block, never a refusal") {
    val error = VocabError.OutOfRange(VocabError.Kind.BlockCount, BigInt(1) << 64)
    assertEquals(
      BlockFailure.Byron(ByronChainError.StateOutOfRange(error)).stateOutOfRange,
      Some(error)
    )
    assertEquals(
      BlockFailure
        .Byron(
          ByronChainError.SignatureThreshold(xyz.kd5ujc.scarab.codec.Bytes(Vector.empty), 3, 4, 2)
        )
        .stateOutOfRange,
      None
    )
  }
