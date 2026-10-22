// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.syntax.all.*
import com.comcast.ip4s.{IpAddress, SocketAddress}
import xyz.kd5ujc.scarab.protocol.{ConnectionIdentityError, PeerSharing}
import xyz.kd5ujc.scarab.protocol.PeerSharing.Word32

/** The concrete connection key used by the reference's ChainSync handle Map. The reference
  * `Ouroboros.Network.ConnectionId` orders the remote address first, then the local address; this
  * identity is independent of activation order and CSJ registration.
  */
final case class ConnectionId(local: ConnectionId.Endpoint, remote: ConnectionId.Endpoint)

object ConnectionId:
  /** Native Network.Socket SockAddr fields, retaining unsigned words, IPv6 scope and flow info. */
  enum Endpoint:
    case Inet4(port: PeerSharing.Port, address: Word32)
    case Inet6(
        port: PeerSharing.Port,
        flowInfo: Word32,
        a: Word32,
        b: Word32,
        c: Word32,
        d: Word32,
        scopeId: Word32
    )

  object Endpoint:
    given Ordering[Endpoint] = Ordering.by {
      case Inet4(port, address) => (0, port.value, address.value, 0L, 0L, 0L, 0L, 0L)
      case Inet6(port, flow, a, b, c, d, scope) =>
        (1, port.value, flow.value, a.value, b.value, c.value, d.value, scope.value)
    }

    def fromSocketAddress(
        address: SocketAddress[IpAddress]
    ): Either[ConnectionIdentityError, Endpoint] =
      val scope = address.host.fold(_ => None, _.scopeId)
      for
        scopeId <- scope.fold[Either[ConnectionIdentityError, Word32]](Right(Word32.ofBits(0))) {
          value =>
            if value.isEmpty || !value.forall(c => c >= '0' && c <= '9') then
              Left(ConnectionIdentityError.NonNumericScope(value))
            else
              val n = value.foldLeft(BigInt(0))((acc, c) => acc * 10 + (c - '0'))
              Either.cond(
                n <= Word32.Max,
                Word32.ofBits(n.toInt),
                ConnectionIdentityError.ScopeOutOfRange(n)
              )
        }
        port <- PeerSharing.Port
          .from(address.port.value)
          .toRight(ConnectionIdentityError.InvalidPort(address.port.value))
        endpoint <- PeerSharingAddress
          .wire(address)
          .toRight(ConnectionIdentityError.UnsupportedAddressLength(address.host.toBytes.length))
          .map {
            case PeerSharing.Address.V4(word, _)       => Inet4(port, word)
            case PeerSharing.Address.V6(a, b, c, d, _) =>
              // Default TCP flowInfo is zero in both runtimes; the native real-socket corpus
              // verifies this. Explicit endpoints retain nonzero flowInfo for ordering tests.
              Inet6(port, Word32.ofBits(0), a, b, c, d, scopeId)
          }
      yield endpoint

  given Ordering[ConnectionId] = Ordering.by(id => (id.remote, id.local))

  def fromSocketAddresses(
      local: SocketAddress[IpAddress],
      remote: SocketAddress[IpAddress]
  ): Either[ConnectionIdentityError, ConnectionId] =
    (Endpoint.fromSocketAddress(local), Endpoint.fromSocketAddress(remote)).mapN(ConnectionId(_, _))
