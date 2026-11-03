// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.Era

import PreprodDeepTx.onSmallStack

/** The reference's ledger decoders have no budget of items, string bytes or nesting (cborg and
  * `cardano-ledger-binary`; the header corpus of `HeaderDecodingReferenceSuite` shows it): what
  * bounds a block is BlockFetch's 2500000 bytes a message and, after decoding, `maxBBSize` and
  * `maxTxSize`; a header, ChainSync's 65535 bytes and `maxBHSize`. Scarab reads them as the
  * reference does, and an input nested deeper than any it reads is refused with a typed error on a
  * small stack (ADR 0011, #749).
  */
class DecodeBoundsSuite extends FunSuite:
  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.toString), identity)

  private def head(major: Int, n: Long): Bytes =
    val m = major << 5
    if n < 24 then Bytes(Vector((m | n.toInt).toByte))
    else if n < 256 then Bytes(Vector((m | 24).toByte, n.toByte))
    else if n < 65536 then Bytes(Vector((m | 25).toByte, (n >> 8).toByte, n.toByte))
    else Bytes((m | 26).toByte +: Vector(24, 16, 8, 0).map(s => (n >> s).toByte))

  private def bstr(b: Bytes): Bytes = head(2, b.size.toLong) ++ b
  private def filled(byte: Int, n: Int): Bytes = Bytes(Vector.fill(n)(byte.toByte))

  /** A Conway header of the header corpus: `[body, signature]`, definite. */
  private val header: Bytes =
    val body = Vector(
      head(0, 42),
      head(0, 1000),
      bstr(filled(0x11, 32)),
      bstr(filled(0x22, 32)),
      bstr(filled(0x33, 32)),
      head(4, 2) ++ bstr(filled(0x40, 64)) ++ bstr(filled(0x41, 80)),
      head(0, 1024),
      bstr(filled(0x55, 32)),
      head(4, 4) ++ bstr(filled(0x66, 32)) ++ head(0, 3) ++ head(0, 7) ++ bstr(filled(0x77, 64)),
      head(4, 2) ++ head(0, 10) ++ head(0, 0)
    ).foldLeft(head(4, 10))(_ ++ _)
    head(4, 2) ++ body ++ bstr(filled(0x88, 448))

  test("the Conway header built here is read") {
    assert(Header.fromRaw(Era.Conway, header).isRight)
  }

  test("a block whose transaction carries more than 1,000,000 metadata items is read") {
    // a body {0: [[h'..', 0]], 1: [[address, 2000000]], 2: 200000}, no witnesses, and auxiliary
    // data {0: [0, 0, ...]}: more items than the former budget of a block, in 1.1 MB
    val items = 1100000
    val body = head(5, 3) ++ head(0, 0) ++ head(4, 1) ++ head(4, 2) ++ bstr(filled(0x99, 32)) ++
      head(0, 0) ++ head(0, 1) ++ head(4, 1) ++ head(4, 2) ++ bstr(hex("61") ++ filled(0x44, 28)) ++
      head(0, 2000000) ++ head(0, 2) ++ head(0, 200000)
    val aux = head(5, 1) ++ head(0, 0) ++ head(4, items.toLong) ++ filled(0, items)
    val block = hex("8207") ++ head(4, 5) ++ header ++ head(4, 1) ++ body ++ head(4, 1) ++
      head(5, 0) ++ head(5, 1) ++ head(0, 0) ++ aux ++ head(4, 0)
    assert(block.size > 1000000)
    onSmallStack(Block.decodeShelley(block)) match
      case Right(b: Block.Shelley) =>
        assertEquals(b.transactions.map(_.auxiliaryData.map(_.size)), Vector(Some(aux.size)))
      case other => fail(s"not read: $other")
  }

  test("a header whose body is nested 200,000 levels deep is refused, typed, on a small stack") {
    val deep = head(4, 2) ++ Bytes(Vector.fill(200000)(0x81.toByte)) ++ head(0, 0) ++
      bstr(filled(0x88, 448))
    val read = onSmallStack(Header.fromRaw(Era.Conway, deep))
    assert(read.isLeft, s"read: $read")
    assert(onSmallStack(LeiosHeader.decode(deep)).isLeft)
  }

  /** 200,000 nested single-item lists around a zero. */
  private val nested: Bytes = Bytes(Vector.fill(200000)(0x81.toByte)) ++ head(0, 0)

  test("a body read for its bootstrap effects, a proposal nested 200,000 deep, is refused typed") {
    // a Babbage body {0: [], 1: [], 2: 0, 6: [[[...]]]}: the update proposal is read for its effects
    val body = head(5, 4) ++ head(0, 0) ++ head(4, 0) ++ head(0, 1) ++ head(4, 0) ++ head(0, 2) ++
      head(0, 0) ++ head(0, 6) ++ nested
    val read = onSmallStack(LiteLedger.effects(body, Era.Babbage))
    assert(read.isLeft, s"read: $read")
  }

  test("a Byron body whose transaction payload is nested 200,000 deep is refused typed") {
    // [txPayload, sscPayload, dlgPayload, updPayload], the first nested
    val body = head(4, 4) ++ head(4, 1) ++ nested ++ head(4, 0) ++ head(4, 0) ++ head(4, 0)
    val read = onSmallStack(byron.ByronTransport.check(body, hex("81a0"), boundary = false))
    assert(read.isLeft, s"read: $read")
  }
