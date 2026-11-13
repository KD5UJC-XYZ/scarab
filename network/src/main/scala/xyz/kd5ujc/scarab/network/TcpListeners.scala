// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{Async, Resource}
import com.comcast.ip4s.{IpAddress, SocketAddress}

/** A bound, listening TCP socket. */
trait TcpListener[F[_]]:
  /** The address it is bound to (the port the kernel chose, for port 0). */
  def address: SocketAddress[IpAddress]

  /** The next connection, or why `accept` failed. A cancelled accept closes the listener, as the
    * JDK can interrupt a blocking accept no other way.
    */
  def accept: F[Either[AcceptFailure, TcpStream[F]]]

/** Why an accept failed. The reference's `AcceptFailure` carries any exception of the call that is
  * not asynchronous (ouroboros-network 2fb69829,
  * `ouroboros-network/framework/lib/Ouroboros/Network/Snocket.hs` 170-214): here a failed system
  * call, the configuration of the accepted socket ([[TcpSockets.listen]]), or an exception that is
  * not one, a defect.
  */
enum AcceptFailure:
  case System(error: SystemCallError)

  /** Setting no-delay or linger on the accepted socket failed. The reference sets them on the
    * listening socket, whose accepted sockets inherit them; the JDK's listening channel takes
    * neither, so they are set on each accepted one.
    */
  case Configure(error: TcpSocketError)
  case Defect(cause: Throwable)

  /** The failure that ends the server's accept loop with it. */
  def diffusion: DiffusionFailure = this match
    case System(error)    => DiffusionFailure.System(error)
    case Configure(error) => DiffusionFailure.NodeToNode(error)
    case Defect(cause)    => DiffusionFailure.Defect(cause)

  def shown: String = diffusion.shown

/** Where the node-to-node server's listening sockets come from: the configured sockets of
  * [[TcpSockets]] in the node, scripted ones in tests. A socket that cannot be opened, configured
  * or bound is the resource's typed value.
  */
trait TcpListeners[F[_]]:
  def bind(address: SocketAddress[IpAddress]): Resource[F, Either[TcpSocketError, TcpListener[F]]]

object TcpListeners:
  /** The node's listening sockets, configured as the reference's ([[TcpSockets.listen]]). */
  def configured[F[_]: Async]: TcpListeners[F] = new TcpListeners[F]:
    def bind(
        address: SocketAddress[IpAddress]
    ): Resource[F, Either[TcpSocketError, TcpListener[F]]] = TcpSockets.listen[F](address)
