// SPDX-License-Identifier: Apache-2.0
package lab.node

import java.time.Instant

/** Slot/epoch arithmetic for a single-era (Shelley-based from genesis) chain. Every value comes
  * from the genesis configuration; nothing here assumes a particular network.
  */
final case class EpochGeometry(
    systemStart: Instant,
    slotLengthMillis: Long,
    epochLength: Long,
    securityParam: Int,
    activeSlotsCoeff: BigDecimal
):
  def epochOf(slot: BigInt): BigInt = slot / epochLength
  def firstSlot(epoch: BigInt): BigInt = epoch * epochLength
  def slotStart(slot: BigInt): Instant =
    systemStart.plusMillis((slot * slotLengthMillis).toLong)
  def slotAt(instant: Instant): BigInt =
    val elapsed = instant.toEpochMilli - systemStart.toEpochMilli
    if elapsed <= 0 then 0 else BigInt(elapsed / slotLengthMillis)

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
