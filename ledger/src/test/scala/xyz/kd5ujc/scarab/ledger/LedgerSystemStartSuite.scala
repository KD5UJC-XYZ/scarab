// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import java.time.Instant
import munit.FunSuite

/** `slotToPOSIXTime` (cardano-ledger 9df9f98c `Plutus/TxInfo.hs:165-172`): `truncate . (* 1000)` of
  * the system start plus the slot's offset. The truncation is of the sum, toward zero (#1337).
  */
class LedgerSystemStartSuite extends FunSuite:
  private val PsPerMilli = BigInt(1000000000)

  test("a start with a fraction of a millisecond is cut with the offset added, not before") {
    val start = SystemStart(Instant.parse("2026-10-12T00:31:08.240586427Z"))
    val millis = BigInt(Instant.parse("2026-10-12T00:31:08Z").toEpochMilli)
    assertEquals(start.posixMillis(0), millis + 240)
    assertEquals(start.posixMillis(1000 * PsPerMilli), millis + 1240)
    // an offset of a fraction of a millisecond reaches the next one with the start's fraction
    assertEquals(start.posixMillis(BigInt(500000000)), millis + 241)
  }

  test("a time before 1970 is cut toward zero, as Haskell's truncate cuts it") {
    // -0.2405 s: the sum is cut toward zero, not floored
    val start = SystemStart(Instant.ofEpochSecond(-1, 759500000))
    assertEquals(start.posixMillis(0), BigInt(-240))
    // past zero, the same start and an offset of a second give 759.5 ms, cut to 759
    assertEquals(start.posixMillis(1000 * PsPerMilli), BigInt(759))
  }

  test("a whole-second start gives the start's milliseconds plus the offset") {
    val start = SystemStart(Instant.ofEpochSecond(1666656000))
    assertEquals(start.posixMillis(20 * PsPerMilli), BigInt(1666656000000L) + 20)
    assertEquals(
      PosixClock.total(start, TestGeometry(start.utc, 1000, 100, 5, "0.05")).posix(7),
      Right(BigInt(1666656000000L) + 7000)
    )
  }
