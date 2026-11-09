// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

/** The state of one node-to-node connection as the reference's connection manager keeps it: the
  * constructors of `ConnectionState` (ouroboros-network `2fb69829`,
  * `ConnectionManager/State.hs:191-217`) without their runtime fields (connection id, thread,
  * handle, error), which the manager holds beside the state. No transport, no time.
  *
  * `OutboundDup` carries whether the outbound idle timeout has fired (`TimeoutExpired`,
  * `ConnectionManager/Types.hs:228`). A connection the manager has removed is not in its table;
  * the reference calls that `UnknownConnectionSt`.
  */
enum ConnectionState:
  /** An outbound connection is reserved for an address and not yet connected. */
  case ReservedOutbound

  /** The transport is up and the handshake has not finished. */
  case Unnegotiated(provenance: ConnectionState.Provenance)

  /** Ours, unidirectional, in outbound use. */
  case OutboundUni

  /** Ours or reused, duplex, in outbound use only; the remote side is idle. */
  case OutboundDup(timeout: ConnectionState.TimeoutExpired)

  /** In outbound use no more; waits for the remote side or the outbound idle timeout. */
  case OutboundIdle(flow: ConnectionState.DataFlow)

  /** The peer's; neither side runs protocols on it. */
  case InboundIdle(flow: ConnectionState.DataFlow)

  /** The peer runs its protocols on it; we do not. */
  case Inbound(flow: ConnectionState.DataFlow)

  /** Both sides run their protocols on it. */
  case Duplex

  /** Being closed: its resources are being released, or it is held in time-wait. */
  case Terminating

  /** Gone; kept only for the instant between release and removal. */
  case Terminated

  def isLive: Boolean = this match
    case Terminating | Terminated => false
    case _                        => true

  /** The reference's `AbstractState` name (`ConnectionManager/Types.hs:674-688`), as its traces
    * print it.
    */
  def abstractName: String = this match
    case ReservedOutbound   => "ReservedOutboundSt"
    case Unnegotiated(p)    => s"UnnegotiatedSt $p"
    case OutboundUni        => "OutboundUniSt"
    case OutboundDup(t)     => s"OutboundDupSt $t"
    case OutboundIdle(flow) => s"OutboundIdleSt $flow"
    case InboundIdle(flow)  => s"InboundIdleSt $flow"
    case Inbound(flow)      => s"InboundSt $flow"
    case Duplex             => "DuplexSt"
    case Terminating        => "TerminatingSt"
    case Terminated         => "TerminatedSt"

