// SPDX-License-Identifier: Apache-2.0
package lab.network

/** Pure life cycle of one node-to-node connection as the connection manager keeps it (a reduction
  * of ouroboros-network's `ConnectionState`: the states that matter for accounting and for deciding
  * who may run clients on a connection). No transport, no time.
  *
  * {{{
  *   outbound:  Reserved -> Unnegotiated(Outbound) -> Established(Outbound, flow)
  *   inbound:              Unnegotiated(Inbound)  -> Established(Inbound, flow)
  *   any live state -> Terminating -> Terminated
  * }}}
  *
  * `flow` is what the handshake agreed: `Unidirectional` (the dialling side runs the clients and
  * the other side the servers) or `Duplex` (both sides run both, see Mux duplex).
  */
enum ConnectionState:
  /** An outbound slot is taken for an address; the connection is being opened. */
  case Reserved

  /** The transport is up and the handshake has not finished. */
  case Unnegotiated(provenance: ConnectionState.Provenance)

  /** The handshake agreed `flow`. */
  case Established(provenance: ConnectionState.Provenance, flow: ConnectionState.DataFlow)

  /** Being closed: its resources are being released. */
  case Terminating

  /** Gone; kept only for the instant between release and removal. */
  case Terminated

  def direction: Option[ConnectionState.Provenance] = this match
    case Reserved                 => Some(ConnectionState.Provenance.Outbound)
    case Unnegotiated(p)          => Some(p)
    case Established(p, _)        => Some(p)
    case Terminating | Terminated => None

  def isLive: Boolean = this match
    case Terminating | Terminated => false
    case _                        => true

  /** We may run our own clients on the connection: always when we dialled it, and when the peer
    * dialled and agreed to duplex.
    */
  def canRunClients: Boolean = this match
    case Established(ConnectionState.Provenance.Outbound, _)                              => true
    case Established(ConnectionState.Provenance.Inbound, ConnectionState.DataFlow.Duplex) => true
    case _                                                                                => false

object ConnectionState:
  enum Provenance:
    case Inbound, Outbound

  enum DataFlow:
    case Unidirectional, Duplex

  enum Event:
    /** The transport of a reserved outbound connection is up. */
    case Connected

    /** The handshake agreed `flow`. */
    case Negotiated(flow: DataFlow)

    /** Close the connection (a failure, a protocol error, the peer leaving, shutdown). */
    case Close

    /** The resources are released. */
    case Released

  /** The state a connection starts in. */
  def initial(provenance: Provenance): ConnectionState = provenance match
    case Provenance.Outbound => Reserved
    case Provenance.Inbound  => Unnegotiated(Provenance.Inbound)

  /** An event the state does not allow. */
  final case class Illegal(from: ConnectionState, event: Event):
    def message: String = s"connection in state $from cannot take $event"

  def transition(state: ConnectionState, event: Event): Either[Illegal, ConnectionState] =
    (state, event) match
      case (Reserved, Event.Connected)               => Right(Unnegotiated(Provenance.Outbound))
      case (Unnegotiated(p), Event.Negotiated(flow)) => Right(Established(p, flow))
      case (Terminated, Event.Close) | (Terminating, Event.Close) => Right(state)
      case (s, Event.Close) if s.isLive                           => Right(Terminating)
      case (Terminating, Event.Released)                          => Right(Terminated)
      case (s, e)                                                 => Left(Illegal(s, e))

  /** What the manager counts: connections by direction (every live connection, negotiated or not,
    * counts against the limit of its direction) and the number of established duplex ones.
    */
  final case class Counters(inbound: Int, outbound: Int, duplex: Int, established: Int):
    def total: Int = inbound + outbound

  object Counters:
    val zero: Counters = Counters(0, 0, 0, 0)

    def of(states: Iterable[ConnectionState]): Counters =
      states.foldLeft(zero) { (c, s) =>
        val withDirection = s.direction match
          case Some(Provenance.Inbound)  => c.copy(inbound = c.inbound + 1)
          case Some(Provenance.Outbound) => c.copy(outbound = c.outbound + 1)
          case None                      => c
        s match
          case Established(_, flow) =>
            withDirection.copy(
              established = withDirection.established + 1,
              duplex = withDirection.duplex + (if flow == DataFlow.Duplex then 1 else 0)
            )
          case _ => withDirection
      }
