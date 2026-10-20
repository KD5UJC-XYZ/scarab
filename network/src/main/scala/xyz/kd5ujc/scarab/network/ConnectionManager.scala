// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{Async, Deferred, Ref, Resource}
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import com.comcast.ip4s.{Host, IpAddress, SocketAddress}
import xyz.kd5ujc.scarab.protocol.ConnectionState
import xyz.kd5ujc.scarab.protocol.ConnectionState.{Counters, DataFlow, Event, Provenance}

/** What the manager did, for the log. */
enum ConnectionEvent:
  case Opened(remote: String, provenance: Provenance)
  case Changed(remote: String, from: ConnectionState, to: ConnectionState)
  case Refused(error: ConnectionError)
  case Closed(remote: String, provenance: Provenance, reason: ConnectionEnd)

trait ConnectionEvents[F[_]]:
  def emit(event: ConnectionEvent): F[Unit]

/** One connection as its owner sees it. Every change goes through the pure
  * [[ConnectionState.transition]]; an event the state does not allow is a typed error.
  */
trait Connection[F[_]]:
  def remote: SocketAddress[IpAddress]
  def provenance: Provenance
  def state: F[ConnectionState]

  /** The transport of a reserved outbound connection is up. */
  def connected(using Raise[F, ConnectionError]): F[Unit]

  /** The handshake agreed `flow`. */
  def negotiated(flow: DataFlow)(using Raise[F, ConnectionError]): F[Unit]

  /** Mark the connection closing with a reason (kept for the log); the owner then ends it. */
  def close(reason: ConnectionEnd): F[Unit]

  /** Completes once the connection has been released and removed. */
  def closed: F[Unit]

/** The node's table of node-to-node connections: at most one live connection per peer address and
  * direction. Admission policy controls whether opposite directions may coexist; accepted and
  * opened connections have separate limits and typed refusals. The connection life itself is owned
  * by whoever holds the resource: releasing it (normally, by failure or by cancellation) always
  * runs the connection through `Terminating` to removal, so the accounting cannot leak.
  *
  * A peer's inbound connection is keyed by the address the transport reports; a peer that dials
  * from its listening port (as the reference node does) is thereby the same address we would dial.
  * One that dials from an ephemeral port is a distinct address and is not recognised as the peer we
  * also dial.
  */
trait ConnectionManager[F[_]]:
  /** Admit an accepted connection from `remote` (state `Unnegotiated`). With `alongsideOutbound`
    * the connection we opened to the same peer does not refuse it: we run no clients on inbound
    * connections (`--inbound-sync off`), so the outbound one stays the source.
    */
  def inbound(remote: SocketAddress[IpAddress], alongsideOutbound: Boolean = false)(using
      Raise[F, ConnectionError]
  ): Resource[F, Connection[F]]

  /** Reserve the outbound slot for `remote` (state `Reserved`). With `alongsideInbound` the peer's
    * live inbound connection does not refuse it: the caller does not use that one for its clients
    * (`--inbound-sync off`), so both are needed.
    */
  def outbound(remote: SocketAddress[IpAddress], alongsideInbound: Boolean = false)(using
      Raise[F, ConnectionError]
  ): Resource[F, Connection[F]]

  /** The live connection to or from `remote`, if any. */
  def lookup(remote: SocketAddress[IpAddress]): F[Option[ConnectionManager.Live[F]]]

  def snapshot: F[ConnectionManager.Snapshot]

