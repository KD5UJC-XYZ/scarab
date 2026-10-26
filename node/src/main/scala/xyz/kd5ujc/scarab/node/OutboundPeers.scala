// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Async, Deferred, Ref, Resource}
import cats.effect.std.{Mutex, Supervisor}
import cats.effect.syntax.all.*
import cats.mtl.{Handle, Raise, Stateful}
import cats.syntax.all.*
import com.comcast.ip4s.{IpAddress, SocketAddress}
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.consensus.ChainSelection
import xyz.kd5ujc.scarab.ledger.{NodeError, PeerError, ValidationError}
import xyz.kd5ujc.scarab.network.*
import xyz.kd5ujc.scarab.network.selection.*
import xyz.kd5ujc.scarab.protocol.{ConnectionState, NtNHeader}
import xyz.kd5ujc.scarab.protocol.ConnectionState.Provenance

/** How a peer's address becomes a bearer: the TCP connection in the node, an in-memory pair in the
  * tests of the governor with many peers.
  */
trait Transport[F[_], A]:
  /** The address the connection manager knows the peer by. */
  def resolve(peer: A): F[Either[PeerError, SocketAddress[IpAddress]]]

  /** Open the bearer to a resolved peer. */
  def bearer(peer: A, remote: SocketAddress[IpAddress])(using
      Raise[F, NetworkError]
  ): Resource[F, Bearer[F]]

/** The connections the governor opens, as the temperatures it moves peers between (the reference's
  * `withPeerStateActions`, `PeerSelection/PeerStateActions.hs` 590-):
  *
  *   - `establish` opens a connection through the connection manager, negotiates the handshake and
  *     runs KeepAlive: the peer is warm.
  *   - `activate` starts ChainSync, BlockFetch and TxSubmission2 and the [[Follower]] session over
  *     them: the peer is hot. The session owns its claims on the [[BlockPool]], so releasing the
  *     session releases whatever it still held (issue 130).
  *   - `deactivate` brings the session to rest between two batches, ends the hot protocols on the
  *     wire and leaves the connection warm; when that does not work within the deactivation
  *     timeout, or the session failed, the connection is closed.
  *   - `close` requests protocol termination and releases the connection; the governor job waits
  *     for the retained handle to become Cold.
  *
  * What the world does without being asked (a session fails, the bearer closes) is reported to the
  * governor as [[Event.Observed]] through `report`, once as the connection starts to go (`released =
  * false`) and once the manager has released it (`released = true`).
  *
  * Each connection is the resource of a fiber of its own and each hot session the resource of a
  * second one. Its retained result/control handles and status are observed by a separate monitor
  * under the outer `supervisor`, which survives both resources.
  */
