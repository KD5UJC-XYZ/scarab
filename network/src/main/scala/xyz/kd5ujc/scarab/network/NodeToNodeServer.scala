// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.data.NonEmptySet
import cats.effect.{Async, Ref, Resource}
import cats.effect.std.Mutex
import fs2.concurrent.SignallingRef
import cats.effect.syntax.all.*
import cats.mtl.{Handle, Raise}
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import xyz.kd5ujc.scarab.protocol.{ChainSync, Handshake, NtnVersion}
import scala.concurrent.duration.*

/** What a node serves to its peers: its chain (ChainSync headers and BlockFetch blocks) and an
  * inbox for transactions. `chainSync` opens one cursor per connection.
  *
  * @param peerSharing
  *   The peers we tell others about, when the connection agrees to peer sharing; none answers every
  *   request with an empty list.
  */
final class ServerHandlers[F[_], P](
    val codec: ChainSync.PayloadCodec[P],
    val chainSync: Resource[F, ChainSyncServer.Source[F, P]],
    val blocks: BlockFetchServer.Blocks[F],
    val transactions: TxSubmissionServer.Sink[F],
    val peerSharing: Option[PeerSharingServer.Source[F]] = None
)

/** Node-to-node, responder side. [[start]] runs the four server mini-protocols on a multiplexer and
  * [[accept]] drives a whole inbound connection: handshake, then the servers, until the peer or a
  * protocol error closes it.
  */
