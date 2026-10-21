// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.blst.BlstProblem
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.codec.DomainError
import xyz.kd5ujc.scarab.crypto.kes.Sum6KesSigning
import xyz.kd5ujc.scarab.crypto.opcert.OperationalCertificate
import xyz.kd5ujc.scarab.crypto.vrf.{
  Draft03Prover,
  PraosLeaderThreshold as Leader,
  PraosVrfCertificate as Vrf
}
import xyz.kd5ujc.scarab.protocol.{ChainSync, Mux as WireMux, NetworkError, ProtocolError}

/** Closed hierarchy of node failures. Algebras raise the narrowest subtype; because cats-mtl
  * `Raise` is contravariant in its error, one `Raise[F, NodeError]` serves them all.
  */
sealed trait NodeError extends DomainError:
  def message: String

/** Failures attributable to the remote peer or the connection. These are recoverable by
  * reconnecting and re-intersecting.
  */
enum PeerError extends NodeError:
  case Handshake(cause: NetworkError.HandshakeRefused | NetworkError.VersionMismatch)
  case Protocol(cause: PeerError.ProtocolFailure)
  case Framing(cause: WireMux.Error)
  case MuxSetup(cause: WireMux.Error)
  case MuxWrite(cause: WireMux.Error)
  case Unavailable(cause: PeerError.Unavailability)
  case Genesis(cause: PeerError.GenesisProblem)

  /** The peer's chain shares no point with ours (it is behind, or on another network). */
  case NoIntersection

  /** Native normal ChainSync termination after draining (`Client.hs` 1555–1585). */
  case RolledBackPastIntersection(point: ChainSync.Point)

  /** The peer's chain and ours part ways in a way that is nobody's fault: it rolled back beyond
    * what we keep, or our chain moved on so that its candidate no longer joins it. The session
    * starts again; the peer did nothing wrong.
    */
  case Diverged(cause: PeerError.Divergence)

  /** A header the peer announced breaks a header rule; its block was not fetched (G6). */
  case InvalidHeader(point: ChainSync.Point, error: xyz.kd5ujc.scarab.ledger.HeaderError)

  /** A block the peer sent is refused by our rules (the session ends and nothing of the block was
    * applied). The same fact as a [[ValidationError]] raised in the session, seen as the peer's.
    */
  case InvalidBlock(error: ValidationError)

  def message: String = this match
    case Handshake(r)   => s"handshake: ${r.message}"
    case Protocol(r)    => s"protocol: ${PeerError.protocolMessage(r)}"
    case Framing(e)     => s"protocol: multiplexer framing: ${e.message}"
    case MuxSetup(e)    => s"multiplexer setup: ${e.message}"
    case MuxWrite(e)    => s"multiplexer write: ${e.message}"
    case Unavailable(r) => s"unavailable: ${r.message}"
    case Genesis(cause) => s"Genesis: ${cause.message}"
    case NoIntersection => "the peer's chain shares no point with ours"
    case RolledBackPastIntersection(point) =>
      s"the peer rolled back beyond the captured intersection fragment to ${Points.show(point)}"
    case Diverged(r)         => s"diverged: ${r.message}"
    case InvalidHeader(p, e) => s"invalid header ${Points.show(p)}: ${e.message}"
    case InvalidBlock(e)     => s"invalid block ${e.message}"

