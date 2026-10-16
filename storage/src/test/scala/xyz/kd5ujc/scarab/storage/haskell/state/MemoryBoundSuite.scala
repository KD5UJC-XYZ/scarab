// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import java.nio.ByteBuffer
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.state.{ProtVer, Word64}
import xyz.kd5ujc.scarab.storage.haskell.state.NodeOrders.given

/** What a decode may hold in the heap (issue #94, docs/design/memory-lean-decoding.md). The build
  * runs this suite alone in a forked JVM with `-Xmx256m` (`storage / Test / testGrouping`), since a
  * heap bound is only a bound if it is real.
  *
  * The first version of the decoder built an object tree of 104 bytes an item: the 1,000,000 votes
  * of one committee (a map of `[0, hash28] => vote`, 32 MB of CBOR, the shape of the ones in the
  * epoch-188 Preprod proposals) needed 587 MiB to decode.
  */
class MemoryBoundSuite extends munit.FunSuite:
  private given ProtVer = ProtVer(Word64.fromUnsigned(10), Word64.zero)

  /** The CBOR of a committee-votes map of `n` entries in key order, 33 bytes an entry. */
  private def syntheticVotes(n: Int): Array[Byte] =
    val out = new Array[Byte](5 + 33 * n)
    val buffer = ByteBuffer.wrap(out)
    buffer.put(0xba.toByte).putInt(n)
    val hash = new Array[Byte](28)
    var i = 0
    while i < n do
      ByteBuffer.wrap(hash).putInt(20, i)
      buffer.put(0x82.toByte).put(0x00.toByte).put(0x58.toByte).put(0x1c.toByte).put(hash)
      buffer.put((i % 3).toByte)
      i += 1
    out

  test("the heap is the bound of the test") {
    assert(Runtime.getRuntime.maxMemory <= 300L * 1024 * 1024, "run in the forked JVM of the build")
  }

  test("a map of a million committee votes decodes in a 256 MiB heap") {
    val n = 1000000
    val input = Bytes.wrap(syntheticVotes(n))
    val codec = Wire.mapOf(HaskellStateCodecs.nodeCredential, HaskellStateCodecs.vote)
    val decoded = codec.decodeBytes(input, HaskellState.limits).fold(e => fail(e.render), identity)
    assertEquals(decoded.size, n)
  }
