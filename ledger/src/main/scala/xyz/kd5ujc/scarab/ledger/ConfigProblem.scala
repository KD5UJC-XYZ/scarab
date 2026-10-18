// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import io.circe.{DecodingFailure, ParsingFailure}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.vrf.PraosLeaderThreshold as Leader

/** Configuration failures retain parsed inputs and source causes; text is rendered at presentation.
  */
enum ConfigProblem:
  case ActiveSlotsCoefficient(cause: Leader.Error)
  case FileMissing(role: String, file: String)
  case GenesisHashMismatch(role: String, file: String, expected: String, actual: String)
  case UnsupportedTopology
  case RemovedFlag(flag: String)
  case JsonSyntax(context: String, cause: ParsingFailure)
  case JsonField(context: String, field: String, cause: DecodingFailure)
  case MissingField(context: String, field: String)
  case InvalidTimestamp(value: String, cause: Throwable)
  case FractionalSlotMilliseconds(value: BigDecimal)
  case ByronGenesisRequired
  case IntegerText(context: String, value: String)
  case Base64(context: String, value: String, cause: Throwable)
  case Base58Address(value: String)
  case Hex(cause: Bytes.HexError)
  case ByteLength(context: String, expected: Int, actual: Int)
  case LedgerValue(cause: LedgerDecodeError)
  case Credential(value: String)
  case DRep(value: String)
  case DuplicateInjection(name: String)
  case CostModelLanguage(value: String)
  case NonPositive(context: String, value: BigDecimal)
  case ActiveSlotRange(value: BigDecimal)
  case EpochTooShort(epochLength: Long, randomnessWindow: BigInt)

  case ExpectedObject(context: String, actual: io.circe.Json)
  case Selection(context: String, value: String, allowed: Set[String])
  case ConflictingFields(fields: Set[String])
  case RenamedField(previous: String, current: String)
  case MempoolCapacity(actual: io.circe.Json)
  case PeerTargets[E <: Product](cause: E)
  case PeerAddress[E <: Product](cause: E)
  case PeerSnapshot[E <: Product](cause: E)
  case StateMirror[E <: Product](cause: E)
  case Ancillary[E <: Product](cause: E)
  case FileRead(file: String, cause: Throwable)
  case InvalidPath(value: String, cause: Throwable)
  case MissingGenesis
  case MissingGenesisParameters
  case MissingChunkSize
  case NoPeers
  case TopologyPort(actual: io.circe.Json)
  case TopologyHost(value: String)
  case UnspecifiedDestination(value: String)
  case SrvAccessPoint(value: String)
  case UntrustedBootstrap
  case MissingValency
  case UnknownFlag(flag: String)
  case DuplicateFlag(flag: String)
  case MissingFlagValue(flag: String)
  case UnexpectedArgument(value: String)
  case MissingFlag(flag: String)
  case RequiredOneOf(flags: Set[String])
  case FlagValue(flag: String, value: String)
  case FlagDependency(flags: Set[String], required: String)
  case LogSink(value: String)
  case LogNamespace(value: String)
  case Usage(command: String)
  case WithUsage(cause: ConfigProblem, usage: String)
  case AncillaryVerificationRequired
  case AncillaryKeyRequired

  def message: String = this match
    case ActiveSlotsCoefficient(e) => e.message
    case JsonSyntax(context, cause) => s"$context is not JSON: ${cause.message}"
    case JsonField(context, field, cause) => s"$context $field: ${cause.message}"
    case MissingField(context, field) => s"$context: missing required field $field"
    case FileMissing(role, file) => s"missing $role file $file"
    case FileRead(file, cause) => s"cannot read $file: ${cause.getMessage}"
    case UnknownFlag(flag) => s"unknown flag $flag"
    case DuplicateFlag(flag) => s"duplicate flag $flag"
    case MissingFlagValue(flag) => s"flag $flag needs a value"
    case UnexpectedArgument(value) => s"unexpected argument $value"
    case MissingFlag(flag) => s"$flag is required"
    case RemovedFlag(flag) => s"--$flag was removed"
    case FlagValue(flag, value) => s"--${flag.stripPrefix("--")}: cannot use $value"
    case FlagDependency(flags, required) => s"${flags.toVector.sorted.mkString(", ")} need --$required"
    case LogSink(value) => s"no sink $value (stdout, stderr, file)"
    case LogNamespace(value) => s"no namespace $value"
    case ConflictingFields(fields) => s"specify one of ${fields.toVector.sorted.mkString(", ")}, not both"
    case NoPeers => "no peer to follow: the topology names no local root, bootstrap or public root"
    case WithUsage(cause, usage) => s"${cause.message}\n$usage"
    case _ => toString