object PeerError:
  enum GenesisProblem:
    case HistoricalAwait(
        point: ChainSync.Point,
        slotTimePicos: BigInt,
        arrivalTimePicos: BigInt,
        cutoffPicos: BigInt
    )
    case HistoricalRollback(
        point: ChainSync.Point,
        slotTimePicos: BigInt,
        arrivalTimePicos: BigInt,
        cutoffPicos: BigInt
    )
    case LoPExpired, DensityTooLow
    case CandidateTooSparse(
        intersection: ChainSync.Point,
        ourTip: ChainSync.Point,
        theirTip: ChainSync.Point
    )
    case InvalidJumpResponse(requested: ChainSync.Point, actual: Option[ChainSync.Point])
    def message: String = this match
      case HistoricalAwait(point, slot, arrival, cutoff) =>
        s"historical AwaitReply at ${Points.show(point)}: age ${arrival - slot} ps exceeds $cutoff ps"
      case HistoricalRollback(point, slot, arrival, cutoff) =>
        s"historical rollback rewinds ${Points.show(point)}: age ${arrival - slot} ps exceeds $cutoff ps"
      case CandidateTooSparse(intersection, ourTip, theirTip) =>
        s"candidate at ${Points.show(theirTip)} is not preferred to ${Points.show(ourTip)} beyond the forecast at ${Points.show(intersection)}"
      case LoPExpired    => "ChainSync patience bucket exhausted"
      case DensityTooLow => "candidate density cannot compete with another peer"
      case InvalidJumpResponse(requested, actual) =>
        s"intersection response $actual does not match requested jump ${Points.show(requested)}"
  type ProtocolFailure = ProtocolError | ProtocolProblem | NetworkError.IngressLimit |
    NetworkError.Decode | NetworkError.IllegalTransition | NetworkError.QueryRefused |
    NetworkError.TxSubmission
  enum ProtocolProblem:
    case HeaderDecode(cause: ChainError)
    case HeaderDoesNotExtend(point: ChainSync.Point, tip: ChainSync.Point)
    case HeaderValidationDidNotSettle
    case BlockCount(expected: Int, actual: Int)
    def message: String = this match
      case HeaderDecode(cause) => cause.message
      case HeaderDoesNotExtend(point, _) =>
        s"header ${Points.show(point)} does not extend the peer's chain"
      case HeaderValidationDidNotSettle => "header validation did not settle"
      case BlockCount(expected, actual) => s"expected $expected block, received $actual"
  enum Unavailability:
    case Network(
        cause: NetworkError.Connect | NetworkError.Timeout | NetworkError.SduTimeout |
          NetworkError.BearerClosed
    )
    case Connection(cause: xyz.kd5ujc.scarab.protocol.ConnectionError)
    case Resolution(host: String, cause: java.io.IOException)
    case SessionEnded
    case MissingAnnouncedBlock(point: ChainSync.Point)
    def message: String = this match
      case Network(cause)           => cause.message
      case Connection(cause)        => cause.message
      case Resolution(host, cause)  => s"cannot resolve $host: ${cause.getMessage}"
      case SessionEnded             => "the session ended"
      case MissingAnnouncedBlock(_) => "peer does not have the announced block"
  enum Divergence:
    case RollbackOutsideChain(point: ChainSync.Point)
    case DetachedCandidate(base: ChainSync.Point)
    case ForeignBlock(cause: ValidationError)
    def message: String = this match
      case RollbackOutsideChain(point) =>
        s"peer rolled back to ${Points.show(point)}, outside our chain"
      case DetachedCandidate(base) =>
        s"the peer's chain no longer joins ours at ${Points.show(base)}"
      case ForeignBlock(cause) => s"a block fetched by another peer: ${cause.message}"
  def protocolMessage(cause: ProtocolFailure): String = cause match
    case error: ProtocolError   => error.message
    case error: ProtocolProblem => error.message
    case error: NetworkError    => error.message

/** Violated local Genesis invariants; reconnecting or blaming the remote peer cannot repair them.
  */