final class OutboundPeers[F[_]: Async, A] private (
    node: Handle[F, NodeError],
    transport: Transport[F, A],
    manager: ConnectionManager[F],
    config: OutboundPeers.Config,
    hot: OutboundPeers.Hot[F],
    supervisor: Supervisor[F],
    report: Event[A] => F[Unit],
    metrics: Stateful[F, PeerMetrics[A]],
    table: Ref[F, Map[A, OutboundPeers.Link[F]]],
    connected: Option[(A, SocketAddress[IpAddress]) => F[Unit]],
    sourceCounter: Ref[F, BigInt],
    liveSources: Ref[F, Map[BigInt, PeerError => F[Unit]]],
    sourceLock: Mutex[F],
    handles: Ref[F, Map[A, PeerStateActions.Connection[F]]],
    connectionCounter: Ref[F, BigInt]
) extends PeerStateActions[F, A],
      PeerStateActions.Connections[F, A]:
  import OutboundPeers.*
  import Peer.networkAsPeer
  private val F = Async[F]

  // ------------------------------------------------------------------ warm

  def establish(
      peer: A,
      mode: DiffusionMode,
      provenance: Provenance
  ): F[Either[EstablishmentError, PeerStateActions.Connection[F]]] =
    establishAt(peer, mode, provenance, BigInt(0))

  override def establishAt(
      peer: A,
      mode: DiffusionMode,
      provenance: Provenance,
      generation: BigInt
  ): F[Either[EstablishmentError, PeerStateActions.Connection[F]]] =
    transport
      .resolve(peer)
      .flatMap {
        case Left(error)   => F.pure(Left(EstablishmentError.Failed(error)))
        case Right(remote) =>
          // Transfer ownership only when the ready wait succeeds. Cancellation during acquisition
          // releases that exact fiber; cancellation after return does not own the connection.
          F.uncancelable { poll =>
            for
              ready <- Deferred[F, Either[EstablishmentError, PeerStateActions.Connection[F]]]
              acquiring <- supervisor.supervise(connection(peer, remote, mode, generation, ready))
              result <- poll(ready.get).onCancel(acquiring.cancel)
            yield result
          }
      }
      .handleErrorWith { cause =>
        hot.fatal(Fatal.Defect(cause)).as(Left(EstablishmentError.Defect(cause)))
      }

  /** The life of one connection: opened, registered, held until the governor lets go of it or the
    * bearer closes, and released in every case. It runs in a fiber of its own, in an error scope of
    * its own: what it meets is a value ([[Fault]]) that it reports, never an exception.
    */
  private def connection(
      peer: A,
      remote: SocketAddress[IpAddress],
      mode: DiffusionMode,
      generation: BigInt,
      ready: Deferred[F, Either[EstablishmentError, PeerStateActions.Connection[F]]]
  ): F[Unit] =
    (
      Deferred[F, Unit],
      Deferred[F, Unit],
      Ref.of[F, Option[Link[F]]](None),
      Ref.of[F, Vector[Fatal.Defect]](Vector.empty)
    ).tupled.flatMap { (release, gone, linked, releaseDefects) =>
      def releaseOwnership: F[Unit] =
        table.update { current =>
          if current.get(peer).exists(_.release eq release) then current - peer else current
        } *> gone.complete(()).void

      def resetCold: F[Unit] = linked.get.flatMap(
        _.traverse_(link =>
          link.transition(
            link.phase.set(Phase.Cold()) *> link.warm.applications.notifyChange
          )
        )
      )

      def publishReleaseDefects(defects: Vector[Fatal.Defect]): F[Unit] =
        (releaseOwnership *> resetCold *> defects.traverse_(hot.fatal)).whenA(defects.nonEmpty)

      // The Resource has unwound before a startup failure reaches this boundary. Reset partial
      // installation before publishing Left; a monitor may not have been started yet.
      def startupFailed(error: EstablishmentError, notifyFatal: Boolean = true): F[Unit] =
        val cause = error match
          case EstablishmentError.Failed(problem) =>
            SessionEnd.networkFailure(SessionEnd.Failed(problem))
          case EstablishmentError.Defect(problem) => Some(NetworkError.Defect(problem))
          case EstablishmentError.Superseded      => None
        F.uncancelable { _ =>
          releaseOwnership *> linked.get.flatMap(
            _.traverse_(link =>
              link.transition(
                cause.traverse_(link.warm.applications.abortRunning) *>
                  link.phase.set(Phase.Cold()) *> link.warm.applications.notifyChange
              )
            )
          ) *> (error match
            case EstablishmentError.Defect(problem) =>
              hot.fatal(Fatal.Defect(problem)).whenA(notifyFatal)
            case _ => F.unit) *>
            ready.complete(Left(error)).void
        }

      val body: Raise[F, PeerError] ?=> F[Option[EstablishmentError]] =
        given Raise[F, ConnectionError] =
          Peer.contramapped[F, PeerError, ConnectionError](summon)(e =>
            PeerError.Unavailable(PeerError.Unavailability.Connection(e))
          )
        val open: Resource[F, (WarmConnection[F, NtNHeader, SessionEnd], Supervisor[F])] =
          for
            slot <- manager.outbound(remote, alongsideInbound = true)
            bearer <- transport.bearer(peer, remote)
            warm <- NodeToNode.warmOverWithResults[F, NtNHeader, SessionEnd](
              bearer,
              hot.magic,
              NtNHeader.codec,
              SessionEnd.Returned,
              hot.network,
              serve = mode match
                case DiffusionMode.InitiatorOnly         => None
                case DiffusionMode.InitiatorAndResponder => hot.serve
            )
            children <- Supervisor[F](await = false)
            _ <- Resource.eval(
              slot.connected *> slot.negotiated(
                if warm.duplex then ConnectionState.DataFlow.Duplex
                else ConnectionState.DataFlow.Unidirectional
              )
            )
          yield (warm, children)

        ConnectionResources
          .retainReleaseDefects(open)(defect => releaseDefects.update(_ :+ defect))
          .use { (warm, children) =>
            for
              phase <- Ref.of[F, Phase[F]](Phase.Warm())
              terminal <- Deferred[F, SessionEnd]
              transitions <- Mutex[F]
              identity <- connectionCounter.updateAndGet(_ + 1)
              link = Link(
                warm,
                release,
                gone,
                phase,
                generation,
                identity,
                terminal,
                transitions,
                children
              )
              handle = PeerStateActions.Connection(
                identity,
                generation,
                readConnection(link),
                trust => activate(peer, link, trust),
                deactivate(link),
                close(link),
                awaitCold(link)
              )
              installed <- table.modify { current =>
                if current.contains(peer) then (current, false)
                else (current.updated(peer, link), true)
              }
              result <-
                if !installed then F.pure(Some(EstablishmentError.Superseded))
                else
                  (linked.set(Some(link)) *>
                    handles.update(_.updated(peer, handle)) *>
                    connected.traverse_(_(peer, remote)) *>
                    report(
                      Event.SharingNegotiated(peer, warm.peerSharing.isDefined, Some(generation))
                    ) *>
                    supervisor.supervise(peerMonitoringLoop(peer, link)).void *>
                    ready.complete(Right(handle)) *>
                    F.race(release.get, warm.mux.closed).flatMap {
                      case Left(_)      => F.unit
                      case Right(error) => warm.applications.abortRunning(error)
                    }).as(None)
            yield result
          }

      def failed(error: EstablishmentError, notifyFatal: Boolean = true): F[Unit] =
        ready.tryGet.flatMap {
          case Some(Right(_)) =>
            error match
              case EstablishmentError.Failed(problem) =>
                linked.get.flatMap(_.traverse_(publish(_, SessionEnd.Failed(problem))))
              case EstablishmentError.Defect(_)  => startupFailed(error, notifyFatal)
              case EstablishmentError.Superseded => F.unit
          case _ => startupFailed(error, notifyFatal)
        }

      F.uncancelable { poll =>
        Fault.fiber(poll(body)).flatMap { outcome =>
          releaseDefects.getAndSet(Vector.empty).flatMap { defects =>
            publishReleaseDefects(defects) *> (outcome match
              case Right(error)              => error.traverse_(startupFailed(_))
              case Left(Fault.Failed(error)) => failed(EstablishmentError.Failed(error))
              case Left(Fault.Defect(cause)) =>
                failed(EstablishmentError.Defect(cause), !defects.exists(_.cause eq cause)))
          }
        }
      }.guarantee(
        releaseOwnership *> ready.tryGet.flatMap {
          case Some(Right(_)) => F.unit
          case _              => resetCold
        } *> releaseDefects.getAndSet(Vector.empty).flatMap(publishReleaseDefects)
      )
    }

  /** Producers publish values; only the per-connection monitor handles asynchronous demotions. */
  private def publish(link: Link[F], end: SessionEnd): F[Unit] =
    link.terminal.complete(end).void *> link.warm.applications.notifyChange

  private def stop(link: Link[F], fatal: Fatal): F[Unit] =
    val end = fatal match
      case Fatal.Unavailable(error) => SessionEnd.Unavailable(error)
      case Fatal.Storage(error)     => SessionEnd.Storage(error)
      case Fatal.Internal(error)    => SessionEnd.Internal(error)
      case Fatal.Defect(cause)      => SessionEnd.Defect(cause)
    publish(link, end)

  /** One job outside both the bearer and the hot session: PeerStateActions.hs:662–776,838–848 at
    * ouroboros-network 2fb69829. The loop observes retained application results, handles a clean
    * hot return by deactivation, and waits for manager release before Cooling becomes Cold.
    */
  private def peerMonitoringLoop(peer: A, link: Link[F]): F[Unit] =
    def failed(error: NetworkError, demoting: Boolean = false): F[Unit] =
      link.warm.applications.abortRunning(error) *> (error match
        case NetworkError.BearerClosed(NetworkError.Closure.AbortedBy(problem: Fatal)) =>
          fatal(problem)
        case other =>
          Peer.networkFault(other) match
            case Fault.Failed(problem) => lost(problem, demoting)
            case Fault.Defect(cause)   => fatal(Fatal.Defect(cause)))

    def cooling: F[Boolean] = link.transition(
      link.phase
        .modify {
          case phase @ (Phase.Cooling() | Phase.Cold()) => (phase, false)
          case _                                        => (Phase.Cooling(), true)
        }
        .flatTap(_ => link.warm.applications.notifyChange)
    )

    def cold: F[Unit] = link.gone.get *> link.transition(
      link.phase.set(Phase.Cold()) *> link.warm.applications.notifyChange
    )

    def fatal(problem: Fatal): F[Unit] =
      cooling *> link.release.complete(()).void *> hot.fatal(problem) *> cold

    def lost(error: PeerError, demoting: Boolean = false): F[Unit] =
      cooling.flatMap { changed =>
        def observed(released: Boolean): F[Unit] = report(
          Event.Observed(
            peer,
            Observation
              .Lost(FailurePolicy.classify(error), config.repromoteDelay, released, error.message),
            Some(link.rootsGeneration),
            Some(link.identity)
          )
        )
        link.release.complete(()).void *>
          (hot.lost(error) *> observed(false)).whenA(changed || demoting) *>
          cold *> observed(true).whenA(changed || demoting)
      }

    def ended(end: SessionEnd): F[Unit] =
      SessionEnd.networkFailure(end).traverse_(link.warm.applications.abortRunning) *>
        (FailurePolicy.disposition(end) match
          case Disposition.StopNode(problem) => fatal(problem)
          case Disposition.Forget | Disposition.Backoff =>
            SessionEnd.peerError(end).traverse_(lost(_))
          case Disposition.Hold(_) =>
            lost(PeerError.Unavailable(PeerError.Unavailability.SessionEnded))
          case Disposition.Settled => F.unit)

    val loop = F.tailRecM(()) { _ =>
      for
        observed <- link.transition(
          (link.warm.applications.snapshot, link.terminal.tryGet, link.phase.get).tupled
        )
        (snapshot, terminal, phase) = observed
        next <- terminal match
          case Some(end) => ended(end).as(Right(()))
          case None =>
            phase match
              case Phase.Cold()    => F.pure(Right(()))
              case Phase.Cooling() => cold.as(Right(()))
              case Phase.Warm() | Phase.Hot(_) =>
                snapshot.first match
                  case Some((_, _, Left(error))) => failed(error).as(Right(()))
                  case Some((_, ProtocolResults.Temperature.Hot, Right(_))) =>
                    phase match
                      case Phase.Hot(session) =>
                        deactivate(link, Some(peer -> session)).flatMap {
                          case Right(PeerStateActions.Deactivation.Warm) => F.pure(Left(()))
                          case Left(failure) =>
                            report(
                              Event.Observed(
                                peer,
                                Observation.ActionFailed(PeerOperation.Deactivate, failure.cause),
                                Some(link.rootsGeneration),
                                Some(link.identity)
                              )
                            ) *>
                              link.warm.mux.closed.flatMap(failed(_, demoting = true)).as(Right(()))
                          case _ =>
                            link.warm.mux.closed.flatMap(failed(_, demoting = true)).as(Right(()))
                        }
                      case _ => F.pure(Left(()))
                  case Some((protocol, _, Right(_))) =>
                    close(link).flatMap {
                      case Left(failure) =>
                        report(
                          Event.Observed(
                            peer,
                            Observation.ActionFailed(PeerOperation.Close, failure.cause),
                            Some(link.rootsGeneration),
                            Some(link.identity)
                          )
                        )
                      case Right(result)
                          if result.completion == PeerStateActions.CloseCompletion.TimedOut =>
                        report(
                          Event.Observed(
                            peer,
                            Observation.ActionFailed(
                              PeerOperation.Close,
                              PeerActionError.TimedOut(PeerOperation.Close)
                            ),
                            Some(link.rootsGeneration),
                            Some(link.identity)
                          )
                        )
                      case _ => F.unit
                    } *> cold *> readConnection(link)
                      .flatMap { current =>
                        report(
                          Event.Observed(
                            peer,
                            Observation.Lost(
                              FailureClass.Transient,
                              current.repromoteDelay.getOrElse(Duration.Zero),
                              released = true,
                              s"mini-protocol $protocol returned"
                            ),
                            Some(link.rootsGeneration),
                            Some(link.identity)
                          )
                        )
                      }
                      .as(Right(()))
                  case None =>
                    F.race(snapshot.changed, F.race(link.terminal.get, link.warm.mux.closed))
                      .flatMap {
                        case Left(_)             => F.pure(Left(()))
                        case Right(Left(end))    => ended(end).as(Right(()))
                        case Right(Right(error)) => failed(error).as(Right(()))
                      }
      yield next
    }
    loop.handleErrorWith(cause => fatal(Fatal.Defect(cause)))

  /** Match the adding hot BlockFetch client, never a later activation on the same bearer. The
    * bounded pool reporter retains only immutable metadata; resource-owned callbacks disappear
    * before release of their mux. Native mkPunishThisThread captures the adding ThreadId
    * (82ecba329, ChainDB/API/Types/InvalidBlockPunishment.hs:48–56).
    */
  private def source(
      peer: A,
      rootsGeneration: BigInt,
      abort: PeerError => F[Unit]
  ): Resource[F, SourceKey[A]] =
    Resource
      .eval(sourceCounter.modify(n => (n + 1, SourceKey(peer, rootsGeneration, n + 1))))
      .flatMap { key =>
        Resource.make(
          sourceLock.lock
            .surround(liveSources.update(_ + (key.activationGeneration -> abort)))
            .as(key)
        )(_ => retire(key))
      }

  private def retire(key: SourceKey[A]): F[Unit] =
    sourceLock.lock.surround(liveSources.update(_ - key.activationGeneration))

  private[node] def cachedSource(peer: A): F[Option[SourceKey[A]]] =
    table.get
      .flatMap(_.get(peer).traverse { link =>
        link.phase.get.flatMap {
          case Phase.Hot(session) =>
            session.source.get.map(_.map(SourceKey(peer, link.rootsGeneration, _)))
          case _ => F.pure(None)
        }
      })
      .map(_.flatten)

  private[node] def rejectedCachedBlock(key: SourceKey[A], refusal: ValidationError): F[Unit] =
    F.uncancelable { _ =>
      sourceLock.lock
        .surround(liveSources.get.flatMap { current =>
          val abort = current.get(key.activationGeneration)
          abort.traverse_(_(PeerError.InvalidBlock(refusal))).as(abort.isDefined)
        })
        .flatMap(live =>
          report(
            Event.Observed(
              key.peer,
              Observation
                .CachedBlockRejected(SourceRefusal.Typed(refusal), key.activationGeneration, live),
              Some(key.rootsGeneration)
            )
          )
        )
    }

  /** A request starts in its own scope. Domain network failures terminate that mini-protocol's
    * connection; defects stop the node without peer blame. A missing controller is no answer.
    */
  def requestPeerShare(peer: A, amount: xyz.kd5ujc.scarab.protocol.PeerSharing.Amount)(
      address: xyz.kd5ujc.scarab.protocol.PeerSharing.Address => Option[A]
  ): F[PeerShare.Result[A]] =
    table.get.map(_.get(peer)).flatMap {
      case None => F.pure(PeerShare.Result.NotRegistered())
      case Some(link) =>
        link.warm.peerSharing match
          case None => F.pure(PeerShare.Result.NotRegistered())
          case Some(client) =>
            Fibers
              .scoped[F, NetworkError, Vector[xyz.kd5ujc.scarab.protocol.PeerSharing.Address]](
                NetworkError.fromThrowable("peer-sharing", _)
              )(client.request(amount))
              .flatMap {
                case Right(peers) => F.pure(PeerShare.Result.Peers(peers.flatMap(address)))
                case Left(NetworkError.Defect(cause)) =>
                  stop(link, Fatal.Defect(cause)).as(PeerShare.Result.NotRegistered())
                case Left(error) => link.warm.mux.abort(error).as(PeerShare.Result.Failed(error))
              }
    }

  // ------------------------------------------------------------------ hot

  override def activate(
      peer: A,
      trust: ChainSyncTimeouts.PeerTrust
  ): F[Either[PeerActionError, Unit]] =
    table.get.map(_.get(peer)).flatMap {
      case None => F.pure(Left(PeerActionError.Inactive(PeerOperation.Activate, PeerStatus.Cold)))
      case Some(link) => activate(peer, link, trust)
    }

  private def activate(
      peer: A,
      link: Link[F],
      trust: ChainSyncTimeouts.PeerTrust
  ): F[Either[PeerActionError, Unit]] =
    Session.create[F].flatMap { session =>
      Deferred[F, Either[PeerActionError, Unit]].flatMap { started =>
        link
          .transition(link.phase.get.flatMap {
            case Phase.Warm() =>
              for
                _ <- link.warm.applications.activateHot
                results <- link.warm.applications.startHot(hot.transactions.nonEmpty)
                _ <- session.result.complete(results.chainSync)
                _ <- link.phase.set(Phase.Hot(session))
                _ <- link.children
                  .supervise(hotSession(peer, link, session, started, trust, results))
                _ <- link.warm.applications.notifyChange
              yield Right(())
            case phase =>
              val status = phase match
                case Phase.Hot(_)    => PeerStatus.Hot
                case Phase.Cooling() => PeerStatus.Cooling
                case _               => PeerStatus.Cold
              F.pure(Left(PeerActionError.Inactive(PeerOperation.Activate, status)))
          })
          .flatMap {
            case Left(error) => F.pure(Left(error))
            case Right(_) =>
              started.get.flatMap {
                case Right(_) => F.pure(Right(()))
                case Left(error) =>
                  close(link).map {
                    case Left(failure) => Left(failure.cause)
                    case _             => Left(error)
                  }
              }
          }
      }
    }

  /** What the session of `peer` observes: the node's own observer, and the peer metrics (who is
    * first with a header and a block), one update of the shared board per batch of headers and per
    * range of blocks.
    */
  private def metered(peer: A): Peer.Observation => F[Unit] =
    given Stateful[F, PeerMetrics[A]] = metrics
    PeerMetricsObserver(peer, hot.observe)

  /** The hot protocols and the session over them, for as long as the session lasts. The session
    * runs in an error scope of its own and ends with a [[SessionEnd]], a value: what the peer does,
    * what our rules and stores raise through the node's scope, and what throws, all become one of
    * its cases in this fiber, and [[FailurePolicy.disposition]] says what the node does about it.
    */
  private def hotSession(
      peer: A,
      link: Link[F],
      session: Session[F],
      started: Deferred[F, Either[PeerActionError, Unit]],
      trust: ChainSyncTimeouts.PeerTrust,
      results: ProtocolResults.Hot[F, SessionEnd]
  ): F[Unit] =
    def asked: F[Boolean] = session.asked.get

    /** Run the follower session over the peer; stopping it ends it with `Stopped`. */
    def follow(over: Peer[F]): F[SessionEnd] =
      SessionEnd.scoped(node) { (scope, _) ?=>
        val gated = new Peer[F]:
          override def headerCandidate = over.headerCandidate
          override def selectionWork = over.selectionWork
          override def invalidBlocks = over.invalidBlocks
          override def invalidated(
              header: xyz.kd5ujc.scarab.ledger.Header,
              refusal: ValidationError
          )(using cats.Applicative[F]) = over.invalidated(header, refusal)
          override def validation(header: xyz.kd5ujc.scarab.ledger.Header)(using
              cats.Applicative[F],
              Raise[F, PeerError]
          ) = over.validation(header)
          override def rejected(refusal: ValidationError)(using cats.Applicative[F]) =
            over.rejected(refusal)
          def intersect(candidates: Vector[xyz.kd5ujc.scarab.protocol.ChainSync.Point])(using
              Raise[F, PeerError]
          ) = over.intersect(candidates)
          def next(using Raise[F, PeerError]) =
            F.race(
              link.warm.applications.awaitTermination(ProtocolResults.Temperature.Hot),
              Fault.fiber(over.next)
            ).flatMap {
              case Left(_)       => scope.raise[SessionEnd, SyncEvent](SessionEnd.Stopped(false))
              case Right(result) => Fault.land(result)
            }
          def fetch(header: xyz.kd5ujc.scarab.ledger.Header)(using Raise[F, PeerError]) =
            over.fetch(header)
        hot
          .follow(gated, candidate => session.candidate.complete(candidate).void)
          .as(SessionEnd.Returned)
      }

    val run: F[SessionEnd] =
      SessionEnd.scoped(node) { (_, _) ?=>
        source(
          peer,
          link.rootsGeneration,
          error =>
            link.warm.mux.abort(NetworkError.BearerClosed(NetworkError.Closure.AbortedBy(error)))
        ).evalTap(key => session.source.set(Some(key.activationGeneration)))
          .onFinalize(session.source.set(None))
          .flatMap(key =>
            link.warm
              .startHotProtocols(
                results,
                hot.transactions,
                Some(retire(key)),
                trust,
                Some(result => session.result.complete(result).void)
              )
              .map(_ -> key)
          )
          .use { (connection, sourceKey) =>
            GenesisRuntime
              .controls(
                hot.genesis,
                Some(hot.pool),
                peer.hashCode.toLong,
                connection.mux.connectionId,
                error =>
                  connection.mux
                    .abort(NetworkError.BearerClosed(NetworkError.Closure.AbortedBy(error))),
                problem => stop(link, Fatal.Internal(problem))
              )
              .use { (fetching, genesis) =>
                Peer
                  .over(
                    connection,
                    hot.peer,
                    hot.prepare,
                    hot.heightSnapshot,
                    genesis
                      .fold(hot.headers)(bound => hot.headers.map(_.withPatience(bound.patience))),
                    Some(metered(peer)),
                    Some(hot.pool),
                    Some(rejectedCachedBlock(sourceKey, _)),
                    fetchIdentity = Some(peer.hashCode.toLong),
                    patience = genesis.map(_.patience),
                    received = genesis.map(_.received),
                    chainSyncObserver = genesis.map(_.observer),
                    fetchSession = fetching,
                    genesis = genesis.map(_.control),
                    receivedBody = hot.receivedBody,
                    retainedHeaders = genesis.fold(BlockPool.Retained)(_.retainedHeaders),
                    selectionWork = genesis.map(_.work)
                  )
                  .use { over =>
                    session.connection.complete(connection) *> started.complete(Right(())) *>
                      follow(over)
                        .flatMap {
                          // the pool is shared: a block that another session delivered and the rules
                          // refuse is no fault of this peer, which fetches it itself when it tries again
                          case SessionEnd.Rejected(refusal) =>
                            over
                              .refused(refusal)
                              .map(foreign =>
                                if foreign then
                                  SessionEnd.Failed(
                                    PeerError
                                      .Diverged(PeerError.Divergence.ForeignBlock(refusal))
                                  )
                                else SessionEnd.Rejected(refusal)
                              )
                          case other => F.pure(other)
                        }
                        .flatTap {
                          // asked to stop: bring the pipeline to rest before the wire is ended
                          case SessionEnd.Stopped(_) => asked.flatMap(a => over.quiesce.whenA(a))
                          case end
                              if SessionEnd
                                .returnDelay(end)
                                .nonEmpty || end == SessionEnd.Returned =>
                            session.result.get.flatMap(_.complete(Right(end))) *> over.quiesce
                          case end =>
                            publish(link, end).whenA(
                              SessionEnd.networkFailure(end).nonEmpty
                            ) *>
                              SessionEnd.networkFailure(end).traverse_(connection.mux.abort)
                        }
                  }
                  .flatMap {
                    case SessionEnd.Stopped(_) =>
                      connection.finishGracefully(false).as(SessionEnd.Stopped(true))
                    case SessionEnd.Returned =>
                      connection.finishGracefully(false).as(SessionEnd.Returned)
                    case end if SessionEnd.returnDelay(end).nonEmpty =>
                      connection.finishGracefully(true).as(end)
                    case other => F.pure(other)
                  }
              }
          }
      }

    // Save terminal values before abort/cleanup can cancel this producer; reporting belongs to
    // the connection monitor, which is not a child of this session or bearer.
    run
      .onCancel(link.terminal.tryGet.flatMap { terminal =>
        val error = terminal
          .flatMap(SessionEnd.networkFailure)
          .getOrElse(NetworkError.BearerClosed(NetworkError.Closure.Aborted))
        session.result.tryGet.flatMap(_.traverse_(_.complete(Left(error)))) *>
          started.complete(Left(PeerActionError.ConnectionFailure(error))).void *>
          link.warm.applications.notifyChange
      })
      .flatMap { end =>
        val result =
          if end == SessionEnd.Returned || end == SessionEnd
              .Stopped(true) || SessionEnd.returnDelay(end).nonEmpty
          then Right(end)
          else
            Left(
              SessionEnd
                .networkFailure(end)
                .getOrElse(NetworkError.BearerClosed(NetworkError.Closure.Aborted))
            )
        session.result.tryGet
          .flatMap(_.traverse_(_.complete(result))) *>
          started.complete(result.leftMap(PeerActionError.ConnectionFailure(_)).void).void *>
          (FailurePolicy.disposition(end) match
            case Disposition.Settled => F.unit
            case _                   => publish(link, end)) *> link.warm.applications.notifyChange
      }

  def deactivate(peer: A): F[PeerStateActions.Result[PeerStateActions.Deactivation]] =
    table.get.map(_.get(peer)).flatMap {
      case Some(link) => deactivate(link)
      case None =>
        F.pure(
          Left(
            PeerStateActions.Failure(
              PeerActionError.Inactive(PeerOperation.Deactivate, PeerStatus.Cold),
              PeerStateActions.Release.Released
            )
          )
        )
    }

  private def released(link: Link[F]): F[PeerStateActions.Release] =
    link.gone.tryGet.map(
      _.fold(PeerStateActions.Release.Lingering)(_ => PeerStateActions.Release.Released)
    )

  private def awaitCold(link: Link[F]): F[Unit] = F.tailRecM(()) { _ =>
    link.transition((link.phase.get, link.warm.applications.snapshot).tupled).flatMap {
      case (Phase.Cold(), _) => F.pure(Right(()))
      case (_, snapshot)     => snapshot.changed.as(Left(()))
    }
  }

  /** Preserve all application failures at the action boundary. Today's mux abort accepts one cause;
    * that projection is separate from the aggregate and every fatal cause reaches its sink.
    */
  private def abortAction(link: Link[F], error: PeerActionError): F[PeerStateActions.Failure] =
    val failures = error match
      case PeerActionError.ConnectionFailure(error)    => Vector(error)
      case PeerActionError.ApplicationFailures(errors) => errors.toVector.map(_.error)
      case PeerActionError.TimedOut(operation) =>
        Vector(NetworkError.Timeout(Protocols.ChainSync, operation.toString))
      case PeerActionError.Inactive(_, _) => Vector.empty
    val fatals = failures.flatMap {
      case NetworkError.BearerClosed(NetworkError.Closure.AbortedBy(problem: Fatal)) =>
        Vector(problem)
      case cause =>
        Peer.networkFault(cause) match
          case Fault.Defect(problem) => Vector(Fatal.Defect(problem))
          case _                     => Vector.empty
    }
    fatals.traverse_(hot.fatal) *>
      failures.headOption.traverse_(cause =>
        link.warm.applications.abortRunning(cause) *> link.warm.mux.abort(cause)
      ) *>
      link.release.complete(()).void *> released(link).map(PeerStateActions.Failure(error, _))

  private def deactivate(
      link: Link[F],
      observed: Option[(A, Session[F])] = None
  ): F[PeerStateActions.Result[PeerStateActions.Deactivation]] =
    def closed: F[PeerStateActions.Result[PeerStateActions.Deactivation]] = released(link).map {
      case PeerStateActions.Release.Released  => Right(PeerStateActions.Deactivation.Closed)
      case PeerStateActions.Release.Lingering => Right(PeerStateActions.Deactivation.Lingering)
    }
    def finish(
        session: Session[F],
        notify: Option[A]
    ): F[PeerStateActions.Result[PeerStateActions.Deactivation]] =
      val all = link.warm.applications.awaitAll(Set(ProtocolResults.Temperature.Hot)).flatMap {
        case ProtocolResults.LastToFinish.SomeErrored(errors) =>
          F.pure(Left(PeerActionError.ApplicationFailures(errors)))
        case ProtocolResults.LastToFinish.AllSucceeded(results) => F.pure(Right(results))
      }
      F.timeoutTo(
        all,
        config.deactivateTimeout,
        F.pure(Left(PeerActionError.TimedOut(PeerOperation.Deactivate)))
      ).flatMap {
        case Right(results) =>
          link
            .transition(link.phase.get.flatMap {
              case Phase.Hot(current) if current eq session =>
                link.warm.applications.retireHot *> link.phase.set(Phase.Warm()) *>
                  link.warm.applications.notifyChange.as((Phase.Warm(): Phase[F], true))
              case phase => F.pure((phase, false))
            })
            .flatMap { (phase, changed) =>
              notify
                .traverse_(peer =>
                  report(
                    Event.Observed(
                      peer,
                      Observation.HotEnded(
                        results.values.map(SessionEnd.initiatorReturnDelay).maxOption,
                        results.get(Protocols.ChainSync).flatMap(SessionEnd.returnReason)
                      ),
                      Some(link.rootsGeneration),
                      Some(link.identity)
                    )
                  )
                )
                .whenA(changed) *>
                (phase match
                  case Phase.Warm() | Phase.Hot(_) =>
                    F.pure(Right(PeerStateActions.Deactivation.Warm))
                  case _ => closed)
            }
        case Left(error) =>
          link
            .transition(link.phase.get.flatMap {
              case Phase.Hot(current) if current eq session =>
                link.phase.set(Phase.Cooling()) *> link.warm.applications.notifyChange.as(true)
              case Phase.Cooling() | Phase.Cold() => F.pure(true)
              case _                              => F.pure(false)
            })
            .flatMap {
              case true  => abortAction(link, error).map(Left(_))
              case false => F.pure(Right(PeerStateActions.Deactivation.Warm))
            }
      }
    link
      .transition(link.phase.get.flatMap {
        case Phase.Warm() => F.pure(F.pure(Right(PeerStateActions.Deactivation.Warm)))
        case Phase.Hot(session) if observed.forall((_, expected) => expected eq session) =>
          session.asked.getAndSet(true).flatMap { asked =>
            link.warm.applications.terminateHot
              .as(finish(session, observed.map(_._1).filterNot(_ => asked)))
          }
        case Phase.Hot(_) => F.pure(F.pure(Right(PeerStateActions.Deactivation.Warm)))
        case phase =>
          val status = phase match
            case Phase.Cold() => PeerStatus.Cold
            case _            => PeerStatus.Cooling
          F.pure(
            released(link).map(release =>
              Left(
                PeerStateActions
                  .Failure(PeerActionError.Inactive(PeerOperation.Deactivate, status), release)
              )
            )
          )
      })
      .flatten

  // ------------------------------------------------------------------ cold

  def close(peer: A): F[PeerStateActions.Result[PeerStateActions.CloseResult]] =
    table.get
      .map(_.get(peer))
      .flatMap(
        _.fold(
          F.pure(
            Right(
              PeerStateActions
                .CloseResult(PeerStateActions.Release.Released, status = PeerStatus.Cold)
            ): PeerStateActions.Result[PeerStateActions.CloseResult]
          )
        )(close)
      )

  override def closeIfGeneration(
      peer: A,
      generation: BigInt
  ): F[Option[PeerStateActions.Result[PeerStateActions.CloseResult]]] =
    table.get.map(_.get(peer)).flatMap {
      case Some(link) if link.rootsGeneration == generation => close(link).map(Some(_))
      case _                                                => F.pure(None)
    }

  private def close(link: Link[F]): F[PeerStateActions.Result[PeerStateActions.CloseResult]] =
    def completed(
        completion: PeerStateActions.CloseCompletion = PeerStateActions.CloseCompletion.Clean,
        status: PeerStatus = PeerStatus.Cooling
    ) =
      released(link).map(release =>
        Right(PeerStateActions.CloseResult(release, completion, status)): PeerStateActions.Result[
          PeerStateActions.CloseResult
        ]
      )
    link
      .transition(link.phase.get.flatMap { phase =>
        val markRequested = phase match
          case Phase.Hot(session) => session.asked.set(true)
          case _                  => F.unit
        markRequested *> link.warm.applications.terminateAll *>
          link.phase.set(phase match
            case Phase.Cold() | Phase.Cooling() => phase
            case _                              => Phase.Cooling()) *>
          link.warm.applications.notifyChange.as(phase)
      })
      .flatMap {
        case Phase.Cold()    => completed(status = PeerStatus.Cold)
        case Phase.Cooling() => completed()
        case _ =>
          val stopApplications = link.warm.stopEstablished *>
            link.warm.applications.awaitAll(ProtocolResults.Temperature.values.toSet)
          F.timeoutTo(stopApplications.map(Some(_)), config.closeTimeout, F.pure(None)).flatMap {
            case Some(ProtocolResults.LastToFinish.SomeErrored(errors)) =>
              abortAction(link, PeerActionError.ApplicationFailures(errors)).map(Left(_))
            case Some(ProtocolResults.LastToFinish.AllSucceeded(_)) =>
              link.release.complete(()).void *> completed()
            case None =>
              val error = NetworkError.Timeout(Protocols.KeepAlive, "close")
              link.warm.applications.abortRunning(error) *> link.warm.mux.abort(error) *>
                link.release.complete(()).void *> completed(
                  PeerStateActions.CloseCompletion.TimedOut
                )
          }
      }

  /** The handle is retained until the governor forgets its peer. Holding an older handle keeps its
    * final Cold snapshot even after a new connection is installed at the same address.
    */
  def monitorPeerConnection(peer: A): F[Option[PeerStateActions.Connection[F]]] =
    handles.get.map(_.get(peer))

  def retainConnections(known: Set[A]): F[Unit] = handles.update(_.filter((peer, _) => known(peer)))

  private def readConnection(link: Link[F]): F[PeerStateActions.Monitoring] =
    link.transition(
      (link.phase.get, link.warm.applications.snapshot, link.terminal.tryGet).mapN {
        (phase, applications, terminal) =>
          val delays = applications.entries.values.toVector.flatMap(_.result match
            case ProtocolResults.Result.Errored(_) | ProtocolResults.Result.NotRunning(Left(_)) =>
              Vector(config.repromoteDelay)
            case ProtocolResults.Result.Returned(value) =>
              Vector(SessionEnd.initiatorReturnDelay(value))
            case ProtocolResults.Result.NotRunning(Right(value)) =>
              Vector(SessionEnd.initiatorReturnDelay(value))
            case _ => Vector.empty) ++ terminal
            .flatMap(SessionEnd.networkFailure)
            .map(_ => config.repromoteDelay)
          val delay = delays.maxOption
          val failure = terminal
            .flatMap(SessionEnd.peerError)
            .orElse(
              applications.first.flatMap {
                case (_, _, Left(error)) =>
                  Peer.networkFault(error) match
                    case Fault.Failed(problem) => Some(problem)
                    case Fault.Defect(_)       => None
                case _ => None
              }
            )
          phase match
            case Phase.Warm() =>
              PeerStateActions.Monitoring(PeerStateActions.Status.Warm, delay, failure)
            case Phase.Hot(_) =>
              PeerStateActions.Monitoring(PeerStateActions.Status.Hot, delay, failure)
            case Phase.Cooling() =>
              PeerStateActions.Monitoring(PeerStateActions.Status.Cooling, None, failure)
            case Phase.Cold() =>
              PeerStateActions.Monitoring(PeerStateActions.Status.Cold, delay, failure)
      }
    )

  // ------------------------------------------------------------------ what is hot

  /** What each hot session says for the Genesis State Machine: whether its peer is idle and how its
    * candidate chain compares with our selection.
    */
  def readings: F[Vector[Gsm.PeerReading]] =
    table.get.flatMap(_.values.toVector.flatTraverse { link =>
      link.phase.get.flatMap {
        case Phase.Hot(session) =>
          (session.connection.tryGet, session.candidate.tryGet).tupled.flatMap {
            case (Some(connection), Some(candidate)) =>
              (connection.chainSync.idling, candidate.get.flatMap(hot.versus))
                .mapN(Gsm.PeerReading(_, _))
                .map(Vector(_))
            case _ => F.pure(Vector.empty[Gsm.PeerReading])
          }
        case _ => F.pure(Vector.empty[Gsm.PeerReading])
      }
    })

