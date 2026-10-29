// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.ledger.{
  GenesisInternalProblem,
  Header,
  NodeError,
  PeerError,
  Points,
  ValidationError
}
import cats.effect.{Async, Ref, Resource}
import cats.effect.syntax.all.*
import cats.effect.std.Mutex
import cats.mtl.{Handle, Raise, Stateful}
import cats.syntax.all.*
import scala.concurrent.duration.*
import com.comcast.ip4s.{IpAddress, SocketAddress}
import xyz.kd5ujc.scarab.protocol.{ChainSync, ConnectionState, NtNHeader}
import xyz.kd5ujc.scarab.network.*
import xyz.kd5ujc.scarab.network.selection.PeerMetrics

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

  /** A normal ChainSync result leaves the established bearer warm before reactivation. */
  case SessionReturned(reason: InboundGovernor.ReturnReason, delay: FiniteDuration)

  /** A cached original was rejected, including after its adding connection has closed. */
  case CachedBlockRejected(refusal: ValidationError, generation: BigInt, sourceLive: Boolean)

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
    heightSnapshot: Peer.HeightSnapshot[F],
    headers: Option[HeaderCheck[F]],
    transactions: Option[TxSubmissionClient.Source[F]],
    events: Option[InboundEvents[F]],
    observe: Option[Peer.Observation => F[Unit]],
    peerMetrics: Option[Ref[F, PeerMetrics[PeerNetwork.Addr]]],
    sharing: Option[PeerSharingRuntime[F]],
    pool: BlockPool[F],
    genesis: Option[GenesisRuntime[F]],
    receivedBody: Option[Peer.BodySink[F]],
    repromotion: RepromotionWait[F],
    sessionTable: Ref[F, Map[String, InboundGovernor.Session]],
    sourceCounter: Ref[F, BigInt],
    liveSources: Ref[F, Map[BigInt, InboundGovernor.LiveSource[F]]],
    sourceLock: Mutex[F]
):
  private val F = Async[F]
  import Peer.networkAsPeer
  private def emit(remote: SocketAddress[IpAddress], event: InboundEvent): F[Unit] =
    events.traverse_(_.emit(remote.toString, event))

  private def metered(remote: SocketAddress[IpAddress]): Option[Peer.Observation => F[Unit]] =
    peerMetrics.fold(observe) { board =>
      given Stateful[F, PeerMetrics[PeerNetwork.Addr]] = RefStateful(board)
      Some(PeerMetricsObserver[F, PeerNetwork.Addr](remote, observe))
    }

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

  private def reportFatal(remote: SocketAddress[IpAddress], problem: Fatal): F[Unit] =
    F.uncancelable { _ =>
      sessionTable
        .modify { table =>
          val key = remote.toString
          val current = table.getOrElse(key, InboundGovernor.Session(key))
          val already = current.ended.contains(InboundGovernor.SessionFailure.Fatal(problem))
          (
            table.updated(
              key,
              current.copy(ended = Some(InboundGovernor.SessionFailure.Fatal(problem)))
            ),
            !already
          )
        }
        .flatMap(first => fatal(problem).whenA(first))
    }

  /** Serve one accepted connection until it ends; returns why it ended. Never raises. */
  def serve(bearer: Bearer[F], remote: SocketAddress[IpAddress]): F[ConnectionEnd] =
    Handle
      .allow[ConnectionError] {
        // a connection we opened to the peer is where our clients run unless inbound sync is on
        // and this connection agrees duplex; its inbound connection serves our chain to it and so
        // does not conflict with ours
        manager.inbound(remote, alongsideOutbound = true).use { connection =>
          emit(remote, InboundEvent.Accepted) *>
            Handle
              .allow[NetworkError] {
                NodeToNodeServer.accept(
                  bearer,
                  config.magic,
                  handlers,
                  config.server,
                  negotiated = (agreed, mux) => negotiated(connection, remote, agreed, mux),
                  // our clients run on a duplex connection (`negotiated`): then the remote side's
                  // idleness does not release it
                  ownClients = agreed => !agreed.data.initiatorOnly && config.runClients
                )
              }
              // an exception of the connection's own work ends that connection, reported as a
              // defect with its cause, and the node goes on: the reference's default `ShutdownPeer`
              // (network 2fb69829 RethrowPolicy.hs 61-70, 99-103; issue 567)
              .rescue(error => F.pure(ConnectionEnd.fromNetwork(error)))
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

  /** Established protocols live for the bearer; each clean normal return gets a fresh hot
    * activation after the reference repromotion delay.
    */
  private def sessionResource(
      remote: SocketAddress[IpAddress],
      agreed: HandshakeClient.Agreed,
      mux: Mux[F]
  ): Resource[F, Unit] =
    Resource.eval(sourceCounter.updateAndGet(_ + 1)).flatMap { number =>
      (update(remote)(_ => InboundGovernor.Session(remote.toString, number = number)) *>
        attempt(remote, agreed.version, agreed.data.peerSharing, mux)).background.void
    }

  /** The pool retains only this immutable key and the governor. Live transport callbacks are
    * removed after their hot protocol has drained. A later activation on the same bearer has a
    * different key (network2fb69829 PeerStateActions.hs:979,1000–1016,1164–1195).
    * ChainSel.hs:1051–1067 / InvalidBlockPunishment.hs:48–56 punish the adding client.
    */
  private[node] def source(
      remote: SocketAddress[IpAddress],
      abort: PeerError => F[Unit]
  ): Resource[F, InboundGovernor.SourceKey] =
    Resource
      .eval(sourceCounter.modify(n => (n + 1, InboundGovernor.SourceKey(n + 1, remote))))
      .flatMap { key =>
        Resource.make(
          sourceLock.lock
            .surround(
              liveSources.update(_ + (key.generation -> InboundGovernor.LiveSource(key, abort)))
            )
            .as(key)
        )(_ => retire(key))
      }

  private def retire(key: InboundGovernor.SourceKey): F[Unit] =
    sourceLock.lock.surround(liveSources.update(_ - key.generation))

  private[node] def cachedSource(
      remote: SocketAddress[IpAddress]
  ): F[Option[InboundGovernor.SourceKey]] =
    liveSources.get.map(_.valuesIterator.find(_.key.remote == remote).map(_.key))

  private[node] def rejectedCachedBlock(
      key: InboundGovernor.SourceKey,
      refusal: ValidationError
  ): F[Unit] =
    F.uncancelable { _ =>
      sourceLock.lock
        .surround(liveSources.get.flatMap { live =>
          val abort = live.get(key.generation).map(_.abort)
          abort.traverse_(_(PeerError.InvalidBlock(refusal))).as(abort.isDefined)
        })
        .flatMap(sourceLive =>
          emit(key.remote, InboundEvent.CachedBlockRejected(refusal, key.generation, sourceLive))
        )
    }

  /** KeepAlive and PeerSharing survive clean hot-protocol returns. Reusing a terminal ChainSync
    * runtime would send a new FindIntersect after MsgDone.
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
          .warm[F, NtNHeader](
            mux,
            Mux.Role.Initiator,
            version,
            NtNHeader.codec,
            config.peer.network,
            peerSharing = peerSharing,
            duplex = true
          )
          .use { warm =>
            F.tailRecM[Unit, SessionEnd](()) { _ =>
              source(
                remote,
                error => mux.abort(NetworkError.BearerClosed(NetworkError.Closure.AbortedBy(error)))
              )
                .flatMap(key => warm.activate(transactions, Some(retire(key))).map(_ -> key))
                .use { (connection, source) =>
                  SessionEnd
                    .scoped(node) { (_, _) ?=>
                      following(remote, connection, source).as(SessionEnd.Returned)
                    }
                    .flatMap { end =>
                      val cooling =
                        (InboundGovernor.ReturnReason.of(end), SessionEnd.returnDelay(end)).tupled
                      cooling match
                        case None =>
                          F.pure(
                            Right(end): Either[
                              (InboundGovernor.ReturnReason, FiniteDuration),
                              SessionEnd
                            ]
                          )
                        case Some(value) =>
                          connection.finishAfterChainSync(config.deactivateTimeout).flatMap {
                            case true => F.pure(Left(value))
                            case false =>
                              mux
                                .abort(NetworkError.BearerClosed(NetworkError.Closure.Aborted))
                                .as(Right(SessionEnd.Stopped(false)))
                          }
                    }
                }
                .flatMap {
                  case Left((reason, delay)) =>
                    update(remote)(s => s.copy(retries = s.retries + 1)) *>
                      emit(remote, InboundEvent.SessionReturned(reason, delay)) *>
                      repromotion.delay(delay).as(Left(()))
                  case Right(end) => F.pure(Right(end))
                }
            }
          }
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
        (why match
          case InboundGovernor.SessionFailure.Fatal(problem) => reportFatal(remote, problem)
          case _ => update(remote)(_.copy(ended = Some(why)))
        ) *>
          emit(remote, InboundEvent.SessionEnded(why, drop)) *>
          mux.abort(NetworkError.BearerClosed(closure)).whenA(drop)
      }
      _ <- disposition match
        case Disposition.StopNode(f) =>
          reportFatal(remote, f) *> mux.abort(
            NetworkError.BearerClosed(NetworkError.Closure.AbortedBy(f))
          )
        case _ => F.unit
    yield ()

  /** One hot activation, returning normal results only after its submitted replies are drained. */
  private def following(
      remote: SocketAddress[IpAddress],
      connection: NodeToNodeConnection[F, NtNHeader],
      source: InboundGovernor.SourceKey
  )(using outer: Raise[F, NodeError]): F[Unit] =
    Handle
      .allow[NodeError] {
        val caller = summon[Raise[F, NodeError]]
        // the controls raise to this scope, in the session's own fiber
        given Raise[F, PeerError] = caller
        val abort: PeerError => F[Unit] = error =>
          connection.mux.abort(NetworkError.BearerClosed(NetworkError.Closure.AbortedBy(error)))
        // an invariant of the Genesis code met on this connection ends it, uncharged
        val invariant: GenesisInternalProblem => F[Unit] =
          problem => abort(PeerError.GenesisInvariant(problem))
        GenesisRuntime
          .controls(
            genesis,
            pool,
            remote.hashCode.toLong,
            connection.mux.connectionId,
            abort,
            invariant
          )
          .use { (fetching, bound) =>
            Peer
              .over(
                connection,
                config.peer.copy(address = SocketAddress(remote.host, remote.port)),
                prepare,
                heightSnapshot,
                bound.fold(headers.map(_.withInvariant(invariant)))(value => Some(value.headers)),
                metered(remote),
                Some(pool),
                invalidCachedBlock = Some(rejectedCachedBlock(source, _)),
                patience = bound.map(_.patience),
                received = bound.map(_.received),
                chainSyncObserver = bound.map(_.observer),
                fetchSession = fetching,
                genesis = bound.map(_.control),
                receivedBody = receivedBody,
                retainedHeaders = bound.fold(BlockPool.Retained)(_.retainedHeaders),
                selectionWork = bound.map(_.work),
                pipelineFailed = Some(fault =>
                  InboundGovernor.pipelineEnd(fault) match
                    case Left(problem) => reportFatal(remote, problem)
                    case Right(error) =>
                      update(remote)(s =>
                        if s.ended.isDefined then s
                        else s.copy(ended = Some(InboundGovernor.SessionFailure.Peer(error)))
                      )
                )
              )
              .use { peer =>
                SessionEnd
                  .scoped(node) { (_, _) ?=>
                    follow(counted(remote, peer)).as(SessionEnd.Returned)
                  }
                  .flatMap {
                    case SessionEnd.Rejected(refusal) =>
                      peer.refused(refusal).map { foreign =>
                        if foreign then
                          SessionEnd.Failed(
                            PeerError.Diverged(PeerError.Divergence.ForeignBlock(refusal))
                          )
                        else SessionEnd.Rejected(refusal)
                      }
                    case other => F.pure(other)
                  }
                  .flatMap { end =>
                    val record = FailurePolicy.disposition(end) match
                      case Disposition.StopNode(problem) =>
                        reportFatal(remote, problem)
                      case Disposition.Forget | Disposition.Backoff =>
                        SessionEnd
                          .peerError(end)
                          .filterNot(_ == PeerError.NoIntersection)
                          .traverse_(problem =>
                            update(remote)(
                              _.copy(ended = Some(InboundGovernor.SessionFailure.Peer(problem)))
                            )
                          )
                      case Disposition.Settled => F.unit
                    record *> peer.quiesce.whenA(SessionEnd.returnDelay(end).nonEmpty) *> SessionEnd
                      .networkFailure(end)
                      .traverse_(connection.mux.abort) *> (end match
                      case SessionEnd.Failed(error)      => caller.raise[NodeError, Unit](error)
                      case SessionEnd.Rejected(error)    => caller.raise[NodeError, Unit](error)
                      case SessionEnd.Unavailable(error) => caller.raise[NodeError, Unit](error)
                      case SessionEnd.Unverifiable(error) =>
                        caller.raise[NodeError, Unit](error)
                      case SessionEnd.Storage(error)  => caller.raise[NodeError, Unit](error)
                      case SessionEnd.Internal(error) => caller.raise[NodeError, Unit](error)
                      case SessionEnd.Defect(error)   => F.raiseError[Unit](error)
                      case SessionEnd.Cancelled       => F.canceled *> F.never[Unit]
                      case SessionEnd.Returned | SessionEnd.Stopped(_) => F.unit
                      case SessionEnd.RolledBackPastIntersection(point) =>
                        caller.raise[NodeError, Unit](PeerError.RolledBackPastIntersection(point))
                      case SessionEnd.NoMoreIntersection =>
                        caller.raise[NodeError, Unit](PeerError.NoIntersection))
                  }
              }
              .as(Option.empty[NodeError])
          }
      }
      .rescue(e => F.pure(Some(e)))
      .flatMap {
        case None        => F.unit
        case Some(error) => outer.raise(error)
      }

  /** The peer, with what the session sees of it counted. */
  private def counted(remote: SocketAddress[IpAddress], peer: Peer[F]): Peer[F] = new Peer[F]:
    override def headerCandidate = peer.headerCandidate
    override def selectionWork = peer.selectionWork
    override def invalidBlocks = peer.invalidBlocks
    override def noVerdictBlocks = peer.noVerdictBlocks
    override def invalidated(header: Header, refusal: ValidationError)(using cats.Applicative[F]) =
      peer.invalidated(header, refusal)
    override def validation(header: Header)(using cats.Applicative[F], Raise[F, PeerError]) =
      peer.validation(header)
    override def rejected(refusal: xyz.kd5ujc.scarab.ledger.ValidationError)(using
        cats.Applicative[F]
    ) =
      peer.rejected(refusal)
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
  /** What the failure of a session's header pipeline is: the peer's error, or the node's stop. An
    * exception of the pipeline's own work ends this connection only, uncharged, as in
    * [[SessionEnd.scoped]]; a failure to acquire the node's own state is the node's (H6 of issue
    * 567).
    */
  private[node] def pipelineEnd(fault: Fault): Either[Fatal, PeerError] = fault match
    case Fault.Failed(error)                                 => Right(error)
    case Fault.Defect(failure: NetworkError.InternalFailure) => Left(Fatal.Defect(failure))
    case Fault.Defect(cause) => Right(PeerError.ConnectionDefect(cause))

  enum ReturnReason:
    case NoMoreIntersection
    case RolledBackPastIntersection(point: ChainSync.Point)
    def message: String = this match
      case NoMoreIntersection => "no more intersection"
      case RolledBackPastIntersection(point) =>
        s"rolled back past intersection: ${Points.show(point)}"
  object ReturnReason:
    def of(end: SessionEnd): Option[ReturnReason] = end match
      case SessionEnd.NoMoreIntersection => Some(ReturnReason.NoMoreIntersection)
      case SessionEnd.RolledBackPastIntersection(point) =>
        Some(ReturnReason.RolledBackPastIntersection(point))
      case _ => None

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

  /** The session of one inbound connection: what our clients saw of the peer.
    *
    * @param retries
    *   Normal ChainSync returns awaiting a fresh hot activation.
    */
  final case class Session(
      remote: String,
      number: BigInt = 0,
      intersection: Option[String] = None,
      headers: Long = 0,
      rollbacks: Long = 0,
      retries: Long = 0,
      peerTip: Option[BigInt] = None,
      ended: Option[SessionFailure] = None
  )

  /** The follower session over the peer, raising a [[PeerError]] when the peer fails. */
  type Follow[F[_]] = Peer[F] => Raise[F, NodeError] ?=> F[Unit]

  private[node] final case class SourceKey(generation: BigInt, remote: SocketAddress[IpAddress])

  private final case class LiveSource[F[_]](key: SourceKey, abort: PeerError => F[Unit])

  private val RetainedSessions = 64

  /** `runClients` off serves inbound connections without following them. */
  final case class Config(
      magic: Long,
      server: NodeToNodeServer.Settings,
      peer: Peer.Settings,
      runClients: Boolean = true,
      deactivateTimeout: FiniteDuration = 300.seconds
  )

  /** The node supplies its pool and owns its decision loop before serving connections (consensus
    * 82ecba329 NodeKernel.hs:382–393). An inbound connection never creates a hidden pool whose
    * fetch decisions have no owner.
    */
  def create[F[_]: Async](
      manager: ConnectionManager[F],
      config: Config,
      handlers: ServerHandlers[F, NtNHeader],
      follow: Follow[F],
      prepare: Peer.Prepare[F],
      fatal: Fatal => F[Unit],
      heightSnapshot: Peer.HeightSnapshot[F],
      pool: BlockPool[F],
      headers: Option[HeaderCheck[F]] = None,
      transactions: Option[TxSubmissionClient.Source[F]] = None,
      events: Option[InboundEvents[F]] = None,
      observe: Option[Peer.Observation => F[Unit]] = None,
      sharing: Option[PeerSharingRuntime[F]] = None,
      genesis: Option[GenesisRuntime[F]] = None,
      receivedBody: Option[Peer.BodySink[F]] = None,
      peerMetrics: Option[Ref[F, PeerMetrics[PeerNetwork.Addr]]] = None
  )(using node: Handle[F, NodeError], repromotion: RepromotionWait[F]): F[InboundGovernor[F]] =
    (
      Ref.of[F, Map[String, Session]](Map.empty),
      Ref.of[F, BigInt](0),
      Ref.of[F, Map[BigInt, LiveSource[F]]](Map.empty),
      Mutex[F]
    ).mapN { (sessions, counter, sources, lock) =>
      new InboundGovernor[F](
        node,
        fatal,
        manager,
        config,
        handlers,
        follow,
        prepare,
        heightSnapshot,
        headers,
        transactions,
        events,
        observe,
        peerMetrics,
        sharing,
        pool,
        genesis,
        receivedBody,
        repromotion,
        sessions,
        counter,
        sources,
        lock
      )
    }