enum GenesisInternalProblem extends NodeError:
  case MissingSharedBlockPool, UnsupportedHeaderFamily
  case MissingDynamoJumpInfo, UnexpectedIntersection
  case GraftMissingPoint(point: ChainSync.Point)
  case HeaderTimeUnavailable(point: ChainSync.Point)
  case HeaderSelectionUnavailable(point: ChainSync.Point)
  case HeaderStateUnavailable(point: ChainSync.Point)
  def message: String = this match
    case MissingDynamoJumpInfo => "Genesis dynamo has no validated jump snapshot"
    case UnexpectedIntersection =>
      "Genesis jumper with a settled intersection received another jump result"
    case UnsupportedHeaderFamily =>
      "Genesis requires a header gate supporting typed snapshot grafts"
    case MissingSharedBlockPool   => "Genesis requires the shared block pool"
    case GraftMissingPoint(point) => s"Genesis jump snapshot lacks ${Points.show(point)}"
    case HeaderSelectionUnavailable(point) =>
      s"Genesis validated header lacks a selection view at ${Points.show(point)}"
    case HeaderStateUnavailable(point) =>
      s"Genesis validated header lacks state at ${Points.show(point)}"
    case HeaderTimeUnavailable(point) =>
      s"Genesis validated header lacks slot time at ${Points.show(point)}"

/** Failures of the local chain model. These are not recoverable by reconnecting. */
enum ChainError extends NodeError:
  /** The retained ledger point and pinned backing generation do not describe one state. */
  case StateAcquire(cause: NetworkError.StateAcquireFailure)

  case Decode(what: String, cause: LedgerDecodeError)
  case NotConnected(point: ChainSync.Point, tip: ChainSync.Point)
  case RollbackBeyondAnchor(target: ChainSync.Point, anchor: ChainSync.Point)
  case NoIntersection
  case MempoolTick(slot: BigInt, cause: rules.TickFailure)

  def message: String = this match
    case MempoolTick(slot, cause) => s"cannot tick mempool ledger to slot $slot: $cause"
    case StateAcquire(cause)      => cause.message
    case Decode(w, r)             => s"decode $w: $r"
    case NotConnected(p, t)       => s"${Points.show(p)} does not extend tip ${Points.show(t)}"
    case RollbackBeyondAnchor(t, a) =>
      s"rollback to ${Points.show(t)} beyond anchor ${Points.show(a)}"
    case NoIntersection => "peer shares no point with the local chain"

/** A block or header the local rules reject, with the closed [[BlockFailure]] that says why. */
final case class ValidationError(point: ChainSync.Point, failure: BlockFailure) extends NodeError:
  /** The rule family and the reason as the log renders them (the `rule` and `reason` fields). */
  def rule: String = failure.rule.name
  def reason: String = failure.reason
  def message: String = s"${Points.show(point)} rejected by $rule: $reason"

/** The implementation cannot evaluate this block. It is no verdict on the block or its peer. */
final case class EvaluationUnavailable(point: ChainSync.Point, failure: LedgerFailure.Unavailable)
    extends NodeError:
  def message: String = s"${Points.show(point)} evaluation unavailable: ${failure.message}"

/** Why a UTxO checkpoint of the RocksDB store cannot be restored by this build. */
enum CheckpointProblem:
  /** The keys are in two layouts (an older build, then this one, wrote to the same store): the
    * entries of the old layout may have been spent since, so none can be trusted.
    */
  case MixedKeyLayouts

  /** A key of the layout before the 34-byte key whose index does not fit the 16 bits of the current
    * one.
    */
  case IndexTooLarge(index: Long)

  /** A key of a length no layout of the store has. */
  case UnknownKeyLength(length: Int)

  def render: String = this match
    case MixedKeyLayouts =>
      "it holds keys of the 36-byte layout and of the 34-byte one together"
    case IndexTooLarge(i)    => s"it holds an output index of $i, which the 34-byte key cannot hold"
    case UnknownKeyLength(n) => s"it holds a key of $n bytes"

/** Failures of the on-disk chain database. Raised by the storage algebras; IO exceptions are mapped
  * to [[Io]] at the file boundary only.
  */
enum FirstImmutableProblem extends DomainError:
  case InvalidIndexEntry
  case ChecksumMismatch
  case MissingChunk
  case BlockDecode(error: ChainError)

  def message: String = this match
    case InvalidIndexEntry  => "invalid first immutable index entry"
    case ChecksumMismatch   => "first immutable block checksum mismatch"
    case MissingChunk       => "first immutable index references a missing chunk"
    case BlockDecode(error) => s"first immutable block cannot be decoded: ${error.message}"

