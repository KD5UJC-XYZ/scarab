// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

/** Why the connection manager refuses or cannot do something. Closed; raised through `Raise`. */
enum ConnectionError:
  /** The hard limit of accepted (inbound) connections is reached. */
  case InboundLimit(remote: String, limit: Int)

  /** The limit of connections we open is reached. */
  case OutboundLimit(remote: String, limit: Int)

  /** There is already a live connection to or from this address: one connection per peer. */
  case AlreadyConnected(remote: String, state: ConnectionState)

  /** An event the connection's state does not allow. */
  case IllegalTransition(remote: String, problem: ConnectionState.Illegal)

  def message: String = this match
    case InboundLimit(r, l) =>
      s"refused inbound connection from $r: $l connections accepted already"
    case OutboundLimit(r, l)    => s"refused outbound connection to $r: $l connections open already"
    case AlreadyConnected(r, s) => s"already connected to $r ($s)"
    case IllegalTransition(r, p) => s"$r: ${p.message}"

