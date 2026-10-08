// SPDX-License-Identifier: Apache-2.0
package lab

class ReferenceHandshakeCommandSuite extends munit.FunSuite:
  test("probe parses numeric port and uint32 magic with fixed loopback") {
    val (peer, magic) = ReferenceHandshakeCommand.options(List("3001", "1082026")).toOption.get
    assertEquals(peer.canonicalAddress, "127.0.0.1")
    assertEquals(magic, 1082026L)
  }
  test("reject unsafe and ambiguous probe arguments before opening a socket") {
    List(
      List("0", "42"),
      List("65536", "42"),
      List("3001", "-1"),
      List("3001", "4294967296"),
      List("example.org", "42"),
      List("3001", "42", "extra")
    ).foreach(args => assert(ReferenceHandshakeCommand.options(args).isLeft))
  }
