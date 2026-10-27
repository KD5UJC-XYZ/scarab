// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import java.time.Instant
import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.ledger.state.{NonZero, PositiveUnitInterval, Word64}

/** The Byron prefix of the era history: `epochs` Byron epochs of `epochSlots` slots, each slot
  * `slotLengthMillis` long, before the Shelley-based eras begin. A chain that starts in Shelley
  * (Preview) has no Byron prefix. On a public network the number of Byron epochs is not configured:
  * the Byron ledger's update state decides it, so until the chain has entered Shelley the span is
  * `derived` (open-ended: every slot is a Byron slot) and `resolved` replaces it.
  */
final case class ByronSpan(
    epochs: BigInt,
    epochSlots: BigInt,
    slotLengthMillis: Long,
    derived: Boolean = false
):
  def slots: BigInt = epochs * epochSlots

object ByronSpan:
  val Absent: ByronSpan = ByronSpan(0, 1, 1)

  /** Placeholder epoch count of a span whose end is not yet known. */
  private val Open: BigInt = 1000000

  def derived(epochSlots: BigInt, slotLengthMillis: Long): ByronSpan =
    ByronSpan(Open, epochSlots, slotLengthMillis, derived = true)

/** The era history: slot/epoch arithmetic across the Byron prefix and the Shelley-based eras
  * (Shelley..Conway share one epoch length and slot length, only the protocol changes at their
  * boundaries). Slots and epochs are absolute from the system start: the first Shelley epoch is
  * `byron.epochs` and starts at slot `byron.epochs * byron.epochSlots`. Every value comes from the
  * genesis files and the node configuration; nothing here assumes a particular network.
  *
  * @param securityParam
  *   `sgSecurityParam :: NonZero Word64` (cardano-ledger f649f975,
  *   `eras/shelley/impl/src/Cardano/Ledger/Shelley/Genesis.hs:312`).
  * @param activeSlotsCoeff
  *   `sgActiveSlotsCoeff :: PositiveUnitInterval` (same file, 311).
  */
final case class EpochGeometry(
    systemStart: Instant,
    slotLengthMillis: Long,
    epochLength: Long,
    securityParam: NonZero[Word64],
    activeSlotsCoeff: PositiveUnitInterval,
    byron: ByronSpan = ByronSpan.Absent
):
  /** The same history once the chain is known to enter Shelley at `firstShelleyEpoch`. */
  def resolved(firstShelleyEpoch: BigInt): EpochGeometry =
    if byron.derived then copy(byron = byron.copy(epochs = firstShelleyEpoch, derived = false))
    else this

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

  /** Milliseconds from the system start to the onset of `slot`. Exact for any slot number, so a
    * slot taken from a peer's header cannot overflow it (unlike [[slotStart]], which is for slots
    * of the node's own chain and clock).
    */
  def slotOffsetMillis(slot: BigInt): BigInt =
    if slot < byron.slots then slot * byron.slotLengthMillis
    else byron.slots * byron.slotLengthMillis + (slot - byron.slots) * slotLengthMillis

  def slotStart(slot: BigInt): Instant =
    systemStart.plusMillis(slotOffsetMillis(slot).toLong)

  def slotAt(instant: Instant): BigInt =
    val elapsed = instant.toEpochMilli - systemStart.toEpochMilli
    val byronMillis = byron.slots * byron.slotLengthMillis
    if elapsed <= 0 then 0
    else if BigInt(elapsed) < byronMillis then BigInt(elapsed) / byron.slotLengthMillis
    else byron.slots + (BigInt(elapsed) - byronMillis) / slotLengthMillis

  /** 3k/f: slots after which the chain is stable, as `computeStabilityWindow` (cardano-ledger
    * f649f975, `eras/shelley/impl/src/Cardano/Ledger/Shelley/StabilityWindow.hs:14-21`).
    */
  lazy val stabilityWindow: Word64 = windowOf(3)

  /** 4k/f: randomness stabilisation window used by Praos nonce freezing, as
    * `computeRandomnessStabilisationWindow` (same file, 28-35).
    */
  lazy val randomnessStabilisationWindow: Word64 = windowOf(4)

  /** The security parameter as a number, for the callers' slot arithmetic. */
  def k: BigInt = securityParam.value.toBigInt

  // `ceiling $ (n * fromIntegral k) /. f`: the product and the quotient are exact `Rational`s, and
  // `ceiling` into `Word64` goes through `properFraction`, whose `fromInteger` keeps the low 64 bits.
  private def windowOf(factor: Int): Word64 =
    Word64.fromUnsigned((Rational(factor * k) / activeSlotsCoeff.rational).ceil.toLong)

object EpochGeometry:
  /** The geometry of the Shelley genesis values. `securityParam` and `activeSlotsCoeff` are bounded
    * by their types as the reference's `FromJSON` reads them. The three checks here are not the
    * reference's. A slot length and an epoch length that are not positive are refused: an approved
    * deviation, recorded in #474 (the reference accepts 0, and the era arithmetic here divides by
    * both). An epoch no longer than 4k/f is refused (`EpochTooShort`), which the reference node
    * does not do (see [[ShelleyGenesisValidation]]); its removal is pending in #474.
    */
  def checked(
      systemStart: Instant,
      slotLengthMillis: Long,
      epochLength: Long,
      securityParam: NonZero[Word64],
      activeSlotsCoeff: PositiveUnitInterval
  ): Either[ConfigError, EpochGeometry] =
    val g =
      EpochGeometry(systemStart, slotLengthMillis, epochLength, securityParam, activeSlotsCoeff)
    if slotLengthMillis <= 0 then
      Left(ConfigError(ConfigProblem.NonPositive("slot length", BigDecimal(slotLengthMillis))))
    else if epochLength <= 0 then
      Left(ConfigError(ConfigProblem.NonPositive("epoch length", BigDecimal(epochLength))))
    else if g.randomnessStabilisationWindow.toBigInt >= epochLength then
      Left(ConfigError(ConfigProblem.EpochTooShort(epochLength, g.randomnessStabilisationWindow)))
    else Right(g)
