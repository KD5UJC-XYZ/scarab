// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import java.net.StandardProtocolFamily
import scala.concurrent.duration.FiniteDuration
import xyz.kd5ujc.scarab.codec.DomainError

/** The family of a node-to-node socket address (`socketAddressType`, ouroboros-network 2fb69829,
  * `ouroboros-network/lib/Ouroboros/Network/Diffusion.hs` 95-98). A socket is opened in the family
  * of its address (`openToConnect = openSocket . socketAddrFamily`, `Snocket.hs` 378), never as the
  * JDK's default dual-stack `AF_INET6` socket.
  */
enum SocketFamily(val protocol: StandardProtocolFamily):
  case Inet extends SocketFamily(StandardProtocolFamily.INET)
  case Inet6 extends SocketFamily(StandardProtocolFamily.INET6)

/** A setting of the reference's `configureSocket` (ouroboros-network 2fb69829,
  * `ouroboros-network/framework/lib/Ouroboros/Network/Socket.hs` 148-170), in the order it sets
  * them.
  */
enum SocketSetting(val option: String):
  /** `SO_REUSEADDR` 1. */
  case ReuseAddress extends SocketSetting("SO_REUSEADDR")

  /** `SO_REUSEPORT` 1 (the reference skips it on Windows only). */
  case ReusePort extends SocketSetting("SO_REUSEPORT")

  /** `TCP_NODELAY` 1. */
  case NoDelay extends SocketSetting("TCP_NODELAY")

  /** `SO_LINGER` on with 0 s: `close` resets the connection, and the closer keeps no TIME_WAIT. */
  case LingerZero extends SocketSetting("SO_LINGER")

  /** `IPV6_V6ONLY` 1, for a socket configured with an IPv6 address. */
  case Ipv6Only extends SocketSetting("IPV6_V6ONLY")

object SocketSetting:
  /** What `configureSocket sock addr` sets: every setting, and [[Ipv6Only]] only when `addr` (the
    * node's own address, not the remote's) is an IPv6 address.
    */
  def configured(family: Option[SocketFamily]): List[SocketSetting] =
    List(ReuseAddress, ReusePort, NoDelay, LingerZero) ++
      Option.when(family.contains(SocketFamily.Inet6))(Ipv6Only).toList

/** A call on a TCP socket. */
enum SocketStep[+Host, +Address]:
  /** Resolving a host name before a tool's connection (the node resolves its peers itself). */
  case Resolve(host: Host)
  case Open(family: SocketFamily)
  case Configure(setting: SocketSetting)

  /** `bind`; for a listener also `listen`, which the JDK makes in the same call. */
  case Bind(address: Address)
  case Connect(remote: Address)
  case Accept

  /** Reading the socket's addresses or options back. */
  case Inspect

  /** The location the reference's `IOError` names for this call: network 3.2.8.0's, without the
    * socket's descriptor that its `connect` adds (the JDK does not tell it; issue 854), and a
    * description of the two calls that are not one of the reference's here.
    */
  def location: String = this match
    case Resolve(host) => s"resolve $host"
    case Open(_)       => SystemCall.Socket.location
    case Configure(_)  => "Network.Socket.setSockOpt"
    case Bind(_)       => SystemCall.Bind.location
    case Connect(_)    => "Network.Socket.connect"
    case Accept        => SystemCall.Accept.location
    case Inspect       => "socket inspection"

/** Why a TCP socket could not be opened, configured, bound, connected or accepted. */
enum TcpSocketError[+Host, +Address] extends DomainError:
  /** A call failed with an errno, read from the JDK's exception ([[Errno.of]]). */
  case Failed(step: SocketStep[Host, Address], cause: ErrnoCause)

  /** A setting of the reference that the JDK gives no way to apply. */
  case Unsupported(setting: SocketSetting)

  case ConnectTimeout(remote: Address, after: FiniteDuration)

  /** An exception that is not one of the socket's own, from a library or a bug. */
  case Defect(cause: Throwable)

  /** The text of the failure, as GHC shows the reference's `IOError` where there is one
    * (`<location>: <kind> (<reason>)`).
    */
  def shown: String = this match
    case Failed(step, cause)       => s"${step.location}: ${cause.shown}"
    case Unsupported(setting)      => s"${setting.option} cannot be set on a JDK socket"
    case ConnectTimeout(remote, d) => s"connect $remote: no connection within $d"
    case Defect(cause)             => cause.toString
