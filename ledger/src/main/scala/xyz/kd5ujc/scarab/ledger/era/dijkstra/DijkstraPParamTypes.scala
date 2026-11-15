// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.dijkstra

import xyz.kd5ujc.scarab.ledger.Unsigned
import xyz.kd5ujc.scarab.ledger.Unsigned.Width.W32
import xyz.kd5ujc.scarab.ledger.era.NonNegativeInterval

/** `Milliseconds32` (`newtype Milliseconds32 = Milliseconds32 {unMilliseconds32 :: Word32}`,
  * cardano-ledger 9df9f98c `libs/cardano-ledger-core/src/Cardano/Ledger/BaseTypes.hs:704-708`): a
  * duration in milliseconds, at most about 49.7 days.
  */
final case class Milliseconds32(unMilliseconds32: Unsigned[W32])

object Milliseconds32:
  /** `Milliseconds32 0`. */
  val zero: Milliseconds32 = Milliseconds32(Unsigned.lit[W32](0))

/** `SlotInterval` (`newtype SlotInterval = SlotInterval {unSlotInterval :: Word32}`, cardano-base
  * c1e1ed8d `cardano-slotting/src/Cardano/Slotting/Slot.hs:56-61`): a number of slots.
  */
final case class SlotInterval(unSlotInterval: Unsigned[W32])

object SlotInterval:
  /** `SlotInterval 0`. */
  val zero: SlotInterval = SlotInterval(Unsigned.lit[W32](0))

/** `MaxPledgeLeverage` (`newtype MaxPledgeLeverage = MaxPledgeLeverage {unMaxPledgeLeverage ::
  * StrictMaybe NonNegativeInterval}`, cardano-ledger 9df9f98c `Core/PParams.hs:369-379`): the most
  * a pool's stake may be over its pledge, or no limit.
  */
final case class MaxPledgeLeverage(unMaxPledgeLeverage: Option[NonNegativeInterval])

object MaxPledgeLeverage:
  /** `MaxPledgeLeverage SNothing`: no limit. */
  val none: MaxPledgeLeverage = MaxPledgeLeverage(None)