object ConnectionManager:
  /** A connection is known by the peer's address and the direction it was made in. */
  type Key = (SocketAddress[IpAddress], Provenance)

  final case class Limits(maxInbound: Int = 64, maxOutbound: Int = 64)

  final case class Live[F[_]](
      remote: SocketAddress[IpAddress],
      state: ConnectionState,
      closed: F[Unit]
  )

  final case class Snapshot(
      counters: Counters,
      connections: Vector[(SocketAddress[IpAddress], ConnectionState)]
  )

  /** The IP address of `address` (the first one a name resolves to). */
  def resolve[F[_]: Async](address: SocketAddress[Host]): F[SocketAddress[IpAddress]] =
    address.host
      .resolve[F](using fs2.io.net.Network.forAsync[F].dns, Async[F])
      .map(ip => SocketAddress(ip, address.port))

  private final case class Entry[F[_]](
      id: Long,
      provenance: Provenance,
      state: ConnectionState,
      closed: Deferred[F, Unit],
      reason: Option[ConnectionEnd]
  )

  def create[F[_]: Async](
      limits: Limits = Limits(),
      events: Option[ConnectionEvents[F]] = None
  ): F[ConnectionManager[F]] =
    (
      Ref.of[F, Map[ConnectionManager.Key, Entry[F]]](Map.empty),
      Ref.of[F, Long](0L)
    ).mapN { (table, ids) =>
      val F = Async[F]
      def emit(event: ConnectionEvent): F[Unit] = events.traverse_(_.emit(event))
      def refuse[A](error: ConnectionError)(using Raise[F, ConnectionError]): F[A] =
        emit(ConnectionEvent.Refused(error)) *> error.raise[F, A]

      def counters(m: Map[ConnectionManager.Key, Entry[F]]): Counters =
        Counters.of(m.values.map(_.state))

      /** Apply `event` to our entry. */
      def apply(
          key: ConnectionManager.Key,
          id: Long,
          event: Event
      ): F[Either[ConnectionError, Unit]] =
        val remote = key._1
        table
          .modify { m =>
            m.get(key).filter(_.id == id) match
              case None =>
                (
                  m,
                  Left(
                    ConnectionError.IllegalTransition(
                      remote.toString,
                      ConnectionState.Illegal(ConnectionState.Terminated, event)
                    )
                  )
                )
              case Some(entry) =>
                ConnectionState.transition(entry.state, event) match
                  case Left(problem) =>
                    (m, Left(ConnectionError.IllegalTransition(remote.toString, problem)))
                  case Right(next) =>
                    (m.updated(key, entry.copy(state = next)), Right((entry.state, next)))
          }
          .flatMap {
            case Left(error) => F.pure(Left(error))
            case Right((from, to)) =>
              emit(ConnectionEvent.Changed(remote.toString, from, to))
                .whenA(from != to)
                .as(Right(()))
          }

      def step(key: ConnectionManager.Key, id: Long, event: Event)(using
          Raise[F, ConnectionError]
      ): F[Unit] =
        apply(key, id, event).flatMap(_.fold(refuse[Unit](_), _ => F.unit))

      def handle(
          at: SocketAddress[IpAddress],
          id: Long,
          origin: Provenance,
          done: Deferred[F, Unit]
      ): Connection[F] = new Connection[F]:
        private val key = (at, origin)
        def remote = at
        def provenance = origin
        def state = table.get.map(
          _.get(key).filter(_.id == id).fold[ConnectionState](ConnectionState.Terminated)(_.state)
        )
        def connected(using Raise[F, ConnectionError]) = step(key, id, Event.Connected)
        def negotiated(flow: DataFlow)(using Raise[F, ConnectionError]) =
          step(key, id, Event.Negotiated(flow))
        def close(reason: ConnectionEnd) =
          table.update(m =>
            m.get(key)
              .filter(e => e.id == id && e.reason.isEmpty)
              .fold(m)(e => m.updated(key, e.copy(reason = Some(reason))))
          ) *> apply(key, id, Event.Close).void
        def closed = done.get

      /** Take the slot for `remote` or say why not (atomically, with the limits). */
      def admit(
          remote: SocketAddress[IpAddress],
          provenance: Provenance,
          alongside: Boolean
      )(using Raise[F, ConnectionError]): F[Connection[F]] =
        for
          closed <- Deferred[F, Unit]
          id <- ids.updateAndGet(_ + 1)
          admitted <- table.modify { m =>
            val key = (remote, provenance)
            val other = provenance match
              case Provenance.Inbound  => (remote, Provenance.Outbound)
              case Provenance.Outbound => (remote, Provenance.Inbound)
            // one connection per address and direction; across directions the two only coexist
            // when the caller says our clients do not run on the inbound one
            val taken = m
              .get(key)
              .filter(_.state.isLive)
              .orElse(
                m.get(other)
                  .filter(_.state.isLive)
                  .filterNot(_ => alongside)
              )
            val c = counters(m)
            if taken.isDefined then
              (m, Left(ConnectionError.AlreadyConnected(remote.toString, taken.get.state)))
            else if provenance == Provenance.Inbound && c.inbound >= limits.maxInbound then
              (m, Left(ConnectionError.InboundLimit(remote.toString, limits.maxInbound)))
            else if provenance == Provenance.Outbound && c.outbound >= limits.maxOutbound then
              (m, Left(ConnectionError.OutboundLimit(remote.toString, limits.maxOutbound)))
            else
              (
                m.updated(
                  key,
                  Entry(id, provenance, ConnectionState.initial(provenance), closed, None)
                ),
                Right(())
              )
          }
          connection <- admitted.fold(
            refuse[Connection[F]](_),
            _ =>
              emit(ConnectionEvent.Opened(remote.toString, provenance))
                .as(handle(remote, id, provenance, closed))
          )
        yield connection

      /** Release: through Terminating to removal, whatever state the connection is in. */
      def release(c: Connection[F]): F[Unit] =
        val at = c.remote
        val key = (at, c.provenance)
        table.get.map(_.get(key)).flatMap {
          _.traverse_ { e =>
            val reason = e.reason.getOrElse(ConnectionEnd.Released)
            c.close(reason) *> apply(key, e.id, Event.Released) *>
              table.update(m => m.get(key).filter(_.id == e.id).fold(m)(_ => m - key)) *>
              e.closed.complete(()) *>
              emit(ConnectionEvent.Closed(at.toString, e.provenance, reason))
          }
        }

      new ConnectionManager[F]:
        def inbound(remote: SocketAddress[IpAddress], alongsideOutbound: Boolean)(using
            Raise[F, ConnectionError]
        ) =
          Resource.make(admit(remote, Provenance.Inbound, alongsideOutbound))(release)

        def outbound(remote: SocketAddress[IpAddress], alongsideInbound: Boolean)(using
            Raise[F, ConnectionError]
        ) =
          Resource.make(admit(remote, Provenance.Outbound, alongsideInbound))(release)

        def lookup(remote: SocketAddress[IpAddress]) =
          table.get.map { m =>
            m.get((remote, Provenance.Inbound))
              .filter(_.state.isLive)
              .orElse(m.get((remote, Provenance.Outbound)).filter(_.state.isLive))
              .map(e => Live[F](remote, e.state, e.closed.get))
          }

        def snapshot =
          table.get.map(m =>
            Snapshot(
              counters(m),
              m.toVector.map((k, e) => k._1 -> e.state).sortBy(_._1.toString)
            )
          )
    }
