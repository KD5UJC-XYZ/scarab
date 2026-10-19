// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{Async, Deferred}
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
      submit: Bytes => F[LocalTxSubmissionServer.Response],
      stateQuery: Option[LocalStateQueryServer.Source[F, Nothing]] = None,
      settings: LocalTxSubmissionServer.Settings = LocalTxSubmissionServer.Settings(),
      /** One cursor over the chain per connection, serving whole blocks. */
      chainSync: Option[Resource[F, ChainSyncServer.Source[F, RawNtNBlock]]] = None,
      txMonitor: Option[LocalTxMonitorServer.Source[F]] = None
  )(using Raise[F, NetworkError]): F[LocalTxSubmissionServer.End] =
    acceptBackend[F, Nothing](bearer, magic, submit, stateQuery, settings, chainSync, txMonitor)
      .map(_.fold(identity, identity))

  def acceptBackend[F[_]: Async, E](
      bearer: Bearer[F],
      magic: Long,
      submit: Bytes => F[LocalTxSubmissionServer.Response],
      stateQuery: Option[LocalStateQueryServer.Source[F, E]],
      settings: LocalTxSubmissionServer.Settings = LocalTxSubmissionServer.Settings(),
      chainSync: Option[Resource[F, ChainSyncServer.Source[F, RawNtNBlock]]] = None,
      txMonitor: Option[LocalTxMonitorServer.Source[F]] = None
  )(using Raise[F, NetworkError]): F[Either[E, LocalTxSubmissionServer.End]] =
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
                .IllegalTransition(
                  Protocols.Handshake,
                  NetworkError.RuntimeViolation.UnofferedLocalVersion(agreed.version)
                )
                .raise[F, NtcVersion]
            )(Async[F].pure)
          unavailable <- Deferred[F, LocalTxSubmissionServer.Unavailable]
          backendFailure <- Deferred[F, E]
          submission <- mux.channel(Protocols.LocalTxSubmission)
          querying <- stateQuery
            .traverse(source => mux.channel(Protocols.LocalStateQuery).map(_ -> source))
          following <- chainSync
            .traverse(source => mux.channel(Protocols.LocalChainSync).map(_ -> source))
          monitoring <- txMonitor
            .traverse(source => mux.channel(Protocols.LocalTxMonitor).map(_ -> source))
          served = Fibers.failFast[F, NetworkError](
            List(
              child(LocalTxSubmissionServer.run(submission, submit, settings).flatMap {
                case None        => Async[F].unit
                case Some(error) => unavailable.complete(error).void *> Async[F].never[Unit]
              }),
              querying.fold(Async[F].pure(Right(()): Either[NetworkError, Unit])) { (c, source) =>
                Fibers
                  .scoped[F, NetworkError, Either[E, Unit]](t =>
                    NetworkError.fromThrowable("local-state-query", t)
                  )(
                    LocalStateQueryServer.run(
                      c,
                      source.forVersion(version),
                      LocalStateQueryServer.Settings(settings.idleTimeout)
                    )
                  )
                  .flatMap {
                    case Left(error) => Async[F].pure(Left(error))
                    case Right(Left(error)) =>
                      backendFailure.complete(error) *> Async[F].never[Either[NetworkError, Unit]]
                    case Right(Right(())) => Async[F].pure(Right(()))
                  }
              },
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
          ended <- Async[F].race(
            Async[F].race(Async[F].race(served, mux.closed), unavailable.get),
            backendFailure.get
          )
          reason <- ended match
            case Right(error) => Async[F].pure(Left(error))
            case Left(Right(error)) =>
              Async[F].pure(Right(LocalTxSubmissionServer.End.ServiceUnavailable(error)))
            case Left(Left(Left(result))) =>
              Fibers
                .land[F, NetworkError, Unit](result)
                .as(
                  Right(LocalTxSubmissionServer.End.ClientFinished)
                )
            case Left(Left(Right(defect: NetworkError.Defect))) =>
              defect.raise[F, Either[E, LocalTxSubmissionServer.End]]
            case Left(Left(Right(other))) =>
              Async[F].pure(Right(LocalTxSubmissionServer.End.ConnectionClosed(other)))
        yield reason
      }
