// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import xyz.kd5ujc.scarab.codec.DomainError

/** Why the connection manager refuses or cannot do something. Closed; raised through `Raise`. */
enum ConnectionError extends DomainError:
  /** The hard limit of accepted (inbound) connections is reached. */
  case InboundLimit(remote: String, limit: Long)

  /** The per-address limit of accepted connections from one IP address is reached. The reference
    * has none; see ADR 0008.
    */
  case AddressLimit(remote: String, limit: Int)

  /** The limit of connections we open is reached. */
  case OutboundLimit(remote: String, limit: Int)

  /** There is already a live connection to or from this address: one connection per peer. */
  case AlreadyConnected(remote: String, state: ConnectionState)

  /** An event the connection's state does not allow. */
  case IllegalTransition(remote: String, problem: ConnectionState.Illegal)

  /** A connection with `remote` ended less than the time-wait ago: no outbound connection to it
    * until then, as the reference's connection manager refuses one to a peer it holds in
    * `TerminatingState` (`ConnectionInTerminatingState`, ouroboros-network `2fb69829`
    * `ConnectionManager/Core.hs`).
    */
  case TimeWait(remote: String)

  def message: String = this match
    case InboundLimit(r, l) =>
      s"refused inbound connection from $r: $l connections accepted already"
    case AddressLimit(r, l) =>
      s"refused inbound connection from $r: $l connections from that address already"
    case OutboundLimit(r, l)    => s"refused outbound connection to $r: $l connections open already"
    case AlreadyConnected(r, s) => s"already connected to $r ($s)"
    case IllegalTransition(r, p) => s"$r: ${p.message}"
    case TimeWait(r) => s"refused outbound connection to $r: its last connection is in time-wait"
