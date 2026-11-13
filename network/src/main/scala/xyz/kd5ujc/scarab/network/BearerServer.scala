// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{Async, Deferred, Ref, Resource}
import cats.effect.std.{Semaphore, Supervisor}
import cats.effect.syntax.all.*
import cats.mtl.Handle
import cats.syntax.all.*
import com.comcast.ip4s.{Host, IpAddress, Ipv4Address, Port, SocketAddress}
import fs2.io.net.Network
import scala.util.control.NonFatal
import xyz.kd5ujc.scarab.codec.DomainError

/** Inbound side of the transport: a listening TCP socket whose accepted connections are handed to
  * one handler each, at most `maxConnections` at a time (`None`: not bounded here, the caller's
  * `beforeAccept` and connection manager limit them). A handler that fails closes only its own
  * connection and is reported to the owner as a typed value. Each handler owns its error scope;
  * cancellation releases its socket without reporting a failure (ADR0017).
  *
  * The accept loop is the reference's (`Server.hs` 188-254, ouroboros-network 2fb69829), as
  * [[LocalServer]] runs it for the node-to-client socket: an accept that failed is traced, then,
  * for `ECONNABORTED`, tried again after [[LocalServer.ConnAbortedDelay]], and any other failure
  * ends the loop with its value, which stops the reference's node (its `DiffusionErrored`,
  * `Diffusion.hs` 905-911). The JDK's accept itself restarts on `ECONNABORTED` (OpenJDK jdk-25+36,
  * `src/java.base/unix/native/libnio/ch/Net.c` 417-427), so that case is not seen here, nor traced
  * or paused for, as the reference's is.
  */
object BearerServer:
  /** What the reference's node-to-node server traces of its accept loop (`Server.Trace`,
    * `Server.hs` 281-290, rendered by
    * `ouroboros-network/tracing/Ouroboros/Network/Tracing/Server.hs` under `Net.Server.Remote`).
    */
  enum Trace:
    /** `TrAcceptError`. */
    case AcceptError(failure: AcceptFailure)

    /** `TrServerStopped`. */
    case ServerStopped

    /** `TrServerError`. */
    case ServerError(failure: DiffusionFailure)

  /** A listening server: the address it is bound to, and a value that completes only if its accept
    * loop stops, with why.
    */
  final case class Listening[F[_]](address: SocketAddress[IpAddress], stopped: F[DiffusionFailure])

  /** Listen on `host:port` (`None`: all interfaces / an ephemeral port) with a socket of
    * `listeners`. The resource keeps accepting until it is released. `beforeAccept`, if given, runs
    * before each accept, as the reference's accept loop runs `runConnectionRateLimits`.
    */
  def listen[F[_]: Async, E <: DomainError](
      listeners: TcpListeners[F],
      host: Option[Host],
      port: Option[Port],
      trace: Trace => F[Unit],
      maxConnections: Option[Int] = Some(64),
      beforeAccept: Option[F[Unit]] = None
  )(
      onFailure: (SocketAddress[IpAddress], HandlerFailure[E]) => F[Unit]
  )(
      handle: Handle[F, E] ?=> (Bearer[F], SocketAddress[IpAddress]) => F[Unit]
  ): Resource[F, Listening[F]] =
    val dns = Network.forAsync[F].dns
    for
      requested <- Resource.eval(
        host
          .traverse(_.resolve[F](using dns, Async[F]))
          .map(ip =>
            SocketAddress(ip.getOrElse(Ipv4Address.Wildcard), port.getOrElse(Port.Wildcard))
          )
      )
      listener <- listeners.bind(requested)
      // the clients accepted and not yet closed, closed when the server is released: a handler's
      // fiber that the supervisor cancels before it starts never runs its own close
      open <- Resource.make(Ref.of[F, Map[Long, F[Unit]]](Map.empty))(
        _.get.flatMap(_.values.toList.traverse_(_.attempt.void))
      )
      clients <- Supervisor[F](await = false)
      slots <- Resource.eval(maxConnections.traverse(n => Semaphore[F](n.toLong)))
      stopped <- Resource.eval(Deferred[F, DiffusionFailure])
      // `TrServerStopped` then `TrServerError`, as the reference's `finally` within its `catch`
      // (`Server.hs` 168-175); the owner learns that the loop stopped even if a trace fails
      _ <- acceptLoop(listener, trace, clients, open, slots, beforeAccept)(onFailure)(handle)
        .onCancel(trace(Trace.ServerStopped))
        .flatMap(failure =>
          (trace(Trace.ServerStopped) *> trace(Trace.ServerError(failure)))
            .guarantee(stopped.complete(failure).void)
        )
        .background
    yield Listening(listener.address, stopped.get)

  /** One accept per round, each client handed to a fiber of its own, at most `slots` of them at a
    * time. An exception of the loop itself ends it as a defect.
    */
  private def acceptLoop[F[_], E <: DomainError](
      listener: TcpListener[F],
      trace: Trace => F[Unit],
      clients: Supervisor[F],
      open: Ref[F, Map[Long, F[Unit]]],
      slots: Option[Semaphore[F]],
      beforeAccept: Option[F[Unit]]
  )(
      onFailure: (SocketAddress[IpAddress], HandlerFailure[E]) => F[Unit]
  )(
      handle: Handle[F, E] ?=> (Bearer[F], SocketAddress[IpAddress]) => F[Unit]
  )(using F: Async[F]): F[DiffusionFailure] =
    val acquire = slots.traverse_(_.acquire)
    val release = slots.traverse_(_.release)

    // the slot is released however the close ends
    def serve(client: TcpStream[F], id: Long): F[Unit] =
      HandlerFailure
        .capture[F, E, Unit](handle(client.bearer, client.remote))
        .flatMap(_.fold(onFailure(client.remote, _), _ => F.unit))
        .guarantee(client.close.guarantee(open.update(_ - id) *> release))

    // between the accept and the start of its fiber nothing may cancel, or the client leaks;
    // `accepted` counts the clients accepted and names them in `open`
    def round(accepted: Long): F[Either[Long, DiffusionFailure]] =
      F.uncancelable { poll =>
        poll(beforeAccept.sequence_ *> acquire) *>
          poll(listener.accept).onCancel(release).flatMap {
            case Right(client) =>
              open.update(_.updated(accepted, client.close)) *>
                clients.supervise(serve(client, accepted)).as(Left(accepted + 1))
            case Left(failure) =>
              release *> trace(Trace.AcceptError(failure)) *> (failure match
                case AcceptFailure.System(error) if error.errno.contains(Errno.ECONNABORTED) =>
                  poll(F.sleep(LocalServer.ConnAbortedDelay)).as(Left(accepted))
                case _ => F.pure(Right(failure.diffusion)))
          }
      }

    F.tailRecM(0L)(round).recover { case NonFatal(cause) => DiffusionFailure.Defect(cause) }
