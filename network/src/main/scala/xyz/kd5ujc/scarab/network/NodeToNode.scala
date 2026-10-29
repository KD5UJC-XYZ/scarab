// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.Monad
import cats.effect.{Async, Concurrent, Deferred, Ref, Resource}
import cats.mtl.Raise
import cats.mtl.syntax.all.*
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
  * @param blockFetchEnded
  *   Retire attribution to the adding BlockFetch client after its successful MsgClientDone, before
  *   another hot protocol can wait while finishing (consensus ClientInterface.hs:193).
  * @param chainSyncPipeline
  *   The owned rolling pipeline supplied by production hot activations. Test connections can retain
  *   their explicitly scripted fixed-batch client.
  */
final class NodeToNodeConnection[F[_], P](
    val version: Int,
    val chainSync: ChainSyncClient[F, P],
    val blockFetch: BlockFetchClient[F],
    val mux: Mux[F],
    val transactions: Option[TxSubmissionClient.Running[F]] = None,
    val fetchPerformance: Option[F[FetchGsv.PeerGsv]] = None,
    blockFetchEnded: Option[F[Unit]] = None,
    val chainSyncPipeline: Option[ChainSyncClient.Pipeline[F, P]] = None,
    chainSyncEnded: Option[F[Unit]] = None
):
  /** End the hot protocols on the wire, so the connection can carry a new activation: ChainSync and
    * BlockFetch say `MsgDone` where they are idle, TxSubmission2 at the peer's next blocking
    * request, waiting up to `within` for it. `false` when a protocol could not be ended (a request
    * is outstanding, or the peer never asked): the connection must be closed, not reused.
    */
  def finish(within: FiniteDuration)(using Monad[F], Raise[F, NetworkError]): F[Boolean] =
    (chainSync.idle, blockFetch.idle).tupled.flatMap {
      case (true, true) =>
        chainSync.done *> chainSyncEnded.sequence_ *> blockFetch.done *> blockFetchEnded.sequence_ *> transactions
          .fold(
            true.pure[F]
          )(_.finish(within))
      case _ => false.pure[F]
    }

  /** ChainSync has already sent MsgDone as a normal result. Drain the remaining hot protocols
    * without sending a second ChainSync message in its terminal state.
    */
  def finishAfterChainSync(
      within: FiniteDuration
  )(using Monad[F], Raise[F, NetworkError]): F[Boolean] =
    blockFetch.idle.flatMap {
      case true =>
        chainSyncEnded.sequence_ *> blockFetch.done *> blockFetchEnded.sequence_ *> transactions
          .fold(true.pure[F])(
            _.finish(within)
          )
      case false => false.pure[F]
    }

  /** End the drained hot protocols without a second deadline or a lossy Boolean result.
    * PeerStateActions owns the all-results timeout and connection release.
    */
  def finishGracefully(
      chainSyncAlreadyEnded: Boolean
  )(using Monad[F], Raise[F, NetworkError]): F[Unit] =
    chainSync.done.unlessA(chainSyncAlreadyEnded) *> chainSyncEnded.sequence_ *>
      blockFetch.done *> blockFetchEnded.sequence_ *>
      transactions.traverse_(_.terminate.flatMap(_.fold(_.raise[F, Unit], _.pure[F])))

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
final class WarmConnection[F[_], P, A] private[network] (
    val version: Int,
    val mux: Mux[F],
    role: Mux.Role,
    codec: ChainSync.PayloadCodec[P],
    settings: NodeToNode.Settings,
    val peerSharing: Option[PeerSharingClient[F]] = None,
    val duplex: Boolean = false,
    val fetchPerformance: Option[F[FetchGsv.PeerGsv]],
    val applications: ProtocolResults[F, A],
    val stopEstablished: F[Unit],
    noInitiatorResult: A
):
  /** Start the hot protocols as the initiator side of each. With `transactions` the node offers
    * those transactions over TxSubmission2 for the life of the resource. `blockFetchEnded` is a
    * resource-owned retirement hook; it retains no caller error capability.
    */
  def activate(
      transactions: Option[TxSubmissionClient.Source[F]] = None,
      blockFetchEnded: Option[F[Unit]] = None,
      peerTrust: ChainSyncTimeouts.PeerTrust = ChainSyncTimeouts.PeerTrust.Untrustable,
      chainSyncResult: Option[ProtocolResults.Run[F, A] => F[Unit]] = None
  )(using
      Async[F],
      Raise[F, NetworkError]
  ): Resource[F, NodeToNodeConnection[F, P]] =
    Resource
      .eval(applications.activateHot *> applications.startHot(transactions.nonEmpty))
      .flatMap(startHotProtocols(_, transactions, blockFetchEnded, peerTrust, chainSyncResult))

  /** Start an activation whose control and result handles were installed by its status owner. */
  def startHotProtocols(
      results: ProtocolResults.Hot[F, A],
      transactions: Option[TxSubmissionClient.Source[F]],
      blockFetchEnded: Option[F[Unit]],
      peerTrust: ChainSyncTimeouts.PeerTrust,
      chainSyncResult: Option[ProtocolResults.Run[F, A] => F[Unit]]
  )(using Async[F], Raise[F, NetworkError]): Resource[F, NodeToNodeConnection[F, P]] =
    for
      _ <- Resource.eval(chainSyncResult.traverse_(_(results.chainSync)))
      running <- (transactions, results.transactions).tupled.traverse { (source, result) =>
        TxSubmissionClient.backgroundFinishing(
          mux,
          source,
          role = Some(role),
          completed = Some(outcome => result.complete(outcome.as(noInitiatorResult))),
          termination = Some(applications.awaitTermination(ProtocolResults.Temperature.Hot))
        )
      }
      chainSyncChannel <- Resource.eval(mux.channel(Protocols.ChainSync, role))
      blockFetchChannel <- Resource.eval(mux.channel(Protocols.BlockFetch, role))
      failure <- Resource.eval(NodeToNode.FirstFailure.create[F])
      ended = (result: ProtocolResults.Run[F, A]) =>
        (error: NetworkError) =>
          failure.ended(error).flatMap(cause => result.complete(Left(cause))) *> mux.abort(error)
      chainSync <- ChainSyncClient.resource(
        chainSyncChannel,
        codec,
        ended(results.chainSync),
        settings.chainSync.withPeerTrust(peerTrust),
        maxOutstanding = settings.chainSync.pipelineCapacity
      )
      blockFetch <- BlockFetchClient.resource(
        blockFetchChannel,
        ended(results.blockFetch),
        settings.blockFetch,
        completed = Some(results.blockFetch.complete(Right(noInitiatorResult)))
      )
    yield NodeToNodeConnection(
      version,
      chainSync,
      blockFetch,
      mux,
      running,
      fetchPerformance,
      blockFetchEnded,
      Some(chainSync),
      Option.when(chainSyncResult.isEmpty)(results.chainSync.complete(Right(noInitiatorResult)))
    )

