// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.codec.DomainError
import io.circe.{DecodingFailure, ParsingFailure}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.vrf.PraosLeaderThreshold as Leader
import xyz.kd5ujc.scarab.protocol.Era

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
  case GenesisAddressEncoding(cause: xyz.kd5ujc.scarab.codec.Cbor.Error)

  /** A crypto provider failed while the configuration was checked (a Byron genesis delegation
    * certificate): there is no verdict on the genesis, which is not a fault of the genesis.
    */
  case GenesisUnverifiable(cause: ProviderDefect)
  case Credential(value: String)
  case DRep(value: String)
  case GenesisCostModel(cause: CostModelProblem)
  case NonPositive(context: String, value: BigDecimal)
  case Negative(context: String, value: BigDecimal)

  /** A genesis number the reference's `FromJSON` instance refuses. */
  case GenesisNumber(field: GenesisNumberField, cause: json.NumberError)

  /** A configuration number the reference's `FromJSON` instance refuses. */
  case ConfigNumber(field: ConfigNumberField, cause: json.NumberError)

  /** A field of a genesis file that the reference's parser refuses ([[GenesisSchema]]). */
  case GenesisField(file: GenesisFile, path: Vector[String], cause: GenesisFieldError)

  /** A security parameter the chain store cannot count in an `Int`, though the reference's
    * `NonZero Word64` holds it. An approved deviation, recorded in #474.
    */
  case SecurityParamBeyondStorage(k: BigInt)

  case ExpectedObject(context: String, actual: io.circe.Json)
  case Selection(context: String, value: String, allowed: Set[String])
  case ConflictingFields(fields: Set[String])
  case RenamedField(previous: String, current: String)
  case MempoolCapacity(actual: io.circe.Json)
  case PeerTargets[E <: DomainError](cause: E)
  case PeerAddress[E <: DomainError](cause: E)
  case PeerSnapshot[E <: DomainError](cause: E)
  case StateMirror[E <: DomainError](cause: E)
  case GenesisStart(cause: rules.GenesisStartError)
  case Ancillary[E <: DomainError](cause: E)
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
  case BootstrapPeersInGenesisMode
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
    case ActiveSlotsCoefficient(e)   => e.message
    case GenesisNumber(field, cause) => s"genesis ${field.key}: ${cause.message}"
    case ConfigNumber(field, cause)  => s"configuration ${field.key}: ${cause.message}"
    case GenesisField(file, path, cause) =>
      s"${file.label} genesis ${path.mkString(".")}: ${cause.message}"
    case SecurityParamBeyondStorage(k) =>
      s"genesis securityParam $k: the chain store counts at most ${Int.MaxValue} blocks"
    case JsonSyntax(context, cause)       => s"$context is not JSON: ${cause.message}"
    case JsonField(context, field, cause) => s"$context $field: ${cause.message}"
    case MissingField(context, field)     => s"$context: missing required field $field"
    case FileMissing(role, file)          => s"missing $role file $file"
    case FileRead(file, cause)            => s"cannot read $file: ${cause.getMessage}"
    case UnknownFlag(flag)                => s"unknown flag $flag"
    case DuplicateFlag(flag)              => s"duplicate flag $flag"
    case MissingFlagValue(flag)           => s"flag $flag needs a value"
    case UnexpectedArgument(value)        => s"unexpected argument $value"
    case MissingFlag(flag)                => s"$flag is required"
    case RemovedFlag(flag)                => s"--$flag was removed"
    case FlagValue(flag, value)           => s"--${flag.stripPrefix("--")}: cannot use $value"
    case FlagDependency(flags, required) =>
      s"${flags.toVector.sorted.mkString(", ")} need --$required"
    case LogSink(value)      => s"no sink $value (stdout, stderr, file)"
    case LogNamespace(value) => s"no namespace $value"
    case ConflictingFields(fields) =>
      s"specify one of ${fields.toVector.sorted.mkString(", ")}, not both"
    case NoPeers => "no peer to follow: the topology names no local root, bootstrap or public root"
    case BootstrapPeersInGenesisMode =>
      "topology bootstrapPeers cannot be configured with ConsensusMode GenesisMode"
    case GenesisUnverifiable(cause) => s"no verdict on the genesis: ${cause.message}"
    case GenesisCostModel(cause)    => s"genesis cost models: ${cause.message}"
    case WithUsage(cause, usage)    => s"${cause.message}\n$usage"
    case _                          => toString

/** A genesis file of the Shelley-based eras. */
enum GenesisFile:
  case Shelley, Alonzo, Conway

  def label: String = this match
    case Shelley => "Shelley"
    case Alonzo  => "Alonzo"
    case Conway  => "Conway"

/** Why the reference's genesis parser refuses a field ([[GenesisSchema]]). */
enum GenesisFieldError:
  /** `.:` of an absent key. */
  case Missing

  /** A JSON value of another type than the instance reads (`typeMismatch`). */
  case Kind(expected: json.JsonKind, found: json.JsonKind)

  /** A number its instance refuses. */
  case Number(cause: json.NumberError)

  /** A text outside the values the instance knows (`Network`, the `Nonce` tag). */
  case Choice(value: String, allowed: Vector[String])

  /** Hexadecimal text of the wrong length or alphabet (a hash). */
  case Hash(expectedBytes: Int, value: String)

  /** A value a reader of its own refuses: an address ([[GenesisState.address]]) or a credential
    * ([[GenesisState.credential]]).
    */
  case Nested(cause: ConfigProblem)

  def message: String = this match
    case Missing                => "key not found"
    case Kind(expected, found)  => s"expected $expected, but encountered $found"
    case Number(cause)          => cause.message
    case Choice(value, allowed) => s"$value is not one of ${allowed.mkString(", ")}"
    case Hash(bytes, value)     => s"expected $bytes bytes of hexadecimal, got $value"
    case Nested(cause)          => cause.message

/** A configuration field read with a number instance of the reference: the hard-fork triggers of
  * the test networks, `Maybe EpochNo` and `Maybe Word` (cardano-node 11.1.3
  * `cardano-node/src/Cardano/Node/Types.hs:301-376`, read by `.:?` in
  * `Configuration/POM.hs:670-690`).
  */
enum ConfigNumberField:
  case HardForkAtEpoch(era: Era)
  case HardForkAtVersion(era: Era)

  /** The JSON key. */
  def key: String = this match
    case HardForkAtEpoch(era)   => s"Test${era}HardForkAtEpoch"
    case HardForkAtVersion(era) => s"Test${era}HardForkAtVersion"

/** A Shelley genesis field read with a number instance of the reference. */
enum GenesisNumberField:
  case ActiveSlotsCoeff, SecurityParam

  /** The JSON key. */
  def key: String = this match
    case ActiveSlotsCoeff => "activeSlotsCoeff"
    case SecurityParam    => "securityParam"
