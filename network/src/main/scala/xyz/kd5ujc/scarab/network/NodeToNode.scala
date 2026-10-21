// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.Monad
import cats.effect.{Async, Ref, Resource}
import cats.mtl.Raise
import cats.syntax.all.*
import com.comcast.ip4s.{Host, SocketAddress}
import xyz.kd5ujc.scarab.protocol.{ChainSync, Handshake, NtnVersion}
import xyz.kd5ujc.scarab.network.selection.FetchGsv
import scala.concurrent.duration.*

/** The hot part of a node-to-node connection: ChainSync, BlockFetch and (with a source of
  * transactions) TxSubmission2, started by [[WarmConnection.activate]]. All clients share the one
  * multiplexed bearer of the connection.
  *
  * @param transactions
  *   The TxSubmission2 client, when this activation runs one and can end it.
  */
final class NodeToNodeConnection[F[_], P](
    val version: Int,
    val chainSync: ChainSyncClient[F, P],
    val blockFetch: BlockFetchClient[F],
    val mux: Mux[F],
    val transactions: Option[TxSubmissionClient.Running[F]] = None,
    val fetchPerformance: Option[F[FetchGsv.PeerGsv]] = None
):
  /** End the hot protocols on the wire, so the connection can carry a new activation: ChainSync and
    * BlockFetch say `MsgDone` where they are idle, TxSubmission2 at the peer's next blocking
    * request, waiting up to `within` for it. `false` when a protocol could not be ended (a request
    * is outstanding, or the peer never asked): the connection must be closed, not reused.
    */
  def finish(within: FiniteDuration)(using Monad[F], Raise[F, NetworkError]): F[Boolean] =
    (chainSync.idle, blockFetch.idle).tupled.flatMap {
      case (true, true) =>
        chainSync.done *> blockFetch.done *> transactions.fold(true.pure[F])(_.finish(within))
      case _ => false.pure[F]
    }

/** A connection with its handshake done and KeepAlive running, the temperature the reference calls
  * warm (`Ouroboros.Network.PeerSelection.PeerStateActions`: established protocols only). The hot
  * protocols start with [[activate]] and end when its resource is released; a connection can be
  * activated again after [[NodeToNodeConnection.finish]] ended them cleanly.
  *
  * @param peerSharing
  *   The PeerSharing client, when both sides agreed to peer sharing (the established temperature,
  *   like KeepAlive); the governor asks through it.
  * @param duplex
  *   The handshake agreed InitiatorAndResponder for this bearer.
  */
final class WarmConnection[F[_], P] private[network] (
    val version: Int,
    val mux: Mux[F],
    role: Mux.Role,
    codec: ChainSync.PayloadCodec[P],
    settings: NodeToNode.Settings,
    val peerSharing: Option[PeerSharingClient[F]] = None,
    val duplex: Boolean = false,
    val fetchPerformance: Option[F[FetchGsv.PeerGsv]] = None
):
  /** Start the hot protocols as the initiator side of each. With `transactions` the node offers
    * those transactions over TxSubmission2 for the life of the resource.
    */
  def activate(transactions: Option[TxSubmissionClient.Source[F]] = None)(using
      Async[F],
      Raise[F, NetworkError]
  ): Resource[F, NodeToNodeConnection[F, P]] =
    for
      running <- transactions.traverse(source =>
        TxSubmissionClient.backgroundFinishing(mux, source, role = Some(role))
      )
      chainSyncChannel <- Resource.eval(mux.channel(Protocols.ChainSync, role))
      blockFetchChannel <- Resource.eval(mux.channel(Protocols.BlockFetch, role))
      chainSync <- Resource.eval(ChainSyncClient(chainSyncChannel, codec, settings.chainSync))
      blockFetch <- BlockFetchClient.resource(blockFetchChannel, mux.abort, settings.blockFetch)
    yield NodeToNodeConnection(version, chainSync, blockFetch, mux, running, fetchPerformance)

