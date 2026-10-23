// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.codec.DomainError
import xyz.kd5ujc.scarab.codec.{Cbor, Value}
import xyz.kd5ujc.scarab.ledger.byron.{ByronVersion, DelegationError}

/** Failures while reading ledger values. A [[DecodeSite]] identifies the place; cases identify
  * failures.
  */
enum LedgerDecodeError extends DomainError:
  case Protocol(cause: xyz.kd5ujc.scarab.protocol.ProtocolError)
  case Transaction(id: xyz.kd5ujc.scarab.codec.Bytes, cause: LedgerDecodeError)
  case Syntax(cause: Cbor.Error)
  case Expected(site: DecodeSite, expected: LedgerDecodeError.Shape, actual: Value)
  case ExpectedTag(site: DecodeSite, expected: BigInt, actual: Value)
  case ByteLength(site: DecodeSite, expected: Int, actual: Int)
  case ArrayLength(site: DecodeSite, expected: Int, actual: Int)
  case Empty(site: DecodeSite)
  case MissingField(site: DecodeSite, field: DecodeSite)
  case UnknownFields(site: DecodeSite, fields: Set[Int])
  case DuplicateKey(site: DecodeSite)
  case UnsupportedCostModelLanguage(language: Int)
  case CostModelLength(language: Int, expected: Int, actual: Int)
  case DuplicateElement(site: DecodeSite)
  case Zero(site: DecodeSite)
  case IndefiniteLength(site: DecodeSite)
  case ZeroDenominator(site: DecodeSite)
  case RationalOutOfBounds(site: DecodeSite, numerator: BigInt, denominator: BigInt)
  case UnknownTag(site: DecodeSite, tag: BigInt)
  case TaggedArity(site: DecodeSite, tag: BigInt, actual: Int)
  case AtMostOne(site: DecodeSite, actual: Int)
  case NumberOutOfRange(site: DecodeSite, value: BigInt, minimum: BigInt, maximum: BigInt)
  case AddressLength(site: DecodeSite, expected: Int, actual: Int)
  case AddressHeader(actual: Int)
  case RewardAccountHeader(actual: Int)
  case Pointer(cause: Ptr.Failure)
  case AssetNameLength(site: DecodeSite, actual: Int)
  case ZeroQuantity(site: DecodeSite)
  case MultiAssetNotAllowed(site: DecodeSite)
  case MultiAssetTooBig(site: DecodeSite, assets: Int, policies: Int)
  case NativeScript(cause: xyz.kd5ujc.scarab.ledger.NativeScript.DecodeError)
  case Voting(cause: VotingProcedures.DecodeError)
  case Version(cause: ByronVersion.Error)
  case Delegation(cause: DelegationError)
  case CrcMismatch(expected: BigInt, actual: BigInt)
  case EmptyInputsOrOutputs
  case DuplicateGenesisAddress
  case GenesisMissingField(field: DecodeField)
  case GenesisExpectedNumber(field: DecodeField)
  case ProposalEpochMismatch(target: BigInt, epoch: BigInt, beforePointOfNoReturn: Boolean)

  case ByronLayout
  case ByronBlockUnexpected
  case BodyWitnessCount(bodies: Int, witnesses: Int)
  case TransactionIndex(actual: Value, bound: Int)
  case LeiosCertificate(cause: xyz.kd5ujc.scarab.crypto.bls.BlsError)

  def message: String = this match
    case Syntax(cause)               => cause.message
    case Protocol(cause)             => cause.message
    case Transaction(id, cause)      => s"tx ${id.hex.take(16)}: ${cause.message}"
    case Expected(site, expected, _) => s"$site: expected $expected"
    case ExpectedTag(site, tag, _)   => s"$site: expected tag $tag"
    case NativeScript(cause)         => cause.message
    case Voting(cause)               => cause.message
    case Version(cause)              => cause.message
    case Delegation(cause)           => cause.message
    case _                           => toString

object LedgerDecodeError:
  enum Shape:
    case Unsigned, Integer, Bytes, Text, Boolean, Array, Map, ArrayOrMap, Metadatum
