// SPDX-License-Identifier: Apache-2.0
package lab.node

import lab.network.ChainSync

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

  /** A header the peer announced breaks a header rule; its block was not fetched (G6). */
  case InvalidHeader(point: ChainSync.Point, error: lab.node.consensus.HeaderError)

  def message: String = this match
    case Handshake(r)        => s"handshake: $r"
    case Protocol(r)         => s"protocol: $r"
    case Unavailable(r)      => s"unavailable: $r"
    case NoIntersection      => "the peer's chain shares no point with ours"
    case InvalidHeader(p, e) => s"invalid header ${Points.show(p)}: ${e.message}"

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

/** A block or header the local rules reject. */
final case class ValidationError(point: ChainSync.Point, rule: String, reason: String)
    extends NodeError:
  def message: String = s"${Points.show(point)} rejected by $rule: $reason"

/** Failures of the on-disk chain database. Raised by the storage algebras; IO exceptions are mapped
  * to [[Io]] at the file boundary only.
  */
enum StorageError extends NodeError:
  /** The stored bytes fail validation (bad checksum, truncation, impossible structure). */
  case Corrupt(what: String, reason: String)

  /** An operating-system failure reading or writing `what`. */
  case Io(what: String, reason: String)

  /** The data was written by an incompatible format or schema version. */
  case VersionMismatch(what: String, found: String, expected: String)

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

  def message: String = this match
    case Corrupt(w, r)            => s"corrupt $w: $r"
    case Io(w, r)                 => s"io $w: $r"
    case VersionMismatch(w, f, e) => s"$w version $f, expected $e"
    case Missing(w)               => s"missing $w"
    case OutOfOrder(r)            => s"out of order: $r"
    case NetworkMismatch(f, e) =>
      s"the database belongs to another network: it was created for ${f.describe}, this node is configured for ${e.describe}; use the matching --genesis-dir or another --db"
    case ForeignChain(f, e) =>
      s"the database holds a chain whose first block names $f as its predecessor, the configured genesis requires $e; use the matching --genesis-dir or another --db"

/** Why this node cannot (or did not) forge a block. [[NotLeader]] is the ordinary answer of most
  * slots; the others are real problems with the keys, the ledger view or the assembled block.
  */
enum ForgeError extends NodeError:
  /** A key or certificate file is missing, malformed or of the wrong type. */
  case KeyFile(file: String, reason: String)

  /** The keys do not belong together (hot key, certificate signature, VRF pair). */
  case KeyMismatch(reason: String)

  /** The KES period of the slot is outside the operational certificate's window. */
  case KeyPeriodOutOfRange(period: BigInt, start: BigInt, evolutions: BigInt)

  /** The KES key could not be evolved to the slot's period (it has already moved past it). */
  case KesFailure(reason: String)

  case VrfFailure(reason: String)

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
    case KeyMismatch(r) => s"keys do not match: $r"
    case KeyPeriodOutOfRange(p, s, e) =>
      s"KES period $p outside the operational certificate window [$s, ${s + e})"
    case KesFailure(r)                  => s"KES: $r"
    case VrfFailure(r)                  => s"VRF: $r"
    case NotLeader(slot)                => s"not elected for slot $slot"
    case LedgerViewUnavailable(slot, r) => s"no ledger view for slot $slot: $r"
    case UnsupportedProtocol(r)         => s"cannot forge: $r"
    case Assembly(r)                    => s"block assembly: $r"
    case SelfRejected(rule, r)          => s"our own $rule rules refuse the forged block: $r"

final case class ConfigError(reason: String) extends NodeError:
  def message: String = s"config: $reason"

object Points:
  def show(point: ChainSync.Point): String = point match
    case ChainSync.Point.Origin            => "origin"
    case ChainSync.Point.Block(slot, hash) => s"${slot.value}@${hash.hex.take(16)}"
