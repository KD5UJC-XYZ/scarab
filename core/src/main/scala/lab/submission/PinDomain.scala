// SPDX-License-Identifier: Apache-2.0
package lab.submission

import cats.kernel.{Eq, Order}
import lab.cbor.Bytes

/** Role-checked data, never owner possession or authorization. No implicit unwrapping conversions.
  * Identity roles deliberately have Eq only; sorting them is not a domain operation.
  */
object PinDomain:
  private val MaxUInt64 = (BigInt(1) << 64) - 1
  enum Role:
    case Owner, CoherentState, LedgerState, Environment, Generation, ValidationSlot
  enum Error:
    case InvalidHash(role: Role)
    case InvalidUInt64(role: Role)
    def message: String = this match
      case InvalidHash(_) =>
        "32-byte owner, coherent state, ledger state and environment identities required"
      case InvalidUInt64(_) => "uint64 generation and validation slot required"
  private def hash(value: Bytes, role: Role): Either[Error, Bytes] =
    if value == null || value.value == null || value.size != 32 then Left(Error.InvalidHash(role))
    else Right(value)
  private def uint64(value: BigInt, role: Role): Either[Error, BigInt] =
    if value == null || value < 0 || value > MaxUInt64 then Left(Error.InvalidUInt64(role))
    else Right(value)

  opaque type OwnerId = Bytes
  object OwnerId:
    def checked(value: Bytes): Either[Error, OwnerId] = hash(value, Role.Owner)
    extension (value: OwnerId) def bytes: Bytes = value
    given Eq[OwnerId] = Eq.instance((left, right) => left == right)

  opaque type CoherentStateId = Bytes
  object CoherentStateId:
    def checked(value: Bytes): Either[Error, CoherentStateId] = hash(value, Role.CoherentState)
    extension (value: CoherentStateId) def bytes: Bytes = value
    given Eq[CoherentStateId] = Eq.instance((left, right) => left == right)

  opaque type LedgerStateId = Bytes
  object LedgerStateId:
    def checked(value: Bytes): Either[Error, LedgerStateId] = hash(value, Role.LedgerState)
    extension (value: LedgerStateId) def bytes: Bytes = value
    given Eq[LedgerStateId] = Eq.instance((left, right) => left == right)

  opaque type EnvironmentId = Bytes
  object EnvironmentId:
    def checked(value: Bytes): Either[Error, EnvironmentId] = hash(value, Role.Environment)
    extension (value: EnvironmentId) def bytes: Bytes = value
    given Eq[EnvironmentId] = Eq.instance((left, right) => left == right)

  opaque type Generation = BigInt
  object Generation:
    def checked(value: BigInt): Either[Error, Generation] = uint64(value, Role.Generation)
    extension (value: Generation) def value: BigInt = value
    given Order[Generation] = Order.from((left, right) => left.compare(right))

  opaque type ValidationSlot = BigInt
  object ValidationSlot:
    def checked(value: BigInt): Either[Error, ValidationSlot] = uint64(value, Role.ValidationSlot)
    extension (value: ValidationSlot) def value: BigInt = value
    given Order[ValidationSlot] = Order.from((left, right) => left.compare(right))
