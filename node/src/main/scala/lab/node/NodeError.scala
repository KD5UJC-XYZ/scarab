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

final case class ConfigError(reason: String) extends NodeError:
  def message: String = s"config: $reason"

object Points:
  def show(point: ChainSync.Point): String = point match
    case ChainSync.Point.Origin            => "origin"
    case ChainSync.Point.Block(slot, hash) => s"${slot.value}@${hash.hex.take(16)}"
