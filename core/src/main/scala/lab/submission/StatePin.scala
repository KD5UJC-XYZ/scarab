// SPDX-License-Identifier: Apache-2.0
package lab.submission

import lab.cbor.Bytes
import lab.header.PraosCertificateState.Point

/** Immutable shape-checked provenance value, not proof of state authority or owner possession.
  * Equality compares every field; generation and owner lifecycle are enforced by the owner.
  */
final class StatePin private (
    val ownerId: Bytes,
    val generation: BigInt,
    val point: Point,
    val coherentStateId: Bytes,
    val ledgerStateId: Bytes,
    val environmentId: Bytes,
    val validationSlot: BigInt,
    val profileId: String
):
  override def equals(other: Any): Boolean = other match
    case that: StatePin =>
      ownerId == that.ownerId && generation == that.generation && point == that.point &&
      coherentStateId == that.coherentStateId && ledgerStateId == that.ledgerStateId &&
      environmentId == that.environmentId && validationSlot == that.validationSlot &&
      profileId == that.profileId
    case _ => false
  override def hashCode(): Int =
    (
      ownerId,
      generation,
      point,
      coherentStateId,
      ledgerStateId,
      environmentId,
      validationSlot,
      profileId
    ).hashCode()

object StatePin:
  val Profile = AdmissionProfile.AdaVkey.id
  val MaxUInt64: BigInt = (BigInt(1) << 64) - 1
  private def hash(value: Bytes): Boolean = value != null && value.value != null && value.size == 32
  private def uint64(value: BigInt): Boolean = value != null && value >= 0 && value <= MaxUInt64

  def checked(
      ownerId: Bytes,
      generation: BigInt,
      point: Point,
      coherentStateId: Bytes,
      ledgerStateId: Bytes,
      environmentId: Bytes,
      validationSlot: BigInt,
      profileId: String
  ): Either[String, StatePin] =
    if !hash(ownerId) || !hash(coherentStateId) || !hash(ledgerStateId) || !hash(environmentId) then
      Left("32-byte owner, coherent state, ledger state and environment identities required")
    else if point == null || !hash(point.hash) || !uint64(point.slot) || !uint64(point.blockNo) then
      Left("full point requires 32-byte hash and uint64 slot/block number")
    else if !uint64(generation) || !uint64(validationSlot) then
      Left("uint64 generation and validation slot required")
    else if AdmissionProfile.fromId(profileId).isEmpty then Left("unsupported state pin profile")
    else
      Right(
        new StatePin(
          ownerId,
          generation,
          point,
          coherentStateId,
          ledgerStateId,
          environmentId,
          validationSlot,
          profileId
        )
      )