enum StorageError extends NodeError:
  case DatabaseInUse(database: String)

  /** The stored bytes fail validation (bad checksum, truncation, impossible structure). */
  case Corrupt[E <: DomainError](what: String, cause: E) extends StorageError

  /** A read-only validation failure while checking the first block before legacy adoption. */
  case FirstImmutable(problem: FirstImmutableProblem)

  /** An operating-system failure reading or writing `what`. */
  case Io(what: String, cause: Throwable)

  /** The data was written by an incompatible format or schema version. */
  case VersionMismatch(what: String, found: String, expected: String)

  /** A file names a format version of its kind that this build does not know (written by a newer
    * build, most likely): it is not opened, and nothing is rewritten.
    */
  case UnknownVersion(what: String, kind: FileKind, found: String)

  /** A known format whose migration reader has been retired. Refused before mutable stores open. */
  case RetiredVersion(
      what: String,
      kind: FileKind,
      found: FormatVersion,
      readableFrom: FormatVersion
  )

  /** A requested block or point is not stored. */
  case Missing(what: String)

  /** An append that does not extend the stored chain. */
  case OutOfOrder(cause: StorageStructureError)

  /** The database was created for another network than the one this node is configured for. */
  case NetworkMismatch(found: NetworkIdentity, expected: NetworkIdentity)

  /** A v1/v2 identity proves these fields differ, but does not contain the newer genesis inputs. */
  case LegacyNetworkMismatch(found: LegacyNetworkIdentity, expected: NetworkIdentity)

  /** A matching but incomplete v1/v2 identity needs an explicit operator acknowledgement. */
  case LegacyIdentityNeedsConfirmation(version: FormatVersion)

  /** Populated data has no identity; associating it with a network needs explicit confirmation. */
  case MissingIdentityNeedsConfirmation(database: String)

  /** An operator-confirmation backup already exists but contains different bytes. */
  case IdentityBackupConflict(path: String)

  /** A database without a recorded identity whose first block does not follow the configured
    * genesis (it belongs to another network, or to another genesis of the same one).
    */
  case ForeignChain(firstBlockPredecessor: String, expectedPredecessor: String)

  /** The UTxO checkpoint `dir` cannot be used. The snapshot it belongs to restores nothing safely:
    * the state has to be replayed from the immutable chunks.
    */
  case UtxoCheckpoint(dir: String, problem: CheckpointProblem)

  def message: String = this match
    case DatabaseInUse(db) => s"database $db is already owned by a follower or offline command"
    case Corrupt(w, r)     => s"corrupt $w: $r"
    case FirstImmutable(p) => s"corrupt first immutable block: ${p.message}"
    case Io(w, r)          => s"io $w: $r"
    case VersionMismatch(w, f, e) => s"$w version $f, expected $e"
    case UnknownVersion(w, k, f) =>
      s"$w: unknown ${k.label} format '$f' (this build reads from '${k.identifier(k.readableFrom)}' through '${k.identifier(k.current)}'); it was probably written by a newer build"
    case RetiredVersion(w, k, f, oldest) =>
      s"$w: retired ${k.label} format '${k.identifier(f)}'; use the archived migration build to convert a copy to '${k.identifier(oldest)}' before opening it"
    case Missing(w)    => s"missing $w"
    case OutOfOrder(r) => s"out of order: $r"
    case NetworkMismatch(f, e) =>
      s"the database belongs to another network: it was created for ${f.describe}, this node is configured for ${e.describe}; use the matching --genesis-dir or another --db"
    case LegacyNetworkMismatch(f, e) =>
      s"the legacy database identity differs from this node: it records ${f.describe}, configured identity is ${e.describe}; use the matching genesis configuration or another --db"
    case LegacyIdentityNeedsConfirmation(version) =>
      s"the database identity format ${FileKind.IdentityFile.identifier(version)} lacks Alonzo and Conway genesis bindings; rerun with --adopt-legacy-network-identity after verifying the database's origin (this acknowledgement is not historical proof)"
    case MissingIdentityNeedsConfirmation(database) =>
      s"database $database contains data but has no network identity; rerun with --adopt-legacy-network-identity only after verifying its origin (this acknowledgement is not historical proof)"
    case IdentityBackupConflict(path) =>
      s"cannot preserve the legacy network identity because $path already contains different bytes"
    case ForeignChain(f, e) =>
      s"the database holds a chain whose first block names $f as its predecessor, the configured genesis requires $e; use the matching --genesis-dir or another --db"
    case UtxoCheckpoint(d, p) =>
      s"the UTxO checkpoint $d cannot be restored: ${p.render}; replay from the immutable chunks (stop the node, remove <db>/snapshots and <db>/utxo, start it again)"

