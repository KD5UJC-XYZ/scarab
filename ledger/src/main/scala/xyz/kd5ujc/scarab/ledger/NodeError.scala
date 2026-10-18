// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.blst.BlstProblem
import xyz.kd5ujc.scarab.crypto.kes.Sum6KesSigning
import xyz.kd5ujc.scarab.crypto.vrf.{
  Draft03Prover,
  PraosLeaderThreshold as Leader,
  PraosVrfCertificate as Vrf
}
import xyz.kd5ujc.scarab.protocol.ChainSync

/** Closed hierarchy of node failures. Algebras raise the narrowest subtype; because cats-mtl
  * `Raise` is contravariant in its error, one `Raise[F, NodeError]` serves them all.
  */
sealed trait NodeError extends Product with Serializable:
  def message: String

/** Failures attributable to the remote peer or the connection. These are recoverable by
  * reconnecting and re-intersecting.
  */
enum PeerError extends NodeError:
  case Handshake(reason: String)
  case Protocol(reason: String)
  case Unavailable(reason: String)

  /** The peer's chain shares no point with ours (it is behind, or on another network). */
  case NoIntersection

  /** The peer's chain and ours part ways in a way that is nobody's fault: it rolled back beyond
    * what we keep, or our chain moved on so that its candidate no longer joins it. The session
    * starts again; the peer did nothing wrong.
    */
  case Diverged(reason: String)

  /** A header the peer announced breaks a header rule; its block was not fetched (G6). */
  case InvalidHeader(point: ChainSync.Point, error: xyz.kd5ujc.scarab.ledger.HeaderError)

  /** A block the peer sent is refused by our rules (the session ends and nothing of the block was
    * applied). The same fact as a [[ValidationError]] raised in the session, seen as the peer's.
    */
  case InvalidBlock(error: ValidationError)

  def message: String = this match
    case Handshake(r)        => s"handshake: $r"
    case Protocol(r)         => s"protocol: $r"
    case Unavailable(r)      => s"unavailable: $r"
    case NoIntersection      => "the peer's chain shares no point with ours"
    case Diverged(r)         => s"diverged: $r"
    case InvalidHeader(p, e) => s"invalid header ${Points.show(p)}: ${e.message}"
    case InvalidBlock(e)     => s"invalid block ${e.message}"

/** Failures of the local chain model. These are not recoverable by reconnecting. */
enum ChainError extends NodeError:
  case Decode(what: String, reason: String)
  case NotConnected(point: ChainSync.Point, tip: ChainSync.Point)
  case RollbackBeyondAnchor(target: ChainSync.Point, anchor: ChainSync.Point)
  case NoIntersection

  def message: String = this match
    case Decode(w, r)       => s"decode $w: $r"
    case NotConnected(p, t) => s"${Points.show(p)} does not extend tip ${Points.show(t)}"
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
enum StorageError extends NodeError:
  case DatabaseInUse(database: String)

  /** The stored bytes fail validation (bad checksum, truncation, impossible structure). */
  case Corrupt(what: String, reason: String)

  /** An operating-system failure reading or writing `what`. */
  case Io(what: String, reason: String)

  /** The data was written by an incompatible format or schema version. */
  case VersionMismatch(what: String, found: String, expected: String)

  /** A file names a format version of its kind that this build does not know (written by a newer
    * build, most likely): it is not opened, and nothing is rewritten.
    */
  case UnknownVersion(what: String, kind: FileKind, found: String)

  /** A requested block or point is not stored. */
  case Missing(what: String)

  /** An append that does not extend the stored chain. */
  case OutOfOrder(reason: String)

  /** The database was created for another network than the one this node is configured for. */
  case NetworkMismatch(found: NetworkIdentity, expected: NetworkIdentity)

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
    case Io(w, r)          => s"io $w: $r"
    case VersionMismatch(w, f, e) => s"$w version $f, expected $e"
    case UnknownVersion(w, k, f) =>
      s"$w: unknown ${k.label} format '$f' (this build reads '${k.identifier(FormatVersion.V1)}' and '${k.identifier(k.current)}'); it was probably written by a newer build"
    case Missing(w)    => s"missing $w"
    case OutOfOrder(r) => s"out of order: $r"
    case NetworkMismatch(f, e) =>
      s"the database belongs to another network: it was created for ${f.describe}, this node is configured for ${e.describe}; use the matching --genesis-dir or another --db"
    case ForeignChain(f, e) =>
      s"the database holds a chain whose first block names $f as its predecessor, the configured genesis requires $e; use the matching --genesis-dir or another --db"
    case UtxoCheckpoint(d, p) =>
      s"the UTxO checkpoint $d cannot be restored: ${p.render}; replay from the immutable chunks (stop the node, remove <db>/snapshots and <db>/utxo, start it again)"

/** Why this node cannot (or did not) forge a block. [[NotLeader]] is the ordinary answer of most
  * slots; the others are real problems with the keys, the ledger view or the assembled block.
  */
