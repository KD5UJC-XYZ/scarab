// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.Async
import cats.mtl.Raise
import cats.effect.implicits.*
import cats.syntax.all.*
import cats.effect.Resource
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.CardanoBlockFetch
import xyz.kd5ujc.scarab.protocol.CardanoBlockFetch.RawNtNBlock

/** One inbound node-to-client connection: the handshake (versions 16 to 23), then the
  * mini-protocols the node serves (LocalChainSync, LocalTxSubmission, LocalStateQuery and
  * LocalTxMonitor), each run until the client finishes it. The connection ends when the client has
  * finished every served protocol or closes the socket.
  */
object NodeToClientServer:
  def accept[F[_]: Async](
      bearer: Bearer[F],
      magic: Long,
      submit: Bytes => F[Option[Bytes]],
      stateQuery: Option[LocalStateQueryServer.Source[F]] = None,
      settings: LocalTxSubmissionServer.Settings = LocalTxSubmissionServer.Settings(),
      /** One cursor over the chain per connection, serving whole blocks. */
      chainSync: Option[Resource[F, ChainSyncServer.Source[F, RawNtNBlock]]] = None,
      txMonitor: Option[LocalTxMonitorServer.Source[F]] = None
  )(using Raise[F, NetworkError]): F[String] =
    val protocols =
      Set(Protocols.Handshake, Protocols.LocalTxSubmission) ++
        stateQuery.map(_ => Protocols.LocalStateQuery) ++
        chainSync.map(_ => Protocols.LocalChainSync) ++
        txMonitor.map(_ => Protocols.LocalTxMonitor)
    Mux
      .resource(bearer, protocols, Mux.Config(role = Mux.Role.Responder))
      .use { mux =>
        for
          _ <- mux
            .channel(Protocols.Handshake)
            .flatMap(
              HandshakeServer.run(
                _,
                HandshakeServer.localOffers(magic),
                suite = xyz.kd5ujc.scarab.protocol.Handshake.Suite.NodeToClient
              )
            )
          submission <- mux.channel(Protocols.LocalTxSubmission)
          querying <- stateQuery.traverse(source =>
            mux
              .channel(Protocols.LocalStateQuery)
              .map(c =>
                LocalStateQueryServer
                  .run(c, source, LocalStateQueryServer.Settings(settings.idleTimeout))
              )
          )
          following <- chainSync.traverse(source =>
            mux
              .channel(Protocols.LocalChainSync)
              .map(c =>
                source.use(
                  ChainSyncServer.run(
                    c,
                    CardanoBlockFetch.payloadCodec(),
                    _,
                    ChainSyncServer.NodeToClient.copy(idleTimeout = settings.idleTimeout)
                  )
                )
              )
          )
          monitoring <- txMonitor.traverse(source =>
            mux
              .channel(Protocols.LocalTxMonitor)
              .map(c =>
                LocalTxMonitorServer
                  .run(c, source, LocalTxMonitorServer.Settings(settings.idleTimeout))
              )
          )
          served = List(
            LocalTxSubmissionServer.run(submission, submit, settings),
            querying.getOrElse(Async[F].unit),
            following.getOrElse(Async[F].unit),
            monitoring.getOrElse(Async[F].unit)
          ).parSequence_
          ended <- Async[F].race(served, mux.closed)
        yield ended.fold(_ => "client finished", identity)
      }
