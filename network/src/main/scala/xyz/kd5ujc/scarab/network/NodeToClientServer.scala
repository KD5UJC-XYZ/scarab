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
  /** @param chainSync
    *   One cursor over the chain per connection, serving whole blocks.
    */
  def accept[F[_]: Async](
      bearer: Bearer[F],
      magic: Long,
      submit: Bytes => F[LocalTxSubmissionServer.Response],
      stateQuery: Option[LocalStateQueryServer.Source[F, Nothing]] = None,
      chainSync: Option[Resource[F, ChainSyncServer.Source[F, RawNtNBlock]]] = None,
      txMonitor: Option[LocalTxMonitorServer.Source[F]] = None
  )(using Raise[F, NetworkError]): F[LocalTxSubmissionServer.End] =
    acceptBackend[F, Nothing](bearer, magic, submit, stateQuery, chainSync, txMonitor)
      .map(_.fold(identity, identity))

  def acceptBackend[F[_]: Async, E](
      bearer: Bearer[F],
      magic: Long,
      submit: Bytes => F[LocalTxSubmissionServer.Response],
      stateQuery: Option[LocalStateQueryServer.Source[F, E]],
      chainSync: Option[Resource[F, ChainSyncServer.Source[F, RawNtNBlock]]] = None,
      txMonitor: Option[LocalTxMonitorServer.Source[F]] = None,
      openMux: MuxFactory[F] = MuxFactory.nodeToClient[F]
  )(using Raise[F, NetworkError]): F[Either[E, LocalTxSubmissionServer.End]] =
    openMux
      .open(
        bearer,
        protocols(
          stateQuery = stateQuery.isDefined,
          chainSync = chainSync.isDefined,
          txMonitor = txMonitor.isDefined
        )
      )
      .use(mux => acceptOn(mux, magic, submit, stateQuery, chainSync, txMonitor))

  /** Opens a connection's multiplexer over its bearer. */
  trait MuxFactory[F[_]]:
    def open(bearer: Bearer[F], protocols: Set[Int])(using
        Async[F],
        Raise[F, NetworkError]
    ): Resource[F, Mux[F]]

  object MuxFactory:
    /** The node's own: [[Mux.Config.nodeToClient]] over the mini-protocols [[protocols]] returns,
      * and the default of [[acceptBackend]]'s `openMux`. A caller that wraps this one serves the
      * production set and config, and differs from the node only in the decoder it injects.
      */
    def nodeToClient[F[_]]: MuxFactory[F] = new MuxFactory[F]:
      def open(bearer: Bearer[F], protocols: Set[Int])(using
          Async[F],
          Raise[F, NetworkError]
      ): Resource[F, Mux[F]] = Mux.resource(bearer, protocols, Mux.Config.nodeToClient)

  /** The mini-protocols a connection serves: the handshake and submission always, the others when
    * their source is given. A multiplexer handed to [[acceptOn]] is opened for these.
    */
  private[network] def protocols(
      stateQuery: Boolean,
      chainSync: Boolean,
      txMonitor: Boolean
  ): Set[Int] =
    Set(Protocols.Handshake, Protocols.LocalTxSubmission) ++
      Option.when(stateQuery)(Protocols.LocalStateQuery) ++
      Option.when(chainSync)(Protocols.LocalChainSync) ++
      Option.when(txMonitor)(Protocols.LocalTxMonitor)

  /** The connection's mini-protocols on a multiplexer already open over its bearer: the handshake,
    * then the servers, until the client finishes them or one of them fails the connection.
    * [[acceptBackend]] opens the multiplexer of a local socket and delegates here; naming the
    * multiplexer lets a test run the whole connection over one whose decoder of a mini-protocol
    * throws, which no socket and no real codec can produce.
    */
  private[network] def acceptOn[F[_]: Async, E](
      mux: Mux[F],
      magic: Long,
      submit: Bytes => F[LocalTxSubmissionServer.Response],
      stateQuery: Option[LocalStateQueryServer.Source[F, E]],
      chainSync: Option[Resource[F, ChainSyncServer.Source[F, RawNtNBlock]]] = None,
      txMonitor: Option[LocalTxMonitorServer.Source[F]] = None
  )(using Raise[F, NetworkError]): F[Either[E, LocalTxSubmissionServer.End]] =
    // the connection's protocols run in fibers of their own; each in an error scope of its own, and
    // the first to fail ends the others (`Fibers.failFast`)
    def child(program: Handle[F, NetworkError] ?=> F[Unit]): F[Either[NetworkError, Unit]] =
      Fibers.scoped[F, NetworkError, Unit](t => NetworkError.fromThrowable("node-to-client", t))(
        program
      )
    for
      agreed <- mux
        .channel(Protocols.Handshake)
        .flatMap(
          HandshakeServer.run(
            _,
            HandshakeServer.localOffers(magic),
            timeout = ProtocolDeadline.Unbounded,
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
          child(LocalTxSubmissionServer.run(submission, submit).flatMap {
            case None        => Async[F].unit
            case Some(error) => unavailable.complete(error).void *> Async[F].never[Unit]
          }),
          querying.fold(Async[F].pure(Right(()): Either[NetworkError, Unit])) { (c, source) =>
            Fibers
              .scoped[F, NetworkError, Either[E, Unit]](t =>
                NetworkError.fromThrowable("local-state-query", t)
              )(
                LocalStateQueryServer.run(c, source.forVersion(version))
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
                CardanoBlockFetch.localPayloadCodec,
                _,
                ChainSyncServer.NodeToClient
              )
            )
          }),
          child(monitoring.fold(Async[F].unit) { (c, source) =>
            LocalTxMonitorServer
              .run(c, source)
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
