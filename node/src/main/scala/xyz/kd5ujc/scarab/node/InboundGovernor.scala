// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.ledger.{Header, NodeError, PeerError, Points}
import cats.effect.{Async, Ref, Resource}
import cats.effect.syntax.all.*
import cats.mtl.{Handle, Raise}
import cats.syntax.all.*
import com.comcast.ip4s.{IpAddress, SocketAddress}
import xyz.kd5ujc.scarab.protocol.{ChainSync, ConnectionState, NtNHeader}
import xyz.kd5ujc.scarab.network.*

/** What happened on an inbound connection, for the log. */
enum InboundEvent:
  /** The connection was accepted and admitted by the connection manager. */
  case Accepted

  /** The handshake agreed duplex: our clients will run on the connection. */
  case Duplex

  /** The handshake agreed initiator-only: the peer only reads from us. */
  case Unidirectional

  /** Our ChainSync client found an intersection with the peer's chain. */
  case Following(intersection: ChainSync.Point)

  /** Our client session on the connection ended; the connection stays unless `dropped`. */
  case SessionEnded(reason: InboundGovernor.SessionFailure, dropped: Boolean)

  /** The connection ended. */
  case Closed(reason: InboundEnd)

  /** The connection manager refused the connection. */
  case Refused(reason: ConnectionError)

trait InboundEvents[F[_]]:
  def emit(remote: String, event: InboundEvent): F[Unit]

/** The inbound governor (G2): for every accepted connection it serves our chain and, when the
  * handshake agreed duplex (InitiatorAndResponder), also runs our own ChainSync, BlockFetch and
  * KeepAlive clients on the same connection, so that a peer that connected to us is a source of
  * blocks as well as a consumer of ours. The sessions are the ones the node runs on connections it
  * opens (header-first validation, pipelined block fetch, chain selection in [[Follower]]).
  *
  * The connection manager keeps the accounting and the one-connection-per-peer rule; the session
  * and the connection share their life: when the multiplexer closes, the session's resources are
  * released with it (the clients, the pipelined producer, the supervised fibers). A session that
  * fails because the peer broke the protocol or announced an invalid header drops the connection;
  * one that fails for any other reason (the peer does not serve ChainSync, say) leaves the serving
  * side running.
  */