object ConnectionState:
  enum Provenance:
    case Inbound, Outbound

  enum DataFlow:
    case Unidirectional, Duplex

  /** Whether the outbound idle timeout of an `OutboundDup` connection has fired. */
  enum TimeoutExpired:
    case Ticking, Expired

  /** The reference's named transitions (the `@Name : From → To@` annotations of
    * `ConnectionManager/Core.hs` and the cases of `verifyAbstractTransition`,
    * `Test/Ouroboros/Network/ConnectionManager/Utils.hs`). Each event is defined on the states the
    * reference takes it from; [[transition]] refuses it anywhere else.
    */
  enum Event:
    /** `Connected`: the transport of a reserved outbound connection is up. */
    case Connected

    /** `Negotiated^{flow}_{Outbound}` or `Negotiated^{flow}_{Inbound}`: the handshake agreed `flow`. */
    case Negotiated(flow: DataFlow)

    /** `Overwritten`: an accepted connection takes the place of an outbound reservation for the
      * same address.
      */
    case Overwritten

    /** `Awake^{Duplex}_{Local}`: the outbound side starts using an idle inbound duplex connection. */
    case AwakeLocal

    /** `PromotedToWarm^{Duplex}_{Local}`: the outbound side starts using an inbound duplex connection
      * the peer is using.
      */
    case PromotedToWarmLocal

    /** `DemotedToCold^{flow}_{Local}`: the outbound side stops using the connection. */
    case DemotedToColdLocal

    /** `PromotedToWarm^{Duplex}_{Remote}` and `Awake^{flow}_{Remote}`: the peer starts its
      * protocols on the connection.
      */
    case AwakeRemote

    /** `DemotedToCold^{flow}_{Remote}`: the peer's protocols on the connection all ended. */
    case DemotedToColdRemote

    /** `TimeoutExpired`: the outbound idle timeout of an outbound duplex connection fired. */
    case TimeoutExpired

    /** `SelfConn`: our own outbound connection reached our listener; its inbound side takes the
      * entry (`includeInboundConnectionImpl`, `ConnectionManager/Core.hs:845-855`).
      */
    case SelfConn

    /** `SelfConn⁻¹`: the outbound side takes back an entry the inbound side took (`Core.hs`
      * 1552-1561 for an unnegotiated one, 1633-1652 for a negotiated one).
      */
    case SelfConnReversed

    /** `SelfConnNegotiated^{flow}_{Inbound}`: the inbound side of a self-connection negotiated
      * `flow` (`Core.hs:924-930`).
      */
    case SelfConnNegotiated(flow: DataFlow)

    /** `Commit^{flow}`: an idle connection is closed; it enters time-wait. */
    case Commit

    /** A handshake that agreed no version: the connection enters time-wait
      * (`computeConnStateOnError`, `HandshakeFailure` in `classifyHandlerError`).
      */
    case HandshakeFailed

    /** A failure ends the connection from any live state, with no time-wait (the reference's
      * implicit move to `TerminatedState`).
      */
    case Fail

    /** `Terminate`: the time-wait of a terminating connection is over. */
    case Released

  /** The state a new connection starts in (`Reserve` and `Accepted`). */
  def initial(provenance: Provenance): ConnectionState = provenance match
    case Provenance.Outbound => ReservedOutbound
    case Provenance.Inbound  => Unnegotiated(Provenance.Inbound)

  /** An event the state does not allow. */
  final case class Illegal(from: ConnectionState, event: Event):
    def message: String = s"connection in state ${from.abstractName} cannot take $event"

  def transition(state: ConnectionState, event: Event): Either[Illegal, ConnectionState] =
    import DataFlow.{Duplex as DuplexFlow, Unidirectional}
    import TimeoutExpired.{Expired, Ticking}
    (state, event) match
      case (ReservedOutbound, Event.Connected) => Right(Unnegotiated(Provenance.Outbound))
      case (ReservedOutbound, Event.Overwritten) => Right(Unnegotiated(Provenance.Inbound))
      case (Unnegotiated(Provenance.Outbound), Event.Negotiated(Unidirectional)) =>
        Right(OutboundUni)
      case (Unnegotiated(Provenance.Outbound), Event.Negotiated(DuplexFlow)) =>
        Right(OutboundDup(Ticking))
      case (Unnegotiated(Provenance.Inbound), Event.Negotiated(flow)) => Right(InboundIdle(flow))
      case (InboundIdle(DuplexFlow), Event.AwakeLocal)                => Right(OutboundDup(Ticking))
      case (Inbound(DuplexFlow), Event.PromotedToWarmLocal)           => Right(Duplex)
      case (OutboundUni, Event.DemotedToColdLocal) => Right(OutboundIdle(Unidirectional))
      case (OutboundDup(Ticking), Event.DemotedToColdLocal) => Right(InboundIdle(DuplexFlow))
      case (OutboundDup(Expired), Event.DemotedToColdLocal) => Right(OutboundIdle(DuplexFlow))
      case (Duplex, Event.DemotedToColdLocal)               => Right(Inbound(DuplexFlow))
      case (OutboundDup(_), Event.AwakeRemote)              => Right(Duplex)
      case (InboundIdle(flow), Event.AwakeRemote)           => Right(Inbound(flow))
      case (OutboundIdle(DuplexFlow), Event.AwakeRemote)    => Right(Inbound(DuplexFlow))
      case (Duplex, Event.DemotedToColdRemote)              => Right(OutboundDup(Ticking))
      case (Inbound(flow), Event.DemotedToColdRemote)       => Right(InboundIdle(flow))
      // `demotedToColdRemote` leaves these as they are (`Core.hs:2286-2305`); for an idle inbound
      // connection of either flow, although `verifyAbstractTransition` admits only the duplex one
      case (OutboundDup(_) | OutboundIdle(_) | InboundIdle(_), Event.DemotedToColdRemote) =>
        Right(state)
      case (Unnegotiated(Provenance.Outbound), Event.SelfConn) => Right(Unnegotiated(Provenance.Inbound))
      case (Unnegotiated(Provenance.Inbound), Event.SelfConnReversed) =>
        Right(Unnegotiated(Provenance.Outbound))
      case (InboundIdle(Unidirectional), Event.SelfConnReversed) => Right(OutboundUni)
      case (InboundIdle(DuplexFlow), Event.SelfConnReversed)     => Right(OutboundDup(Ticking))
      case (Unnegotiated(Provenance.Outbound), Event.SelfConnNegotiated(flow)) => Right(InboundIdle(flow))
      case (OutboundDup(Ticking), Event.TimeoutExpired)                     => Right(OutboundDup(Expired))
      case (InboundIdle(_), Event.Commit) | (OutboundIdle(_), Event.Commit) => Right(Terminating)
      case (Unnegotiated(_), Event.HandshakeFailed)                         => Right(Terminating)
      case (s, Event.Fail) if s.isLive                                      => Right(Terminated)
      case (Terminating, Event.Released)                                    => Right(Terminated)
      case (s, e)                                                           => Left(Illegal(s, e))

  /** The events that close a connection in `state`. With `timeWait` it is an orderly end (released
    * by its owner, or a handshake that agreed no version): the reference's demotions to idle, then
    * `Commit`, which enters `Terminating` (time-wait); a refused handshake enters it directly.
    * Without, it is a failure, which goes to `Terminated` at once. A connection that is not live
    * needs none.
    */
  def closing(state: ConnectionState, timeWait: Boolean): Vector[Event] =
    import Event.*
    state match
      case Terminating | Terminated       => Vector.empty
      case ReservedOutbound               => Vector(Fail)
      case _ if !timeWait                 => Vector(Fail)
      case Unnegotiated(_)                => Vector(HandshakeFailed)
      case OutboundUni | OutboundDup(_)   => Vector(DemotedToColdLocal, Commit)
      case OutboundIdle(_)                => Vector(Commit)
      case InboundIdle(_)                 => Vector(Commit)
      case Inbound(_)                     => Vector(DemotedToColdRemote, Commit)
      case Duplex                         => Vector(DemotedToColdLocal, DemotedToColdRemote, Commit)

  /** Apply `events` in order. */
  def through(state: ConnectionState, events: Vector[Event]): Either[Illegal, ConnectionState] =
    events.foldLeft[Either[Illegal, ConnectionState]](Right(state))((s, e) => s.flatMap(transition(_, e)))

  /** Whether the reference accepts the move from `from` to `to`: `verifyAbstractTransition`
    * (`Test/Ouroboros/Network/ConnectionManager/Utils.hs`, ouroboros-network `2fb69829`), with
    * `None` for a connection not in the table (`UnknownConnectionSt`). Its self-connection cases
    * (`SelfConn`, `SelfConn⁻¹`, `SelfConnNegotiated`) are included.
    */
  def valid(from: Option[ConnectionState], to: Option[ConnectionState]): Boolean =
    import DataFlow.{Duplex as DuplexFlow, Unidirectional}
    import Provenance.{Inbound as In, Outbound as Out}
    import TimeoutExpired.{Expired, Ticking}
    (from, to) match
      case (Some(Terminated) | None, Some(ReservedOutbound))                  => true
      case (Some(ReservedOutbound), Some(Unnegotiated(Out)))                  => true
      case (Some(Unnegotiated(Out)), Some(OutboundUni))                       => true
      case (Some(Unnegotiated(Out)), Some(OutboundDup(Ticking)))              => true
      case (Some(Unnegotiated(_)), Some(Terminating))                         => true
      case (Some(OutboundUni), Some(OutboundIdle(Unidirectional)))            => true
      case (Some(OutboundDup(Ticking)), Some(OutboundDup(Expired)))           => true
      case (Some(OutboundDup(Expired)), Some(OutboundIdle(DuplexFlow)))       => true
      case (Some(OutboundIdle(a)), Some(OutboundIdle(b)))                     => a == b
      case (Some(OutboundDup(Ticking)), Some(InboundIdle(DuplexFlow)))        => true
      case (Some(InboundIdle(DuplexFlow)), Some(OutboundDup(Ticking)))        => true
      case (Some(OutboundDup(_)), Some(Duplex))                               => true
      case (Some(OutboundDup(a)), Some(OutboundDup(b)))                       => a == b
      case (Some(Inbound(DuplexFlow)), Some(Duplex))                          => true
      case (Some(Duplex), Some(OutboundDup(Ticking)))                         => true
      case (Some(Duplex), Some(Inbound(DuplexFlow)))                          => true
      case (Some(Terminated) | None, Some(Unnegotiated(In)))                  => true
      case (Some(ReservedOutbound), Some(Unnegotiated(In)))                   => true
      case (Some(Unnegotiated(In)), Some(InboundIdle(_)))                     => true
      case (Some(Unnegotiated(Out)), Some(InboundIdle(_)))                    => true
      case (Some(InboundIdle(DuplexFlow)), Some(InboundIdle(DuplexFlow)))     => true
      case (Some(InboundIdle(a)), Some(Inbound(b)))                           => a == b
      case (Some(InboundIdle(_)), Some(Terminating))                          => true
      case (Some(Inbound(a)), Some(InboundIdle(b)))                           => a == b
      case (Some(OutboundIdle(DuplexFlow)), Some(Inbound(DuplexFlow)))        => true
      case (Some(OutboundIdle(_)), Some(Terminating))                         => true
      case (Some(Unnegotiated(Out)), Some(Unnegotiated(In)))                  => true
      case (Some(Unnegotiated(In)), Some(Unnegotiated(Out)))                  => true
      case (Some(InboundIdle(Unidirectional)), Some(OutboundUni))             => true
      case (Some(Terminating), Some(Terminated))                              => true
      case (Some(Terminated), Some(Terminated))                               => false
      case (_, Some(Terminated))                                              => true
      case (None, None)                                                       => false
      case (_, None)                                                          => true
      case (Some(Terminating), Some(Unnegotiated(In)))                        => true
      case _                                                                  => false

  /** The reference's `ConnectionManagerCounters` (`ConnectionManager/Types.hs:694-703`), counted
    * per state as `connectionStateToCounters` does (`ConnectionManager/Core.hs:173-212`): a
    * connection counts by what it is used for now, so a duplex connection both sides use counts
    * as inbound and as outbound, a reservation counts nothing and a closing one counts only as
    * terminating.
    */
  final case class Counters(
      fullDuplex: Int,
      duplex: Int,
      unidirectional: Int,
      inbound: Int,
      outbound: Int,
      terminating: Int
  ):
    def +(o: Counters): Counters =
      Counters(
        fullDuplex + o.fullDuplex,
        duplex + o.duplex,
        unidirectional + o.unidirectional,
        inbound + o.inbound,
        outbound + o.outbound,
        terminating + o.terminating
      )

  object Counters:
    val zero: Counters = Counters(0, 0, 0, 0, 0, 0)

    private val inboundConn = zero.copy(inbound = 1)
    private val outboundConn = zero.copy(outbound = 1)
    private val duplexConn = zero.copy(duplex = 1)
    private val unidirectionalConn = zero.copy(unidirectional = 1)

    private def flowConn(flow: DataFlow): Counters = flow match
      case DataFlow.Duplex         => duplexConn
      case DataFlow.Unidirectional => unidirectionalConn

    /** `connectionStateToCounters`. */
    def ofState(state: ConnectionState): Counters = state match
      case ReservedOutbound                    => zero
      case Unnegotiated(Provenance.Inbound)    => inboundConn
      case Unnegotiated(Provenance.Outbound)   => outboundConn
      case OutboundUni                         => unidirectionalConn + outboundConn
      case OutboundDup(_)                      => duplexConn + outboundConn
      case OutboundIdle(flow)                  => flowConn(flow) + outboundConn
      case InboundIdle(flow)                   => flowConn(flow) + inboundConn
      case Inbound(flow)                       => flowConn(flow) + inboundConn
      case Duplex => zero.copy(fullDuplex = 1) + duplexConn + inboundConn + outboundConn
      case Terminating                         => zero.copy(terminating = 1)
      case Terminated                          => zero

    /** `connectionManagerStateToCounters`. */
    def of(states: Iterable[ConnectionState]): Counters =
      states.foldLeft(zero)((c, s) => c + ofState(s))
