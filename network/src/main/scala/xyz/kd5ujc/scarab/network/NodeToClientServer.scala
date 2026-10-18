// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.Async
import cats.mtl.{Handle, Raise}
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import cats.effect.Resource
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.NtcVersion
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
    // the connection's protocols run in fibers of their own; each in an error scope of its own, and
    // the first to fail ends the others (`Fibers.failFast`)
    def child(program: Handle[F, NetworkError] ?=> F[Unit]): F[Either[NetworkError, Unit]] =
      Fibers.scoped[F, NetworkError, Unit](t => NetworkError.fromThrowable("node-to-client", t))(
        program
      )
    Mux
      .resource(
        bearer,
        protocols,
        Mux.Config(role = Mux.Role.Responder, ingressLimit = IngressLimits.nodeToClient)
      )
      .use { mux =>
        for
          agreed <- mux
            .channel(Protocols.Handshake)
            .flatMap(
              HandshakeServer.run(
                _,
                HandshakeServer.localOffers(magic),
                suite = xyz.kd5ujc.scarab.protocol.Handshake.Suite.NodeToClient
              )
            )
          version <- NtcVersion
            .fromNumber(agreed.version)
            .fold(
              NetworkError
                .IllegalTransition(Protocols.Handshake, "unoffered node-to-client version")
                .raise[F, NtcVersion]
            )(Async[F].pure)
          submission <- mux.channel(Protocols.LocalTxSubmission)
          querying <- stateQuery
            .traverse(source => mux.channel(Protocols.LocalStateQuery).map(_ -> source))
          following <- chainSync
            .traverse(source => mux.channel(Protocols.LocalChainSync).map(_ -> source))
          monitoring <- txMonitor
            .traverse(source => mux.channel(Protocols.LocalTxMonitor).map(_ -> source))
          served = Fibers.failFast[F, NetworkError](
            List(
              child(LocalTxSubmissionServer.run(submission, submit, settings)),
              child(querying.fold(Async[F].unit) { (c, source) =>
                LocalStateQueryServer
                  .run(
                    c,
                    source.forVersion(version),
                    LocalStateQueryServer.Settings(settings.idleTimeout)
                  )
              }),
              child(following.fold(Async[F].unit) { (c, source) =>
                source.use(
                  ChainSyncServer.run(
                    c,
                    CardanoBlockFetch.payloadCodec(),
                    _,
                    ChainSyncServer.NodeToClient.copy(idleTimeout = settings.idleTimeout)
                  )
                )
              }),
              child(monitoring.fold(Async[F].unit) { (c, source) =>
                LocalTxMonitorServer
                  .run(c, source, LocalTxMonitorServer.Settings(settings.idleTimeout))
              })
            )
          )
          ended <- Async[F].race(served, mux.closed)
          reason <- ended.fold(
            result => Fibers.land[F, NetworkError, Unit](result).as("client finished"),
            closed =>
              closed match
                case defect: NetworkError.Defect => defect.raise[F, String]
                case other                       => Async[F].pure(other.message)
          )
        yield reason
      }
