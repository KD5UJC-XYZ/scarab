// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.codec.{Cbor, Value}
import xyz.kd5ujc.scarab.ledger.byron.{ByronVersion, DelegationError}

/** Failures while reading ledger values. Context names identify fields; cases identify failures. */
enum LedgerDecodeError:
  case Protocol(cause: xyz.kd5ujc.scarab.protocol.ProtocolError)
  case Syntax(cause: Cbor.Error)
  case Expected(context: String, expected: LedgerDecodeError.Shape, actual: Value)
  case ExpectedTag(context: String, expected: BigInt, actual: Value)
  case ByteLength(context: String, expected: Int, actual: Int)
  case ArrayLength(context: String, expected: Int, actual: Int)
  case Empty(context: String)
  case MissingField(context: String, field: String)
  case UnknownFields(context: String, fields: Set[Int])
  case DuplicateKey(context: String)
  case ZeroDenominator(context: String)
  case UnknownTag(context: String, tag: BigInt)
  case TaggedArity(context: String, tag: BigInt, actual: Int)
  case AtMostOne(context: String, actual: Int)
  case NumberOutOfRange(context: String, value: BigInt, minimum: BigInt, maximum: BigInt)
  case AddressLength(context: String, expected: Int, actual: Int)
  case AddressHeader(actual: Int)
  case RewardAccountHeader(actual: Int)
  case Pointer(cause: Ptr.Failure)
  case AssetNameLength(context: String, actual: Int)
  case NativeScript(cause: xyz.kd5ujc.scarab.ledger.NativeScript.DecodeError)
  case Voting(cause: VotingProcedures.DecodeError)
  case Version(cause: ByronVersion.Error)
  case Delegation(cause: DelegationError)
  case CrcMismatch(expected: BigInt, actual: BigInt)
  case EmptyInputsOrOutputs
  case DuplicateGenesisAddress
  case GenesisMissingField(field: String)
  case GenesisExpectedNumber(field: String)
  case ProposalEpochMismatch(target: BigInt, epoch: BigInt, beforePointOfNoReturn: Boolean)

  case ByronLayout
  case ByronBlockUnexpected
  case BodyWitnessCount(bodies: Int, witnesses: Int)
  case TransactionIndex(actual: Value, bound: Int)
  case LeiosCertificate(cause: xyz.kd5ujc.scarab.crypto.bls.BlsError)

  def message: String = toString

object LedgerDecodeError:
  enum Shape:
    case Unsigned, Integer, Bytes, Text, Boolean, Array, Map, ArrayOrMap
