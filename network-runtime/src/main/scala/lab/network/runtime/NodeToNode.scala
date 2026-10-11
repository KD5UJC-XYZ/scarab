// SPDX-License-Identifier: Apache-2.0
package lab.network.runtime

import cats.effect.{Async, Resource}
import cats.mtl.Raise
import cats.syntax.all.*
import com.comcast.ip4s.{Host, SocketAddress}
import lab.network.{ChainSync, Handshake}
import scala.concurrent.duration.*

/** A negotiated node-to-node connection. All clients share one multiplexed bearer; KeepAlive runs
  * in the background for the life of the resource.
  */
final class NodeToNodeConnection[F[_], P](
    val version: Int,
    val chainSync: ChainSyncClient[F, P],
    val blockFetch: BlockFetchClient[F],
    val mux: Mux[F]
)

object NodeToNode:
  final case class Settings(
      connectTimeout: FiniteDuration = 10.seconds,
      handshakeTimeout: FiniteDuration = Handshake.DefaultPhaseTimeout,
      mux: Mux.Config = Mux.Config(),
      chainSync: ChainSyncClient.Settings = ChainSyncClient.Settings(),
      blockFetch: BlockFetchClient.Settings = BlockFetchClient.Settings(),
      keepAlive: KeepAliveClient.Settings = KeepAliveClient.Settings()
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
    val offers = Vector(14 -> Handshake.Data(magic, initiatorOnly = serve.isEmpty))
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
        NodeToNodeServer.start(mux, handlers)
      }
      _ <- transactions.traverse_(source => TxSubmissionClient.background(mux, source))
      chainSyncChannel <- Resource.eval(mux.channel(Protocols.ChainSync))
      blockFetchChannel <- Resource.eval(mux.channel(Protocols.BlockFetch))
      keepAliveChannel <- Resource.eval(mux.channel(Protocols.KeepAlive))
      _ <- KeepAliveClient.background(keepAliveChannel, mux, settings.keepAlive)
      chainSync <- Resource.eval(ChainSyncClient(chainSyncChannel, codec, settings.chainSync))
      blockFetch <- Resource.eval(BlockFetchClient(blockFetchChannel, settings.blockFetch))
    yield NodeToNodeConnection(agreed.version, chainSync, blockFetch, mux)
