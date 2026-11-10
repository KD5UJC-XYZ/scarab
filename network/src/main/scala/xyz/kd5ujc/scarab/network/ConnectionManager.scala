// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.FlatMap
import cats.effect.{Async, Deferred, Poll, Ref, Resource}
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.effect.syntax.all.*
import cats.syntax.all.*
import com.comcast.ip4s.{Host, IpAddress, SocketAddress}
import fs2.concurrent.SignallingRef
import xyz.kd5ujc.scarab.network.selection.Rng
import xyz.kd5ujc.scarab.protocol.{ConnectionIdentityError, ConnectionState}
import xyz.kd5ujc.scarab.protocol.ConnectionState.{
  ConnectionType,
  Counters,
  DataFlow,
  Event,
  Provenance,
  TimeoutExpired
}
import scala.concurrent.duration.*

/** What the manager did, for the log. */
enum ConnectionEvent:
  case Opened(remote: String, provenance: Provenance)
  case Changed(remote: String, from: ConnectionState, to: ConnectionState)
  case Refused(error: ConnectionError)
  case Closed(remote: String, provenance: Provenance, reason: ConnectionEnd)

  /** The manager ended `pruned`, `number` of `candidates` inbound connections, to keep the accepted
    * connections within the hard limit (the reference's `TrPruneConnections`,
    * `ConnectionManager/Core.hs:765-768`).
    */
  case Pruned(pruned: Vector[String], number: Int, candidates: Int)

  /** The accept loop delays the next accept (the reference's
    * `ServerTraceAcceptConnectionRateLimiting`, `Server/RateLimiting.hs`).
    */
  case AcceptRateLimited(delay: DiffTime, connections: Int)

  /** The accept loop stopped at the hard limit (`ServerTraceAcceptConnectionHardLimit`). */
  case AcceptHardLimit(limit: Word32)

  /** The accept loop accepts again after a hard limit (`ServerTraceAcceptConnectionResume`). */
  case AcceptResumed(connections: Int)

trait ConnectionEvents[F[_]]:
  def emit(event: ConnectionEvent): F[Unit]

/** What the handshake of a connection produced: the reference's connection `Handle` (the mux and
  * the negotiated version data, `ConnectionHandler.hs`). The manager publishes it together with the
  * negotiated state, and an outbound acquisition that reuses the connection gets it.
  */
final case class Negotiated[F[_]](mux: Mux[F], agreed: HandshakeClient.Agreed):
  def version: Int = agreed.version
  def peerSharing: Boolean = agreed.data.peerSharing

  /** `connectionDataFlow`: duplex unless either side asked for initiator-only. */
  def dataFlow: DataFlow =
    if agreed.data.initiatorOnly then DataFlow.Unidirectional else DataFlow.Duplex

  /** The transport's identity, captured once by the mux (none for in-memory bearers). */
  def identity: Option[Either[ConnectionIdentityError, ConnectionId]] = mux.connectionId

/** The result of an operation the reference defines on some states only (`OperationResult`,
  * `ConnectionManager/Types.hs`): its value, or the state it does not apply to (`None` for a
  * connection no longer in the table, the reference's `UnknownConnectionSt`).
  */
enum OperationResult[+A]:
  case Success(value: A)
  case Unsupported(state: Option[ConnectionState])

/** What `releaseInbound` did to a connection whose remote side stayed idle
  * (`DemotedToColdRemoteTr`, `ConnectionManager/Types.hs`): closed it, or kept it because the
  * outbound side uses it.
  */
enum RemoteRelease:
  case Commit, Keep

/** One connection of the manager's table, as its users see it: the owner of its transport (an
  * accept loop handler, or the dialling fiber), the outbound side that uses it and the tracker of
  * the peer's activity. Every operation acts on this generation of the entry only; a connection
  * that replaced it under the same address is another one. Each change goes through the pure
  * [[ConnectionState.transition]].
  */
trait Connection[F[_]]:
  def remote: SocketAddress[IpAddress]

  /** This generation's identity, as [[ConnectionManager.Snapshot]] lists it. */
  def id: Long

  /** The side whose handler owns the transport: `Inbound` for an accepted connection. */
  def provenance: Provenance
  def state: F[ConnectionState]

  /** `Connected`: the transport of a reserved outbound connection is up, from `local` (none for a
    * transport without one). The entry is keyed by it from now on (`updateLocalAddr`); a connection
    * that already has that identity refuses this one (`Core.hs` 1495-1513).
    */
  def connected(local: Option[ConnectionId.Endpoint])(using Raise[F, ConnectionError]): F[Unit]

  /** The handshake agreed `handle` 's data flow; the handle is published with the negotiated state
    * (`Negotiated^{flow}`, `Core.hs` 873-903 and 1588-1660).
    */
  def negotiated(handle: Negotiated[F])(using Raise[F, ConnectionError]): F[Unit]

  /** The handshake agreed no version: the connection is taken out of the table through
    * `Terminating` (`terminateInboundWithErrorOrQuery`, `terminateOutboundWithErrorOrQuery`).
    */
  def handshakeFailed(error: NetworkError): F[Unit]

  /** `promotedToWarmRemote`: the peer started a responder (`Core.hs` 2048-2240). */
  def promotedToWarmRemote: F[OperationResult[ConnectionState]]

  /** `demotedToColdRemote`: all the peer's responders ended (`Core.hs` 2242-2320). */
  def demotedToColdRemote: F[OperationResult[ConnectionState]]

  /** `releaseInboundConnection`: the remote side stayed idle for the protocol idle timeout
    * (`Core.hs` 1110-1248).
    */
  def releaseInbound: F[OperationResult[RemoteRelease]]

  /** `releaseOutboundConnection`: the outbound side no longer uses the connection (`Core.hs`
    * 1889-2033). An idle outbound connection is closed after
    * [[ConnectionManager.Limits.outboundIdle]] unless the peer wakes it up first; this waits for
    * that.
    */
  def releaseOutbound: F[OperationResult[ConnectionState]]

  /** `waitForOutboundDemotion` (`Core.hs` 438-457): completes once the outbound side holds the
    * connection no more.
    */
  def awaitOutboundDemotion: F[Unit]

  /** Completes when the manager ends the connection (a commit, the outbound idle timeout or a
    * prune): its owner then lets go of the transport, as the reference cancels the connection's
    * thread.
    */
  def stopped: F[ConnectionEnd]

  /** Record why the connection ended, for the log; the first reason stands. */
  def close(reason: ConnectionEnd): F[Unit]

  /** Completes once the owner of the transport released the connection. */
  def closed: F[Unit]

/** What an outbound acquisition got (`Connected` with `Here`, or `Nowhere`, in
  * `acquireOutboundConnectionImpl`).
  */
