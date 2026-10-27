// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.Monad
import cats.effect.{Async, Deferred, Resource}
import cats.effect.std.Supervisor
import cats.effect.syntax.all.*
import cats.mtl.{Handle, Raise}
import cats.syntax.all.*
import scala.concurrent.duration.*
import scala.util.control.NonFatal
import xyz.kd5ujc.scarab.codec.DomainError

/** The node-to-client server on a Unix socket, as the reference runs it: the stale socket removed
  * while the configured sockets are gathered (cardano-node 11.1.3,
  * `cardano-node/src/Cardano/Node/Configuration/Socket.hs` 200-224), then, in the network layer,
  * the socket opened, bound, checked and listened on (`withLocalSocket`, ouroboros-network
  * 2fb69829, `ouroboros-network/lib/Ouroboros/Network/Diffusion/Utils.hs` 76-136, with
  * `checkLocalSocket` of `Diffusion.hs` 966-990), and its accept loop (`Server.with`,
  * `ouroboros-network/framework/lib/Ouroboros/Network/Server.hs` 101-254) with no limit on the
  * number of clients (`AcceptedConnectionsLimit maxBound maxBound 0`, `Diffusion.hs` 336).
  */
object LocalServer:
  /** What the reference traces of its local socket and server: the local `DiffusionTracer` cases
    * (`Diffusion/Types.hs`, rendered by
    * `ouroboros-network/tracing/Ouroboros/Network/Tracing/PeerSelection.hs` 29-113) and the
    * `Server.Trace` of the local server (`Server.hs` 281-290, rendered by
    * `ouroboros-network/tracing/Ouroboros/Network/Tracing/Server.hs` 72-130).
    */
  enum Trace:
    case CreateSystemdSocketForSnocketPath(address: LocalAddress)
    case CreatedLocalSocket(address: LocalAddress)
    case ConfiguringLocalSocket(address: LocalAddress, fd: FileDescriptor)
    case ConfiguredLocalSocket(address: LocalAddress, fd: FileDescriptor)
    case ListeningLocalSocket(address: LocalAddress, fd: FileDescriptor)
    case LocalSocketUp(address: LocalAddress, fd: FileDescriptor)

    /** Others may read or write the socket; `mode` is its whole `st_mode`. */
    case InsecureLocalSocketPermissions(address: LocalAddress, mode: Int)

    /** The group or others may write the socket's directory; `mode` is the directory's `st_mode`.
      */
    case InsecureLocalSocketDirectory(address: LocalAddress, mode: Int)
    case RunLocalServer(address: LocalAddress)

    /** `TrServerStarted`. */
    case ServerStarted(addresses: List[LocalAddress])

    /** `TrAcceptConnection`. */
    case AcceptConnection(id: LocalConnectionId)

    /** `TrAcceptError`. */
    case AcceptError(error: SystemCallError)

    /** `TrServerStopped`. */
    case ServerStopped

    /** `TrServerError`. */
    case ServerError(failure: DiffusionFailure)

  /** The pause after an accept that failed with `ECONNABORTED` (`server_CONNABORTED_DELAY`,
    * `Server.hs` 81-84).
    */
  val ConnAbortedDelay: FiniteDuration = 500.millis

  /** `removeStaleLocalSocket` (`Socket.hs` 211-224): `removeFile` of the path, where nothing there
    * is fine and any other failure refuses the start.
    */
  def removeStale[F[_]: Monad](sockets: LocalSockets[F], path: String)(using
      Raise[F, SocketConfigError]
  ): F[Unit] =
    sockets.removeFile(path).flatMap {
      case Left(error) if !error.doesNotExist =>
        Raise[F, SocketConfigError].raise(SocketConfigError.LocalSocketError(path, error))
      case _ => Monad[F].unit
    }

  /** `S_IROTH | S_IWOTH` (`0o006`) and `S_IWGRP | S_IWOTH` (`0o022`). */
  private val OtherReadWrite = 0x6
  private val GroupOtherWrite = 0x12

  /** The warning for a socket whose `st_mode` is `mode`, if others may read or write it. */
  private[network] def permissions(address: LocalAddress, mode: Int): Option[Trace] =
    Option.when((mode & OtherReadWrite) != 0)(Trace.InsecureLocalSocketPermissions(address, mode))

  /** The warning for a socket directory whose `st_mode` is `mode`, if the group or others may write
    * it.
    */
  private[network] def directory(address: LocalAddress, mode: Int): Option[Trace] =
    Option.when((mode & GroupOtherWrite) != 0)(Trace.InsecureLocalSocketDirectory(address, mode))

  /** The directory of the socket at `path`, as the `parentDirectory` of `checkLocalSocket` takes
    * it: what is before the last `/`, `/` when that is empty, `.` when there is no `/`.
    */
  private[network] def parentDirectory(path: String): String =
    path.lastIndexOf('/') match
      case -1 => "."
      case 0  => "/"
      case at => path.substring(0, at)

  /** `checkLocalSocket`: warn when others may read or write the socket, then when the group or
    * others may write its directory. It only warns and sets no mode. A `getFileStatus` that fails
    * stops the network layer, naming the file it could not read.
    */
  private def checkLocalSocket[F[_]: Monad](
      sockets: LocalSockets[F],
      address: LocalAddress,
      trace: Trace => F[Unit]
  )(using Raise[F, DiffusionFailure]): F[Unit] =
    def status(path: String): F[Int] =
      sockets
        .fileMode(path)
        .flatMap(m => Fibers.land(m.leftMap(DiffusionFailure.System(_))))
    status(address.path).flatMap(m => permissions(address, m).traverse_(trace)) *>
      status(parentDirectory(address.path)).flatMap(m => directory(address, m).traverse_(trace))

  /** The server on `address`, for as long as the resource is held: the socket is set up while the
    * resource is acquired, each failure there a [[DiffusionFailure]] raised to the caller, and the
    * clients are then accepted, each served by `handle` in a fiber and an error scope of its own
    * (`onFailure` receives how it failed). The value completes only if the accept loop stops: an
    * accept failed for another reason than `ECONNABORTED`, which stops the reference's node. On
    * release the server stops and the socket is closed; its file stays, as the reference leaves it.
    */
  def resource[F[_]: Async, E <: DomainError](
      sockets: LocalSockets[F],
      address: LocalAddress,
      trace: Trace => F[Unit]
  )(
      onFailure: (LocalConnectionId, HandlerFailure[E]) => F[Unit]
  )(
      handle: Handle[F, E] ?=> (Bearer[F], LocalConnectionId) => F[Unit]
  )(using Raise[F, DiffusionFailure]): Resource[F, F[DiffusionFailure]] =
    for
      _ <- Resource.eval(trace(Trace.CreateSystemdSocketForSnocketPath(address)))
      listener <- sockets
        .open(address)
        .evalMap(opened => Fibers.land(opened.leftMap(DiffusionFailure.System(_))))
      _ <- Resource.eval(trace(Trace.CreatedLocalSocket(address)))
      _ <- Resource.eval(configure(sockets, listener, address, trace))
      _ <- Resource.eval(trace(Trace.RunLocalServer(address)))
      clients <- Supervisor[F](await = false)
      _ <- Resource.eval(trace(Trace.ServerStarted(List(address))))
      stopped <- Resource.eval(Deferred[F, DiffusionFailure])
      _ <- acceptLoop(listener, address, trace, clients)(onFailure)(handle)
        .guarantee(trace(Trace.ServerStopped))
        .flatTap(failure => trace(Trace.ServerError(failure)))
        .flatMap(stopped.complete)
        .background
    yield stopped.get

  /** The part of `withLocalSocket` between the socket and its server: bind, check, listen, each
    * step traced as the reference does. The JDK's bind already listens and its descriptor is known
    * once bound, so the trace before the bind is written after it, in the same order.
    */
  private def configure[F[_]: Monad](
      sockets: LocalSockets[F],
      listener: LocalListener[F],
      address: LocalAddress,
      trace: Trace => F[Unit]
  )(using Raise[F, DiffusionFailure]): F[Unit] =
    for
      bound <- listener.bind
      // no descriptor where the platform does not tell it: the reference's `invalidFileDescriptor`
      fd <- listener.descriptor.map(_.getOrElse(FileDescriptor.Invalid))
      _ <- trace(Trace.ConfiguringLocalSocket(address, fd))
      _ <- Fibers.land(bound)
      _ <- checkLocalSocket(sockets, address, trace)
      _ <- trace(Trace.ConfiguredLocalSocket(address, fd))
      _ <- trace(Trace.ListeningLocalSocket(address, fd))
      _ <- listener.listen.flatMap(Fibers.land(_))
      _ <- trace(Trace.LocalSocketUp(address, fd))
    yield ()

  /** The accept loop of `Server.hs` 188-254, for one socket: each client traced and handed to a
    * fiber of its own, an accept that failed traced, and then, for `ECONNABORTED` (the client gave
    * up before it was accepted), tried again after [[ConnAbortedDelay]]; any other failure ends the
    * loop with its value. An exception of the loop itself ends it as a defect. The counter that
    * names the clients counts the accepted ones.
    */
  private def acceptLoop[F[_], E <: DomainError](
      listener: LocalListener[F],
      address: LocalAddress,
      trace: Trace => F[Unit],
      clients: Supervisor[F]
  )(
      onFailure: (LocalConnectionId, HandlerFailure[E]) => F[Unit]
  )(
      handle: Handle[F, E] ?=> (Bearer[F], LocalConnectionId) => F[Unit]
  )(using F: Async[F]): F[DiffusionFailure] =
    def serve(client: LocalStream[F], id: LocalConnectionId): F[Unit] =
      HandlerFailure
        .capture[F, E, Unit](handle(client.bearer, id))
        .flatMap(_.fold(onFailure(id, _), _ => F.unit))
        .guarantee(client.close)

    // between the accept and the start of its fiber nothing may cancel, or the client leaks
    def round(accepted: Long): F[Either[Long, DiffusionFailure]] =
      F.uncancelable { poll =>
        poll(listener.accept).flatMap {
          case Right(client) =>
            val id = LocalConnectionId(address, LocalAddress(s"${address.path}@$accepted"))
            trace(Trace.AcceptConnection(id)) *>
              clients.supervise(serve(client, id)).as(Left(accepted + 1))
          case Left(error) =>
            trace(Trace.AcceptError(error)) *>
              (if error.errno.contains(Errno.ECONNABORTED) then
                 poll(F.sleep(ConnAbortedDelay)).as(Left(accepted))
               else F.pure(Right(DiffusionFailure.System(error))))
        }
      }

    F.tailRecM(0L)(round).recover { case NonFatal(cause) => DiffusionFailure.Defect(cause) }
