// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.codec

/** What a decode shares (issue #94): equal values come out as one instance within a document, and
  * only there.
  */
class InternsSuite extends munit.FunSuite:
  private def root(hex: String): Node =
    Cbor.decode(Bytes.fromHex(hex).fold(fail(_), identity)).fold(fail(_), identity)

  private def bytes(n: Int): Bytes = Bytes.fromArray(Array(n.toByte, 7.toByte))

  private val hashes = new Interned[Bytes]
  private val others = new Interned[Bytes]

  test("equal values of one kind are one instance in a document, and unequal ones are not") {
    val items = root("83010203").items.fold(fail("not an array"))(_.toVector)
    val first = items(0).share(hashes)(bytes(1))
    val again = items(1).share(hashes)(bytes(1))
    val other = items(2).share(hashes)(bytes(2))
    assert(first eq again)
    assert(first ne other)
    assertEquals(other, bytes(2))
    assertEquals(first, bytes(1))
  }

  test("a kind has a table of its own") {
    val node = root("01")
    val a = node.share(hashes)(bytes(1))
    val b = node.share(others)(bytes(1))
    assert(a ne b)
    assert(node.share(others)(bytes(1)) eq b)
  }

  test("two documents share nothing, and a node built by hand shares nothing") {
    val x = bytes(1)
    val y = bytes(1)
    val z = bytes(1)
    assert(root("01").share(hashes)(x) eq x)
    assert(root("01").share(hashes)(y) eq y)
    val strict = Node(Value.Null, Bytes.empty)
    assert(strict.share(hashes)(x) eq x)
    assert(strict.share(hashes)(z) eq z)
  }
