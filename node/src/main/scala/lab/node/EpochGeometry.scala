// SPDX-License-Identifier: Apache-2.0
package lab.node

import java.time.Instant

/** The Byron prefix of the era history: `epochs` Byron epochs of `epochSlots` slots, each slot
  * `slotLengthMillis` long, before the Shelley-based eras begin. A chain that starts in Shelley
  * (Preview) has no Byron prefix.
  */
final case class ByronSpan(epochs: BigInt, epochSlots: BigInt, slotLengthMillis: Long):
  def slots: BigInt = epochs * epochSlots

object ByronSpan:
  val Absent: ByronSpan = ByronSpan(0, 1, 1)

/** The era history: slot/epoch arithmetic across the Byron prefix and the Shelley-based eras
  * (Shelley..Conway share one epoch length and slot length, only the protocol changes at their
  * boundaries). Slots and epochs are absolute from the system start: the first Shelley epoch is
  * `byron.epochs` and starts at slot `byron.epochs * byron.epochSlots`. Every value comes from the
  * genesis files and the node configuration; nothing here assumes a particular network.
  */
final case class EpochGeometry(
    systemStart: Instant,
    slotLengthMillis: Long,
    epochLength: Long,
    securityParam: Int,
    activeSlotsCoeff: BigDecimal,
    byron: ByronSpan = ByronSpan.Absent
):
  /** First epoch (and absolute slot) of the Shelley-based eras. */
  def firstShelleyEpoch: BigInt = byron.epochs
  def firstShelleySlot: BigInt = byron.slots

  def isByronSlot(slot: BigInt): Boolean = slot < byron.slots

  def epochOf(slot: BigInt): BigInt =
    if slot < byron.slots then slot / byron.epochSlots
    else byron.epochs + (slot - byron.slots) / epochLength

  def firstSlot(epoch: BigInt): BigInt =
    if epoch < byron.epochs then epoch * byron.epochSlots
    else byron.slots + (epoch - byron.epochs) * epochLength

  def epochSlots(epoch: BigInt): BigInt =
    if epoch < byron.epochs then byron.epochSlots else BigInt(epochLength)

  def slotStart(slot: BigInt): Instant =
    val millis =
      if slot < byron.slots then slot * byron.slotLengthMillis
      else byron.slots * byron.slotLengthMillis + (slot - byron.slots) * slotLengthMillis
    systemStart.plusMillis(millis.toLong)

  def slotAt(instant: Instant): BigInt =
    val elapsed = instant.toEpochMilli - systemStart.toEpochMilli
    val byronMillis = byron.slots * byron.slotLengthMillis
    if elapsed <= 0 then 0
    else if BigInt(elapsed) < byronMillis then BigInt(elapsed) / byron.slotLengthMillis
    else byron.slots + (BigInt(elapsed) - byronMillis) / slotLengthMillis

  /** 3k/f: slots after which the chain is stable (Shelley stability window). */
  def stabilityWindow: BigInt = ceilDiv(3 * securityParam)

  /** 4k/f: randomness stabilisation window used by Praos nonce freezing. */
  def randomnessStabilisationWindow: BigInt = ceilDiv(4 * securityParam)

  private def ceilDiv(numerator: Int): BigInt =
    val exact = BigDecimal(numerator) / activeSlotsCoeff
    exact.setScale(0, BigDecimal.RoundingMode.CEILING).toBigInt

object EpochGeometry:
  def checked(
      systemStart: Instant,
      slotLengthMillis: Long,
      epochLength: Long,
      securityParam: Int,
      activeSlotsCoeff: BigDecimal
  ): Either[ConfigError, EpochGeometry] =
    val g =
      EpochGeometry(systemStart, slotLengthMillis, epochLength, securityParam, activeSlotsCoeff)
    if slotLengthMillis <= 0 then Left(ConfigError("slot length must be positive"))
    else if epochLength <= 0 then Left(ConfigError("epoch length must be positive"))
    else if securityParam <= 0 then Left(ConfigError("security parameter must be positive"))
    else if activeSlotsCoeff <= 0 || activeSlotsCoeff > 1 then
      Left(ConfigError("active slot coefficient must be in (0, 1]"))
    else if g.randomnessStabilisationWindow >= epochLength then
      Left(ConfigError("epoch shorter than the randomness stabilisation window (4k/f)"))
    else Right(g)
