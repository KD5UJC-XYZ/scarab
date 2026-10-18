// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import com.comcast.ip4s.{IpAddress, Port, SocketAddress}
import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
import xyz.kd5ujc.scarab.protocol.PeerSharing

class PeerSharingAddressSuite extends ScalaCheckSuite:
  private def socket(host: String, port: Int): SocketAddress[IpAddress] =
    SocketAddress(
      IpAddress.fromString(host).getOrElse(fail(host)),
      Port.fromInt(port).getOrElse(fail("port"))
    )

  test("real Haskell127.0.0.1 word0100007f and IPv6 network-order words") {
    val v4 = socket("127.0.0.1", 3350)
    val wire = PeerSharingAddress.wire(v4).getOrElse(fail("wire"))
    wire match
      case PeerSharing.Address.V4(a, p) =>
        assertEquals(a.value, 0x0100007fL)
        assertEquals(p.value, 3350)
      case other => fail(other.toString)
    assertEquals(PeerSharingAddress.socket(wire), Some(v4))
    val v6 = socket("2001:db8:1234:5678:90ab:cdef:1122:3344", 65535)
    PeerSharingAddress.wire(v6).getOrElse(fail("wire")) match
      case a @ PeerSharing.Address.V6(w, x, y, z, p) =>
        assertEquals(
          List(w.value, x.value, y.value, z.value),
          List(0x20010db8L, 0x12345678L, 0x90abcdefL, 0x11223344L)
        )
        assertEquals(p.value, 65535)
        assertEquals(PeerSharingAddress.socket(a), Some(v6))
      case other => fail(other.toString)
  }

  property("all IPv4/IPv6 octets and boundary ports round trip without narrowing") {
    Prop.forAll(
      Gen.oneOf(4, 16).flatMap(n => Gen.listOfN(n, Gen.choose(0, 255))),
      Gen.oneOf(0, 1, 3001, 65535)
    ) { (octets, port) =>
      val ip = IpAddress.fromBytes(octets.map(_.toByte).toArray).getOrElse(fail("IP"))
      val value = SocketAddress(ip, Port.fromInt(port).getOrElse(fail("port")))
      Prop(PeerSharingAddress.wire(value).flatMap(PeerSharingAddress.socket).contains(value))
    }
  }
