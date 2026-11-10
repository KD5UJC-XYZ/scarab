// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import xyz.kd5ujc.scarab.codec.DomainError

/** Why the connection manager refuses or cannot do something. Closed; raised through `Raise`. The
  * cases after `IllegalTransition` are the reference's `ConnectionManagerError` constructors
  * (ouroboros-network `2fb69829`, `ConnectionManager/Types.hs`), raised where `Core.hs` raises
  * them.
  */
enum ConnectionError extends DomainError:
  /** The hard limit of accepted (inbound) connections is reached. */
  case InboundLimit(remote: String, limit: Long)

  /** The per-address limit of accepted connections from one IP address is reached. The reference
    * has none; see ADR 0008.
    */
  case AddressLimit(remote: String, limit: Int)

  /** The limit of connections we open is reached. */
  case OutboundLimit(remote: String, limit: Int)

  /** The reference's `ConnectionExists`: the outbound side already holds a connection to this
    * address (`acquireOutboundConnectionImpl`, `Core.hs` 1287-1314, 1365-1369).
    */
  case AlreadyConnected(remote: String, state: ConnectionState)

  /** An event the connection's state does not allow. */
  case IllegalTransition(remote: String, problem: ConnectionState.Illegal)

  /** `ForbiddenOperation`: the connection is in a state the operation is not defined for, such as
    * an outbound acquisition of an `OutboundIdle` connection (`Core.hs` 1321-1325) or an accepted
    * connection whose identity an established one already has (`Core.hs` 840-860).
    */
  case ForbiddenOperation(remote: String, state: ConnectionState)

  /** `ForbiddenConnection`: the peer's connection is unidirectional, so the outbound side cannot
    * use it (`Core.hs` 1327-1331, 1356-1362).
    */
  case ForbiddenConnection(remote: String)

  /** `InboundConnectionNotFound`: a peer known only from its own connection (inbound provenance) is
    * no longer connected; the manager does not dial it (`Core.hs` 1390-1399).
    */
  case InboundConnectionNotFound(remote: String)

  /** `ImpossibleState`: a state the reference rules out at this point (`Core.hs` 1666-1668). */
  case ImpossibleState(remote: String, state: ConnectionState)

  /** `Disconnected`: the connection the outbound side waited for ended before it could be used
    * (`Core.hs` 1741-1749), in `state` (`Terminating` or `Terminated`), with the error its handler
    * ended with if it failed: the reference keeps it in the connection's state
    * (`mkDisconnectionException`, `Core.hs` 1080-1094), and the outbound governor tells a handshake
    * failure from another one by it (`PeerStateActions.hs` 852-880).
    */
  case Disconnected(remote: String, state: ConnectionState, cause: Option[NetworkError])

  /** The manager pruned the connection to keep the accepted connections within the hard limit
    * (`mkPruneAction`, `Core.hs` 703-778): the mux is stopped with this cause.
    */
  case Pruned(remote: String)

  def message: String = this match
    case InboundLimit(r, l) =>
      s"refused inbound connection from $r: $l connections accepted already"
    case AddressLimit(r, l) =>
      s"refused inbound connection from $r: $l connections from that address already"
    case OutboundLimit(r, l)    => s"refused outbound connection to $r: $l connections open already"
    case AlreadyConnected(r, s) => s"already connected to $r (${s.abstractName})"
    case IllegalTransition(r, p) => s"$r: ${p.message}"
    case ForbiddenOperation(r, s) =>
      s"$r: the operation is forbidden on a connection in state ${s.abstractName}"
    case ForbiddenConnection(r) =>
      s"$r: the peer's connection is unidirectional; the outbound side cannot use it"
    case InboundConnectionNotFound(r) =>
      s"$r: the peer is known from its own connection, which no longer exists"
    case ImpossibleState(r, s) => s"$r: impossible connection state ${s.abstractName}"
    case Disconnected(r, s, c) =>
      s"$r: the connection ended (${s.abstractName}${c.fold("")(e => s": ${e.message}")}) before the outbound side could use it"
    case Pruned(r) => s"$r: pruned to keep the accepted connections within the hard limit"