enum Acquired[F[_]]:
  /** An existing connection the outbound side now uses: its owner keeps the transport. */
  case Reused(connection: Connection[F], handle: Negotiated[F])

  /** A reservation: the caller connects, negotiates and owns the transport. */
  case Reserved(connection: Connection[F])

/** The node's table of node-to-node connections, the reference's connection manager
  * (ouroboros-network `2fb69829`, `ConnectionManager/Core.hs`). One connection serves both sides of
  * a peer: the outbound side reuses a duplex connection the peer opened, and the peer's protocols
  * run on a duplex connection we opened. Entries are keyed by the remote and the local address
  * (`ConnMap.hs` 45-50): a peer that dials from its listening port is the address we would dial,
  * and one that dials from an ephemeral port is another connection.
  *
  * The owner of a connection's transport holds its resource ([[include]], or [[acquire]]'s
  * reservation); releasing it in any way ends the connection: through time-wait when it was left in
  * `Terminating`, at once otherwise, as the reference's connection thread cleans up (`Core.hs`
  * 590-702).
  */
trait ConnectionManager[F[_]]:
  /** `includeInboundConnection`: admit a connection accepted from `remote` on `local` (none for a
    * transport without one) in state `Unnegotiated Inbound`, refused at the hard limit of accepted
    * connections and the per-address limit (`Core.hs` 780-870).
    */
  def include(remote: SocketAddress[IpAddress], local: Option[ConnectionId.Endpoint])(using
      Raise[F, ConnectionError]
  ): Resource[F, Connection[F]]

  /** `acquireOutboundConnection` for a peer whose provenance is `provenance` (`Core.hs` 1250-1755):
    * reuse the peer's duplex connection, wait while a connection to it negotiates or terminates, or
    * reserve one to dial. A peer of inbound provenance is never dialled. The resource of a
    * reservation is the dialled connection's; that of a reused one holds nothing (its outbound use
    * ends with [[Connection.releaseOutbound]]).
    */
  def acquire(remote: SocketAddress[IpAddress], provenance: Provenance)(using
      Raise[F, ConnectionError]
  ): Resource[F, Acquired[F]]

  /** A live connection to or from `remote`, if any. */
  def lookup(remote: SocketAddress[IpAddress]): F[Option[ConnectionManager.Live[F]]]

  def snapshot: F[ConnectionManager.Snapshot]

  /** The number of inbound connections, as the accept loop's rate limit reads it (the reference's
    * `numberOfConnections`, `icmNumberOfConnections`).
    */
  def inboundCount: F[Int]

  /** Completes once fewer than `limit` inbound connections are live (the reference's STM `check` in
    * `runConnectionRateLimits`).
    */
  def awaitInboundBelow(limit: Long): F[Unit]

