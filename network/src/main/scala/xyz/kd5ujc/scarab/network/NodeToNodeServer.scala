// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{Async, Resource}
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

  private[network] def applicationProtocols(peerSharing: Boolean): Set[Int] =
    Protocol - Protocols.Handshake -- (if peerSharing then Set.empty
                                       else Set(Protocols.PeerSharing))

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
    // `StartOnDemandAny`: the first byte of any responder starts it (with none installed, nothing does)
    val onDemandAny: F[Unit] =
      installed.toList
        .map(onDemand)
        .reduceLeftOption((a, b) => Async[F].race(a, b).void)
        .getOrElse(Async[F].never)
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

  /** The responders of one inbound connection that are running: each is started on demand by its
    * mini-protocol's first byte and ends with that protocol, as the reference's inbound governor
    * starts and restarts them (`InboundGovernor.hs`, ouroboros-network `2fb69829`).
    */
  private[network] final class Responders[F[_]: Async] private (count: SignallingRef[F, Int]):
    /** `run`, counted while it runs. */
    def running[A](run: F[A]): F[A] =
      Async[F].bracket(count.update(_ + 1))(_ => run)(_ => count.update(_ - 1))
    def awaitBusy: F[Unit] = count.waitUntil(_ > 0)
    def awaitIdle: F[Unit] = count.waitUntil(_ == 0)

  private[network] object Responders:
    def create[F[_]: Async]: F[Responders[F]] = SignallingRef.of[F, Int](0).map(new Responders(_))

  /** The idle timer of the reference's inbound governor: end the connection with
    * [[NetworkError.ProtocolIdleTimeout]] when no responder runs for `after`, first after the
    * handshake and, when `rearm`, again each time the last running responder ends
    * (`WaitIdleRemote`, `InboundGovernor.hs` and `ConnectionManager/Core.hs`, ouroboros-network
    * `2fb69829`). A connection that our own clients use is not released when its remote side goes
    * idle (the reference keeps a duplex connection in outbound use), so it is armed once only.
    */
  private[network] def idleWatch[F[_]: Async](
      mux: Mux[F],
      responders: Responders[F],
      after: FiniteDuration,
      rearm: Boolean
  ): Resource[F, Unit] =
    val F = Async[F]
    // whether the window passed with no responder started
    val window: F[Boolean] = F.race(responders.awaitBusy, F.sleep(after)).map(_.isRight)
    val watch: F[Unit] = F.tailRecM(()) { _ =>
      window.flatMap {
        case true           => mux.abort(NetworkError.ProtocolIdleTimeout(after)).as(Right(()))
        case false if rearm => responders.awaitIdle.as(Left(()))
        case false          => F.pure(Right(()))
      }
    }
    Fibers
      .scoped[F, NetworkError, Unit](t => NetworkError.fromThrowable("protocol-idle", t))(watch)
      .flatMap(_.fold(mux.abort, F.pure))
      .background
      .void

  /** An inbound connection: negotiate, serve, and return the reason it ended. `negotiated` runs
    * once the handshake succeeded and may use the multiplexer (a duplex connection also carries our
    * clients, see [[Mux.Config.duplex]]).
    *
    * @param ownClients
    *   whether our own clients run on the connection once negotiated; the protocol-idle timer is
    *   then armed once only ([[idleWatch]])
    */
  def accept[F[_]: Async, P](
      bearer: Bearer[F],
      magic: Long,
      handlers: ServerHandlers[F, P],
      settings: Settings = Settings(),
      negotiated: (HandshakeClient.Agreed, Mux[F]) => Resource[F, Unit] = (_, _: Mux[F]) =>
        Resource.unit[F],
      ownClients: HandshakeClient.Agreed => Boolean = _ => false
  )(using Raise[F, NetworkError]): F[ConnectionEnd] =
    val config = settings.mux.copy(role = Mux.Role.Responder, duplex = settings.duplex)
    Mux.handshakeResource(bearer, Protocol, config).use { mux =>
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
            )
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
          ) *> idleWatch(
            mux,
            responders,
            settings.protocolIdleTimeout,
            rearm = !ownClients(agreed)
          ) *> negotiated(agreed, mux)
        ).surround(mux.closed)
        result <- reason match
          case defect: NetworkError.Defect => defect.raise[F, ConnectionEnd]
          case other                       => Async[F].pure(ConnectionEnd.fromNetwork(other))
      yield result
    }
