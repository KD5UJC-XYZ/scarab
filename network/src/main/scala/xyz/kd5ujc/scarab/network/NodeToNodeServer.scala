// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.data.NonEmptySet
import cats.effect.{Async, Outcome, Ref, Resource}
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
    *   c45735a5 `Ouroboros/Network/ConnectionManager/Core.hs`, armed as `registerDelay idleTimeout`
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

  /** The temperature of a responder's mini-protocol in the reference's bundle (`TemperatureBundle`,
    * cardano-diffusion `NodeToNode.hs` 252-325, ouroboros-network `c5117705`): ChainSync,
    * BlockFetch and TxSubmission2 are hot; KeepAlive and PeerSharing established; none is warm.
    */
  private type Temperature = ProtocolResults.Temperature
  private val Temperature = ProtocolResults.Temperature

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
    // one run of a responder, started on demand by `trigger`: counted by its temperature while it
    // runs, when the connection counts its responders (for the peer's activity)
    def episode(temperature: Temperature, trigger: F[Unit])(run: F[Unit]): F[Unit] =
      responders.fold(run)(counted => trigger *> counted.running(temperature)(run))
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
        episode(Temperature.Hot, onDemand(Protocols.ChainSync))(
          handlers.chainSync
            .use(ChainSyncServer.run(chainSync, handlers.codec, _, settings.chainSync))
            .void
        ).foreverM[Unit]
      ).background
      _ <- guarded("block-fetch")(
        episode(Temperature.Hot, onDemand(Protocols.BlockFetch))(
          BlockFetchServer.run(blockFetch, handlers.blocks, settings.blockFetch).void
        ).foreverM[Unit]
      ).background
      _ <- guarded("tx-submission")(
        episode(Temperature.Hot, onDemand(Protocols.TxSubmission2))(
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
          episode(Temperature.Established, onDemandAny)(
            KeepAliveServer.run(keepAlive, settings.keepAlive).void
          )
            .foreverM[Unit]
        )
      ).background
      _ <- Option
        .when(peerSharing)(handlers.peerSharing.getOrElse(PeerSharingServer.empty[F]))
        .traverse_ { source =>
          channel(Protocols.PeerSharing).flatMap { shared =>
            guarded("peer-sharing")(
              episode(Temperature.Established, onDemand(Protocols.PeerSharing))(
                PeerSharingServer.run(shared, source, settings.peerSharing).void
              ).foreverM[Unit]
            ).background
          }
        }
    yield ()

  /** What the responders of a connection did, in order, as the reference's mux tracer writes it to
    * the inbound governor's channel (`inboundGovernorMuxTracer`, `InboundGovernor.hs` 602-682).
    */
  private[network] enum Activity:
    /** `AwakeRemote`: a responder started while none ran. */
    case Awake

    /** `RemotePromotedToHot`: a hot responder started while no hot one ran. */
    case PromotedToHot

    /** `RemoteDemotedToWarm`: the last running hot responder ended cleanly. */
    case DemotedToWarm

    /** `WaitIdleRemote`: the last running responder ended cleanly. */
    case WaitIdle

  /** The running responders of a connection by temperature (`ResponderCounters`,
    * `InboundGovernor/State.hs` 190-197).
    */
  private final case class Running(hot: Int, other: Int):
    /** A responder of `temperature` starts: what it signals, and the counts after it. */
    def started(temperature: Temperature): (Vector[Activity], Running) =
      val hotOne = temperature == Temperature.Hot
      val signals =
        Vector(Activity.Awake).filter(_ => hot == 0 && other == 0) ++
          Vector(Activity.PromotedToHot).filter(_ => hotOne && hot == 0)
      (signals, if hotOne then copy(hot = hot + 1) else copy(other = other + 1))

    /** A responder of `temperature` ended cleanly: what it signals, and the counts after it. */
    def ended(temperature: Temperature): (Vector[Activity], Running) =
      val hotOne = temperature == Temperature.Hot
      val signals =
        Vector(Activity.DemotedToWarm).filter(_ => hotOne && hot == 1) ++
          Vector(Activity.WaitIdle).filter(_ => hot + other == 1)
      (signals, if hotOne then copy(hot = hot - 1) else copy(other = other - 1))

  /** The responders of one connection that are running: each is started on demand by its
    * mini-protocol's first byte and ends with that protocol, as the reference's inbound governor
    * starts and restarts them (`InboundGovernor.hs`, ouroboros-network `c45735a5`). What their
    * starts and clean ends signal ([[Activity]]) is kept in order for the tracker of the peer's
    * activity, which waits for it without taking it, so none is lost whatever wins its wait (the
    * reference's inbound governor reads its channel and its timers in one STM transaction,
    * `InboundGovernor.hs` 239-274). A responder that would start once the idle timer has expired
    * waits until the expiry is settled: the reference's tracer retries on an expired `RemoteIdle`
    * (`InboundGovernor.hs` 638-648). A responder that ends in an error or is cancelled stops the
    * counting for good, as the tracer then empties its counters (`SNothing`, 688-696): the
    * connection's mux is ending.
    */
  private[network] final class Responders[F[_]: Async] private (
      count: Ref[F, Option[Running]],
      pending: SignallingRef[F, Vector[Activity]],
      lock: Mutex[F],
      deadline: SignallingRef[F, Option[FiniteDuration]]
  ):
    private val F = Async[F]

    private def counted(change: Running => (Vector[Activity], Running)): F[Unit] =
      count
        .modify {
          case Some(running) =>
            val (signals, after) = change(running)
            (Some(after), signals)
          case None => (None, Vector.empty)
        }
        .flatMap(signals => pending.update(_ ++ signals).whenA(signals.nonEmpty))

    /** Count a starting responder, once no expired idle timer holds it. */
    private def enter(temperature: Temperature): F[Unit] = F.tailRecM(()) { _ =>
      lock.lock
        .surround((deadline.get, F.monotonic).tupled.flatMap { (until, now) =>
          val held = until.exists(_ <= now)
          counted(_.started(temperature)).unlessA(held).as(Option.when(held)(until))
        })
        .flatMap {
          case Some(seen) => deadline.waitUntil(_ != seen).as(Left(()))
          case None       => F.pure(Right(()))
        }
    }

    /** `run`, a responder of `temperature`, counted while it runs. */
    def running[A](temperature: Temperature)(run: F[A]): F[A] =
      F.bracketFull(poll => poll(enter(temperature)))(_ => run) {
        case (_, Outcome.Succeeded(_)) => lock.lock.surround(counted(_.ended(temperature)))
        case _                         => lock.lock.surround(count.set(None))
      }

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
        Ref.of[F, Option[Running]](Some(Running(0, 0))),
        SignallingRef.of[F, Vector[Activity]](Vector.empty),
        Mutex[F],
        SignallingRef.of[F, Option[FiniteDuration]](None)
      )
        .mapN(new Responders(_, _, _, _))

  /** What the inbound governor knows of the peer's side of a connection (`RemoteState`,
    * `InboundGovernor/State.hs:250-288`): idle since `since` with its timer running, using the
    * connection with or without a hot responder, or idle and kept because our outbound side uses
    * it.
    */
  private enum RemoteState:
    case Idle(since: FiniteDuration)
    case Warm
    case Hot
    case Cold

    /** `mkRemoteSt` (`InboundGovernor/State.hs` 344-348). */
    def st: RemoteSt = this match
      case Idle(_) => RemoteSt.Idle
      case Warm    => RemoteSt.Warm
      case Hot     => RemoteSt.Hot
      case Cold    => RemoteSt.Cold

  /** The inbound governor's handling of one duplex or accepted connection (`InboundGovernor.hs`
    * 354-360, 445-543, and its mux tracer 602-682, ouroboros-network `c45735a5`): the first
    * responder that starts is `AwakeRemote` (`promotedToWarmRemote`, `RemoteWarm`), the first hot
    * one `RemotePromotedToHot` (`RemoteHot`), the end of the last hot one `RemoteDemotedToWarm`
    * (`RemoteWarm`), the end of the last one `WaitIdleRemote` (`demotedToColdRemote`, which arms
    * the idle timer again: `RemoteIdle`), and a timer that expires is `CommitRemote`
    * (`releaseInbound`): the manager closes the connection, which the inbound governor tracks no
    * more, or keeps it because our outbound side uses it (`RemoteCold`), and the peer's next
    * responder wakes it up again. Each remote state is recorded on the connection
    * ([[Connection.updateRemoteState]]) from `RemoteIdle` on, until a commit or the end of the
    * resource. It runs on every connection whose responders the peer can start, the ones we dial
    * included. An expired timer is committed before the activities that are waiting, which are
    * handled after, as the reference's step puts `CommitRemote` before the channel's messages
    * (`InboundGovernor.hs` 258-266).
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
    def recorded(state: RemoteState): F[Either[RemoteState, Unit]] =
      connection.updateRemoteState(state.st).as(Left(state))
    // the inbound governor's step for one message of the channel (`InboundGovernor.hs` 445-505)
    def after(activity: Activity): F[Either[RemoteState, Unit]] =
      activity match
        // the manager's answer does not change the remote state
        case Activity.Awake =>
          responders.arm(None) *> connection.promotedToWarmRemote *> recorded(RemoteState.Warm)
        case Activity.PromotedToHot => recorded(RemoteState.Hot)
        case Activity.DemotedToWarm => recorded(RemoteState.Warm)
        case Activity.WaitIdle =>
          connection.demotedToColdRemote.flatMap {
            case OperationResult.Success(_) =>
              F.monotonic.flatMap(now => recorded(RemoteState.Idle(now)))
            case OperationResult.Unsupported(_) => F.pure(Right(()))
          }
    // `CommitRemote`; the responders that start meanwhile wait until it is settled
    def commit(since: FiniteDuration): F[Either[RemoteState, Unit]] =
      connection.releaseInbound.flatMap {
        case OperationResult.Success(RemoteRelease.Commit) =>
          connection.unregisterRemote.as(Right(()))
        case OperationResult.Success(RemoteRelease.Keep) =>
          responders.arm(None) *> recorded(RemoteState.Cold)
        // the reference leaves the expired timer as it is, and commits again
        case OperationResult.Unsupported(Some(state)) if state.isLive =>
          F.pure(Left(RemoteState.Idle(since)))
        case OperationResult.Unsupported(_) => F.pure(Right(()))
      }
    val watch: F[Unit] = F.monotonic.flatMap { start =>
      F.tailRecM[RemoteState, Unit](RemoteState.Idle(start)) {
        case RemoteState.Idle(since) =>
          val until = since + idleTimeout
          responders.arm(Some(until)) *> F.monotonic.flatMap { now =>
            // what wins the wait decides nothing: the clock is read again after it
            val left = (until - now).max(Duration.Zero)
            F.race(responders.awaitActivity, timer.fold(F.sleep(left))(_(left))) *>
              F.monotonic.flatMap(later =>
                if later >= until then commit(since) else responders.next.flatMap(after)
              )
          }
        case RemoteState.Warm | RemoteState.Hot | RemoteState.Cold =>
          responders.next.flatMap(after)
      }
    }
    // `NewConnection` registers the connection as `RemoteIdle`; `MuxFinished` unregisters it
    Resource.make(connection.updateRemoteState(RemoteSt.Idle))(_ => connection.unregisterRemote) *>
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