enum ForgeError extends NodeError:
  /** A key or certificate file is missing, malformed or of the wrong type. */
  case KeyFile(file: String, reason: String)

  /** A KES signing key was decoded, but its material is invalid. */
  case KesKey(file: String, cause: Sum6KesSigning.Failure)

  /** The keys do not belong together (hot key, certificate signature, VRF pair). */
  case KeyMismatch(reason: String)

  /** The KES period of the slot is outside the operational certificate's window. */
  case KeyPeriodOutOfRange(period: BigInt, start: BigInt, evolutions: BigInt)

  /** Legacy unexpected forger-step failure; infrastructure/defect classification is #180 G7-F3. */
  case KesFailure(reason: String)

  /** The KES key could not be evolved to the slot's period (it has already moved past it). */
  case KesEvolution(cause: Sum6KesSigning.Failure)

  case VrfInput(cause: Vrf.Failure)
  case VrfProof(cause: Draft03Prover.Failure)
  case LeaderThreshold(cause: Leader.Error)

  /** The VRF output of the slot is above the pool's threshold (or the pool has no stake). */
  case NotLeader(slot: BigInt)

  /** The slot's pool distribution cannot be derived from the tip (tip too old or ledger not ready).
    */
  case LedgerViewUnavailable(slot: BigInt, reason: String)

  /** Forging is only implemented for Praos (Conway) on a fully validated ledger. */
  case UnsupportedProtocol(reason: String)

  /** The block body or header could not be assembled within the protocol limits. */
  case Assembly(reason: String)

  /** Our own block rules refuse the block we built. */
  case SelfRejected(rule: String, reason: String)

  def message: String = this match
    case KeyFile(f, r)  => s"key file $f: $r"
    case KesKey(f, e)   => s"key file $f: ${e.message}"
    case KeyMismatch(r) => s"keys do not match: $r"
    case KeyPeriodOutOfRange(p, s, e) =>
      s"KES period $p outside the operational certificate window [$s, ${s + e})"
    case KesFailure(r)                  => s"KES: $r"
    case KesEvolution(e)                => s"KES: ${e.message}"
    case VrfInput(e)                    => s"VRF: $e"
    case VrfProof(e)                    => s"VRF: $e"
    case LeaderThreshold(e)             => s"VRF: ${e.message}"
    case NotLeader(slot)                => s"not elected for slot $slot"
    case LedgerViewUnavailable(slot, r) => s"no ledger view for slot $slot: $r"
    case UnsupportedProtocol(r)         => s"cannot forge: $r"
    case Assembly(r)                    => s"block assembly: $r"
    case SelfRejected(rule, r)          => s"our own $rule rules refuse the forged block: $r"

/** The peer governor stopped the node, as the reference's `abortGovernor` does: its state broke an
  * invariant (a bug), or the node did not reach a clean state with only trusted peers in time
  * (`BootstrapPeersCriticalTimeoutError`, ouroboros-network `Governor/Types.hs` 1863-1869).
  */
enum GovernorFault extends NodeError:
  case InvariantBroken(violations: List[String])
  case BootstrapPeersCriticalTimeout

  def message: String = this match
    case InvariantBroken(vs) => s"the peer selection state broke an invariant: ${vs.mkString(", ")}"
    case BootstrapPeersCriticalTimeout =>
      "the node did not reach a clean state with only bootstrap peers within 15 minutes"

/** The BLS12-381 library the JVM would load cannot run on this machine, or computes a wrong answer
  * (checked at start-up; ADR 0012). Without the check the JVM dies with SIGILL, without an event.
  */
final case class NativeLibraryError(problem: BlstProblem) extends NodeError:
  def message: String = s"native library: ${problem.message}"

/** A configuration failure. `problem` says which one for the callers that act on it (a genesis file
  * whose hash differs from the configured one, a topology the node cannot follow); `reason` is the
  * text.
  */
final case class ConfigError(reason: String, problem: ConfigProblem = ConfigProblem.Invalid)
    extends NodeError:
  def message: String = problem match
    case ConfigProblem.ActiveSlotsCoefficient(e) => s"config: ${e.message}"
    case _                                       => s"config: $reason"

/** What kind of configuration failure a [[ConfigError]] is. */
enum ConfigProblem:
  case ActiveSlotsCoefficient(cause: Leader.Error)

  /** Anything else: a malformed file, a missing or invalid field, a bad flag. */
  case Invalid

  /** A file the configuration lists could not be read. */
  case FileMissing(role: String, file: String)

  /** The blake2b-256 of a genesis file differs from the hash the configuration lists. */
  case GenesisHashMismatch(role: String, file: String, expected: String, actual: String)

  /** A topology in a format this node does not read (the legacy `Producers` one) or an access point
    * it cannot connect to.
    */
  case UnsupportedTopology

  /** A flag of an earlier build that this one no longer has (the rule sets and the differential
    * harness, removed at J2.9): the node is not started.
    */
  case RemovedFlag(flag: String)

object Points:
  def show(point: ChainSync.Point): String = point match
    case ChainSync.Point.Origin            => "origin"
    case ChainSync.Point.Block(slot, hash) => s"${slot.value}@${hash.hex.take(16)}"
