// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.codec.Bytes

/** The issue-number rule at the top of the counter's range. Praos checks `m <= n` and `n <= m + 1`
  * in `Word64` (ouroboros-consensus 82ecba32 `Ouroboros/Consensus/Protocol/Praos.hs` lines
  * 646-647), so `m + 1` wraps to 0 when `m` is `2^64 - 1` and no counter is accepted after it.
  * TPraos checks only `m <= n` (cardano-ledger f649f975
  * `libs/cardano-protocol-tpraos/src/Cardano/Protocol/TPraos/Rules/OCert.hs` lines 101-107).
  */
class OCertCounterSuite extends munit.FunSuite:
  private val issuer = Bytes.empty
  private val max = (BigInt(1) << 64) - 1

  private def praos(m: BigInt, n: BigInt) =
    OCertRules.checkCounter(Some(m), issuer, n, known = true, bounded = true)
  private def tpraos(m: BigInt, n: BigInt) =
    OCertRules.checkCounter(Some(m), issuer, n, known = true, bounded = false)

  test("Praos accepts the same counter or the next one, and nothing else") {
    assertEquals(praos(5, 5), Right(()))
    assertEquals(praos(5, 6), Right(()))
    assertEquals(praos(5, 4), Left(OCertError.CounterOutOfRange(5, 4)))
    assertEquals(praos(5, 7), Left(OCertError.CounterOutOfRange(5, 7)))
  }

  test("Praos accepts the largest counter after the one below it") {
    assertEquals(praos(max - 1, max - 1), Right(()))
    assertEquals(praos(max - 1, max), Right(()))
  }

  test("Praos accepts no counter after the largest, as m + 1 wraps to 0 in Word64") {
    assertEquals(praos(max, max), Left(OCertError.CounterOutOfRange(max, max)))
  }

  test("TPraos only requires the counter not to decrease, up to the largest") {
    assertEquals(tpraos(max, max), Right(()))
    assertEquals(tpraos(max - 1, max), Right(()))
    assertEquals(tpraos(max, max - 1), Left(OCertError.CounterTooSmall(max, max - 1)))
  }
