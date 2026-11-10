// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Async, Resource}
import cats.mtl.Handle
import cats.syntax.all.*
import com.comcast.ip4s.{IpAddress, SocketAddress}
import xyz.kd5ujc.scarab.network.*

/** What happened on an inbound connection, for the log. */
enum InboundEvent:
  /** The connection was accepted and admitted by the connection manager. */
  case Accepted

  /** The handshake agreed duplex: the outbound side may use the connection too. */
  case Duplex

  /** The handshake agreed initiator-only: the peer only reads from us. */
  case Unidirectional

  /** The connection ended. */
  case Closed(reason: InboundEnd)

  /** The connection manager refused the connection. */
  case Refused(reason: ConnectionError)

trait InboundEvents[F[_]]:
  def emit(remote: String, event: InboundEvent): F[Unit]

/** The inbound governor (G2): for every accepted connection it runs the handshake and serves our
  * chain, and tracks the peer's activity on it for the connection manager (`InboundGovernor.hs`,
  * ouroboros-network `2fb69829`; [[NodeToNodeServer.accept]]). It runs none of our clients: those
  * run only where the outbound governor promotes the peer, and on a duplex connection the peer
  * opened the outbound side reuses this one ([[ConnectionManager.acquire]]).
  */
final class InboundGovernor[F[_]: Async] private (
    manager: ConnectionManager[F],
    config: InboundGovernor.Config,
    handlers: ServerHandlers[F, xyz.kd5ujc.scarab.protocol.NtNHeader],
    events: Option[InboundEvents[F]],
    sharing: Option[PeerSharingRuntime[F]]
):
  private def emit(remote: SocketAddress[IpAddress], event: InboundEvent): F[Unit] =
    events.traverse_(_.emit(remote.toString, event))

  /** Serve one accepted connection until it ends; returns why it ended. Never raises. */
  def serve(bearer: Bearer[F], remote: SocketAddress[IpAddress]): F[ConnectionEnd] =
    // the connection is known by its local address too (`ConnMap.hs` 45-50)
    val local: F[Option[ConnectionId.Endpoint]] =
      bearer.connectionId.fold(Async[F].pure(Option.empty[ConnectionId.Endpoint]))(
        _.map(_.toOption.map(_.local))
      )
    local.flatMap { at =>
      Handle
        .allow[ConnectionError] {
          manager.include(remote, at).use { connection =>
            emit(remote, InboundEvent.Accepted) *>
              Handle
                .allow[NetworkError] {
                  NodeToNodeServer.accept(
                    bearer,
                    config.magic,
                    handlers,
                    connection,
                    config.server,
                    negotiated = (agreed, _) => negotiated(remote, agreed)
                  )
                }
                // an exception of the connection's own work ends that connection, reported as a
                // defect with its cause, and the node goes on: the reference's default
                // `ShutdownPeer` (network 2fb69829 RethrowPolicy.hs 61-70, 99-103; issue 567)
                .rescue(error => Async[F].pure(ConnectionEnd.fromNetwork(error)))
                .flatTap(reason => connection.close(reason))
          }
        }
        .rescue(error => emit(remote, InboundEvent.Refused(error)).as(ConnectionEnd.Refused(error)))
        .flatTap(reason => emit(remote, InboundEvent.Closed(InboundEnd.Connection(reason))))
    }

  /** After the handshake: the data flow for the log, and a duplex peer for peer sharing. */
  private def negotiated(
      remote: SocketAddress[IpAddress],
      agreed: HandshakeClient.Agreed
  ): Resource[F, Unit] =
    val duplex = !agreed.data.initiatorOnly
    Resource.eval(
      emit(remote, if duplex then InboundEvent.Duplex else InboundEvent.Unidirectional)
    ) *>
      sharing.filter(_ => duplex).traverse_(_.accepted(remote, agreed.data.peerSharing))

object InboundGovernor:
  /** The connection table as JSON (`GET /connections`). */
  def json(snapshot: ConnectionManager.Snapshot): io.circe.Json =
    import io.circe.Json
    import io.circe.syntax.*
    Json.obj(
      "counters" -> Json.obj(
        "fullDuplex" -> snapshot.counters.fullDuplex.asJson,
        "duplex" -> snapshot.counters.duplex.asJson,
        "unidirectional" -> snapshot.counters.unidirectional.asJson,
        "inbound" -> snapshot.counters.inbound.asJson,
        "outbound" -> snapshot.counters.outbound.asJson,
        "terminating" -> snapshot.counters.terminating.asJson
      ),
      "connections" -> snapshot.listed.map { l =>
        Json.obj(
          "remote" -> l.remote.toString.asJson,
          "state" -> l.state.abstractName.asJson,
          "connection" -> l.id.asJson
        )
      }.asJson
    )

  final case class Config(magic: Long, server: NodeToNodeServer.Settings)

  def create[F[_]: Async](
      manager: ConnectionManager[F],
      config: Config,
      handlers: ServerHandlers[F, xyz.kd5ujc.scarab.protocol.NtNHeader],
      events: Option[InboundEvents[F]] = None,
      sharing: Option[PeerSharingRuntime[F]] = None
  ): F[InboundGovernor[F]] =
    Async[F].pure(new InboundGovernor[F](manager, config, handlers, events, sharing))
