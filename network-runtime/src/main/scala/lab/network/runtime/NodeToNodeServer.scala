// SPDX-License-Identifier: Apache-2.0
package lab.network.runtime

import cats.effect.{Async, Resource, Temporal}
import cats.effect.syntax.all.*
import cats.mtl.{Handle, Raise}
import cats.syntax.all.*
import lab.network.{ChainSync, Handshake}
import scala.concurrent.duration.*

/** What a node serves to its peers: its chain (ChainSync headers and BlockFetch blocks) and an
  * inbox for transactions. `chainSync` opens one cursor per connection.
  */
final class ServerHandlers[F[_], P](
    val codec: ChainSync.PayloadCodec[P],
    val chainSync: Resource[F, ChainSyncServer.Source[F, P]],
    val blocks: BlockFetchServer.Blocks[F],
    val transactions: TxSubmissionServer.Sink[F]
)

/** Node-to-node, responder side. [[start]] runs the four server mini-protocols on a multiplexer and
  * [[accept]] drives a whole inbound connection: handshake, then the servers, until the peer or a
  * protocol error closes it.
  */
object NodeToNodeServer:
  final case class Settings(
      handshakeTimeout: FiniteDuration = Handshake.DefaultPhaseTimeout,
      mux: Mux.Config = Mux.Config(role = Mux.Role.Responder),
      /** Offer InitiatorAndResponder; the connection is duplex only if the peer agrees. */
      duplex: Boolean = true,
      chainSync: ChainSyncServer.Settings = ChainSyncServer.Settings(),
      blockFetch: BlockFetchServer.Settings = BlockFetchServer.Settings(),
      keepAlive: KeepAliveServer.Settings = KeepAliveServer.Settings(),
      transactions: TxSubmissionServer.Settings = TxSubmissionServer.Settings()
  )

  val Protocol: Set[Int] = Set(
    Protocols.Handshake,
    Protocols.ChainSync,
    Protocols.BlockFetch,
    Protocols.TxSubmission2,
    Protocols.KeepAlive
  )

  /** Run the servers for the life of the resource. A failed server aborts the multiplexer, which
    * ends the connection; a client that finishes its protocol (`Done`) just ends that server.
    */
  def start[F[_]: Async, P](
      mux: Mux[F],
      handlers: ServerHandlers[F, P],
      settings: Settings = Settings()
  ): Resource[F, Unit] =
    def guarded(name: String)(program: Handle[F, NetworkError] ?=> F[Unit]): F[Unit] =
      Handle
        .allow[NetworkError](program)
        .rescue(error => mux.abort(error.message))
        .handleErrorWith(t =>
          mux.abort(s"$name: ${Option(t.getMessage).getOrElse(t.getClass.getSimpleName)}")
        )
    def channel(protocol: Int) = Resource.eval(mux.channel(protocol, Mux.Role.Responder))
    for
      chainSync <- channel(Protocols.ChainSync)
      blockFetch <- channel(Protocols.BlockFetch)
      txs <- channel(Protocols.TxSubmission2)
      keepAlive <- channel(Protocols.KeepAlive)
      _ <- guarded("chain-sync")(
        handlers.chainSync.use(
          ChainSyncServer.run(chainSync, handlers.codec, _, settings.chainSync)
        )
      ).background
      _ <- guarded("block-fetch")(
        BlockFetchServer.run(blockFetch, handlers.blocks, settings.blockFetch)
      ).background
      _ <- guarded("tx-submission")(
        TxSubmissionServer.run(txs, handlers.transactions, settings.transactions)
      ).background
      _ <- guarded("keep-alive")(KeepAliveServer.run(keepAlive, settings.keepAlive)).background
    yield ()

  /** An inbound connection: negotiate, serve, and return the reason it ended. `negotiated` runs
    * once the handshake succeeded and may use the multiplexer (a duplex connection also carries our
    * clients, see [[Mux.Config.duplex]]).
    */
  def accept[F[_]: Async, P](
      bearer: Bearer[F],
      magic: Long,
      handlers: ServerHandlers[F, P],
      settings: Settings = Settings(),
      negotiated: (HandshakeClient.Agreed, Mux[F]) => Resource[F, Unit] = (_, _: Mux[F]) =>
        Resource.unit[F]
  )(using Raise[F, NetworkError]): F[String] =
    val config = settings.mux.copy(role = Mux.Role.Responder, duplex = settings.duplex)
    Mux.resource(bearer, Protocol, config).use { mux =>
      for
        agreed <- mux
          .channel(Protocols.Handshake)
          .flatMap(
            HandshakeServer.run(
              _,
              HandshakeServer.offers(magic, settings.duplex),
              settings.handshakeTimeout
            )
          )
        reason <- (
          start(mux, handlers, settings) *> negotiated(agreed, mux)
        ).surround(mux.closed)
      yield reason
    }
