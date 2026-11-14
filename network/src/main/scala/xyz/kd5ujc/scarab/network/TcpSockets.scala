// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.data.EitherT
import cats.effect.{Async, Resource}
import cats.effect.std.Mutex
import cats.effect.syntax.all.*
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import com.comcast.ip4s.{Host, IpAddress, Ipv4Address, Ipv6Address, SocketAddress}
import fs2.{Chunk, Stream}
import java.io.IOException
import java.net.{InetSocketAddress, StandardSocketOptions}
import java.nio.channels.{NetworkChannel, ServerSocketChannel, SocketChannel}
import scala.concurrent.duration.FiniteDuration
import xyz.kd5ujc.scarab.network.selection.DiffusionMode
import xyz.kd5ujc.scarab.protocol.ConnectionIdentityError

/** The transport instantiates the shared socket vocabulary with its typed endpoints. */
type SocketFamily = xyz.kd5ujc.scarab.protocol.SocketFamily
object SocketFamily:
  export xyz.kd5ujc.scarab.protocol.SocketFamily.*
  def of(address: IpAddress): SocketFamily = address.fold(_ => Inet, _ => Inet6)

type SocketSetting = xyz.kd5ujc.scarab.protocol.SocketSetting
object SocketSetting:
  export xyz.kd5ujc.scarab.protocol.SocketSetting.*

  /** JDK listeners expose reuse options; no-delay and linger are set on accepted channels. */
  private[network] def onListener(setting: SocketSetting): Boolean = setting match
    case NoDelay | LingerZero => false
    case _                    => true

type SocketStep = xyz.kd5ujc.scarab.protocol.SocketStep[Host, SocketAddress[IpAddress]]
val SocketStep = xyz.kd5ujc.scarab.protocol.SocketStep
type TcpSocketError = xyz.kd5ujc.scarab.protocol.TcpSocketError[Host, SocketAddress[IpAddress]]
val TcpSocketError = xyz.kd5ujc.scarab.protocol.TcpSocketError

/** The node's own listening addresses, one of each family: the connection manager's `ipv4Address`
  * and `ipv6Address` (ouroboros-network c45735a5, `Diffusion.hs` 427-445,
  * `ConnectionManager/Core.hs` 1472-1491), the configured addresses as they are, port included.
  */
final case class NodeAddresses(
    ipv4: Option[SocketAddress[Ipv4Address]],
    ipv6: Option[SocketAddress[Ipv6Address]]
):
  /** The address of `family`, if the node has one. */
  def of(family: SocketFamily): Option[SocketAddress[IpAddress]] = family match
    case SocketFamily.Inet  => ipv4
    case SocketFamily.Inet6 => ipv6

object NodeAddresses:
  /** A node that does not listen: its outbound sockets are bound to nothing. */
  val none: NodeAddresses = NodeAddresses(None, None)

  /** The node listening at `address`. An IPv6 address is refused with `Unsupported(Ipv6Only)`: the
    * reference makes every IPv6 socket of the node IPv6-only, and the JDK cannot (`TcpSockets`).
    * This is an approved deviation (issue 854, owner decision of 2026-11-09); setting the option
    * through the foreign-function interface is issue 859.
    */
  def listening(address: SocketAddress[IpAddress]): Either[TcpSocketError, NodeAddresses] =
    address.host.fold(
      v4 => Right(NodeAddresses(Some(SocketAddress(v4, address.port)), None)),
      _ => Left(TcpSocketError.Unsupported(SocketSetting.Ipv6Only))
    )

/** What the reference's connection manager does with a fresh outbound socket before `connect`
  * (ouroboros-network c45735a5, `ConnectionManager/Core.hs` 1472-1491): it configures the socket
  * for the node's own address of the remote's family, and binds the socket to that address, port
  * included, only in `InitiatorAndResponder` mode.
  */
final case class OutboundPlan(
    remote: SocketAddress[IpAddress],
    family: SocketFamily,
    settings: List[SocketSetting],
    bind: Option[SocketAddress[IpAddress]]
)

object OutboundPlan:
  /** @param own
    *   the node's own listening addresses
    * @param mode
    *   the diffusion mode of this connection
    */
  def of(remote: SocketAddress[IpAddress], own: NodeAddresses, mode: DiffusionMode): OutboundPlan =
    val family = SocketFamily.of(remote.host)
    val address = own.of(family)
    OutboundPlan(
      remote,
      family,
      SocketSetting.configured(address.map(a => SocketFamily.of(a.host))),
      address.filter(_ => mode == DiffusionMode.InitiatorAndResponder)
    )

/** The settings the kernel reports for a socket. */
final case class ObservedSettings(
    reuseAddress: Boolean,
    reusePort: Boolean,
    noDelay: Boolean,
    linger: Int
)