object NodeToNode:

  /** The first failure of a hot protocol is the connection's, as the reference's mux status becomes
    * `Failed e` when a mini-protocol throws `e`. A protocol that is only cancelled after it (its
    * own end is the plain `Aborted` of a cancellation) reports that cause, as a mini-protocol still
    * running then completes with `Shutdown (Just e)` (ouroboros-network 2fb69829
    * network-mux/src/Network/Mux.hs:459–464, 800–812); a protocol's own failure reports itself.
    * Without it the connection's monitor, which takes the lowest-numbered finished protocol, would
    * take a cancelled ChainSync's `Aborted` for the peer's fault that BlockFetch met (issue 597).
    */
  private[network] final class FirstFailure[F[_]: Concurrent] private (
      first: Deferred[F, NetworkError]
  ):
    def ended(error: NetworkError): F[NetworkError] = error match
      case NetworkError.BearerClosed(NetworkError.Closure.Aborted) =>
        first.tryGet.map(_.getOrElse(error))
      case cause => first.complete(cause).as(cause)

  private[network] object FirstFailure:
    def create[F[_]: Concurrent]: F[FirstFailure[F]] =
      Deferred[F, NetworkError].map(new FirstFailure(_))

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
      copy(
        chainSync = chainSync.copy(pipelineCapacity = requests.max(1)),
        mux = mux.copy(ingressLimit =
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
  )(using Raise[F, NetworkError]): Resource[F, WarmConnection[F, P, Unit]] =
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
  )(using Raise[F, NetworkError]): Resource[F, WarmConnection[F, P, Unit]] =
    warmOverWithResults(bearer, magic, codec, (), settings, serve)

  /** Establish the application bundle with the caller's typed initiator result and exit policy. */
  def warmOverWithResults[F[_]: Async, P, A](
      bearer: Bearer[F],
      magic: Long,
      codec: ChainSync.PayloadCodec[P],
      noInitiatorResult: A,
      settings: Settings = Settings(),
      serve: Option[ServerHandlers[F, P]] = None
  )(using Raise[F, NetworkError]): Resource[F, WarmConnection[F, P, A]] =
    val offers = Handshake.nodeToNodeOffers(
      settings.maxVersion,
      Handshake.Data(
        magic,
        initiatorOnly = serve.isEmpty,
        peerSharing = settings.offerPeerSharing
      )
    )
    for
      mux <- Mux.handshakeResource(
        bearer,
        NodeToNodeServer.Protocol,
        settings.mux.copy(duplex = serve.isDefined)
      )
      agreed <- Resource.eval(
        mux
          .channel(Protocols.Handshake)
          .flatMap(HandshakeClient.run(_, offers, settings.handshakeTimeout))
      )
      _ <- serve
        .filter(_ => !agreed.data.initiatorOnly)
        .fold(
          Resource.eval(
            mux.installApplication(
              NodeToNodeServer.applicationProtocols(agreed.data.peerSharing),
              Set.empty
            )
          )
        ) { handlers =>
          NodeToNodeServer.start(
            mux,
            handlers,
            peerSharing = agreed.data.peerSharing,
            initiators = true
          )
        }
      warm <- warmWithResults(
        mux,
        settings.mux.role,
        agreed.version,
        codec,
        settings,
        peerSharing = agreed.data.peerSharing,
        duplex = !agreed.data.initiatorOnly,
        noInitiatorResult = noInitiatorResult
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
  )(using Raise[F, NetworkError]): Resource[F, WarmConnection[F, P, Unit]] =
    warmWithResults(mux, role, version, codec, settings, peerSharing, duplex, ())

  private def warmWithResults[F[_]: Async, P, A](
      mux: Mux[F],
      role: Mux.Role,
      version: Int,
      codec: ChainSync.PayloadCodec[P],
      settings: Settings,
      peerSharing: Boolean,
      duplex: Boolean,
      noInitiatorResult: A
  )(using Raise[F, NetworkError]): Resource[F, WarmConnection[F, P, A]] =
    for
      performance <- Resource.eval(Ref.of[F, FetchGsv.PeerGsv](FetchGsv.default))
      applications <- Resource.eval(ProtocolResults.create[F, A])
      keepAliveResult <- Resource.eval(
        applications.start(Protocols.KeepAlive, ProtocolResults.Temperature.Established)
      )
      keepAliveChannel <- Resource.eval(mux.channel(Protocols.KeepAlive, role))
      _ <- KeepAliveClient.controlled(
        keepAliveChannel,
        mux,
        settings.keepAlive,
        Some(duration =>
          Async[F].monotonic.flatMap { end =>
            val endPicos = BigInt(end.toNanos) * 1000
            val measured = FetchGsv.fromSample(endPicos - BigInt(duration.toNanos) * 1000, endPicos)
            performance.update(previous => measured.update(previous))
          }
        ),
        outcome => keepAliveResult.complete(outcome.as(noInitiatorResult)),
        applications.control(ProtocolResults.Temperature.Established),
        applications.awaitTermination(ProtocolResults.Temperature.Established)
      )
      sharing <- Resource.eval(
        Option
          .when(peerSharing)(())
          .traverse(_ =>
            mux
              .channel(Protocols.PeerSharing, role)
              .flatMap(PeerSharingClient(_, settings.peerSharing))
              .flatMap(client =>
                applications
                  .start(Protocols.PeerSharing, ProtocolResults.Temperature.Established)
                  .map { result =>
                    new PeerSharingClient[F]:
                      def request(amount: xyz.kd5ujc.scarab.protocol.PeerSharing.Amount)(using
                          Raise[F, NetworkError]
                      ) =
                        Fibers
                          .scoped[F, NetworkError, Vector[
                            xyz.kd5ujc.scarab.protocol.PeerSharing.Address
                          ]](NetworkError.Defect(_))(
                            client.request(amount)
                          )
                          .flatMap {
                            case Right(peers) => Async[F].pure(peers)
                            case Left(error) =>
                              result.complete(Left(error)) *> mux.abort(error) *> error
                                .raise[F, Vector[xyz.kd5ujc.scarab.protocol.PeerSharing.Address]]
                          }
                      def done(using Raise[F, NetworkError]) =
                        client.done *> result.complete(Right(noInitiatorResult))
                  }
              )
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
      Some(performance.get),
      applications,
      Fibers
        .scoped[F, NetworkError, Unit](NetworkError.Defect(_))(
          sharing.traverse_(_.done)
        )
        .flatMap(_.fold(mux.abort, _ => Async[F].unit)),
      noInitiatorResult
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
    warm(mux, role, version, codec, settings, peerSharing).flatMap(
      _.activate(transactions)
    )