object OutboundPeers:
  /** @param deactivateTimeout
    *   How long the hot protocols get to end on the wire (`deactivateTimeout`, `Policies.hs` 31).
    * @param repromoteDelay
    *   The exit policy's wait before a peer whose connection failed is tried again.
    */
  final case class Config(
      deactivateTimeout: FiniteDuration = 300.seconds,
      repromoteDelay: FiniteDuration = 10.seconds,
      closeTimeout: FiniteDuration = 120.seconds
  )

  /** Receives the candidate chain of a session once it exists. */
  type Tracker[F[_]] = Ref[F, ChainSelection.Candidate] => F[Unit]

  /** The follower session over a peer, which hands its candidate chain to the tracker. */
  type Follow[F[_]] = (Peer[F], Tracker[F]) => Raise[F, NodeError] ?=> F[Unit]

  /** What the hot part needs, besides the connection: the follower session, the pool, the header
    * check, the transactions, the settings and how a candidate compares with our selection.
    *
    * @param lost
    *   A connection was lost (once per loss): the log's `reconnecting`.
    * @param fatal
    *   A session ended in a way no peer policy handles (storage, an internal failure, a defect):
    *   the node stops ([[FailurePolicy.disposition]]).
    * @param serve
    *   Responder protocols served on outbound peers whose diffusion mode is duplex.
    */
  final case class Hot[F[_]](
      magic: Long,
      network: NodeToNode.Settings,
      peer: Peer.Settings,
      follow: Follow[F],
      prepare: Peer.Prepare[F],
      heightSnapshot: Peer.HeightSnapshot[F],
      headers: Option[HeaderCheck[F]],
      transactions: Option[TxSubmissionClient.Source[F]],
      observe: Option[Peer.Observation => F[Unit]],
      pool: BlockPool[F],
      versus: ChainSelection.Candidate => F[Gsm.Candidate],
      lost: PeerError => F[Unit],
      fatal: Fatal => F[Unit],
      serve: Option[ServerHandlers[F, NtNHeader]] = None,
      genesis: Option[GenesisRuntime[F]] = None,
      receivedBody: Option[Peer.BodySink[F]] = None
  )

  /** A connection of ours, and what its owner can do to it.
    *
    * @param release
    *   Completed to let go of the connection.
    * @param gone
    *   Completed once the connection has been released everywhere.
    */
  private[node] final class Link[F[_]](
      val warm: WarmConnection[F, NtNHeader, SessionEnd],
      val release: Deferred[F, Unit],
      val gone: Deferred[F, Unit],
      val phase: Ref[F, Phase[F]],
      val rootsGeneration: BigInt,
      val identity: BigInt,
      val terminal: Deferred[F, SessionEnd],
      val transitions: Mutex[F],
      val children: Supervisor[F]
  ):
    def transition[B](body: F[B])(using F: Async[F]): F[B] =
      transitions.lock.surround(F.uncancelable(_ => body))

  private[node] final case class SourceKey[A](
      peer: A,
      rootsGeneration: BigInt,
      activationGeneration: BigInt
  )

  private[node] enum Phase[F[_]]:
    case Warm()
    case Hot(session: Session[F])

    case Cooling()
    case Cold()

  /** One hot period. Termination is read from the shared hot application control; the retained
    * result is scoped to this activation, including its normal ChainSync return value.
    */
  private[node] final class Session[F[_]](
      val asked: Ref[F, Boolean],
      val connection: Deferred[F, NodeToNodeConnection[F, NtNHeader]],
      val candidate: Deferred[F, Ref[F, ChainSelection.Candidate]],
      val source: Ref[F, Option[BigInt]],
      val result: Deferred[F, ProtocolResults.Run[F, SessionEnd]]
  )

  private[node] object Session:
    def create[F[_]: Async]: F[Session[F]] =
      (
        Ref.of[F, Boolean](false),
        Deferred[F, NodeToNodeConnection[F, NtNHeader]],
        Deferred[F, Ref[F, ChainSelection.Candidate]],
        Ref.of[F, Option[BigInt]](None),
        Deferred[F, ProtocolResults.Run[F, SessionEnd]]
      ).mapN(new Session[F](_, _, _, _, _))

  def create[F[_]: Async, A](
      transport: Transport[F, A],
      manager: ConnectionManager[F],
      config: Config,
      hot: Hot[F],
      supervisor: Supervisor[F],
      report: Event[A] => F[Unit],
      metrics: Stateful[F, PeerMetrics[A]],
      connected: Option[(A, SocketAddress[IpAddress]) => F[Unit]] = None
  )(using node: Handle[F, NodeError]): F[OutboundPeers[F, A]] =
    (
      Ref.of[F, Map[A, Link[F]]](Map.empty),
      Ref.of[F, BigInt](0),
      Ref.of[F, Map[BigInt, PeerError => F[Unit]]](Map.empty),
      Mutex[F],
      Ref.of[F, Map[A, PeerStateActions.Connection[F]]](Map.empty),
      Ref.of[F, BigInt](0)
    ).mapN { (table, counter, sources, lock, handles, connections) =>
      new OutboundPeers[F, A](
        node,
        transport,
        manager,
        config,
        hot,
        supervisor,
        report,
        metrics,
        table,
        connected,
        counter,
        sources,
        lock,
        handles,
        connections
      )
    }