/** Why this node cannot (or did not) forge a block. [[NotLeader]] is the ordinary answer of most
  * slots; the others are real problems with the keys, the ledger view or the assembled block.
  */
enum ForgeError extends NodeError:
  /** A key or certificate file is missing, malformed or of the wrong type. */
  case KeyFile(file: String, cause: ForgeKeyProblem)

  /** A failure at one of the durability boundaries for an evolved KES key. */
  case KesFileFailure(file: String, operation: KesFileOperation, cause: KesFileFailureCause)

  /** A KES signing key was decoded, but its material is invalid. */
  case KesKey(file: String, cause: Sum6KesSigning.Failure)

  /** The certificate names another KES key. */
  case KesKeyNotCertified(
      expected: Bytes,
      actual: Bytes
  )

  case CertificateFailure(cause: OperationalCertificate.Failure)
  case CertificateSignatureRejected

  /** The KES period of the slot is outside the operational certificate's window. */
  case KeyPeriodOutOfRange(period: BigInt, start: BigInt, evolutions: BigInt)

  /** A cancelled key operation may have mutated key material without completing durability. */
  case KesUpdateInterrupted

  /** The KES key could not be evolved to the slot's period (it has already moved past it). */
  case KesEvolution(cause: Sum6KesSigning.Failure)

  case VrfInput(cause: Vrf.Failure)
  case VrfProof(cause: Draft03Prover.Failure)
  case LeaderThreshold(cause: Leader.Error)

  /** The VRF output of the slot is above the pool's threshold (or the pool has no stake). */
  case NotLeader(slot: BigInt)

  /** The slot's pool distribution cannot be derived from the tip (tip too old or ledger not ready).
    */
  case LedgerViewUnavailable(slot: BigInt, cause: ForgeLedgerProblem)

  /** Forging is only implemented for Praos (Conway) on a fully validated ledger. */
  case UnsupportedProtocol(cause: ForgeProtocolProblem)

  /** The block body or header could not be assembled within the protocol limits. */
  case Assembly(cause: ForgeAssemblyProblem)

  /** Our own block rules refuse the block we built. */
  case SelfRejected(error: ValidationError)

  def message: String = this match
    case KeyFile(f, r) => s"key file $f: $r"
    case KesFileFailure(f, op, cause) =>
      s"KES key file $f during ${op.toString}: ${cause.message}"
    case KesKey(f, e) => s"key file $f: ${e.message}"
    case KesKeyNotCertified(_, _) =>
      "keys do not match: the operational certificate names another KES verification key"
    case CertificateFailure(e) => s"operational certificate: ${e.message}"
    case CertificateSignatureRejected =>
      "keys do not match: the operational certificate is not signed by its cold key"
    case KeyPeriodOutOfRange(p, s, e) =>
      s"KES period $p outside the operational certificate window [$s, ${s + e})"
    case KesUpdateInterrupted =>
      "KES key operation was interrupted; restart to recover durable key material"
    case KesEvolution(e)                => s"KES: ${e.message}"
    case VrfInput(e)                    => s"VRF: $e"
    case VrfProof(e)                    => s"VRF: $e"
    case LeaderThreshold(e)             => s"VRF: ${e.message}"
    case NotLeader(slot)                => s"not elected for slot $slot"
    case LedgerViewUnavailable(slot, r) => s"no ledger view for slot $slot: $r"
    case UnsupportedProtocol(r)         => s"cannot forge: $r"
    case Assembly(r)                    => s"block assembly: $r"
    case SelfRejected(e) => s"our own ${e.rule} rules refuse the forged block: ${e.reason}"

