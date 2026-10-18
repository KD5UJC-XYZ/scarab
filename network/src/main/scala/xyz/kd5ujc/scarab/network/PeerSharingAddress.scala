// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import com.comcast.ip4s.{IpAddress, Port, SocketAddress}
import xyz.kd5ujc.scarab.protocol.PeerSharing
import xyz.kd5ujc.scarab.protocol.PeerSharing.{Address, Word32}

/** The reference's SockAddr wire convention (PeerSelection/PeerSharing/Codec.hs:29-65): IPv4 octets
  * are little endian, IPv6 words are in network order. No DNS happens here.
  */
object PeerSharingAddress:
  private def bytes(word: Word32): Vector[Byte] =
    (3 to 0 by -1).toVector.map(n => (word.value >>> (8 * n)).toByte)

  def socket(address: Address): Option[SocketAddress[IpAddress]] =
    val (octets, port) = address match
      case Address.V4(a, p)          => (bytes(a).reverse, p)
      case Address.V6(a, b, c, d, p) => (List(a, b, c, d).toVector.flatMap(bytes), p)
    for
      ip <- IpAddress.fromBytes(octets.toArray)
      p <- Port.fromInt(port.value)
    yield SocketAddress(ip, p)

  def wire(address: SocketAddress[IpAddress]): Option[Address] =
    def word(octets: Seq[Byte]): Word32 =
      Word32.ofBits(octets.foldLeft(0)((n, b) => (n << 8) | (b & 255)))
    val octets = address.host.toBytes.toVector
    PeerSharing.Port.from(address.port.value).flatMap { port =>
      octets match
        case v if v.size == 4 => Some(Address.V4(word(v.reverse), port))
        case v if v.size == 16 =>
          v.grouped(4).map(word).toList match
            case a :: b :: c :: d :: Nil => Some(Address.V6(a, b, c, d, port))
            case _                       => None
        case _ => None
    }
