// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Async, Deferred, Ref, Resource}
import cats.effect.std.Supervisor
import cats.effect.syntax.all.*
import cats.mtl.{Handle, Raise, Stateful}
import cats.syntax.all.*
import com.comcast.ip4s.{IpAddress, SocketAddress}
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.consensus.ChainSelection
import xyz.kd5ujc.scarab.ledger.PeerError
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
  *   - `close` ends the connection and waits for the manager to release it.
  *
  * What the world does without being asked (a session fails, the bearer closes) is reported to the
  * governor as [[Event.Observed]] through `report`, once as the connection starts to go (`released =
  * false`) and once the manager has released it (`released = true`).
  *
  * Each connection is the resource of a fiber of its own and each hot session the resource of a
  * second one, both under the `supervisor`; the table of links is the only shared state.
  */
final class OutboundPeers[F[_]: Async, A] private (
    transport: Transport[F, A],
    manager: ConnectionManager[F],
    config: OutboundPeers.Config,
    hot: OutboundPeers.Hot[F],
    supervisor: Supervisor[F],
    report: Event[A] => F[Unit],
    metrics: Stateful[F, PeerMetrics[A]],
    table: Ref[F, Map[A, OutboundPeers.Link[F]]]
) extends PeerStateActions[F, A]:
  import OutboundPeers.*
  private val F = Async[F]

  // ------------------------------------------------------------------ warm

  def establish(peer: A, mode: DiffusionMode, provenance: Provenance): F[Option[FailureClass]] =
    transport.resolve(peer).flatMap {
      case Left(error) => F.pure(Some(FailurePolicy.classify(error)))
      case Right(remote) =>
        for
          ready <- Deferred[F, Option[FailureClass]]
          _ <- supervisor.supervise(connection(peer, remote, ready))
          result <- ready.get
        yield result
    }

  /** The life of one connection: opened, registered, held until the governor lets go of it or the
    * bearer closes, and released in every case.
    */
  private def connection(
      peer: A,
      remote: SocketAddress[IpAddress],
      ready: Deferred[F, Option[FailureClass]]
  ): F[Unit] =
    (Deferred[F, Unit], Deferred[F, Unit], Ref.of[F, Option[Link[F]]](None)).tupled.flatMap {
      (release, gone, linked) =>
        val carried = Peer.carrying[F]
        given Raise[F, NetworkError] = Peer.carryingNetwork[F]
        given Raise[F, ConnectionError] = new Raise[F, ConnectionError]:
          def functor = F
          def raise[E2 <: ConnectionError, B](e: E2): F[B] =
            carried.raise(PeerError.Unavailable(e.message))

        val open: Resource[F, WarmConnection[F, NtNHeader]] =
          for
            slot <- manager.outbound(remote, alongsideInbound = true)
            bearer <- transport.bearer(peer, remote)
            warm <- NodeToNode.warmOver[F, NtNHeader](
              bearer,
              hot.magic,
              NtNHeader.codec,
              hot.network
            )
            _ <- Resource.eval(
              slot.connected *> slot.negotiated(ConnectionState.DataFlow.Unidirectional)
            )
          yield warm

        open
          .use { warm =>
            for
              phase <- Ref.of[F, Phase[F]](Phase.Warm())
              link = Link(warm, release, gone, phase)
              _ <- linked.set(Some(link))
              _ <- table.update(_ + (peer -> link))
              _ <- ready.complete(None)
              closed <- F.race(release.get, warm.mux.closed)
              _ <- closed.traverse_(reason => lost(peer, link, PeerError.Unavailable(reason)))
            yield ()
          }
          .handleErrorWith { t =>
            val error = t match
              case failure: PeerFailure => failure.error
              case other                => PeerError.Unavailable(InboundGovernor.describe(other))
            // a connection that was never ready failed to open; one that was ready was lost
            ready.complete(Some(FailurePolicy.classify(error))).flatMap { opening =>
              if opening then F.unit
              else linked.get.flatMap(_.traverse_(lost(peer, _, error)))
            }
          }
          .guarantee(table.update(_ - peer) *> gone.complete(()).void)
    }

  /** The connection is going without the governor having asked: say so, once. */
  private def lost(peer: A, link: Link[F], error: PeerError): F[Unit] =
    link.phase.getAndSet(Phase.Ending()).flatMap {
      case Phase.Ending() => F.unit
      case _ =>
        val failure = FailurePolicy.classify(error)
        def observation(released: Boolean) =
          Event.Observed(
            peer,
            Observation.Lost(failure, config.repromoteDelay, released, error.message)
          )
        hot.lost(error) *> report(observation(released = false)) *>
          supervisor
            .supervise(
              link.release.complete(()) *> link.gone.get *> report(observation(released = true))
            )
            .void
    }

  // ------------------------------------------------------------------ hot

  def activate(peer: A): F[Option[FailureClass]] =
    table.get.map(_.get(peer)).flatMap {
      case None => F.pure(Some(FailureClass.Transient))
      case Some(link) =>
        Session.create[F].flatMap { session =>
          link.phase
            .modify {
              case Phase.Warm() => (Phase.Hot(session), true)
              case other        => (other, false)
            }
            .flatMap {
              case false => F.pure(Some(FailureClass.Transient))
              case true =>
                for
                  started <- Deferred[F, Option[FailureClass]]
                  _ <- supervisor.supervise(hotSession(peer, link, session, started))
                  result <- started.get
                  // a hot session that did not start leaves no half-open connection behind
                  _ <- result.traverse_(_ => close(link))
                yield result
            }
        }
    }

  /** What the session of `peer` observes: the node's own observer, and the peer metrics (who is
    * first with a header and a block), one update of the shared board per batch of headers and per
    * range of blocks.
    */
  private def metered(peer: A): Peer.Observation => F[Unit] = {
    case o @ Peer.Observation.HeadersArrived(slots, at) =>
      hot.observe.traverse_(_(o)) *> metrics.modify(_.reportHeaders(peer, slots, at))
    case o @ Peer.Observation.BlocksArrived(blocks, at) =>
      hot.observe.traverse_(_(o)) *> metrics.modify(_.reportFetches(peer, blocks, at))
    case other => hot.observe.traverse_(_(other))
  }

  /** The hot protocols and the session over them, for as long as the session lasts. */
  private def hotSession(
      peer: A,
      link: Link[F],
      session: Session[F],
      started: Deferred[F, Option[FailureClass]]
  ): F[Unit] =
    given Raise[F, NetworkError] = Peer.carryingNetwork[F]
    val deactivated = PeerError.Unavailable(DeactivatedReason)

    def asked: F[Boolean] = session.asked.get

    /** Run the follower session over the peer; stopping it ends it with the sentinel error. */
    def follow(peer: Peer[F]): F[End] =
      val gated = new Peer[F]:
        def intersect(candidates: Vector[xyz.kd5ujc.scarab.protocol.ChainSync.Point]) =
          peer.intersect(candidates)
        def next = F.race(session.stop.get, peer.next).flatMap {
          case Left(_)      => Peer.carrying[F].raise[PeerError, SyncEvent](deactivated)
          case Right(event) => F.pure(event)
        }
        def fetch(header: xyz.kd5ujc.scarab.ledger.Header) = peer.fetch(header)
      Handle
        .allow[PeerError] {
          hot
            .follow(gated, candidate => session.candidate.complete(candidate).void)
            .as(End.Returned)
        }
        .rescue(error => F.pure[End](End.Failed(error)))
        .handleErrorWith {
          case failure: PeerFailure => F.pure[End](End.Failed(failure.error))
          case other =>
            F.pure[End](End.Failed(PeerError.Unavailable(InboundGovernor.describe(other))))
        }

    val run: F[End] =
      Handle
        .allow[PeerError] {
          link.warm.activate(hot.transactions).use { connection =>
            Peer
              .over(
                connection,
                hot.peer,
                hot.prepare,
                hot.headers,
                Some(metered(peer)),
                Some(hot.pool),
                hot.fetchMode
              )
              .use { peer =>
                session.connection.complete(connection) *> started.complete(None) *>
                  follow(peer).flatTap { end =>
                    // asked to stop: bring the pipeline to rest before the wire is ended
                    asked.flatMap(a => (peer.quiesce).whenA(a && end == End.Failed(deactivated)))
                  }
              }
              .flatMap {
                case End.Failed(`deactivated`) =>
                  connection.finish(config.deactivateTimeout).map(End.Stopped(_))
                case other => F.pure(other)
              }
          }
        }
        .rescue(error => F.pure[End](End.Failed(error)))
        .handleErrorWith {
          case failure: PeerFailure => F.pure[End](End.Failed(failure.error))
          case other =>
            F.pure[End](End.Failed(PeerError.Unavailable(InboundGovernor.describe(other))))
        }

    run.flatMap { end =>
      val failure = end match
        case End.Failed(error) => Some(error)
        case End.Returned      => Some(PeerError.Unavailable("the session ended"))
        case End.Stopped(_)    => None
      // a start that failed tells the governor; a session that ends on its own is a loss
      started.complete(failure.map(FailurePolicy.classify)).flatMap { startedHere =>
        session.ended.complete(end) *>
          asked.flatMap(a => failure.traverse_(lost(peer, link, _)).unlessA(a || startedHere))
      }
    }

  def deactivate(peer: A): F[PeerStateActions.Deactivation] =
    table.get.map(_.get(peer)).flatMap {
      case None => F.pure(PeerStateActions.Deactivation.Closed)
      case Some(link) =>
        link.phase.get.flatMap {
          case Phase.Hot(session) =>
            for
              _ <- session.asked.set(true)
              _ <- session.stop.complete(())
              end <- F.timeoutTo(
                session.ended.get.map(Option(_)),
                config.deactivateTimeout,
                F.pure(None)
              )
              result <- end match
                case Some(End.Stopped(true)) =>
                  link.phase.set(Phase.Warm()).as(PeerStateActions.Deactivation.Warm)
                case _ =>
                  close(link).map(released =>
                    if released then PeerStateActions.Deactivation.Closed
                    else PeerStateActions.Deactivation.Lingering
                  )
            yield result
          case _ =>
            close(link).map(released =>
              if released then PeerStateActions.Deactivation.Closed
              else PeerStateActions.Deactivation.Lingering
            )
        }
    }

  // ------------------------------------------------------------------ cold

  def close(peer: A): F[Boolean] =
    table.get.map(_.get(peer)).flatMap(_.fold(F.pure(true))(close))

  /** End the connection and wait for it to be released; false when it is not within the demotion
    * timeout.
    */
  private def close(link: Link[F]): F[Boolean] =
    link.phase.set(Phase.Ending()) *> link.release.complete(()) *>
      F.timeoutTo(link.gone.get.as(true), config.demotionTimeout, F.pure(false))

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
  private val DeactivatedReason = "deactivated by the governor"

  final case class Config(
      /** How long the hot protocols get to end on the wire (`deactivateTimeout`, `Policies.hs` 31).
        */
      deactivateTimeout: FiniteDuration = 300.seconds,
      /** How long a closed connection may stay in the connection manager before the governor stops
        * waiting for it (`c_PEER_DEMOTION_TIMEOUT`).
        */
      demotionTimeout: FiniteDuration = 10.minutes,
      /** The exit policy's wait before a peer whose connection failed is tried again. */
      repromoteDelay: FiniteDuration = 10.seconds
  )

  /** Receives the candidate chain of a session once it exists. */
  type Tracker[F[_]] = Ref[F, ChainSelection.Candidate] => F[Unit]

  /** The follower session over a peer, which hands its candidate chain to the tracker. */
  type Follow[F[_]] = (Peer[F], Tracker[F]) => Raise[F, PeerError] ?=> F[Unit]

  /** What the hot part needs, besides the connection: the follower session, the pool, the header
    * check, the transactions, the settings and how a candidate compares with our selection.
    */
  final case class Hot[F[_]](
      magic: Long,
      network: NodeToNode.Settings,
      peer: Peer.Settings,
      follow: Follow[F],
      prepare: Peer.Prepare[F],
      headers: Option[HeaderCheck[F]],
      transactions: Option[TxSubmissionClient.Source[F]],
      observe: Option[Peer.Observation => F[Unit]],
      pool: BlockPool[F],
      versus: ChainSelection.Candidate => F[Gsm.Candidate],
      /** A connection was lost (once per loss): the log's `reconnecting`. */
      lost: PeerError => F[Unit],
      /** The fetch mode, which BlockFetch reads to decide whether a block another session has
        * claimed may be requested too.
        */
      fetchMode: Option[F[FetchMode]] = None
  )

  /** A connection of ours, and what its owner can do to it. */
  private[node] final class Link[F[_]](
      val warm: WarmConnection[F, NtNHeader],
      /** Completed to let go of the connection. */
      val release: Deferred[F, Unit],
      /** Completed once the connection has been released everywhere. */
      val gone: Deferred[F, Unit],
      val phase: Ref[F, Phase[F]]
  )

  private[node] enum Phase[F[_]]:
    case Warm()
    case Hot(session: Session[F])

    /** Closed by the governor or reported lost: nothing more is reported about it. */
    case Ending()

  /** One hot period of a connection. */
  private[node] final class Session[F[_]](
      /** Completed to ask the session to stop between two batches. */
      val stop: Deferred[F, Unit],
      val asked: Ref[F, Boolean],
      val ended: Deferred[F, End],
      val connection: Deferred[F, NodeToNodeConnection[F, NtNHeader]],
      val candidate: Deferred[F, Ref[F, ChainSelection.Candidate]]
  )

  private[node] object Session:
    def create[F[_]: Async]: F[Session[F]] =
      (
        Deferred[F, Unit],
        Ref.of[F, Boolean](false),
        Deferred[F, End],
        Deferred[F, NodeToNodeConnection[F, NtNHeader]],
        Deferred[F, Ref[F, ChainSelection.Candidate]]
      ).mapN(new Session[F](_, _, _, _, _))

  /** How a hot period ended. */
  private[node] enum End:
    /** The session returned (the follower never does). */
    case Returned

    /** The session failed. */
    case Failed(error: PeerError)

    /** The governor stopped it; `clean` when the protocols ended on the wire and the connection can
      * be activated again.
      */
    case Stopped(clean: Boolean)

  def create[F[_]: Async, A](
      transport: Transport[F, A],
      manager: ConnectionManager[F],
      config: Config,
      hot: Hot[F],
      supervisor: Supervisor[F],
      report: Event[A] => F[Unit],
      metrics: Stateful[F, PeerMetrics[A]]
  ): F[OutboundPeers[F, A]] =
    Ref
      .of[F, Map[A, Link[F]]](Map.empty)
      .map(new OutboundPeers[F, A](transport, manager, config, hot, supervisor, report, metrics, _))