final class InboundGovernor[F[_]: Async] private (
    node: Handle[F, NodeError],
    fatal: Fatal => F[Unit],
    manager: ConnectionManager[F],
    config: InboundGovernor.Config,
    handlers: ServerHandlers[F, NtNHeader],
    follow: InboundGovernor.Follow[F],
    prepare: Peer.Prepare[F],
    headers: Option[HeaderCheck[F]],
    transactions: Option[TxSubmissionClient.Source[F]],
    events: Option[InboundEvents[F]],
    observe: Option[Peer.Observation => F[Unit]],
    sharing: Option[PeerSharingRuntime[F]],
    sessionTable: Ref[F, Map[String, InboundGovernor.Session]]
):
  private val F = Async[F]
  import Peer.networkAsPeer
  private def emit(remote: SocketAddress[IpAddress], event: InboundEvent): F[Unit] =
    events.traverse_(_.emit(remote.toString, event))

  /** Sessions of the connections accepted so far (the newest ones, ended or not). */
  def sessions: F[Vector[InboundGovernor.Session]] =
    sessionTable.get.map(_.values.toVector.sortBy(_.remote))

  private def update(remote: SocketAddress[IpAddress])(
      f: InboundGovernor.Session => InboundGovernor.Session
  ): F[Unit] =
    sessionTable.update { m =>
      val key = remote.toString
      val next = f(m.getOrElse(key, InboundGovernor.Session(key)))
      val all = m.updated(key, next)
      // keep the live ones and the newest of the ended
      if all.size <= InboundGovernor.RetainedSessions then all
      else
        val (ended, live) = all.partition(_._2.ended.isDefined)
        live ++ ended.toVector
          .sortBy(_._2.number)
          .takeRight(InboundGovernor.RetainedSessions - live.size)
    }

  /** Serve one accepted connection until it ends; returns why it ended. Never raises. */
  def serve(bearer: Bearer[F], remote: SocketAddress[IpAddress]): F[ConnectionEnd] =
    Handle
      .allow[ConnectionError] {
        // a connection we opened is initiator-only: the peer can read our chain only over a
        // connection it opens itself, so ours never refuses its inbound one
        manager.inbound(remote, alongsideOutbound = true).use { connection =>
          emit(remote, InboundEvent.Accepted) *>
            Handle
              .allow[NetworkError] {
                NodeToNodeServer.accept(
                  bearer,
                  config.magic,
                  handlers,
                  config.server,
                  negotiated = (agreed, mux) => negotiated(connection, remote, agreed, mux)
                )
              }
              .rescue {
                case NetworkError.Defect(cause) =>
                  fatal(Fatal.Defect(cause)).as(ConnectionEnd.Defect(cause))
                case other => F.pure(ConnectionEnd.fromNetwork(other))
              }
              .flatTap(reason => connection.close(reason))
        }
      }
      .rescue(error => emit(remote, InboundEvent.Refused(error)).as(ConnectionEnd.Refused(error)))
      .flatTap(reason => emit(remote, InboundEvent.Closed(InboundEnd.Connection(reason))))

  /** After the handshake: record the data flow and, on a duplex connection, run our clients. */
  private def negotiated(
      connection: Connection[F],
      remote: SocketAddress[IpAddress],
      agreed: HandshakeClient.Agreed,
      mux: Mux[F]
  ): Resource[F, Unit] =
    val duplex = !agreed.data.initiatorOnly
    Resource
      .eval(
        Handle
          .allow[ConnectionError] {
            connection
              .negotiated(
                if duplex then ConnectionState.DataFlow.Duplex
                else ConnectionState.DataFlow.Unidirectional
              )
              .as(true)
          }
          .rescue(error =>
            mux.abort(NetworkError.BearerClosed(NetworkError.Closure.AbortedBy(error))).as(false)
          )
      )
      .flatMap { admitted =>
        if !admitted then Resource.unit[F]
        else
          Resource.eval(
            emit(remote, if duplex then InboundEvent.Duplex else InboundEvent.Unidirectional)
          ) *>
            sharing.filter(_ => duplex).traverse_(_.accepted(remote, agreed.data.peerSharing)) *>
            (if duplex && config.runClients then sessionResource(remote, agreed, mux)
             else Resource.unit[F])
      }

  /** Our clients on the connection and the follower session over them, for the life of the
    * connection. A peer that shares no point with our chain (it is behind us, as a relay that is
    * still syncing from us is) is tried again after `retryDelay`: the protocol is back in its idle
    * state after a failed FindIntersect, so the connection is reusable.
    */
  private def sessionResource(
      remote: SocketAddress[IpAddress],
      agreed: HandshakeClient.Agreed,
      mux: Mux[F]
  ): Resource[F, Unit] =
    session(remote, agreed.version, agreed.data.peerSharing, mux).background.void

  private def session(
      remote: SocketAddress[IpAddress],
      version: Int,
      peerSharing: Boolean,
      mux: Mux[F]
  ): F[Unit] =
    for
      n <- sessionTable.get.map(_.size.toLong)
      _ <- update(remote)(_ => InboundGovernor.Session(remote.toString, number = n + 1))
      _ <- attempt(remote, version, peerSharing, mux)
    yield ()

  /** Our clients on the connection, for as long as the session lasts. They are started once: a
    * retry after [[PeerError.NoIntersection]] follows again over the same clients, since starting
    * them again would begin each mini-protocol a second time on the same channels (a second
    * TxSubmission2 `MsgInit` mid-protocol makes the peer drop the connection).
    *
    * The session is a fiber of its own and ends with a [[SessionEnd]], a value
    * ([[SessionEnd.scoped]]); what the node does about it is [[FailurePolicy.disposition]].
    */
  private def attempt(
      remote: SocketAddress[IpAddress],
      version: Int,
      peerSharing: Boolean,
      mux: Mux[F]
  ): F[Unit] =
    for
      end <- SessionEnd.scoped(node) { (_, _) ?=>
        NodeToNode
          .clients[F, NtNHeader](
            mux,
            Mux.Role.Initiator,
            version,
            NtNHeader.codec,
            config.peer.network,
            transactions,
            peerSharing = peerSharing
          )
          .use(connection => following(remote, connection))
          .as(SessionEnd.Returned)
      }
      disposition = FailurePolicy.disposition(end)
      reason = disposition match
        case Disposition.Forget | Disposition.Backoff =>
          SessionEnd.peerError(end).map(InboundGovernor.SessionFailure.Peer(_))
        case Disposition.StopNode(f) => Some(InboundGovernor.SessionFailure.Fatal(f))
        case Disposition.Settled     => None
      drop = disposition == Disposition.Forget
      _ <- reason.traverse_ { why =>
        val closure = SessionEnd
          .peerError(end)
          .fold[NetworkError.Closure](
            NetworkError.Closure.Aborted
          )(NetworkError.Closure.AbortedBy(_))
        update(remote)(_.copy(ended = Some(why))) *>
          emit(remote, InboundEvent.SessionEnded(why, drop)) *>
          mux.abort(NetworkError.BearerClosed(closure)).whenA(drop)
      }
      _ <- disposition match
        case Disposition.StopNode(f) =>
          fatal(f) *> mux.abort(NetworkError.BearerClosed(NetworkError.Closure.AbortedBy(f)))
        case _ => F.unit
    yield ()

  /** Follow the peer over `connection`; while it shares no point with our chain, try again after
    * `retryDelay` on the same clients.
    */
  private def following(
      remote: SocketAddress[IpAddress],
      connection: NodeToNodeConnection[F, NtNHeader]
  )(using Raise[F, NodeError]): F[Unit] =
    Handle
      .allow[PeerError] {
        Peer
          .over(connection, config.peer, prepare, headers, observe)
          .use(peer => follow(counted(remote, peer)))
          .as(Option.empty[PeerError])
      }
      .rescue(e => F.pure(Some(e)))
      .flatMap {
        case None => F.unit
        case Some(PeerError.NoIntersection) =>
          update(remote)(s => s.copy(retries = s.retries + 1)) *>
            emit(
              remote,
              InboundEvent
                .SessionEnded(InboundGovernor.SessionFailure.Peer(PeerError.NoIntersection), false)
            ) *>
            F.sleep(config.retryDelay) *> following(remote, connection)
        case Some(error) => Raise[F, PeerError].raise(error)
      }

  /** The peer, with what the session sees of it counted. */
  private def counted(remote: SocketAddress[IpAddress], peer: Peer[F]): Peer[F] = new Peer[F]:
    def intersect(candidates: Vector[ChainSync.Point])(using Raise[F, PeerError]) =
      peer.intersect(candidates).flatTap { found =>
        found.traverse_ { point =>
          update(remote)(_.copy(intersection = Some(Points.show(point)))) *>
            emit(remote, InboundEvent.Following(point))
        }
      }
    def next(using Raise[F, PeerError]) = peer.next.flatTap {
      case SyncEvent.RollForward(_, tip) =>
        update(remote)(s => s.copy(headers = s.headers + 1, peerTip = tipBlock(tip)))
      case SyncEvent.RollBackward(_, tip) =>
        update(remote)(s => s.copy(rollbacks = s.rollbacks + 1, peerTip = tipBlock(tip)))
    }
    def fetch(header: Header)(using Raise[F, PeerError]) = peer.fetch(header)

  private def tipBlock(tip: ChainSync.Tip): Option[BigInt] = Some(tip.blockNo.value)

