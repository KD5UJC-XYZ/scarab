// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import java.time.Instant

/** The ledger's system start: `systemStart` of `Globals`, a `SystemStart` (cardano-base c1e1ed8d
  * `cardano-slotting/src/Cardano/Slotting/Time.hs:61`), which `mkShelleyGlobals` takes from the
  * Shelley genesis's `sgSystemStart` (cardano-ledger 9df9f98c `Shelley/Genesis.hs:768-789`). The
  * Plutus script contexts' times are counted from it.
  *
  * It is not the hard-fork combinator's system start, the first era's (`getSameConfigValue
  * getSystemStart`, ouroboros-consensus 2416493b `HardFork/Combinator/Node.hs:38`, Byron's
  * `gdStartTime`, `Byron/Node.hs:296-298`), from which the slot clock and `GetSystemStart` count:
  * that one is [[EpochGeometry.systemStart]]. The two need not agree where a network was not
  * started with both genesis files written from one time.
  *
  * @param utc
  *   the time, to the nanosecond the genesis gives it to.
  */
final case class SystemStart(utc: Instant):

  /** `slotToPOSIXTime` (cardano-ledger 9df9f98c
    * `libs/cardano-ledger-core/src/Cardano/Ledger/Plutus/ TxInfo.hs:165-172`) of a slot
    * `relativePs` picoseconds after this start: the time of the slot (`fromRelativeTime start`,
    * cardano-base c1e1ed8d `EpochInfo/API.hs:88-95`) in POSIX milliseconds, `truncate . (* 1000)`
    * of the sum. The truncation is of the sum, toward zero, not of the start, so a start with a
    * fraction of a millisecond adds it to every slot's offset before the time is cut.
    */
  def posixMillis(relativePs: BigInt): BigInt = (picos + relativePs) / SystemStart.PsPerMilli

  private def picos: BigInt =
    BigInt(utc.getEpochSecond) * SystemStart.PsPerSecond + BigInt(utc.getNano) * 1000

object SystemStart:
  private val PsPerSecond = BigInt(1000000000000L)
  private val PsPerMilli = BigInt(1000000000L)
