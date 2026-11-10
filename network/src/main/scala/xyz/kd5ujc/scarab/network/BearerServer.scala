// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{Async, Deferred, Resource}
import cats.effect.std.{Semaphore, Supervisor}
import cats.effect.syntax.all.*
import cats.mtl.{Handle, Raise}
import cats.syntax.all.*
import com.comcast.ip4s.{IpAddress, SocketAddress}
import scala.util.control.NonFatal
import xyz.kd5ujc.scarab.codec.DomainError

/** Inbound side of the transport: a listening TCP socket configured as the reference's
  * ([[TcpSockets.listen]]), whose accepted connections are handed to one handler each, at most
  * `maxConnections` at a time (`None`: not bounded here, the caller's `beforeAccept` and connection
  * manager limit them). A handler that fails closes only its own connection and is reported to the
  * owner as a typed value. Each handler owns its error scope; cancellation releases its socket
  * without reporting a failure (ADR0017).
  */
object BearerServer:
  /** A listening socket: the address it is bound to, and a value that completes only if its accept
    * loop stops ([[listen]]).
    */
  final case class Listening[F[_]](address: SocketAddress[IpAddress], stopped: F[TcpSocketError])

  /** Listen on `address` (port 0: one the kernel chooses). The socket is opened, configured and
    * bound while the resource is acquired, a failure there raised to the caller; then the
    * connections are accepted until the resource is released. `beforeAccept`, if given, runs before
    * each accept, as the reference's accept loop runs `runConnectionRateLimits` (ouroboros-network
    * 2fb69829, `Server.hs`).
    */
  def listen[F[_], E <: DomainError](
      address: SocketAddress[IpAddress],
      maxConnections: Option[Int] = Some(64),
      beforeAccept: Option[F[Unit]] = None
  )(
      onFailure: (SocketAddress[IpAddress], HandlerFailure[E]) => F[Unit]
  )(
      handle: Handle[F, E] ?=> (Bearer[F], SocketAddress[IpAddress]) => F[Unit]
  )(using F: Async[F], raise: Raise[F, TcpSocketError]): Resource[F, Listening[F]] =
    for
      listener <- TcpSockets.listen[F](address).evalMap(Fibers.land(_))
      handlers <- Supervisor[F](await = false)
      slots <- Resource.eval(maxConnections.traverse(n => Semaphore[F](n.toLong)))
      stopped <- Resource.eval(Deferred[F, TcpSocketError])
      _ <- acceptLoop(listener, handlers, slots, beforeAccept)(onFailure)(handle)
        .flatMap(stopped.complete)
        .background
    yield Listening(listener.address, stopped.get)

  /** The accept loop of `Server.hs` 188-254: one accept at a time, each connection handed to a
    * fiber of its own; an accept that failed with `ECONNABORTED` (the client gave up before it was
    * accepted) is tried again after [[LocalServer.ConnAbortedDelay]], any other failure ends the
    * loop with its value. An exception of the loop itself ends it as a defect.
    */
  private def acceptLoop[F[_], E <: DomainError](
      listener: TcpListener[F],
      handlers: Supervisor[F],
      slots: Option[Semaphore[F]],
      beforeAccept: Option[F[Unit]]
  )(
      onFailure: (SocketAddress[IpAddress], HandlerFailure[E]) => F[Unit]
  )(
      handle: Handle[F, E] ?=> (Bearer[F], SocketAddress[IpAddress]) => F[Unit]
  )(using F: Async[F]): F[TcpSocketError] =
    val acquire = slots.traverse_(_.acquire)
    val release = slots.traverse_(_.release)

    def serve(stream: TcpStream[F]): F[Unit] =
      HandlerFailure
        .capture[F, E, Unit](handle(stream.bearer, stream.remote))
        .flatMap(_.fold(onFailure(stream.remote, _), _ => F.unit))

    // between the accept and the start of its fiber nothing may cancel, or the socket leaks; the
    // socket of a connection is released as soon as its handler ends
    val round: F[Either[Unit, TcpSocketError]] =
      beforeAccept.sequence_ *> F.uncancelable { poll =>
        poll(acquire) *> poll(listener.accept.allocated).onCancel(release).flatMap {
          case (Right(stream), close) =>
            handlers.supervise(serve(stream).guarantee(close *> release)).as(Left(()))
          case (Left(TcpSocketError.Failed(_, ErrnoCause.Known(Errno.ECONNABORTED))), close) =>
            (close *> release) *> poll(F.sleep(LocalServer.ConnAbortedDelay)).as(Left(()))
          case (Left(error), close) => (close *> release).as(Right(error))
        }
      }

    F.tailRecM(())(_ => round).recover { case NonFatal(cause) => TcpSocketError.Defect(cause) }