/** A connected TCP socket: its bearer, its two addresses, a way to read its settings back, and the
  * close of its channel (which may be run more than once).
  */
final case class TcpStream[F[_]](
    bearer: Bearer[F],
    local: SocketAddress[IpAddress],
    remote: SocketAddress[IpAddress],
    observed: F[Either[TcpSocketError, ObservedSettings]],
    close: F[Unit]
)

/** Node-to-node TCP sockets configured as the reference configures them, on blocking JDK
  * `SocketChannel` s. Every call runs on the blocking pool and a blocked read, write, connect or
  * accept is cancelled by closing its channel ([[LocalSockets.interruptible]]), as the local
  * socket's are. A live connection holds one blocking-pool thread while its reader waits for bytes
  * (docs/performance.md, issue 854).
  */
object TcpSockets:
  /** The listen backlog of the reference's socket snocket (`Snocket.hs` 386). */
  val Backlog: Int = 8

  private def call[A](step: SocketStep)(io: => A): Either[TcpSocketError, A] =
    Either.catchNonFatal(io).leftMap {
      case e: IOException => TcpSocketError.Failed(step, Errno.of(e))
      case other          => TcpSocketError.Defect(other)
    }

  private def set(channel: NetworkChannel, setting: SocketSetting): Either[TcpSocketError, Unit] =
    val step = SocketStep.Configure(setting)
    setting match
      case SocketSetting.ReuseAddress =>
        call(step)(channel.setOption(StandardSocketOptions.SO_REUSEADDR, Boolean.box(true))).void
      case SocketSetting.ReusePort =>
        call(step)(channel.setOption(StandardSocketOptions.SO_REUSEPORT, Boolean.box(true))).void
      case SocketSetting.NoDelay =>
        call(step)(channel.setOption(StandardSocketOptions.TCP_NODELAY, Boolean.box(true))).void
      case SocketSetting.LingerZero =>
        call(step)(channel.setOption(StandardSocketOptions.SO_LINGER, Int.box(0))).void
      // the JDK sets IPV6_V6ONLY to 0 on every AF_INET6 socket it opens (`Net.c`, `socket0`) and
      // offers no option to set it again
      case SocketSetting.Ipv6Only => Left(TcpSocketError.Unsupported(setting))

  private def inet(
      address: java.net.SocketAddress
  ): Either[TcpSocketError, SocketAddress[IpAddress]] =
    address match
      case a: InetSocketAddress => Right(SocketAddress.fromInetSocketAddress(a))
      case other =>
        Left(TcpSocketError.Defect(new IllegalStateException(s"not an IP socket address: $other")))

  private def observe(channel: SocketChannel): Either[TcpSocketError, ObservedSettings] =
    call(SocketStep.Inspect)(
      ObservedSettings(
        channel.getOption(StandardSocketOptions.SO_REUSEADDR).booleanValue,
        channel.getOption(StandardSocketOptions.SO_REUSEPORT).booleanValue,
        channel.getOption(StandardSocketOptions.TCP_NODELAY).booleanValue,
        channel.getOption(StandardSocketOptions.SO_LINGER).intValue
      )
    )

  /** A bearer on a connected channel: the local socket's blocking reads and writes
    * ([[LocalSockets.stream]]), with the connection's identity.
    */
  private def bearer[F[_]: Async](
      channel: SocketChannel,
      writes: Mutex[F],
      local: SocketAddress[IpAddress],
      remote: SocketAddress[IpAddress]
  ): Bearer[F] =
    val inner = LocalSockets.stream(channel, writes)
    new Bearer[F]:
      override def connectionId: Option[F[Either[ConnectionIdentityError, ConnectionId]]] =
        Some(Async[F].pure(ConnectionId.fromSocketAddresses(local, remote)))
      def reads: Stream[F, Byte] = inner.reads
      def write(bytes: Chunk[Byte]): F[Unit] = inner.write(bytes)
      def close: F[Unit] = inner.close

  private def connected[F[_]](channel: SocketChannel)(using
      F: Async[F]
  ): F[Either[TcpSocketError, TcpStream[F]]] =
    F.blocking(
      (
        call(SocketStep.Inspect)(channel.getLocalAddress).flatMap(inet),
        call(SocketStep.Inspect)(channel.getRemoteAddress).flatMap(inet)
      ).tupled
    ).flatMap(_.traverse { (local, remote) =>
      Mutex[F].map(writes =>
        TcpStream(
          bearer(channel, writes, local, remote),
          local,
          remote,
          F.blocking(observe(channel)),
          F.blocking(channel.close())
        )
      )
    })

  private def opened[F[_], C <: NetworkChannel](family: SocketFamily)(open: => C)(using
      F: Async[F]
  ): Resource[F, Either[TcpSocketError, C]] =
    Resource.make(F.blocking(call(SocketStep.Open(family))(open)))(
      _.traverse_(channel => F.blocking(channel.close()))
    )

  /** Open a socket of the remote's family, apply the plan's settings in the reference's order, bind
    * it if the plan says so, and connect it, within `timeout`. The resource closes the socket,
    * which with `SO_LINGER` 0 resets the connection.
    */
  def connect[F[_]](plan: OutboundPlan, timeout: FiniteDuration)(using
      F: Async[F]
  ): Resource[F, Either[TcpSocketError, TcpStream[F]]] =
    opened(plan.family)(SocketChannel.open(plan.family.protocol)).evalMap(_.flatTraverse {
      channel =>
        val configure = F.blocking(
          plan.settings.traverse_(set(channel, _)) *>
            plan.bind.traverse_(own =>
              call(SocketStep.Bind(own))(channel.bind(own.toInetSocketAddress)).void
            )
        )
        val connecting = LocalSockets
          .interruptible(
            F.blocking(
              call(SocketStep.Connect(plan.remote))(
                channel.connect(plan.remote.toInetSocketAddress)
              ).void
            ),
            F.blocking(channel.close())
          )
          .timeoutTo(
            timeout,
            F.pure(Left(TcpSocketError.ConnectTimeout(plan.remote, timeout)))
          )
        (EitherT(configure) *> EitherT(connecting) *> EitherT(connected(channel))).value
    })

  /** [[connect]] for a caller in the network's error scope: a failure is raised as the connection's
    * `NetworkError.Connect` with its typed cause, and an exception that is not the socket's own as
    * a defect, never as an unavailable peer.
    */
  def dial[F[_]: Async](plan: OutboundPlan, timeout: FiniteDuration)(using
      Raise[F, NetworkError]
  ): Resource[F, TcpStream[F]] =
    connect[F](plan, timeout).evalMap {
      case Right(stream)                      => stream.pure[F]
      case Left(TcpSocketError.Defect(cause)) => NetworkError.Defect(cause).raise[F, TcpStream[F]]
      case Left(error) => NetworkError.Connect(plan.remote.toString, error).raise[F, TcpStream[F]]
    }

  /** Open a socket of the address's family, apply `configureSocket sock (Just address)` as far as a
    * listening JDK channel takes it ([[SocketSetting.onListener]]), then bind and listen with the
    * reference's backlog (`Diffusion/Utils.hs` 61-71).
    */
  def listen[F[_]](address: SocketAddress[IpAddress])(using
      F: Async[F]
  ): Resource[F, Either[TcpSocketError, TcpListener[F]]] =
    val family = SocketFamily.of(address.host)
    val (onListener, onAccepted) =
      SocketSetting.configured(Some(family)).partition(SocketSetting.onListener)
    opened(family)(ServerSocketChannel.open(family.protocol)).evalMap(_.flatTraverse { server =>
      F.blocking(
        onListener.traverse_(set(server, _)) *>
          call(SocketStep.Bind(address))(server.bind(address.toInetSocketAddress, Backlog)) *>
          call(SocketStep.Inspect)(server.getLocalAddress).flatMap(inet)
      ).map(_.map(bound => listener(server, bound, onAccepted)))
    })

  private def listener[F[_]](
      server: ServerSocketChannel,
      bound: SocketAddress[IpAddress],
      onAccepted: List[SocketSetting]
  )(using F: Async[F]): TcpListener[F] = new TcpListener[F]:
    def address: SocketAddress[IpAddress] = bound

    // between the accept and the stream that owns its channel nothing may cancel, and the channel
    // is closed if the stream is not made
    def accept: F[Either[AcceptFailure, TcpStream[F]]] =
      F.uncancelable { poll =>
        poll(
          LocalSockets.interruptible(
            F.blocking(Either.catchNonFatal(server.accept())),
            F.blocking(server.close())
          )
        ).flatMap {
          case Right(channel) =>
            F.blocking(onAccepted.traverse_(set(channel, _)))
              .flatMap(_.flatTraverse(_ => connected(channel)))
              .onError { case _ => F.blocking(channel.close()) }
              .flatMap {
                case Right(stream) => F.pure(stream.asRight[AcceptFailure])
                case Left(error) =>
                  val failure = error match
                    case TcpSocketError.Defect(cause) => AcceptFailure.Defect(cause)
                    case other                        => AcceptFailure.Configure(other)
                  F.blocking(channel.close()).as(failure.asLeft[TcpStream[F]])
              }
          case Left(e: IOException) =>
            F.pure(
              AcceptFailure
                .System(SystemCallError.of(SystemCall.Accept, None)(e))
                .asLeft[TcpStream[F]]
            )
          case Left(cause) => F.pure(AcceptFailure.Defect(cause).asLeft[TcpStream[F]])
        }
      }
