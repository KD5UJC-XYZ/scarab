// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.data.EitherT
import cats.effect.{Async, Resource}
import cats.effect.std.Mutex
import cats.effect.syntax.all.*
import cats.syntax.all.*
import com.comcast.ip4s.{IpAddress, SocketAddress}
import fs2.{Chunk, Stream}
import java.io.IOException
import java.net.{InetSocketAddress, StandardProtocolFamily, StandardSocketOptions}
import java.nio.channels.{NetworkChannel, ServerSocketChannel, SocketChannel}
import scala.concurrent.duration.FiniteDuration
import xyz.kd5ujc.scarab.codec.DomainError
import xyz.kd5ujc.scarab.network.selection.DiffusionMode
import xyz.kd5ujc.scarab.protocol.ConnectionIdentityError

/** The family of a node-to-node socket address (`socketAddressType`, ouroboros-network 2fb69829,
  * `ouroboros-network/lib/Ouroboros/Network/Diffusion.hs` 95-98). A socket is opened in the family
  * of its address (`openToConnect = openSocket . socketAddrFamily`, `Snocket.hs` 378), never as the
  * JDK's default dual-stack `AF_INET6` socket.
  */
enum SocketFamily(val protocol: StandardProtocolFamily):
  case Inet extends SocketFamily(StandardProtocolFamily.INET)
  case Inet6 extends SocketFamily(StandardProtocolFamily.INET6)

object SocketFamily:
  def of(address: IpAddress): SocketFamily = address.fold(_ => Inet, _ => Inet6)

/** A setting of the reference's `configureSocket` (ouroboros-network 2fb69829,
  * `ouroboros-network/framework/lib/Ouroboros/Network/Socket.hs` 148-170), in the order it sets
  * them.
  */
enum SocketSetting:
  /** `SO_REUSEADDR` 1. */
  case ReuseAddress

  /** `SO_REUSEPORT` 1 (the reference skips it on Windows only). */
  case ReusePort

  /** `TCP_NODELAY` 1. */
  case NoDelay

  /** `SO_LINGER` on with 0 s: `close` resets the connection, and the closer keeps no TIME_WAIT. */
  case LingerZero

  /** `IPV6_V6ONLY` 1, for a socket configured with an IPv6 address. */
  case Ipv6Only

object SocketSetting:
  /** What `configureSocket sock addr` sets: every setting, and [[Ipv6Only]] only when `addr` (the
    * node's own address, not the remote's) is an IPv6 address.
    */
  def configured(family: Option[SocketFamily]): List[SocketSetting] =
    List(ReuseAddress, ReusePort, NoDelay, LingerZero) ++
      Option.when(family.contains(SocketFamily.Inet6))(Ipv6Only).toList

  /** The settings a JDK `ServerSocketChannel` takes. `TCP_NODELAY` and `SO_LINGER` are not among
    * its supported options, so the listener sets them on each accepted channel instead, which is
    * what Linux's accepted sockets inherit from the reference's listening socket.
    */
  private[network] def onListener(setting: SocketSetting): Boolean = setting match
    case NoDelay | LingerZero => false
    case _                    => true

/** What the reference's connection manager does with a fresh outbound socket before `connect`
  * (ouroboros-network 2fb69829, `ConnectionManager/Core.hs` 1472-1491): it configures the socket
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
  /** @param ipv4
    *   the node's configured IPv4 listening address, if any (`ipv4Address`)
    * @param ipv6
    *   the node's configured IPv6 listening address, if any (`ipv6Address`)
    * @param mode
    *   the diffusion mode of this connection
    */
  def of(
      remote: SocketAddress[IpAddress],
      ipv4: Option[SocketAddress[IpAddress]],
      ipv6: Option[SocketAddress[IpAddress]],
      mode: DiffusionMode
  ): OutboundPlan =
    val family = SocketFamily.of(remote.host)
    val own = family match
      case SocketFamily.Inet  => ipv4
      case SocketFamily.Inet6 => ipv6
    OutboundPlan(
      remote,
      family,
      SocketSetting.configured(own.map(a => SocketFamily.of(a.host))),
      own.filter(_ => mode == DiffusionMode.InitiatorAndResponder)
    )

/** A call on a TCP socket. */
enum SocketStep:
  case Open(family: SocketFamily)
  case Configure(setting: SocketSetting)

  /** `bind`; for a listener also `listen`, which the JDK makes in the same call. */
  case Bind(address: SocketAddress[IpAddress])
  case Connect(remote: SocketAddress[IpAddress])
  case Accept

  /** Reading the socket's addresses or options back. */
  case Inspect

/** Why a TCP socket could not be opened, configured, bound, connected or accepted. */
enum TcpSocketError extends DomainError:
  /** A call failed with an errno, read from the JDK's exception ([[Errno.of]]). */
  case Failed(step: SocketStep, cause: ErrnoCause)

  /** A setting of the reference that the JDK gives no way to apply. */
  case Unsupported(setting: SocketSetting)

  case ConnectTimeout(remote: SocketAddress[IpAddress], after: FiniteDuration)

  /** An exception that is not one of the socket's own, from a library or a bug. */
  case Defect(cause: Throwable)

/** The settings the kernel reports for a socket. */
final case class ObservedSettings(
    reuseAddress: Boolean,
    reusePort: Boolean,
    noDelay: Boolean,
    linger: Int
)

/** A connected TCP socket: its bearer, its two addresses, and a way to read its settings back. */
final case class TcpStream[F[_]](
    bearer: Bearer[F],
    local: SocketAddress[IpAddress],
    remote: SocketAddress[IpAddress],
    observed: F[Either[TcpSocketError, ObservedSettings]]
)

/** A listening TCP socket. */
trait TcpListener[F[_]]:
  /** The address it is bound to (the port the kernel chose, for port 0). */
  def address: SocketAddress[IpAddress]

  /** The next connection; the resource closes it. A cancelled accept closes the listener, as the
    * JDK can interrupt a blocking accept no other way.
    */
  def accept: Resource[F, Either[TcpSocketError, TcpStream[F]]]

/** Node-to-node TCP sockets configured as the reference configures them, on blocking JDK
  * `SocketChannel` s (prototype for issue 854; not used by the node). Every call runs on the
  * blocking pool and a blocked read, write, connect or accept is cancelled by closing its channel
  * ([[LocalSockets.interruptible]]), as the local socket's are.
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
          F.blocking(observe(channel))
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

  /** Open a socket of the address's family, apply `configureSocket sock (Just address)` as far as a
    * listening JDK channel takes it ([[SocketSetting.onListener]]), then bind and listen with the
    * reference's backlog.
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

    def accept: Resource[F, Either[TcpSocketError, TcpStream[F]]] =
      Resource
        .makeFull[F, Either[TcpSocketError, SocketChannel]](poll =>
          poll(
            LocalSockets.interruptible(
              F.blocking(call(SocketStep.Accept)(server.accept())),
              F.blocking(server.close())
            )
          )
        )(_.traverse_(channel => F.blocking(channel.close())))
        .evalMap(_.flatTraverse { channel =>
          F.blocking(onAccepted.traverse_(set(channel, _)))
            .flatMap(_.flatTraverse(_ => connected(channel)))
        })