object NodeToNodeServer:
  /** @param duplex
    *   Offer InitiatorAndResponder; the connection is duplex only if the peer agrees.
    * @param offerPeerSharing
    *   Take part in peer sharing: offered in the handshake; the connection does if the peer does
    *   too.
    * @param protocolIdleTimeout
    *   How long an accepted connection may stay without a byte on any remote-initiated
    *   mini-protocol after the handshake: `defaultProtocolIdleTimeout = 5` in ouroboros-network
    *   2fb69829 `Ouroboros/Network/ConnectionManager/Core.hs`, armed as `registerDelay idleTimeout`
    *   by the inbound governor when it starts an accepted connection (`InboundGovernor.hs`).
    * @param maxVersion
    *   The highest node-to-node version accepted: the latest release unless the configuration
    *   enables experimental protocols.
    */
  final case class Settings(
      handshakeTimeout: FiniteDuration = Handshake.DefaultPhaseTimeout,
      protocolIdleTimeout: FiniteDuration = 5.seconds,
      mux: Mux.Config = Mux.Config(role = Mux.Role.Responder),
      duplex: Boolean = true,
      chainSync: ChainSyncServer.Settings = ChainSyncServer.Settings(),
      blockFetch: BlockFetchServer.Settings = BlockFetchServer.Settings(),
      keepAlive: KeepAliveServer.Settings = KeepAliveServer.Settings(),
      transactions: TxSubmissionServer.Settings = TxSubmissionServer.Settings(),
      offerPeerSharing: Boolean = false,
      maxVersion: NtnVersion = NtnVersion.LatestReleased,
      peerSharing: PeerSharingServer.Settings = PeerSharingServer.Settings()
  )

  val Protocol: Set[Int] = Set(
    Protocols.Handshake,
    Protocols.ChainSync,
    Protocols.BlockFetch,
    Protocols.TxSubmission2,
    Protocols.KeepAlive,
    Protocols.PeerSharing
  )

  /** The mini-protocols after the handshake: PeerSharing only when the connection agreed to it. */
  private def application(peerSharing: Boolean): NonEmptySet[Int] =
    val always =
      NonEmptySet.of(
        Protocols.ChainSync,
        Protocols.BlockFetch,
        Protocols.TxSubmission2,
        Protocols.KeepAlive
      )
    if peerSharing then always.add(Protocols.PeerSharing) else always

  private[network] def applicationProtocols(peerSharing: Boolean): Set[Int] =
    application(peerSharing).toSortedSet

  /** Run the servers for the life of the resource. A failed server aborts the multiplexer, which
    * ends the connection; a client that finishes its protocol (`Done`) just ends that conversation,
    * and the server waits for the next one on the same channel: a peer that demotes us from hot to
    * warm and promotes us again starts each hot protocol afresh on the one bearer (the reference
    * restarts its responders on demand the same way). With `peerSharing` (the connection agreed to
    * it) the PeerSharing server runs too, on the established temperature like KeepAlive.
    */
  def start[F[_]: Async, P](
      mux: Mux[F],
      handlers: ServerHandlers[F, P],
      settings: Settings = Settings(),
      peerSharing: Boolean = false,
      initiators: Boolean = false,
      responders: Option[Responders[F]] = None
  )(using Raise[F, NetworkError]): Resource[F, Unit] =
    // each server fiber runs in an error scope of its own and ends the connection with the reason
    def guarded(name: String)(program: Handle[F, NetworkError] ?=> F[Unit]): F[Unit] =
      Fibers
        .scoped[F, NetworkError, Unit](t => NetworkError.fromThrowable(name, t))(program)
        .flatMap(_.fold(mux.abort, _ => Async[F].unit))
    def channel(protocol: Int) = Resource.eval(mux.channel(protocol, Mux.Role.Responder))
    val installed = applicationProtocols(peerSharing)
    // one run of a responder, started on demand by `trigger`: counted while it runs, when the
    // connection counts its responders (inbound connections, for the idle timer)
    def episode(trigger: F[Unit])(run: F[Unit]): F[Unit] =
      responders.fold(run)(counted => trigger *> counted.running(run))
    def onDemand(protocol: Int): F[Unit] = mux.awaitResponderInput(protocol)
    // `StartOnDemandAny`: the first byte of any responder starts it
    val onDemandAny: F[Unit] =
      application(peerSharing).reduceLeftTo(onDemand)((a, b) => Async[F].race(a, onDemand(b)).void)
    for
      _ <- Resource.eval(
        mux.installApplication(if initiators then installed else Set.empty, installed)
      )
      chainSync <- channel(Protocols.ChainSync)
      blockFetch <- channel(Protocols.BlockFetch)
      txs <- channel(Protocols.TxSubmission2)
      keepAlive <- channel(Protocols.KeepAlive)
      _ <- guarded("chain-sync")(
        episode(onDemand(Protocols.ChainSync))(
          handlers.chainSync
            .use(ChainSyncServer.run(chainSync, handlers.codec, _, settings.chainSync))
            .void
        ).foreverM[Unit]
      ).background
      _ <- guarded("block-fetch")(
        episode(onDemand(Protocols.BlockFetch))(
          BlockFetchServer.run(blockFetch, handlers.blocks, settings.blockFetch).void
        ).foreverM[Unit]
      ).background
      _ <- guarded("tx-submission")(
        episode(onDemand(Protocols.TxSubmission2))(
          TxSubmissionServer.run(txs, handlers.transactions, settings.transactions).void
        ).foreverM[Unit]
      ).background
      // cardano-diffusion NodeToNode.hs:310: KeepAlive uses StartOnDemandAny.
      // restarted on demand when the client ends it, as the reference's inbound governor restarts
      // its responders, when the connection counts them
      _ <- guarded("keep-alive")(
        responders.fold(
          mux.awaitResponderActivity *> KeepAliveServer.run(keepAlive, settings.keepAlive).void
        )(_ =>
          episode(onDemandAny)(KeepAliveServer.run(keepAlive, settings.keepAlive).void)
            .foreverM[Unit]
        )
      ).background
      _ <- Option
        .when(peerSharing)(handlers.peerSharing.getOrElse(PeerSharingServer.empty[F]))
        .traverse_ { source =>
          channel(Protocols.PeerSharing).flatMap { shared =>
            guarded("peer-sharing")(
              episode(onDemand(Protocols.PeerSharing))(
                PeerSharingServer.run(shared, source, settings.peerSharing).void
              ).foreverM[Unit]
            ).background
          }
        }
    yield ()

  /** What the responders of a connection did, in order: the first one started, or the last running
    * one ended (the reference's mux tracer, `InboundGovernor.hs` 629-708, writes `AwakeRemote` and
    * `WaitIdleRemote` to the inbound governor's channel on these).
    */
  private[network] enum Activity:
    case Started, Finished

  /** The responders of one connection that are running: each is started on demand by its
    * mini-protocol's first byte and ends with that protocol, as the reference's inbound governor
    * starts and restarts them (`InboundGovernor.hs`, ouroboros-network `2fb69829`). Their first
    * start and last end are kept in order for the tracker of the peer's activity, which waits for
    * them without taking them, so none is lost whatever wins its wait (the reference's inbound
    * governor reads its channel and its timers in one STM transaction, `InboundGovernor.hs`
    * 240-275). A responder that would start once the idle timer has expired waits until the expiry
    * is settled: the reference's tracer retries on an expired `RemoteIdle` (`InboundGovernor.hs`
    * 665-672).
    */
  private[network] final class Responders[F[_]: Async] private (
      count: Ref[F, Int],
      pending: SignallingRef[F, Vector[Activity]],
      lock: Mutex[F],
      deadline: SignallingRef[F, Option[FiniteDuration]]
  ):
    private val F = Async[F]

    private def counted(by: Int): F[Unit] =
      count.modify(c => (c + by, (c, c + by))).flatMap {
        case (0, 1) => pending.update(_ :+ Activity.Started)
        case (1, 0) => pending.update(_ :+ Activity.Finished)
        case _      => F.unit
      }

    /** Count a starting responder, once no expired idle timer holds it. */
    private def enter: F[Unit] = F.tailRecM(()) { _ =>
      lock.lock
        .surround((deadline.get, F.monotonic).tupled.flatMap { (until, now) =>
          val held = until.exists(_ <= now)
          counted(1).unlessA(held).as(Option.when(held)(until))
        })
        .flatMap {
          case Some(seen) => deadline.waitUntil(_ != seen).as(Left(()))
          case None       => F.pure(Right(()))
        }
    }

    /** `run`, counted while it runs. */
    def running[A](run: F[A]): F[A] =
      F.bracketFull(poll => poll(enter))(_ => run)((_, _) => lock.lock.surround(counted(-1)))

    /** Completes once an activity is waiting; it takes none. */
    def awaitActivity: F[Unit] = pending.waitUntil(_.nonEmpty)

    /** The oldest waiting activity, once there is one. */
    def next: F[Activity] = F.tailRecM(()) { _ =>
      awaitActivity *> lock.lock.surround(pending.modify { waiting =>
        waiting.headOption.fold((waiting, Left(()): Either[Unit, Activity]))(first =>
          (waiting.drop(1), Right(first))
        )
      })
    }

    /** The instant the idle timer expires, while it runs; none otherwise. */
    def arm(until: Option[FiniteDuration]): F[Unit] = deadline.set(until)

  private[network] object Responders:
    def create[F[_]: Async]: F[Responders[F]] =
      (
        Ref.of[F, Int](0),
        SignallingRef.of[F, Vector[Activity]](Vector.empty),
        Mutex[F],
        SignallingRef.of[F, Option[FiniteDuration]](None)
      )
        .mapN(new Responders(_, _, _, _))

  /** What the inbound governor knows of the peer's side of a connection (`RemoteState`,
    * `InboundGovernor/State.hs:218-256`): idle since `since` with its timer running, using the
    * connection, or idle and kept because our outbound side uses it.
    */
  private enum RemoteState:
    case Idle(since: FiniteDuration)
    case Warm
    case Cold

  /** The inbound governor's handling of one duplex or accepted connection (`InboundGovernor.hs`
    * 329-335, 463-481, 499-570, and its mux tracer 629-708, ouroboros-network `2fb69829`): the
    * first responder that starts is `AwakeRemote` (`promotedToWarmRemote`), the last one that ends
    * `WaitIdleRemote` (`demotedToColdRemote`, which arms the idle timer again), and a timer that
    * expires is `CommitRemote` (`releaseInbound`): the manager closes the connection, or keeps it
    * because our outbound side uses it, and the peer's next responder wakes it up again. It runs on
    * every connection whose responders the peer can start, the ones we dial included. An expired
    * timer is committed before the activities that are waiting, which are handled after, as the
    * reference's step puts `CommitRemote` before the channel's messages (`InboundGovernor.hs`
    * 253-261).
    *
    * @param timer
    *   How the idle timer waits: `Async.sleep`; a test controls what happens as it fires.
    */
  private[network] def remoteActivity[F[_]: Async](
      mux: Mux[F],
      responders: Responders[F],
      idleTimeout: FiniteDuration,
      connection: Connection[F],
      timer: Option[FiniteDuration => F[Unit]] = None
  ): Resource[F, Unit] =
    val F = Async[F]
    def awake: F[Either[RemoteState, Unit]] =
      responders.arm(None) *> connection.promotedToWarmRemote.as(Left(RemoteState.Warm))
    def after(activity: Activity, idle: RemoteState): F[Either[RemoteState, Unit]] =
      activity match
        case Activity.Started  => awake
        case Activity.Finished => F.pure(Left(idle))
    // `CommitRemote`; the responders that start meanwhile wait until it is settled
    def commit(since: FiniteDuration): F[Either[RemoteState, Unit]] =
      connection.releaseInbound.flatMap {
        case OperationResult.Success(RemoteRelease.Commit) => F.pure(Right(()))
        case OperationResult.Success(RemoteRelease.Keep) =>
          responders.arm(None).as(Left(RemoteState.Cold))
        // the reference leaves the expired timer as it is, and commits again
        case OperationResult.Unsupported(Some(state)) if state.isLive =>
          F.pure(Left(RemoteState.Idle(since)))
        case OperationResult.Unsupported(_) => F.pure(Right(()))
      }
    val watch: F[Unit] = F.monotonic.flatMap { start =>
      F.tailRecM[RemoteState, Unit](RemoteState.Idle(start)) {
        case idle @ RemoteState.Idle(since) =>
          val until = since + idleTimeout
          responders.arm(Some(until)) *> F.monotonic.flatMap { now =>
            // what wins the wait decides nothing: the clock is read again after it
            val left = (until - now).max(Duration.Zero)
            F.race(responders.awaitActivity, timer.fold(F.sleep(left))(_(left))) *>
              F.monotonic.flatMap(later =>
                if later >= until then commit(since)
                else responders.next.flatMap(after(_, idle))
              )
          }
        case RemoteState.Warm =>
          responders.next.flatMap {
            case Activity.Finished =>
              connection.demotedToColdRemote.flatMap {
                case OperationResult.Success(_) =>
                  F.monotonic.map(now => Left(RemoteState.Idle(now)))
                case OperationResult.Unsupported(_) => F.pure(Right(()))
              }
            case Activity.Started => F.pure(Left(RemoteState.Warm))
          }
        case RemoteState.Cold => responders.next.flatMap(after(_, RemoteState.Cold))
      }
    }
    Fibers
      .scoped[F, NetworkError, Unit](t => NetworkError.fromThrowable("protocol-idle", t))(watch)
      .flatMap(_.fold(mux.abort, F.pure))
      .background
      .void

  /** An inbound connection: negotiate, publish it to the manager, serve, and return the reason it
    * ended: the peer or a failure closed it, or the manager ended it ([[Connection.stopped]]).
    * `negotiated` runs once the handshake succeeded and may use the multiplexer.
    */
  def accept[F[_]: Async, P](
      bearer: Bearer[F],
      magic: Long,
      handlers: ServerHandlers[F, P],
      connection: Connection[F],
      settings: Settings = Settings(),
      negotiated: (HandshakeClient.Agreed, Mux[F]) => Resource[F, Unit] = (_, _: Mux[F]) =>
        Resource.unit[F]
  )(using Raise[F, NetworkError]): F[ConnectionEnd] =
    val config = settings.mux.copy(role = Mux.Role.Responder, duplex = settings.duplex)
    val served = Mux.handshakeResource(bearer, Protocol, config).use { mux =>
      for
        agreed <- mux
          .channel(Protocols.Handshake)
          .flatMap(
            HandshakeServer.run(
              _,
              HandshakeServer.offers(
                magic,
                settings.duplex,
                settings.offerPeerSharing,
                settings.maxVersion
              ),
              ProtocolDeadline.After(settings.handshakeTimeout)
            )(using summon, ConnectionManager.recordingRefusal(connection))
          )
        responders <- Responders.create[F]
        reason <- (
          start(
            mux,
            handlers,
            settings,
            agreed.data.peerSharing,
            initiators = !agreed.data.initiatorOnly,
            responders = Some(responders)
          ) *> published(connection, Negotiated(mux, agreed)) *>
            negotiated(agreed, mux) *>
            remoteActivity(mux, responders, settings.protocolIdleTimeout, connection)
        ).surround(Async[F].race(mux.closed, connection.stopped))
        result <- reason match
          case Left(defect: NetworkError.Defect) => defect.raise[F, ConnectionEnd]
          case Left(other)                       => Async[F].pure(ConnectionEnd.fromNetwork(other))
          case Right(end)                        => Async[F].pure(end)
      yield result
    }
    // a connection the manager ends while it negotiates (a prune) ends there
    Async[F].race(connection.stopped, served).map(_.merge)

  /** Publish the negotiated `handle` with its state; a connection the manager refuses now is
    * stopped (the reference's handler fails, and its mux with it).
    */
  private[network] def published[F[_]: Async](
      connection: Connection[F],
      handle: Negotiated[F]
  ): Resource[F, Unit] =
    Resource.eval(
      Handle
        .allow[ConnectionError](connection.negotiated(handle))
        .rescue(error =>
          handle.mux.abort(NetworkError.BearerClosed(NetworkError.Closure.AbortedBy(error)))
        )
    )