object ConnectionManager:
  /** The local side of a connection's key (`LocalAddr`, `ConnMap.hs`). */
  enum LocalAddress:
    /** `UnknownLocalAddr`: an outbound reservation that has not connected yet. */
    case Unknown

    /** `LocalAddr`: the transport's local address. */
    case At(address: ConnectionId.Endpoint)

    /** A transport without addresses (an in-memory bearer); a connection is still one entry. */
    case Unidentified

  type Key = (SocketAddress[IpAddress], LocalAddress)

  /** @param accepted
    *   The reference's accepted-connection limits; the hard limit refuses the connection that would
    *   exceed it, the soft limit and the delay rate limit the accept loop
    *   ([[AcceptedConnectionsLimit]]), and pruning keeps the inbound connections within it.
    * @param maxOutbound
    *   The most live connections we reserved or dialled; reusing a peer's connection opens none.
    *   The reference has no such limit (its outbound governor's targets bound it).
    * @param maxInboundPerAddress
    *   The most live accepted connections from one IP address. The reference has no such limit.
    *   Deviation approved by the owner on 2026-10-25: a per-address inbound limit (default 64) on
    *   top of the reference's global accept limits, protecting inbound slots from a single address;
    *   the reference has no per-address limit (ADR 0008).
    * @param timeWait
    *   How long a connection left in `Terminating` is held after its transport closed
    *   (`defaultTimeWaitTimeout = 60`, `Core.hs:271-274`): meanwhile an outbound acquisition of its
    *   address waits, and an inbound connection with its identity replaces it.
    * @param outboundIdle
    *   How long an `OutboundIdle` connection stays before it is closed (`outboundIdleTimeout`,
    *   `Core.hs:135-138`), which the diffusion sets to the protocol idle timeout (5 s,
    *   `Diffusion.hs:508`; `defaultProtocolIdleTimeout`, `Core.hs:276-280`).
    * @param seed
    *   The seed of the random choices: which of several connections to a peer an acquisition takes
    *   (`lookupByRemoteAddr`) and the scores of the prune policy.
    */
  final case class Limits(
      accepted: AcceptedConnectionsLimit = AcceptedConnectionsLimit.default,
      maxOutbound: Int = 64,
      maxInboundPerAddress: Int = 64,
      timeWait: FiniteDuration = 60.seconds,
      outboundIdle: FiniteDuration = 5.seconds,
      seed: Long = 0L
  )

  final case class Live[F[_]](
      remote: SocketAddress[IpAddress],
      state: ConnectionState,
      closed: F[Unit]
  )

  /** One entry of the table: its remote, its state and its generation ([[Connection.id]]). */
  final case class Listed(remote: SocketAddress[IpAddress], state: ConnectionState, id: Long)

  final case class Snapshot(counters: Counters, listed: Vector[Listed]):
    def connections: Vector[(SocketAddress[IpAddress], ConnectionState)] =
      listed.map(l => l.remote -> l.state)

  /** The IP address of `address` (the first one a name resolves to). */
  def resolve[F[_]: Async](address: SocketAddress[Host]): F[SocketAddress[IpAddress]] =
    address.host
      .resolve[F](using fs2.io.net.Network.forAsync[F].dns, Async[F])
      .map(ip => SocketAddress(ip, address.port))

  /** `outer`, which first takes `connection` out of the table when the handshake agreed no version
    * ([[Connection.handshakeFailed]]). Any other error is raised as is.
    */
  def recordingRefusal[F[_]: FlatMap](connection: Connection[F])(using
      outer: Raise[F, NetworkError]
  ): Raise[F, NetworkError] =
    new Raise[F, NetworkError]:
      def functor = outer.functor
      def raise[E <: NetworkError, A](e: E): F[A] = e match
        case refused: (NetworkError.HandshakeRefused | NetworkError.VersionMismatch) =>
          connection.close(ConnectionEnd.Failed(refused)) *> connection.handshakeFailed(refused) *>
            outer.raise[NetworkError, A](e)
        case _ => outer.raise[NetworkError, A](e)

  /** Which side's handler holds a connection handle. */
  private enum Side:
    case Inbound, Outbound

  /** How a generation ended, kept apart from the table as the reference keeps a connection's state
    * variable after it leaves the map: a waiter learns it even after the entry is gone.
    */
  private final case class Ended(state: ConnectionState, cause: Option[NetworkError])

  private final case class Entry[F[_]](
      id: Long,
      origin: Provenance,
      state: ConnectionState,
      handle: Option[Negotiated[F]],
      stop: Deferred[F, ConnectionEnd],
      closed: Deferred[F, Unit],
      ended: Deferred[F, Ended],
      reason: Option[ConnectionEnd],
      releasedAt: Option[FiniteDuration] = None
  ):
    /** Released and in time-wait still at `now`. */
    def waiting(now: FiniteDuration, timeWait: FiniteDuration): Boolean =
      releasedAt.exists(now - _ < timeWait)

  private final case class Table[F[_]](entries: Map[Key, Entry[F]], version: Long, rng: Rng):
    def locate(id: Long): Option[(Key, Entry[F])] = entries.find(_._2.id == id)
    def put(key: Key, entry: Entry[F]): Table[F] = copy(entries = entries.updated(key, entry))

  /** What a change of the table asks to be done after it: the log, and the connections to end with
    * the reason kept for the log and the cause their mux is stopped with.
    */
  /** What a change asks for: the log, the connections to stop, the generations whose end is
    * published, and the released generations whose owners are told to let go of their transports.
    * Everything but the log is guaranteed: the reference writes a connection's terminal state in
    * the transaction that changes it, whatever its trace then does (`Core.hs` 605-641).
    */
  private final case class Effects[F[_]](
      events: Vector[ConnectionEvent],
      stops: Vector[(Entry[F], ConnectionEnd, NetworkError)],
      ends: Vector[(Entry[F], Ended)],
      released: Vector[Entry[F]]
  ):
    def ++(o: Effects[F]): Effects[F] =
      new Effects(events ++ o.events, stops ++ o.stops, ends ++ o.ends, released ++ o.released)

  private object Effects:
    def apply[F[_]](
        events: Vector[ConnectionEvent],
        stops: Vector[(Entry[F], ConnectionEnd, NetworkError)]
    ): Effects[F] = new Effects[F](events, stops, Vector.empty, Vector.empty)
    def none[F[_]]: Effects[F] = Effects[F](Vector.empty, Vector.empty)
    def log[F[_]](events: Vector[ConnectionEvent]): Effects[F] = Effects[F](events, Vector.empty)
    def ended[F[_]](entry: Entry[F], state: ConnectionState, cause: Option[NetworkError]) =
      new Effects[F](Vector.empty, Vector.empty, Vector((entry, Ended(state, cause))), Vector.empty)

  /** How an acquisition goes on after one look at the table. */
  private enum Dispatch[F[_]]:
    case Done(acquired: Acquired[F])
    case Refuse(error: ConnectionError)

    /** `There`: wait for this connection's handshake (`Core.hs` 1301-1307). */
    case There(entry: Entry[F])

    /** `retry`: wait for the table to change (`Core.hs` 1379-1388). */
    case Retry()

  /** The changes `events` make to `entry`, or the first one its state does not allow. */
  private def walk[F[_]](
      remote: SocketAddress[IpAddress],
      entry: Entry[F],
      events: Event*
  ): Either[ConnectionState.Illegal, (Entry[F], Vector[ConnectionEvent])] =
    events
      .foldLeft[Either[ConnectionState.Illegal, (ConnectionState, Vector[ConnectionEvent])]](
        Right((entry.state, Vector.empty))
      )((walked, event) =>
        walked.flatMap((current, log) =>
          ConnectionState
            .transition(current, event)
            .map(next =>
              (
                next,
                if next == current then log
                else log :+ ConnectionEvent.Changed(remote.toString, current, next)
              )
            )
        )
      )
      .map((reached, log) => (entry.copy(state = reached), log))

  private def outboundIdle(state: ConnectionState): Boolean = state match
    case ConnectionState.OutboundIdle(_) => true
    case _                               => false

  def create[F[_]: Async](
      limits: Limits = Limits(),
      events: Option[ConnectionEvents[F]] = None
  ): F[ConnectionManager[F]] =
    (SignallingRef.of[F, Table[F]](Table(Map.empty, 0L, Rng(limits.seed))), Ref.of[F, Long](0L))
      .mapN((table, ids) => new Impl(table, ids, limits, events))

  private final class Impl[F[_]](
      table: SignallingRef[F, Table[F]],
      ids: Ref[F, Long],
      limits: Limits,
      events: Option[ConnectionEvents[F]]
  )(using F: Async[F])
      extends ConnectionManager[F]:
    // `includeInboundConnectionImpl` accepts while `numberOfCons + 1 <= hardLimit`
    private val hardLimit = limits.accepted.acceptedConnectionsHardLimit.toLong
    // the cause a commit stops the mux with: the protocol idle timeout, which is also the outbound
    // idle timeout (`Diffusion.hs` 508 and 752 set both to `dcProtocolIdleTimeout`)
    private val idleCause = NetworkError.ProtocolIdleTimeout(limits.outboundIdle)

    private def emit(event: ConnectionEvent): F[Unit] = events.traverse_(_.emit(event))

    private def refuse[A](error: ConnectionError)(using Raise[F, ConnectionError]): F[A] =
      emit(ConnectionEvent.Refused(error)) *> error.raise[F, A]

    private def counters(t: Table[F]): Counters = Counters.of(t.entries.values.map(_.state))

    /** Run what a change asked for: the log, then the connections to end. Their owners let go of
      * their transports, and a published mux is stopped so that whoever uses it sees the end.
      */
    private def run(effects: Effects[F]): F[Unit] =
      val ends = effects.ends.traverse_((entry, end) => entry.ended.complete(end).void) *>
        effects.released.traverse_(_.stop.complete(ConnectionEnd.Released).void) *>
        effects.stops.traverse_ { (entry, end, cause) =>
          entry.ended.complete(Ended(ConnectionState.Terminated, None)).void *>
            entry.stop.complete(end).void *> entry.handle.traverse_(_.mux.abort(cause))
        }
      effects.events.traverse_(emit).guarantee(ends)

    /** Remove the entries whose time-wait has ended (`TerminatingState → TerminatedState`, then out
      * of the table, `Core.hs` 666-702).
      */
    private def purge(now: FiniteDuration)(t: Table[F]): (Table[F], Vector[ConnectionEvent]) =
      val (over, kept) =
        t.entries.partition((_, e) => e.releasedAt.isDefined && !e.waiting(now, limits.timeWait))
      (
        if over.isEmpty then t else t.copy(entries = kept),
        over.toVector.map((k, _) =>
          ConnectionEvent
            .Changed(k._1.toString, ConnectionState.Terminating, ConnectionState.Terminated)
        )
      )

    /** One atomic change of the table; its version moves only when an entry changed, so a waiter
      * wakes for a real change. The change and what it asks for (the log, the connections it ends)
      * are one step that cancellation cannot split, as the reference masks a transition with its
      * trace and its prune action (`Core.hs` 2048-2066, 703-778).
      */
    private def changing[A](f: (Table[F], FiniteDuration) => (Table[F], Effects[F], A)): F[A] =
      F.uncancelable { _ =>
        F.monotonic
          .flatMap { now =>
            table.modify { held =>
              val (purged, gone) = purge(now)(held)
              val (next, effects, result) = f(purged, now)
              val version = if next.entries eq held.entries then held.version else held.version + 1
              (next.copy(version = version), (Effects.log[F](gone) ++ effects, result))
            }
          }
          .flatMap((effects, result) => run(effects).as(result))
      }

    /** Wait until the table changed from `version`, or a time-wait ended. */
    private def changedFrom(version: Long): F[Unit] =
      (table.get, F.monotonic).tupled.flatMap { (t, now) =>
        val wake = t.entries.values
          .flatMap(_.releasedAt.map(at => (at + limits.timeWait - now).max(Duration.Zero)))
          .minOption
        F.race(
          table.waitUntil(_.version != version),
          wake.fold(F.never[Unit])(d => F.sleep(d + 1.millis))
        ).void
      }

    /** `mkPruneAction` (`Core.hs` 703-778) with the reference's `prunePolicy`
      * (`Diffusion/Policies.hs:284-305`): of the inbound connections and `self` in its next state,
      * end the `number` with the lowest (upstream, random score, connection type), upstream last.
      * Returns the table with the pruned ones `Terminated`, whether `self` is among them, and the
      * effects; the caller writes `self` 's next state when it is spared.
      */
    private def pruning(
        t: Table[F],
        self: Key,
        next: ConnectionState,
        number: Int
    ): (Table[F], Boolean, Effects[F]) =
      val others = t.entries.toVector.flatMap((k, e) =>
        if k != self && ConnectionState.isInboundConn(e.state) then
          ConnectionType.of(e.state).map(k -> _).toVector
        else Vector.empty
      )
      val choices = (others ++ ConnectionType.of(next).map(self -> _).toVector)
        .sortBy((k, _) => (k._1.toString, k._2.toString))
      val (scored, rng) =
        choices.foldLeft((Vector.empty[(Key, ConnectionType, Long)], t.rng)) {
          case ((acc, g), (k, kind)) =>
            val (score, after) = g.nextLong
            (acc :+ ((k, kind, score)), after)
        }
      val chosen = scored
        .sortBy((_, kind, score) => (kind.upstream, score, kind))
        .take(number)
        .map(_._1)
        .toSet
      val ended = t.entries.toVector.filter((k, e) => chosen(k) && e.state.isLive)
      val entries = ended.foldLeft(t.entries)((m, ke) =>
        m.updated(ke._1, ke._2.copy(state = ConnectionState.Terminated))
      )
      val effects = Effects[F](
        events = ended.map((k, e) =>
          ConnectionEvent.Changed(k._1.toString, e.state, ConnectionState.Terminated)
        ) :+ ConnectionEvent
          .Pruned(chosen.toVector.map(_._1.toString).sorted, number, choices.size),
        stops = ended.map { (k, e) =>
          val why = ConnectionError.Pruned(k._1.toString)
          (
            e,
            ConnectionEnd.Refused(why),
            NetworkError.BearerClosed(NetworkError.Closure.AbortedBy(why))
          )
        }
      )
      (t.copy(entries = entries, rng = rng), chosen(self), effects)

    /** How many connections must go if one more counted inbound: `numberOfConns + 1 - hardLimit`
      * (`Core.hs` 1906-1915, 2079-2088).
      */
    private def excess(t: Table[F]): Int = (counters(t).inbound.toLong + 1L - hardLimit).toInt

    /** `entry` moved by `events`, or left as it is with `otherwise` when its state refuses them. */
    private def moving[A](t: Table[F], key: Key, entry: Entry[F], events: Event*)(
        result: Entry[F] => A,
        otherwise: => A
    ): (Table[F], Effects[F], A) =
      walk(key._1, entry, events*) match
        case Right((next, log)) => (t.put(key, next), Effects.log[F](log), result(next))
        case Left(_)            => (t, Effects.none[F], otherwise)

    /** One generation of an entry, as its owner and users hold it. */
    private final class Handle(entry: Entry[F], at: SocketAddress[IpAddress], side: Side)
        extends Connection[F]:
      val id = entry.id
      def remote = at
      def provenance = side match
        case Side.Inbound  => Provenance.Inbound
        case Side.Outbound => Provenance.Outbound
      def state =
        table.get.map(
          _.locate(id)
            .filter(_._2.releasedAt.isEmpty)
            .fold[ConnectionState](ConnectionState.Terminated)(_._2.state)
        )

      private def unsupported[A](e: Entry[F]): OperationResult[A] =
        OperationResult.Unsupported(Some(e.state))

      def connected(local: Option[ConnectionId.Endpoint])(using Raise[F, ConnectionError]) =
        // `updateLocalAddr` first, as a step of its own (`Core.hs` 1500-1513), then `Connected`
        // (1540-1575): an accepted connection with the same identity may come in between
        val located = local.fold(F.unit)(address =>
          changing { (t, _) =>
            t.locate(id) match
              case Some((key @ (_, LocalAddress.Unknown), entry)) =>
                val moved: Key = (at, LocalAddress.At(address))
                t.entries.get(moved) match
                  case Some(other) =>
                    (
                      t,
                      Effects.none[F],
                      Left(ConnectionError.AlreadyConnected(at.toString, other.state))
                    )
                  case None =>
                    (
                      t.copy(entries = t.entries - key + (moved -> entry)),
                      Effects.none[F],
                      Right(())
                    )
              case _ => (t, Effects.none[F], Right(()))
          }.flatMap(_.fold(refuse[Unit](_), F.pure))
        )
        located *> changing { (t, _) =>
          t.locate(id) match
            case Some((key, entry)) =>
              entry.state match
                case ConnectionState.ReservedOutbound =>
                  moving(t, key, entry, Event.Connected)(_ => (), ())
                // `SelfConn⁻¹`: an accepted connection with our identity took the entry
                case ConnectionState.Unnegotiated(Provenance.Inbound) =>
                  moving(t, key, entry, Event.SelfConnReversed)(_ => (), ())
                case _ => (t, Effects.none[F], ())
            case None => (t, Effects.none[F], ())
        }

      def negotiated(agreed: Negotiated[F])(using Raise[F, ConnectionError]) =
        changing { (t, _) =>
          t.locate(id) match
            case None =>
              (
                t,
                Effects.none[F],
                Left(ConnectionError.Disconnected(at.toString, ConnectionState.Terminated, None))
              )
            case Some((key, entry)) =>
              val flow = agreed.dataFlow
              // the events, and whether the state takes this handle: a state the other side of a
              // self-connection reached keeps the handle it has (`Core.hs` 885-887, 1624-1652)
              val events: Either[ConnectionError, (Vector[Event], Boolean)] =
                (side, entry.state) match
                  case (_, ConnectionState.Terminating | ConnectionState.Terminated) =>
                    Left(ConnectionError.Disconnected(at.toString, entry.state, None))
                  // the common cases, `Core.hs` 913-930 and 1588-1622
                  case (Side.Inbound, ConnectionState.Unnegotiated(Provenance.Inbound)) =>
                    Right((Vector(Event.Negotiated(flow, Provenance.Inbound)), true))
                  case (Side.Outbound, ConnectionState.Unnegotiated(Provenance.Outbound)) =>
                    Right((Vector(Event.Negotiated(flow, Provenance.Outbound)), true))
                  // a connection to ourselves. The inbound side: `SelfConnNegotiated` (913-930);
                  // the outbound side that won the race keeps it (932-934); an outbound idle one
                  // becomes inbound idle with its flow (936-945)
                  case (Side.Inbound, ConnectionState.Unnegotiated(Provenance.Outbound)) =>
                    Right((Vector(Event.SelfConnNegotiated(flow)), true))
                  case (
                        Side.Inbound,
                        ConnectionState.OutboundUni | ConnectionState.OutboundDup(_)
                      ) =>
                    Right((Vector.empty, false))
                  case (Side.Inbound, ConnectionState.OutboundIdle(_)) =>
                    Right((Vector(Event.SelfConnIdle), true))
                  // the outbound side writes its negotiated state whatever the provenance the
                  // accept left, in one move (1600-1622); `SelfConn'⁻¹` keeps the inbound side's
                  // handle (1624-1652)
                  case (Side.Outbound, ConnectionState.Unnegotiated(Provenance.Inbound)) =>
                    Right((Vector(Event.Negotiated(flow, Provenance.Outbound)), true))
                  case (Side.Outbound, ConnectionState.InboundIdle(_)) =>
                    Right((Vector(Event.SelfConnReversed), false))
                  case (Side.Outbound, other) =>
                    Left(ConnectionError.ForbiddenOperation(at.toString, other))
                  case (Side.Inbound, other) =>
                    Left(ConnectionError.ImpossibleState(at.toString, other))
              events.flatMap((moves, takes) =>
                walk(key._1, entry, moves*)
                  .leftMap(ConnectionError.IllegalTransition(at.toString, _))
                  .map((next, log) => (next, log, takes))
              ) match
                case Left(error) => (t, Effects.none[F], Left(error))
                case Right((next, log, takes)) =>
                  val kept = if takes then next.copy(handle = Some(agreed)) else next
                  (t.put(key, kept), Effects.log[F](log), Right(()))
        }.flatMap(_.fold(refuse[Unit](_), F.pure))

      // `computeConnStateOnError` for `HandshakeFailure` is `TerminatingState`, which keeps the
      // error, written in the transaction that deletes the entry from the table (`Core.hs`
      // 1080-1094, 1782-1838, 1000-1075): the generation's end is published before its removal
      // can be seen, so a waiter that finds it gone learns the error; the two are one step that
      // cancellation cannot split
      def handshakeFailed(error: NetworkError) = F.uncancelable { _ =>
        table.get.flatMap(
          _.locate(id).filter(_._2.state != ConnectionState.Terminated).traverse_ { (_, entry) =>
            entry.ended.complete(Ended(ConnectionState.Terminating, Some(error))).void
          }
        ) *> changing { (t, _) =>
          t.locate(id) match
            case Some((key, entry)) if entry.state != ConnectionState.Terminated =>
              val log = walk(key._1, entry, Event.HandshakeFailed).fold(_ => Vector.empty, _._2)
              (
                t.copy(entries = t.entries - key),
                Effects.log[F](
                  log :+ ConnectionEvent
                    .Changed(at.toString, ConnectionState.Terminating, ConnectionState.Terminated)
                ),
                ()
              )
            case _ => (t, Effects.none[F], ())
        }
      }

      def promotedToWarmRemote =
        changing { (t, _) =>
          t.locate(id) match
            case None => (t, Effects.none[F], OperationResult.Unsupported(None))
            case Some((key, entry)) =>
              entry.state match
                // `PromotedToWarm^{Duplex}_{Remote}` and `Awake^{Duplex}_{Remote}`: each may have
                // to prune to stay within the hard limit (2085-2170)
                case ConnectionState.OutboundDup(_) |
                    ConnectionState.OutboundIdle(DataFlow.Duplex) =>
                  walk(key._1, entry, Event.AwakeRemote) match
                    case Left(_) => (t, Effects.none[F], unsupported(entry))
                    case Right((next, log)) =>
                      val number = excess(t)
                      if number > 0 then
                        val (pruned, self, effects) = pruning(t, key, next.state, number)
                        if self then (pruned, effects, OperationResult.Success(entry.state))
                        else
                          (
                            pruned.put(key, next),
                            Effects.log[F](log) ++ effects,
                            OperationResult.Success(entry.state)
                          )
                      else
                        (
                          t.put(key, next),
                          Effects.log[F](log),
                          OperationResult.Success(entry.state)
                        )
                // `Awake^{dataFlow}_{Remote}` (2182-2192)
                case ConnectionState.InboundIdle(_) =>
                  moving(t, key, entry, Event.AwakeRemote)(
                    _ => OperationResult.Success(entry.state),
                    unsupported(entry)
                  )
                case ConnectionState.Inbound(_) | ConnectionState.Duplex =>
                  (t, Effects.none[F], OperationResult.Success(entry.state))
                case _ => (t, Effects.none[F], unsupported(entry))
        }

      def demotedToColdRemote =
        changing { (t, _) =>
          t.locate(id) match
            case None => (t, Effects.none[F], OperationResult.Unsupported(None))
            case Some((key, entry)) =>
              entry.state match
                // `DemotedToCold^{dataFlow}_{Remote}`, or left as it is (2286-2320)
                case ConnectionState.Inbound(_) | ConnectionState.Duplex |
                    ConnectionState.OutboundDup(_) | ConnectionState.OutboundIdle(_) |
                    ConnectionState.InboundIdle(_) =>
                  moving(t, key, entry, Event.DemotedToColdRemote)(
                    next => OperationResult.Success(next.state),
                    unsupported(entry)
                  )
                case _ => (t, Effects.none[F], unsupported(entry))
        }

      def releaseInbound =
        changing { (t, _) =>
          t.locate(id) match
            // notified late about a connection that has already ended (1121-1130)
            case None => (t, Effects.none[F], OperationResult.Success(RemoteRelease.Commit))
            case Some((key, entry)) =>
              def ending(events: Event*)(result: OperationResult[RemoteRelease]) =
                walk(key._1, entry, events*) match
                  case Right((next, log)) =>
                    (
                      t.put(key, next),
                      Effects[F](log, Vector((entry, ConnectionEnd.Released, idleCause))),
                      result
                    )
                  case Left(_) => (t, Effects.none[F], unsupported[RemoteRelease](entry))
              entry.state match
                // `TimeoutExpired`: the outbound side uses it, so it stays (1148-1157)
                case ConnectionState.OutboundDup(TimeoutExpired.Ticking) =>
                  moving(t, key, entry, Event.TimeoutExpired)(
                    _ => OperationResult.Success(RemoteRelease.Keep),
                    unsupported(entry)
                  )
                case ConnectionState.OutboundDup(TimeoutExpired.Expired) =>
                  (t, Effects.none[F], OperationResult.Success(RemoteRelease.Keep))
                // `Commit^{dataFlow}` (1188-1201)
                case ConnectionState.InboundIdle(_) =>
                  ending(Event.Commit)(OperationResult.Success(RemoteRelease.Commit))
                // the inbound governor should have demoted it first; closed at once (1203-1215)
                case ConnectionState.Inbound(_) =>
                  ending(Event.CommitInUse)(unsupported(entry))
                // likewise (1217-1229)
                case ConnectionState.Duplex =>
                  moving(t, key, entry, Event.DemotedToColdRemote)(
                    _ => unsupported(entry),
                    unsupported(entry)
                  )
                // an outbound release just before (1231-1239), or an unexpected state (1170-1186)
                case ConnectionState.Terminating | ConnectionState.OutboundIdle(_) =>
                  (t, Effects.none[F], OperationResult.Success(RemoteRelease.Commit))
                case _ => (t, Effects.none[F], unsupported(entry))
        }

      def releaseOutbound =
        changing { (t, _) =>
          t.locate(id) match
            // a connection that failed removed itself; nothing to do (1896-1899)
            case None =>
              (
                t,
                Effects.none[F],
                Left(OperationResult.Success[ConnectionState](ConnectionState.Terminated))
              )
            case Some((key, entry)) =>
              entry.state match
                // `DemotedToCold^{flow}_{Local}` into `OutboundIdle`, then its timeout (1924-1950)
                case ConnectionState.OutboundUni |
                    ConnectionState.OutboundDup(TimeoutExpired.Expired) =>
                  moving(t, key, entry, Event.DemotedToColdLocal)(
                    _ => Right(()),
                    Left(unsupported[ConnectionState](entry))
                  )
                // `DemotedToCold^{Duplex}_{Local}` into `InboundIdle`, or a prune (1952-1985). A
                // prune that spares this connection leaves its state as it was: the reference
                // writes the new state only when it does not prune
                case ConnectionState.OutboundDup(TimeoutExpired.Ticking) =>
                  walk(key._1, entry, Event.DemotedToColdLocal) match
                    case Left(_) => (t, Effects.none[F], Left(unsupported[ConnectionState](entry)))
                    case Right((next, log)) =>
                      val number = excess(t)
                      if number > 0 then
                        val (pruned, _, effects) = pruning(t, key, next.state, number)
                        (pruned, effects, Left(OperationResult.Success(entry.state)))
                      else
                        (
                          t.put(key, next),
                          Effects.log[F](log),
                          Left(OperationResult.Success(entry.state))
                        )
                // `DemotedToCold^{Duplex}_{Local}` into `Inbound` (2002-2014)
                case ConnectionState.Duplex =>
                  moving(t, key, entry, Event.DemotedToColdLocal)(
                    _ => Left(OperationResult.Success(entry.state)),
                    Left(unsupported[ConnectionState](entry))
                  )
                case ConnectionState.ReservedOutbound | ConnectionState.Unnegotiated(_) |
                    ConnectionState.Inbound(_) =>
                  (t, Effects.none[F], Left(unsupported[ConnectionState](entry)))
                case other => (t, Effects.none[F], Left(OperationResult.Success(other)))
        }.flatMap {
          case Left(result) => F.pure(result)
          case Right(())    => idle
        }

      /** The outbound idle timeout of an `OutboundIdle` connection (`Core.hs` 2017-2048): unless it
        * leaves that state first, it is closed (`Commit`, into `TerminatingState`) when the timeout
        * fires.
        */
      private def idle: F[OperationResult[ConnectionState]] =
        val left: F[ConnectionState] =
          table.waitUntil(_.locate(id).forall((_, e) => !outboundIdle(e.state))) *> state
        F.race(left, F.sleep(limits.outboundIdle)).flatMap {
          case Left(now) => F.pure(outcome(now))
          case Right(_) =>
            changing { (t, _) =>
              t.locate(id) match
                case Some((key, entry)) if outboundIdle(entry.state) =>
                  walk(key._1, entry, Event.Commit) match
                    case Right((next, log)) =>
                      (
                        t.put(key, next),
                        Effects[F](log, Vector((entry, ConnectionEnd.Released, idleCause))),
                        OperationResult.Success[ConnectionState](next.state)
                      )
                    case Left(_) => (t, Effects.none[F], unsupported[ConnectionState](entry))
                case Some((_, entry)) => (t, Effects.none[F], outcome(entry.state))
                case None             => (t, Effects.none[F], outcome(ConnectionState.Terminated))
            }
        }

      /** A connection that left `OutboundIdle` on its own: a success if it ended. */
      private def outcome(now: ConnectionState): OperationResult[ConnectionState] = now match
        case ConnectionState.Terminating | ConnectionState.Terminated =>
          OperationResult.Success(now)
        case other => OperationResult.Unsupported(Some(other))

      def awaitOutboundDemotion =
        F.tailRecM(()) { _ =>
          table.get.flatMap { t =>
            val demoted = t
              .locate(id)
              .forall((_, e) =>
                e.state match
                  case ConnectionState.InboundIdle(_) | ConnectionState.Inbound(_) |
                      ConnectionState.Terminated |
                      ConnectionState.Unnegotiated(Provenance.Inbound) =>
                    true
                  case _ => false
              )
            if demoted then F.pure(Right(())) else changedFrom(t.version).as(Left(()))
          }
        }

      def stopped = entry.stop.get

      def close(reason: ConnectionEnd) =
        table.update(t =>
          t.locate(id).fold(t) { (key, entry) =>
            if entry.reason.isEmpty then t.put(key, entry.copy(reason = Some(reason))) else t
          }
        )

      def closed = entry.closed.get

    /** The connection thread's cleanup (`Core.hs` 590-702): a connection left in `Terminating` is
      * held for the time-wait; any other is `Terminated` and removed at once.
      */
    private def release(
        id: Long,
        at: SocketAddress[IpAddress],
        closed: Deferred[F, Unit]
    ): F[Unit] =
      changing { (t, now) =>
        t.locate(id) match
          case None => (t, Effects.none[F], ())
          case Some((key, entry)) =>
            val closing = ConnectionEvent.Closed(
              at.toString,
              entry.origin,
              entry.reason.getOrElse(ConnectionEnd.Released)
            )
            // the generation's end, published and its owner let go whether or not its log is
            // written (`Core.hs` 605-641)
            val failure = entry.reason.collect { case ConnectionEnd.Failed(error) => error }
            val ending = new Effects[F](
              Vector.empty,
              Vector.empty,
              Vector((entry, Ended(ConnectionState.Terminated, failure))),
              Vector(entry)
            )
            entry.state match
              case ConnectionState.Terminating if limits.timeWait > Duration.Zero =>
                (
                  t.put(key, entry.copy(releasedAt = Some(now))),
                  Effects.log[F](Vector(closing)) ++ ending,
                  ()
                )
              case ConnectionState.Terminated =>
                (t.copy(entries = t.entries - key), Effects.log[F](Vector(closing)) ++ ending, ())
              case other =>
                (
                  t.copy(entries = t.entries - key),
                  Effects.log[F](
                    Vector(
                      ConnectionEvent.Changed(at.toString, other, ConnectionState.Terminated),
                      closing
                    )
                  ) ++ ending,
                  ()
                )
      }.guarantee(closed.complete(()).void)

    private def fresh(origin: Provenance): F[Entry[F]] =
      (ids.updateAndGet(_ + 1), Deferred[F, ConnectionEnd], Deferred[F, Unit], Deferred[F, Ended])
        .mapN { (id, stop, closed, ended) =>
          Entry(id, origin, ConnectionState.initial(origin), None, stop, closed, ended, None)
        }

    def include(remote: SocketAddress[IpAddress], local: Option[ConnectionId.Endpoint])(using
        Raise[F, ConnectionError]
    ) =
      val key: Key = (remote, local.fold(LocalAddress.Unidentified)(LocalAddress.At(_)))
      val opened = ConnectionEvent.Opened(remote.toString, Provenance.Inbound)
      val admit: F[Entry[F]] = fresh(Provenance.Inbound).flatMap { created =>
        changing { (t, _) =>
          val fromAddress = t.entries.count((k, e) =>
            e.origin == Provenance.Inbound && k._1.host == remote.host && e.state.isLive
          )
          val limited = Option
            .when(counters(t).inbound.toLong + 1L > hardLimit)(
              ConnectionError.InboundLimit(remote.toString, hardLimit)
            )
            .orElse(
              Option.when(fromAddress >= limits.maxInboundPerAddress)(
                ConnectionError.AddressLimit(remote.toString, limits.maxInboundPerAddress)
              )
            )
          limited match
            case Some(error) => (t, Effects.none[F], Left(error))
            case None =>
              t.entries.get(key) match
                // `Accepted` (842-846)
                case None => (t.put(key, created), Effects.log[F](Vector(opened)), Right(created))
                // a new entry in place of one that is terminating (859-860); the replaced one has
                // ended, whether or not its owner released it yet
                case Some(old) if !old.state.isLive =>
                  (
                    t.put(key, created),
                    Effects.log[F](
                      Vector(
                        ConnectionEvent.Changed(remote.toString, old.state, created.state),
                        opened
                      )
                    ) ++ Effects.ended(old, old.state, None),
                    Right(created)
                  )
                // `Overwritten` and `SelfConn` keep the entry (847-852); any other is refused
                case Some(old) =>
                  val event = old.state match
                    case ConnectionState.ReservedOutbound => Some(Event.Overwritten)
                    case ConnectionState.Unnegotiated(Provenance.Outbound) => Some(Event.SelfConn)
                    case _                                                 => None
                  event.flatMap(walk(remote, old, _).toOption) match
                    case Some((next, log)) =>
                      val taken = next.copy(origin = Provenance.Inbound)
                      (t.put(key, taken), Effects.log[F](log), Right(taken))
                    case None =>
                      (
                        t,
                        Effects.none[F],
                        Left(ConnectionError.ForbiddenOperation(remote.toString, old.state))
                      )
        }.flatMap(_.fold(refuse[Entry[F]](_), F.pure))
      }
      Resource
        .make(admit)(entry => release(entry.id, remote, entry.closed))
        .map(entry => Handle(entry, remote, Side.Inbound))

    /** `lookupByRemoteAddr` (`ConnMap.hs:177-208`): the reservation if there is one, else one of
      * the remote's connections at random, uniformly as its `uniformR` picks.
      */
    private def byRemote(
        t: Table[F],
        remote: SocketAddress[IpAddress]
    ): (Option[(Key, Entry[F])], Rng) =
      val reserved: Key = (remote, LocalAddress.Unknown)
      t.entries.get(reserved) match
        case Some(entry) => (Some(reserved -> entry), t.rng)
        case None =>
          val all = t.entries.toVector.filter(_._1._1 == remote).sortBy(_._1._2.toString)
          if all.isEmpty then (None, t.rng)
          else
            val (pick, next) = t.rng.uniformR(0L, all.size.toLong - 1L)
            (all.lift(pick.toInt), next)

    /** The outbound side's look at a connection to its peer (`Core.hs` 1284-1388, and 1663-1755
      * once it `waited` for the handshake): reuse it (`Awake^{Duplex}_{Local}` or
      * `PromotedToWarm^{Duplex}_{Local}`), wait, or say why not.
      */
    private def reuse(
        t: Table[F],
        key: Key,
        entry: Entry[F],
        remote: SocketAddress[IpAddress],
        waited: Boolean
    ): (Table[F], Effects[F], Dispatch[F]) =
      def refused(error: ConnectionError) = (t, Effects.none[F], Dispatch.Refuse[F](error))
      val at = remote.toString
      entry.state match
        case ConnectionState.ReservedOutbound if waited =>
          refused(ConnectionError.ImpossibleState(at, entry.state))
        case ConnectionState.ReservedOutbound | ConnectionState.Unnegotiated(Provenance.Outbound) |
            ConnectionState.OutboundUni | ConnectionState.OutboundDup(_) | ConnectionState.Duplex =>
          refused(ConnectionError.AlreadyConnected(at, entry.state))
        case ConnectionState.Unnegotiated(_) => (t, Effects.none[F], Dispatch.There(entry))
        case ConnectionState.OutboundIdle(_) =>
          refused(ConnectionError.ForbiddenOperation(at, entry.state))
        case ConnectionState.InboundIdle(DataFlow.Unidirectional) |
            ConnectionState.Inbound(DataFlow.Unidirectional) =>
          refused(ConnectionError.ForbiddenConnection(at))
        case ConnectionState.InboundIdle(DataFlow.Duplex) |
            ConnectionState.Inbound(DataFlow.Duplex) =>
          val event = entry.state match
            case ConnectionState.InboundIdle(_) => Event.AwakeLocal
            case _                              => Event.PromotedToWarmLocal
          (walk(remote, entry, event), entry.handle) match
            case (Right((next, log)), Some(negotiated)) =>
              (
                t.put(key, next),
                Effects.log[F](log),
                Dispatch.Done[F](Acquired.Reused(Handle(entry, remote, Side.Outbound), negotiated))
              )
            case _ => refused(ConnectionError.ImpossibleState(at, entry.state))
        case ConnectionState.Terminating | ConnectionState.Terminated if waited =>
          refused(ConnectionError.Disconnected(at, entry.state, None))
        case ConnectionState.Terminating | ConnectionState.Terminated =>
          (t, Effects.none[F], Dispatch.Retry[F]())

    private def dispatch(
        remote: SocketAddress[IpAddress],
        provenance: Provenance,
        created: Entry[F]
    ): (Table[F], FiniteDuration) => (Table[F], Effects[F], (Dispatch[F], Long)) = (t, _) =>
      val (found, rng) = byRemote(t, remote)
      val drawn = t.copy(rng = rng)
      val (next, effects, decision) = found match
        case Some((key, entry)) => reuse(drawn, key, entry, remote, waited = false)
        // `InboundConnectionNotFound` (1390-1399): never dial a peer known from its own connection
        case None if provenance == Provenance.Inbound =>
          (
            drawn,
            Effects.none[F],
            Dispatch.Refuse[F](ConnectionError.InboundConnectionNotFound(remote.toString))
          )
        case None =>
          val dialled =
            t.entries.count((_, e) => e.origin == Provenance.Outbound && e.state.isLive)
          if dialled >= limits.maxOutbound then
            (
              drawn,
              Effects.none[F],
              Dispatch.Refuse[F](ConnectionError.OutboundLimit(remote.toString, limits.maxOutbound))
            )
          else
            (
              drawn.put((remote, LocalAddress.Unknown), created),
              Effects.log[F](Vector(ConnectionEvent.Opened(remote.toString, Provenance.Outbound))),
              Dispatch.Done[F](Acquired.Reserved(Handle(created, remote, Side.Outbound)))
            )
      (next, effects, (decision, t.version))

    /** `There`: the outbound side waits, without holding the table, for an accepted connection's
      * handshake, then reuses it or settles as the reference does (1663-1755). A connection that
      * ended is `Disconnected` with the state and error it ended with, which outlive its entry as
      * the reference's connection state variable outlives the map (1741-1749). Only the wait is
      * cancelable (`poll`).
      */
    private def there(
        waited: Entry[F],
        remote: SocketAddress[IpAddress],
        poll: Poll[F]
    ): F[Dispatch[F]] =
      F.tailRecM(()) { _ =>
        changing { (t, _) =>
          t.locate(waited.id) match
            case None => (t, Effects.none[F], Right(None))
            case Some((_, entry))
                if entry.state == ConnectionState.Unnegotiated(Provenance.Inbound) =>
              (t, Effects.none[F], Left(t.version))
            case Some((key, entry)) =>
              val (next, effects, decision) = reuse(t, key, entry, remote, waited = true)
              (next, effects, Right(Some(decision)))
        }.flatMap {
          case Left(version)         => poll(changedFrom(version)).as(Left(()))
          case Right(Some(decision)) => F.pure(Right(decision))
          // every generation that leaves the table has its end published: wait for it
          case Right(None) =>
            poll(waited.ended.get).map(end =>
              Right(
                Dispatch.Refuse[F](
                  ConnectionError.Disconnected(remote.toString, end.state, end.cause)
                )
              )
            )
        }
      }

    def acquire(remote: SocketAddress[IpAddress], provenance: Provenance)(using
        Raise[F, ConnectionError]
    ) =
      Resource.eval(fresh(Provenance.Outbound)).flatMap { created =>
        // the claims (a reservation, a reuse) and their handoff are not cancelable; only the waits
        // are (`poll`), so a cancelled acquisition holds nothing it has not handed over
        def settle(poll: Poll[F]): F[Dispatch[F]] = F.tailRecM(()) { _ =>
          changing(dispatch(remote, provenance, created)).flatMap {
            case (Dispatch.There(entry), _)  => there(entry, remote, poll).map(Right(_))
            case (Dispatch.Retry(), version) => poll(changedFrom(version)).as(Left(()))
            case (decision, _)               => F.pure(Right(decision))
          }
        }
        val unreserve = release(created.id, remote, created.closed)
        Resource
          .makeCaseFull[F, Acquired[F]](poll =>
            settle(poll).flatMap {
              case Dispatch.Done(acquired) => F.pure(acquired)
              case Dispatch.Refuse(error)  => refuse[Acquired[F]](error)
              case Dispatch.There(_) | Dispatch.Retry() =>
                refuse[Acquired[F]](ConnectionError.ImpossibleState(remote.toString, created.state))
            }
          ) {
            case (Acquired.Reserved(_), _) => unreserve
            // a reuse the caller did not take over (it failed or was cancelled before its use
            // ended) is released by the outbound side, as its owner would
            case (Acquired.Reused(_, _), Resource.ExitCase.Succeeded) => F.unit
            case (Acquired.Reused(connection, _), _) => connection.releaseOutbound.void
          }
      }

    def lookup(remote: SocketAddress[IpAddress]) =
      table.get.map(
        _.entries.toVector
          .filter((k, e) => k._1 == remote && e.state.isLive)
          .sortBy(_._1._2.toString)
          .headOption
          .map((_, e) => Live[F](remote, e.state, e.closed.get))
      )

    def inboundCount = table.get.map(counters(_).inbound)

    def awaitInboundBelow(limit: Long) = table.waitUntil(counters(_).inbound < limit)

    def snapshot =
      changing { (t, _) =>
        (
          t,
          Effects.none[F],
          Snapshot(
            counters(t),
            t.entries.toVector
              .map((k, e) => Listed(k._1, e.state, e.id))
              .sortBy(l => (l.remote.toString, l.id))
          )
        )
      }