object NodeToNode:
  /** @param offerPeerSharing
    *   Take part in peer sharing: offered in the handshake; the connection does if the peer does
    *   too.
    * @param maxVersion
    *   The highest version offered: the latest release (15, as cardano-node 11.1.3) unless the
    *   configuration enables experimental protocols (then 16, which adds the Peras flag).
    */
  final case class Settings(
      connectTimeout: FiniteDuration = 10.seconds,
      handshakeTimeout: FiniteDuration = Handshake.DefaultPhaseTimeout,
      mux: Mux.Config = Mux.Config(),
      chainSync: ChainSyncClient.Settings = ChainSyncClient.Settings(),
      blockFetch: BlockFetchClient.Settings = BlockFetchClient.Settings(),
      keepAlive: KeepAliveClient.Settings = KeepAliveClient.Settings(),
      offerPeerSharing: Boolean = false,
      maxVersion: NtnVersion = NtnVersion.LatestReleased,
      peerSharing: PeerSharingClient.Settings = PeerSharingClient.Settings()
  ):
    /** The ingress limits for a ChainSync client that pipelines `requests` requests at once: the
      * reference's high mark of 300 (`chainSyncPipeliningHighMark`) is the least, since the limit
      * of the ChainSync queue is derived from how many replies a peer may have in flight.
      */
    def pipelining(requests: Int): Settings =
      copy(mux =
        mux.copy(ingressLimit =
          IngressLimits.nodeToNode(
            IngressLimits.Parameters(chainSyncPipeliningHighMark = requests.max(300))
          )
        )
      )

  /** Connect over TCP, run the handshake and start the mini-protocols. `P` is the ChainSync header
    * payload, decoded by `codec`.
    */
  def connect[F[_]: Async, P](
      address: SocketAddress[Host],
      magic: Long,
      codec: ChainSync.PayloadCodec[P],
      settings: Settings = Settings(),
      transactions: Option[TxSubmissionClient.Source[F]] = None,
      serve: Option[ServerHandlers[F, P]] = None
  )(using Raise[F, NetworkError]): Resource[F, NodeToNodeConnection[F, P]] =
    Bearer
      .tcp[F](address, settings.connectTimeout)
      .flatMap(over(_, magic, codec, settings, transactions, serve))

  /** The same over any bearer; the tests use an in-memory one. With `transactions` the node offers
    * those transactions over TxSubmission2 for the life of the connection. With `serve` the
    * connection is offered as duplex (InitiatorAndResponder) and, if the peer agrees, our servers
    * run on it next to the clients.
    */
  def over[F[_]: Async, P](
      bearer: Bearer[F],
      magic: Long,
      codec: ChainSync.PayloadCodec[P],
      settings: Settings = Settings(),
      transactions: Option[TxSubmissionClient.Source[F]] = None,
      serve: Option[ServerHandlers[F, P]] = None
  )(using Raise[F, NetworkError]): Resource[F, NodeToNodeConnection[F, P]] =
    warmOver(bearer, magic, codec, settings, serve).flatMap(_.activate(transactions))

  /** Connect over TCP and negotiate, leaving the connection warm (KeepAlive only). */
  def connectWarm[F[_]: Async, P](
      address: SocketAddress[Host],
      magic: Long,
      codec: ChainSync.PayloadCodec[P],
      settings: Settings = Settings(),
      serve: Option[ServerHandlers[F, P]] = None
  )(using Raise[F, NetworkError]): Resource[F, WarmConnection[F, P]] =
    Bearer
      .tcp[F](address, settings.connectTimeout)
      .flatMap(warmOver(_, magic, codec, settings, serve))

  /** The handshake over any bearer, then KeepAlive: the connection is warm and the caller decides
    * when to [[WarmConnection.activate]] it.
    */
  def warmOver[F[_]: Async, P](
      bearer: Bearer[F],
      magic: Long,
      codec: ChainSync.PayloadCodec[P],
      settings: Settings = Settings(),
      serve: Option[ServerHandlers[F, P]] = None
  )(using Raise[F, NetworkError]): Resource[F, WarmConnection[F, P]] =
    val offers = Handshake.nodeToNodeOffers(
      settings.maxVersion,
      Handshake.Data(
        magic,
        initiatorOnly = serve.isEmpty,
        peerSharing = settings.offerPeerSharing
      )
    )
    for
      mux <- Mux.resource(
        bearer,
        NodeToNodeServer.Protocol,
        settings.mux.copy(duplex = serve.isDefined)
      )
      agreed <- Resource.eval(
        mux
          .channel(Protocols.Handshake)
          .flatMap(HandshakeClient.run(_, offers, settings.handshakeTimeout))
      )
      _ <- serve.filter(_ => !agreed.data.initiatorOnly).traverse_ { handlers =>
        NodeToNodeServer.start(mux, handlers, peerSharing = agreed.data.peerSharing)
      }
      warm <- warm(
        mux,
        settings.mux.role,
        agreed.version,
        codec,
        settings,
        peerSharing = agreed.data.peerSharing,
        duplex = !agreed.data.initiatorOnly
      )
    yield warm

  /** KeepAlive on an established multiplexer, as the initiator side: the warm temperature. With
    * `peerSharing` (both sides agreed to it) the PeerSharing client is there too, idle until asked.
    * It lives as long as the resource; closing it does not close the multiplexer.
    */
  def warm[F[_]: Async, P](
      mux: Mux[F],
      role: Mux.Role,
      version: Int,
      codec: ChainSync.PayloadCodec[P],
      settings: Settings = Settings(),
      peerSharing: Boolean = false,
      duplex: Boolean = false
  )(using Raise[F, NetworkError]): Resource[F, WarmConnection[F, P]] =
    for
      performance <- Resource.eval(Ref.of[F, FetchGsv.PeerGsv](FetchGsv.default))
      keepAliveChannel <- Resource.eval(mux.channel(Protocols.KeepAlive, role))
      _ <- KeepAliveClient.background(
        keepAliveChannel,
        mux,
        settings.keepAlive,
        Some(duration =>
          Async[F].monotonic.flatMap { end =>
            val endPicos = BigInt(end.toNanos) * 1000
            val measured = FetchGsv.fromSample(endPicos - BigInt(duration.toNanos) * 1000, endPicos)
            performance.update(previous => measured.update(previous))
          }
        )
      )
      sharing <- Resource.eval(
        Option
          .when(peerSharing)(())
          .traverse(_ =>
            mux
              .channel(Protocols.PeerSharing, role)
              .flatMap(PeerSharingClient(_, settings.peerSharing))
          )
      )
    yield WarmConnection(
      version,
      mux,
      role,
      codec,
      settings,
      sharing,
      duplex,
      Some(performance.get)
    )

  /** Our own clients (ChainSync, BlockFetch, KeepAlive and, with `transactions`, TxSubmission2) on
    * an established multiplexer, as the initiator side of each mini-protocol. A connection we
    * opened has them by default; on a connection the peer opened (an inbound duplex connection, see
    * [[NodeToNodeServer.accept]]) they run beside our servers, `role` being the side of the
    * mini-protocols that is ours: always `Initiator` for a client. The clients live as long as the
    * resource; closing it does not close the multiplexer.
    */
  def clients[F[_]: Async, P](
      mux: Mux[F],
      role: Mux.Role,
      version: Int,
      codec: ChainSync.PayloadCodec[P],
      settings: Settings = Settings(),
      transactions: Option[TxSubmissionClient.Source[F]] = None,
      peerSharing: Boolean = false
  )(using Raise[F, NetworkError]): Resource[F, NodeToNodeConnection[F, P]] =
    warm(mux, role, version, codec, settings, peerSharing).flatMap(_.activate(transactions))
