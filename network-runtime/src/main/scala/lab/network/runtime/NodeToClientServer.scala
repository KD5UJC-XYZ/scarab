// SPDX-License-Identifier: Apache-2.0
package lab.network.runtime

import cats.effect.Async
import cats.mtl.Raise
import cats.syntax.all.*
import lab.cbor.Bytes

/** One inbound node-to-client connection: the handshake (versions 16 to 23), then the
  * mini-protocols the node serves, each run until the client finishes it. The connection ends when
  * the client has finished every served protocol or closes the socket.
  */
object NodeToClientServer:
  def accept[F[_]: Async](
      bearer: Bearer[F],
      magic: Long,
      submit: Bytes => F[Option[Bytes]],
      stateQuery: Option[LocalStateQueryServer.Source[F]] = None,
      settings: LocalTxSubmissionServer.Settings = LocalTxSubmissionServer.Settings()
  )(using Raise[F, NetworkError]): F[String] =
    val protocols =
      Set(Protocols.Handshake, Protocols.LocalTxSubmission) ++
        stateQuery.map(_ => Protocols.LocalStateQuery)
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
                suite = lab.network.Handshake.Suite.NodeToClient
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
          served = Async[F].both(
            LocalTxSubmissionServer.run(submission, submit, settings),
            querying.getOrElse(Async[F].unit)
          )
          ended <- Async[F].race(served, mux.closed)
        yield ended.fold(_ => "client finished", identity)
      }