object InboundGovernor:
  /** A client session's retained failure, before log/JSON presentation. */
  enum SessionFailure:
    case Peer(error: PeerError)
    case Fatal(error: xyz.kd5ujc.scarab.node.Fatal)

    def message: String = this match
      case Peer(error)  => error.message
      case Fatal(error) => error.message

  /** The connection table and the inbound sessions as JSON (`GET /connections`). */
  def json(
      snapshot: ConnectionManager.Snapshot,
      sessions: Vector[Session]
  ): io.circe.Json =
    import io.circe.Json
    import io.circe.syntax.*
    Json.obj(
      "counters" -> Json.obj(
        "inbound" -> snapshot.counters.inbound.asJson,
        "outbound" -> snapshot.counters.outbound.asJson,
        "duplex" -> snapshot.counters.duplex.asJson,
        "established" -> snapshot.counters.established.asJson
      ),
      "connections" -> snapshot.connections.map { (remote, state) =>
        Json.obj("remote" -> remote.toString.asJson, "state" -> state.toString.asJson)
      }.asJson,
      "inboundSessions" -> sessions.map { s =>
        Json.obj(
          "remote" -> s.remote.asJson,
          "intersection" -> s.intersection.asJson,
          "headers" -> s.headers.asJson,
          "rollbacks" -> s.rollbacks.asJson,
          "retries" -> s.retries.asJson,
          "peerTipBlock" -> s.peerTip.map(_.toString).asJson,
          "ended" -> s.ended.map(_.message).asJson
        )
      }.asJson
    )

  /** The session of one inbound connection: what our clients saw of the peer. */
  final case class Session(
      remote: String,
      number: Long = 0,
      intersection: Option[String] = None,
      headers: Long = 0,
      rollbacks: Long = 0,
      /** Times the peer shared no point with our chain and the session was started again. */
      retries: Long = 0,
      peerTip: Option[BigInt] = None,
      ended: Option[SessionFailure] = None
  )

  /** The follower session over the peer, raising a [[PeerError]] when the peer fails. */
  type Follow[F[_]] = Peer[F] => Raise[F, NodeError] ?=> F[Unit]

  private val RetainedSessions = 64

  /** `runClients` off serves inbound connections without following them. */
  final case class Config(
      magic: Long,
      server: NodeToNodeServer.Settings,
      peer: Peer.Settings,
      runClients: Boolean = true,
      /** How long to wait before following a peer again that shared no point with our chain. */
      retryDelay: scala.concurrent.duration.FiniteDuration =
        scala.concurrent.duration.DurationInt(10).seconds
  )

  def create[F[_]: Async](
      manager: ConnectionManager[F],
      config: Config,
      handlers: ServerHandlers[F, NtNHeader],
      follow: Follow[F],
      prepare: Peer.Prepare[F],
      fatal: Fatal => F[Unit],
      headers: Option[HeaderCheck[F]] = None,
      transactions: Option[TxSubmissionClient.Source[F]] = None,
      events: Option[InboundEvents[F]] = None,
      observe: Option[Peer.Observation => F[Unit]] = None,
      sharing: Option[PeerSharingRuntime[F]] = None
  )(using node: Handle[F, NodeError]): F[InboundGovernor[F]] =
    Ref
      .of[F, Map[String, Session]](Map.empty)
      .map(
        new InboundGovernor[F](
          node,
          fatal,
          manager,
          config,
          handlers,
          follow,
          prepare,
          headers,
          transactions,
          events,
          observe,
          sharing,
          _
        )
      )
