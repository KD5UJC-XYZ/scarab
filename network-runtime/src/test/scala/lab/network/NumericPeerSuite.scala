// SPDX-License-Identifier: Apache-2.0
package lab.network

import scala.concurrent.duration.*

class NumericPeerSuite extends munit.FunSuite:
  test("numeric spellings canonicalize by bytes and port without DNS") {
    val a = NumericPeer.checked("2001:DB8::1", 3001).toOption.get
    val b = NumericPeer.checked("2001:0db8:0:0:0:0:0:1", 3001).toOption.get
    assertEquals(a, b)
    assertEquals(a.identity, b.identity)
    assertEquals(a.canonicalAddress, "2001:0db8:0000:0000:0000:0000:0000:0001")
    assertEquals(a.socketAddress.getAddress.getAddress.toVector, a.addressBytes)
    assert(!a.socketAddress.isUnresolved)
    assertNotEquals(a, NumericPeer.checked("2001:db8::1", 3002).toOption.get)
    assertEquals(NumericPeer.checked("127.0.0.1", 1).toOption.get.family, "ipv4")
    assert(NumericPeer.checked("::1", 65535).isRight)
  }
  test("reject hosts URLs scopes unspecified multicast legacy IPv4 and mapped IPv6") {
    List(
      "localhost",
      "https://127.0.0.1",
      "127.1",
      "0177.0.0.1",
      "127.00.0.1",
      "0x7f.0.0.1",
      "2130706433",
      "127.0.0.256",
      "127.0.0.1 ",
      "[::1]",
      "fe80::1%eth0",
      "::",
      "0.0.0.0",
      "224.0.0.1",
      "239.255.255.255",
      "ff02::1",
      "::ffff:127.0.0.1",
      "::ffff:7f00:1",
      "0:0:0:0:0:ffff:7f00:1",
      "1::2::3",
      "1:2:3:4:5:6:7:8:9",
      "1:2:3:4:5:6:7",
      ":1",
      "1:",
      ":::",
      ""
    ).foreach { text =>
      assert(NumericPeer.checked(text, 3001).isLeft, text)
    }
    assert(NumericPeer.checked("127.0.0.1", 0).isLeft)
    assert(NumericPeer.checked("127.0.0.1", 65536).isLeft)
  }
  test("checked limits enforce hard caps") {
    assert(TcpLimits.checked().isRight)
    assert(TcpLimits.checked(connect = 6.seconds).isLeft)
    assert(TcpLimits.checked(read = Duration.Zero).isLeft)
    assert(TcpLimits.checked(write = 6.seconds).isLeft)
    assert(TcpLimits.checked(cleanup = 3.seconds).isLeft)
    assert(TcpLimits.checked(maxChunkBytes = 65544).isLeft)
    assert(TcpLimits.checked(threads = 5).isLeft)
  }