enum KesFileOperation:
  case StageWrite, StageSync, StageReadback, DirectorySyncBeforeErase
  case ScrubWrite, ScrubSync, Rename, DirectorySyncAfterRename, Cleanup

enum KesFileFailureCause:
  case FileSystem(cause: Throwable)
  case NoWriteProgress
  case StageReadback(cause: ForgeError)
  case StagedKeyMismatch(mismatch: KesStageMismatch)
  case StageStillPresentAfterRecovery

  def message: String = this match
    case FileSystem(cause)              => Option(cause.getMessage).getOrElse(cause.toString)
    case NoWriteProgress                => "file channel made no write progress"
    case StageReadback(cause)           => cause.message
    case StagedKeyMismatch(value)       => value.message
    case StageStillPresentAfterRecovery => "staged key remains after successful recovery"

enum KesStageMismatch:
  case Period(expected: Int, actual: Int)
  case VerificationKey(expected: Bytes, actual: Bytes)
  case SerializedKeyBytes

  def message: String = this match
    case Period(expected, actual) => s"staged KES period $actual, expected $expected"
    case VerificationKey(_, _)    => "staged KES verification key does not match the evolved key"
    case SerializedKeyBytes       => "staged KES serialized key bytes do not match the evolved key"

/** The peer governor stopped the node, as the reference's `abortGovernor` does: its state broke an
  * invariant (a bug), or the node did not reach a clean state with only trusted peers in time
  * (`BootstrapPeersCriticalTimeoutError`, ouroboros-network `Governor/Types.hs` 1863-1869).
  */
enum GovernorFault extends NodeError:
  case InvariantBroken[E <: DomainError](violations: List[E]) extends GovernorFault
  case BootstrapPeersCriticalTimeout
  case SnapshotStorage(error: StorageError)
  case SnapshotVerificationDefect(cause: Throwable)
  case LocalDnsRefreshDefect(cause: Throwable)

  def message: String = this match
    case InvariantBroken(vs) => s"the peer selection state broke an invariant: ${vs.mkString(", ")}"
    case BootstrapPeersCriticalTimeout =>
      "the node did not reach a clean state with only bootstrap peers within 15 minutes"
    case SnapshotStorage(error) => s"peer snapshot verification: ${error.message}"
    case SnapshotVerificationDefect(cause) =>
      s"peer snapshot verification defect: ${Option(cause.getMessage).getOrElse(cause.getClass.getName)}"
    case LocalDnsRefreshDefect(cause) =>
      s"local root DNS refresh defect: ${Option(cause.getMessage).getOrElse(cause.getClass.getName)}"

/** The BLS12-381 library the JVM would load cannot run on this machine, or computes a wrong answer
  * (checked at start-up; ADR 0012). Without the check the JVM dies with SIGILL, without an event.
  */
final case class NativeLibraryError(problem: BlstProblem) extends NodeError:
  def message: String = s"native library: ${problem.message}"

/** A configuration failure. `problem` says which one for the callers that act on it (a genesis file
  * whose hash differs from the configured one, a topology the node cannot follow); `reason` is the
  * text.
  */
final case class ConfigError(problem: ConfigProblem) extends NodeError:
  def reason: String = problem.message
  def message: String = s"config: ${problem.message}"

object Points:
  def show(point: ChainSync.Point): String = point match
    case ChainSync.Point.Origin            => "origin"
    case ChainSync.Point.Block(slot, hash) => s"${slot.value}@${hash.hex.take(16)}"
