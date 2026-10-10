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

  enum ConstructionError:
    case InvalidIdentity, InvalidPoint, InvalidQuantity, UnsupportedProfile
    def message: String = this match
      case InvalidIdentity =>
        "32-byte owner, coherent state, ledger state and environment identities required"
      case InvalidPoint       => "full point requires 32-byte hash and uint64 slot/block number"
      case InvalidQuantity    => "uint64 generation and validation slot required"
      case UnsupportedProfile => "unsupported state pin profile"

  /** Compatibility ingestion boundary; preserves historical validation order and error text. */
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
    construct(
      ownerId,
      generation,
      point,
      coherentStateId,
      ledgerStateId,
      environmentId,
      validationSlot,
      profileId
    ).left.map(_.message)

  /** New role-safe assembly boundary. Result fields and wire representations remain unchanged. */
  def checkedTyped(
      ownerId: PinDomain.OwnerId,
      generation: PinDomain.Generation,
      point: Point,
      coherentStateId: PinDomain.CoherentStateId,
      ledgerStateId: PinDomain.LedgerStateId,
      environmentId: PinDomain.EnvironmentId,
      validationSlot: PinDomain.ValidationSlot,
      profile: AdmissionProfile
  ): Either[ConstructionError, StatePin] =
    import PinDomain.*
    construct(
      ownerId.bytes,
      generation.value,
      point,
      coherentStateId.bytes,
      ledgerStateId.bytes,
      environmentId.bytes,
      validationSlot.value,
      if profile == null then null else profile.id
    )

  private def construct(
      ownerId: Bytes,
      generation: BigInt,
      point: Point,
      coherentStateId: Bytes,
      ledgerStateId: Bytes,
      environmentId: Bytes,
      validationSlot: BigInt,
      profileId: String
  ): Either[ConstructionError, StatePin] =
    if !hash(ownerId) || !hash(coherentStateId) || !hash(ledgerStateId) || !hash(environmentId) then
      Left(ConstructionError.InvalidIdentity)
    else if point == null || !hash(point.hash) || !uint64(point.slot) || !uint64(point.blockNo) then
      Left(ConstructionError.InvalidPoint)
    else if !uint64(generation) || !uint64(validationSlot) then
      Left(ConstructionError.InvalidQuantity)
    else if AdmissionProfile.fromId(profileId).isEmpty then
      Left(ConstructionError.UnsupportedProfile)
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
