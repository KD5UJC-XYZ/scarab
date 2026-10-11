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

  def message: String = this match
    case Handshake(r)   => s"handshake: $r"
    case Protocol(r)    => s"protocol: $r"
    case Unavailable(r) => s"unavailable: $r"

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

  def message: String = this match
    case Corrupt(w, r)            => s"corrupt $w: $r"
    case Io(w, r)                 => s"io $w: $r"
    case VersionMismatch(w, f, e) => s"$w version $f, expected $e"
    case Missing(w)               => s"missing $w"
    case OutOfOrder(r)            => s"out of order: $r"

final case class ConfigError(reason: String) extends NodeError:
  def message: String = s"config: $reason"

object Points:
  def show(point: ChainSync.Point): String = point match
    case ChainSync.Point.Origin            => "origin"
    case ChainSync.Point.Block(slot, hash) => s"${slot.value}@${hash.hex.take(16)}"
